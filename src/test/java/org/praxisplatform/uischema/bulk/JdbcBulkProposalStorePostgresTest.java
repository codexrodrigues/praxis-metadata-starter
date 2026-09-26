package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.List;
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
        store = new JdbcBulkProposalStore(new BulkExecutionInfrastructure(dataSource, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID));
        sql.execute("create table public.host_existing(id integer primary key)");
        sql.execute("create role bulk_runtime login");
        System.out.println("Proposal store proof PostgreSQL: " + sql.queryForObject("select version()", String.class));
    }
    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void reset() { sql.execute("drop schema if exists praxis_bulk cascade"); }
    void migrate() {
        assertThat(BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isEqualTo(7);
        BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
    }
    int count() { return sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal", Integer.class); }

    @Test void explicitMigrationPreservesHostAndIsRepeatable() {
        migrate(); assertThat(BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isZero();
        BulkExecutionMigrator.validate(dataSource);
        assertThat(sql.queryForObject("select count(*) from public.host_existing", Integer.class)).isZero();
        assertThat(sql.queryForObject("select to_regclass('public.flyway_schema_history')::text", String.class)).isNull();
    }
    @Test void databaseRejectsV6WriterThatOmitsDescriptorTupleAfterV7() {
        migrate();
        var valid = proposal();
        var snapshot = valid.snapshot();
        assertThatThrownBy(() -> sql.update("""
                insert into praxis_bulk.praxis_bulk_proposal
                    (proposal_id, namespace_id, subject_id, resource_key, operation_id,
                     created_at, expires_at, fingerprint, payload)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), snapshot.context().namespaceId(), snapshot.context().subjectId(),
                snapshot.context().resourceKey(), snapshot.context().operationRef().operationId(),
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5), snapshot.fingerprint(),
                BulkSnapshotStorageCodec.encode(snapshot)))
                .isInstanceOf(RuntimeException.class);
        assertThat(count()).isZero();
    }
    @Test void concurrentMigrationHasOneVersionApplication() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> { barrier.await(5, TimeUnit.SECONDS); return BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId()); };
            var first = executor.submit(task); var second = executor.submit(task);
            assertThat(first.get(30, TimeUnit.SECONDS)+second.get(30, TimeUnit.SECONDS)).isEqualTo(7);
        }
        BulkExecutionMigrator.validate(dataSource);
    }
    @Test void refusesUnknownNonemptyDedicatedSchema() {
        sql.execute("create schema praxis_bulk"); sql.execute("create table praxis_bulk.foreign_data(id integer)");
        assertThatThrownBy(() -> BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_proposal')::text", String.class)).isNull();
    }
    @Test void migrationInsideDomainTransactionIsRejected() {
        assertThatThrownBy(() -> tx.execute(status -> BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId()))).isInstanceOf(IllegalStateException.class);
    }
    @Test void detectsRemovedConstraintsAndTriggerEvenWithValidHistory() {
        migrate();
        String constraint = sql.queryForObject("select conname from pg_constraint where conrelid='praxis_bulk.praxis_bulk_proposal'::regclass and contype='c' order by conname limit 1", String.class);
        sql.execute("alter table praxis_bulk.praxis_bulk_proposal drop constraint \""+constraint+"\"");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);
        sql.execute("drop schema praxis_bulk cascade"); migrate();
        sql.execute("alter table praxis_bulk.praxis_bulk_proposal disable trigger user");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> BulkPostgresTestSupport.migrate(dataSource, CONTEXT.namespaceId())).isInstanceOf(RuntimeException.class);
    }
    @Test void detectsChangedCheckSemanticsUnloggedTableAndDeferredPrimaryKey() {
        String[] mutations = {
            "alter table praxis_bulk.praxis_bulk_proposal drop constraint praxis_bulk_proposal_fingerprint_format_check; alter table praxis_bulk.praxis_bulk_proposal add constraint praxis_bulk_proposal_fingerprint_format_check check (fingerprint ~ '^sha256:[0-9A-F]{64}$')",
            "alter table praxis_bulk.praxis_bulk_tombstone set unlogged",
            "alter table praxis_bulk.praxis_bulk_proposal alter column created_at type timestamptz(0)",
            "alter table praxis_bulk.praxis_bulk_tombstone drop constraint praxis_bulk_tombstone_pkey; alter table praxis_bulk.praxis_bulk_tombstone add constraint praxis_bulk_tombstone_pkey primary key (namespace_id, authorization_scope_digest_version, authorization_scope_digest, resource_key, operation_id, idempotency_key_digest) deferrable initially deferred",
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
        int subject = 0;
        for (var mode : BulkMode.values()) {
            persistAndRead(snapshot(subjectContext(++subject), mode, BulkIdentityCodecs.integers(), "42", "1.0"));
            persistAndRead(snapshot(subjectContext(++subject), mode, BulkIdentityCodecs.longs(), "\"9223372036854775807\"", "123456789012345678901234567890"));
            persistAndRead(snapshot(subjectContext(++subject), mode, BulkIdentityCodecs.strings(), "\"ação😀\"", "0.12345678901234567890123456789"));
            persistAndRead(snapshot(subjectContext(++subject), mode, BulkIdentityCodecs.uuids(), "\"123e4567-e89b-12d3-a456-426614174000\"", "1e100"));
        }
        // Reading an expired input is intentional: this low-level store does not admit execution.
        var old = new BulkStoredProposal(UUID.randomUUID(), Instant.parse("2000-01-01T00:00:00Z"),
                Instant.parse("2000-01-01T00:01:00Z"), proposal().snapshot(), CONTROL_EXPECTATION);
        tx.executeWithoutResult(status -> store.insert(old));
        assertThat(tx.<java.util.Optional<BulkStoredProposal>>execute(status -> store.find(CONTEXT, old.id()))).isPresent();
        assertThat(count()).isEqualTo(13);
    }

    @Test
    void pendingQuotaLimitsSerializeSubjectAndDeploymentRaces() throws Exception {
        migrate();
        var subjectScope = subjectContext(700);
        for (int i = 0; i < BulkQuotaLedger.MAX_PENDING_SUBJECT - 1; i++)
            tx.executeWithoutResult(status -> store.insert(proposal(snapshot(subjectScope,
                    BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.strings(), "\"pending-" + UUID.randomUUID()
                            + "\"", "1.0"))));
        var subjectRace = List.of(
                proposal(snapshot(subjectScope, BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.strings(),
                        "\"subject-race-a\"", "1.0")),
                proposal(snapshot(subjectScope, BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.strings(),
                        "\"subject-race-b\"", "1.0")));
        assertOneCommitAndOneCapacity(subjectRace);
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                + "where kind='PROPOSAL_PENDING' and state='PENDING' and subject_scope_digest=?",
                Integer.class, BulkScopeDigests.subjectQuotaDigest(BulkPostgresTestSupport.DEPLOYMENT_ID,
                        subjectScope.subjectId()))).isEqualTo(BulkQuotaLedger.MAX_PENDING_SUBJECT);

        int alreadyPending = count();
        for (int i = alreadyPending; i < BulkQuotaLedger.MAX_PENDING_DEPLOYMENT - 1; i++) {
            var scope = subjectContext(1000 + i);
            tx.executeWithoutResult(status -> store.insert(proposal(snapshot(scope, BulkMode.DOMAIN_COMMAND,
                    BulkIdentityCodecs.strings(), "\"deployment-" + UUID.randomUUID() + "\"", "1.0"))));
        }
        var deploymentRace = List.of(
                proposal(snapshot(subjectContext(5001), BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.strings(),
                        "\"deployment-race-a\"", "1.0")),
                proposal(snapshot(subjectContext(5002), BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.strings(),
                        "\"deployment-race-b\"", "1.0")));
        assertOneCommitAndOneCapacity(deploymentRace);
        assertThat(count()).isEqualTo(BulkQuotaLedger.MAX_PENDING_DEPLOYMENT);
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                + "where kind='PROPOSAL_PENDING' and state='PENDING'", Integer.class))
                .isEqualTo(BulkQuotaLedger.MAX_PENDING_DEPLOYMENT);
    }

    private void assertOneCommitAndOneCapacity(List<BulkStoredProposal> proposals) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = proposals.stream().map(proposal -> executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    tx.executeWithoutResult(status -> store.insert(proposal));
                    return "COMMITTED";
                } catch (BulkProposalStorageException error) {
                    return error.reason().name();
                }
            })).toList();
            var results = futures.stream().map(future -> {
                try { return future.get(15, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError(error); }
            }).toList();
            assertThat(results).containsExactlyInAnyOrder("COMMITTED", "CAPACITY");
        }
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
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                + "where kind='PROPOSAL_PENDING' and state='PENDING'", Integer.class)).isZero();
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
        sql.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to bulk_runtime");
        sql.execute("grant update (deployment_id) on praxis_bulk.praxis_bulk_namespace_binding to bulk_runtime");
        sql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to bulk_runtime");
        sql.execute("grant select on praxis_bulk.praxis_bulk_deployment_bucket to bulk_runtime");
        sql.execute("grant update (deployment_id) on praxis_bulk.praxis_bulk_deployment_bucket to bulk_runtime");
        sql.execute("grant select, insert on praxis_bulk.praxis_bulk_subject_bucket to bulk_runtime");
        sql.execute("grant update (deployment_id) on praxis_bulk.praxis_bulk_subject_bucket to bulk_runtime");
        sql.execute("grant select, insert on praxis_bulk.praxis_bulk_allocation to bulk_runtime");
        sql.execute("grant update (state) on praxis_bulk.praxis_bulk_allocation to bulk_runtime");
        sql.execute("grant update (proposal_id) on praxis_bulk.praxis_bulk_proposal to bulk_runtime");
        var runtimeDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_runtime", "postgres"), "bulk_runtime", "");
        var manager = new DataSourceTransactionManager(runtimeDs); var runtimeTx = new TransactionTemplate(manager);
        var runtimeStore = new JdbcBulkProposalStore(new BulkExecutionInfrastructure(runtimeDs, manager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
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
    private static BulkFingerprintContext subjectContext(int sequence) {
        return new BulkFingerprintContext(CONTEXT.namespaceId(), "subject-" + sequence,
                CONTEXT.resourceKey(), CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity());
    }
    private void persistAndRead(BulkIntentSnapshot snapshot) {
        var value = proposal(snapshot); tx.executeWithoutResult(status -> store.insert(value));
        var restored = tx.execute(status -> store.find(snapshot.context(), value.id()).orElseThrow());
        assertThat(restored.id()).isEqualTo(value.id()); assertThat(restored.createdAt()).isEqualTo(value.createdAt());
        assertThat(restored.expiresAt()).isEqualTo(value.expiresAt()); assertThat(restored.snapshot().fingerprint()).isEqualTo(snapshot.fingerprint());
        assertThat(BulkCanonicalJson.digest(restored.snapshot().intent())).isEqualTo(BulkCanonicalJson.digest(snapshot.intent()));
    }
}
