package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.*;
import org.praxisplatform.uischema.service.base.ResourceMutationContext;
import org.praxisplatform.rules.violation.PraxisRuleViolationException;
import org.praxisplatform.uischema.command.*;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.dto.CursorPage;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

/**
 * Declarative resource adapter over the existing protected proposal and durable execution kernel.
 * Construction performs no I/O, DDL, publication or worker startup. Policy remains host-owned.
 */
public final class JpaResourceBulkEngine<ID, F> {
    private final BulkExecutionInfrastructure infrastructure;
    private final BulkOperationLifecycle lifecycle;
    private final BulkDomainResourceAdapter<ID, F> domain;
    private final BulkIdentityCodec<Object, ID> codec;
    private final BulkEditableFields fields;
    private final ObjectMapper mapper;
    private final BulkResourcePolicy policy;
    private final BulkReadCursorConfiguration cursors;
    private final Map<String, BulkDomainCommandAdapter<ID>> commands;
    private final JdbcBulkProposalStore proposals;
    private final JdbcBulkDurableExecution executions;

    @SuppressWarnings("unchecked")
    public JpaResourceBulkEngine(BulkExecutionInfrastructure infrastructure, BulkOperationLifecycle lifecycle,
            BulkDomainResourceAdapter<ID, F> domain, BulkIdentityCodec<?, ID> codec, BulkEditableFields fields,
            ObjectMapper mapper, BulkResourcePolicy policy, BulkReadCursorConfiguration cursors,
            Map<String, BulkDomainCommandAdapter<ID>> commands) {
        this.infrastructure = Objects.requireNonNull(infrastructure);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.domain = Objects.requireNonNull(domain);
        this.codec = (BulkIdentityCodec<Object, ID>) Objects.requireNonNull(codec);
        this.fields = Objects.requireNonNull(fields);
        this.mapper = Objects.requireNonNull(mapper);
        this.policy = Objects.requireNonNull(policy);
        this.cursors = Objects.requireNonNull(cursors);
        this.commands = Map.copyOf(commands);
        proposals = new JdbcBulkProposalStore(infrastructure);
        executions = new JdbcBulkDurableExecution(infrastructure, lifecycle);
    }

    public BulkProposal evaluateUniform(String operationId, BulkUniformEvaluationRequest<?, F> request) {
        @SuppressWarnings("unchecked") var typed = (BulkUniformEvaluationRequest<Object, F>) request;
        var context = context(operationId);
        var snapshot = BulkIntentSnapshot.uniform(context, codec, typed, mapper::valueToTree);
        return evaluate(snapshot, request.executionMode(), request.selection().mode());
    }

    public BulkProposal evaluateItems(String operationId, BulkItemEvaluationRequest<?> request) {
        @SuppressWarnings("unchecked") var typed = (BulkItemEvaluationRequest<Object>) request;
        return evaluate(BulkIntentSnapshot.items(context(operationId), codec, typed), request.executionMode(), BulkSelectionMode.EXPLICIT);
    }

    public <P> BulkProposal evaluateDomainCommand(String operationId, BulkCommandEvaluationRequest<P, ?, F> request) {
        requireCommand(operationId);
        @SuppressWarnings("unchecked") var typed = (BulkCommandEvaluationRequest<P, Object, F>) request;
        return evaluate(BulkIntentSnapshot.command(context(operationId), codec, typed, mapper::valueToTree, mapper::valueToTree),
                request.executionMode(), request.selection().mode());
    }

