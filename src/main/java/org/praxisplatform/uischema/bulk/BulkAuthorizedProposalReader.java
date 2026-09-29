package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

/**
 * Server-side composition of an authorized, public-safe RS1 proposal.
 *
 * <p>The trusted resource binding is fixed by the host. The protected proposal, its complete
 * target set, current authorization and persisted RS2 projection are read in one Metadata-owned
 * repeatable-read, read-only snapshot. This class does not publish an endpoint, capability or
 * readiness state.</p>
 *
 * <p>When retention has removed the protected proposal, a retained tombstone can return only
 * {@link State#TOMBSTONED}, and only after the current global operation grant and the historical
 * creator scope digest both match. {@link State#GONE} is reserved for a live, retained proposal
 * whose TTL elapsed after the full target set was authorized. Tombstones do not retain targets
 * for granular re-authorization; other subjects therefore receive the same non-enumerating result
 * as an unknown proposal.</p>
 */
public final class BulkAuthorizedProposalReader {
    private static final Duration READ_BUDGET = Duration.ofSeconds(3);
    private static final long MAX_AGGREGATE_BYTES = 20L * 1024 * 1024;
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PUBLIC_DIAGNOSTICS = 64;
    private static final int MAX_REVISION_BYTES = 128;

    private final BulkExecutionInfrastructure infrastructure;
    private final String resourceKey;
    private final String operationId;
    private final String projectionRevision;
    private final BulkReadAuthorizationProvider authorization;
    private final BulkProposalProjectionProvider projection;
    private final BulkPreviewPageReader preview;
    private final Clock clock;

    public BulkAuthorizedProposalReader(BulkExecutionInfrastructure infrastructure,
            String resourceKey, BulkReadAuthorizationProvider authorization,
            BulkProposalProjectionProvider projection) {
        this(infrastructure, resourceKey, authorization, projection, Clock.systemUTC());
    }

