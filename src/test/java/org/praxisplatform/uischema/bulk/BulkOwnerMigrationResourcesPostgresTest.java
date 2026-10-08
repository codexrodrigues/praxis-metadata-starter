package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Positive pool-size hypotheses, not accepted minimums. A real acquisition failure must remain
 * a test error in those hypotheses. Separate typed acquisition negatives prove cleanup and retry;
 * the sanitized manifest records each stage without relabeling historical hypothesis failures.
 * The observer is outside the measured owner pool and is used only for read-only evidence.
 */
class BulkOwnerMigrationResourcesPostgresTest {
    @Test
    void freshFlywayDdlFitsThreeOwnerPoolConnections() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
                var proof = new ResourceProof("owner-flyway-pool-three", postgres.getPostgresDatabase(), 3)) {
            try {
                int migrated = Flyway.configure().dataSource(proof.pool)
                        .locations("classpath:db/praxis-bulk-migrations")
                        .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                        .table("praxis_bulk_schema_history").createSchemas(true)
                        .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true)
                        .load().migrate().migrationsExecuted;
                assertThat(migrated).isEqualTo(19);
                proof.assertHistoryAndBootstrap("PENDING");
                proof.assertionsComplete = true;
            } catch (Exception | AssertionError failure) {
                proof.failure = failure;
                throw failure;
            }
        }
    }

    @Test
    void freshPublicOwnerMigrationFitsFourPoolConnections() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
                var proof = new ResourceProof("owner-public-migrate-pool-four", postgres.getPostgresDatabase(), 4)) {
            try {
                var roles = BulkExecutionRoleConfiguration.none("postgres");
                int migrated = BulkExecutionMigrator.migrate(proof.pool,
                        Map.of("tenant:prod:owner-resources", "deployment-owner-resources"), roles);
                assertThat(migrated).isEqualTo(19);
                proof.assertHistoryAndBootstrap("COMPLETE");
                BulkExecutionMigrator.validate(proof.pool, roles);
                proof.assertionsComplete = true;
            } catch (Exception | AssertionError failure) {
                proof.failure = failure;
                throw failure;
            }
        }
    }

    @Test
    void ownerPoolOneRejectsIndependentProbeAcquisitionBeforeAnyDdlAndClosesCleanly() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            DataSource ownerSource = postgres.getPostgresDatabase();
            var proof = new ResourceProof("owner-probe-pool-one-typed-negative", ownerSource, 1);
            proof.successOutcome = "TYPED_PROBE_ACQUISITION_NEGATIVE_CONFIRMED";
            proof.unfinishedOutcome = "TYPED_PROBE_ACQUISITION_NEGATIVE_NOT_CONFIRMED";
            try (proof) {
                try {
                    var rejected = assertThrows(IllegalStateException.class, () -> BulkExecutionMigrator.migrate(
                            proof.pool, Map.of("tenant:prod:owner-probe", "deployment-owner-probe"),
                            BulkExecutionRoleConfiguration.none("postgres")));
                    proof.failure = rejected;
                    assertThat(rejected).isExactlyInstanceOf(IllegalStateException.class)
                            .hasMessage("Unable to coordinate bulk schema upgrade");
                    assertThat(rejected.getCause()).isExactlyInstanceOf(SQLTransientConnectionException.class);
                    assertThat(java.util.Arrays.stream(rejected.getCause().getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals("com.zaxxer.hikari.pool.HikariPool")
                                    && frame.getMethodName().equals("getConnection")))
                            .as("the independent probe failed specifically during Hikari loan acquisition").isTrue();
                    assertThat(java.util.Arrays.stream(rejected.getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals(BulkExecutionMigrator.class.getName())
                                    && frame.getMethodName().equals("migrateSchemaWithHistoricalPreflight")))
                            .isTrue();
                    proof.assertAcquisitionFailureCleanup();
                    assertThat(proof.maxActive.get()).isEqualTo(1);
                    assertThat(proof.maxAwaiting.get()).isGreaterThanOrEqualTo(1);
                    try (var statement = proof.observer.createStatement()) {
                        statement.setQueryTimeout(1);
                        try (var rows = statement.executeQuery("select pg_catalog.to_regnamespace('praxis_bulk') is null "
                                + "and pg_catalog.to_regclass('praxis_bulk.praxis_bulk_schema_history') is null")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getBoolean(1)).isTrue();
                            assertThat(rows.next()).isFalse();
                        }
                    }
                    proof.cleanupEvidence.put("schemaAbsentBeforeShutdown", true);
                    proof.assertionsComplete = true;
                } catch (Exception | AssertionError failure) {
                    if (proof.failure == null) proof.failure = failure;
                    throw failure;
                }
            }
            // ResourceProof has closed the pool before this independent shutdown observation.
            var closed = assertClosedBeforeRetry(ownerSource, proof);
            closed.put("caseId", "owner-probe-pool-one-closed").put("logicalDatabase", "owner_resources_local")
                    .put("harnessPid", ProcessHandle.current().pid()).put("barriersUsed", false)
                    .put("subprocesses", 0).put("caseOutcome", "CLOSED_POOL_BACKENDS_ZERO_CONFIRMED");
            String configured = System.getProperty("praxis.bulk.proof.directory");
            Path directory = configured == null ? Files.createTempDirectory("praxis-owner-probe-closed-proof-")
                    : Path.of(configured);
            Files.createDirectories(directory);
            Path file = directory.resolve("owner-probe-pool-one-closed.json");
            Files.writeString(file, closed.toPrettyString(), StandardCharsets.UTF_8);
            if (configured == null) System.out.println("Owner probe shutdown manifest: " + file.toAbsolutePath());
        }
    }

    @Test
    void bareFlywayPoolTwoRejectsOnlyEventAcquisitionAndRetriesAfterPoolClosure() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            DataSource ownerSource = postgres.getPostgresDatabase();
            var insufficient = new ResourceProof("owner-bare-pool-two-typed-negative", ownerSource, 2);
            insufficient.successOutcome = "TYPED_ACQUISITION_NEGATIVE_CONFIRMED";
            insufficient.unfinishedOutcome = "TYPED_ACQUISITION_NEGATIVE_NOT_CONFIRMED";
            try (insufficient) {
                try {
                    Throwable rejected = catchThrowable(() -> Flyway.configure().dataSource(insufficient.pool)
                            .locations("classpath:db/praxis-bulk-migrations")
                            .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                            .table("praxis_bulk_schema_history").createSchemas(true)
                            .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true)
                            .load().migrate());
                    insufficient.failure = rejected;
                    assertTypedEventAcquisitionFailure(rejected);
                    insufficient.assertAcquisitionFailureCleanup();
                    insufficient.assertionsComplete = true;
                } catch (Exception | AssertionError failure) {
                    if (insufficient.failure == null) insufficient.failure = failure;
                    throw failure;
                }
            }
            var closed = assertClosedBeforeRetry(ownerSource, insufficient);
            try (var retry = new ResourceProof("owner-bare-pool-three-retry", ownerSource, 3)) {
                retry.successOutcome = "RETRY_POSITIVE_ASSERTIONS_COMPLETED";
                retry.unfinishedOutcome = "RETRY_POSITIVE_FAILED";
                retry.cleanupEvidence = closed;
                try {
                    var flyway = Flyway.configure().dataSource(retry.pool)
                            .locations("classpath:db/praxis-bulk-migrations")
                            .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                            .table("praxis_bulk_schema_history").createSchemas(true)
                            .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load();
                    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(19);
                    retry.assertHistoryAndBootstrap("PENDING");
                    // Bare Flyway certifies DDL/checksums, not the public serving bootstrap.
                    flyway.validate();
                    retry.assertionsComplete = true;
                } catch (Exception | AssertionError failure) {
                    retry.failure = failure;
                    throw failure;
                }
            }
        }
    }

    @Test
    void coordinatedOwnerPoolThreeRejectsOnlyEventAcquisitionAndRetriesAfterPoolClosure() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            DataSource ownerSource = postgres.getPostgresDatabase();
            var roles = BulkExecutionRoleConfiguration.none("postgres");
            var deployment = Map.of("tenant:prod:owner-resources-retry", "deployment-owner-resources-retry");
            var insufficient = new ResourceProof("owner-coord-pool-three-typed-negative", ownerSource, 3);
            insufficient.successOutcome = "TYPED_ACQUISITION_NEGATIVE_CONFIRMED";
            insufficient.unfinishedOutcome = "TYPED_ACQUISITION_NEGATIVE_NOT_CONFIRMED";
            try (insufficient) {
                try {
                    Throwable rejected = catchThrowable(() -> BulkExecutionMigrator.migrate(
                            insufficient.pool, deployment, roles));
                    insufficient.failure = rejected;
                    assertTypedEventAcquisitionFailure(rejected);
                    insufficient.assertAcquisitionFailureCleanup();
                    insufficient.assertionsComplete = true;
                } catch (Exception | AssertionError failure) {
                    if (insufficient.failure == null) insufficient.failure = failure;
                    throw failure;
                }
            }
            var closed = assertClosedBeforeRetry(ownerSource, insufficient);
            try (var retry = new ResourceProof("owner-coord-pool-four-retry", ownerSource, 4)) {
                retry.successOutcome = "RETRY_POSITIVE_ASSERTIONS_COMPLETED";
                retry.unfinishedOutcome = "RETRY_POSITIVE_FAILED";
                retry.cleanupEvidence = closed;
                try {
                    assertThat(BulkExecutionMigrator.migrate(retry.pool, deployment, roles)).isEqualTo(19);
                    retry.assertHistoryAndBootstrap("COMPLETE");
                    BulkExecutionMigrator.validate(retry.pool, roles);
                    retry.assertionsComplete = true;
                } catch (Exception | AssertionError failure) {
                    retry.failure = failure;
                    throw failure;
                }
            }
        }
    }

    @Test
    void coordinatorAdvisoryTimesOutWithZeroNativeLimitsThenRetriesOnTheSamePool() throws Exception {
        assertCoordinatorTimeoutAndRetry("owner-coord-query-budget-ten", false);
    }

    @Test
    void stricterNativeStatementTimeoutWinsAndSurvivesCoordinatorRollbackAndRetry() throws Exception {
        assertCoordinatorTimeoutAndRetry("owner-coord-native-budget-250ms", true);
    }

    private static void assertCoordinatorTimeoutAndRetry(String caseId, boolean stricterNativePolicy) throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            DataSource ownerSource = postgres.getPostgresDatabase();
            try (var proof = new ResourceProof(caseId, ownerSource, 4, stricterNativePolicy);
                    var holder = ownerSource.getConnection();
                    var witness = ownerSource.getConnection()) {
                proof.successOutcome = "COORDINATOR_TIMEOUT_AND_RETRY_CONFIRMED";
                proof.unfinishedOutcome = "COORDINATOR_TIMEOUT_OR_RETRY_NOT_CONFIRMED";
                proof.barriersUsed = true;
                var before = poolNativeTimeouts(proof.pool);
                assertThat(before.get("statementTimeout").asText()).isEqualTo(stricterNativePolicy ? "250ms" : "0");
                assertThat(before.get("lockTimeout").asText()).isEqualTo("0");
                holder.setAutoCommit(false);
                int holderPid;
                try (var statement = holder.createStatement(); var rows = statement.executeQuery("select pg_backend_pid()")) {
                    assertThat(rows.next()).isTrue();
                    holderPid = rows.getInt(1);
                    assertThat(rows.next()).isFalse();
                }
                try (var statement = holder.createStatement()) {
                    statement.execute("select pg_advisory_xact_lock(1347574124,5)");
                }
                witness.setReadOnly(true);
                try (var statement = witness.createStatement()) {
                    statement.setQueryTimeout(1);
                    try (var rows = statement.executeQuery("""
                            select to_regclass('praxis_bulk.praxis_bulk_schema_history') is null,
                                   to_regclass('praxis_bulk.praxis_bulk_capacity_read_bootstrap') is null,
                                   to_regclass('praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap') is null
                            """)) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getBoolean(1)).isTrue();
                        assertThat(rows.getBoolean(2)).isTrue();
                        assertThat(rows.getBoolean(3)).isTrue();
                        assertThat(rows.next()).isFalse();
                    }
                }
                var roles = BulkExecutionRoleConfiguration.none("postgres");
                var deployment = Map.of("tenant:prod:owner-coordinator-budget", "deployment-owner-coordinator-budget");
                var caller = Executors.newSingleThreadExecutor();
                var call = caller.submit(() -> BulkExecutionMigrator.migrate(proof.pool, deployment, roles));
                try {
                    var edge = awaitCoordinatorBlockingEdge(witness, proof.applicationName, holderPid);
                    proof.coordinatorEvidence = edge;
                    edge.put("historyAndBootstrapsInitiallyAbsent", true);
                    edge.set("nativeBefore", before);
                    assertThat(edge.get("waiterPid").asInt()).isEqualTo(before.get("backendPid").asInt());
                    // The holder stays locked until the database cancellation has actually occurred.
                    // This observation window is not a scheduler or whole-migration SLA.
                    var rejected = assertThrows(ExecutionException.class, () -> call.get(15, TimeUnit.SECONDS));
                    proof.failure = rejected.getCause();
                    assertThat(proof.failure).isExactlyInstanceOf(IllegalStateException.class)
                            .hasMessage("Unable to coordinate bulk schema upgrade");
                    assertThat(proof.failure.getCause()).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) proof.failure.getCause()).getSQLState()).isEqualTo("57014");
                    edge.put("sqlState", "57014");
                    // The existing cleanup helper checks loans/history, independent of failure classification.
                    proof.assertAcquisitionFailureCleanup();
                    var returned = poolNativeTimeouts(proof.pool);
                    edge.set("nativeAfterRollback", returned);
                    assertThat(returned).isEqualTo(before);
                    holder.rollback();
                    assertThat(BulkExecutionMigrator.migrate(proof.pool, deployment, roles)).isEqualTo(19);
                    proof.assertHistoryAndBootstrap("COMPLETE");
                    BulkExecutionMigrator.validate(proof.pool, roles);
                    var afterRetry = poolNativeTimeouts(proof.pool);
                    edge.set("nativeAfterRetry", afterRetry);
                    assertThat(afterRetry.get("statementTimeout")).isEqualTo(before.get("statementTimeout"));
                    assertThat(afterRetry.get("lockTimeout")).isEqualTo(before.get("lockTimeout"));
                    proof.assertionsComplete = true;
                } catch (Exception | AssertionError failure) {
                    if (proof.failure == null) proof.failure = failure;
                    throw failure;
                } finally {
                    // Release the real database barrier before cancelling/joining the caller on every exit.
                    try { holder.rollback(); }
                    finally {
                        call.cancel(true);
                        caller.shutdownNow();
                        if (!caller.awaitTermination(15, TimeUnit.SECONDS))
                            throw new IllegalStateException("Owner coordinator caller did not stop after holder release");
                    }
                }
            }
        }
    }

    private static ObjectNode poolNativeTimeouts(HikariDataSource pool) throws SQLException {
        try (var connection = pool.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("select pg_backend_pid(),current_setting('statement_timeout'),"
                        + "current_setting('lock_timeout')")) {
            assertThat(rows.next()).isTrue();
            var result = JsonNodeFactory.instance.objectNode().put("backendPid", rows.getInt(1))
                    .put("statementTimeout", rows.getString(2)).put("lockTimeout", rows.getString(3));
            assertThat(rows.next()).isFalse();
            return result;
        }
    }

    private static ObjectNode awaitCoordinatorBlockingEdge(Connection witness, String applicationName, int holderPid)
            throws Exception {
        try (var statement = witness.prepareStatement("""
                select a.pid,a.backend_xid::text,a.wait_event_type,a.wait_event
                  from pg_stat_activity a
                 where a.datname=current_database() and a.application_name=?
                   and ?=any(pg_blocking_pids(a.pid))
                   and exists(select 1 from pg_locks l where l.pid=a.pid and l.locktype='advisory'
                       and l.classid=1347574124 and l.objid=5 and l.objsubid=2 and not l.granted)
                """)) {
            statement.setQueryTimeout(1);
            statement.setString(1, applicationName);
            statement.setInt(2, holderPid);
            long bound = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
            do {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        var edge = JsonNodeFactory.instance.objectNode().put("holderPid", holderPid)
                                .put("waiterPid", rows.getInt(1)).put("blockingPid", holderPid)
                                .put("advisoryKeyClass", 1347574124).put("advisoryKeyObject", 5)
                                .put("waitType", rows.getString(3)).put("waitEvent", rows.getString(4));
                        String xid = rows.getString(2);
                        if (xid != null && xid.matches("[0-9]{1,20}")) edge.put("waiterXid", xid);
                        assertThat(edge.get("waitType").asText()).isEqualTo("Lock");
                        assertThat(edge.get("waitEvent").asText()).isEqualTo("advisory");
                        assertThat(rows.next()).isFalse();
                        return edge;
                    }
                }
                if (System.nanoTime() >= bound) break;
                TimeUnit.MILLISECONDS.sleep(5);
            } while (true);
            throw new AssertionError("Expected actual coordinator advisory wait edge was not observed");
        }
    }

    @Test
    void twoOwnersSharingPoolFiveSerializeFreshMigrationOnce() throws Exception {
        assertTwoOwnersShareBoundedPool("owner-concurrent-pool-five-positive", 5, false);
    }

    @Test
    void twoOwnersSharingPoolFourExposeTypedEventAcquisitionFailureThenRecover() throws Exception {
        assertTwoOwnersShareBoundedPool("owner-concurrent-pool-four-typed-negative", 4, true);
    }

    private static void assertTwoOwnersShareBoundedPool(String caseId, int capacity,
            boolean expectAcquisitionFailure) throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            DataSource ownerSource = postgres.getPostgresDatabase();
            try (var proof = new ResourceProof(caseId, ownerSource, capacity);
                    var holder = ownerSource.getConnection();
                    var witness = ownerSource.getConnection()) {
                proof.barriersUsed = true;
                proof.successOutcome = expectAcquisitionFailure
                        ? "TWO_OWNER_TYPED_FAILURE_PEER_COMPLETION_RETRY_CONFIRMED"
                        : "TWO_OWNER_SHARED_POOL_POSITIVE_CONFIRMED";
                proof.unfinishedOutcome = "TWO_OWNER_SHARED_POOL_HYPOTHESIS_FAILED";
                proof.concurrentEvidence = JsonNodeFactory.instance.objectNode()
                        .put("callers", 2).put("callerToBackendMapping", "NOT_ESTABLISHED");
                holder.setAutoCommit(false);
                int holderPid;
                try (var statement = holder.createStatement(); var rows = statement.executeQuery("select pg_backend_pid()")) {
                    assertThat(rows.next()).isTrue();
                    holderPid = rows.getInt(1);
                    assertThat(rows.next()).isFalse();
                }
                try (var statement = holder.createStatement()) {
                    statement.execute("select pg_advisory_xact_lock(1347574124,5)");
                }
                witness.setReadOnly(true);
                var roles = BulkExecutionRoleConfiguration.none("postgres");
                var deployment = Map.of("tenant:prod:owner-shared-pool", "deployment-owner-shared-pool");
                var callers = Executors.newFixedThreadPool(2);
                var first = callers.submit(() -> BulkExecutionMigrator.migrate(proof.pool, deployment, roles));
                var second = callers.submit(() -> BulkExecutionMigrator.migrate(proof.pool, deployment, roles));
                try {
                    proof.concurrentEvidence.set("initialWaits", awaitTwoCoordinatorWaiters(
                            witness, proof.pool, proof.applicationName, holderPid));
                    holder.rollback();
                    if (expectAcquisitionFailure)
                        proof.concurrentEvidence.set("eventAcquisitionPressure", awaitPeerAndPoolPressure(
                                witness, proof.pool, proof.applicationName));
                    var outcomes = List.of(ownerOutcome(first), ownerOutcome(second));
                    var recorded = proof.concurrentEvidence.putArray("callerOutcomes");
                    for (int i = 0; i < outcomes.size(); i++) {
                        var result = outcomes.get(i);
                        var event = recorded.addObject().put("submissionIndex", i + 1);
                        if (result.failure() == null) event.put("migrations", result.migrations());
                        else event.set("failure", ResourceProof.safeFailure(result.failure()));
                    }
                    var failed = outcomes.stream().filter(result -> result.failure() != null).toList();
                    var applied = outcomes.stream().filter(result -> result.failure() == null)
                            .map(OwnerOutcome::migrations).toList();
                    if (expectAcquisitionFailure) {
                        assertThat(failed).hasSize(1);
                        proof.failure = failed.getFirst().failure();
                        assertTypedEventAcquisitionFailure(proof.failure);
                        assertThat(applied).containsExactly(19);
                    } else {
                        assertThat(failed).isEmpty();
                        assertThat(applied).containsExactlyInAnyOrder(19, 0);
                    }
                    proof.assertHistoryAndBootstrap("COMPLETE");
                    assertSharedOwnerPoolReleased(proof);
                    BulkExecutionMigrator.validate(proof.pool, roles);
                    if (expectAcquisitionFailure) {
                        // The peer legitimately applied V19; this case must not assert history0.
                        var beforeRetry = ownerHistoryAndBootstrapRows(proof.observer);
                        assertThat(BulkExecutionMigrator.migrate(proof.pool, deployment, roles)).isZero();
                        BulkExecutionMigrator.validate(proof.pool, roles);
                        assertThat(beforeRetry.equals(ownerHistoryAndBootstrapRows(proof.observer)))
                                .as("same-pool retry preserves full history and bootstrap rows").isTrue();
                        proof.concurrentEvidence.put("retryMigrations", 0).put("retryRowsUnchanged", true);
                        assertSharedOwnerPoolReleased(proof);
                    }
                    proof.assertionsComplete = true;
                } catch (Exception | AssertionError failure) {
                    if (proof.failure == null) proof.failure = failure;
                    throw failure;
                } finally {
                    // Both callers must be able to finish before cancellation/join on every failure path.
                    try { holder.rollback(); }
                    finally {
                        first.cancel(true);
                        second.cancel(true);
                        callers.shutdownNow();
                        if (!callers.awaitTermination(15, TimeUnit.SECONDS))
                            throw new IllegalStateException("Shared-pool owners did not stop after holder release");
                    }
                }
            }
        }
    }

    private record OwnerOutcome(Integer migrations, Throwable failure) {
    }

    private static OwnerOutcome ownerOutcome(Future<Integer> future) throws Exception {
        try { return new OwnerOutcome(future.get(15, TimeUnit.SECONDS), null); }
        catch (ExecutionException failure) { return new OwnerOutcome(null, failure.getCause()); }
    }

    private static ObjectNode awaitTwoCoordinatorWaiters(Connection witness, HikariDataSource pool,
            String applicationName, int holderPid) throws Exception {
        try (var statement = witness.prepareStatement("""
                select a.pid,pg_blocking_pids(a.pid)
                  from pg_stat_activity a
                 where a.datname=current_database() and a.application_name=?
                   and ?=any(pg_blocking_pids(a.pid))
                   and exists(select 1 from pg_locks l where l.pid=a.pid and l.locktype='advisory'
                       and l.classid=1347574124 and l.objid=5 and l.objsubid=2 and not l.granted)
                 order by a.pid
                """)) {
            statement.setQueryTimeout(1);
            statement.setString(1, applicationName);
            statement.setInt(2, holderPid);
            long bound = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
            do {
                var observed = JsonNodeFactory.instance.objectNode().put("holderPid", holderPid)
                        .put("advisoryKeyClass", 1347574124).put("advisoryKeyObject", 5);
                var edges = observed.putArray("waiters");
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var edge = edges.addObject().put("waiterPid", rows.getInt(1));
                        var blockers = edge.putArray("blockedByPids");
                        var array = rows.getArray(2);
                        try {
                            for (var value : (Object[]) array.getArray()) {
                                int pid = ((Number) value).intValue();
                                assertThat(pid).isPositive();
                                blockers.add(pid);
                            }
                        } finally { array.free(); }
                    }
                }
                int active = pool.getHikariPoolMXBean().getActiveConnections();
                if (edges.size() == 2 && active >= 2) {
                    assertThat(edges.get(0).get("waiterPid").asInt())
                            .isNotEqualTo(edges.get(1).get("waiterPid").asInt());
                    return observed.put("observedActiveLoans", active);
                }
                if (System.nanoTime() >= bound) break;
                TimeUnit.MILLISECONDS.sleep(5);
            } while (true);
            throw new AssertionError("Two real shared-pool coordinator waiters were not observed");
        }
    }

    private static ObjectNode awaitPeerAndPoolPressure(Connection witness, HikariDataSource pool,
            String applicationName) throws Exception {
        try (var statement = witness.prepareStatement("""
                select g.pid,p.pid
                  from pg_locks g join pg_locks p
                    on p.locktype=g.locktype and p.classid=g.classid and p.objid=g.objid and p.objsubid=g.objsubid
                  join pg_stat_activity ga on ga.pid=g.pid join pg_stat_activity pa on pa.pid=p.pid
                 where g.locktype='advisory' and g.classid=1347574124 and g.objid=5 and g.objsubid=2
                   and g.granted and not p.granted and ga.datname=current_database()
                   and pa.datname=current_database() and ga.application_name=? and pa.application_name=?
                   and g.pid=any(pg_blocking_pids(p.pid))
                """)) {
            statement.setQueryTimeout(1);
            statement.setString(1, applicationName);
            statement.setString(2, applicationName);
            long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        int ownerPid = rows.getInt(1);
                        int peerPid = rows.getInt(2);
                        int active = pool.getHikariPoolMXBean().getActiveConnections();
                        int awaiting = pool.getHikariPoolMXBean().getThreadsAwaitingConnection();
                        if (active == 4 && awaiting >= 1) {
                            assertThat(ownerPid).isNotEqualTo(peerPid);
                            assertThat(rows.next()).isFalse();
                            return JsonNodeFactory.instance.objectNode().put("keyOwnerPid", ownerPid)
                                    .put("peerWaiterPid", peerPid).put("blockedByPid", ownerPid)
                                    .put("activeLoans", active).put("awaitingPoolConnection", awaiting)
                                    .put("advisoryKeyClass", 1347574124).put("advisoryKeyObject", 5);
                        }
                    }
                }
                if (System.nanoTime() >= bound) break;
                TimeUnit.MILLISECONDS.sleep(5);
            } while (true);
            throw new AssertionError("Event-acquisition pressure with a real peer coordinator loan was not observed");
        }
    }

    private static void assertSharedOwnerPoolReleased(ResourceProof proof) throws SQLException {
        assertThat(proof.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(proof.pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
        try (var statement = proof.observer.prepareStatement("""
                select (select count(*) from pg_stat_activity
                         where datname=current_database() and application_name=? and state like 'idle in transaction%'),
                       (select count(*) from praxis_bulk.praxis_bulk_schema_history where version='19' and success)
                """)) {
            statement.setQueryTimeout(1);
            statement.setString(1, proof.applicationName);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
                assertThat(rows.getInt(2)).isEqualTo(1);
                assertThat(rows.next()).isFalse();
            }
        }
        proof.concurrentEvidence.put("activeAfterCalls", 0).put("awaitingAfterCalls", 0)
                .put("idleTransactionBackendsAfterCalls", 0).put("successfulHistory19Rows", 1);
    }

    private static Map<String, List<String>> ownerHistoryAndBootstrapRows(Connection observer) throws SQLException {
        var snapshot = new java.util.LinkedHashMap<String, List<String>>();
        try (var statement = observer.createStatement()) {
            statement.setQueryTimeout(1);
            for (String table : List.of("praxis_bulk_schema_history", "praxis_bulk_manifest_bootstrap",
                    "praxis_bulk_preview_bootstrap", "praxis_bulk_preview_integrity_bootstrap",
                    "praxis_bulk_preview_reader_bootstrap", "praxis_bulk_atomic_bootstrap",
                    "praxis_bulk_capacity_read_bootstrap", "praxis_bulk_capacity_occupancy_bootstrap")) {
                var entries = new ArrayList<String>();
                try (var rows = statement.executeQuery("select to_jsonb(t)::text from praxis_bulk." + table + " t order by 1")) {
                    while (rows.next()) entries.add(rows.getString(1));
                }
                snapshot.put(table, entries);
            }
        }
        return snapshot;
    }

    private static void assertTypedEventAcquisitionFailure(Throwable failure) {
        assertThat(failure).isExactlyInstanceOf(FlywaySqlUnableToConnectToDbException.class);
        assertThat(failure.getCause()).isExactlyInstanceOf(SQLTransientConnectionException.class);
        assertThat(java.util.Arrays.stream(failure.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals("org.flywaydb.core.internal.database.base.Database")
                        && frame.getMethodName().equals("getEventConnection")))
                .as("Flyway failed specifically while acquiring its event connection").isTrue();
        assertThat(java.util.Arrays.stream(failure.getCause().getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals("com.zaxxer.hikari.pool.HikariPool")
                        && frame.getMethodName().equals("getConnection")))
                .as("the exact cause came from Hikari acquisition").isTrue();
    }

    /** An observation bound for teardown evidence, not a promised shutdown latency. */
    private static ObjectNode assertClosedBeforeRetry(DataSource ownerSource, ResourceProof previous) throws Exception {
        assertThat(previous.pool.isClosed()).isTrue();
        try (var witness = ownerSource.getConnection();
                var statement = witness.prepareStatement(
                        "select count(*) from pg_stat_activity where datname=current_database() and application_name=?")) {
            witness.setReadOnly(true);
            statement.setQueryTimeout(1);
            statement.setString(1, previous.applicationName);
            long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            int remaining;
            do {
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    remaining = rows.getInt(1);
                    assertThat(rows.next()).isFalse();
                }
                if (remaining == 0 || System.nanoTime() >= bound) break;
                TimeUnit.MILLISECONDS.sleep(10);
            } while (true);
            assertThat(remaining).as("closed prior pool has no remaining database backends").isZero();
            return JsonNodeFactory.instance.objectNode().put("priorCaseId", previous.caseId)
                    .put("priorPoolClosedBeforeRetry", previous.pool.isClosed()).put("priorPoolBackends", remaining);
        }
    }

    private static final class ResourceProof implements AutoCloseable {
        private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
        private final String caseId;
        private final int capacity;
        private final String applicationName;
        private final HikariDataSource pool;
        private final Connection observer;
        private final ScheduledExecutorService sampler;
        private final List<ObjectNode> samples = new CopyOnWriteArrayList<>();
        private final List<ObjectNode> samplingFailures = new CopyOnWriteArrayList<>();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger maxTotal = new AtomicInteger();
        private final AtomicInteger maxAwaiting = new AtomicInteger();
        private final AtomicInteger omittedSamples = new AtomicInteger();
        private final String flywayVersion;
        private final String postgresVersion;
        private final String jdbcVersion;
        private final int observerPid;
        private final long started = System.nanoTime();
        private volatile Throwable failure;
        private boolean assertionsComplete;
        private String successOutcome = "POSITIVE_ASSERTIONS_COMPLETED";
        private String unfinishedOutcome = "POSITIVE_HYPOTHESIS_FAILED";
        private ObjectNode cleanupEvidence;
        private ObjectNode coordinatorEvidence;
        private ObjectNode concurrentEvidence;
        private boolean barriersUsed;

        ResourceProof(String caseId, DataSource postgresSource, int capacity) throws Exception {
            this(caseId, postgresSource, capacity, false);
        }

        ResourceProof(String caseId, DataSource postgresSource, int capacity, boolean stricterNativePolicy) throws Exception {
            this.caseId = caseId;
            this.capacity = capacity;
            applicationName = "bulk_" + caseId.replace('-', '_');
            observer = postgresSource.getConnection();
            HikariDataSource candidate = null;
            ScheduledExecutorService candidateSampler = null;
            try {
                observer.setReadOnly(true);
                postgresVersion = safeVersion(observer.getMetaData().getDatabaseProductVersion());
                jdbcVersion = safeVersion(observer.getMetaData().getDriverVersion());
                flywayVersion = flywayVersion();
                try (var statement = observer.createStatement();
                        var rows = statement.executeQuery("select pg_backend_pid()")) {
                    assertThat(rows.next()).isTrue();
                    observerPid = rows.getInt(1);
                    assertThat(rows.next()).isFalse();
                }
                candidate = new HikariDataSource();
                candidate.setDataSource(postgresSource);
                candidate.setPoolName(applicationName);
                candidate.setMaximumPoolSize(capacity);
                candidate.setMinimumIdle(0);
                candidate.setConnectionTimeout(1000);
                candidate.setValidationTimeout(500);
                candidate.setInitializationFailTimeout(0);
                candidate.setConnectionInitSql("set application_name='" + applicationName + "'"
                        + (stricterNativePolicy ? "; set statement_timeout='250ms'" : ""));
                pool = candidate;
                // Start the lazy Hikari pool without retaining a fixture connection during migration.
                try (var connection = pool.getConnection()) {
                    assertThat(connection.getAutoCommit()).isTrue();
                    try (var statement = connection.createStatement();
                            var rows = statement.executeQuery("select current_user")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("postgres");
                        assertThat(rows.next()).isFalse();
                    }
                }
                candidateSampler = Executors.newSingleThreadScheduledExecutor();
                sampler = candidateSampler;
                sample();
                sampler.scheduleWithFixedDelay(this::sample, 0, 5, TimeUnit.MILLISECONDS);
            } catch (Exception | AssertionError setupFailure) {
                if (candidateSampler != null) candidateSampler.shutdownNow();
                if (candidate != null) candidate.close();
                observer.close();
                throw setupFailure;
            }
        }

        /** Polling measures real resources; no barrier or delay is used to manufacture a winner. */
        private void sample() {
            try {
                var metrics = pool.getHikariPoolMXBean();
                int active = metrics.getActiveConnections();
                int total = metrics.getTotalConnections();
                int awaiting = metrics.getThreadsAwaitingConnection();
                maxActive.accumulateAndGet(active, Math::max);
                maxTotal.accumulateAndGet(total, Math::max);
                maxAwaiting.accumulateAndGet(awaiting, Math::max);
                var event = JSON.objectNode().put("elapsedMillis", elapsedMillis())
                        .put("active", active).put("idle", metrics.getIdleConnections())
                        .put("total", total).put("awaiting", awaiting);
                var backends = event.putArray("backends");
                try (var statement = observer.prepareStatement("""
                        select pid,backend_xid::text,state,wait_event_type,wait_event
                          from pg_stat_activity
                         where datname=current_database() and application_name=? order by pid
                        """)) {
                    statement.setQueryTimeout(1);
                    statement.setString(1, applicationName);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            var backend = backends.addObject().put("pid", rows.getInt(1));
                            String xid = rows.getString(2);
                            if (xid != null && xid.matches("[0-9]{1,20}")) backend.put("xid", xid);
                            String state = rows.getString(3);
                            backend.put("state", state != null && List.of("active", "idle", "idle in transaction",
                                    "idle in transaction (aborted)", "disabled").contains(state) ? state : "OTHER");
                            backend.put("waitType", safeSymbol(rows.getString(4)));
                            backend.put("waitEvent", safeSymbol(rows.getString(5)));
                        }
                    }
                }
                if (samples.size() < 512) samples.add(event);
                else {
                    samples.set(511, event);
                    omittedSamples.incrementAndGet();
                }
            } catch (Exception evidenceFailure) {
                if (samplingFailures.size() < 16) samplingFailures.add(safeFailure(evidenceFailure));
            }
        }

        private void assertHistoryAndBootstrap(String expectedPhase) throws SQLException {
            // The sampler is the only concurrent user of this observer until it is stopped.
            stopSampler();
            assertThat(samplingFailures).isEmpty();
            var phase = committedPhase();
            assertThat(phase.get("historyRows").asInt()).isEqualTo(19);
            assertThat(phase.get("latestVersion").asInt()).isEqualTo(19);
            assertThat(phase.get("readBootstrap").asText()).isEqualTo(expectedPhase);
            assertThat(phase.get("occupancyBootstrap").asText()).isEqualTo(expectedPhase);
            assertThat(maxTotal.get()).isLessThanOrEqualTo(capacity);
        }

        private void assertAcquisitionFailureCleanup() throws SQLException {
            stopSampler();
            sample();
            assertThat(samplingFailures).isEmpty();
            var metrics = pool.getHikariPoolMXBean();
            assertThat(metrics.getActiveConnections()).isZero();
            assertThat(metrics.getThreadsAwaitingConnection()).isZero();
            int idleTransactions;
            try (var statement = observer.prepareStatement("""
                    select count(*) from pg_stat_activity
                     where datname=current_database() and application_name=?
                       and state like 'idle in transaction%'
                    """)) {
                statement.setQueryTimeout(1);
                statement.setString(1, applicationName);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    idleTransactions = rows.getInt(1);
                    assertThat(rows.next()).isFalse();
                }
            }
            assertThat(idleTransactions).isZero();
            var phase = committedPhase();
            assertThat(phase.get("historyRows").asInt()).isZero();
            assertThat(phase.get("latestVersion").asInt()).isZero();
            assertThat(phase.get("readBootstrap").asText()).isEqualTo("ABSENT");
            assertThat(phase.get("occupancyBootstrap").asText()).isEqualTo("ABSENT");
            // Schema creation itself can be committed; history0 does not certify absence of all DDL.
            cleanupEvidence = JSON.objectNode().put("activeAfterFailure", metrics.getActiveConnections())
                    .put("awaitingAfterFailure", metrics.getThreadsAwaitingConnection())
                    .put("idleTransactionBackendsAfterFailure", idleTransactions);
        }

        private ObjectNode committedPhase() throws SQLException {
            var result = JSON.objectNode();
            try (var statement = observer.createStatement()) {
                statement.setQueryTimeout(1);
                try (var rows = statement.executeQuery("select to_regclass('praxis_bulk.praxis_bulk_schema_history') is not null")) {
                    assertThat(rows.next()).isTrue();
                    if (!rows.getBoolean(1)) return result.put("historyRows", 0).put("latestVersion", 0)
                            .put("readBootstrap", "ABSENT").put("occupancyBootstrap", "ABSENT");
                }
                try (var rows = statement.executeQuery("select count(*) filter(where version is not null),"
                        + " coalesce(max(version::integer),0) from praxis_bulk.praxis_bulk_schema_history where success")) {
                    assertThat(rows.next()).isTrue();
                    result.put("historyRows", rows.getInt(1)).put("latestVersion", rows.getInt(2));
                }
                for (var entry : Map.of("readBootstrap", "praxis_bulk_capacity_read_bootstrap",
                        "occupancyBootstrap", "praxis_bulk_capacity_occupancy_bootstrap").entrySet()) {
                    try (var rows = statement.executeQuery("select to_regclass('praxis_bulk." + entry.getValue() + "') is not null")) {
                        assertThat(rows.next()).isTrue();
                        if (!rows.getBoolean(1)) {
                            result.put(entry.getKey(), "ABSENT");
                            continue;
                        }
                    }
                    try (var rows = statement.executeQuery("select phase from praxis_bulk." + entry.getValue())) {
                        assertThat(rows.next()).isTrue();
                        String phase = rows.getString(1);
                        assertThat(List.of("PENDING", "COMPLETE")).contains(phase);
                        result.put(entry.getKey(), phase);
                        assertThat(rows.next()).isFalse();
                    }
                }
            }
            return result;
        }

        private void stopSampler() {
            sampler.shutdown();
            try {
                if (!sampler.awaitTermination(2, TimeUnit.SECONDS)) {
                    sampler.shutdownNow();
                    if (!sampler.awaitTermination(2, TimeUnit.SECONDS))
                        throw new IllegalStateException("Owner resource observer did not stop");
                }
            } catch (InterruptedException interrupted) {
                sampler.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Owner resource observer interrupted", interrupted);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                stopSampler();
                sample();
                var manifest = JSON.objectNode().put("caseId", caseId).put("logicalDatabase", "owner_resources_local")
                        .put("harnessPid", ProcessHandle.current().pid()).put("observerPid", observerPid)
                        .put("subprocesses", 0).put("processExit", "NOT_APPLICABLE_IN_PROCESS_JUNIT")
                        .put("barriersUsed", barriersUsed).put("poolCapacity", capacity).put("connectionTimeoutMillis", 1000)
                        .put("maxObservedActive", maxActive.get()).put("maxObservedTotal", maxTotal.get())
                        .put("maxObservedAwaiting", maxAwaiting.get()).put("omittedSamples", omittedSamples.get())
                        .put("flywayVersion", flywayVersion).put("postgresVersion", postgresVersion)
                        .put("jdbcVersion", jdbcVersion).put("elapsedMillis", elapsedMillis())
                        .put("caseOutcome", assertionsComplete ? successOutcome : unfinishedOutcome);
                if (failure != null) manifest.set("failure", safeFailure(failure));
                if (cleanupEvidence != null) manifest.set("cleanupEvidence", cleanupEvidence);
                if (coordinatorEvidence != null) manifest.set("coordinatorEvidence", coordinatorEvidence);
                if (concurrentEvidence != null) manifest.set("concurrentEvidence", concurrentEvidence);
                try { manifest.set("committedPhase", committedPhase()); }
                catch (Exception | AssertionError phaseFailure) {
                    manifest.set("phaseObservationFailure", safeFailure(phaseFailure));
                }
                var observations = manifest.putArray("samples");
                samples.forEach(observations::add);
                var failures = manifest.putArray("samplingFailures");
                samplingFailures.forEach(failures::add);
                String configured = System.getProperty("praxis.bulk.proof.directory");
                Path directory = configured == null ? Files.createTempDirectory("praxis-owner-resources-proof-")
                        : Path.of(configured);
                Files.createDirectories(directory);
                Path file = directory.resolve(caseId + ".json");
                Files.writeString(file, manifest.toPrettyString(), StandardCharsets.UTF_8);
                if (configured == null) System.out.println("Owner resource manifest: " + file.toAbsolutePath());
            } finally {
                try { pool.close(); }
                finally { observer.close(); }
            }
        }

        private long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

        private static String flywayVersion() throws Exception {
            var properties = new Properties();
            try (var stream = Flyway.class.getResourceAsStream("/META-INF/maven/org.flywaydb/flyway-core/pom.properties")) {
                if (stream != null) properties.load(stream);
            }
            String version = properties.getProperty("version", Flyway.class.getPackage().getImplementationVersion());
            return safeVersion(version);
        }

        private static String safeVersion(String value) {
            return value != null && value.matches("[0-9][0-9A-Za-z.+_-]{0,80}") ? value : "UNAVAILABLE";
        }

        private static String safeSymbol(String value) {
            return value != null && value.matches("[A-Za-z][A-Za-z0-9_]{0,80}") ? value : "NONE";
        }

        /** No exception message, SQL text, connection URL, credentials or protected identifiers. */
        private static ObjectNode safeFailure(Throwable failure) {
            var result = JSON.objectNode();
            var chain = result.putArray("causes");
            var seen = new ArrayList<Throwable>();
            for (Throwable current = failure; current != null && seen.size() < 12 && !seen.contains(current);
                    current = current.getCause()) {
                seen.add(current);
                var cause = chain.addObject().put("type", current.getClass().getName());
                if (current instanceof SQLException sql) {
                    String state = sql.getSQLState();
                    cause.put("sqlState", state != null && state.matches("[A-Z0-9]{5}") ? state : "UNAVAILABLE");
                }
                var frames = cause.putArray("resourceFrames");
                int count = 0;
                for (var frame : current.getStackTrace()) {
                    if ((frame.getClassName().startsWith("org.flywaydb.")
                            || frame.getClassName().startsWith("com.zaxxer.hikari.")
                            || frame.getClassName().equals(BulkExecutionMigrator.class.getName())) && count++ < 12)
                        frames.add(frame.getClassName() + "#" + frame.getMethodName());
                }
            }
            return result;
        }
    }
}
