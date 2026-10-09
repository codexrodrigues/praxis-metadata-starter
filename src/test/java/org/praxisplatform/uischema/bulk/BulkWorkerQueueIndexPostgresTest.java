package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Owner schema readiness, not live per-job catalog detection or production throughput. */
class BulkWorkerQueueIndexPostgresTest {
    private static final Map<String, String> NAMESPACES = Map.of("index:prod:scope", "index-deployment");
    private static final BulkExecutionRoleConfiguration ROLES = BulkExecutionRoleConfiguration.none("postgres");
    private static final String INDEX = "praxis_bulk.praxis_bulk_execution_worker_queue_idx";

    @Test
    void freshV20AndReplayKeepAllBootstrapLatchesComplete() throws Exception {
        try (var pg = postgres()) {
            var owner = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            assertThat(BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isEqualTo(20);
            BulkExecutionMigrator.validate(owner, ROLES);
            var before = history(sql);
            var phases = phases(sql);
            assertThat(phases).hasSize(7).allMatch("COMPLETE"::equals);
            assertThat(sql.queryForObject("select to_regclass(?) is not null", Boolean.class, INDEX)).isTrue();
            assertThat(BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isZero();
            assertThat(history(sql)).isEqualTo(before);
            assertThat(phases(sql)).isEqualTo(phases);
        }
    }

    @Test
    void genuinePublicV19UpgradesOnceWithoutChangingPriorHistoryOrBinding() throws Exception {
        try (var pg = postgres(); var sdk = historicalSdk()) {
            var owner = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var migrator = sdk.loadClass("org.praxisplatform.uischema.bulk.BulkExecutionMigrator");
            assertThat(Path.of(migrator.getProtectionDomain().getCodeSource().getLocation().toURI()))
                    .isEqualTo(historicalJar().toRealPath());
            // The genuine public SDK completes its own owner bootstraps; no fixture grants/repair.
            assertThat(invokeHistoricalMigrate(sdk, owner)).isEqualTo(19);
            var before = history(sql);
            var bindings = sql.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding order by namespace_id");
            var phases = phases(sql);
            assertThat(phases).hasSize(7).allMatch("COMPLETE"::equals);
            assertThat(sql.queryForObject("select to_regclass(?) is null", Boolean.class, INDEX)).isTrue();
            assertThat(BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isEqualTo(1);
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history where version is null OR version::integer<=19 order by installed_rank"))
                    .isEqualTo(before);
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding order by namespace_id"))
                    .isEqualTo(bindings);
            assertThat(phases(sql)).isEqualTo(phases);
            BulkExecutionMigrator.validate(owner, ROLES);
            assertThat(BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isZero();
        }
    }

    @Test
    void currentIndexDriftsDenyOwnerValidateAndReplayWithoutRepair() throws Exception {
        // Each scenario has an independent database; no repaired index is mistaken for canonical DDL.
        for (String replacement : List.of(
                "", // missing
                "CREATE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution (created_at,namespace_id,execution_id) WHERE execution_mode='ASYNC' AND status='QUEUED'",
                "CREATE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution (namespace_id,created_at,execution_id) WHERE status='QUEUED'",
                "CREATE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution (namespace_id,created_at,execution_id) INCLUDE(status) WHERE execution_mode='ASYNC' AND status='QUEUED'",
                "CREATE UNIQUE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution (namespace_id,created_at,execution_id) WHERE execution_mode='ASYNC' AND status='QUEUED'",
                "CREATE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution (namespace_id text_pattern_ops,created_at,execution_id) WHERE execution_mode='ASYNC' AND status='QUEUED'",
                "CREATE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution (namespace_id,created_at DESC,execution_id) WHERE execution_mode='ASYNC' AND status='QUEUED'",
                "CREATE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution (lower(namespace_id),created_at,execution_id) WHERE execution_mode='ASYNC' AND status='QUEUED'",
                "CREATE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution (namespace_id COLLATE \"C\",created_at,execution_id) WHERE execution_mode='ASYNC' AND status='QUEUED'")) {
            try (var pg = postgres()) {
                var owner = pg.getPostgresDatabase();
                var sql = new JdbcTemplate(owner);
                assertThat(BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isEqualTo(20);
                sql.execute("DROP INDEX " + INDEX);
                if (!replacement.isEmpty()) sql.execute(replacement);
                var before = history(sql);
                var phases = phases(sql);
                var physical = sql.queryForList("select c.relname,pg_get_indexdef(c.oid) definition from pg_class c where c.oid=to_regclass('" + INDEX + "')");
                assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, ROLES)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isInstanceOf(IllegalStateException.class);
                assertThat(history(sql)).isEqualTo(before);
                assertThat(phases(sql)).isEqualTo(phases);
                assertThat(sql.queryForList("select c.relname,pg_get_indexdef(c.oid) definition from pg_class c where c.oid=to_regclass('" + INDEX + "')"))
                        .isEqualTo(physical);
            }
        }
    }

    @Test
    void prematureIndexInGenuineV19DeniesBeforeNewDdlOrBootstrapMutation() throws Exception {
        try (var pg = postgres(); var sdk = historicalSdk()) {
            var owner = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            assertThat(invokeHistoricalMigrate(sdk, owner)).isEqualTo(19);
            sql.execute("CREATE INDEX praxis_bulk_execution_worker_queue_idx ON praxis_bulk.praxis_bulk_execution(namespace_id,created_at,execution_id) WHERE execution_mode='ASYNC' AND status='QUEUED'");
            var before = history(sql);
            var phases = phases(sql);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isInstanceOf(IllegalStateException.class);
            assertThat(history(sql)).isEqualTo(before);
            assertThat(phases(sql)).isEqualTo(phases);
        }
    }

    @Test
    void interruptedFreshV19ResumesAllSevenLatchesBeforeInstallingV20() throws Exception {
        try (var pg = postgres()) {
            var owner = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            assertThat(rawHistoricalV19(owner)).isEqualTo(19);
            assertThat(phases(sql)).hasSize(7).allMatch("PENDING"::equals);
            var before = history(sql);
            assertThat(BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isEqualTo(1);
            assertThat(phases(sql)).hasSize(7).allMatch("COMPLETE"::equals);
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history where version is null OR version::integer<=19 order by installed_rank"))
                    .isEqualTo(before);
            BulkExecutionMigrator.validate(owner, ROLES);
        }
    }

    @Test
    void pendingMarkerAclDriftDeniesBeforeAnyBootstrapGrantOrV20Ddl() throws Exception {
        try (var pg = postgres()) {
            var owner = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            assertThat(rawHistoricalV19(owner)).isEqualTo(19);
            sql.execute("GRANT SELECT ON praxis_bulk.praxis_bulk_preview_integrity_bootstrap TO PUBLIC");
            var before = history(sql);
            var beforePhases = phases(sql);
            var acl = sql.queryForList("select relname,relacl::text from pg_class where relnamespace='praxis_bulk'::regnamespace order by relname");
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner, NAMESPACES, ROLES)).isInstanceOf(IllegalStateException.class);
            assertThat(history(sql)).isEqualTo(before);
            assertThat(phases(sql)).isEqualTo(beforePhases);
            assertThat(sql.queryForList("select relname,relacl::text from pg_class where relnamespace='praxis_bulk'::regnamespace order by relname"))
                    .isEqualTo(acl);
            assertThat(sql.queryForObject("select to_regclass(?) is null", Boolean.class, INDEX)).isTrue();
        }
    }

    /** Official raw Flyway crash boundary: immutable V1–19 only, before owner bootstrap. */
    private static int rawHistoricalV19(javax.sql.DataSource owner) {
        return org.flywaydb.core.Flyway.configure().dataSource(owner)
                .locations("classpath:db/praxis-bulk-migrations").schemas("praxis_bulk")
                .defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .target("19").createSchemas(true).baselineOnMigrate(false).cleanDisabled(true)
                .validateOnMigrate(true).load().migrate().migrationsExecuted;
    }

    private static List<Map<String, Object>> history(JdbcTemplate sql) {
        return sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
    }

    private static List<String> phases(JdbcTemplate sql) {
        var result = new ArrayList<String>();
        for (String marker : List.of("manifest", "preview", "preview_integrity", "preview_reader", "atomic", "capacity_read", "capacity_occupancy"))
            result.add(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_" + marker + "_bootstrap", String.class));
        return List.copyOf(result);
    }

    private static EmbeddedPostgres postgres() throws Exception {
        return EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
    }

    private static Object invokeHistoricalMigrate(URLClassLoader sdk, javax.sql.DataSource owner) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(sdk);
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(sdk);
            var resource = sdk.getResource("db/praxis-bulk-migrations/V19__bulk_capacity_occupancy.sql");
            assertThat(resource).isNotNull();
            var origin = (java.net.JarURLConnection) resource.openConnection();
            origin.setUseCaches(false);
            assertThat(Path.of(origin.getJarFileURL().toURI()).toRealPath()).isEqualTo(historicalJar().toRealPath());
            assertThat(sdk.getResource("db/praxis-bulk-migrations/V20__bulk_worker_queue_index.sql")).isNull();
            Object count = sdk.loadClass("org.praxisplatform.uischema.bulk.BulkExecutionMigrator")
                    .getMethod("migrate", javax.sql.DataSource.class, Map.class).invoke(null, owner, NAMESPACES);
            var sql = new JdbcTemplate(owner);
            assertThat(sql.queryForObject("select max(version::integer) from praxis_bulk.praxis_bulk_schema_history",
                    Integer.class)).isEqualTo(19);
            assertThat(sql.queryForObject("select to_regclass(?) is null", Boolean.class, INDEX)).isTrue();
            return count;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Path historicalJar() {
        String configured = System.getProperty("praxis.bulk.historical.rc155.jar",
                Path.of(System.getProperty("user.dir"), "target", "historical-bulk-sdk",
                        "metadata-v19-rc155.jar").toString());
        assertThat(configured).as("genuine Central rc155 artifact prepared by generate-test-resources").isNotBlank();
        return Path.of(configured);
    }

    private static URLClassLoader historicalSdk() throws Exception {
        Path jar = historicalJar().toRealPath();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))))
                .isEqualTo("8be69c98a109567118eb12d5f8b29ed955fe54335876a388ca3350265c71c371");
        var paths = new java.util.LinkedHashSet<Path>();
        paths.add(jar);
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String part : cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path dependency = Path.of(part);
            if (!Files.isRegularFile(dependency) || !part.endsWith(".jar")) continue;
            try (var archive = new JarFile(dependency.toFile())) {
                if (archive.getJarEntry("org/praxisplatform/uischema/bulk/BulkExecutionMigrator.class") != null) continue;
            }
            paths.add(dependency.toRealPath());
        }
        var urls = new java.net.URL[paths.size()];
        int ordinal = 0;
        for (Path path : paths) urls[ordinal++] = path.toUri().toURL();
        var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        try {
            assertThat(loader.getResource("db/praxis-bulk-migrations/V19__bulk_capacity_occupancy.sql")).isNotNull();
            assertThat(loader.getResource("db/praxis-bulk-migrations/V20__bulk_worker_queue_index.sql")).isNull();
            return loader;
        } catch (Exception | Error failure) { loader.close(); throw failure; }
    }
}
