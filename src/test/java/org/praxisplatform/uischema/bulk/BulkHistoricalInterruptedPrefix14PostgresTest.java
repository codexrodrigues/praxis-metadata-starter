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

/** An authenticated interruption after V14 is not first-time publication initialization.
 * Public146 produces complete V13; a separate public149 SQL-only child commits V14.
 * No deleted/fabricated publication row, marker, or candidate SQL constructs that prefix.
 */
class BulkHistoricalInterruptedPrefix14PostgresTest {
    @TempDir Path temporaryDirectory;
    private static final String HISTORY = "praxis_bulk.praxis_bulk_schema_history";
    private static final String LEDGER_BLOCK = "every bound deployment requires its durable OpenAPI publication row";
    private static final String BLOCK = "governed lifecycle function grants differ";

    @Test
    void interruptedArtifactAuthenticatedPrefix14CannotBeHealed() throws Exception {
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
            var native13Topology = roleTopology(sql);
            var native13Acl = acl(sql);
            var native13NamespaceAcl = namespaceBindingAclEntries(sql);
            Files.writeString(evidence.resolve("native13-topology.txt"), native13Topology.toString());
            writeCommittedPrefix14(postgres, temporaryDirectory);
            assertHistory(sql, 14);
            assertThat(history(sql).subList(0, originalHistory.size())).isEqualTo(originalHistory);
            assertThat(snapshot(sql, protectedColumns)).isEqualTo(historicalRows);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_openapi_publication", Integer.class)).isZero();
            assertThat(roleTopology(sql)).isEqualTo(native13Topology);
            assertPrefix14AclDelta(sql, native13Acl, native13NamespaceAcl);
            var committedPrefix14 = history(sql);
            Files.writeString(evidence.resolve("interrupted-prefix14-history.txt"), committedPrefix14.toString());
            Files.writeString(evidence.resolve("interrupted-prefix14-rows.txt"), snapshot(sql, columns(sql)).toString());
            Files.writeString(evidence.resolve("interrupted-prefix14-acl.txt"), acl(sql).toString());
            Files.writeString(evidence.resolve("interrupted-prefix14-topology.txt"), roleTopology(sql).toString());
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
            assertThat(history(sql).subList(0, committedPrefix14.size())).isEqualTo(committedPrefix14); // independently committed Flyway DDL; owner bootstrap has not healed authority
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
                    Integer.class)).isZero();
            var afterFirst = snapshot(sql, columns(sql));
            Files.writeString(evidence.resolve("after-first-full-rows.txt"), afterFirst.toString());
            var historyAfterFirst = history(sql);
            var aclAfterFirst = acl(sql);
            var expectedTopology = new ArrayList<Map<String,Object>>(native13Topology);
            assertThat(native13Topology.stream().noneMatch(row -> "praxis_bulk_capacity_owner".equals(row.get("identity")))).isTrue();
            expectedTopology.add(Map.of("kind", "role", "identity", "praxis_bulk_capacity_owner",
                    "attributes", "[false, false, false, false, false, false, false]"));
            assertThat(roleTopology(sql)).containsExactlyInAnyOrderElementsOf(expectedTopology);
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
            assertThat(retry).isInstanceOf(IllegalStateException.class).hasMessage(LEDGER_BLOCK);
            assertHistory(sql, 19);
            assertThat(snapshot(sql, columns(sql))).isEqualTo(afterFirst);
            assertThat(history(sql)).isEqualTo(historyAfterFirst);
            assertThat(acl(sql)).isEqualTo(aclAfterProvisioning);
            assertThat(roleTopology(sql)).isEqualTo(roleTopology);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_openapi_publication", Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution", Integer.class)).isZero();
            Files.writeString(evidence.resolve("after-first-ledger-rejection-full-rows.txt"), snapshot(sql, columns(sql)).toString());
            Files.writeString(evidence.resolve("after-first-ledger-rejection-history.txt"), history(sql).toString());
            Files.writeString(evidence.resolve("after-first-ledger-rejection-acl.txt"), acl(sql).toString());
            Files.writeString(evidence.resolve("after-first-ledger-rejection-topology.txt"), roleTopology(sql).toString());
            var restarted = catchThrowable(() -> BulkExecutionMigrator.migrate(
                    new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", ""),
                    bindings, roles, identities));
            writeFailure(evidence.resolve("provisioned-restart-failure.txt"), restarted);
            assertThat(restarted).isInstanceOf(IllegalStateException.class).hasMessage(LEDGER_BLOCK);
            assertThat(snapshot(sql, columns(sql))).isEqualTo(afterFirst);
            assertThat(history(sql)).isEqualTo(historyAfterFirst);
            assertThat(acl(sql)).isEqualTo(aclAfterProvisioning);
            assertThat(roleTopology(sql)).isEqualTo(roleTopology);
            Files.writeString(evidence.resolve("after-restart-full-rows.txt"), snapshot(sql, columns(sql)).toString());
            Files.writeString(evidence.resolve("after-restart-history.txt"), history(sql).toString());
            Files.writeString(evidence.resolve("after-restart-acl.txt"), acl(sql).toString());
            Files.writeString(evidence.resolve("characterization-result.properties"),
                    "producer=PUBLIC146_NATIVE13\nprefix=PUBLIC149_SQL_INTERRUPTED14\nprovisioning=EXACT_DOCUMENTED_GRANTS\ncurrentUpgrade=BLOCKED_LEDGER\nrestart=BLOCKED_WITHOUT_MUTATION\n"
                    + "historicalAcceptance=CANNOT_CLOSE\nledgerAbsence=CAUSAL_DIAGNOSTIC_REACHED\nbackendComplete=false\n");
        }
    }

    private static void writeCommittedPrefix14(EmbeddedPostgres postgres, Path temporaryDirectory) throws Exception {
        var project = Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).toRealPath();
        var workRoot = historicalTestDirectory("praxis.prefix14.work", temporaryDirectory.resolve("prefix14-work"));
        var repository = historicalTestDirectory("praxis.prefix14.repository", temporaryDirectory.resolve("prefix14-repository"));
        try (var entries = Files.list(repository)) { assertThat(entries.findAny()).isEmpty(); }
        try (var entries = Files.list(workRoot)) { assertThat(entries.findAny()).isEmpty(); }
        assertThat(workRoot).isNotEqualTo(repository);
        var fixture = Files.createDirectory(workRoot.resolve("project"));
        var source = project.resolve("src/test/resources/historical/bulk-interrupted-prefix14-consumer");
        try (var files = Files.walk(source)) {
            for (var path : files.sorted().toList()) {
                var destination = fixture.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination);
            }
        }
        var configured = System.getProperty("praxis.historical.settings");
        Path settings;
        if (configured == null || configured.isBlank()) {
            settings = temporaryDirectory.resolve("prefix14-central-only-settings.xml");
            Files.writeString(settings, historicalCentralOnlySettings());
        } else settings = Path.of(configured).toRealPath();
        var evidence = workRoot.resolve("prefix14-evidence.properties");
        var jar = repository.resolve("io/github/codexrodrigues/praxis-metadata-starter/8.0.0-rc.149/praxis-metadata-starter-8.0.0-rc.149.jar");
        var command = List.of("sh", project.resolve("mvnw").toString(), "-B", "-ntp", "-s", settings.toString(),
                "-f", fixture.resolve("pom.xml").toString(), "-Dmaven.repo.local=" + repository,
                "-Dhistorical.jdbc.url=" + postgres.getJdbcUrl("postgres", "postgres"), "-Dhistorical.jar=" + jar,
                "-Dcandidate.source.root=" + project, "-Dhistorical.evidence.file=" + evidence,
                "-Dtest=InterruptedPrefix14SqlTest", "test");
        Files.writeString(workRoot.resolve("child-command.txt"), String.join("\n", command) + "\n");
        var builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true)
                .redirectOutput(workRoot.resolve("historical-child-maven.log").toFile());
        builder.environment().put("MAVEN_SKIP_RC", "true"); builder.environment().remove("MAVEN_ARGS");
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        long started = System.currentTimeMillis(); var process = builder.start(); boolean interrupted = false; boolean timedOut = false;
        try {
            Files.writeString(workRoot.resolve("child-pid.properties"), "pid=" + process.pid() + "\nstartedMillis=" + started + "\n");
            timedOut = !process.waitFor(20, TimeUnit.MINUTES);
            assertThat(timedOut).as("prefix14 child bounded wait exceeded").isFalse();
            Files.writeString(workRoot.resolve("child-exit.properties"), "pid=" + process.pid() + "\nstartedMillis=" + started + "\nfinishedMillis=" + System.currentTimeMillis()
                    + "\nactualExit=" + process.exitValue() + "\n");
            assertThat(process.exitValue()).isZero();
        } catch (InterruptedException failure) { interrupted = true; throw failure; }
        finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
                try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException failure) { interrupted = true; }
                try {
                    Files.writeString(workRoot.resolve("child-cleanup.properties"), "pid=" + process.pid()
                            + "\nstartedMillis=" + started + "\nfinishedMillis=" + System.currentTimeMillis()
                            + "\ntimedOut=" + timedOut + "\ninterrupted=" + interrupted
                            + "\naliveAfterCleanup=" + process.isAlive()
                            + (process.isAlive() ? "" : "\nobservedExitAfterCleanup=" + process.exitValue()) + "\n");
                } catch (java.io.IOException receiptFailure) {
                    System.err.println("Prefix14 cleanup receipt could not be written: " + receiptFailure);
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            preservePrefix14ChildEvidence(project, workRoot, fixture, started, process.pid());
        }
        var report = fixture.resolve("target/surefire-reports/TEST-org.praxisplatform.consumer.InterruptedPrefix14SqlTest.xml");
        assertThat(Files.getLastModifiedTime(report).toMillis()).isBetween(started, System.currentTimeMillis());
        Files.copy(report, workRoot.resolve("prefix14-surefire.xml"));
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var root = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        assertThat(root.getAttribute("name")).isEqualTo("org.praxisplatform.consumer.InterruptedPrefix14SqlTest");
        for (var attribute : List.of("tests", "failures", "errors", "skipped"))
            assertThat(root.getAttribute(attribute)).isEqualTo(attribute.equals("tests") ? "1" : "0");
        var tests = root.getElementsByTagName("testcase"); assertThat(tests.getLength()).isEqualTo(1);
        assertThat(((org.w3c.dom.Element) tests.item(0)).getAttribute("name"))
                .isEqualTo("migrationCreatesCommittedPrefix14WithoutPublication");
        assertThat(((org.w3c.dom.Element) tests.item(0)).getAttribute("classname"))
                .isEqualTo("org.praxisplatform.consumer.InterruptedPrefix14SqlTest");
        var xmlProperties = root.getElementsByTagName("property");
        var javaVersions = new ArrayList<String>();
        for (int index = 0; index < xmlProperties.getLength(); index++) {
            var property = (org.w3c.dom.Element) xmlProperties.item(index);
            if (property.getAttribute("name").equals("java.version")) javaVersions.add(property.getAttribute("value"));
        }
        // Attest the actual parent JDK, rather than a workstation-specific patch release.
        assertThat(Runtime.version().feature()).isEqualTo(21);
        assertThat(javaVersions).containsExactly(System.getProperty("java.version"));
        var receipts = new Properties(); try (var input = Files.newInputStream(evidence)) { receipts.load(input); }
        assertThat(receipts.getProperty("historical.jar.sha256"))
                .isEqualTo("a0bd4137726acdced16fb1c193a8db7e6c6c1e26b23a4ba9294ccb8da5ddb2cc");
        assertThat(Path.of(receipts.getProperty("historical.codeSource")).toRealPath()).isEqualTo(jar.toRealPath());
        assertThat(receipts.getProperty("migrations.executed")).isEqualTo("1");
        assertThat(receipts.getProperty("resource.v14.sha256"))
                .isEqualTo("78ea1083dd006a74a9ae8f32780fce0f11f4c71293d4b773f3e323685aa48647");
        assertThat(receipts.getProperty("historical.pom.sha256"))
                .isEqualTo("d5a16be5bab1db40ac6801ae60c233092c7f60b57c276f6d471f1695f48d2dcf");
        assertThat(receipts.getProperty("construction"))
                .isEqualTo("PUBLIC146_NATIVE13_THEN_PUBLIC149_SQL_INTERRUPTED14");
    }

    /** Preserve diagnostics before JUnit removes @TempDir, including nonzero child outcomes. */
    private static void preservePrefix14ChildEvidence(Path project, Path work, Path fixture,
            long started, long pid) throws java.io.IOException {
        Path retained = project.resolve("target/historical-child-custody/prefix14-" + started + "-" + pid);
        Files.createDirectories(retained);
        var observations = new Properties();
        observations.setProperty("pid", Long.toString(pid));
        observations.setProperty("startedMillis", Long.toString(started));
        var sources = new LinkedHashMap<String, Path>();
        for (String name : List.of("historical-child-maven.log", "child-command.txt",
                "child-pid.properties", "child-exit.properties", "child-cleanup.properties")) {
            sources.put(name, work.resolve(name));
        }
        sources.put("child-surefire.xml", fixture.resolve(
                "target/surefire-reports/TEST-org.praxisplatform.consumer.InterruptedPrefix14SqlTest.xml"));
        for (var source : sources.entrySet()) {
            boolean present = Files.isRegularFile(source.getValue());
            observations.setProperty(source.getKey() + ".present", Boolean.toString(present));
            if (present) Files.copy(source.getValue(), retained.resolve(source.getKey()));
        }
        try (var output = Files.newOutputStream(retained.resolve("custody.properties"))) {
            observations.store(output, "Actual child diagnostics; missing XML is not a passing test");
        }
        System.err.println("Prefix14 child diagnostics retained under target/historical-child-custody");
    }

    private static Set<String> namespaceBindingAclEntries(JdbcTemplate sql) {
        return new TreeSet<>(sql.queryForList("""
                select 'table|'||pg_get_userbyid(a.grantee)||'|'||pg_get_userbyid(a.grantor)||'|'||a.privilege_type||'|'||a.is_grantable
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                cross join lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                where n.nspname='praxis_bulk' and c.relname='praxis_bulk_namespace_binding'
                union all
                select 'column:'||v.attname||'|'||pg_get_userbyid(a.grantee)||'|'||pg_get_userbyid(a.grantor)||'|'||a.privilege_type||'|'||a.is_grantable
                from pg_attribute v join pg_class c on c.oid=v.attrelid join pg_namespace n on n.oid=c.relnamespace
                cross join lateral aclexplode(v.attacl) a
                where n.nspname='praxis_bulk' and c.relname='praxis_bulk_namespace_binding' and v.attnum>0 and not v.attisdropped
                """, String.class));
    }

    private static void assertPrefix14AclDelta(JdbcTemplate sql, List<Map<String,Object>> before, Set<String> namespaceBefore) {
        var after = acl(sql);
        var beforeIdentities = new HashSet<String>();
        before.forEach(row -> beforeIdentities.add(row.get("kind") + "|" + row.get("identity")));
        java.util.function.Predicate<Map<String,Object>> alteredNamespaceAcl = row ->
                row.get("kind").equals("relation") && row.get("identity").equals("praxis_bulk_namespace_binding")
                || row.get("kind").equals("column") && row.get("identity").equals("praxis_bulk_namespace_binding.deployment_id");
        assertThat(after.stream().filter(row -> beforeIdentities.contains(row.get("kind") + "|" + row.get("identity")))
                .filter(alteredNamespaceAcl.negate()).toList())
                .isEqualTo(before.stream().filter(alteredNamespaceAcl.negate()).toList());
        for (var old : before.stream().filter(alteredNamespaceAcl).toList()) {
            assertThat(after.stream().filter(row -> row.get("kind").equals(old.get("kind"))
                    && row.get("identity").equals(old.get("identity"))).toList()).hasSize(1)
                    .first().satisfies(row -> assertThat(row.get("owner")).isEqualTo(old.get("owner")));
        }
        var namespaceExpected = new TreeSet<>(namespaceBefore);
        namespaceExpected.add("table|praxis_bulk_control_owner|postgres|SELECT|false");
        namespaceExpected.add("column:deployment_id|praxis_bulk_control_owner|postgres|UPDATE|false");
        assertThat(namespaceBindingAclEntries(sql)).isEqualTo(namespaceExpected);
        var created = after.stream().filter(row -> !beforeIdentities.contains(row.get("kind") + "|" + row.get("identity"))).toList();
        var expectedIdentities = new TreeSet<String>();
        expectedIdentities.add("relation|praxis_bulk_openapi_publication");
        expectedIdentities.add("relation|praxis_bulk_openapi_publication_pkey");
        expectedIdentities.add("column|praxis_bulk_openapi_publication_pkey.deployment_id");
        for (var column : List.of("deployment_id", "state", "generation", "document_digest", "updated_at"))
            expectedIdentities.add("column|praxis_bulk_openapi_publication." + column);
        expectedIdentities.add("function|praxis_bulk.lock_openapi_publication(text,text)");
        expectedIdentities.add("function|praxis_bulk.transition_openapi_publication(text,text,bigint,text,text)");
        assertThat(new TreeSet<>(created.stream().map(row -> row.get("kind") + "|" + row.get("identity")).toList()))
                .isEqualTo(expectedIdentities);
        var owner = sql.queryForObject("select oid from pg_roles where rolname='postgres'", Long.class);
        var controlOwner = sql.queryForObject("select oid from pg_roles where rolname='praxis_bulk_control_owner'", Long.class);
        for (var row : created) {
            assertThat(((Number) row.get("owner")).longValue()).isEqualTo(row.get("kind").equals("function") ? controlOwner : owner);
            if (row.get("kind").equals("function"))
                assertThat(row.get("acl")).isEqualTo("{praxis_bulk_control_owner=X/praxis_bulk_control_owner}");
            else if (row.get("identity").equals("praxis_bulk_openapi_publication"))
                assertThat(row.get("acl")).isEqualTo("{postgres=arwdDxt/postgres,praxis_bulk_control_owner=r/postgres}");
            else if (row.get("kind").equals("column") && Set.of(
                    "praxis_bulk_openapi_publication.state", "praxis_bulk_openapi_publication.generation",
                    "praxis_bulk_openapi_publication.document_digest", "praxis_bulk_openapi_publication.updated_at")
                    .contains(row.get("identity")))
                assertThat(row.get("acl")).isEqualTo("{praxis_bulk_control_owner=w/postgres}");
            else assertThat(row.get("acl")).isNull();
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
