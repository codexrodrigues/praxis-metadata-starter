package org.praxisplatform.uischema.bulk;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.CONTEXT;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.REVISION;

/** Initial local occupancy kernel cases; no public ASYNC admission or multiprocess claim. */
class BulkCapacityOccupancyPostgresTest {
    @Test
    void enqueueCommitsQueuedLedgerAndQueueOccupationAndReplaysTheSameIdentity() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("enqueue-replay-conflict")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var input = fixture.persist();
            var reservation = fixture.enqueue(input, "queue-key", queue);
            assertThat(reservation.replayed()).isFalse();
            assertQueued(fixture, reservation, queue);
            var allocationId = fixture.allocation(reservation.executionId()).get("allocation_id");
            var replay = fixture.enqueue(input, "queue-key", queue);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.executionId()).isEqualTo(reservation.executionId());
            assertThat(replay.proposalId()).isEqualTo(reservation.proposalId());
            assertThat(replay.control().ownerId()).isEqualTo(reservation.control().ownerId());
            assertThat(replay.control().epoch()).isEqualTo(1);
            assertThat(replay.execution().deadlineAt()).isEqualTo(reservation.execution().deadlineAt());
            assertThat(fixture.allocation(reservation.executionId()).get("allocation_id")).isEqualTo(allocationId);
            var conflicting = fixture.persist();
            var controlBefore = fixture.controlRow();
            assertThatThrownBy(() -> fixture.enqueue(conflicting, "queue-key", queue))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CONFLICT));
            assertThat(fixture.count("execution")).isEqualTo(1);
            assertThat(fixture.observer.queryForObject("select state from praxis_bulk.praxis_bulk_allocation where proposal_id=?",
                    String.class, conflicting.proposal().id())).isEqualTo("PENDING");
            assertThat(fixture.controlRow()).isEqualTo(controlBefore);
            assertQueued(fixture, reservation, queue);
            fixture.observe("replay-and-conflict", reservation.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void protectedClaimTransfersOneAllocationAndOccupationFromQueueToActiveWithEpochTwo() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("claim-transfer")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var reservation = fixture.enqueue(fixture.persist(), "claim-key", queue);
            assertQueued(fixture, reservation, queue);
            Object allocationId = fixture.allocation(reservation.executionId()).get("allocation_id");
            Instant deadline = reservation.execution().deadlineAt();
            fixture.observe("queued", reservation.executionId());
            var claimed = fixture.kernel.claim(CONTEXT, reservation.executionId(), "fixture-worker", active).orElseThrow();
            assertThat(claimed.executionId()).isEqualTo(reservation.executionId());
            assertThat(claimed.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
            assertThat(claimed.nextOrdinal()).isZero();
            assertThat(claimed.control().ownerId()).isEqualTo("fixture-worker");
            assertThat(claimed.control().epoch()).isEqualTo(2);
            assertThat(claimed.execution().deadlineAt()).isEqualTo(deadline);
            assertThat(fixture.allocation(claimed.executionId())).containsEntry("allocation_id", allocationId)
                    .containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "ACTIVE");
            assertFree(fixture, queue, 1);
            assertThat(fixture.slot(active)).containsEntry("capacity_class", "ACTIVE")
                    .containsEntry("occupancy_sequence", 1L).containsEntry("current_execution_id", claimed.executionId())
                    .containsEntry("current_owner_epoch", 2L);
            var history = fixture.observer.queryForList("""
                    select token_id,occupancy_sequence,owner_epoch from praxis_bulk.praxis_bulk_capacity_occupation
                    where execution_id=? order by owner_epoch
                    """, claimed.executionId());
            assertThat(history).hasSize(2);
            assertThat(history.get(0)).containsEntry("token_id", queue).containsEntry("occupancy_sequence", 1L)
                    .containsEntry("owner_epoch", 1L);
            assertThat(history.get(1)).containsEntry("token_id", active).containsEntry("occupancy_sequence", 1L)
                    .containsEntry("owner_epoch", 2L);
            assertThat(fixture.kernel.claim(CONTEXT, claimed.executionId(), "second-worker", active)).isEmpty();
            var unchanged = fixture.kernel.find(CONTEXT, claimed.executionId()).orElseThrow();
            assertThat(unchanged.control().epoch()).isEqualTo(2);
            assertThat(unchanged.control().ownerId()).isEqualTo("fixture-worker");
            assertThat(fixture.count("capacity_occupation")).isEqualTo(2);
            assertThat(fixture.count("execution")).isEqualTo(1);
            assertThat(fixture.count("item_receipt")).isZero();
            assertThat(fixture.count("admission")).isZero();
            fixture.observe("claimed", claimed.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void ordinaryRuntimeCannotClaimByUpdatingTheExecutionDirectly() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("raw-update-denied")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var reservation = fixture.enqueue(fixture.persist(), "raw-key", queue);
            var controlBefore = fixture.controlRow();
            var allocationBefore = fixture.allocation(reservation.executionId());
            try (var connection = fixture.runtimeSource.getConnection()) {
                assertThat(connection.getMetaData().getUserName()).isEqualTo("bulk_runtime_test");
                try (var statement = connection.prepareStatement("""
                        update praxis_bulk.praxis_bulk_execution set status='RUNNING',owner_id='raw-worker',
                            owner_epoch=2,active_token_id=?,updated_at=clock_timestamp() where execution_id=?
                        """)) {
                    statement.setObject(1, active); statement.setObject(2, reservation.executionId());
                    assertThatThrownBy(statement::executeUpdate).isInstanceOfSatisfying(SQLException.class, error -> {
                        assertThat(error.getSQLState()).isEqualTo("55000");
                        assertThat(error.getMessage()).contains("claim requires the protected SQL entrypoint");
                    });
                }
            }
            assertThat(fixture.allocation(reservation.executionId())).isEqualTo(allocationBefore);
            assertThat(fixture.controlRow()).isEqualTo(controlBefore);
            assertFree(fixture, active, 0);
            assertQueued(fixture, reservation, queue);
            fixture.observe("direct-update-denied", reservation.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void enqueueWithoutPhysicalMarkerRejectsBeforeAnyExecutionOrOccupationMutation() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("absent-marker-denied")) {
            var input = fixture.persist();
            var before = fixture.controlRow();
            assertThatThrownBy(() -> fixture.enqueue(input, "absent-marker-key", UUID.randomUUID()))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
            assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_marker", Long.class))
                    .isZero();
            assertNoExecutionMutation(fixture, input);
            assertThat(fixture.controlRow()).isEqualTo(before);
            fixture.assertionsComplete();
        }
    }

    @Test
    void absentOrMismatchedConfiguredBindingCannotEnqueueAgainstAnActiveMarker() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("configured-binding-denied")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var input = fixture.persist();
            var before = fixture.controlRow();
            var unbound = new JdbcBulkDurableExecution(fixture.runtime);
            assertThatThrownBy(() -> unbound.enqueue(CONTEXT, input.proposal().id(), "unbound-key", "supervisor",
                    REVISION, Instant.now().plusSeconds(300), queue))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            var expected = fixture.expected;
            var mismatch = new JdbcBulkCapacityInstallation.ExpectedBinding(expected.deploymentId(), expected.tenantId(),
                    expected.environment(), expected.bindingId(), expected.generation(), expected.databaseId(),
                    UUID.randomUUID(), expected.authorityId(), expected.authorityEpoch());
            var wrongBinding = new JdbcBulkDurableExecution(fixture.runtime, null, mismatch);
            assertThatThrownBy(() -> wrongBinding.enqueue(CONTEXT, input.proposal().id(), "mismatch-key", "supervisor",
                    REVISION, Instant.now().plusSeconds(300), queue))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
            assertNoExecutionMutation(fixture, input);
            assertFree(fixture, queue, 0);
            assertThat(fixture.controlRow()).isEqualTo(before);
            fixture.assertionsComplete();
        }
    }

    @Test
    void queuedCancellationReturnsTerminalEvidenceAndReleasesQueueWithoutStartingCallback() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("queued-cancel")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var reservation = fixture.enqueue(fixture.persist(), "cancel-key", queue);
            AtomicInteger callbacks = new AtomicInteger();
            assertThatThrownBy(() -> fixture.kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                    ignored -> {
                        callbacks.incrementAndGet();
                        fixture.runtimeSql.update("update occupancy_domain_witness set writes=writes+1 where id=1");
                        return BulkUnitMutationResult.confirmed();
                    })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(callbacks).hasValue(0);
            assertThat(fixture.observer.queryForObject("select writes from occupancy_domain_witness where id=1", Integer.class)).isZero();
            var cancelled = fixture.kernel.requestCancel(CONTEXT, reservation.executionId());
            assertThat(cancelled.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(cancelled.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.CANCELLED_BY_USER);
            assertThat(cancelled.cancelRequestedAt()).isNotNull();
            assertThat(cancelled.nextOrdinal()).isZero();
            assertThat(cancelled.receiptCount()).isZero();
            assertThat(cancelled.admissionCount()).isZero();
            assertThat(fixture.allocation(reservation.executionId())).containsEntry("kind", "EXECUTION_ASYNC")
                    .containsEntry("state", "RELEASED").containsEntry("release_reason", "TERMINAL_RECONCILED");
            assertThat(fixture.allocation(reservation.executionId()).get("released_at")).isNotNull();
            assertFree(fixture, queue, 1);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(1);
            assertThat(fixture.count("item_receipt")).isZero();
            assertThat(fixture.count("admission")).isZero();
            var summary = fixture.kernel.summarizeConsistent(CONTEXT, reservation.executionId());
            assertThat(summary.status()).isEqualTo(BulkExecutionStatus.CANCELLED);
            assertThat(summary.totals().pending()).isZero();
            assertThat(summary.totals().notProcessed()).isEqualTo(2);
            var replay = fixture.kernel.requestCancel(CONTEXT, reservation.executionId());
            assertThat(replay.cancelRequestedAt()).isEqualTo(cancelled.cancelRequestedAt());
            assertThatThrownBy(() -> fixture.kernel.executeUnit(reservation.control(), 0, ignored -> BulkUnitAdmission.admit(),
                    ignored -> {
                        callbacks.incrementAndGet();
                        fixture.runtimeSql.update("update occupancy_domain_witness set writes=writes+1 where id=1");
                        return BulkUnitMutationResult.confirmed();
                    })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED));
            assertThat(callbacks).hasValue(0);
            assertThat(fixture.observer.queryForObject("select writes from occupancy_domain_witness where id=1", Integer.class)).isZero();
            fixture.observe("queued-cancelled", reservation.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void privilegedDefinerCannotEraseOccupiedSlotEpochThroughSqlUnknown() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("privileged-null-slot-guard")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var reservation = fixture.enqueue(fixture.persist(), "null-slot-key", queue);
            var before = fixture.slot(queue);
            // Privileged test setup only: this helper is never granted to the runtime. Its
            // DDL, ownership and temporary membership are all rolled back in this transaction.
            try (var connection = fixture.ownerSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (var statement = connection.createStatement()) {
                        statement.execute("grant praxis_bulk_capacity_owner to postgres");
                        statement.execute("grant create on schema praxis_bulk to praxis_bulk_capacity_owner");
                        statement.execute("""
                                create function praxis_bulk.fixture_invalid_slot_epoch(p_token_id uuid)
                                returns void language plpgsql security definer set search_path=pg_catalog,pg_temp as $$
                                begin
                                    if current_user<>'praxis_bulk_capacity_owner' then
                                        raise exception 'fixture definer identity differs';
                                    end if;
                                    update praxis_bulk.praxis_bulk_capacity_slot set current_owner_epoch=null
                                     where token_id=p_token_id and current_execution_id is not null;
                                end;
                                $$
                                """);
                        statement.execute("alter function praxis_bulk.fixture_invalid_slot_epoch(uuid) owner to praxis_bulk_capacity_owner");
                        statement.execute("revoke create on schema praxis_bulk from praxis_bulk_capacity_owner");
                        statement.execute("revoke all on function praxis_bulk.fixture_invalid_slot_epoch(uuid) from public");
                    }
                    try (var statement = connection.prepareStatement("select praxis_bulk.fixture_invalid_slot_epoch(?)")) {
                        statement.setObject(1, queue);
                        assertThatThrownBy(statement::executeQuery).isInstanceOfSatisfying(SQLException.class, error -> {
                            // The guard must reject UNKNOWN itself, before the shape CHECK (23514).
                            assertThat(error.getSQLState()).isEqualTo("55000");
                            assertThat(error.getMessage()).contains("capacity slot CAS or sequence differs");
                        });
                    }
                } finally { connection.rollback(); }
            }
            assertThat(fixture.slot(queue)).isEqualTo(before);
            assertThat(fixture.observer.queryForObject("select to_regprocedure('praxis_bulk.fixture_invalid_slot_epoch(uuid)') is null",
                    Boolean.class)).isTrue();
            BulkExecutionMigrator.validate(fixture.ownerSource, BulkPostgresTestSupport.testRoleConfiguration());
            assertQueued(fixture, reservation, queue);
            fixture.observe("privileged-null-transition-denied", reservation.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void claimedUnitsCommitDomainAndReceiptInOnePhysicalTransactionAndReleaseActiveAtCompletion() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("unit-commit-replay-terminal")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var input = fixture.persist();
            var queued = fixture.enqueue(input, "unit-key", queue);
            var claimed = fixture.kernel.claim(CONTEXT, queued.executionId(), "unit-worker", active).orElseThrow();
            AtomicInteger admissions = new AtomicInteger();
            AtomicInteger callbacks = new AtomicInteger();
            var physical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            assertThatThrownBy(() -> fixture.kernel.executeUnit(queued.control(), 0, unit -> {
                admissions.incrementAndGet(); return BulkUnitAdmission.admit();
            }, unit -> {
                callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed();
            })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThat(admissions).hasValue(0);
            assertThat(callbacks).hasValue(0);
            assertThat(fixture.count("item_receipt")).isZero();
            assertThat(fixture.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
            // The public reservation gate stays SYNC-only, including an already claimed ASYNC replay.
            assertThatThrownBy(() -> fixture.kernel.reserve(CONTEXT, input.proposal().id(), "unit-key", "unit-worker",
                    REVISION, claimed.execution().deadlineAt()))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            var first = fixture.kernel.executeUnit(claimed.control(), 0, unit -> {
                admissions.incrementAndGet(); return BulkUnitAdmission.admit();
            }, unit -> {
                callbacks.incrementAndGet(); physical.set(fixture.writeDomain(unit)); return BulkUnitMutationResult.confirmed();
            });
            assertThat(first.receiptPresent()).isTrue();
            assertThat(first.replayed()).isFalse();
            assertThat(first.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
            assertThat(first.execution().nextOrdinal()).isEqualTo(1);
            fixture.assertPhysicalCommit(claimed.executionId(), physical.get());
            var receiptBefore = fixture.observer.queryForMap("select *,xmin::text::bigint as receipt_xid from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                    claimed.executionId());
            var replay = fixture.kernel.executeUnit(claimed.control(), 0, unit -> {
                admissions.incrementAndGet(); return BulkUnitAdmission.admit();
            }, unit -> {
                callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed();
            });
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.receiptPresent()).isTrue();
            assertThat(admissions).hasValue(1);
            assertThat(callbacks).hasValue(1);
            assertThat(fixture.observer.queryForMap("select *,xmin::text::bigint as receipt_xid from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                    claimed.executionId())).isEqualTo(receiptBefore);
            fixture.assertPhysicalCommit(claimed.executionId(), physical.get());
            var second = fixture.kernel.executeUnit(claimed.control(), 1, unit -> {
                admissions.incrementAndGet(); return BulkUnitAdmission.admit();
            }, unit -> {
                callbacks.incrementAndGet(); physical.set(fixture.writeDomain(unit)); return BulkUnitMutationResult.confirmed();
            });
            assertThat(second.status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
            assertThat(second.execution().nextOrdinal()).isEqualTo(2);
            assertThat(second.execution().receiptCount()).isEqualTo(2);
            assertThat(admissions).hasValue(2);
            assertThat(callbacks).hasValue(2);
            fixture.assertPhysicalCommit(claimed.executionId(), physical.get());
            assertReleased(fixture, claimed.executionId(), queue, active);
            var summary = fixture.kernel.summarizeConsistent(CONTEXT, claimed.executionId());
            assertThat(summary.status()).isEqualTo(BulkExecutionStatus.COMPLETED);
            assertThat(summary.totals().confirmed()).isEqualTo(2);
            assertThat(summary.totals().pending()).isZero();
            fixture.observe("two-units-committed-terminal", claimed.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void callbackFailureAfterDomainWriteRollsBackDomainAndReceiptTogether() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("unit-callback-rollback")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = fixture.enqueue(fixture.persist(), "rollback-key", queue);
            var claimed = fixture.kernel.claim(CONTEXT, queued.executionId(), "rollback-worker", active).orElseThrow();
            AtomicInteger callbacks = new AtomicInteger();
            var attempted = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var result = fixture.kernel.executeUnit(claimed.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                callbacks.incrementAndGet();
                attempted.set(fixture.writeDomain(unit));
                throw new IllegalStateException("fixture callback failure after domain write");
            });
            assertThat(callbacks).hasValue(1);
            assertThat(attempted.get()).isNotNull();
            assertThat(result.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(result.reasonCode()).isEqualTo(BulkUnitReasonCode.UNIT_ROLLED_BACK);
            assertThat(result.receiptPresent()).isFalse();
            assertThat(result.execution().nextOrdinal()).isZero();
            assertThat(fixture.count("item_receipt")).isZero();
            assertThat(fixture.count("admission")).isZero();
            assertThat(fixture.observer.queryForMap("select writes,last_pid,last_xid from occupancy_domain_witness where id=1"))
                    .containsEntry("writes", 0).containsEntry("last_pid", null).containsEntry("last_xid", null);
            assertReleased(fixture, claimed.executionId(), queue, active);
            fixture.observe("domain-and-receipt-rolled-back", claimed.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void fencedMarkerBlocksFreshUnitsEnqueueAndClaimButConfirmedReceiptReplaysAfterDeadline() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("marker-fence-receipt-replay")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var input = fixture.persist();
            var queued = fixture.enqueue(input, "fence-live-key", queue, Instant.now().plusSeconds(4));
            var claimed = fixture.kernel.claim(CONTEXT, queued.executionId(), "fence-worker", active).orElseThrow();
            AtomicInteger callbacks = new AtomicInteger();
            var physical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var committed = fixture.kernel.executeUnit(claimed.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                callbacks.incrementAndGet(); physical.set(fixture.writeDomain(unit)); return BulkUnitMutationResult.confirmed();
            });
            assertThat(committed.receiptPresent()).isTrue();
            fixture.assertPhysicalCommit(claimed.executionId(), physical.get());
            var other = fixture.enqueue(fixture.persist(), "fence-queued-key", queue);
            var pending = fixture.persist();
            var queuedBefore = fixture.slot(queue);
            var activeBefore = fixture.slot(active);
            var controlBefore = fixture.controlRow();
            var executionBefore = fixture.observer.queryForMap("select * from praxis_bulk.praxis_bulk_execution where execution_id=?",
                    claimed.executionId());
            var receiptBefore = fixture.observer.queryForMap("select *,xmin::text::bigint as receipt_xid from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                    claimed.executionId());
            fixture.installation.fence();
            assertThatThrownBy(() -> fixture.kernel.executeUnit(claimed.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
                callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed();
            })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThatThrownBy(() -> fixture.kernel.claim(CONTEXT, other.executionId(), "fenced-claim-worker", active))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThatThrownBy(() -> fixture.enqueue(pending, "fenced-new-key", queue))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThat(fixture.observer.queryForMap("select * from praxis_bulk.praxis_bulk_execution where execution_id=?",
                    claimed.executionId())).isEqualTo(executionBefore);
            assertThat(fixture.observer.queryForObject("select state from praxis_bulk.praxis_bulk_allocation where proposal_id=?",
                    String.class, pending.proposal().id())).isEqualTo("PENDING");
            assertThat(fixture.kernel.find(CONTEXT, other.executionId()).orElseThrow().status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
            assertThat(fixture.slot(queue)).isEqualTo(queuedBefore);
            assertThat(fixture.slot(active)).isEqualTo(activeBefore);
            assertThat(fixture.controlRow()).isEqualTo(controlBefore);
            assertThat(fixture.count("execution")).isEqualTo(2);
            assertThat(fixture.count("item_receipt")).isEqualTo(1);
            assertThat(fixture.count("admission")).isZero();
            assertThat(callbacks).hasValue(1);
            fixture.waitUntilDeadline(claimed.execution().deadlineAt());
            var replay = fixture.kernel.executeUnit(claimed.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed();
            });
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.receiptPresent()).isTrue();
            assertThat(replay.execution().nextOrdinal()).isEqualTo(1);
            assertThat(callbacks).hasValue(1);
            assertThat(fixture.observer.queryForMap("select *,xmin::text::bigint as receipt_xid from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                    claimed.executionId())).isEqualTo(receiptBefore);
            fixture.assertPhysicalCommit(claimed.executionId(), physical.get());
            assertThat(fixture.observer.queryForObject("select writes from occupancy_domain_witness where id=2", Integer.class)).isZero();
            var summary = fixture.kernel.summarizeConsistent(CONTEXT, claimed.executionId());
            assertThat(summary.totals().confirmed()).isEqualTo(1);
            assertThat(summary.totals().pending()).isEqualTo(1);
            fixture.observe("confirmed-replay-after-fence-deadline", claimed.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void conservativeRecoveryAfterFencePreservesConfirmedPrefixWithoutRepeatingMutation() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("recovery-confirmed-prefix")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = fixture.enqueue(fixture.persist(), "recovery-key", queue);
            var claimed = fixture.kernel.claim(CONTEXT, queued.executionId(), "original-worker", active).orElseThrow();
            AtomicInteger callbacks = new AtomicInteger();
            var physical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            fixture.kernel.executeUnit(claimed.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                callbacks.incrementAndGet(); physical.set(fixture.writeDomain(unit)); return BulkUnitMutationResult.confirmed();
            });
            fixture.assertPhysicalCommit(claimed.executionId(), physical.get());
            var receiptBefore = fixture.observer.queryForMap("select *,xmin::text::bigint as receipt_xid from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                    claimed.executionId());
            fixture.installation.fence();
            var recovery = fixture.kernel.recover(CONTEXT, claimed.executionId(), "explicit-recovery-owner");
            assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(recovery.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
            assertThat(recovery.execution().nextOrdinal()).isEqualTo(1);
            assertThat(recovery.receiptCount()).isEqualTo(1);
            assertThat(recovery.control().epoch()).isEqualTo(3);
            assertThat(recovery.control().ownerId()).isEqualTo("explicit-recovery-owner");
            assertThat(callbacks).hasValue(1);
            assertThat(fixture.observer.queryForMap("select *,xmin::text::bigint as receipt_xid from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                    claimed.executionId())).isEqualTo(receiptBefore);
            fixture.assertPhysicalCommit(claimed.executionId(), physical.get());
            assertThatThrownBy(() -> fixture.kernel.executeUnit(claimed.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
                callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed();
            })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThatThrownBy(() -> fixture.kernel.executeUnit(recovery.control(), 1, unit -> BulkUnitAdmission.admit(), unit -> {
                callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed();
            })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(callbacks).hasValue(1);
            assertThat(fixture.observer.queryForObject("select writes from occupancy_domain_witness where id=2", Integer.class)).isZero();
            assertReleased(fixture, claimed.executionId(), queue, active);
            fixture.observe("prefix-preserved-explicit-recovery", claimed.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void queuedRecoveryAfterFenceStopsConservativelyAndReleasesQueueWithoutClaimOrCallback() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("recovery-queued")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queued = fixture.enqueue(fixture.persist(), "queued-recovery-key", queue);
            fixture.installation.fence();
            var recovery = fixture.kernel.recover(CONTEXT, queued.executionId(), "queued-recovery-owner");
            assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(recovery.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
            assertThat(recovery.execution().nextOrdinal()).isZero();
            assertThat(recovery.receiptCount()).isZero();
            assertThat(recovery.control().epoch()).isEqualTo(2);
            assertFree(fixture, queue, 1);
            assertThat(fixture.allocation(queued.executionId())).containsEntry("kind", "EXECUTION_ASYNC")
                    .containsEntry("state", "RELEASED");
            AtomicInteger callbacks = new AtomicInteger();
            assertThatThrownBy(() -> fixture.kernel.executeUnit(recovery.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed();
            })).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                    error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(callbacks).hasValue(0);
            assertThat(fixture.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
            assertThat(fixture.count("item_receipt")).isZero();
            assertThat(fixture.count("admission")).isZero();
            assertThat(fixture.count("capacity_occupation")).isEqualTo(1);
            assertThat(fixture.observer.queryForObject("select active_token_id from praxis_bulk.praxis_bulk_execution where execution_id=?",
                    UUID.class, queued.executionId())).isNull();
            fixture.observe("queued-recovery-stopped-without-claim", queued.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void concurrentClaimersOfTheSameExecutionProduceOneOwnerAndOneActiveOccupation() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("claim-same-execution")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var queued = fixture.enqueue(fixture.persist(), "same-execution-key", queue);
            assertQueued(fixture, queued, queue);
            var allocationBefore = fixture.allocation(queued.executionId());
            var controlBefore = fixture.controlRow();
            try (var race = fixture.claimRace()) {
                var executor = Executors.newFixedThreadPool(2);
                try {
                    var first = executor.submit(() -> race.first(queued.executionId(), active));
                    race.awaitFirstBeforeCommit();
                    // Fresh MVCC observer cannot see A's provisional claim or slot changes.
                    assertQueued(fixture, queued, queue);
                    assertFree(fixture, active, 0);
                    var second = executor.submit(() -> race.second(queued.executionId(), active));
                    race.awaitBlockedAndRelease();
                    var winner = first.get(5, TimeUnit.SECONDS).orElseThrow();
                    assertThat(second.get(5, TimeUnit.SECONDS)).isEmpty();
                    assertActiveWinner(fixture, queued, winner, queue, active, allocationBefore.get("allocation_id"));
                    assertThat(fixture.count("execution")).isEqualTo(1);
                    assertThat(fixture.count("capacity_occupation")).isEqualTo(2);
                    assertThat(fixture.controlRow()).isEqualTo(controlBefore);
                    assertNoDomainOrChildren(fixture);
                    fixture.claimOutcome("A", "WON");
                    fixture.claimOutcome("B", "EMPTY_AFTER_WINNER_COMMIT");
                    fixture.observe("same-execution-one-owner-after-causal-wait", queued.executionId());
                    fixture.assertionsComplete();
                } finally {
                    race.release();
                    executor.shutdownNow();
                    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("claim threads terminated after release").isTrue();
                }
            }
        }
    }

    @Test
    void concurrentExecutionsDisputingOneActiveSlotRollBackTheLosingClaimCompletely() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("claim-one-active-slot")) {
            fixture.activate();
            UUID firstQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID secondQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var firstQueued = fixture.enqueue(fixture.persist(), "first-active-key", firstQueue);
            var secondQueued = fixture.enqueue(fixture.persist(), "second-active-key", secondQueue);
            var firstAllocation = fixture.allocation(firstQueued.executionId());
            var secondAllocation = fixture.allocation(secondQueued.executionId());
            var secondExecution = executionRow(fixture, secondQueued.executionId());
            var secondSlot = fixture.slot(secondQueue);
            var controlBefore = fixture.controlRow();
            try (var race = fixture.claimRace()) {
                var executor = Executors.newFixedThreadPool(2);
                try {
                    var first = executor.submit(() -> race.first(firstQueued.executionId(), active));
                    race.awaitFirstBeforeCommit();
                    assertThat(fixture.allocation(firstQueued.executionId())).isEqualTo(firstAllocation);
                    assertFree(fixture, active, 0);
                    // CAPACITY is an expected safe result only after real contention was observed.
                    var second = executor.submit(() -> {
                        try {
                            race.second(secondQueued.executionId(), active);
                            throw new AssertionError("Second execution unexpectedly acquired the occupied ACTIVE slot");
                        } catch (BulkDurableExecutionException failure) { return failure.reason(); }
                    });
                    race.awaitBlockedAndRelease();
                    var winner = first.get(5, TimeUnit.SECONDS).orElseThrow();
                    assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(BulkDurableExecutionException.Reason.CAPACITY);
                    assertActiveWinner(fixture, firstQueued, winner, firstQueue, active, firstAllocation.get("allocation_id"));
                    assertThat(executionRow(fixture, secondQueued.executionId())).isEqualTo(secondExecution);
                    assertThat(fixture.allocation(secondQueued.executionId())).isEqualTo(secondAllocation)
                            .containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "QUEUED");
                    assertThat(fixture.slot(secondQueue)).isEqualTo(secondSlot)
                            .containsEntry("occupancy_sequence", 1L).containsEntry("current_owner_epoch", 1L)
                            .containsEntry("current_execution_id", secondQueued.executionId());
                    var losingHistory = occupationRows(fixture, secondQueued.executionId());
                    assertThat(losingHistory).hasSize(1);
                    assertThat(losingHistory.getFirst()).containsEntry("token_id", secondQueue)
                            .containsEntry("occupancy_sequence", 1L).containsEntry("owner_epoch", 1L);
                    assertThat(fixture.count("execution")).isEqualTo(2);
                    assertThat(fixture.count("capacity_occupation")).isEqualTo(3);
                    assertThat(fixture.controlRow()).isEqualTo(controlBefore);
                    assertNoDomainOrChildren(fixture);
                    fixture.claimOutcome("A", "WON");
                    fixture.claimOutcome("B", "CAPACITY_WITH_FULL_ROLLBACK");
                    fixture.observe("losing-execution-still-queued-after-causal-wait", secondQueued.executionId());
                    fixture.assertionsComplete();
                } finally {
                    race.release();
                    executor.shutdownNow();
                    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("claim threads terminated after release").isTrue();
                }
            }
        }
    }

    @Test
    void releasedSlotsReuseIncreasingSequencesAndStaleExecutionControlsCannotTouchTheNewOccupation() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("claim-slot-reuse")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var firstQueued = fixture.enqueue(fixture.persist(), "reuse-first-key", queue);
            var first = fixture.kernel.claim(CONTEXT, firstQueued.executionId(), "reuse-first-worker", active).orElseThrow();
            var firstAllocationId = fixture.allocation(first.executionId()).get("allocation_id");
            var firstHistory = occupationRows(fixture, first.executionId());
            var stopped = fixture.kernel.recover(CONTEXT, first.executionId(), "reuse-recovery-owner");
            assertThat(stopped.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(stopped.control().epoch()).isEqualTo(3);
            assertThat(stopped.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
            assertThat(stopped.receiptCount()).isZero();
            assertFree(fixture, queue, 1);
            assertFree(fixture, active, 1);
            assertThat(fixture.allocation(first.executionId())).containsEntry("allocation_id", firstAllocationId)
                    .containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "RELEASED");
            assertThat(occupationRows(fixture, first.executionId())).isEqualTo(firstHistory);

            var nextQueued = fixture.enqueue(fixture.persist(), "reuse-next-key", queue);
            assertThat(fixture.slot(queue)).containsEntry("occupancy_sequence", 2L)
                    .containsEntry("current_execution_id", nextQueued.executionId()).containsEntry("current_owner_epoch", 1L);
            var nextAllocationId = fixture.allocation(nextQueued.executionId()).get("allocation_id");
            var next = fixture.kernel.claim(CONTEXT, nextQueued.executionId(), "reuse-next-worker", active).orElseThrow();
            assertThat(next.control().epoch()).isEqualTo(2);
            assertThat(next.executionId()).isNotEqualTo(first.executionId());
            assertFree(fixture, queue, 2);
            assertThat(fixture.slot(active)).containsEntry("occupancy_sequence", 2L)
                    .containsEntry("current_execution_id", next.executionId()).containsEntry("current_owner_epoch", 2L);
            assertThat(fixture.allocation(next.executionId())).containsEntry("allocation_id", nextAllocationId)
                    .containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "ACTIVE");
            var nextHistory = occupationRows(fixture, next.executionId());
            assertThat(nextHistory).hasSize(2);
            assertThat(nextHistory.get(0)).containsEntry("token_id", queue).containsEntry("occupancy_sequence", 2L)
                    .containsEntry("owner_epoch", 1L);
            assertThat(nextHistory.get(1)).containsEntry("token_id", active).containsEntry("occupancy_sequence", 2L)
                    .containsEntry("owner_epoch", 2L);
            var nextExecution = executionRow(fixture, next.executionId());
            var nextAllocation = fixture.allocation(next.executionId());
            var activeSlot = fixture.slot(active);
            var controlBefore = fixture.controlRow();
            var callbacks = new AtomicInteger();
            assertThatThrownBy(() -> fixture.kernel.executeUnit(first.control(), 0,
                    unit -> { callbacks.incrementAndGet(); return BulkUnitAdmission.admit(); },
                    unit -> { callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed(); }))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThat(fixture.kernel.claim(CONTEXT, first.executionId(), "stale-java-worker", active)).isEmpty();
            // Ordinary runtime invokes the existing protected entrypoint, with its required epoch1.
            // This is neither a public ASYNC API nor an invented caller-supplied slot CAS contract.
            assertThat(fixture.runtimeSql.queryForObject("select praxis_bulk.claim_capacity_execution(?,?,?,?,?)",
                    Boolean.class, first.executionId(), CONTEXT.namespaceId(), "stale-sql-worker", active, 1L)).isFalse();
            assertThat(callbacks).hasValue(0);
            assertThat(executionRow(fixture, next.executionId())).isEqualTo(nextExecution);
            assertThat(fixture.allocation(next.executionId())).isEqualTo(nextAllocation);
            assertThat(fixture.slot(active)).isEqualTo(activeSlot);
            assertThat(occupationRows(fixture, first.executionId())).isEqualTo(firstHistory);
            assertThat(occupationRows(fixture, next.executionId())).isEqualTo(nextHistory);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(4);
            assertThat(fixture.controlRow()).isEqualTo(controlBefore);
            assertNoDomainOrChildren(fixture);
            fixture.observe("tokens-reused-with-sequence-two-and-stale-controls-denied", next.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void saturatedQueueRetryPreservesPendingProposalAndReusesTheOriginalKeyAfterRelease() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("queue-local-saturation-retry")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var firstInput = fixture.persist();
            var secondInput = fixture.persist();
            Instant secondDeadline = Instant.now().plusSeconds(300);
            var first = fixture.enqueue(firstInput, "saturated-first-key", queue);
            var firstExecution = executionRow(fixture, first.executionId());
            var firstAllocation = fixture.allocation(first.executionId());
            var slot = fixture.slot(queue);
            var history = occupationRows(fixture, first.executionId());
            var controlBefore = fixture.controlRow();
            assertThatThrownBy(() -> fixture.enqueue(secondInput, "saturated-retry-key", queue, secondDeadline))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.CAPACITY));
            assertPendingWithoutExecution(fixture, secondInput);
            assertThat(executionRow(fixture, first.executionId())).isEqualTo(firstExecution);
            assertThat(fixture.allocation(first.executionId())).isEqualTo(firstAllocation);
            assertThat(fixture.slot(queue)).isEqualTo(slot);
            assertThat(occupationRows(fixture, first.executionId())).isEqualTo(history);
            var replay = fixture.enqueue(firstInput, "saturated-first-key", queue);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.executionId()).isEqualTo(first.executionId());
            assertThat(replay.execution().deadlineAt()).isEqualTo(first.execution().deadlineAt());
            assertThat(executionRow(fixture, first.executionId())).isEqualTo(firstExecution);
            fixture.kernel.requestCancel(CONTEXT, first.executionId());
            assertFree(fixture, queue, 1);
            var admitted = fixture.enqueue(secondInput, "saturated-retry-key", queue, secondDeadline);
            assertThat(admitted.replayed()).isFalse();
            assertThat(admitted.executionId()).isNotEqualTo(first.executionId());
            assertThat(fixture.slot(queue)).containsEntry("occupancy_sequence", 2L)
                    .containsEntry("current_execution_id", admitted.executionId()).containsEntry("current_owner_epoch", 1L);
            assertThat(fixture.allocation(admitted.executionId())).containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "QUEUED");
            var admittedReplay = fixture.enqueue(secondInput, "saturated-retry-key", queue, secondDeadline);
            assertThat(admittedReplay.replayed()).isTrue();
            assertThat(admittedReplay.executionId()).isEqualTo(admitted.executionId());
            assertThat(admittedReplay.execution().deadlineAt()).isEqualTo(admitted.execution().deadlineAt());
            assertThat(fixture.count("execution")).isEqualTo(2);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(2);
            assertThat(occupationRows(fixture, first.executionId())).isEqualTo(history);
            assertThat(fixture.controlRow()).isEqualTo(controlBefore);
            assertNoDomainOrChildren(fixture);
            fixture.observe("local-one-queue-slot-retry-keeps-key-and-reuses-sequence", admitted.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void enqueueReplaySurvivesFenceAndRealDeadlineWhileFreshEnqueueIsDenied() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("enqueue-replay-fence-deadline")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var firstInput = fixture.persist();
            var secondInput = fixture.persist();
            var first = fixture.enqueue(firstInput, "fence-replay-key", queue, Instant.now().plusSeconds(4));
            var executionBefore = executionRow(fixture, first.executionId());
            var allocationBefore = fixture.allocation(first.executionId());
            var queueBefore = fixture.slot(queue);
            var activeBefore = fixture.slot(active);
            var historyBefore = occupationRows(fixture, first.executionId());
            var controlBefore = fixture.controlRow();
            fixture.installation.fence();
            fixture.waitUntilDeadline(first.execution().deadlineAt());
            var replay = fixture.enqueue(firstInput, "fence-replay-key", queue);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.executionId()).isEqualTo(first.executionId());
            assertThat(replay.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
            assertThat(replay.execution().deadlineAt()).isEqualTo(first.execution().deadlineAt());
            assertThatThrownBy(() -> fixture.enqueue(secondInput, "fenced-new-key", queue))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            assertThatThrownBy(() -> fixture.kernel.claim(CONTEXT, first.executionId(), "fenced-deadline-worker", active))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.FENCED));
            var callbacks = new AtomicInteger();
            assertThatThrownBy(() -> fixture.kernel.executeUnit(first.control(), 0,
                    unit -> { callbacks.incrementAndGet(); return BulkUnitAdmission.admit(); },
                    unit -> { callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed(); }))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(callbacks).hasValue(0);
            assertPendingWithoutExecution(fixture, secondInput);
            assertThat(executionRow(fixture, first.executionId())).isEqualTo(executionBefore);
            assertThat(fixture.allocation(first.executionId())).isEqualTo(allocationBefore);
            assertThat(fixture.slot(queue)).isEqualTo(queueBefore);
            assertThat(fixture.slot(active)).isEqualTo(activeBefore);
            assertThat(occupationRows(fixture, first.executionId())).isEqualTo(historyBefore);
            assertThat(fixture.count("execution")).isEqualTo(1);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(1);
            assertThat(fixture.controlRow()).isEqualTo(controlBefore);
            assertNoDomainOrChildren(fixture);
            fixture.observe("enqueue-identity-replayed-after-marker-fence-and-real-deadline", first.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void queuedDeadlineClaimStopsWithoutCallbackAndFailedNewAdmissionDoesNotReserveItsKey() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("queued-deadline-key-retry")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var firstInput = fixture.persist();
            var secondInput = fixture.persist();
            var first = fixture.enqueue(firstInput, "expired-existing-key", queue, Instant.now().plusSeconds(4));
            fixture.waitUntilDeadline(first.execution().deadlineAt());
            var replay = fixture.enqueue(firstInput, "expired-existing-key", queue);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.executionId()).isEqualTo(first.executionId());
            assertThat(replay.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
            assertThat(fixture.kernel.claim(CONTEXT, first.executionId(), "expired-claim-worker", active)).isEmpty();
            var stopped = fixture.kernel.find(CONTEXT, first.executionId()).orElseThrow();
            assertThat(stopped.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(stopped.terminalReasonCode()).isEqualTo(BulkUnitReasonCode.DEADLINE_EXCEEDED);
            assertThat(stopped.nextOrdinal()).isZero();
            assertThat(stopped.deadlineAt()).isEqualTo(first.execution().deadlineAt());
            assertThat(stopped.control().executionId()).isEqualTo(first.control().executionId());
            assertThat(stopped.control().ownerId()).isEqualTo(first.control().ownerId());
            assertThat(stopped.control().epoch()).isEqualTo(first.control().epoch());
            assertFree(fixture, queue, 1);
            assertFree(fixture, active, 0);
            assertThat(fixture.allocation(first.executionId())).containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "RELEASED");
            var callbacks = new AtomicInteger();
            assertThatThrownBy(() -> fixture.kernel.executeUnit(stopped.control(), 0,
                    unit -> { callbacks.incrementAndGet(); return BulkUnitAdmission.admit(); },
                    unit -> { callbacks.incrementAndGet(); fixture.writeDomain(unit); return BulkUnitMutationResult.confirmed(); }))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(callbacks).hasValue(0);
            var terminalRow = executionRow(fixture, first.executionId());
            var terminalReplay = fixture.enqueue(firstInput, "expired-existing-key", queue);
            assertThat(terminalReplay.replayed()).isTrue();
            assertThat(terminalReplay.executionId()).isEqualTo(first.executionId());
            assertThat(terminalReplay.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(executionRow(fixture, first.executionId())).isEqualTo(terminalRow);
            Instant expiredDeadline = fixture.observer.queryForObject("select clock_timestamp()-interval '1 second'",
                    java.time.OffsetDateTime.class).toInstant();
            assertThatThrownBy(() -> fixture.enqueue(secondInput, "expired-new-retry-key", queue, expiredDeadline))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED));
            assertPendingWithoutExecution(fixture, secondInput);
            assertFree(fixture, queue, 1);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(1);
            var admitted = fixture.enqueue(secondInput, "expired-new-retry-key", queue);
            assertThat(admitted.replayed()).isFalse();
            assertThat(admitted.executionId()).isNotEqualTo(first.executionId());
            assertThat(fixture.slot(queue)).containsEntry("occupancy_sequence", 2L)
                    .containsEntry("current_execution_id", admitted.executionId()).containsEntry("current_owner_epoch", 1L);
            assertThat(fixture.count("execution")).isEqualTo(2);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(2);
            assertNoDomainOrChildren(fixture);
            fixture.observe("deadline-terminalized-queue-and-failed-new-key-retried", admitted.executionId());
            fixture.assertionsComplete();
        }
    }

    @Test
    void governedRetentionPurgesAsyncHistoryKeepsTombstoneAndAllowsSequenceTwoReuse() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("async-retention-tombstone-reuse")) {
            fixture.activate();
            UUID queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var input = fixture.persist();
            String key = "retention-original-key";
            var queued = fixture.enqueue(input, key, queue);
            var queuedRow = executionRow(fixture, queued.executionId());
            assertThat(fixture.purgeAsRetentionExecutor(queued.executionId())).isFalse();
            assertThat(executionRow(fixture, queued.executionId())).isEqualTo(queuedRow);
            var claimed = fixture.kernel.claim(CONTEXT, queued.executionId(), "retention-worker", active).orElseThrow();
            var runningRow = executionRow(fixture, claimed.executionId());
            assertThat(fixture.purgeAsRetentionExecutor(claimed.executionId())).isFalse();
            assertThat(executionRow(fixture, claimed.executionId())).isEqualTo(runningRow);
            var recovery = fixture.kernel.recover(CONTEXT, claimed.executionId(), "retention-recovery-owner");
            assertThat(recovery.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
            assertThat(recovery.execution().nextOrdinal()).isZero();
            assertThat(recovery.execution().terminalReasonCode()).isEqualTo(BulkUnitReasonCode.RECOVERY_STOPPED);
            assertThat(recovery.receiptCount()).isZero();
            assertReleased(fixture, claimed.executionId(), queue, active);
            assertThat(fixture.observer.queryForObject("select praxis_bulk.terminal_evidence_complete(?,0)",
                    Boolean.class, claimed.executionId())).isTrue();
            var youngRow = executionRow(fixture, claimed.executionId());
            var releasedAllocation = fixture.allocation(claimed.executionId());
            var history = occupationRows(fixture, claimed.executionId());
            assertThat(fixture.purgeAsRetentionExecutor(claimed.executionId())).isFalse();
            assertThat(executionRow(fixture, claimed.executionId())).isEqualTo(youngRow);
            assertThat(fixture.allocation(claimed.executionId())).isEqualTo(releasedAllocation);
            assertThat(occupationRows(fixture, claimed.executionId())).isEqualTo(history);
            assertThat(fixture.runtimeSql.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_capacity_occupation where execution_id=?
                    """, Long.class, claimed.executionId())).isEqualTo(2);
            // Real ordinary-runtime denial, without owner elevation or synthetic privileges.
            try (var connection = fixture.runtimeSource.getConnection()) {
                try (var statement = connection.prepareStatement("delete from praxis_bulk.praxis_bulk_capacity_occupation where execution_id=?")) {
                    statement.setObject(1, claimed.executionId());
                    assertThatThrownBy(statement::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
                }
                try (var statement = connection.prepareStatement("select praxis_bulk.purge_terminal_execution(?)")) {
                    statement.setObject(1, claimed.executionId());
                    assertThatThrownBy(statement::executeQuery).isInstanceOfSatisfying(SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
                }
            }
            fixture.ageStoppedExecutionForRetention(claimed.executionId());
            var aged = executionRow(fixture, claimed.executionId());
            var allocation = fixture.observer.queryForMap(
                    "select * from praxis_bulk.praxis_bulk_allocation where execution_id=?", claimed.executionId());
            assertThat(fixture.observer.queryForObject("""
                    select terminal_at<=clock_timestamp()-interval '30 days' and created_at<deadline_at
                       and deadline_at<=created_at+interval '30 minutes'
                       and created_at<=terminal_at and terminal_at<=updated_at
                      from praxis_bulk.praxis_bulk_execution where execution_id=?
                    """, Boolean.class, claimed.executionId())).isTrue();
            assertThat(fixture.observer.queryForObject("""
                    select bool_and(h.acquired_at between e.created_at and e.terminal_at)
                      from praxis_bulk.praxis_bulk_capacity_occupation h
                      join praxis_bulk.praxis_bulk_execution e on e.execution_id=h.execution_id where e.execution_id=?
                    """, Boolean.class, claimed.executionId())).isTrue();
            assertThat(fixture.purgeAsRetentionExecutor(claimed.executionId())).isTrue();
            assertThat(fixture.purgeAsRetentionExecutor(claimed.executionId())).isFalse();
            var tombstone = fixture.observer.queryForMap("select * from praxis_bulk.praxis_bulk_tombstone where execution_id=?",
                    claimed.executionId());
            assertThat(tombstone).containsEntry("execution_id", claimed.executionId()).containsEntry("proposal_id", input.proposal().id())
                    .containsEntry("namespace_id", CONTEXT.namespaceId()).containsEntry("resource_key", CONTEXT.resourceKey())
                    .containsEntry("operation_id", CONTEXT.operationRef().operationId()).containsEntry("terminal_status", "STOPPED")
                    .containsEntry("terminal_at", aged.get("terminal_at"))
                    .containsEntry("idempotency_key_digest", aged.get("idempotency_key_digest"))
                    .containsEntry("authorization_scope_digest_version", allocation.get("authorization_scope_digest_version"))
                    .containsEntry("authorization_scope_digest", allocation.get("authorization_scope_digest"));
            // The existing tombstone has terminal_status, not a fabricated stop-reason column.
            for (String table : List.of("execution", "proposal", "evaluation", "target_manifest", "preview_state",
                    "target_preview", "preview_item_integrity", "allocation", "capacity_occupation")) {
                assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_" + table, Long.class))
                        .as("purge removes execution-owned %s", table).isZero();
            }
            assertFree(fixture, queue, 1);
            assertFree(fixture, active, 1);
            assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Long.class)).isEqualTo(2);
            assertNoDomainOrChildren(fixture);
            assertThatThrownBy(() -> fixture.enqueue(input, "retention-new-key-for-purged-proposal", queue))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.RESULT_PURGED));
            var nextInput = fixture.persist();
            assertThatThrownBy(() -> fixture.enqueue(nextInput, key, queue))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(BulkDurableExecutionException.Reason.RESULT_PURGED));
            assertPendingWithoutExecution(fixture, nextInput);
            var nextQueued = fixture.enqueue(nextInput, "retention-reuse-valid-key", queue);
            var next = fixture.kernel.claim(CONTEXT, nextQueued.executionId(), "retention-next-worker", active).orElseThrow();
            assertFree(fixture, queue, 2);
            assertThat(fixture.slot(active)).containsEntry("occupancy_sequence", 2L)
                    .containsEntry("current_execution_id", next.executionId()).containsEntry("current_owner_epoch", 2L);
            var nextHistory = occupationRows(fixture, next.executionId());
            assertThat(nextHistory).hasSize(2);
            assertThat(nextHistory.get(0)).containsEntry("token_id", queue).containsEntry("occupancy_sequence", 2L).containsEntry("owner_epoch", 1L);
            assertThat(nextHistory.get(1)).containsEntry("token_id", active).containsEntry("occupancy_sequence", 2L).containsEntry("owner_epoch", 2L);
            assertThat(fixture.allocation(next.executionId())).containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "ACTIVE");
            assertThat(fixture.observer.queryForMap("select * from praxis_bulk.praxis_bulk_tombstone where execution_id=?",
                    claimed.executionId())).isEqualTo(tombstone);
            BulkExecutionMigrator.validate(fixture.ownerSource, BulkPostgresTestSupport.testRoleConfiguration());
            assertNoDomainOrChildren(fixture);
            fixture.observe("history-purged-tombstone-retained-and-slots-reused-at-sequence-two", next.executionId());
            fixture.assertionsComplete();
        }
    }

    private static void assertPendingWithoutExecution(BulkCapacityOccupancyPostgresFixture fixture, BulkEvaluationSnapshot input) {
        assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution where proposal_id=?",
                Long.class, input.proposal().id())).isZero();
        assertThat(fixture.observer.queryForObject("select state from praxis_bulk.praxis_bulk_allocation where proposal_id=?",
                String.class, input.proposal().id())).isEqualTo("PENDING");
    }

    private static java.util.Map<String, Object> executionRow(BulkCapacityOccupancyPostgresFixture fixture, UUID execution) {
        return fixture.observer.queryForMap("select e.* from praxis_bulk.praxis_bulk_execution e where execution_id=?", execution);
    }

    private static List<java.util.Map<String, Object>> occupationRows(BulkCapacityOccupancyPostgresFixture fixture, UUID execution) {
        return fixture.observer.queryForList("""
                select token_id,occupancy_sequence,owner_epoch from praxis_bulk.praxis_bulk_capacity_occupation
                 where execution_id=? order by owner_epoch
                """, execution);
    }

    private static void assertActiveWinner(BulkCapacityOccupancyPostgresFixture fixture, BulkExecutionReservation queued,
            BulkExecutionReservation winner, UUID queue, UUID active, Object allocationId) {
        assertThat(winner.executionId()).isEqualTo(queued.executionId());
        assertThat(winner.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
        assertThat(winner.control().ownerId()).isEqualTo("concurrent-worker-a");
        assertThat(winner.control().epoch()).isEqualTo(2);
        assertThat(winner.nextOrdinal()).isZero();
        assertThat(winner.execution().deadlineAt()).isEqualTo(queued.execution().deadlineAt());
        assertThat(fixture.allocation(winner.executionId())).containsEntry("allocation_id", allocationId)
                .containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "ACTIVE");
        assertFree(fixture, queue, 1);
        assertThat(fixture.slot(active)).containsEntry("occupancy_sequence", 1L)
                .containsEntry("current_execution_id", winner.executionId()).containsEntry("current_owner_epoch", 2L);
        var history = occupationRows(fixture, winner.executionId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("token_id", queue).containsEntry("occupancy_sequence", 1L)
                .containsEntry("owner_epoch", 1L);
        assertThat(history.get(1)).containsEntry("token_id", active).containsEntry("occupancy_sequence", 1L)
                .containsEntry("owner_epoch", 2L);
    }

    private static void assertNoDomainOrChildren(BulkCapacityOccupancyPostgresFixture fixture) {
        assertThat(fixture.count("item_receipt")).isZero();
        assertThat(fixture.count("admission")).isZero();
        assertThat(fixture.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
    }

    private static void assertReleased(BulkCapacityOccupancyPostgresFixture fixture, UUID execution, UUID queue, UUID active) {
        assertThat(fixture.allocation(execution)).containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "RELEASED")
                .containsEntry("release_reason", "TERMINAL_RECONCILED");
        assertThat(fixture.allocation(execution).get("released_at")).isNotNull();
        assertFree(fixture, queue, 1);
        assertFree(fixture, active, 1);
        assertThat(fixture.count("capacity_occupation")).isEqualTo(2);
        var history = fixture.observer.queryForList("""
                select token_id,occupancy_sequence,owner_epoch from praxis_bulk.praxis_bulk_capacity_occupation
                where execution_id=? order by owner_epoch
                """, execution);
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("token_id", queue).containsEntry("occupancy_sequence", 1L)
                .containsEntry("owner_epoch", 1L);
        assertThat(history.get(1)).containsEntry("token_id", active).containsEntry("occupancy_sequence", 1L)
                .containsEntry("owner_epoch", 2L);
        assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot where current_execution_id=?",
                Long.class, execution)).isZero();
    }

    private static void assertQueued(BulkCapacityOccupancyPostgresFixture fixture, BulkExecutionReservation reservation,
            UUID queue) {
        assertThat(reservation.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
        assertThat(reservation.nextOrdinal()).isZero();
        assertThat(reservation.control().epoch()).isEqualTo(1);
        assertThat(fixture.allocation(reservation.executionId())).containsEntry("kind", "EXECUTION_ASYNC")
                .containsEntry("state", "QUEUED");
        assertThat(fixture.slot(queue)).containsEntry("capacity_class", "QUEUE").containsEntry("occupancy_sequence", 1L)
                .containsEntry("current_execution_id", reservation.executionId()).containsEntry("current_owner_epoch", 1L);
        assertThat(fixture.count("capacity_occupation")).isEqualTo(1);
        assertThat(fixture.observer.queryForMap("""
                select token_id,occupancy_sequence,owner_epoch from praxis_bulk.praxis_bulk_capacity_occupation
                where execution_id=?
                """, reservation.executionId())).containsEntry("token_id", queue)
                .containsEntry("occupancy_sequence", 1L).containsEntry("owner_epoch", 1L);
        assertThat(fixture.observer.queryForObject("select state from praxis_bulk.praxis_bulk_allocation where proposal_id=?",
                String.class, reservation.proposalId())).isEqualTo("CONSUMED");
        assertThat(fixture.count("item_receipt")).isZero();
        assertThat(fixture.count("admission")).isZero();
        var summary = fixture.kernel.summarizeConsistent(CONTEXT, reservation.executionId());
        assertThat(summary.status()).isEqualTo(BulkExecutionStatus.QUEUED);
        assertThat(summary.totals().allPending()).isTrue();
        assertThat(summary.totals().pending()).isEqualTo(2);
        assertThat(fixture.observer.queryForObject("select active_attempt_id from praxis_bulk.praxis_bulk_execution where execution_id=?",
                UUID.class, reservation.executionId())).isNull();
    }

    private static void assertFree(BulkCapacityOccupancyPostgresFixture fixture, UUID token, long sequence) {
        assertThat(fixture.slot(token)).containsEntry("occupancy_sequence", sequence)
                .containsEntry("current_execution_id", null).containsEntry("current_owner_epoch", null);
    }

    private static void assertNoExecutionMutation(BulkCapacityOccupancyPostgresFixture fixture, BulkEvaluationSnapshot input) {
        for (String table : List.of("execution", "item_receipt", "admission", "capacity_occupation"))
            assertThat(fixture.count(table)).as(table).isZero();
        assertThat(fixture.observer.queryForObject("select state from praxis_bulk.praxis_bulk_allocation where proposal_id=?",
                String.class, input.proposal().id())).isEqualTo("PENDING");
    }
}
