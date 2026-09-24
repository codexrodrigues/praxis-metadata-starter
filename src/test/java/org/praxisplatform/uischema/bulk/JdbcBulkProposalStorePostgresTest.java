package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.*;

/** PostgreSQL with independent physical connections: no H2 fallback and no conditionally skipped proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcBulkProposalStorePostgresTest {
    private EmbeddedPostgres postgres;
    private DataSource dataSource;
    private JdbcTemplate sql;
    private TransactionTemplate tx;
    private JdbcBulkProposalStore store;
    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        dataSource = postgres.getPostgresDatabase(); sql = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource); tx = new TransactionTemplate(manager);
        store = new JdbcBulkProposalStore(new BulkExecutionInfrastructure(dataSource, manager, CONTEXT.namespaceId()));
        sql.execute("create table public.host_existing(id integer primary key)");
        sql.execute("create role bulk_runtime login");
        System.out.println("Proposal store proof PostgreSQL: " + sql.queryForObject("select version()", String.class));
    }
    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void reset() { sql.execute("drop schema if exists praxis_bulk cascade"); }
    void migrate() { assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(3); }
    int count() { return sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal", Integer.class); }

    @Test void explicitMigrationPreservesHostAndIsRepeatable() {
        migrate(); assertThat(BulkExecutionMigrator.migrate(dataSource)).isZero();
        BulkExecutionMigrator.validate(dataSource);
        assertThat(sql.queryForObject("select count(*) from public.host_existing", Integer.class)).isZero();
        assertThat(sql.queryForObject("select to_regclass('public.flyway_schema_history')::text", String.class)).isNull();
    }
    @Test void concurrentMigrationHasOneVersionApplication() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> { barrier.await(5, TimeUnit.SECONDS); return BulkExecutionMigrator.migrate(dataSource); };
            var first = executor.submit(task); var second = executor.submit(task);
            assertThat(first.get(30, TimeUnit.SECONDS)+second.get(30, TimeUnit.SECONDS)).isEqualTo(3);
        }
        BulkExecutionMigrator.validate(dataSource);
    }
    @Test void refusesUnknownNonemptyDedicatedSchema() {
        sql.execute("create schema praxis_bulk"); sql.execute("create table praxis_bulk.foreign_data(id integer)");
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(dataSource)).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_proposal')::text", String.class)).isNull();
    }
    @Test void migrationInsideDomainTransactionIsRejected() {
        assertThatThrownBy(() -> tx.execute(status -> BulkExecutionMigrator.migrate(dataSource))).isInstanceOf(IllegalStateException.class);
    }
    @Test void detectsRemovedConstraintsAndTriggerEvenWithValidHistory() {
        migrate();
        String constraint = sql.queryForObject("select conname from pg_constraint where conrelid='praxis_bulk.praxis_bulk_proposal'::regclass and contype='c' order by conname limit 1", String.class);
        sql.execute("alter table praxis_bulk.praxis_bulk_proposal drop constraint \""+constraint+"\"");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);
        sql.execute("drop schema praxis_bulk cascade"); migrate();
        sql.execute("alter table praxis_bulk.praxis_bulk_proposal disable trigger user");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(dataSource)).isInstanceOf(RuntimeException.class);
    }
    @Test void detectsChangedCheckSemanticsUnloggedTableAndDeferredPrimaryKey() {
        String[] mutations = {
            "alter table praxis_bulk.praxis_bulk_proposal drop constraint praxis_bulk_proposal_fingerprint_format_check; alter table praxis_bulk.praxis_bulk_proposal add constraint praxis_bulk_proposal_fingerprint_format_check check (fingerprint ~ '^sha256:[0-9A-F]{64}$')",
            "alter table praxis_bulk.praxis_bulk_item_receipt set unlogged; alter table praxis_bulk.praxis_bulk_execution set unlogged; alter table praxis_bulk.praxis_bulk_evaluation set unlogged; alter table praxis_bulk.praxis_bulk_proposal set unlogged",
            "alter table praxis_bulk.praxis_bulk_proposal alter column created_at type timestamptz(0)",
            "alter table praxis_bulk.praxis_bulk_proposal drop constraint praxis_bulk_proposal_pkey; alter table praxis_bulk.praxis_bulk_proposal add constraint praxis_bulk_proposal_pkey primary key (proposal_id) deferrable initially deferred",
            "alter function praxis_bulk.reject_praxis_bulk_proposal_update() security definer"
        };
        for (String mutation : mutations) {
            migrate(); sql.execute(mutation);
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource)).as(mutation).isInstanceOf(RuntimeException.class);
            sql.execute("drop schema praxis_bulk cascade");
        }
    }

    @Test void rejectsHistoryChecksumTampering() {
        migrate(); sql.execute("update praxis_bulk.praxis_bulk_schema_history set checksum=checksum+1 where version='1'");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);
    }
    @Test void durableRoundtripForAllModesAndCodecsAndExpiredInput() {
        migrate();
        for (var mode : BulkMode.values()) {
            persistAndRead(snapshot(mode, BulkIdentityCodecs.integers(), "42", "1.0"));
            persistAndRead(snapshot(mode, BulkIdentityCodecs.longs(), "\"9223372036854775807\"", "123456789012345678901234567890"));
            persistAndRead(snapshot(mode, BulkIdentityCodecs.strings(), "\"ação😀\"", "0.12345678901234567890123456789"));
            persistAndRead(snapshot(mode, BulkIdentityCodecs.uuids(), "\"123e4567-e89b-12d3-a456-426614174000\"", "1e100"));
        }
        // Reading an expired input is intentional: this low-level store does not admit execution.
        var old = new BulkStoredProposal(UUID.randomUUID(), Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2000-01-01T00:01:00Z"), proposal().snapshot());
        tx.executeWithoutResult(status -> store.insert(old));
        assertThat(tx.<java.util.Optional<BulkStoredProposal>>execute(status -> store.find(CONTEXT, old.id()))).isPresent();
        assertThat(count()).isEqualTo(13);
    }
    @Test void provisionalInsertInvisibleToObserverAndOuterRollbackLeavesNothing() {
        migrate(); var value = proposal();
        tx.executeWithoutResult(status -> {
            store.insert(value);
            try (var second = dataSource.getConnection(); var statement = second.createStatement(); var rows = statement.executeQuery("select count(*) from praxis_bulk.praxis_bulk_proposal")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            } catch (java.sql.SQLException error) { throw new AssertionError(error); }
            assertThat(store.find(CONTEXT, value.id())).isPresent(); status.setRollbackOnly();
        });
        assertThat(count()).isZero();
    }
    @Test void duplicateIdIsSanitizedConflictAndCannotOverwrite() {
        migrate(); var first = proposal(); tx.executeWithoutResult(status -> store.insert(first));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.insert(first)))
                .isInstanceOfSatisfying(BulkProposalStorageException.class, error -> assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CONFLICT)).hasNoCause();
        assertThat(count()).isEqualTo(1);
    }
    @Test void concurrentDuplicateHasOneCommittedWinner() throws Exception {
        migrate(); var value = proposal(); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { tx.executeWithoutResult(status -> store.insert(value)); return "COMMITTED"; }
                catch (BulkProposalStorageException error) { return error.reason().name(); }
            };
            var first = executor.submit(task); var second = executor.submit(task);
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("COMMITTED", "CONFLICT");
        }
        assertThat(count()).isEqualTo(1);
    }
    @Test void scopedLookupDoesNotExposeAnotherSubjectResourceOrOperation() {
        migrate(); var value = proposal(); tx.executeWithoutResult(status -> store.insert(value));
        for (var context : java.util.List.of(scope("other", CONTEXT.resourceKey(), CONTEXT.operationRef().operationId()),
                scope(CONTEXT.subjectId(), "other", CONTEXT.operationRef().operationId()), scope(CONTEXT.subjectId(), CONTEXT.resourceKey(), "other"))) {
            assertThat(tx.<java.util.Optional<BulkStoredProposal>>execute(status -> store.find(context, value.id()))).isEmpty();
        }
        var wrongNamespace = new BulkFingerprintContext("other", CONTEXT.subjectId(), CONTEXT.resourceKey(), CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity());
        assertThatThrownBy(() -> tx.execute(status -> store.find(wrongNamespace, value.id()))).isInstanceOf(IllegalArgumentException.class);
        // Schema/path changes are revalidation input; they do not redefine lookup identity or corrupt storage.
        var changedRevision = new BulkFingerprintContext(CONTEXT.namespaceId(), CONTEXT.subjectId(), CONTEXT.resourceKey(),
                new CanonicalOperationRef("new", CONTEXT.operationRef().operationId(), "/new", "POST"), "new-schema", CONTEXT.atomicity());
        assertThat(tx.<BulkFingerprintContext>execute(status -> store.find(changedRevision, value.id()).orElseThrow().snapshot().context())).isEqualTo(CONTEXT);
    }
    @Test void ownerCannotUpdateAndCorruptPayloadFailsClosedWithoutProtectedCause() {
        migrate(); var value = proposal(); tx.executeWithoutResult(status -> store.insert(value));
        assertThatThrownBy(() -> sql.update("update praxis_bulk.praxis_bulk_proposal set payload=?", bytes("protected-customer-value"))).isInstanceOf(RuntimeException.class);
        sql.execute("alter table praxis_bulk.praxis_bulk_proposal disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_proposal set payload=?", bytes("protected-customer-value"));
        assertThatThrownBy(() -> tx.execute(status -> store.find(CONTEXT, value.id())))
                .isInstanceOfSatisfying(BulkProposalStorageException.class, error -> assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT))
                .hasNoCause().hasMessageNotContaining("protected-customer-value");
    }
    @Test void runtimeRoleNeedsOnlyUsageSelectInsertAndCannotMutateSchema() {
        migrate(); sql.execute("grant usage on schema praxis_bulk to bulk_runtime");
        sql.execute("grant select, insert on praxis_bulk.praxis_bulk_proposal to bulk_runtime");
        var runtimeDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_runtime", "postgres"), "bulk_runtime", "");
        var manager = new DataSourceTransactionManager(runtimeDs); var runtimeTx = new TransactionTemplate(manager);
        var runtimeStore = new JdbcBulkProposalStore(new BulkExecutionInfrastructure(runtimeDs, manager, CONTEXT.namespaceId()));
        var value = proposal(); runtimeTx.executeWithoutResult(status -> runtimeStore.insert(value));
        assertThat(runtimeTx.<java.util.Optional<BulkStoredProposal>>execute(status -> runtimeStore.find(CONTEXT, value.id()))).isPresent();
        var runtimeSql = new JdbcTemplate(runtimeDs);
        for (String statement : new String[]{"delete from praxis_bulk.praxis_bulk_proposal", "update praxis_bulk.praxis_bulk_proposal set subject_id='other'", "create table praxis_bulk.unauthorized(id integer)"})
            assertThatThrownBy(() -> runtimeSql.execute(statement)).isInstanceOf(RuntimeException.class);
    }
    private BulkFingerprintContext scope(String subject, String resource, String operation) {
        return new BulkFingerprintContext(CONTEXT.namespaceId(), subject, resource,
                new CanonicalOperationRef("admin", operation, "/employees/bulk", "PATCH"), CONTEXT.schemaRevision(), CONTEXT.atomicity());
    }
    private void persistAndRead(BulkIntentSnapshot snapshot) {
        var value = proposal(snapshot); tx.executeWithoutResult(status -> store.insert(value));
        var restored = tx.execute(status -> store.find(CONTEXT, value.id()).orElseThrow());
        assertThat(restored.id()).isEqualTo(value.id()); assertThat(restored.createdAt()).isEqualTo(value.createdAt());
        assertThat(restored.expiresAt()).isEqualTo(value.expiresAt()); assertThat(restored.snapshot().fingerprint()).isEqualTo(snapshot.fingerprint());
        assertThat(BulkCanonicalJson.digest(restored.snapshot().intent())).isEqualTo(BulkCanonicalJson.digest(snapshot.intent()));
    }
}