    private BulkProposal evaluate(BulkIntentSnapshot intent, BulkExecutionMode executionMode, BulkSelectionMode selectionMode) {
        requireNoTransaction();
        if (executionMode != BulkExecutionMode.SYNC) throw new IllegalArgumentException("Resource engine requires SYNC execution");
        policy.authorize(intent.context());
        var admission = lifecycle.requireReady(identity(intent.context()), executionMode, selectionMode);
        if (admission.mode() != intent.mode() || !admission.matchesScope(intent.context()) || !admission.matchesCodec(intent))
            throw new IllegalStateException("Resource bulk engine differs from its published operation");
        if (intent.mode() != BulkMode.DOMAIN_COMMAND && (admission.editableFields() == null
                || !fields.writableFields(intent.mode()).equals(admission.editableFields().writableFields(intent.mode()))
                || !fields.clearableFields(intent.mode()).equals(admission.editableFields().clearableFields(intent.mode()))))
            throw new IllegalStateException("Resource bulk fields differ from the canonical protected update contract");
        Instant now = Instant.now();
        var proposal = new BulkStoredProposal(UUID.randomUUID(), now, now.plus(admission.profile().proposalLifetime()),
                intent, admission.expectation());
        var tx = new TransactionTemplate(infrastructure.transactionManager());
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Evaluation captures one protected batch, not one mutation unit. Use the
        // store's existing capture ceiling; profile.unitDeadline still fences writes.
        tx.setTimeout(Math.toIntExact(JdbcBulkProposalStore.CAPTURE_BUDGET_CEILING.toSeconds()));
        long deadline = System.nanoTime() + JdbcBulkProposalStore.CAPTURE_BUDGET_CEILING.toNanos();
        tx.execute(status -> proposals.captureAndInsertEvaluated(selectionMode == BulkSelectionMode.QUERY ? admission : null,
                proposal, (connection, budget) -> capture(proposal, admission.maxTargets()),
                evaluation -> preview(evaluation), () -> Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))));
        return proposal(intent.context(), proposal.id());
    }

    private BulkEvaluationSnapshot capture(BulkStoredProposal proposal, int maxTargets) {
        var snapshot = proposal.snapshot();
        JsonNode intent = snapshot.intent();
        List<BulkTarget<Object>> targets = new ArrayList<>();
        if ("QUERY".equals(intent.at("/selection/mode").asText())) {
            F filter = readFilter(intent.at("/selection/filter"));
            List<ID> excluded = new ArrayList<>();
            intent.at("/selection/excludedIds").forEach(id -> excluded.add(codec.decode(codec.readWire(id))));
            for (ID id : domain.selectBulkIdentities(filter, null, excluded, maxTargets))
                targets.add(new BulkTarget<>(codec.encode(id), domain.currentVersion(id)));
        } else {
            JsonNode values = snapshot.mode() == BulkMode.PER_ITEM_UPDATE ? intent.path("items") : intent.at("/selection/targets");
            values.forEach(target -> targets.add(new BulkTarget<>(codec.readWire(target.path("id")), target.path("expectedVersion").asText())));
            if (targets.size() > maxTargets) throw new IllegalArgumentException("Bulk target limit exceeded");
            // Scope selection is checked before reading any candidate, including explicit selections.
            var requested = targets.stream().map(target -> codec.decode(target.id())).toList();
            Set<ID> allowed = new HashSet<>(domain.selectBulkIdentities(null, requested, List.of(), maxTargets));
            // Missing/denied items remain in the proposal with a non-enumerating blocked diagnostic.
            return captured(proposal, targets, allowed);
        }
        return captured(proposal, targets, null);
    }

    private BulkEvaluationSnapshot captured(BulkStoredProposal proposal, List<BulkTarget<Object>> targets, Set<ID> allowed) {
        List<BulkTargetEvidence<?>> evidence = new ArrayList<>();
        var governance = Objects.requireNonNull(policy.evaluateGovernance(proposal.snapshot().context()));
        for (var target : targets) {
            if (allowed != null && !allowed.contains(codec.decode(target.id()))) {
                evidence.add(blocked(target, "BULK_TARGET_UNAVAILABLE", ResourceCommandErrorCategory.PERMISSION));
                continue;
            }
            evidence.add(evaluateTarget(proposal.snapshot(), target, ResourceMutationContext.Stage.EVALUATION, proposal.createdAt()));
        }
        return new BulkEvaluationSnapshot(proposal, Instant.now(), evidence, governance);
    }

    /** Typed filter binding comes from the resource adapter's service type. */
    private F readFilter(JsonNode node) {
        return domain.readFilter(node);
    }

    private BulkTargetEvidence<?> evaluateTarget(BulkIntentSnapshot snapshot, BulkTarget<Object> target, ResourceMutationContext.Stage stage, Instant referenceInstant) {
        try {
            var candidate = candidate(snapshot, target, stage, referenceInstant);
            return new BulkTargetEvidence<>(target, candidate.observedVersion(), candidate.facts(), candidate.plan(), BulkTargetEligibility.executable());
        } catch (RuntimeException failure) {
            BulkUnitAdmission rejection = targetRejection(failure);
            if (rejection == null) throw failure;
            ResourceCommandErrorCategory category = rejectionCategory(rejection);
            if (failure instanceof PraxisRuleViolationException rules) {
                var diagnostics = rules.getViolations().stream().limit(16).map(violation ->
                        new ResourceCommandMessage(category, violation.code(),
                                violation.messagePtBr(), violation.targetField(), Map.of())).toList();
                return new BulkTargetEvidence<>(target, target.expectedVersion(), mapper.createObjectNode(), mapper.createObjectNode(),
                        BulkTargetEligibility.blocked(diagnostics));
            }
            String code = switch (rejection.reason()) {
                case TARGET_VERSION_CONFLICT -> "BULK_VERSION_CONFLICT";
                case TARGET_STATE_CONFLICT -> "BULK_STATE_CONFLICT";
                case TARGET_DENIED -> "BULK_TARGET_UNAVAILABLE";
                case TARGET_NOT_FOUND -> "BULK_TARGET_NOT_FOUND";
                default -> "BULK_TARGET_INVALID";
            };
            return blocked(target, code, category);
        } finally {
            domain.discardEvaluationState();
        }
    }

    private BulkTargetEvidence<?> blocked(BulkTarget<Object> target, String code, ResourceCommandErrorCategory category) {
        return new BulkTargetEvidence<>(target, target.expectedVersion(), mapper.createObjectNode(), mapper.createObjectNode(),
                BulkTargetEligibility.blocked(List.of(new ResourceCommandMessage(category, code, "Bulk target is unavailable for this change", null, Map.of()))));
    }

    private BulkDomainResourceAdapter.Candidate candidate(BulkIntentSnapshot snapshot, BulkTarget<Object> target, ResourceMutationContext.Stage stage, Instant referenceInstant) {
        ID id = codec.decode(target.id());
        if (snapshot.mode() == BulkMode.DOMAIN_COMMAND)
            return requireCommand(snapshot.context().operationRef().operationId()).evaluate(id, target.expectedVersion(), snapshot.intent().path("parameters"));
        JsonNode source = snapshot.intent();
        if (snapshot.mode() == BulkMode.PER_ITEM_UPDATE) {
            source = java.util.stream.StreamSupport.stream(source.path("items").spliterator(), false)
                    .filter(item -> codec.readWire(item.path("id")).equals(target.id())).findFirst().orElseThrow();
        }
        List<BulkFieldChange> changes = new ArrayList<>();
        source.path("changes").forEach(change -> changes.add(new BulkFieldChange(change.path("field").asText(),
                BulkChangeOperator.valueOf(change.path("operator").asText()), change.get("value"))));
        return domain.evaluate(id, target.expectedVersion(), changes, stage == ResourceMutationContext.Stage.EVALUATION
                ? ResourceMutationContext.bulkEvaluation(snapshot.mode(), referenceInstant) : ResourceMutationContext.bulkConfirmation(snapshot.mode(), referenceInstant), fields);
    }

    public BulkExecution confirm(String operationId, BulkConfirmationRequest request, String idempotencyKey) {
        requireNoTransaction();
        var context = context(operationId);
        policy.authorize(context);
        // The durable kernel checks replays before requiring a fresh READY admission.
        if (context.atomicity() == org.praxisplatform.uischema.action.ActionCollectionAtomicity.ATOMIC)
            throw new IllegalArgumentException("Resource controller declares PER_ITEM operations");
        var retained = new TransactionTemplate(infrastructure.transactionManager()).execute(status ->
                proposals.findEvaluation(context, UUID.fromString(request.proposalId())).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Bulk proposal is unavailable")));
        var reservation = executions.reserve(context, UUID.fromString(request.proposalId()), idempotencyKey,
                infrastructure.deploymentId(), retained.proposal().controlExpectation().structuralRevision(),
                () -> Instant.now().plusSeconds(Math.min(120L, Math.max(30L, 15L * retained.targets().size()))));
        Instant confirmationInstant = Instant.now();
        executions.advance(reservation, unit -> {
            var decision = Objects.requireNonNull(policy.admit(unit));
            if (decision.decision() != BulkUnitAdmission.Decision.ADMIT) return decision;
            var currentGovernance = Objects.requireNonNull(policy.evaluateGovernance(unit.originalIntent().context()));
            if (!currentGovernance.document(false).equals(unit.governance().document(false)))
                return BulkUnitAdmission.stop(BulkUnitReasonCode.COMMON_GOVERNANCE_CHANGED);
            var original = unit.targetEvidence();
            @SuppressWarnings("unchecked") var target = (BulkTarget<Object>) original.target();
            var fresh = evaluateTarget(unit.originalIntent(), target, ResourceMutationContext.Stage.CONFIRMATION, confirmationInstant);
            if (!fresh.eligibility().orElseThrow().isExecutable()) {
                var diagnostics = fresh.eligibility().orElseThrow().diagnostics();
                if (diagnostics.stream().anyMatch(message -> message.category() == ResourceCommandErrorCategory.PRECONDITION))
                    return BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
                if (diagnostics.stream().anyMatch(message -> message.category() == ResourceCommandErrorCategory.PERMISSION))
                    return BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED);
                if (diagnostics.stream().anyMatch(message -> message.category() == ResourceCommandErrorCategory.NOT_FOUND))
                    return BulkUnitAdmission.invalid(BulkUnitReasonCode.TARGET_NOT_FOUND);
                if (diagnostics.stream().anyMatch(message -> message.category() == ResourceCommandErrorCategory.CONFLICT_DEPENDENCY
                        || message.category() == ResourceCommandErrorCategory.CONFLICT_DUPLICATE))
                    return BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_STATE_CONFLICT);
                return BulkUnitAdmission.invalid(BulkUnitReasonCode.TARGET_INVALID);
            }
            if (!fresh.observedVersion().equals(original.observedVersion()))
                return BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
            if (!fresh.facts().equals(original.facts()) || !fresh.plan().equals(original.plan()))
                return BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_DEPENDENCY_CHANGED);
            return BulkUnitAdmission.admit();
        }, unit -> {
            var target = unit.targetEvidence();
            @SuppressWarnings("unchecked") ID id = codec.decode(target.target().id());
            try {
                if (unit.originalIntent().mode() == BulkMode.DOMAIN_COMMAND)
                    requireCommand(operationId).execute(id, target.observedVersion(), target.plan());
                else domain.update(id, target.observedVersion(), target.plan(), ResourceMutationContext.bulkConfirmation(unit.originalIntent().mode(), confirmationInstant));
                // Only the committed persisted revision can certify a change in the receipt.
                return target.observedVersion().equals(domain.currentVersion(id))
                        ? BulkUnitMutationResult.unchanged() : BulkUnitMutationResult.confirmed();
            } catch (RuntimeException failure) {
                BulkUnitAdmission rejection = targetRejection(failure);
                if (rejection == null) throw failure;
                throw new BulkUnitRejectedException(rejection);
            }
        });
        return execution(context, reservation.executionId());
    }

    /** Recognizes only caller-correctable target failures, never technical or common failures. */
    static BulkUnitAdmission targetRejection(RuntimeException failure) {
        if (failure instanceof ResourceVersionPreconditionException precondition)
            return precondition.status() == HttpStatus.PRECONDITION_FAILED
                    ? BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT) : null;
        if (failure instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                || failure instanceof jakarta.persistence.OptimisticLockException)
            return BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
        if (failure instanceof PraxisRuleViolationException rules) {
            if (rules.getDecision() != org.praxisplatform.rules.contract.RuleDecision.DENY) return null;
            return rules.getHttpStatus() == 409 ? BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_STATE_CONFLICT)
                    : BulkUnitAdmission.invalid(BulkUnitReasonCode.TARGET_INVALID);
        }
        if (failure instanceof BulkFieldChangeValidationException
                || failure instanceof org.praxisplatform.uischema.customfields.CustomFieldValidationException
                || failure instanceof jakarta.validation.ConstraintViolationException)
            return BulkUnitAdmission.invalid(BulkUnitReasonCode.TARGET_INVALID);
        if (failure instanceof jakarta.persistence.EntityNotFoundException)
            return BulkUnitAdmission.invalid(BulkUnitReasonCode.TARGET_NOT_FOUND);
        if (failure instanceof SecurityException) return BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED);
        if (failure instanceof ResponseStatusException response) return switch (response.getStatusCode().value()) {
            case 400, 422 -> BulkUnitAdmission.invalid(BulkUnitReasonCode.TARGET_INVALID);
            case 403 -> BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED);
            case 404 -> BulkUnitAdmission.invalid(BulkUnitReasonCode.TARGET_NOT_FOUND);
            case 409 -> BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_STATE_CONFLICT);
            case 412 -> BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
            default -> null;
        };
        return null;
    }

    private static ResourceCommandErrorCategory rejectionCategory(BulkUnitAdmission rejection) {
        return switch (rejection.reason()) {
            case TARGET_VERSION_CONFLICT -> ResourceCommandErrorCategory.PRECONDITION;
            case TARGET_STATE_CONFLICT -> ResourceCommandErrorCategory.CONFLICT_DEPENDENCY;
            case TARGET_DENIED -> ResourceCommandErrorCategory.PERMISSION;
            case TARGET_NOT_FOUND -> ResourceCommandErrorCategory.NOT_FOUND;
            default -> ResourceCommandErrorCategory.VALIDATION;
        };
    }

    /** Resolves routing only after the current global read policy has authorized the operation. */
    public String resolveProposalOperationId(BulkOperationStatusLookup lookup, String resourceKey, UUID id) {
        return lookup.proposalOperationId(resourceKey, id, policy);
    }

    /** Resolves routing only after the current global read policy has authorized the operation. */
    public String resolveExecutionOperationId(BulkOperationStatusLookup lookup, String resourceKey, UUID id) {
        return lookup.executionOperationId(resourceKey, id, policy);
    }

    public BulkProposal proposal(String operationId, UUID id) {
        return proposal(context(operationId), id);
    }
    private BulkProposal proposal(BulkFingerprintContext context, UUID id) {
        String operationId = context.operationRef().operationId();
        var observed = new BulkAuthorizedProposalReader(infrastructure, context.resourceKey(),
                policy.readAuthorization(operationId), policy.proposalProjection(operationId)).readProposal(context.subjectId(), id);
        requireComplete(observed.state().name()); return observed.proposal();
    }
    public CursorPage<BulkProposalItemResult<Object>> proposalResults(String operationId, UUID id, int size, String after) {
        var context = context(operationId);
        var observed = new BulkAuthorizedProposalResultsReader(infrastructure, context.resourceKey(),
                policy.readAuthorization(operationId), cursors).readProposalResults(context.subjectId(), id, size, after);
        requireComplete(observed.state().name()); return observed.page();
    }
    public BulkExecution execution(String operationId, UUID id) {
        return execution(context(operationId), id);
    }
    private BulkExecution execution(BulkFingerprintContext context, UUID id) {
        String operationId = context.operationRef().operationId();
        var observed = new BulkAuthorizedExecutionReader(infrastructure, context.resourceKey(),
                policy.readAuthorization(operationId)).readExecution(context.subjectId(), id);
        requireComplete(observed.state().name()); return observed.execution();
    }
    public CursorPage<BulkItemResult<Object>> executionResults(String operationId, UUID id, int size, String after) {
        var context = context(operationId);
        var observed = new BulkAuthorizedExecutionResultsReader(infrastructure, context.resourceKey(),
                policy.readAuthorization(operationId), cursors).readExecutionResults(context.subjectId(), id, size, after);
        requireComplete(observed.state().name()); return observed.page();
    }
    public BulkExecution cancel(String operationId, UUID id) {
        var context = context(operationId);
        execution(context, id); // Full current target authorization precedes cancellation.
        executions.requestCancel(context, id);
        return execution(context, id);
    }

    private BulkPreviewProjection preview(BulkEvaluationSnapshot evaluation) {
        List<BulkPreviewProjection.PublicDiagnostic> diagnostics = new ArrayList<>(List.of(
                new BulkPreviewProjection.PublicDiagnostic(ResourceCommandErrorCategory.PERMISSION, "BULK_TARGET_UNAVAILABLE", "Bulk target is unavailable for this change"),
                new BulkPreviewProjection.PublicDiagnostic(ResourceCommandErrorCategory.VALIDATION, "BULK_TARGET_INVALID", "Bulk candidate is invalid"),
                new BulkPreviewProjection.PublicDiagnostic(ResourceCommandErrorCategory.NOT_FOUND, "BULK_TARGET_NOT_FOUND", "Bulk target is no longer available"),
                new BulkPreviewProjection.PublicDiagnostic(ResourceCommandErrorCategory.CONFLICT_DEPENDENCY, "BULK_STATE_CONFLICT", "Bulk target state conflicts with this change"),
                new BulkPreviewProjection.PublicDiagnostic(ResourceCommandErrorCategory.PRECONDITION, "BULK_VERSION_CONFLICT", "Bulk target version has changed")));
        diagnostics.addAll(policy.publicDiagnostics(evaluation.proposal().snapshot().context().operationRef().operationId()));
        // A host catalog can contain many errors. The bounded public projection retains only
        // explicitly allowlisted codes actually emitted by this evaluation, with their categories.
        var used = evaluation.targets().stream().flatMap(target -> target.eligibility().orElseThrow().diagnostics().stream())
                .map(message -> message.category().name() + ":" + message.code()).collect(java.util.stream.Collectors.toSet());
        var localized = new java.util.LinkedHashMap<String, BulkPreviewProjection.PublicDiagnostic>();
        diagnostics.forEach(diagnostic -> localized.put(diagnostic.category().name() + ":" + diagnostic.code(), diagnostic));
        diagnostics = localized.entrySet().stream().filter(entry -> used.contains(entry.getKey())).map(java.util.Map.Entry::getValue).toList();
        try { return new BulkPreviewProjection(evaluation, evaluation.governance().evaluatorRevision(), diagnostics); }
        catch (IllegalArgumentException unlisted) { return BulkPreviewProjection.unavailable(evaluation); }
    }
    private BulkFingerprintContext context(String operationId) {
        var context = Objects.requireNonNull(policy.context(operationId));
        if (!infrastructure.namespace().equals(context.namespaceId()) || !operationId.equals(context.operationRef().operationId()))
            throw new IllegalStateException("Bulk policy returned a different operation or namespace");
        return context;
    }
    private BulkOperationControlIdentity identity(BulkFingerprintContext context) {
        return new BulkOperationControlIdentity(context.namespaceId(), context.operationRef().operationId());
    }
    private BulkDomainCommandAdapter<ID> requireCommand(String id) {
        var command = commands.get(id);
        if (command == null) throw new IllegalStateException("Bulk domain command adapter is required");
        return command;
    }
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Bulk HTTP orchestration must start outside transactions");
    }
    private static void requireComplete(String state) {
        if ("COMPLETE".equals(state)) return;
        HttpStatus status = switch (state) {
            case "GLOBAL_DENIED" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND_OR_DENIED" -> HttpStatus.NOT_FOUND;
            case "GONE", "TOMBSTONED" -> HttpStatus.GONE;
            case "INVALID_CURSOR" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        throw new ResponseStatusException(status, "Bulk observation is unavailable");
    }
}
