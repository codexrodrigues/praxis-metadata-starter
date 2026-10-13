package org.praxisplatform.uischema.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** External package: capture, admission and worker use public SDK APIs only. */
public final class BulkAsyncPublicAdmissionConsumer {
    public static final String OPERATION = "crud.uniform";
    public static final String RESOURCE = "crud.items";
    public static final CanonicalOperationRef REF =
            new CanonicalOperationRef("crud", OPERATION, "/crud-items/bulk/uniform", "POST");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BulkExecutionInfrastructure infrastructure;
    private final BulkOperationLifecycle lifecycle;
    private final BulkCapacityBinding capacity;
    private final JdbcBulkDurableExecution executions;
    private final String subject;

    public BulkAsyncPublicAdmissionConsumer(BulkExecutionInfrastructure infrastructure,
            BulkOperationLifecycle lifecycle, BulkCapacityBinding capacity, String subject) {
        this.infrastructure = infrastructure;
        this.lifecycle = lifecycle;
        this.capacity = capacity;
        this.subject = subject;
        this.executions = new JdbcBulkDurableExecution(infrastructure, lifecycle, capacity);
    }

    public BulkEvaluationSnapshot capture(int... ids) throws Exception {
        var admission = lifecycle.requireReady(identity(), BulkExecutionMode.ASYNC, BulkSelectionMode.EXPLICIT);
        var body = JSON.createObjectNode().put("executionMode", "ASYNC");
        var selected = body.putObject("selection").put("mode", "EXPLICIT").putArray("targets");
        for (int id : ids) selected.addObject().put("id", id).put("expectedVersion", "v1");
        body.putArray("changes").addObject().put("field", "display_name").put("operator", "SET").put("value", "after");
        var request = new BulkProtocolReader<>(BulkIdentityCodecs.integers())
                .readUniform(JSON.writeValueAsBytes(body));
        var scope = scope(admission.expectation().structuralRevision());
        var intent = BulkIntentSnapshot.uniform(scope, BulkIdentityCodecs.integers(), request,
                value -> value.deepCopy());
        Instant created = Instant.now();
        var proposal = BulkStoredProposal.asynchronous(admission, UUID.randomUUID(), created,
                created.plusSeconds(120), intent);
        var tx = new TransactionTemplate(infrastructure.transactionManager());
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> new JdbcBulkProposalStore(infrastructure).captureAndInsertEvaluated(
                admission, proposal, (connection, budget) -> {
                    int grantRevision;
                    try (var grant = connection.prepareStatement("select allowed,revision from public.async_admission_grant where subject=? for share")) {
                        grant.setString(1, subject);
                        try (var rows = grant.executeQuery()) {
                            if (!rows.next() || !rows.getBoolean(1))
                                throw new IllegalStateException("Fixture capture authority rejected");
                            grantRevision = rows.getInt(2);
                            if (grantRevision < 1 || rows.next()) throw new IllegalStateException("Fixture grant revision/cardinality");
                        }
                    } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
                    var targets = new ArrayList<BulkTargetEvidence<?>>();
                    for (int id : ids) {
                        try (var query = connection.prepareStatement(
                                "select writes from public.async_admission_domain where id=?")) {
                            query.setInt(1, id);
                            try (var rows = query.executeQuery()) {
                                if (!rows.next()) throw new IllegalStateException("Fixture target absent");
                                int writes = rows.getInt(1);
                                if (writes != 0) throw new IllegalStateException("Fixture initial version differs");
                                var facts = JSON.createObjectNode().put("writes", writes);
                                if (rows.next()) throw new IllegalStateException("Fixture target duplicated");
                                targets.add(new BulkTargetEvidence<>(new BulkTarget<>(id, "v1"), "v1",
                                        facts, JSON.createObjectNode().put("display_name", "after"),
                                        BulkTargetEligibility.executable()));
                            }
                        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
                    }
                    return new BulkEvaluationSnapshot(proposal, Instant.now(), targets,
                            new BulkEvaluationGovernance("async-proof-r1", "grant-r" + grantRevision, List.of()));
                }, evaluation -> new BulkPreviewProjection(evaluation, "async-preview-r1", List.of()),
                () -> Duration.ofSeconds(20)));
    }

