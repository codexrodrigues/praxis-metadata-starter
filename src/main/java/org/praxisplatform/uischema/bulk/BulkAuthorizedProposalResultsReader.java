package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.praxisplatform.uischema.dto.CursorPage;

/**
 * Server-side composition for authorized, cursor-bound proposal result pages.
 *
 * <p>The resource and operation are trusted host binding values, never request parameters. Every
 * page reauthorizes the complete protected target set inside the same Metadata-owned read-only
 * snapshot used by the RS2 projection. The returned page contains only the allowlisted public
 * projection. This class does not create an HTTP endpoint, capability or readiness state.</p>
 */
public final class BulkAuthorizedProposalResultsReader {
    private static final Duration READ_BUDGET = Duration.ofSeconds(3);
    private static final byte[] FINGERPRINT_DOMAIN =
            "praxis.bulk.proposal-results.authorization".getBytes(StandardCharsets.US_ASCII);
    private static final int FINGERPRINT_VERSION = 1;

    private final BulkExecutionInfrastructure infrastructure;
    private final String resourceKey;
    private final String operationId;
    private final BulkReadAuthorizationProvider authorization;
    private final BulkPreviewPageReader preview;
    private final BulkReadCursorCodec cursorCodec;
    private final Duration cursorTtl;
    private final Clock clock;

    public BulkAuthorizedProposalResultsReader(BulkExecutionInfrastructure infrastructure,
            String resourceKey, BulkReadAuthorizationProvider authorization,
            BulkReadCursorConfiguration cursorConfiguration) {
        this(infrastructure, resourceKey, authorization, cursorConfiguration, Clock.systemUTC());
    }

