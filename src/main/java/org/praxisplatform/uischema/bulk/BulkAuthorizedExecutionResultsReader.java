package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.dto.CursorPage;

/**
 * Server-side composition for authorized, cursor-bound execution result pages.
 *
 * <p>The resource and operation are trusted host bindings. Every live page reauthorizes the
 * complete protected historical target set in the same Metadata-owned RR/RO snapshot used by RS4.
 * Tombstones reveal only GONE to their historical creator after the current global gate. This
 * class creates no endpoint, capability, execution path or readiness state.</p>
 */
public final class BulkAuthorizedExecutionResultsReader {
    private static final Duration READ_BUDGET = Duration.ofSeconds(3);
    private static final String PROJECTOR_REVISION = "execution-results-v1";

    private final BulkExecutionInfrastructure infrastructure;
    private final String resourceKey;
    private final String operationId;
    private final BulkReadAuthorizationProvider authorization;
    private final BulkExecutionResultsReader results;
    private final BulkReadCursorCodec cursorCodec;
    private final Duration cursorTtl;
    private final Clock clock;

    public BulkAuthorizedExecutionResultsReader(BulkExecutionInfrastructure infrastructure,
            String resourceKey, BulkReadAuthorizationProvider authorization,
            BulkReadCursorConfiguration cursorConfiguration) {
        this(infrastructure, resourceKey, authorization, cursorConfiguration, Clock.systemUTC());
    }

