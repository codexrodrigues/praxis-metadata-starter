package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.evaluation;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.governance;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.preview;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.CONTEXT;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.proposal;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkPreviewPageReaderPostgresTest {
    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource runtime;
    private JdbcTemplate sql;
    private TransactionTemplate tx;
    private JdbcBulkProposalStore store;
    private BulkPreviewPageReader reader;
    private BulkExecutionInfrastructure infrastructure;

    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        sql = new JdbcTemplate(owner);
        var manager = new DataSourceTransactionManager(runtime);
        tx = new TransactionTemplate(manager);
        infrastructure = new BulkExecutionInfrastructure(runtime, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration());
        store = new JdbcBulkProposalStore(infrastructure);
        reader = new BulkPreviewPageReader(infrastructure);
    }

    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    @BeforeEach void reset() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(20);
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
    }

    private UUID persist(BulkEvaluationSnapshot value, BulkPreviewProjection projection) {
        tx.executeWithoutResult(status -> store.insertEvaluated(value, projection));
        return value.proposal().id();
    }

    private BulkPreviewPageReader.Page page(UUID id, int last, int count, int size) {
        return reader.read(CONTEXT, id, last, count, size);
    }

    @Test void returnsOnlyPublicDecisionAndDiagnosticsFromOneBoundedPage() {
        var value = evaluation(proposal());
        assertThat(value.proposal().expiresAt()).isBefore(Instant.now());
        UUID id = persist(value, preview(value));
        var page = page(id, -1, 1, 200);
        assertThat(page.kind()).isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);
        assertThat(page.targetCount()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().ordinal()).isZero();
        assertThat(page.items().getFirst().wireIdentity()).isEqualTo("101");
        assertThat(page.items().getFirst().decision()).isEqualTo(BulkTargetEligibility.Decision.EXECUTABLE);
        assertThat(page.items().getFirst().diagnostics()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextOrdinal()).isZero();
        assertThat(page(id, 0, 1, 200).items()).isEmpty();
        assertThatThrownBy(() -> page(id, -1, 2, 200))
                .isInstanceOfSatisfying(BulkProposalStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT));
    }

    @Test void authorizedFacadeUsesHistoricalCreatorAndReturnsOnlySafeRs2Projection() {
        var value = evaluation(proposal());
        assertThat(value.proposal().expiresAt()).isBefore(Instant.now());
        UUID id = persist(value, preview(value));
        var provider = new RecordingAuthorizationProvider(infrastructure,
                BulkReadAuthorizationProvider.GlobalDecision.ALLOWED,
                BulkReadAuthorizationProvider.ScopeDecision.authorized(new byte[32]));
        var authorized = new BulkAuthorizedProposalResultsReader(
                infrastructure, CONTEXT.resourceKey(), provider, cursorConfiguration())
                .readProposalResults("delegated-reader", id, 1);

        assertThat(authorized.state()).isEqualTo(BulkAuthorizedProposalResultsReader.State.COMPLETE);
        assertThat(authorized.page().size()).isEqualTo(1);
        assertThat(authorized.page().next()).isNull();
        assertThat(authorized.page().prev()).isNull();
        assertThat(authorized.page().content()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("101");
            assertThat(item.decision()).isEqualTo(BulkProposalItemResult.Decision.EXECUTABLE);
            assertThat(item.diagnostics()).isEmpty();
        });
        assertThat(provider.requester).isEqualTo("delegated-reader");
        assertThat(provider.creator).isEqualTo(CONTEXT.subjectId());
        assertThat(provider.targets).singleElement().satisfies(target -> {
            assertThat(target.ordinal()).isZero();
            assertThat(target.wireIdentity()).isEqualTo("101");
            assertThat(target.facts()).isEqualTo(value.targets().getFirst().facts());
        });
        assertThat(authorized.toString()).doesNotContain("delegated-reader", CONTEXT.subjectId(), "101");
    }

    @Test void globalDenialPrecedesLookupAndPostLookupFailuresAreNonEnumerating() {
        var deniedProvider = new RecordingAuthorizationProvider(infrastructure,
                BulkReadAuthorizationProvider.GlobalDecision.DENIED,
                BulkReadAuthorizationProvider.ScopeDecision.authorized(new byte[32]));
        var deniedReader = new BulkAuthorizedProposalResultsReader(
                infrastructure, CONTEXT.resourceKey(), deniedProvider, cursorConfiguration());
        assertThat(deniedReader.readProposalResults("denied-reader", UUID.randomUUID(), 10).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.GLOBAL_DENIED);
        assertThat(deniedProvider.authorizeCalls).isZero();

        var unavailableProvider = new RecordingAuthorizationProvider(infrastructure,
                BulkReadAuthorizationProvider.GlobalDecision.ALLOWED,
                BulkReadAuthorizationProvider.ScopeDecision.unavailable());
        var unavailableReader = new BulkAuthorizedProposalResultsReader(
                infrastructure, CONTEXT.resourceKey(), unavailableProvider, cursorConfiguration());
        assertThat(unavailableReader.readProposalResults("reader", UUID.randomUUID(), 10).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
        var pending = proposal();
        tx.executeWithoutResult(status -> store.insert(pending));
        assertThat(unavailableReader.readProposalResults("reader", pending.id(), 10).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
        assertThat(unavailableProvider.authorizeCalls).isZero();
    }

    @Test void corruptProtectedEvaluationAndAbsentProposalRemainIndistinguishableBeforeFullAuthorization() {
        var value = evaluation(proposal());
        UUID id = persist(value, preview(value));
        var provider = new RecordingAuthorizationProvider(infrastructure,
                BulkReadAuthorizationProvider.GlobalDecision.ALLOWED,
                BulkReadAuthorizationProvider.ScopeDecision.denied());
        var authorized = new BulkAuthorizedProposalResultsReader(
                infrastructure, CONTEXT.resourceKey(), provider, cursorConfiguration());
        assertThat(authorized.readProposalResults("reader", UUID.randomUUID(), 10).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);

        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=? where proposal_id=?",
                    "protected-corrupt".getBytes(StandardCharsets.UTF_8), id);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_evaluation enable trigger user");
        }

        assertThat(authorized.readProposalResults("reader", id, 10).state())
                .isEqualTo(BulkAuthorizedProposalResultsReader.State.NOT_FOUND_OR_DENIED);
        assertThat(provider.authorizeCalls).isZero();
    }

    @Test void lateAuthorizationCannotPublishAResultAfterTheAbsoluteDeadline() {
        var value = evaluation(proposal());
        UUID id = persist(value, preview(value));
        var provider = new BulkReadAuthorizationProvider() {
            @Override public String confirmationOperationId() { return CONTEXT.operationRef().operationId(); }
            @Override public BulkExecutionInfrastructure executionInfrastructure() { return infrastructure; }
            @Override public GlobalDecision preAuthorize(Context context, Duration remainingBudget) {
                return GlobalDecision.ALLOWED;
            }
            @Override public ScopeDecision authorize(Context context, String creatorSubjectId,
                    List<Target> fullTargetSet, Duration remainingBudget) {
                try { Thread.sleep(3_050L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return ScopeDecision.unavailable();
                }
                return ScopeDecision.authorized(new byte[32]);
            }
        };

        var result = new BulkAuthorizedProposalResultsReader(infrastructure, CONTEXT.resourceKey(), provider,
                cursorConfiguration())
                .readProposalResults("reader", id, 1);

        assertThat(result.state()).isEqualTo(BulkAuthorizedProposalResultsReader.State.UNAVAILABLE);
        assertThat(result.page()).isNull();
    }

    @Test void absenceAndUnavailableAreDistinctAndScopeBound() {
        assertThat(page(UUID.randomUUID(), -1, 1, 10).kind()).isEqualTo(BulkPreviewPageReader.Kind.ABSENT);
        var pending = proposal();
        tx.executeWithoutResult(status -> store.insert(pending));
        assertThat(page(pending.id(), -1, 1, 10).kind())
                .isEqualTo(BulkPreviewPageReader.Kind.NOT_EVALUATED);
        var value = evaluation(proposal());
        UUID id = persist(value, BulkPreviewProjection.unavailable(value));
        assertThat(page(id, -1, 1, 10).kind()).isEqualTo(BulkPreviewPageReader.Kind.UNAVAILABLE);
        var foreign = new BulkFingerprintContext(CONTEXT.namespaceId(), "foreign-subject",
                CONTEXT.resourceKey(), CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity());
        assertThat(reader.read(foreign, id, -1, 1, 10).kind())
                .isEqualTo(BulkPreviewPageReader.Kind.ABSENT);
        assertThatThrownBy(() -> page(id, -1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> page(id, -1, 1, 201)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void legacyEvaluationWithoutCertifiedProjectionIsNotAnEmptyPage() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        Flyway.configure().dataSource(owner).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("2").load().migrate();
        var value = evaluation(proposal());
        BulkPostgresTestSupport.insertLegacyInput(sql, value);
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(18);
        assertThat(page(value.proposal().id(), -1, 1, 10).kind())
                .isEqualTo(BulkPreviewPageReader.Kind.UNAVAILABLE_LEGACY);
    }

    @Test void unavailableParentWithGhostPreviewFailsClosed() {
        var value = evaluation(proposal());
        UUID id = persist(value, BulkPreviewProjection.unavailable(value));
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        try {
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_target_preview
                        (proposal_id, evaluation_fingerprint, ordinal, decision, diagnostics)
                    values (?, ?, 0, 'EXECUTABLE', ?)
                    """, id, value.fingerprint(), "[]".getBytes(StandardCharsets.UTF_8));
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        }
        assertCorrupt(id);
    }

    @Test void ownerOnlyBootstrapMarkersGateEveryNewSnapshot() {
        var value = evaluation(proposal());
        UUID id = persist(value, preview(value));
        assertThat(new JdbcTemplate(runtime).queryForObject(
                "select praxis_bulk.assert_preview_integrity_complete()", Boolean.class)).isTrue();
        assertThatThrownBy(() -> new JdbcTemplate(runtime).queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
                """, String.class)).hasCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .satisfies(error -> assertThat(error.getCause()).hasMessageContaining("permission denied"));
        assertThatThrownBy(() -> new JdbcTemplate(runtime).queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_reader_bootstrap
                """, String.class)).hasCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .satisfies(error -> assertThat(error.getCause()).hasMessageContaining("permission denied"));
        for (String marker : List.of("praxis_bulk_preview_integrity_bootstrap",
                "praxis_bulk_preview_reader_bootstrap")) {
            sql.execute("update praxis_bulk." + marker + " set phase='PENDING'");
            try {
                assertThatThrownBy(() -> page(id, -1, 1, 10))
                        .isInstanceOfSatisfying(BulkProposalStorageException.class,
                                error -> assertThat(error.reason())
                                        .isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE))
                        .hasNoCause();
            } finally {
                sql.execute("update praxis_bulk." + marker + " set phase='COMPLETE'");
            }
            assertThat(page(id, -1, 1, 10).kind()).isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);
        }
        sql.execute("delete from praxis_bulk.praxis_bulk_preview_reader_bootstrap");
        assertThatThrownBy(() -> page(id, -1, 1, 10))
                .isInstanceOfSatisfying(BulkProposalStorageException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner,
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class);
    }

    /** Current-owner recovery proof; this does not certify a historical V11/pre14 upgrade. */
    @Test void retriesNativeOwnerInterruptionWithoutHealingCompletedReaderDrift() {
        sql.execute("drop schema praxis_bulk cascade");
        var deployments = Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID);
        var noRolesFault = new FailReaderAfterCasDataSource(postgres.getJdbcUrl("postgres", "postgres"), false);
        assertReaderInterruption(noRolesFault, () -> BulkExecutionMigrator.migrate(noRolesFault, deployments));
        assertPendingReaderRecovery();
        readerRecoveryData().forEach((table, rows) -> {
            if (!table.endsWith("_bootstrap")) assertThat(rows).as("native rollback rows: %s", table).isEmpty();
        });
        var history = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
        assertReaderMigrationHistory(history, 19);
        grantReaderRecoveryBaseRights();
        var pendingData = readerRecoveryData();
        var baseCatalog = readerRecoveryCatalog();
        var grantsFault = new FailReaderAfterCasDataSource(postgres.getJdbcUrl("postgres", "postgres"), true);
        assertReaderInterruption(grantsFault, () -> BulkExecutionMigrator.migrate(grantsFault,
                deployments, BulkPostgresTestSupport.testRoleConfiguration()));
        assertPendingReaderRecovery();
        assertThat(readerRecoveryCatalog()).isEqualTo(baseCatalog);
        assertThat(readerRecoveryData()).isEqualTo(pendingData);
        assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank"))
                .isEqualTo(history);
        var incompleteRoles = new BulkExecutionRoleConfiguration("postgres", java.util.Set.of("bulk_runtime_test"),
                java.util.Set.of(), java.util.Set.of());
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner, deployments, incompleteRoles))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("governed lifecycle function grants differ");
        assertPendingReaderRecovery();
        assertThat(readerRecoveryCatalog()).isEqualTo(baseCatalog);
        assertThat(readerRecoveryData()).isEqualTo(pendingData);
        String function = "praxis_bulk.assert_preview_integrity_complete()";
        String originalDefinition = sql.queryForObject("select pg_get_functiondef(?::regprocedure)",
                String.class, function);
        sql.execute("""
                create or replace function praxis_bulk.assert_preview_integrity_complete()
                returns boolean language plpgsql stable security definer
                set search_path = pg_catalog, pg_temp as $$ begin return true; end $$
                """);
        var corruptBody = readerRecoveryCatalog();
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner, deployments,
                BulkPostgresTestSupport.testRoleConfiguration()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("governed lifecycle function body differs from V12 expectation: assert_preview_integrity_complete()");
        assertPendingReaderRecovery();
        assertThat(readerRecoveryData()).isEqualTo(pendingData);
        assertThat(readerRecoveryCatalog()).isEqualTo(corruptBody);
        sql.execute(originalDefinition);
        sql.execute("create role preview_wrong_reader_owner nologin");
        sql.execute("alter function " + function + " owner to preview_wrong_reader_owner");
        var corruptOwner = readerRecoveryCatalog();
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner, deployments,
                BulkPostgresTestSupport.testRoleConfiguration()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("preview bootstrap guard has unexpected owner: assert_preview_integrity_complete()");
        assertPendingReaderRecovery();
        assertThat(readerRecoveryData()).isEqualTo(pendingData);
        assertThat(readerRecoveryCatalog()).isEqualTo(corruptOwner);
        sql.execute("alter function " + function + " owner to postgres");
        assertThat(readerRecoveryData()).isEqualTo(pendingData);
        assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank"))
                .isEqualTo(history);
        assertThat(BulkExecutionMigrator.migrate(owner, deployments,
                BulkPostgresTestSupport.testRoleConfiguration())).isEqualTo(1);
        BulkExecutionMigrator.validate(owner, BulkPostgresTestSupport.testRoleConfiguration());
        var completeData = readerRecoveryData();
        var completeCatalog = readerRecoveryCatalog();
        var completeHistory = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
        assertReaderMigrationHistory(completeHistory, 20);
        assertThat(readerBootstrapPhases()).containsOnly("COMPLETE").hasSize(7);
        assertThat(BulkExecutionMigrator.migrate(owner, deployments,
                BulkPostgresTestSupport.testRoleConfiguration())).isZero();
        assertThat(readerRecoveryCatalog()).isEqualTo(completeCatalog);
        assertThat(readerRecoveryData()).isEqualTo(completeData);
        assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank"))
                .isEqualTo(completeHistory);
        sql.execute("revoke execute on function " + function + " from bulk_runtime_test");
        var revokedCatalog = readerRecoveryCatalog();
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner, deployments,
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("governed lifecycle function grants differ");
        assertThat(readerRecoveryCatalog()).isEqualTo(revokedCatalog);
        assertThat(readerRecoveryData()).isEqualTo(completeData);
        assertThat(readerBootstrapPhases()).containsOnly("COMPLETE").hasSize(7);
        assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank"))
                .isEqualTo(completeHistory);
        assertThat(sql.queryForObject("select has_function_privilege('bulk_runtime_test', ?,'EXECUTE')",
                Boolean.class, function)).isFalse();
    }

    /** Preserve all history rows, including Flyway's unversioned schema creation entry. */
    private void assertReaderMigrationHistory(List<Map<String, Object>> history, int lastVersion) {
        assertThat(history).hasSize(lastVersion + 1);
        var schema = history.stream().filter(row -> row.get("version") == null).toList();
        assertThat(schema).hasSize(1);
        assertThat(schema.getFirst().get("type")).isEqualTo("SCHEMA");
        assertThat(schema.getFirst().get("installed_rank")).isEqualTo(0);
        assertThat(schema.getFirst().get("success")).isEqualTo(true);
        assertThat(schema.getFirst().get("description")).isEqualTo("<< Flyway Schema Creation >>");
        assertThat(schema.getFirst().get("script")).isEqualTo("\"praxis_bulk\"");
        assertThat(schema.getFirst().get("checksum")).isNull();
        var versioned = history.stream().filter(row -> row.get("version") != null).toList();
        assertThat(versioned.stream().map(row -> row.get("version")).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, lastVersion)
                        .mapToObj(Integer::toString).toList());
        versioned.forEach(row -> {
            assertThat(row.get("type")).isEqualTo("SQL");
            assertThat(row.get("success")).isEqualTo(true);
            assertThat(row.get("checksum")).isNotNull();
        });
    }

    private List<String> readerBootstrapPhases() {
        var phases = new ArrayList<String>();
        for (String marker : List.of("manifest", "preview", "preview_integrity", "preview_reader",
                "atomic", "capacity_read", "capacity_occupancy"))
            phases.add(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_" + marker + "_bootstrap", String.class));
        return phases;
    }

    private void assertPendingReaderRecovery() {
        assertThat(readerBootstrapPhases()).containsOnly("PENDING").hasSize(7);
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_namespace_binding", Integer.class)).isZero();
        try (var connection = owner.getConnection()) {
            assertControlledReaderRights(connection, false);
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
    }

    private Map<String, List<String>> readerRecoveryData() {
        var rows = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : List.of("namespace_binding", "deployment_bucket", "subject_bucket", "openapi_publication",
                "operation_control", "allocation", "proposal", "evaluation", "target_manifest", "preview_state",
                "target_preview", "preview_item_integrity", "manifest_bootstrap", "preview_bootstrap",
                "preview_integrity_bootstrap", "preview_reader_bootstrap", "atomic_bootstrap",
                "capacity_read_bootstrap", "capacity_occupancy_bootstrap"))
            rows.put(table, sql.queryForList("select to_jsonb(t)::text from praxis_bulk.praxis_bulk_"
                    + table + " t order by to_jsonb(t)::text", String.class));
        return rows;
    }

    /** Exact bounded extension rights. Capacity phases have not run at the V12 interruption. */
    private static void assertControlledReaderRights(java.sql.Connection connection, boolean beforeReaderCommit)
            throws java.sql.SQLException {
        var tables = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : List.of("target_manifest", "preview_state", "target_preview", "preview_item_integrity",
                "atomic_receipt", "atomic_item_result", "atomic_effect_ref", "atomic_rejection"))
            tables.put(table, List.of("SELECT", "INSERT"));
        for (String table : List.of("capacity_marker", "capacity_installation", "capacity_slot", "capacity_occupation"))
            tables.put(table, List.of("SELECT"));
        for (String table : List.of("manifest", "preview", "preview_integrity", "preview_reader", "atomic",
                "capacity_read", "capacity_occupancy"))
            tables.put(table + "_bootstrap", List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"));
        for (String role : List.of("bulk_runtime_test", "durable_runtime")) {
            for (var table : tables.entrySet()) for (String right : table.getValue()) {
                boolean expected = beforeReaderCommit && !table.getKey().startsWith("capacity_")
                        && !table.getKey().endsWith("_bootstrap");
                try (var check = connection.prepareStatement("select has_table_privilege(?, ?, ?)")) {
                    check.setString(1, role); check.setString(2, "praxis_bulk.praxis_bulk_" + table.getKey()); check.setString(3, right);
                    try (var result = check.executeQuery()) {
                        if (!result.next() || result.getBoolean(1) != expected)
                            throw new java.sql.SQLException("controlled right differs: " + role + " " + table.getKey() + " " + right, "XX001");
                    }
                }
            }
            for (String function : List.of("assert_preview_integrity_complete()", "atomic_evidence_complete(uuid,integer)",
                    "lock_capacity_marker()", "claim_capacity_execution(uuid,text,text,uuid,bigint)")) {
                boolean expected = beforeReaderCommit && (function.startsWith("assert_") || function.startsWith("atomic_"));
                try (var check = connection.prepareStatement("select has_function_privilege(?, ?, 'EXECUTE')")) {
                    check.setString(1, role); check.setString(2, "praxis_bulk." + function);
                    try (var result = check.executeQuery()) {
                        if (!result.next() || result.getBoolean(1) != expected)
                            throw new java.sql.SQLException("controlled function right differs: " + role + " " + function, "XX001");
                    }
                }
            }
        }
    }

    /** V7 base allowlist plus the host V14 lock; governed extensions belong to the initializer. */
    private void grantReaderRecoveryBaseRights() {
        var rights = Map.ofEntries(
                Map.entry("namespace_binding", "select, update(deployment_id)"),
                Map.entry("deployment_bucket", "select, update(deployment_id)"),
                Map.entry("subject_bucket", "select, insert, update(deployment_id)"),
                Map.entry("proposal", "select, insert, update(proposal_id)"),
                Map.entry("evaluation", "select, insert"), Map.entry("execution", "select, insert, update"),
                Map.entry("item_receipt", "select, insert"), Map.entry("admission", "select, insert"),
                Map.entry("allocation", "select, insert, update(state,released_at,release_reason)"),
                Map.entry("tombstone", "select"));
        for (String role : List.of("bulk_runtime_test", "durable_runtime")) {
            sql.execute("grant usage on schema praxis_bulk to " + role);
            rights.forEach((table, privilege) -> sql.execute("grant " + privilege
                    + " on praxis_bulk.praxis_bulk_" + table + " to " + role));
            sql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text),"
                    + "praxis_bulk.lock_openapi_publication(text,text) to " + role);
        }
    }

    /** Scalar catalog snapshots retain PUBLIC, grant options, column ACLs and role membership. */
    private Map<String, Object> readerRecoveryCatalog() {
        return Map.of(
                "tables", sql.queryForList("select c.oid,c.relname,c.relowner,c.relacl::text from pg_class c "
                        + "join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk' order by c.oid"),
                "columns", sql.queryForList("select a.attrelid,a.attnum,a.attacl::text from pg_attribute a "
                        + "join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace "
                        + "where n.nspname='praxis_bulk' and a.attnum>0 order by a.attrelid,a.attnum"),
                "functions", sql.queryForList("select p.oid,p.proowner,p.proacl::text,pg_get_functiondef(p.oid) "
                        + "from pg_proc p join pg_namespace n on n.oid=p.pronamespace "
                        + "where n.nspname='praxis_bulk' order by p.oid"),
                "schema", sql.queryForList("select oid,nspowner,nspacl::text from pg_namespace where nspname='praxis_bulk'"),
                "roles", sql.queryForList("select oid,rolname,rolsuper,rolinherit,rolcreaterole,rolcreatedb,rolcanlogin,rolreplication,rolbypassrls "
                        + "from pg_roles order by oid"),
                "membership", sql.queryForList("select * from pg_auth_members order by roleid,member"));
    }

    private void assertReaderInterruption(FailReaderAfterCasDataSource fault, Runnable migrate) {
        assertThatThrownBy(migrate::run).isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(error -> {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("XX000");
                    assertThat(cause).hasMessage("test-only interruption after native V12 reader CAS");
                });
        assertThat(fault.failed.get()).isTrue();
        assertThat(fault.pid).isPositive();
        assertThat(fault.affectedRows).isEqualTo(1);
    }

    /** The real Statement executes first. SQLException forces the production rollback path. */
    private static final class FailReaderAfterCasDataSource
            extends org.springframework.jdbc.datasource.DriverManagerDataSource {
        private final boolean expectedGrants;
        private final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        private int pid;
        private int affectedRows;
        FailReaderAfterCasDataSource(String url, boolean expectedGrants) {
            super(url, "postgres", "");
            this.expectedGrants = expectedGrants;
        }
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
            var physical = super.getConnection();
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.Connection.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(physical, args);
                            if (method.getName().equals("createStatement") && result instanceof java.sql.Statement statement)
                                return java.lang.reflect.Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(),
                                        new Class<?>[] {java.sql.Statement.class}, (sp, sm, sa) -> {
                                            try {
                                                Object value = sm.invoke(statement, sa);
                                                if (sm.getName().equals("executeUpdate") && sa != null && sa.length > 0
                                                        && sa[0] instanceof String query && query.strip().equals("""
                                                        update praxis_bulk.praxis_bulk_preview_reader_bootstrap set phase='COMPLETE'
                                                        where bootstrap_version=12 and phase='PENDING'
                                                        """.strip()) && failed.compareAndSet(false, true)) {
                                                    affectedRows = (Integer) value;
                                                    if (affectedRows != 1 || physical.getAutoCommit())
                                                        throw new java.sql.SQLException("reader fault did not observe transactional CAS", "XX001");
                                                    try (var check = physical.createStatement(); var rows = check.executeQuery("""
                                                            select pg_backend_pid(), phase,
                                                            has_function_privilege('bulk_runtime_test','praxis_bulk.assert_preview_integrity_complete()','EXECUTE'),
                                                            has_function_privilege('durable_runtime','praxis_bulk.assert_preview_integrity_complete()','EXECUTE'),
                                                            has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_manifest','INSERT'),
                                                            has_table_privilege('durable_runtime','praxis_bulk.praxis_bulk_target_manifest','INSERT')
                                                            from praxis_bulk.praxis_bulk_preview_reader_bootstrap
                                                            """)) {
                                                        if (!rows.next()) throw new java.sql.SQLException("reader CAS row absent", "XX001");
                                                        pid = rows.getInt(1);
                                                        if (!"COMPLETE".equals(rows.getString(2)))
                                                            throw new java.sql.SQLException("reader CAS phase not observed", "XX001");
                                                        for (int column = 3; column <= 6; column++)
                                                            if (rows.getBoolean(column) != expectedGrants)
                                                                throw new java.sql.SQLException("native reader/manifest grants not observed", "XX001");
                                                    }
                                                    assertControlledReaderRights(physical, expectedGrants);
                                                    try (var check = physical.createStatement(); var rows = check.executeQuery("""
                                                            select (select count(*) from praxis_bulk.praxis_bulk_namespace_binding),
                                                                   (select count(*) from praxis_bulk.praxis_bulk_deployment_bucket),
                                                                   (select count(*) from praxis_bulk.praxis_bulk_openapi_publication where state='UNCOMPOSED')
                                                            """)) {
                                                        if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != 1 || rows.getInt(3) != 1)
                                                            throw new java.sql.SQLException("native lifecycle rows not observed before rollback", "XX001");
                                                    }
                                                    throw new java.sql.SQLException("test-only interruption after native V12 reader CAS", "XX000");
                                                }
                                                return value;
                                            } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                                        });
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }

    @Test void v12FunctionAclOwnerBodySearchPathAndMembershipDriftFailLiveRead() {
        var value = evaluation(proposal());
        UUID id = persist(value, preview(value));
        String function = "praxis_bulk.assert_preview_integrity_complete()";
        String definition = sql.queryForObject("select pg_get_functiondef(?::regprocedure)",
                String.class, function);
        assertLiveDrift(id,
                () -> sql.execute("grant execute on function " + function + " to public"),
                () -> sql.execute("revoke execute on function " + function + " from public"));
        assertLiveDrift(id,
                () -> sql.execute("grant select on praxis_bulk.praxis_bulk_preview_reader_bootstrap "
                        + "to bulk_runtime_test"),
                () -> sql.execute("revoke select on praxis_bulk.praxis_bulk_preview_reader_bootstrap "
                        + "from bulk_runtime_test"));
        assertLiveDrift(id,
                () -> sql.execute("grant execute on function " + function
                        + " to bulk_runtime_test with grant option"),
                () -> sql.execute("revoke grant option for execute on function " + function
                        + " from bulk_runtime_test"));
        sql.execute("create role preview_rogue_owner nologin");
        assertLiveDrift(id,
                () -> sql.execute("alter function " + function + " owner to preview_rogue_owner"),
                () -> sql.execute("alter function " + function + " owner to postgres"));
        assertLiveDrift(id,
                () -> sql.execute("alter function " + function + " set search_path = public"),
                () -> sql.execute("alter function " + function + " set search_path = pg_catalog, pg_temp"));
        assertLiveDrift(id,
                () -> sql.execute("""
                        create or replace function praxis_bulk.assert_preview_integrity_complete()
                        returns boolean language plpgsql stable security definer
                        set search_path = pg_catalog, pg_temp as $$ begin return true; end $$
                        """),
                () -> sql.execute(definition));
        assertLiveDrift(id,
                () -> sql.execute("grant pg_read_all_data to bulk_runtime_test"),
                () -> sql.execute("revoke pg_read_all_data from bulk_runtime_test"));
    }

    private void assertLiveDrift(UUID id, Runnable mutate, Runnable restore) {
        mutate.run();
        try {
            assertThatThrownBy(() -> page(id, -1, 1, 10))
                    .isInstanceOfSatisfying(BulkProposalStorageException.class,
                            error -> assertThat(error.reason())
                                    .isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE));
        } finally {
            restore.run();
        }
        assertThat(page(id, -1, 1, 10).kind()).isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);
    }

    @Test void detectsMessageOnlyLeafAndManifestDriftWithoutProtectedEvaluationDecode() {
        var ordinary = evaluation(proposal());
        var original = ordinary.targets().getFirst();
        var privateMessage = new org.praxisplatform.uischema.command.ResourceCommandMessage(
                org.praxisplatform.uischema.command.ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                "STATE_NOT_ALLOWED", "private employee detail", null, Map.of());
        var blocked = new BulkTargetEvidence<>(original.target(), original.observedVersion(),
                original.facts(), original.plan(),
                BulkTargetEligibility.blocked(List.of(privateMessage)));
        var value = new BulkEvaluationSnapshot(ordinary.proposal(), ordinary.evaluatedAt(),
                List.of(blocked), governance());
        UUID id = persist(value, new BulkPreviewProjection(value, "test/1", List.of(
                new BulkPreviewProjection.PublicDiagnostic(privateMessage.category(),
                        privateMessage.code(), "Public"))));
        byte[] originalDiagnostics = sql.queryForObject("""
                select diagnostics from praxis_bulk.praxis_bulk_target_preview where proposal_id=?
                """, byte[].class, id);
        String originalLeaf = sql.queryForObject("""
                select item_digest from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?
                """, String.class, id);
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_target_preview set diagnostics=? where proposal_id=?",
                    "[{\"category\":\"CONFLICT_DEPENDENCY\",\"code\":\"STATE_NOT_ALLOWED\",\"message\":\"Changed\"}]"
                            .getBytes(StandardCharsets.UTF_8), id);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        }
        assertCorrupt(id);
        // Restore by schema-owner corruption fixture to isolate the leaf and manifest checks.
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_target_preview set diagnostics=? where proposal_id=?",
                    originalDiagnostics, id);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        }
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_preview_item_integrity set item_digest=? where proposal_id=?",
                    "sha256:" + "0".repeat(64), id);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity enable trigger user");
        }
        assertCorrupt(id);
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_preview_item_integrity set item_digest=? where proposal_id=?",
                    originalLeaf, id);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity enable trigger user");
        }
        sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_target_manifest set expected_version=? where proposal_id=?",
                    "v2".getBytes(StandardCharsets.UTF_8), id);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest enable trigger user");
        }
        assertCorrupt(id);
    }

    private void assertCorrupt(UUID id) {
        assertThatThrownBy(() -> page(id, -1, 1, 10))
                .isInstanceOfSatisfying(BulkProposalStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT))
                .hasNoCause().hasMessageNotContaining("private");
    }

    @Test void readsTenThousandWithSizePlusOneAndExclusiveKeyset() {
        var selected = new ArrayList<BulkTarget<String>>(10_000);
        var evidence = new ArrayList<BulkTargetEvidence<?>>(10_000);
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        for (int ordinal = 0; ordinal < 10_000; ordinal++) {
            var target = new BulkTarget<>("target-" + ordinal, "v1");
            selected.add(target);
            evidence.add(new BulkTargetEvidence<>(target, "observed", empty, empty,
                    BulkTargetEligibility.executable()));
        }
        var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null), empty);
        var input = proposal(BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                JsonNode::deepCopy, JsonNode::deepCopy));
        var value = new BulkEvaluationSnapshot(input, input.createdAt().plusSeconds(1), evidence, governance());
        UUID id = persist(value, preview(value));
        var first = page(id, -1, 10_000, 200);
        assertThat(first.items()).hasSize(200);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextOrdinal()).isEqualTo(199);
        var second = page(id, first.nextOrdinal(), 10_000, 200);
        assertThat(second.items()).hasSize(200);
        assertThat(second.items().getFirst().ordinal()).isEqualTo(200);
        var last = page(id, 9799, 10_000, 200);
        assertThat(last.items()).hasSize(200);
        assertThat(last.items().getLast().ordinal()).isEqualTo(9999);
        assertThat(last.hasMore()).isFalse();
    }

    @Test void budgetAcceptsExactLimitAndChargesTheLookaheadRow() {
        var selected = List.of(new BulkTarget<>("one", "v1"), new BulkTarget<>("two", "v1"));
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, selected, null, null), empty);
        var input = proposal(BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                JsonNode::deepCopy, JsonNode::deepCopy));
        var evidence = selected.stream().map(target -> new BulkTargetEvidence<>(target, "observed",
                empty, empty, BulkTargetEligibility.executable())).toList();
        var value = new BulkEvaluationSnapshot(input, input.createdAt().plusSeconds(1), evidence, governance());
        UUID id = persist(value, preview(value));
        byte[] firstWire = jsonStringBytes(8 * 1024 * 1024);
        byte[] firstVersion = repeatedBytes(5 * 1024 * 1024);
        byte[] secondWire = jsonStringBytes(1024 * 1024);
        replaceManifestAndLeaf(id, 0, firstWire, firstVersion);
        replaceManifestAndLeaf(id, 1, secondWire, repeatedBytes(1));
        long remaining = 20L * 1024 * 1024 - selectedPageBytes(id);
        assertThat(remaining).isBetween(1L, 8L * 1024 * 1024 - 1);
        replaceManifestAndLeaf(id, 1, secondWire, repeatedBytes(Math.toIntExact(remaining + 1)));
        assertThat(selectedPageBytes(id)).isEqualTo(20L * 1024 * 1024);
        var exact = page(id, -1, 2, 1);
        assertThat(exact.items()).hasSize(1);
        assertThat(exact.hasMore()).isTrue();
        replaceManifestAndLeaf(id, 1, secondWire, repeatedBytes(Math.toIntExact(remaining + 2)));
        assertThat(selectedPageBytes(id)).isEqualTo(20L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> page(id, -1, 2, 1))
                .isInstanceOfSatisfying(BulkProposalStorageException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE));
    }

    private static byte[] repeatedBytes(int size) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) 'v');
        return bytes;
    }

    private static byte[] jsonStringBytes(int size) {
        byte[] bytes = repeatedBytes(size);
        bytes[0] = '"';
        bytes[size - 1] = '"';
        return bytes;
    }

    private void replaceManifestAndLeaf(UUID id, int ordinal, byte[] wire, byte[] version) {
        var parent = sql.queryForMap("""
                select evaluation_fingerprint, projector_revision, target_count,
                       public_allowlist, projection_digest
                  from praxis_bulk.praxis_bulk_preview_state where proposal_id=?
                """, id);
        var item = sql.queryForMap("""
                select m.target_digest, p.decision, p.diagnostics
                  from praxis_bulk.praxis_bulk_target_manifest m
                  join praxis_bulk.praxis_bulk_target_preview p
                    on p.proposal_id=m.proposal_id and p.ordinal=m.ordinal
                 where m.proposal_id=? and m.ordinal=?
                """, id, ordinal);
        String wireDigest = BulkTargetDigest.wireIdentity(wire);
        byte[] contextDigest = BulkPreviewItemIntegrity.contextDigest(id,
                (String) parent.get("evaluation_fingerprint"), (String) parent.get("projector_revision"),
                (Integer) parent.get("target_count"), (byte[]) parent.get("public_allowlist"),
                (String) parent.get("projection_digest"));
        String digest = BulkPreviewItemIntegrity.itemDigest(contextDigest, ordinal, wire, wireDigest,
                version, (String) item.get("target_digest"), (String) item.get("decision"),
                (byte[]) item.get("diagnostics"));
        sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest disable trigger user");
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity disable trigger user");
        try {
            sql.update("""
                    update praxis_bulk.praxis_bulk_target_manifest
                       set wire_identity=?, wire_identity_digest=?, expected_version=?
                     where proposal_id=? and ordinal=?
                    """, wire, wireDigest, version, id, ordinal);
            sql.update("""
                    update praxis_bulk.praxis_bulk_preview_item_integrity set item_digest=?
                     where proposal_id=? and ordinal=?
                    """, digest, id, ordinal);
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity enable trigger user");
            sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest enable trigger user");
        }
    }

    private long selectedPageBytes(UUID id) {
        return sql.queryForObject("""
                select octet_length(p.fingerprint) + octet_length(e.input_fingerprint)
                       + octet_length(e.evaluation_fingerprint) + octet_length(s.evaluation_fingerprint)
                       + octet_length(s.projection_state) + octet_length(s.projector_revision)
                       + octet_length(s.public_allowlist) + octet_length(s.projection_digest)
                       + 2 * 4 + 128
                       + (select sum(octet_length(m.evaluation_fingerprint)
                           + octet_length(m.wire_identity) + octet_length(m.wire_identity_digest)
                           + octet_length(m.expected_version) + octet_length(m.target_digest)
                           + octet_length(v.evaluation_fingerprint) + octet_length(v.decision)
                           + octet_length(v.diagnostics) + octet_length(i.item_digest)
                           + 3 * 4 + 128)
                            from praxis_bulk.praxis_bulk_target_manifest m
                            join praxis_bulk.praxis_bulk_target_preview v
                              on v.proposal_id=m.proposal_id and v.ordinal=m.ordinal
                            join praxis_bulk.praxis_bulk_preview_item_integrity i
                              on i.proposal_id=m.proposal_id and i.ordinal=m.ordinal
                           where m.proposal_id=p.proposal_id)
                  from praxis_bulk.praxis_bulk_proposal p
                  join praxis_bulk.praxis_bulk_evaluation e on e.proposal_id=p.proposal_id
                  join praxis_bulk.praxis_bulk_preview_state s on s.proposal_id=p.proposal_id
                 where p.proposal_id=?
                """, Long.class, id);
    }

    @Test void expiryCommitCannotMixOldHeaderWithPurgedItems() throws Exception {
        var value = evaluation(proposal());
        UUID id = persist(value, preview(value));
        var paused = new BulkReadPauseDataSource(runtime,
                "from praxis_bulk.praxis_bulk_proposal p");
        var manager = new DataSourceTransactionManager(paused);
        var pausedReader = new BulkPreviewPageReader(new BulkExecutionInfrastructure(paused,
                manager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        try (var workers = Executors.newSingleThreadExecutor()) {
            var snapshot = workers.submit(() -> {
                paused.arm(Thread.currentThread());
                return pausedReader.read(CONTEXT, id, -1, 1, 10);
            });
            try {
                assertThat(paused.awaitObservation(5, TimeUnit.SECONDS)).isTrue();
                sql.execute("grant praxis_bulk_retention_executor to postgres");
                var ownerTx = new TransactionTemplate(new DataSourceTransactionManager(owner));
                Boolean expired = ownerTx.execute(status -> {
                    sql.execute("set local role praxis_bulk_retention_executor");
                    return sql.queryForObject("select praxis_bulk.expire_unconsumed_proposal(?)",
                            Boolean.class, id);
                });
                assertThat(expired).isTrue();
                paused.release();
                assertThat(snapshot.get(5, TimeUnit.SECONDS).kind())
                        .isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);
                sql.execute("revoke praxis_bulk_retention_executor from postgres");
                assertThat(page(id, -1, 1, 10).kind()).isEqualTo(BulkPreviewPageReader.Kind.ABSENT);
            } finally {
                paused.release();
                if (Boolean.TRUE.equals(sql.queryForObject("""
                        select pg_has_role('postgres', 'praxis_bulk_retention_executor', 'member')
                        """, Boolean.class))) {
                    sql.execute("revoke praxis_bulk_retention_executor from postgres");
                }
            }
        }
    }

    @Test void markerTransitionAfterSnapshotKeepsOldPageButClosesNewRead() throws Exception {
        var value = evaluation(proposal());
        UUID id = persist(value, preview(value));
        for (String marker : List.of("praxis_bulk_preview_integrity_bootstrap",
                "praxis_bulk_preview_reader_bootstrap")) {
            var paused = new BulkReadPauseDataSource(runtime,
                    "select praxis_bulk.assert_preview_integrity_complete()");
            var manager = new DataSourceTransactionManager(paused);
            var pausedReader = new BulkPreviewPageReader(new BulkExecutionInfrastructure(paused,
                    manager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                    BulkPostgresTestSupport.testRoleConfiguration()));
            try (var workers = Executors.newSingleThreadExecutor()) {
                var snapshot = workers.submit(() -> {
                    paused.arm(Thread.currentThread());
                    return pausedReader.read(CONTEXT, id, -1, 1, 10);
                });
                try {
                    assertThat(paused.awaitObservation(5, TimeUnit.SECONDS)).isTrue();
                    sql.execute("update praxis_bulk." + marker + " set phase='PENDING'");
                    paused.release();
                    assertThat(snapshot.get(5, TimeUnit.SECONDS).kind())
                            .isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);
                    assertThatThrownBy(() -> page(id, -1, 1, 10))
                            .isInstanceOfSatisfying(BulkProposalStorageException.class,
                                    error -> assertThat(error.reason())
                                            .isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE));
                } finally {
                    paused.release();
                    sql.execute("update praxis_bulk." + marker + " set phase='COMPLETE'");
                }
            }
        }
    }

    private static final class RecordingAuthorizationProvider implements BulkReadAuthorizationProvider {
        private final BulkExecutionInfrastructure infrastructure;
        private final GlobalDecision global;
        private final ScopeDecision scoped;
        private String requester;
        private String creator;
        private List<Target> targets = List.of();
        private int authorizeCalls;

        private RecordingAuthorizationProvider(BulkExecutionInfrastructure infrastructure,
                GlobalDecision global, ScopeDecision scoped) {
            this.infrastructure = infrastructure;
            this.global = global;
            this.scoped = scoped;
        }

        @Override public String confirmationOperationId() {
            return CONTEXT.operationRef().operationId();
        }

        @Override public BulkExecutionInfrastructure executionInfrastructure() {
            return infrastructure;
        }

        @Override public GlobalDecision preAuthorize(Context context, Duration remainingBudget) {
            requester = context.requesterSubjectId();
            assertThat(remainingBudget).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(3));
            return global;
        }

        @Override public ScopeDecision authorize(Context context, String creatorSubjectId,
                List<Target> fullTargetSet, Duration remainingBudget) {
            authorizeCalls++;
            requester = context.requesterSubjectId();
            creator = creatorSubjectId;
            targets = List.copyOf(fullTargetSet);
            assertThat(remainingBudget).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(3));
            return scoped;
        }
    }

    private static BulkReadCursorConfiguration cursorConfiguration() {
        return new BulkReadCursorConfiguration("test-key", Map.of("test-key",
                new SecretKeySpec(new byte[32], "AES")), Duration.ofMinutes(5));
    }
}