    BulkAuthorizedProposalResultsReader(BulkExecutionInfrastructure infrastructure,
            String resourceKey, BulkReadAuthorizationProvider authorization,
            BulkReadCursorConfiguration cursorConfiguration, Clock clock) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
        BulkContractChecks.text(resourceKey, "resourceKey");
        this.resourceKey = resourceKey;
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        if (authorization.executionInfrastructure() != infrastructure)
            throw new IllegalArgumentException("Read authorization must use the exact operational infrastructure instance");
        BulkContractChecks.text(authorization.confirmationOperationId(), "confirmationOperationId");
        this.operationId = authorization.confirmationOperationId();
        BulkReadCursorConfiguration configuration =
                Objects.requireNonNull(cursorConfiguration, "cursorConfiguration");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cursorCodec = new BulkReadCursorCodec(configuration.keySet(), new SecureRandom(), clock);
        this.cursorTtl = configuration.ttl();
        this.preview = new BulkPreviewPageReader(infrastructure);
    }

    public Observation readProposalResults(String authenticatedSubjectId, UUID proposalId, int pageSize) {
        return readProposalResults(authenticatedSubjectId, proposalId, pageSize, null);
    }

    public Observation readProposalResults(String authenticatedSubjectId, UUID proposalId,
            int pageSize, String after) {
        BulkContractChecks.text(authenticatedSubjectId, "authenticatedSubjectId");
        requireCursorText(authenticatedSubjectId, "authenticatedSubjectId");
        Objects.requireNonNull(proposalId, "proposalId");
        if (pageSize < 1 || pageSize > 200)
            throw new IllegalArgumentException("Page size must be within 1..200");

        Deadline deadline = Deadline.start(READ_BUDGET);
        BulkReadCursorCodec.Decoded decoded = null;
        if (after != null) {
            try {
                decoded = cursorCodec.decode(after, BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS);
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
                    authenticatedSubjectId, proposalId, pageSize, continuation, deadline));
        } catch (RuntimeException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
        if (deadline.expired()) return Observation.state(State.UNAVAILABLE);
        if (continuation != null && result.state() == State.COMPLETE
                && !continuation.claims().expiresAt().isAfter(clock.instant()))
            return Observation.state(State.PRECONDITION_FAILED);
        return result;
    }

    private Observation readSnapshot(Connection connection, String authenticatedSubjectId,
            UUID proposalId, int pageSize, BulkReadCursorCodec.Decoded continuation,
            Deadline deadline) {
        var context = new BulkReadAuthorizationProvider.Context(infrastructure.namespace(),
                resourceKey, operationId, authenticatedSubjectId);
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
        byte[] effectiveFingerprint;
        try {
            deadline.requireRemaining();
            deadline.constrain(connection);
            BulkProtectedProposalReader.Observation protectedRead =
                    BulkProtectedProposalReader.readForAuthorizedComposition(connection,
                            infrastructure.namespace(), resourceKey, operationId, proposalId);
            if (protectedRead.kind() != BulkProtectedProposalReader.Kind.EVALUATED
                    || !protectedRead.evaluation().hasTypedEligibility())
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            evaluation = protectedRead.evaluation();

            List<BulkReadAuthorizationProvider.Target> targets = new ArrayList<>(evaluation.targets().size());
            for (int ordinal = 0; ordinal < evaluation.targets().size(); ordinal++) {
                BulkTargetEvidence<?> evidence = evaluation.targets().get(ordinal);
                targets.add(new BulkReadAuthorizationProvider.Target(
                        ordinal, evidence.target().id(), evidence.facts()));
            }
            deadline.requireRemaining();
            BulkReadAuthorizationProvider.ScopeDecision scoped = authorization.authorize(context,
                    evaluation.proposal().snapshot().context().subjectId(),
                    List.copyOf(targets), deadline.remainingForProvider());
            if (scoped == null || scoped.state() != BulkReadAuthorizationProvider.State.AUTHORIZED_ALL
                    || scoped.scopeFingerprint() == null || scoped.scopeFingerprint().length != 32)
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            effectiveFingerprint = effectiveAuthorizationFingerprint(
                    authenticatedSubjectId, scoped.scopeFingerprint());
        } catch (BulkProposalStorageException failure) {
            // No target-level authorization exists yet. Storage corruption and absence must not
            // become an oracle for a caller that passed only the global gate.
            return Observation.state(State.NOT_FOUND_OR_DENIED);
        } catch (RuntimeException | SQLException failure) {
            return Observation.state(State.NOT_FOUND_OR_DENIED);
        }

        BulkFingerprintContext storedContext = evaluation.proposal().snapshot().context();
        BulkReadCursorCodec.Scope storedScope = new BulkReadCursorCodec.Scope(
                infrastructure.namespace(), storedContext.subjectId(), resourceKey, operationId);
        if (continuation != null) {
            BulkReadCursorCodec.Claims claims = continuation.claims();
            if (!claims.proposalId().equals(proposalId)
                    || !claims.scope().equals(storedScope)
                    || !claims.matchesEffectiveScope(effectiveFingerprint))
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            if ((claims.lastOrdinalExclusive() + 1) % claims.pageSize() != 0)
                return Observation.state(State.NOT_FOUND_OR_DENIED);
            if (continuation.expired() || !claims.expiresAt().isAfter(clock.instant())
                    || claims.pageSize() != pageSize
                    || claims.watermarkExclusive() != evaluation.targets().size())
                return Observation.state(State.PRECONDITION_FAILED);
        }

        try {
            deadline.requireRemaining();
            deadline.constrain(connection);
            int lastOrdinal = continuation == null ? -1 : continuation.claims().lastOrdinalExclusive();
            int watermark = continuation == null
                    ? evaluation.targets().size() : continuation.claims().watermarkExclusive();
            BulkPreviewPageReader.Page page = preview.read(connection, storedContext,
                    proposalId, lastOrdinal, watermark, pageSize);
            deadline.requireRemaining();
            if (page.kind() != BulkPreviewPageReader.Kind.COMPLETE) {
                if (continuation != null
                        && (page.kind() == BulkPreviewPageReader.Kind.UNAVAILABLE
                        || page.kind() == BulkPreviewPageReader.Kind.UNAVAILABLE_LEGACY))
                    return Observation.state(State.PRECONDITION_FAILED);
                return switch (page.kind()) {
                    case UNAVAILABLE, UNAVAILABLE_LEGACY -> Observation.state(State.PREVIEW_UNAVAILABLE);
                    case ABSENT, NOT_EVALUATED -> Observation.state(State.UNAVAILABLE);
                    case COMPLETE -> throw new IllegalStateException("unreachable");
                };
            }
            if (continuation != null
                    && !continuation.claims().projectorRevision().equals(page.projectorRevision()))
                return Observation.state(State.PRECONDITION_FAILED);
            if (continuation != null && !continuation.claims().expiresAt().isAfter(clock.instant()))
                return Observation.state(State.PRECONDITION_FAILED);
            if (continuation != null && page.items().isEmpty())
                return Observation.state(State.NOT_FOUND_OR_DENIED);

            List<BulkProposalItemResult<Object>> content = page.items().stream()
                    .map(BulkAuthorizedProposalResultsReader::publicItem)
                    .toList();
            String next = page.hasMore() ? issueNextCursor(continuation, proposalId, storedScope,
                    effectiveFingerprint, page, pageSize) : null;
            deadline.requireRemaining();
            return Observation.complete(new CursorPage<>(List.copyOf(content), next, null, pageSize));
        } catch (BulkProposalStorageException failure) {
            return Observation.state(State.UNAVAILABLE);
        } catch (RuntimeException | SQLException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
    }

    private String issueNextCursor(BulkReadCursorCodec.Decoded continuation, UUID proposalId,
            BulkReadCursorCodec.Scope scope, byte[] effectiveFingerprint,
            BulkPreviewPageReader.Page page, int pageSize) {
        Instant issuedAt = continuation == null ? clock.instant() : continuation.claims().issuedAt();
        Instant expiresAt = continuation == null
                ? issuedAt.plus(cursorTtl) : continuation.claims().expiresAt();
        return cursorCodec.encode(new BulkReadCursorCodec.Claims(
                BulkReadCursorCodec.Purpose.PROPOSAL_RESULTS, proposalId, null, scope,
                effectiveFingerprint, BulkReadCursorCodec.Direction.NEXT, page.nextOrdinal(),
                pageSize, page.targetCount(), page.projectorRevision(), issuedAt, expiresAt));
    }

    private static BulkProposalItemResult<Object> publicItem(BulkPreviewPageReader.Item item) {
        BulkProposalItemResult.Decision decision = item.decision() == BulkTargetEligibility.Decision.EXECUTABLE
                ? BulkProposalItemResult.Decision.EXECUTABLE : BulkProposalItemResult.Decision.BLOCKED;
        return new BulkProposalItemResult<>(item.wireIdentity(), decision, item.diagnostics());
    }

    static byte[] effectiveAuthorizationFingerprint(String subjectId, byte[] providerFingerprint) {
        try {
            byte[] subject = strictUtf8(subjectId);
            if (subject.length == 0 || subject.length > 256)
                throw new IllegalArgumentException("authenticatedSubjectId exceeds the cursor scope limit");
            if (providerFingerprint == null || providerFingerprint.length != 32)
                throw new IllegalArgumentException("providerFingerprint must be SHA-256");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
            DataOutputStream output = new DataOutputStream(bytes);
            writeFramed(output, FINGERPRINT_DOMAIN);
            output.writeInt(FINGERPRINT_VERSION);
            writeFramed(output, subject);
            writeFramed(output, providerFingerprint);
            output.flush();
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not frame bulk read authorization");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static byte[] strictUtf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("authenticatedSubjectId must be valid UTF-8");
        }
    }

    private static void requireCursorText(String value, String name) {
        byte[] encoded = strictUtf8(value);
        if (encoded.length == 0 || encoded.length > 256 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException(name + " exceeds the cursor scope limit");
    }

    private static void writeFramed(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    public enum State {
        COMPLETE, INVALID_CURSOR, GLOBAL_DENIED, GLOBAL_UNAVAILABLE, NOT_FOUND_OR_DENIED,
        PREVIEW_UNAVAILABLE, PRECONDITION_FAILED, UNAVAILABLE
    }

    @JsonIgnoreType
    public static final class Observation {
        private final State state;
        private final CursorPage<BulkProposalItemResult<Object>> page;

        private Observation(State state, CursorPage<BulkProposalItemResult<Object>> page) {
            this.state = state;
            this.page = page;
        }

        static Observation state(State state) { return new Observation(state, null); }
        static Observation complete(CursorPage<BulkProposalItemResult<Object>> page) {
            return new Observation(State.COMPLETE, page);
        }
        public State state() { return state; }
        public CursorPage<BulkProposalItemResult<Object>> page() { return page; }
        @Override public String toString() { return "BulkAuthorizedProposalResultsObservation[" + state + "]"; }
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