    BulkAuthorizedExecutionResultsReader(BulkExecutionInfrastructure infrastructure,
            String resourceKey, BulkReadAuthorizationProvider authorization,
            BulkReadCursorConfiguration cursorConfiguration, Clock clock) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
        BulkContractChecks.text(resourceKey, "resourceKey");
        requireCursorText(resourceKey, "resourceKey");
        this.resourceKey = resourceKey;
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        if (authorization.executionInfrastructure() != infrastructure)
            throw new IllegalArgumentException(
                    "Read authorization must use the exact operational infrastructure instance");
        BulkContractChecks.text(authorization.confirmationOperationId(), "confirmationOperationId");
        requireCursorText(authorization.confirmationOperationId(), "confirmationOperationId");
        this.operationId = authorization.confirmationOperationId();
        requireCursorText(infrastructure.namespace(), "namespaceId");
        BulkReadCursorConfiguration configuration =
                Objects.requireNonNull(cursorConfiguration, "cursorConfiguration");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cursorCodec = new BulkReadCursorCodec(configuration.keySet(), new SecureRandom(), clock);
        this.cursorTtl = configuration.ttl();
        this.results = new BulkExecutionResultsReader(infrastructure);
    }

    public Observation readExecutionResults(String authenticatedSubjectId, UUID executionId,
            int pageSize) {
        return readExecutionResults(authenticatedSubjectId, executionId, pageSize, null);
    }

    public Observation readExecutionResults(String authenticatedSubjectId, UUID executionId,
            int pageSize, String after) {
        BulkContractChecks.text(authenticatedSubjectId, "authenticatedSubjectId");
        requireCursorText(authenticatedSubjectId, "authenticatedSubjectId");
        Objects.requireNonNull(executionId, "executionId");
        if (pageSize < 1 || pageSize > 200)
            throw new IllegalArgumentException("Page size must be within 1..200");

        Deadline deadline = Deadline.start(READ_BUDGET);
        BulkReadCursorCodec.Decoded decoded = null;
        if (after != null) {
            try {
                decoded = cursorCodec.decode(after, BulkReadCursorCodec.Purpose.EXECUTION_RESULTS);
                deadline.requireRemaining();
            } catch (BulkReadCursorCodec.BulkReadCursorException invalid) {
                return Observation.state(deadline.expired() ? State.UNAVAILABLE : State.INVALID_CURSOR);
            } catch (RuntimeException failure) {
                return Observation.state(State.UNAVAILABLE);
            }
        }
        BulkReadCursorCodec.Decoded continuation = decoded;
        Observation result;
        try {
            result = infrastructure.withConsistentRead(connection -> readSnapshot(connection,
                    authenticatedSubjectId, executionId, pageSize, continuation, deadline));
        } catch (RuntimeException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
        if (deadline.expired()) return Observation.state(State.UNAVAILABLE);
        if (result.state() == State.COMPLETE && result.cursorExpiresAt != null
                && !result.cursorExpiresAt.isAfter(clock.instant()))
            return Observation.state(continuation == null ? State.UNAVAILABLE : State.PRECONDITION_FAILED);
        return result;
    }

    private Observation readSnapshot(Connection connection, String requester, UUID executionId,
            int pageSize, BulkReadCursorCodec.Decoded continuation, Deadline deadline) {
        var context = new BulkReadAuthorizationProvider.Context(infrastructure.namespace(),
                resourceKey, operationId, requester);
        BulkReadAuthorizationProvider.GlobalDecision global;
        try {
            global = authorization.preAuthorize(context, deadline.remainingForProvider());
        } catch (RuntimeException failure) {
            return Observation.state(State.GLOBAL_UNAVAILABLE);
        }
        if (global == null || global == BulkReadAuthorizationProvider.GlobalDecision.UNAVAILABLE)
            return Observation.state(State.GLOBAL_UNAVAILABLE);
        if (global == BulkReadAuthorizationProvider.GlobalDecision.DENIED)
            return Observation.state(State.GLOBAL_DENIED);

        BulkEvaluationSnapshot evaluation;
        BulkFingerprintContext storedContext;
        byte[] effectiveFingerprint;
        try {
            deadline.requireRemaining();
            deadline.constrain(connection);
            BulkProtectedExecutionReader.Located located =
                    BulkProtectedExecutionReader.locateForAuthorizedComposition(connection,
                            infrastructure.namespace(), resourceKey, operationId, executionId);
            if (located == null) {
                return tombstone(connection, requester, executionId, continuation);
            }
            BulkProtectedProposalReader.Observation protectedRead =
                    BulkProtectedProposalReader.readForAuthorizedComposition(connection,
                            infrastructure.namespace(), resourceKey, operationId, located.proposalId());
            if (protectedRead.kind() != BulkProtectedProposalReader.Kind.EVALUATED
                    || !protectedRead.evaluation().hasTypedEligibility())
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            evaluation = protectedRead.evaluation();
            storedContext = evaluation.proposal().snapshot().context();
            if (!located.creatorSubjectId().equals(storedContext.subjectId())
                    || !located.inputFingerprint().equals(evaluation.proposal().snapshot().fingerprint())
                    || !located.evaluationFingerprint().equals(evaluation.fingerprint()))
                return Observation.state(State.NOT_FOUND_OR_DENIED);

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
                    || scoped.scopeFingerprint() == null || scoped.scopeFingerprint().length != 32)
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            effectiveFingerprint = BulkAuthorizedProposalResultsReader.effectiveAuthorizationFingerprint(
                    requester, scoped.scopeFingerprint());
        } catch (BulkProposalStorageException | BulkDurableExecutionException failure) {
            return Observation.state(State.NOT_FOUND_OR_DENIED);
        } catch (RuntimeException | SQLException failure) {
            return Observation.state(State.NOT_FOUND_OR_DENIED);
        }

        BulkReadCursorCodec.Scope storedScope = new BulkReadCursorCodec.Scope(
                infrastructure.namespace(), storedContext.subjectId(), resourceKey, operationId);
        if (continuation != null) {
            BulkReadCursorCodec.Claims claims = continuation.claims();
            if (!claims.proposalId().equals(evaluation.proposal().id())
                    || !executionId.equals(claims.executionId())
                    || !claims.scope().equals(storedScope)
                    || !claims.matchesEffectiveScope(effectiveFingerprint)
                    || claims.watermarkExclusive() > evaluation.targets().size()
                    || (claims.lastOrdinalExclusive() + 1) % claims.pageSize() != 0)
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            if (continuation.expired() || !claims.expiresAt().isAfter(clock.instant())
                    || claims.pageSize() != pageSize
                    || !PROJECTOR_REVISION.equals(claims.projectorRevision()))
                return Observation.state(State.PRECONDITION_FAILED);
        }

        try {
            deadline.requireRemaining();
            deadline.constrain(connection);
            int lastOrdinal = continuation == null ? -1 : continuation.claims().lastOrdinalExclusive();
            BulkExecutionResultsReader.Page page = continuation == null
                    ? results.read(connection, storedContext, executionId, lastOrdinal, pageSize)
                    : results.read(connection, storedContext, executionId, lastOrdinal, pageSize,
                            continuation.claims().watermarkExclusive());
            deadline.requireRemaining();
            if (page.kind() != BulkExecutionResultsReader.Kind.LIVE)
                return Observation.state(State.UNAVAILABLE);
            if (continuation != null && !continuation.claims().expiresAt().isAfter(clock.instant()))
                return Observation.state(State.PRECONDITION_FAILED);
            if (continuation != null && page.items().isEmpty())
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            List<BulkItemResult<Object>> content = page.items().stream()
                    .map(BulkAuthorizedExecutionResultsReader::publicItem).toList();
            Instant cursorExpiresAt = continuation != null
                    ? continuation.claims().expiresAt()
                    : page.hasMore() ? clock.instant().plus(cursorTtl) : null;
            String next = page.hasMore() ? issueNextCursor(continuation, evaluation.proposal().id(),
                    executionId, storedScope, effectiveFingerprint, page, pageSize, cursorExpiresAt) : null;
            deadline.requireRemaining();
            return Observation.complete(new CursorPage<>(List.copyOf(content), next, null, pageSize),
                    cursorExpiresAt);
        } catch (BulkDurableExecutionException failure) {
            return Observation.state(State.UNAVAILABLE);
        } catch (RuntimeException | SQLException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
    }

    private Observation tombstone(Connection connection, String requester, UUID executionId,
            BulkReadCursorCodec.Decoded continuation) throws SQLException {
        String terminal = JdbcBulkDurableExecution.scopedTombstone(connection,
                infrastructure.namespace(), requester, resourceKey, operationId, executionId);
        if (terminal == null) return Observation.state(State.NOT_FOUND_OR_DENIED);
        if (continuation != null) {
            BulkReadCursorCodec.Claims claims = continuation.claims();
            BulkReadCursorCodec.Scope creatorScope = new BulkReadCursorCodec.Scope(
                    infrastructure.namespace(), requester, resourceKey, operationId);
            if (!executionId.equals(claims.executionId()) || !creatorScope.equals(claims.scope()))
                return Observation.state(State.NOT_FOUND_OR_DENIED);
        }
        return Observation.state(State.GONE);
    }

    private String issueNextCursor(BulkReadCursorCodec.Decoded continuation, UUID proposalId,
            UUID executionId, BulkReadCursorCodec.Scope scope, byte[] effectiveFingerprint,
            BulkExecutionResultsReader.Page page, int pageSize, Instant expiresAt) {
        Instant issuedAt = continuation == null ? clock.instant() : continuation.claims().issuedAt();
        return cursorCodec.encode(new BulkReadCursorCodec.Claims(
                BulkReadCursorCodec.Purpose.EXECUTION_RESULTS, proposalId, executionId, scope,
                effectiveFingerprint, BulkReadCursorCodec.Direction.NEXT,
                page.lastReturnedOrdinal(), pageSize, page.watermarkExclusive(),
                PROJECTOR_REVISION, issuedAt, expiresAt));
    }

    private static BulkItemResult<Object> publicItem(BulkExecutionResultsReader.Item item) {
        List<ResourceCommandMessage> diagnostics = switch (item.status()) {
            case CONFIRMED, UNCHANGED -> List.of();
            case DENIED -> diagnostics(ResourceCommandErrorCategory.PERMISSION,
                    "BULK_ITEM_DENIED", "The target was not authorized for this operation");
            case INVALID -> diagnostics(ResourceCommandErrorCategory.VALIDATION,
                    "BULK_ITEM_INVALID", "The target could not be processed");
            case CONFLICT -> diagnostics(ResourceCommandErrorCategory.PRECONDITION,
                    "BULK_ITEM_CONFLICT", "The target no longer satisfied the operation preconditions");
            case NOT_PROCESSED -> diagnostics(ResourceCommandErrorCategory.UNEXPECTED_SANITIZED,
                    "BULK_ITEM_NOT_PROCESSED", "The target was not processed");
            case UNKNOWN -> throw new IllegalArgumentException("Unreconciled targets are not public results");
        };
        return new BulkItemResult<>(item.decodedWireIdentity(), item.status(), diagnostics);
    }

    private static List<ResourceCommandMessage> diagnostics(ResourceCommandErrorCategory category,
            String code, String message) {
        return List.of(new ResourceCommandMessage(category, code, message, null, Map.of()));
    }

    private static void requireCursorText(String value, String name) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            if (encoded.remaining() == 0 || encoded.remaining() > 256 || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException(name + " exceeds the cursor scope limit");
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException(name + " must be valid UTF-8", malformed);
        }
    }

    public enum State {
        COMPLETE, GONE, INVALID_CURSOR, GLOBAL_DENIED, GLOBAL_UNAVAILABLE,
        NOT_FOUND_OR_DENIED, PRECONDITION_FAILED, UNAVAILABLE
    }

    @JsonIgnoreType
    public static final class Observation {
        private final State state;
        private final CursorPage<BulkItemResult<Object>> page;
        private final Instant cursorExpiresAt;
        private Observation(State state, CursorPage<BulkItemResult<Object>> page,
                Instant cursorExpiresAt) {
            this.state = state;
            this.page = page;
            this.cursorExpiresAt = cursorExpiresAt;
        }
        static Observation state(State state) { return new Observation(state, null, null); }
        static Observation complete(CursorPage<BulkItemResult<Object>> page, Instant cursorExpiresAt) {
            return new Observation(State.COMPLETE, Objects.requireNonNull(page, "page"), cursorExpiresAt);
        }
        public State state() { return state; }
        public CursorPage<BulkItemResult<Object>> page() { return page; }
        @Override public String toString() {
            return "BulkAuthorizedExecutionResultsObservation[" + state + "]";
        }
    }

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
