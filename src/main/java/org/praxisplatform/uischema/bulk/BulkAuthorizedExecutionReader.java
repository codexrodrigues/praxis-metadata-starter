package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

/**
 * Server-side composition for an authorized aggregate execution snapshot.
 *
 * <p>The resource and confirmation operation are trusted host bindings. A live execution is
 * returned only after the complete protected target set is authorized in the same Metadata-owned
 * read-only snapshot used to certify RS3. A retained tombstone can be observed only by its
 * historical creator after the current global grant succeeds. This class creates no endpoint,
 * item-result page, capability or readiness state.</p>
 */
public final class BulkAuthorizedExecutionReader {
    private static final Duration READ_BUDGET = Duration.ofSeconds(3);
    private static final ResourceCommandMessage GENERIC_STOPPED = new ResourceCommandMessage(
            ResourceCommandErrorCategory.UNEXPECTED_SANITIZED,
            "BULK_EXECUTION_STOPPED",
            "Bulk execution stopped before every target was processed",
            null,
            Map.of());

    private final BulkExecutionInfrastructure infrastructure;
    private final String resourceKey;
    private final String operationId;
    private final BulkReadAuthorizationProvider authorization;
    private final JdbcBulkDurableExecution executions;

    public BulkAuthorizedExecutionReader(BulkExecutionInfrastructure infrastructure,
            String resourceKey, BulkReadAuthorizationProvider authorization) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
        BulkContractChecks.text(resourceKey, "resourceKey");
        this.resourceKey = requireStrictScopeText(resourceKey, "resourceKey");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        if (authorization.executionInfrastructure() != infrastructure) {
            throw new IllegalArgumentException(
                    "Read authorization must use the exact operational infrastructure instance");
        }
        BulkContractChecks.text(authorization.confirmationOperationId(), "confirmationOperationId");
        this.operationId = requireStrictScopeText(
                authorization.confirmationOperationId(), "confirmationOperationId");
        requireStrictScopeText(infrastructure.namespace(), "namespaceId");
        this.executions = new JdbcBulkDurableExecution(infrastructure);
    }

    public Observation readExecution(String authenticatedSubjectId, UUID executionId) {
        BulkContractChecks.text(authenticatedSubjectId, "authenticatedSubjectId");
        String requester = requireStrictScopeText(authenticatedSubjectId, "authenticatedSubjectId");
        Objects.requireNonNull(executionId, "executionId");
        Deadline deadline = Deadline.start(READ_BUDGET);
        Observation result;
        try {
            result = infrastructure.withConsistentRead(connection ->
                    readSnapshot(connection, requester, executionId, deadline));
        } catch (RuntimeException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
        return deadline.expired() ? Observation.state(State.UNAVAILABLE) : result;
    }

    private Observation readSnapshot(Connection connection, String requester, UUID executionId,
            Deadline deadline) {
        var context = new BulkReadAuthorizationProvider.Context(infrastructure.namespace(),
                resourceKey, operationId, requester);
        BulkReadAuthorizationProvider.GlobalDecision global;
        try {
            global = authorization.preAuthorize(context, deadline.remainingForProvider());
        } catch (RuntimeException failure) {
            return Observation.state(State.GLOBAL_UNAVAILABLE);
        }
        if (global == null || global == BulkReadAuthorizationProvider.GlobalDecision.UNAVAILABLE) {
            return Observation.state(State.GLOBAL_UNAVAILABLE);
        }
        if (global == BulkReadAuthorizationProvider.GlobalDecision.DENIED) {
            return Observation.state(State.GLOBAL_DENIED);
        }

        BulkEvaluationSnapshot evaluation;
        BulkFingerprintContext storedContext;
        try {
            deadline.requireRemaining();
            deadline.constrain(connection);
            BulkProtectedExecutionReader.Located located =
                    BulkProtectedExecutionReader.locateForAuthorizedComposition(connection,
                            infrastructure.namespace(), resourceKey, operationId, executionId);
            if (located == null) {
                String terminal = JdbcBulkDurableExecution.scopedTombstone(connection,
                        infrastructure.namespace(), requester, resourceKey, operationId, executionId);
                return terminal == null
                        ? Observation.state(State.NOT_FOUND_OR_DENIED)
                        : Observation.state(State.GONE);
            }

            BulkProtectedProposalReader.Observation protectedRead =
                    BulkProtectedProposalReader.readForAuthorizedComposition(connection,
                            infrastructure.namespace(), resourceKey, operationId, located.proposalId());
            if (protectedRead.kind() != BulkProtectedProposalReader.Kind.EVALUATED
                    || !protectedRead.evaluation().hasTypedEligibility()) {
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            }
            evaluation = protectedRead.evaluation();
            storedContext = evaluation.proposal().snapshot().context();
            if (!located.creatorSubjectId().equals(storedContext.subjectId())) {
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            }
            if (!located.inputFingerprint().equals(evaluation.proposal().snapshot().fingerprint())
                    || !located.evaluationFingerprint().equals(evaluation.fingerprint())) {
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            }

            List<BulkReadAuthorizationProvider.Target> targets =
                    new ArrayList<>(evaluation.targets().size());
            for (int ordinal = 0; ordinal < evaluation.targets().size(); ordinal++) {
                BulkTargetEvidence<?> evidence = evaluation.targets().get(ordinal);
                targets.add(new BulkReadAuthorizationProvider.Target(
                        ordinal, evidence.target().id(), evidence.facts()));
            }
            deadline.requireRemaining();
            BulkReadAuthorizationProvider.ScopeDecision scoped = authorization.authorize(context,
                    storedContext.subjectId(), List.copyOf(targets), deadline.remainingForProvider());
            if (scoped == null || scoped.state() != BulkReadAuthorizationProvider.State.AUTHORIZED_ALL
                    || scoped.scopeFingerprint() == null || scoped.scopeFingerprint().length != 32) {
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            }
        } catch (BulkProposalStorageException | BulkDurableExecutionException failure) {
            // Before complete target authorization, corruption, absence and scope mismatch must not
            // become an execution-existence oracle.
            return Observation.state(State.NOT_FOUND_OR_DENIED);
        } catch (RuntimeException | SQLException failure) {
            return Observation.state(State.NOT_FOUND_OR_DENIED);
        }

        try {
            deadline.requireRemaining();
            deadline.constrain(connection);
            BulkExecutionSummary summary = executions.summarizeConsistent(
                    connection, storedContext, executionId);
            deadline.requireRemaining();
            if (summary.kind() != BulkExecutionSummary.Kind.LIVE
                    || !summary.proposalId().equals(evaluation.proposal().id())) {
                return Observation.state(State.UNAVAILABLE);
            }
            BulkExecution projection = project(summary, evaluation.proposal().snapshot());
            deadline.requireRemaining();
            return Observation.complete(projection);
        } catch (RuntimeException | SQLException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
    }

    private static BulkExecution project(BulkExecutionSummary summary, BulkIntentSnapshot snapshot) {
        JsonNode executionMode = snapshot.intent().get("executionMode");
        if (executionMode == null || !executionMode.isTextual()) {
            throw new IllegalArgumentException("Protected execution mode is invalid");
        }
        BulkExecutionMode mode;
        try {
            mode = BulkExecutionMode.valueOf(executionMode.textValue());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Protected execution mode is invalid", invalid);
        }
        BulkFingerprintContext context = snapshot.context();
        List<ResourceCommandMessage> diagnostics = summary.status() == BulkExecutionStatus.STOPPED
                ? List.of(GENERIC_STOPPED) : List.of();
        return new BulkExecution(summary.executionId().toString(), summary.proposalId().toString(),
                context.operationRef(), snapshot.mode(), mode, context.atomicity(), summary.status(),
                summary.createdAt(), summary.updatedAt(), summary.terminalAt(), summary.totals(), diagnostics);
    }

    private static String requireStrictScopeText(String value, String name) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            if (encoded.remaining() == 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(name + " must be non-empty UTF-8 without NUL");
            }
            return value;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException(name + " must be valid UTF-8", malformed);
        }
    }

    public enum State {
        COMPLETE, GONE, GLOBAL_DENIED, GLOBAL_UNAVAILABLE, NOT_FOUND_OR_DENIED, UNAVAILABLE
    }

    @JsonIgnoreType
    public static final class Observation {
        private final State state;
        private final BulkExecution execution;

        private Observation(State state, BulkExecution execution) {
            this.state = state;
            this.execution = execution;
        }

        static Observation state(State state) { return new Observation(state, null); }
        static Observation complete(BulkExecution execution) {
            return new Observation(State.COMPLETE, Objects.requireNonNull(execution, "execution"));
        }
        public State state() { return state; }
        public BulkExecution execution() { return execution; }
        @Override public String toString() { return "BulkAuthorizedExecutionObservation[" + state + "]"; }
    }

    private record Deadline(long expiresAtNanos) {
        static Deadline start(Duration duration) {
            long now = System.nanoTime();
            long nanos = duration.toNanos();
            return new Deadline(now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos);
        }
        Duration remainingForProvider() {
            long remaining = expiresAtNanos - System.nanoTime();
            if (remaining <= TimeUnit.SECONDS.toNanos(1)) {
                throw new IllegalStateException("Insufficient bulk read authorization budget");
            }
            return Duration.ofNanos(remaining);
        }
        void requireRemaining() {
            if (expired()) throw new IllegalStateException("Bulk read deadline exceeded");
        }
        void constrain(Connection connection) throws SQLException {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(expiresAtNanos - System.nanoTime());
            if (remainingMillis <= 0) throw new IllegalStateException("Bulk read deadline exceeded");
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
