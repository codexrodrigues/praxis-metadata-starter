package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.Duration;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

/**
 * Server-side first-page composition for authorized proposal results.
 *
 * <p>The resource and operation passed to the constructor are trusted host binding values, never
 * request parameters. This class opens one Metadata-owned read-only snapshot and returns only the
 * existing allowlisted RS2 projection. It is not an HTTP controller, cursor issuer or readiness
 * signal.</p>
 */
public final class BulkAuthorizedProposalResultsReader {
    private static final Duration READ_BUDGET = Duration.ofSeconds(3);

    private final BulkExecutionInfrastructure infrastructure;
    private final String resourceKey;
    private final String operationId;
    private final BulkReadAuthorizationProvider authorization;
    private final BulkPreviewPageReader preview;

    public BulkAuthorizedProposalResultsReader(BulkExecutionInfrastructure infrastructure,
            String resourceKey, BulkReadAuthorizationProvider authorization) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
        BulkContractChecks.text(resourceKey, "resourceKey");
        this.resourceKey = resourceKey;
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        if (authorization.executionInfrastructure() != infrastructure)
            throw new IllegalArgumentException("Read authorization must use the exact operational infrastructure instance");
        BulkContractChecks.text(authorization.confirmationOperationId(), "confirmationOperationId");
        this.operationId = authorization.confirmationOperationId();
        this.preview = new BulkPreviewPageReader(infrastructure);
    }

    /** Reads the first bounded page. Continuation and cursor issuance belong to G3b. */
    public Observation readProposalResults(String authenticatedSubjectId, UUID proposalId, int pageSize) {
        BulkContractChecks.text(authenticatedSubjectId, "authenticatedSubjectId");
        Objects.requireNonNull(proposalId, "proposalId");
        if (pageSize < 1 || pageSize > 200) throw new IllegalArgumentException("Page size must be within 1..200");
        Deadline deadline = Deadline.start(READ_BUDGET);
        Observation result;
        try {
            result = infrastructure.withConsistentRead(connection -> {
                var context = new BulkReadAuthorizationProvider.Context(infrastructure.namespace(),
                        resourceKey, operationId, authenticatedSubjectId);
                BulkReadAuthorizationProvider.GlobalDecision global;
                try { global = authorization.preAuthorize(context, deadline.remainingForProvider()); }
                catch (RuntimeException failure) { return Observation.state(State.GLOBAL_UNAVAILABLE); }
                if (global == null || global == BulkReadAuthorizationProvider.GlobalDecision.UNAVAILABLE)
                    return Observation.state(State.GLOBAL_UNAVAILABLE);
                if (global == BulkReadAuthorizationProvider.GlobalDecision.DENIED)
                    return Observation.state(State.GLOBAL_DENIED);

                BulkEvaluationSnapshot evaluation;
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
                    BulkReadAuthorizationProvider.ScopeDecision scoped =
                            authorization.authorize(context,
                                    evaluation.proposal().snapshot().context().subjectId(),
                                    List.copyOf(targets), deadline.remainingForProvider());
                    if (scoped == null || scoped.state() != BulkReadAuthorizationProvider.State.AUTHORIZED_ALL
                            || scoped.scopeFingerprint() == null || scoped.scopeFingerprint().length != 32)
                        return Observation.state(State.NOT_FOUND_OR_DENIED);

                } catch (BulkProposalStorageException failure) {
                    // Before complete authorization, absence, corruption and target-scope failure
                    // intentionally share one non-enumerating outcome.
                    return Observation.state(State.NOT_FOUND_OR_DENIED);
                } catch (RuntimeException | SQLException failure) {
                    return Observation.state(State.NOT_FOUND_OR_DENIED);
                }
                try {
                    deadline.requireRemaining();
                    deadline.constrain(connection);
                    BulkPreviewPageReader.Page page = preview.read(connection,
                            evaluation.proposal().snapshot().context(), proposalId, -1,
                            evaluation.targets().size(), pageSize);
                    deadline.requireRemaining();
                    return switch (page.kind()) {
                        case COMPLETE -> Observation.complete(Page.from(page));
                        case UNAVAILABLE, UNAVAILABLE_LEGACY -> Observation.state(State.PREVIEW_UNAVAILABLE);
                        case ABSENT, NOT_EVALUATED -> Observation.state(State.UNAVAILABLE);
                    };
                } catch (RuntimeException | SQLException failure) {
                    return Observation.state(State.UNAVAILABLE);
                }
            });
        } catch (RuntimeException failure) {
            return Observation.state(State.UNAVAILABLE);
        }
        return deadline.expired() ? Observation.state(State.UNAVAILABLE) : result;
    }

    public enum State {
        COMPLETE, GLOBAL_DENIED, GLOBAL_UNAVAILABLE, NOT_FOUND_OR_DENIED,
        PREVIEW_UNAVAILABLE, UNAVAILABLE
    }

    @JsonIgnoreType
    public static final class Observation {
        private final State state;
        private final Page page;
        private Observation(State state, Page page) { this.state = state; this.page = page; }
        static Observation state(State state) { return new Observation(state, null); }
        static Observation complete(Page page) { return new Observation(State.COMPLETE, page); }
        public State state() { return state; }
        public Page page() { return page; }
        @Override public String toString() { return "BulkAuthorizedProposalResultsObservation[" + state + "]"; }
    }

    @JsonIgnoreType
    public static final class Page {
        private final int targetCount;
        private final String projectorRevision;
        private final List<Item> items;
        private final boolean hasMore;
        private final int nextOrdinal;
        private Page(int targetCount, String projectorRevision, List<Item> items,
                boolean hasMore, int nextOrdinal) {
            this.targetCount = targetCount;
            this.projectorRevision = projectorRevision;
            this.items = List.copyOf(items);
            this.hasMore = hasMore;
            this.nextOrdinal = nextOrdinal;
        }
        static Page from(BulkPreviewPageReader.Page source) {
            return new Page(source.targetCount(), source.projectorRevision(), source.items().stream()
                    .map(item -> new Item(item.ordinal(), item.wireIdentity(), item.decision(), item.diagnostics()))
                    .toList(), source.hasMore(), source.nextOrdinal());
        }
        public int targetCount() { return targetCount; }
        public String projectorRevision() { return projectorRevision; }
        public List<Item> items() { return items; }
        public boolean hasMore() { return hasMore; }
        public int nextOrdinal() { return nextOrdinal; }
        @Override public String toString() { return "BulkAuthorizedProposalResultsPage[protected]"; }
    }

    @JsonIgnoreType
    public record Item(int ordinal, Object wireIdentity, BulkTargetEligibility.Decision decision,
            List<ResourceCommandMessage> diagnostics) {
        public Item { diagnostics = List.copyOf(diagnostics); }
        @Override public String toString() { return "BulkAuthorizedProposalResultItem[protected]"; }
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
