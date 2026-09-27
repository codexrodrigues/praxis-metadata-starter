package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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

    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        sql = new JdbcTemplate(owner);
        var manager = new DataSourceTransactionManager(runtime);
        tx = new TransactionTemplate(manager);
        var infrastructure = new BulkExecutionInfrastructure(runtime, manager, CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration());
        store = new JdbcBulkProposalStore(infrastructure);
        reader = new BulkPreviewPageReader(infrastructure);
    }

    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    @BeforeEach void reset() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(12);
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
        UUID id = persist(value, preview(value));
        var page = page(id, -1, 1, 200);
        assertThat(page.kind()).isEqualTo(BulkPreviewPageReader.Kind.COMPLETE);
        assertThat(page.targetCount()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().ordinal()).isZero();
        assertThat(page.items().getFirst().decision()).isEqualTo(BulkTargetEligibility.Decision.EXECUTABLE);
        assertThat(page.items().getFirst().diagnostics()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextOrdinal()).isZero();
        assertThat(page(id, 0, 1, 200).items()).isEmpty();
        assertThatThrownBy(() -> page(id, -1, 2, 200))
                .isInstanceOfSatisfying(BulkProposalStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT));
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
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(10);
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

    @Test void upgradesV11AndRetriesWrongRoleBootstrapWithoutHealingCompletedDrift() {
        sql.execute("drop schema praxis_bulk cascade");
        Flyway.configure().dataSource(owner).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("11").load().migrate();
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(1);
        sql.execute("update praxis_bulk.praxis_bulk_preview_reader_bootstrap set phase='PENDING'");
        sql.execute("revoke execute on function praxis_bulk.assert_preview_integrity_complete() "
                + "from bulk_runtime_test, durable_runtime");
        var incompleteRoles = new BulkExecutionRoleConfiguration("postgres", java.util.Set.of("bulk_runtime_test"),
                java.util.Set.of(), java.util.Set.of());
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), incompleteRoles))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_reader_bootstrap
                """, String.class)).isEqualTo("PENDING");
        assertThat(sql.queryForObject("""
                select has_function_privilege('bulk_runtime_test',
                    'praxis_bulk.assert_preview_integrity_complete()', 'EXECUTE')
                """, Boolean.class)).isFalse();
        String function = "praxis_bulk.assert_preview_integrity_complete()";
        String originalDefinition = sql.queryForObject("select pg_get_functiondef(?::regprocedure)",
                String.class, function);
        sql.execute("""
                create or replace function praxis_bulk.assert_preview_integrity_complete()
                returns boolean language plpgsql stable security definer
                set search_path = pg_catalog, pg_temp as $$ begin return true; end $$
                """);
        assertThatThrownBy(() -> BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("body differs");
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_reader_bootstrap
                """, String.class)).isEqualTo("PENDING");
        assertThat(sql.queryForObject("""
                select has_function_privilege('bulk_runtime_test',
                    'praxis_bulk.assert_preview_integrity_complete()', 'EXECUTE')
                """, Boolean.class)).isFalse();
        sql.execute(originalDefinition);
        sql.execute("create role preview_wrong_reader_owner nologin");
        sql.execute("alter function " + function + " owner to preview_wrong_reader_owner");
        assertThatThrownBy(() -> BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected owner");
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_reader_bootstrap
                """, String.class)).isEqualTo("PENDING");
        assertThat(sql.queryForObject("""
                select has_function_privilege('bulk_runtime_test',
                    'praxis_bulk.assert_preview_integrity_complete()', 'EXECUTE')
                """, Boolean.class)).isFalse();
        sql.execute("alter function " + function + " owner to postgres");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isZero();
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_reader_bootstrap
                """, String.class)).isEqualTo("COMPLETE");
        sql.execute("revoke execute on function praxis_bulk.assert_preview_integrity_complete() from bulk_runtime_test");
        assertThatThrownBy(() -> BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("""
                select has_function_privilege('bulk_runtime_test',
                    'praxis_bulk.assert_preview_integrity_complete()', 'EXECUTE')
                """, Boolean.class)).isFalse();
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
}