    BulkAuthorizedProposalReader(BulkExecutionInfrastructure infrastructure,
            String resourceKey, BulkReadAuthorizationProvider authorization,
            BulkProposalProjectionProvider projection, Clock clock) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
        this.resourceKey = strictText(resourceKey, "resourceKey");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        if (authorization.executionInfrastructure() != infrastructure)
            throw new IllegalArgumentException(
                    "Read authorization must use the exact operational infrastructure instance");
        this.operationId = strictText(authorization.confirmationOperationId(),
                "confirmationOperationId");
        this.projection = Objects.requireNonNull(projection, "projection");
        if (!resourceKey.equals(projection.resourceKey())
                || !operationId.equals(projection.confirmationOperationId()))
            throw new IllegalArgumentException("Proposal projection binding does not match read authorization");
        this.projectionRevision = boundedText(projection.projectionRevision(),
                "projectionRevision", MAX_REVISION_BYTES);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.preview = new BulkPreviewPageReader(infrastructure);
    }

    public Observation readProposal(String authenticatedSubjectId, UUID proposalId) {
        strictText(authenticatedSubjectId, "authenticatedSubjectId");
        Objects.requireNonNull(proposalId, "proposalId");
        Deadline deadline = Deadline.start(READ_BUDGET);
        Observation result;
        try {
            result = infrastructure.withConsistentRead(connection -> readSnapshot(connection,
                    authenticatedSubjectId, proposalId, deadline));
        } catch (RuntimeException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
        if (deadline.expired()) return Observation.state(State.UNAVAILABLE);
        if (result.state() == State.COMPLETE && result.expiresAt != null
                && !result.expiresAt.isAfter(clock.instant()))
            return Observation.state(State.GONE);
        return result;
    }

    private Observation readSnapshot(Connection connection, String authenticatedSubjectId,
            UUID proposalId, Deadline deadline) {
        var context = new BulkReadAuthorizationProvider.Context(infrastructure.namespace(),
                resourceKey, operationId, authenticatedSubjectId);
        BulkReadAuthorizationProvider.GlobalDecision global;
        try {
            deadline.requireRemaining();
            global = authorization.preAuthorize(context, deadline.remainingForProvider());
        } catch (RuntimeException failure) {
            return Observation.state(State.GLOBAL_UNAVAILABLE);
        }
        if (global == null || global == BulkReadAuthorizationProvider.GlobalDecision.UNAVAILABLE)
            return Observation.state(State.GLOBAL_UNAVAILABLE);
        if (global == BulkReadAuthorizationProvider.GlobalDecision.DENIED)
            return Observation.state(State.GLOBAL_DENIED);

        BulkEvaluationSnapshot evaluation;
        try {
            deadline.requireRemaining();
            deadline.constrain(connection);
            var protectedRead = BulkProtectedProposalReader.readForAuthorizedComposition(connection,
                    infrastructure.namespace(), resourceKey, operationId, proposalId);
            if (protectedRead.kind() == BulkProtectedProposalReader.Kind.ABSENT) {
                // A purged proposal has no protected payload left. A retained tombstone can
                // distinguish its creator from an unrelated requester without restoring data:
                // preAuthorize above verifies the current operation grant, and this exact digest
                // comparison is the historical creator check. A mismatch remains non-enumerating.
                String terminalStatus = JdbcBulkDurableExecution.scopedProposalTombstone(connection,
                        infrastructure.namespace(), authenticatedSubjectId, resourceKey, operationId,
                        proposalId);
                return terminalStatus == null ? Observation.state(State.NOT_FOUND_OR_DENIED)
                        : Observation.state(State.TOMBSTONED);
            }
            if (protectedRead.kind() != BulkProtectedProposalReader.Kind.EVALUATED)
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            evaluation = protectedRead.evaluation();
            List<BulkReadAuthorizationProvider.Target> targets = new ArrayList<>(evaluation.targets().size());
            for (int ordinal = 0; ordinal < evaluation.targets().size(); ordinal++) {
                BulkTargetEvidence<?> target = evaluation.targets().get(ordinal);
                targets.add(new BulkReadAuthorizationProvider.Target(
                        ordinal, target.target().id(), target.facts()));
            }
            deadline.requireRemaining();
            var scoped = authorization.authorize(context,
                    evaluation.proposal().snapshot().context().subjectId(),
                    List.copyOf(targets), deadline.remainingForProvider());
            if (scoped == null
                    || scoped.state() != BulkReadAuthorizationProvider.State.AUTHORIZED_ALL
                    || scoped.scopeFingerprint() == null || scoped.scopeFingerprint().length != 32)
                return Observation.state(State.NOT_FOUND_OR_DENIED);
        } catch (SQLException | RuntimeException failure) {
            // Until the complete target set is authorized, absence and protected corruption share
            // one non-enumerating result.
            return Observation.state(State.NOT_FOUND_OR_DENIED);
        }

        Instant expiresAt = evaluation.proposal().expiresAt();
        if (!expiresAt.isAfter(clock.instant())) return Observation.state(State.GONE);
        try {
            deadline.requireRemaining();
            if (!evaluation.hasTypedEligibility()) return Observation.state(State.UNAVAILABLE);
            if (!resourceKey.equals(projection.resourceKey())
                    || !operationId.equals(projection.confirmationOperationId())
                    || !projectionRevision.equals(projection.projectionRevision()))
                return Observation.state(State.UNAVAILABLE);
            JsonNode redactedIntent = projection.projectRedactedIntent(evaluation);
            if (redactedIntent == null || !redactedIntent.isObject() || redactedIntent.isEmpty())
                return Observation.state(State.UNAVAILABLE);
            ProposalAggregate aggregate = aggregate(connection, evaluation, deadline);
            BulkProposal proposal = proposal(evaluation, redactedIntent, aggregate);
            deadline.requireRemaining();
            return Observation.complete(proposal, expiresAt);
        } catch (SQLException | RuntimeException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
    }

    private ProposalAggregate aggregate(Connection connection, BulkEvaluationSnapshot evaluation,
            Deadline deadline) throws SQLException {
        List<BulkTargetEvidence<?>> targets = evaluation.targets();
        BulkFingerprintContext scope = evaluation.proposal().snapshot().context();
        int next = -1;
        long selectedBytes = 0;
        long executable = 0;
        String persistedRevision = null;
        Map<DiagnosticKey, ResourceCommandMessage> diagnostics = new LinkedHashMap<>();
        while (next + 1 < targets.size()) {
            deadline.requireRemaining();
            deadline.constrain(connection);
            BulkPreviewPageReader.Page page = preview.read(connection, scope,
                    evaluation.proposal().id(), next, targets.size(), PAGE_SIZE);
            if (page.kind() != BulkPreviewPageReader.Kind.COMPLETE
                    || page.targetCount() != targets.size() || page.items().isEmpty())
                throw unavailable();
            if (persistedRevision == null) persistedRevision = page.projectorRevision();
            else if (!persistedRevision.equals(page.projectorRevision())) throw corrupt();
            try { selectedBytes = Math.addExact(selectedBytes, page.selectedBytes()); }
            catch (ArithmeticException overflow) { throw unavailable(); }
            if (selectedBytes > MAX_AGGREGATE_BYTES) throw unavailable();
            for (BulkPreviewPageReader.Item item : page.items()) {
                int expectedOrdinal = next + 1;
                if (item.ordinal() != expectedOrdinal) throw corrupt();
                BulkTargetEvidence<?> protectedTarget = targets.get(expectedOrdinal);
                BulkTargetEligibility protectedEligibility = protectedTarget.eligibility().orElseThrow();
                if (!Objects.equals(item.wireIdentity(), protectedTarget.target().id())
                        || item.decision() != protectedEligibility.decision()
                        || item.diagnostics().size() != protectedEligibility.diagnostics().size())
                    throw corrupt();
                for (int index = 0; index < item.diagnostics().size(); index++) {
                    ResourceCommandMessage safe = item.diagnostics().get(index);
                    ResourceCommandMessage protectedMessage = protectedEligibility.diagnostics().get(index);
                    if (safe.category() != protectedMessage.category()
                            || !safe.code().equals(protectedMessage.code())
                            || safe.target() != null || !safe.metadata().isEmpty())
                        throw corrupt();
                    DiagnosticKey key = new DiagnosticKey(safe.category(), safe.code(), safe.message());
                    diagnostics.putIfAbsent(key, new ResourceCommandMessage(
                            safe.category(), safe.code(), safe.message(), null, Map.of()));
                    if (diagnostics.size() > MAX_PUBLIC_DIAGNOSTICS) throw unavailable();
                }
                if (item.decision() == BulkTargetEligibility.Decision.EXECUTABLE) executable++;
                next = item.ordinal();
            }
            if (page.nextOrdinal() != next) throw corrupt();
            boolean complete = next + 1 == targets.size();
            if (page.hasMore() == complete) throw corrupt();
        }
        if (next + 1 != targets.size() || persistedRevision == null) throw corrupt();
        return new ProposalAggregate(executable, List.copyOf(diagnostics.values()));
    }

    private BulkProposal proposal(BulkEvaluationSnapshot evaluation, JsonNode redactedIntent,
            ProposalAggregate aggregate) {
        BulkStoredProposal stored = evaluation.proposal();
        BulkIntentSnapshot snapshot = stored.snapshot();
        JsonNode execution = snapshot.intent().get("executionMode");
        if (execution == null || !execution.isTextual()) throw corrupt();
        BulkExecutionMode executionMode;
        try { executionMode = BulkExecutionMode.valueOf(execution.textValue()); }
        catch (IllegalArgumentException failure) { throw corrupt(); }
        long count = evaluation.targets().size();
        long blocked = count - aggregate.executable();
        BulkProposalStatus status = blocked == 0 ? BulkProposalStatus.READY : BulkProposalStatus.BLOCKED;
        return new BulkProposal(stored.id().toString(), snapshot.context().operationRef(),
                snapshot.mode(), executionMode, snapshot.context().atomicity(), status,
                stored.createdAt(), stored.expiresAt(),
                new BulkProposalTotals(count, count, aggregate.executable(), blocked),
                redactedIntent, aggregate.diagnostics(), List.of());
    }

    private static String boundedText(String value, String name, int maximumBytes) {
        String checked = strictText(value, name);
        byte[] bytes = strictUtf8(checked, name);
        if (bytes.length > maximumBytes || value.indexOf('\0') >= 0 || !value.equals(value.strip()))
            throw new IllegalArgumentException(name + " is outside the supported server binding");
        for (int index = 0; index < value.length(); index++)
            if (Character.isISOControl(value.charAt(index)))
                throw new IllegalArgumentException(name + " is outside the supported server binding");
        return value;
    }

    private static String strictText(String value, String name) {
        BulkContractChecks.text(value, name);
        strictUtf8(value, name);
        if (value.indexOf('\0') >= 0)
            throw new IllegalArgumentException(name + " is outside the supported server binding");
        return value;
    }

    private static byte[] strictUtf8(String value, String name) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException(name + " must be valid UTF-8");
        }
    }

    private static BulkProposalStorageException unavailable() {
        return new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
    }

    private static BulkProposalStorageException corrupt() {
        return new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
    }

    public enum State {
        COMPLETE, GONE, GLOBAL_DENIED, GLOBAL_UNAVAILABLE, NOT_FOUND_OR_DENIED, TOMBSTONED, UNAVAILABLE
    }

    @JsonIgnoreType
    public static final class Observation {
        private final State state;
        private final BulkProposal proposal;
        private final Instant expiresAt;

        private Observation(State state, BulkProposal proposal, Instant expiresAt) {
            this.state = state;
            this.proposal = proposal;
            this.expiresAt = expiresAt;
        }

        static Observation state(State state) { return new Observation(state, null, null); }
        static Observation complete(BulkProposal proposal, Instant expiresAt) {
            return new Observation(State.COMPLETE, proposal, expiresAt);
        }
        public State state() { return state; }
        public BulkProposal proposal() { return proposal; }
        @Override public String toString() { return "BulkAuthorizedProposalObservation[" + state + "]"; }
    }

    private record ProposalAggregate(long executable, List<ResourceCommandMessage> diagnostics) { }

    private record DiagnosticKey(ResourceCommandErrorCategory category, String code, String message) { }

    private record Deadline(long expiresAtNanos) {
        static Deadline start(Duration duration) {
            long now = System.nanoTime();
            long nanos = duration.toNanos();
            return new Deadline(now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos);
        }
        Duration remainingForProvider() {
            long remaining = expiresAtNanos - System.nanoTime();
            if (remaining <= TimeUnit.SECONDS.toNanos(1))
                throw new IllegalStateException("Insufficient bulk read authorization budget");
            return Duration.ofNanos(remaining);
        }
        void requireRemaining() {
            if (expired()) throw new IllegalStateException("Bulk proposal read deadline exceeded");
        }
        void constrain(Connection connection) throws SQLException {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(expiresAtNanos - System.nanoTime());
            if (remainingMillis <= 0)
                throw new IllegalStateException("Bulk proposal read deadline exceeded");
            try (var statement = connection.prepareStatement("""
                    select set_config('statement_timeout',
                        case when current_setting('statement_timeout') = '0'
                                  or current_setting('statement_timeout')::interval > (? * interval '1 millisecond')
                             then ? || 'ms' else current_setting('statement_timeout') end, true)
                    """)) {
                int bounded = Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1, remainingMillis)));
                statement.setInt(1, bounded);
                statement.setInt(2, bounded);
                statement.execute();
            }
        }
        boolean expired() { return expiresAtNanos - System.nanoTime() <= 0; }
    }
}
