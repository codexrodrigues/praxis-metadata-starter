package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Missing committed identity must never be interpreted as initial creation.
 * Public149 owns the native15 producer; the parent deletes only a proven existing row.
 * These are corruption/rejection proofs, not positive upgrade or recovery acceptance.
 */
class BulkHistoricalPublicationIdentityMissingPostgresTest {
    @TempDir Path temporaryDirectory;
    private static final String HISTORY = "praxis_bulk.praxis_bulk_schema_history";
    private static final String LEDGER_BLOCK = "every bound deployment requires its durable OpenAPI publication row";

    @Test void missingLedgerFromNativeReadyInstallationIsNotHealed() throws Exception {
        rejectsMissingIdentity(false);
    }

    @Test void missingLedgerFromPubliclySuspendedInstallationIsNotHealed() throws Exception {
        rejectsMissingIdentity(true);
    }

    private void rejectsMissingIdentity(boolean suspended) throws Exception {
        var evidence = evidenceDirectory(suspended);
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var historical = writeHistoricalProposal(postgres, temporaryDirectory, suspended);
            var sql = new JdbcTemplate(postgres.getPostgresDatabase());
            assertHistory(sql, 15);
            assertThat(historical.getProperty("historical.version")).isEqualTo("8.0.0-rc.149");
            var control = sql.queryForMap("select * from praxis_bulk.praxis_bulk_operation_control");
            assertThat(control.get("state")).isEqualTo(suspended ? "SUSPENDED" : "READY");
            assertThat(((Number) control.get("generation")).longValue()).isEqualTo(suspended ? 2 : 1);
            if (suspended) assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control where state='READY'",
                    Integer.class)).isZero();
            var global = sql.queryForMap("select * from praxis_bulk.praxis_bulk_openapi_publication");
            assertThat(global.get("deployment_id")).isEqualTo(historical.getProperty("deployment"));
            assertThat(global.get("state")).isEqualTo(suspended ? "SUSPENDED" : "PUBLISHED");
            Files.writeString(evidence.resolve("existing-publication-before-deletion.txt"), global.toString());
            var historicalHistory = history(sql);
            var historicalColumns = columns(sql);
            var rowsBefore = snapshot(sql, historicalColumns);
            var topology = roleTopology(sql);
            var nativeAcl = acl(sql);
            Files.writeString(evidence.resolve("native15-before-corruption-rows.txt"), rowsBefore.toString());
            Files.writeString(evidence.resolve("native15-before-corruption-history.txt"), historicalHistory.toString());
            Files.writeString(evidence.resolve("native15-before-corruption-acl.txt"), nativeAcl.toString());
            Files.writeString(evidence.resolve("native15-before-corruption-topology.txt"), topology.toString());
            // Explicit owner corruption in the test. No migration or producer synthesizes this state.
            assertThat(sql.update("delete from praxis_bulk.praxis_bulk_openapi_publication")).isEqualTo(1);
            var rowsAfterDelete = snapshot(sql, historicalColumns);
            var expectedAfterDelete = new TreeMap<>(rowsBefore);
            expectedAfterDelete.put("praxis_bulk_openapi_publication", List.of());
            assertThat(rowsAfterDelete).isEqualTo(expectedAfterDelete);
            assertThat(history(sql)).isEqualTo(historicalHistory);
            assertThat(acl(sql)).isEqualTo(nativeAcl);
            assertThat(roleTopology(sql)).isEqualTo(topology);
            Files.writeString(evidence.resolve("after-owner-delete-rows.txt"), rowsAfterDelete.toString());
            Files.writeString(evidence.resolve("after-owner-delete-history.txt"), history(sql).toString());
            Files.writeString(evidence.resolve("after-owner-delete-acl.txt"), acl(sql).toString());
            Files.writeString(evidence.resolve("after-owner-delete-topology.txt"), roleTopology(sql).toString());
            var roles = new BulkExecutionRoleConfiguration("postgres", Set.of("bulk_consumer_runtime"),
                    Set.of(), Set.of("bulk_consumer_control"));
            var bindings = Map.of(historical.getProperty("namespace"), historical.getProperty("deployment"));
            var identities = List.of(new BulkOperationControlIdentity(historical.getProperty("namespace"), historical.getProperty("operation.id")));
            var first = catchThrowable(() -> BulkExecutionMigrator.migrate(
                    new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""), bindings, roles, identities));
            writeFailure(evidence.resolve("first-rejection.txt"), first);
            assertThat(first).isExactlyInstanceOf(IllegalStateException.class).hasMessage(LEDGER_BLOCK);
            // Current staged DDL may be committed before rejection; assert the exact boundary, not rollback of Flyway.
            assertHistory(sql, 19);
            assertThat(history(sql).subList(0, historicalHistory.size())).isEqualTo(historicalHistory);
            var afterCutover = sql.queryForMap("select * from praxis_bulk.praxis_bulk_operation_control");
            if (suspended) assertThat(afterCutover).isEqualTo(control);
            else {
                var expectedControl = new LinkedHashMap<>(control);
                expectedControl.put("state", "SUSPENDED");
                expectedControl.put("generation", ((Number) control.get("generation")).longValue() + 1);
                for (var field : List.of("descriptor_fingerprint", "structural_revision",
                        "publication_generation", "publication_document_digest")) expectedControl.put(field, null);
                assertThat(((java.sql.Timestamp) afterCutover.get("updated_at")).toInstant())
                        .isAfterOrEqualTo(((java.sql.Timestamp) control.get("updated_at")).toInstant());
                expectedControl.put("updated_at", afterCutover.get("updated_at"));
                assertThat(afterCutover).isEqualTo(expectedControl);
            }
            var historicalAfterCutover = snapshot(sql, historicalColumns);
            var expectedHistorical = new TreeMap<>(rowsAfterDelete);
            // V16's documented nonrolling cutover alone drains READY; every other retained byte stays exact.
            expectedHistorical.put("praxis_bulk_operation_control", historicalAfterCutover.get("praxis_bulk_operation_control"));
            assertThat(historicalAfterCutover).isEqualTo(expectedHistorical);
            var expectedTopology = new ArrayList<Map<String,Object>>(topology);
            assertThat(topology.stream().noneMatch(row -> "praxis_bulk_capacity_owner".equals(row.get("identity")))).isTrue();
            // V19 creates this dedicated NOLOGIN/NOINHERIT role; no membership or other role drift is permitted.
            expectedTopology.add(Map.of("kind", "role", "identity", "praxis_bulk_capacity_owner",
                    "attributes", "[false, false, false, false, false, false, false]"));
            assertThat(roleTopology(sql)).containsExactlyInAnyOrderElementsOf(expectedTopology);
            var postDdlTopology = roleTopology(sql);
            var allRows = snapshot(sql, columns(sql));
            var allHistory = history(sql);
            var allAcl = acl(sql);
            Files.writeString(evidence.resolve("after-first-full-rows.txt"), allRows.toString());
            Files.writeString(evidence.resolve("after-first-history.txt"), allHistory.toString());
            Files.writeString(evidence.resolve("after-first-acl.txt"), allAcl.toString());
            Files.writeString(evidence.resolve("role-topology-before-ddl.txt"), topology.toString());
            Files.writeString(evidence.resolve("role-topology-after-ddl.txt"), postDdlTopology.toString());
            for (var attempt : List.of("retry", "fresh-owner-restart")) {
                var rejected = catchThrowable(() -> BulkExecutionMigrator.migrate(
                        new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""), bindings, roles, identities));
                writeFailure(evidence.resolve(attempt + "-rejection.txt"), rejected);
                assertThat(rejected).isExactlyInstanceOf(IllegalStateException.class).hasMessage(LEDGER_BLOCK);
                assertThat(snapshot(sql, columns(sql))).isEqualTo(allRows);
                assertThat(history(sql)).isEqualTo(allHistory);
                assertThat(acl(sql)).isEqualTo(allAcl);
                assertThat(roleTopology(sql)).isEqualTo(postDdlTopology);
                assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_openapi_publication", Integer.class)).isZero();
                assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution", Integer.class)).isZero();
                Files.writeString(evidence.resolve(attempt + "-rows.txt"), snapshot(sql, columns(sql)).toString());
            }
        }
    }

    private static List<Map<String,Object>> roleTopology(JdbcTemplate sql) {
        return sql.queryForList("""
                select 'role' as kind, rolname::text as identity,
                       jsonb_build_array(rolsuper,rolinherit,rolcreaterole,rolcreatedb,
                           rolcanlogin,rolreplication,rolbypassrls)::text as attributes
                from pg_roles
                union all
                select 'membership', roleid::text||':'||member::text,
                       jsonb_build_array(grantor::text,admin_option)::text from pg_auth_members
                order by kind,identity
                """);
    }

    private Path evidenceDirectory(boolean suspended) throws Exception {
        var configured = System.getProperty("praxis.origin.evidence");
        return configured == null || configured.isBlank()
                ? Files.createDirectory(temporaryDirectory.resolve(suspended ? "suspended-evidence" : "ready-evidence"))
                : Files.createDirectory(Path.of(configured).toRealPath().resolve(suspended ? "suspended" : "ready"));
    }

    private static void writeFailure(Path file, Throwable failure) throws Exception {
        try (var output = new java.io.PrintWriter(Files.newBufferedWriter(file))) {
            if (failure == null) output.println("NO_FAILURE"); else failure.printStackTrace(output);
        }
    }

    private static List<Map<String,Object>> history(JdbcTemplate sql) {
        return sql.queryForList("select * from " + HISTORY + " order by installed_rank");
    }

    private static void assertHistory(JdbcTemplate sql, int version) {
        var rows = history(sql);
        assertThat(rows).hasSize(version + 1);
        var schemaRows = rows.stream().filter(row -> "SCHEMA".equals(row.get("type"))).toList();
        assertThat(schemaRows).hasSize(1);
        var schema = schemaRows.getFirst();
        assertThat(schema.get("installed_rank")).isEqualTo(0);
        assertThat(schema.get("version")).isNull();
        assertThat(schema.get("description")).isEqualTo("<< Flyway Schema Creation >>");
        assertThat(schema.get("script")).isEqualTo("\"praxis_bulk\"");
        assertThat(schema.get("checksum")).isNull();
        assertThat(schema.get("success")).isEqualTo(true);
        var versioned = rows.stream().filter(row -> row.get("version") != null).toList();
        assertThat(versioned).hasSize(version);
        for (int index = 0; index < version; index++) {
            var row = versioned.get(index);
            assertThat(row.get("version")).isEqualTo(Integer.toString(index + 1));
            assertThat(row.get("type")).isEqualTo("SQL");
            assertThat(row.get("success")).isEqualTo(true);
            assertThat(row.get("checksum")).isNotNull();
        }
    }

    /** Preserve every historical table/column, including bytea via native JSON encoding.
     * New DDL columns are excluded only from the historical projection, retained in the restart snapshot.
     */
    private static Map<String,List<String>> columns(JdbcTemplate sql) {
        var result = new TreeMap<String,List<String>>();
        sql.query("select table_name,column_name from information_schema.columns where table_schema='praxis_bulk' "
                + "and table_name<>'praxis_bulk_schema_history' order by table_name,ordinal_position",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result.computeIfAbsent(row.getString(1),
                        ignored -> new ArrayList<>()).add(row.getString(2)));
        return result;
    }

    private static Map<String,List<String>> snapshot(JdbcTemplate sql, Map<String,List<String>> columns) {
        var result = new TreeMap<String,List<String>>();
        columns.forEach((table, fields) -> {
            assertThat(table).matches("[a-z][a-z0-9_]*");
            fields.forEach(field -> assertThat(field).matches("[a-z][a-z0-9_]*"));
            result.put(table, sql.queryForList("select to_jsonb(t)::text from (select "
                    + String.join(",", fields) + " from praxis_bulk." + table + ") t order by to_jsonb(t)::text", String.class));
        });
        return result;
    }

    private static List<Map<String,Object>> acl(JdbcTemplate sql) {
        return sql.queryForList("""
                select 'relation' as kind, c.relname::text as identity, c.relowner as owner, c.relacl::text as acl
                from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk'
                union all
                select 'column', c.relname||'.'||a.attname, c.relowner, a.attacl::text
                from pg_attribute a join pg_class c on c.oid=a.attrelid
                join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk' and a.attnum>0 and not a.attisdropped
                union all
                select 'function', p.oid::regprocedure::text, p.proowner, p.proacl::text
                from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='praxis_bulk'
                union all
                select 'schema', n.nspname::text, n.nspowner, n.nspacl::text
                from pg_namespace n where n.nspname='praxis_bulk'
                order by kind,identity
                """);
    }

    private static java.util.Properties writeHistoricalProposal(EmbeddedPostgres postgres,
            java.nio.file.Path temporaryDirectory, boolean suspended)
            throws Exception {
        var project = java.nio.file.Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).toRealPath();
        var work = historicalTestDirectory("praxis.historical.work", temporaryDirectory.resolve("work"));
        var repository = historicalTestDirectory("praxis.historical.repository", temporaryDirectory.resolve("repository"));
        work = Files.createDirectory(work.resolve(suspended ? "suspended" : "ready"));
        repository = Files.createDirectory(repository.resolve(suspended ? "suspended" : "ready"));
        try (var entries = java.nio.file.Files.list(repository)) {
            assertThat(entries.findAny()).as("historical Maven repository initially empty").isEmpty();
        }
        try (var entries = java.nio.file.Files.list(work)) {
            assertThat(entries.findAny()).as("historical work directory initially empty").isEmpty();
        }
        assertThat(work).as("historical work and repository must be distinct").isNotEqualTo(repository);
        var configuredSettings = System.getProperty("praxis.historical.settings");
        java.nio.file.Path settings;
        if (configuredSettings == null || configuredSettings.isBlank()) {
            settings = temporaryDirectory.resolve("central-only-settings.xml");
            java.nio.file.Files.writeString(settings, historicalCentralOnlySettings());
        } else settings = java.nio.file.Path.of(configuredSettings).toRealPath();
        assertThat(java.nio.file.Files.isRegularFile(settings)).as("historical Maven settings file").isTrue();
        var fixture = work.resolve("project");
        java.nio.file.Files.createDirectory(fixture);
        var source = project.resolve("src/test/resources/historical/bulk-publication-origin-native15-producer");
        try (var files = java.nio.file.Files.walk(source)) {
            for (var path : files.sorted().toList()) {
                var destination = fixture.resolve(source.relativize(path));
                if (java.nio.file.Files.isDirectory(path)) java.nio.file.Files.createDirectories(destination);
                else java.nio.file.Files.copy(path, destination);
            }
        }
        var evidence = work.resolve("native-writer-evidence.properties");
        var jar = repository.resolve("io/github/codexrodrigues/praxis-metadata-starter/8.0.0-rc.149/"
                + "praxis-metadata-starter-8.0.0-rc.149.jar");
        var command = java.util.List.of("sh", project.resolve("mvnw").toString(), "-B", "-ntp", "-s",
                settings.toString(), "-f", fixture.resolve("pom.xml").toString(),
                "-Dmaven.repo.local=" + repository, "-Dhistorical.jar=" + jar,
                "-Dhistorical.jdbc.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "-Dhistorical.jdbc.runtime.url=" + postgres.getJdbcUrl("bulk_consumer_runtime", "postgres"),
                "-Dhistorical.jdbc.control.url=" + postgres.getJdbcUrl("bulk_consumer_control", "postgres"),
                "-Dhistorical.suspend.before.close=" + suspended,
                "-Dhistorical.evidence.file=" + evidence, "-Dcandidate.source.root=" + project,
                "-Dtest=ArtifactConsumerHttpTest#writesNativeHistoricalProposalForCutover", "test");
        var log = work.resolve("historical-child-maven.log");
        var builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("MAVEN_SKIP_RC", "true"); builder.environment().remove("MAVEN_ARGS");
        java.nio.file.Files.writeString(work.resolve("child-command.txt"), String.join("\n", command) + "\n");
        long started = System.currentTimeMillis();
        var process = builder.start();
        boolean restoreInterrupted = false;
        boolean timedOut = false;
        try {
            java.nio.file.Files.writeString(work.resolve("child-pid.properties"), "pid=" + process.pid()
                    + "\nstartedMillis=" + started + "\n");
            boolean finished = process.waitFor(20, TimeUnit.MINUTES);
            timedOut = !finished;
            if (!finished) throw new AssertionError("Historical child exceeded existing 20-minute budget; " + log);
            int exit = process.exitValue();
            java.nio.file.Files.writeString(work.resolve("child-exit.properties"), "pid=" + process.pid()
                    + "\nstartedMillis=" + started + "\nfinishedMillis=" + System.currentTimeMillis()
                    + "\nactualExit=" + exit + "\n");
            assertThat(exit).as("historical child actual exit; log %s", log).isZero();
            verifyHistoricalChildReport(fixture, work, started, System.currentTimeMillis());
        } catch (InterruptedException interrupted) {
            restoreInterrupted = true;
            throw interrupted;
        } finally {
            // Cleanup only the child owned by this invocation, including errors after launch and interruption.
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { restoreInterrupted = true; }
                try {
                    java.nio.file.Files.writeString(work.resolve("child-cleanup.properties"), "pid=" + process.pid()
                            + "\ntimedOut=" + timedOut + "\ninterrupted=" + restoreInterrupted
                            + "\naliveAfterCleanup=" + process.isAlive()
                            + (process.isAlive() ? "" : "\nobservedExitAfterCleanup=" + process.exitValue()) + "\n");
                } catch (java.io.IOException cleanupReceiptFailure) {
                    // Receipt write failure must not prevent process cleanup or interrupt restoration.
                    System.err.println("Historical child cleanup receipt could not be written: " + cleanupReceiptFailure);
                }
            }
            if (restoreInterrupted) Thread.currentThread().interrupt();
        }
        assertThat(java.nio.file.Files.isRegularFile(evidence)).isTrue();
        var properties = new java.util.Properties();
        try (var input = java.nio.file.Files.newInputStream(evidence)) { properties.load(input); }
        assertThat(properties.getProperty("historical.jar.sha256"))
                .isEqualTo("a0bd4137726acdced16fb1c193a8db7e6c6c1e26b23a4ba9294ccb8da5ddb2cc");
        assertThat(java.nio.file.Path.of(properties.getProperty("historical.codeSource")).toRealPath())
                .isEqualTo(jar.toRealPath());
        return properties;
    }

    private static void verifyHistoricalChildReport(Path fixture, Path work, long started, long finished) throws Exception {
        var report = fixture.resolve("target/surefire-reports/TEST-org.praxisplatform.consumer.ArtifactConsumerHttpTest.xml");
        assertThat(Files.isRegularFile(report)).isTrue();
        long modified = Files.getLastModifiedTime(report).toMillis();
        assertThat(modified).isBetween(started, finished);
        Files.copy(report, work.resolve("native-producer-surefire.xml"));
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var root = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        assertThat(root.getAttribute("name")).isEqualTo("org.praxisplatform.consumer.ArtifactConsumerHttpTest");
        for (var attribute : List.of("tests", "failures", "errors", "skipped")) {
            assertThat(root.getAttribute(attribute)).isEqualTo(attribute.equals("tests") ? "1" : "0");
        }
        var tests = root.getElementsByTagName("testcase");
        assertThat(tests.getLength()).isEqualTo(1);
        var method = (org.w3c.dom.Element) tests.item(0);
        assertThat(method.getAttribute("name")).isEqualTo("writesNativeHistoricalProposalForCutover");
        assertThat(method.getAttribute("classname")).isEqualTo("org.praxisplatform.consumer.ArtifactConsumerHttpTest");
        var properties = root.getElementsByTagName("property");
        var javaVersions = new ArrayList<String>();
        for (int index=0; index<properties.getLength(); index++) {
            var property = (org.w3c.dom.Element) properties.item(index);
            if (property.getAttribute("name").equals("java.version")) javaVersions.add(property.getAttribute("value"));
        }
        assertThat(javaVersions).containsExactly("21.0.10");
    }

    private static java.nio.file.Path historicalTestDirectory(String name, java.nio.file.Path defaultDirectory)
            throws java.io.IOException {
        var configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) return java.nio.file.Files.createDirectory(defaultDirectory).toRealPath();
        return java.nio.file.Path.of(configured).toRealPath();
    }

    private static String historicalCentralOnlySettings() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
                  <mirrors>
                    <mirror>
                      <id>central-only</id>
                      <mirrorOf>*</mirrorOf>
                      <url>https://repo.maven.apache.org/maven2</url>
                    </mirror>
                  </mirrors>
                </settings>
                """;
    }

}
