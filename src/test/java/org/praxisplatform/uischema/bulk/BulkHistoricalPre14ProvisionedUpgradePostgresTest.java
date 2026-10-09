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

/** Real public rc.146 predecessor: canonical V14 creation seeds deny-only publication identity.
 * The first invocation still stops for external provisioning; only the documented grants permit
 * completion. Original pre-fix C14 raw evidence remains historical, not a claim about this source.
 */
class BulkHistoricalPre14ProvisionedUpgradePostgresTest {
    @TempDir Path temporaryDirectory;
    private static final String HISTORY = "praxis_bulk.praxis_bulk_schema_history";
    private static final String BLOCK = "governed lifecycle function grants differ";

    @Test
    void documentedOwnerProvisioningCompletesAuthenticPre14UpgradeWithoutRepublishing() throws Exception {
        var evidence = evidenceDirectory();
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var historical = writeHistoricalProposal(postgres, temporaryDirectory);
            var sql = new JdbcTemplate(postgres.getPostgresDatabase());
            assertThat(historical.getProperty("historical.version")).isEqualTo("8.0.0-rc.146");
            assertHistory(sql, 13);
            var originalHistory = history(sql);
            assertThat(sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_openapi_publication') is null",
                    Boolean.class)).isTrue();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control "
                    + "where state='READY' and generation=1", Integer.class)).isEqualTo(1);
            for (var table : List.of("manifest", "preview", "preview_integrity", "preview_reader")) {
                assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_" + table + "_bootstrap",
                        String.class)).isEqualTo("COMPLETE");
            }
            var controlBefore = sql.queryForMap("select * from praxis_bulk.praxis_bulk_operation_control");
            assertThat(controlBefore.get("namespace_id")).isEqualTo(historical.getProperty("namespace"));
            assertThat(controlBefore.get("operation_id")).isEqualTo(historical.getProperty("operation.id"));
            var protectedColumns = columns(sql);
            var historicalRows = snapshot(sql, protectedColumns);
            Files.writeString(evidence.resolve("native13-rows.txt"), historicalRows.toString());
            Files.writeString(evidence.resolve("native13-history.txt"), history(sql).toString());
            Files.writeString(evidence.resolve("native13-acl.txt"), acl(sql).toString());
            var roles = new BulkExecutionRoleConfiguration("postgres", Set.of("bulk_consumer_runtime"),
                    Set.of(), Set.of("bulk_consumer_control"));
            var bindings = Map.of(historical.getProperty("namespace"), historical.getProperty("deployment"));
            var identities = List.of(new BulkOperationControlIdentity(historical.getProperty("namespace"),
                    historical.getProperty("operation.id")));
            // A new clean owner datasource for each invocation, never the child's transaction.
            var first = catchThrowable(() -> BulkExecutionMigrator.migrate(
                    new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""),
                    bindings, roles, identities));
            writeFailure(evidence.resolve("first-upgrade-failure.txt"), first);
            Files.writeString(evidence.resolve("after-first-history.txt"), history(sql).toString());
            Files.writeString(evidence.resolve("after-first-acl.txt"), acl(sql).toString());
            assertThat(first).isInstanceOf(IllegalStateException.class).hasMessage(BLOCK);
            assertHistory(sql, 19);
            assertThat(history(sql).subList(0, originalHistory.size())).isEqualTo(originalHistory); // independently committed Flyway DDL; owner bootstrap has not healed authority
            var afterHistoricalProjection = snapshot(sql, protectedColumns);
            Files.writeString(evidence.resolve("after-first-historical-rows.txt"), afterHistoricalProjection.toString());
            assertControlCutover(sql, controlBefore);
            var remainingBefore = new TreeMap<>(historicalRows);
            var remainingAfter = new TreeMap<>(afterHistoricalProjection);
            remainingBefore.remove("praxis_bulk_operation_control");
            remainingAfter.remove("praxis_bulk_operation_control");
            assertThat(remainingAfter).isEqualTo(remainingBefore);
            assertNewFunctionGrantsAbsent(sql, evidence);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_openapi_publication",
                    Integer.class)).isEqualTo(1);
            var creationIdentity = sql.queryForMap("select * from praxis_bulk.praxis_bulk_openapi_publication");
            assertThat(creationIdentity.get("deployment_id")).isEqualTo(historical.getProperty("deployment"));
            assertThat(creationIdentity.get("state")).isEqualTo("UNCOMPOSED");
            assertThat(((Number) creationIdentity.get("generation")).longValue()).isZero();
            assertThat(creationIdentity.get("document_digest")).isNull();
            var afterFirst = snapshot(sql, columns(sql));
            Files.writeString(evidence.resolve("after-first-full-rows.txt"), afterFirst.toString());
            var historyAfterFirst = history(sql);
            var aclAfterFirst = acl(sql);
            var roleTopology = roleTopology(sql);
            var aclEntriesBefore = functionAclEntries(sql);
            var expectedAdded = expectedProvisioningAclEntries(sql);
            // Explicit deployment work documented by Metadata/host, not migrator healing.
            sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_consumer_runtime");
            sql.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to bulk_consumer_control");
            sql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_consumer_control");
            var aclAfterProvisioning = acl(sql);
            var expectedFunctionAcl = new TreeSet<>(aclEntriesBefore);
            assertThat(Collections.disjoint(expectedFunctionAcl, expectedAdded)).isTrue();
            expectedFunctionAcl.addAll(expectedAdded);
            assertThat(functionAclEntries(sql)).isEqualTo(expectedFunctionAcl);
            var beforeUnaffectedAcl = aclAfterFirst.stream().filter(row -> !provisionedFunction(row)).toList();
            var afterUnaffectedAcl = aclAfterProvisioning.stream().filter(row -> !provisionedFunction(row)).toList();
            assertThat(afterUnaffectedAcl).isEqualTo(beforeUnaffectedAcl);
            assertThat(roleTopology(sql)).isEqualTo(roleTopology);
            assertThat(snapshot(sql, columns(sql))).isEqualTo(afterFirst);
            assertThat(history(sql)).isEqualTo(historyAfterFirst);
            Files.writeString(evidence.resolve("documented-grants-exact-added-acl.txt"), expectedAdded.toString());
            Files.writeString(evidence.resolve("after-provisioning-acl.txt"), aclAfterProvisioning.toString());
            Files.writeString(evidence.resolve("role-topology.txt"), roleTopology.toString());
            Files.writeString(evidence.resolve("after-provisioning-full-rows.txt"), snapshot(sql, columns(sql)).toString());
            Files.writeString(evidence.resolve("after-provisioning-history.txt"), history(sql).toString());
            var retry = catchThrowable(() -> BulkExecutionMigrator.migrate(
                    new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""),
                    bindings, roles, identities));
            writeFailure(evidence.resolve("restart-failure.txt"), retry);
            assertThat(retry).isNull();
            assertHistory(sql, 20);
            assertThat(history(sql).subList(0, historyAfterFirst.size())).isEqualTo(historyAfterFirst);
            assertThat(snapshot(sql, protectedColumns)).isEqualTo(afterHistoricalProjection);
            assertThat(sql.queryForMap("select * from praxis_bulk.praxis_bulk_openapi_publication"))
                    .isEqualTo(creationIdentity);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution", Integer.class)).isZero();
            BulkExecutionMigrator.validate(sql.getDataSource(), roles);
            var completedRows = snapshot(sql, columns(sql));
            var completedHistory = history(sql);
            var completedAcl = acl(sql);
            var completedTopology = roleTopology(sql);
            Files.writeString(evidence.resolve("completed-full-rows.txt"), completedRows.toString());
            Files.writeString(evidence.resolve("completed-history.txt"), completedHistory.toString());
            Files.writeString(evidence.resolve("completed-acl.txt"), completedAcl.toString());
            var restarted = catchThrowable(() -> BulkExecutionMigrator.migrate(
                    new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""),
                    bindings, roles, identities));
            writeFailure(evidence.resolve("provisioned-restart-failure.txt"), restarted);
            assertThat(restarted).isNull();
            assertThat(snapshot(sql, columns(sql))).isEqualTo(completedRows);
            assertThat(history(sql)).isEqualTo(completedHistory);
            assertThat(acl(sql)).isEqualTo(completedAcl);
            assertThat(roleTopology(sql)).isEqualTo(completedTopology);
            Files.writeString(evidence.resolve("after-restart-full-rows.txt"), snapshot(sql, columns(sql)).toString());
            Files.writeString(evidence.resolve("after-restart-history.txt"), history(sql).toString());
            Files.writeString(evidence.resolve("after-restart-acl.txt"), acl(sql).toString());
            Files.writeString(evidence.resolve("acceptance-result.properties"),
                    "producer=PASS\nprovisioning=EXACT_DOCUMENTED_GRANTS\ncurrentUpgrade=COMPLETE20\n"
                    + "restart=STABLE\npublication=UNCOMPOSED_GENERATION0\nbackendComplete=false\n");
        }
    }

    private static void assertControlCutover(JdbcTemplate sql, Map<String,Object> before) {
        var after = new LinkedHashMap<>(sql.queryForMap("select * from praxis_bulk.praxis_bulk_operation_control"));
        assertThat(before.get("state")).isEqualTo("READY");
        assertThat(((Number) before.get("generation")).longValue()).isEqualTo(1);
        assertThat(before.get("descriptor_fingerprint")).isNotNull();
        assertThat(before.get("structural_revision")).isNotNull();
        assertThat(after).containsKeys("publication_generation", "publication_document_digest");
        assertThat(after.remove("publication_generation")).isNull();
        assertThat(after.remove("publication_document_digest")).isNull();
        var oldTimestamp = ((java.sql.Timestamp) before.get("updated_at")).toInstant();
        var newTimestamp = ((java.sql.Timestamp) after.get("updated_at")).toInstant();
        assertThat(newTimestamp).isAfterOrEqualTo(oldTimestamp);
        var expected = new LinkedHashMap<>(before);
        expected.put("state", "SUSPENDED");
        expected.put("generation", ((Number) before.get("generation")).longValue() + 1);
        expected.put("descriptor_fingerprint", null);
        expected.put("structural_revision", null);
        expected.put("updated_at", after.get("updated_at"));
        assertThat(after).isEqualTo(expected);
    }

    private static void assertNewFunctionGrantsAbsent(JdbcTemplate sql, Path evidence) throws Exception {
        assertThat(sql.queryForObject("select to_regprocedure('praxis_bulk.transition_operation_control(text,text,bigint,text,text,text)') is null",
                Boolean.class)).isTrue();
        var observations = new ArrayList<Map<String,Object>>();
        for (var pair : List.of(
                List.of("bulk_consumer_runtime", "praxis_bulk.lock_openapi_publication(text,text)"),
                List.of("bulk_consumer_control", "praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text)"),
                List.of("bulk_consumer_control", "praxis_bulk.transition_openapi_publication(text,text,bigint,text,text)"))) {
            var row = sql.queryForMap("select ?::text as role, ?::text as function, to_regprocedure(?) is not null as present, "
                    + "has_function_privilege(?, ?, 'EXECUTE') as executable",
                    pair.get(0), pair.get(1), pair.get(1), pair.get(0), pair.get(1));
            observations.add(row);
            assertThat(row.get("present")).isEqualTo(true);
            assertThat(row.get("executable")).isEqualTo(false);
        }
        Files.writeString(evidence.resolve("new-function-grant-absence.txt"), observations.toString());
    }

    private static final List<List<String>> PROVISIONING = List.of(
            List.of("bulk_consumer_runtime", "lock_openapi_publication(text,text)"),
            List.of("bulk_consumer_control", "transition_openapi_publication(text,text,bigint,text,text)"),
            List.of("bulk_consumer_control", "transition_operation_control(text,text,bigint,text,text,text,bigint,text)"));

    private static boolean provisionedFunction(Map<String,Object> row) {
        return "function".equals(row.get("kind")) && PROVISIONING.stream().anyMatch(pair ->
                ("praxis_bulk." + pair.get(1)).equals(row.get("identity")));
    }

    private static SortedSet<String> functionAclEntries(JdbcTemplate sql) {
        return new TreeSet<>(sql.queryForList("""
                select jsonb_build_array(p.oid::regprocedure::text, a.grantor::text,
                       a.grantee::text,a.privilege_type,a.is_grantable)::text
                from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                cross join lateral aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                where n.nspname='praxis_bulk' order by 1
                """, String.class));
    }

    private static SortedSet<String> expectedProvisioningAclEntries(JdbcTemplate sql) {
        var expected = new TreeSet<String>();
        for (var pair : PROVISIONING) {
            expected.add(sql.queryForObject("""
                    select jsonb_build_array(p.oid::regprocedure::text,p.proowner::text,
                           r.oid::text,'EXECUTE',false)::text
                    from pg_proc p cross join pg_roles r
                    where p.oid=to_regprocedure(?) and r.rolname=?
                    """, String.class, "praxis_bulk." + pair.get(1), pair.get(0)));
        }
        assertThat(expected).hasSize(3);
        return expected;
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

    private Path evidenceDirectory() throws Exception {
        var configured = System.getProperty("praxis.pre14.evidence");
        return configured == null || configured.isBlank()
                ? Files.createDirectory(temporaryDirectory.resolve("evidence")) : Path.of(configured).toRealPath();
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
            java.nio.file.Path temporaryDirectory)
            throws Exception {
        var project = java.nio.file.Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).toRealPath();
        var work = historicalTestDirectory("praxis.historical.work", temporaryDirectory.resolve("work"));
        var repository = historicalTestDirectory("praxis.historical.repository", temporaryDirectory.resolve("repository"));
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
        var source = project.resolve("src/test/resources/historical/bulk-pre14-native-producer");
        try (var files = java.nio.file.Files.walk(source)) {
            for (var path : files.sorted().toList()) {
                var destination = fixture.resolve(source.relativize(path));
                if (java.nio.file.Files.isDirectory(path)) java.nio.file.Files.createDirectories(destination);
                else java.nio.file.Files.copy(path, destination);
            }
        }
        var evidence = work.resolve("native-writer-evidence.properties");
        var jar = repository.resolve("io/github/codexrodrigues/praxis-metadata-starter/8.0.0-rc.146/"
                + "praxis-metadata-starter-8.0.0-rc.146.jar");
        var command = java.util.List.of("sh", project.resolve("mvnw").toString(), "-B", "-ntp", "-s",
                settings.toString(), "-f", fixture.resolve("pom.xml").toString(),
                "-Dmaven.repo.local=" + repository, "-Dhistorical.jar=" + jar,
                "-Dhistorical.jdbc.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "-Dhistorical.jdbc.runtime.url=" + postgres.getJdbcUrl("bulk_consumer_runtime", "postgres"),
                "-Dhistorical.jdbc.control.url=" + postgres.getJdbcUrl("bulk_consumer_control", "postgres"),
                "-Dhistorical.evidence.file=" + evidence, "-Dcandidate.source.root=" + project,
                "-Dtest=ArtifactConsumerHttpTest#writesNativePre14Proposal", "test");
        var log = work.resolve("historical-child-maven.log");
        var builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("MAVEN_SKIP_RC", "true"); builder.environment().remove("MAVEN_ARGS");
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
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
                .isEqualTo("405745b5db584b85062d02b68687690aa391eb35104c694ed2ef933e79a8965e");
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
        assertThat(method.getAttribute("name")).isEqualTo("writesNativePre14Proposal");
        assertThat(method.getAttribute("classname")).isEqualTo("org.praxisplatform.consumer.ArtifactConsumerHttpTest");
        var properties = root.getElementsByTagName("property");
        var javaVersions = new ArrayList<String>();
        for (int index=0; index<properties.getLength(); index++) {
            var property = (org.w3c.dom.Element) properties.item(index);
            if (property.getAttribute("name").equals("java.version")) javaVersions.add(property.getAttribute("value"));
        }
        // Attest the actual parent JDK, rather than a workstation-specific patch release.
        assertThat(Runtime.version().feature()).isEqualTo(21);
        assertThat(javaVersions).containsExactly(System.getProperty("java.version"));
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