    public BulkExecutionReservation confirm(BulkEvaluationSnapshot evaluated, String key, Instant deadline) {
        return executions.enqueue(evaluated.proposal().snapshot().context(), evaluated.proposal().id(), key,
                "fixture-supervisor", evaluated.proposal().controlExpectation().structuralRevision(), deadline);
    }

    public SmartLifecycle worker(AtomicInteger admissions, AtomicInteger mutations,
            AtomicInteger cleanups, boolean throwAfterWrite) {
        return worker(admissions, mutations, cleanups, throwAfterWrite, () -> { });
    }

    public SmartLifecycle worker(AtomicInteger admissions, AtomicInteger mutations,
            AtomicInteger cleanups, boolean throwAfterWrite, Runnable afterFirstCleanup) {
        return worker(infrastructure, capacity, admissions, mutations, cleanups, throwAfterWrite, afterFirstCleanup);
    }

    public static SmartLifecycle worker(BulkExecutionInfrastructure infrastructure, BulkCapacityBinding capacity,
            AtomicInteger admissions, AtomicInteger mutations, AtomicInteger cleanups, boolean throwAfterWrite) {
        return worker(infrastructure, capacity, admissions, mutations, cleanups, throwAfterWrite, () -> { });
    }

    private static SmartLifecycle worker(BulkExecutionInfrastructure infrastructure, BulkCapacityBinding capacity,
            AtomicInteger admissions, AtomicInteger mutations, AtomicInteger cleanups, boolean throwAfterWrite,
            Runnable afterFirstCleanup) {
        var operation = new BulkDurableWorkerComposition.Operation(RESOURCE, REF, () ->
                new BulkDurableWorkerComposition.UnitCallbacks(unit -> {
                    admissions.incrementAndGet();
                    var sql = new JdbcTemplate(infrastructure.dataSource());
                    var current = sql.queryForMap("select allowed,revision from public.async_admission_grant where subject=? for share",
                            unit.originalIntent().context().subjectId());
                    if (!Boolean.TRUE.equals(current.get("allowed")) || !("grant-r" + ((Number) current.get("revision")).intValue())
                            .equals(unit.governance().authorizationFingerprint()))
                        return BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED);
                    var target = sql.queryForMap("select writes from public.async_admission_domain where id=? for update",
                            unit.targetEvidence().target().id());
                    int writes = ((Number) target.get("writes")).intValue();
                    if (writes != unit.targetEvidence().facts().path("writes").asInt()
                            || !unit.targetEvidence().observedVersion().equals("v" + (writes + 1))
                            || !unit.targetEvidence().target().expectedVersion().equals("v" + (writes + 1)))
                        return BulkUnitAdmission.conflict(BulkUnitReasonCode.TARGET_VERSION_CONFLICT);
                    return BulkUnitAdmission.admit();
                }, unit -> {
                    var sql = new JdbcTemplate(infrastructure.dataSource());
                    if (sql.update("update public.async_admission_domain set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() where id=?",
                            unit.targetEvidence().target().id()) != 1) throw new IllegalStateException("Fixture mutation cardinality");
                    mutations.incrementAndGet();
                    if (throwAfterWrite) throw new IllegalStateException("Fixture rollback after write");
                    return BulkUnitMutationResult.confirmed();
                }, () -> { if (cleanups.incrementAndGet() == 1) afterFirstCleanup.run(); }));
        return BulkDurableWorkerComposition.compose(List.of(
                new BulkDurableWorkerComposition.Binding(infrastructure, capacity, List.of(operation))));
    }

    public BulkOperationControlIdentity identity() {
        return new BulkOperationControlIdentity(infrastructure.namespace(), OPERATION);
    }
    private BulkFingerprintContext scope(String revision) {
        return new BulkFingerprintContext(infrastructure.namespace(), subject, RESOURCE, REF, revision,
                ActionCollectionAtomicity.PER_ITEM);
    }
}
