package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Assigned Neon storage conformance: no public admission, HTTP, worker fleet or throughput claim. */
class BulkCapacityGlobalOccupancyNeonTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROJECT = "dark-mountain-25788969";
    private static final String BRANCH = "br-aged-paper-ad5ti98v";
    private static final String HOST = "ep-divine-fire-adehuck7.c-2.us-east-1.aws.neon.tech";
    private static final String OWNER = "neondb_owner";
    private static final String JAR_SHA = "994c037961cde1d747c724c94acd6f20e6b886d9d5c8c5906959c49aa9ec59eb";
    private static final Map<String, String> ROLE_NAMES = Map.of("postgres", OWNER,
            "bulk_runtime_test", "scale_runtime_20261013", "durable_runtime", "scale_control_20261013",
            "occupancy_provisioner", "scale_provisioner_20261013", "occupancy_allocator", "scale_allocator_20261013",
            "occupancy_reader", "scale_reader_20261013");

    @Test
    void ordinaryInitializerClosesRuntimeReadBeforeControlCasAndReadsCommittedTuple() throws Exception {
        var lane = new InitializerLanes(false);
        var result = BulkCapacityOccupancyPostgresFixture.composeNeonControl(lane.runtime, lane.control,
                BulkCapacityOccupancyPostgresFixture.CONTEXT, "sha256:" + "a".repeat(64), "document");
        assertThat(result.generation()).isEqualTo(1);
        assertThat(result.descriptorFingerprint()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(lane.events).containsSubsequence("runtime-before:close", "control:borrow",
                "control:commit", "control:close", "runtime-after:borrow", "runtime-after:commit", "runtime-after:close");
        assertThat(lane.events.stream().filter(x -> x.contains(":sql:")).toList()).allSatisfy(event -> {
            if (event.startsWith("control:")) assertThat(event).contains("transition_");
            else assertThat(event).contains("lock_");
        });
        org.mockito.Mockito.verify(lane.before).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        org.mockito.Mockito.verify(lane.after).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        org.mockito.Mockito.verify(lane.write, org.mockito.Mockito.never()).rollback();
    }

    @Test
    void ordinaryInitializerRejectsPostCommitDigestDriftAndClosesBothRoles() throws Exception {
        var lane = new InitializerLanes(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                BulkCapacityOccupancyPostgresFixture.composeNeonControl(lane.runtime, lane.control,
                        BulkCapacityOccupancyPostgresFixture.CONTEXT, "sha256:" + "a".repeat(64), "document"))
                .isInstanceOf(AssertionError.class);
        assertThat(lane.events).containsSubsequence("runtime-before:close", "control:commit", "control:close",
                "runtime-after:borrow", "runtime-after:rollback", "runtime-after:close");
        org.mockito.Mockito.verify(lane.write, org.mockito.Mockito.never()).rollback();
    }

    /** Mock boundary proof only: SQL privileges and remote commits remain the assigned Neon oracle. */
    private static final class InitializerLanes {
        final List<String> events = new ArrayList<>();
        final DataSource runtime = org.mockito.Mockito.mock(DataSource.class);
        final DataSource control = org.mockito.Mockito.mock(DataSource.class);
        final Connection before, write, after;
        InitializerLanes(boolean stale) throws Exception {
            before = connection("runtime-before", List.of(publication("UNCOMPOSED", 0, null)));
            write = connection("control", List.of(transition(1), transition(2), transition(1)));
            after = connection("runtime-after", List.of(publication("PUBLISHED", 2, stale ? "drift" : "document"),
                    operation()));
            var reads = new java.util.ArrayDeque<>(List.of(before, after));
            org.mockito.Mockito.when(runtime.getConnection()).thenAnswer(call -> {
                Connection next = reads.removeFirst();
                events.add(next == before ? "runtime-before:borrow" : "runtime-after:borrow"); return next;
            });
            org.mockito.Mockito.when(control.getConnection()).thenAnswer(call -> {
                assertThat(events).contains("runtime-before:close");
                events.add("control:borrow"); return write;
            });
        }
        private Connection connection(String label, List<java.sql.ResultSet> results) throws Exception {
            Connection c = org.mockito.Mockito.mock(Connection.class);
            var autoCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
            org.mockito.Mockito.when(c.getAutoCommit()).thenAnswer(call -> autoCommit.get());
            org.mockito.Mockito.doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; })
                    .when(c).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
            org.mockito.Mockito.when(c.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            org.mockito.Mockito.when(c.createStatement()).thenReturn(org.mockito.Mockito.mock(java.sql.Statement.class));
            var queue = new java.util.ArrayDeque<>(results);
            org.mockito.Mockito.when(c.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
                events.add(label + ":sql:" + call.getArgument(0));
                var p = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
                org.mockito.Mockito.when(p.executeQuery()).thenReturn(queue.removeFirst()); return p;
            });
            org.mockito.Mockito.doAnswer(call -> { events.add(label + ":commit"); return null; }).when(c).commit();
            org.mockito.Mockito.doAnswer(call -> { events.add(label + ":rollback"); return null; }).when(c).rollback();
            org.mockito.Mockito.doAnswer(call -> { events.add(label + ":close"); return null; }).when(c).close();
            return c;
        }
        private static java.sql.ResultSet row() throws Exception {
            var r = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(r.next()).thenReturn(true, false); return r;
        }
        private static java.sql.ResultSet publication(String state, long generation, String digest) throws Exception {
            var r = row(); org.mockito.Mockito.when(r.getString(1)).thenReturn(state);
            org.mockito.Mockito.when(r.getLong(2)).thenReturn(generation);
            org.mockito.Mockito.when(r.getString(3)).thenReturn(digest);
            org.mockito.Mockito.when(r.getObject(4, java.time.OffsetDateTime.class))
                    .thenReturn(java.time.OffsetDateTime.parse("2026-10-13T00:00:00Z")); return r;
        }
        private static java.sql.ResultSet transition(long generation) throws Exception {
            var r = row(); org.mockito.Mockito.when(r.getBoolean(1)).thenReturn(true);
            org.mockito.Mockito.when(r.getLong(2)).thenReturn(generation); return r;
        }
        private static java.sql.ResultSet operation() throws Exception {
            var r = row(); org.mockito.Mockito.when(r.getString(1)).thenReturn("READY");
            org.mockito.Mockito.when(r.getLong(2)).thenReturn(1L);
            org.mockito.Mockito.when(r.getString(3)).thenReturn("sha256:" + "a".repeat(64));
            org.mockito.Mockito.when(r.getString(4)).thenReturn(BulkCapacityOccupancyPostgresFixture.REVISION); return r;
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    void fourTenantsOccupyEightActiveAndEightyQueueSlotsAndRejectAFifthBindingOnAssignedNeon() throws Exception {
        Path bindingPath = privateFile(Path.of(required("PRAXIS_BULK_SCALE_BINDING_FILE")));
        JsonNode binding = JSON.readTree(bindingPath.toFile());
        assertThat(binding.path("status").asText()).isEqualTo("APPROVED_BY_ROOT");
        assertThat(binding.path("executionAuthorized").asBoolean()).isTrue();
        assertThat(binding.path("project").asText()).isEqualTo(PROJECT);
        assertThat(binding.path("branch").asText()).isEqualTo(BRANCH);
        assertThat(binding.path("endpoint").asText()).isEqualTo(HOST);
        boolean continuing = continuesFirstReady(binding.path("mode").asText());
        if (continuing) validateContinuation(binding);
        else assertThat(binding.has("continuationPreflightPath")).isFalse();
        Instant expires = Instant.parse(binding.path("leaseExpiresAt").asText());
        assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofMinutes(100), Duration.ofHours(4));
        Path output = Path.of(binding.path("outputDirectory").asText());
        assertThat(output.isAbsolute() && output.toRealPath().equals(output)).isTrue();
        assertThat(Files.getPosixFilePermissions(output)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        try (var entries = Files.list(output)) { assertThat(entries.toList()).isEmpty(); }
        System.setProperty("praxis.bulk.proof.directory", output.toString());
        JsonNode identities = JSON.readTree(pinned(binding, "identities").toFile());
        JsonNode credentials = JSON.readTree(pinned(binding, "credentials").toFile());
        JsonNode setup = JSON.readTree(pinned(binding, "setupReceipt").toFile());
        assertThat(setup.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(setup.path("allConnectionsClosed").asBoolean()).isTrue();
        assertThat(setup.path("inheritedCatalogPreserved").asBoolean()).isTrue();
        assertThat(setup.path("branch").asText()).isEqualTo(BRANCH);
        assertThat(setup.path("credentialsSha256").asText()).isEqualTo(hash(pinned(binding, "credentials")));
        assertThat(credentials.path("branch").asText()).isEqualTo(BRANCH);
        assertThat(credentials.path("endpoint").asText()).isEqualTo(HOST);
        validateControl(JSON.readTree(pinned(binding, "control").toFile()));
        Path jar = pinned(binding, "candidateJar"); assertThat(hash(jar)).isEqualTo(JAR_SHA);
        attestClasses(jar);
        attestTestSourcesAndDependencies(binding);
        var uri = URI.create(Files.readString(pinned(binding, "ownerUri")).strip());
        assertThat(uri.getHost()).isEqualTo(HOST);
        assertThat(uri.getPath()).isEqualTo("/neondb");
        assertThat(uri.getQuery()).contains("sslmode=verify-full");
        String[] userInfo = uri.getRawUserInfo().split(":", 2);
        assertThat(URLDecoder.decode(userInfo[0], StandardCharsets.UTF_8)).isEqualTo(OWNER);
        String ownerPassword = URLDecoder.decode(userInfo[1], StandardCharsets.UTF_8);
        var roles = new BulkExecutionRoleConfiguration(OWNER, Set.of(ROLE_NAMES.get("bulk_runtime_test")),
                Set.of(), Set.of(ROLE_NAMES.get("durable_runtime")));
        var authorityRoles = new BulkCapacityAuthorityMigrator.RoleConfiguration(OWNER,
                ROLE_NAMES.get("occupancy_provisioner"), ROLE_NAMES.get("occupancy_allocator"), ROLE_NAMES.get("occupancy_reader"));
        assertThat(identities.path("deploymentId").asText()).isEqualTo(BulkCapacityOccupancyPostgresFixture.DEPLOYMENT);
        assertThat(identities.path("environment").asText()).isEqualTo(BulkCapacityOccupancyPostgresFixture.ENVIRONMENT);
        assertThat(identities.path("authorityEpoch").asLong()).isEqualTo(11);
        assertThat(identities.path("authorityDatabase").asText()).isEqualTo("scale_authority_20261013");
        var identity = new BulkCapacityAuthorityMigrator.Identity(identities.path("deploymentId").asText(),
                identities.path("environment").asText(), UUID.fromString(identities.path("authorityId").asText()), 11);
        var pools = new ArrayList<HikariDataSource>();
        var sources = new LinkedHashMap<String, DataSource>();
        var bindings = new LinkedHashMap<String, BulkCapacityBinding>();
        Throwable primary = null; boolean completed = false;
        try {
            sources.put("postgres:capacity_global", pool("scale_authority_20261013", OWNER, ownerPassword, 4, pools));
            for (String alias : List.of("occupancy_provisioner", "occupancy_allocator", "occupancy_reader")) {
                String role = ROLE_NAMES.get(alias);
                sources.put(alias + ":capacity_global", pool("scale_authority_20261013", role,
                        credentials.path("rolePasswords").path(role).asText(), 1, pools));
            }
            assertThat(identities.path("locals").size()).isEqualTo(5);
            var unique = new java.util.HashSet<UUID>();
            for (int i = 1; i <= 5; i++) {
                JsonNode local = identities.path("locals").get(i - 1);
                String alias = "capacity_local_" + i, database = "scale_domain_" + i + "_20261013";
                assertThat(local.path("ordinal").asInt()).isEqualTo(i);
                assertThat(local.path("database").asText()).isEqualTo(database);
                assertThat(local.path("namespace").asText()).isEqualTo("tenant:prod:occupancy-global-" + i);
                assertThat(local.path("bindingId").asText()).isEqualTo("occupancy-global-binding-" + i);
                assertThat(local.path("tenantId").asText()).isEqualTo("occupancy-global-tenant-" + i);
                assertThat(local.path("generation").asLong()).isEqualTo(3);
                UUID databaseId = UUID.fromString(local.path("databaseId").asText());
                UUID attestationId = UUID.fromString(local.path("attestationId").asText());
                assertThat(unique.add(databaseId) && unique.add(attestationId)).isTrue();
                bindings.put(alias, new BulkCapacityBinding(identity.deploymentId(), local.path("tenantId").asText(),
                        identity.environment(), local.path("bindingId").asText(), 3, databaseId, attestationId,
                        identity.authorityId(), identity.expectedAuthorityEpoch()));
                sources.put("postgres:" + alias, pool(database, OWNER, ownerPassword, 4, pools));
                for (String roleAlias : List.of("bulk_runtime_test", "durable_runtime")) {
                    String role = ROLE_NAMES.get(roleAlias);
                    sources.put(roleAlias + ":" + alias, pool(database, role,
                            credentials.path("rolePasswords").path(role).asText(), 1, pools));
                }
            }
            assertThat(pools.stream().mapToInt(HikariDataSource::getMaximumPoolSize).sum()).isEqualTo(37);
            save(output.resolve("prewarm.private.json"), warm(sources));
            preflight(sources, continuing);
            pools.forEach(pool -> pool.setConnectionTimeout(1000));
            save(output.resolve("preflight.private.json"), JSON.createObjectNode().put("status", "PASS")
                    .put("branch", BRANCH).put("candidateJarSha256", JAR_SHA).put("ownerCreatedOnChild", false));
            var configured = new BulkCapacityOccupancyPostgresFixture.NeonScope(OWNER, roles, identity,
                    authorityRoles, ROLE_NAMES, bindings, sources, pools, new NeonCheckpoints(output, sources), continuing);
            try (var scope = new BulkCapacityOccupancyPostgresFixture.SharedScope(configured)) {
                BulkCapacityGlobalOccupancyPostgresTest.exercise(scope);
            }
            attestClasses(jar); attestTestSourcesAndDependencies(binding);
            completed = true;
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally {
            for (int i = pools.size() - 1; i >= 0; i--) {
                try { pools.get(i).close(); }
                catch (Exception | Error failure) { if (primary == null) primary = failure; else primary.addSuppressed(failure); }
            }
            boolean closed = pools.stream().allMatch(HikariDataSource::isClosed);
            try {
                save(output.resolve("neon-result.private.json"), JSON.createObjectNode()
                        .put("status", completed && primary == null && closed ? "PASS" : "FAILED")
                        .put("candidateJarSha256", JAR_SHA).put("bindingSha256", hash(bindingPath))
                        .put("poolsClosed", closed).put("poolCount", pools.size()).put("ownerCreatedOnChild", false)
                        .put("processExit", "PARENT_MUST_OBSERVE_EXIT")
                        .put("limit", "SERIAL_QUIESCENT_STORAGE_NOT_PUBLIC_ADMISSION_OR_THROUGHPUT"));
            } catch (Exception | Error failure) { if (primary == null) primary = failure; else primary.addSuppressed(failure); }
            if (completed && primary instanceof Exception e) throw e;
            if (completed && primary instanceof Error e) throw e;
        }
    }

    private static boolean continuesFirstReady(String mode) {
        return switch (mode) {
            case "FRESH_SIX_ASSIGNED_DATABASES" -> false;
            case "CONTINUE_AUTHORITY_AND_FIRST_DOMAIN_READY" -> true;
            default -> throw new IllegalArgumentException("Unsupported explicit scale start mode");
        };
    }

    private static void assertSameFullSnapshot(JsonNode expected, JsonNode current) {
        assertThat(current).isEqualTo(expected); // All rows/catalog/ACL/membership/profile fields; types/nulls are preserved.
    }

    private static void validateContinuation(JsonNode binding) throws Exception {
        validateContinuation(binding, Instant.now());
    }

    private static void validateContinuation(JsonNode binding, Instant observationTime) throws Exception {
        Path expectedPath = pinned(binding, "continuationBaseline");
        assertThat(hash(expectedPath)).isEqualTo("1be50d4f3fcca5d0c335aa451d779a6ad321472ba4ee6df29bcfc86c9e4fdfd5");
        Path priorPath = pinned(binding, "priorReadbackReceipt");
        assertThat(hash(priorPath)).isEqualTo("3c6c2551eca207a6a6b6d2c7af164fd2bd48f37208c48b717cdf7a2f54745d18");
        JsonNode prior = JSON.readTree(priorPath.toFile());
        assertThat(prior.path("branch").asText()).isEqualTo(BRANCH);
        assertThat(prior.path("endpoint").asText()).isEqualTo(HOST);
        assertThat(prior.path("allConnectionsClosed").asBoolean()).isTrue();
        JsonNode proof = JSON.readTree(pinned(binding, "continuationPreflight").toFile());
        assertThat(proof.path("status").asText()).isEqualTo("CONTINUATION_PRECONDITIONS_PASS");
        assertThat(proof.path("branch").asText()).isEqualTo(BRANCH);
        assertThat(proof.path("endpoint").asText()).isEqualTo(HOST);
        assertThat(proof.path("candidateJarSha256").asText()).isEqualTo(JAR_SHA);
        assertThat(proof.path("controlSha256").asText()).isEqualTo(binding.path("controlSha256").asText());
        assertThat(proof.path("preflightScriptSha256").asText()).isEqualTo(hash(pinned(binding, "continuationPreflightScript")));
        assertThat(proof.path("allConnectionsClosed").asBoolean()).isTrue();
        assertThat(proof.path("fullSnapshotEqual").asBoolean()).isTrue();
        assertThat(proof.path("historicalOwnedProcessAbsent").asBoolean()).isTrue();
        assertThat(Duration.between(Instant.parse(proof.path("observedAt").asText()), observationTime))
                .isBetween(Duration.ZERO, Duration.ofMinutes(5));
        Path currentPath = pinned(binding, "continuationCurrentSnapshot");
        assertThat(proof.path("snapshotSha256").asText()).isEqualTo(hash(currentPath));
        assertSameFullSnapshot(JSON.readTree(expectedPath.toFile()), JSON.readTree(currentPath.toFile()));
        var cases = proof.path("nativePredicateResults");
        assertThat(cases.size()).isEqualTo(6);
        for (String name : List.of("ACTUAL", "ADMIN_FALSE", "INHERIT_TRUE", "SET_TRUE", "WRONG_MEMBER", "NON_SUPER_GRANTOR"))
        {
            assertThat(cases.path(name).isBoolean()).isTrue();
            assertThat(cases.path(name).asBoolean()).isEqualTo(name.equals("ACTUAL"));
        }
    }

    @Test void scaleContinuationHasNoImplicitModeFallback() {
        assertThat(continuesFirstReady("FRESH_SIX_ASSIGNED_DATABASES")).isFalse();
        assertThat(continuesFirstReady("CONTINUE_AUTHORITY_AND_FIRST_DOMAIN_READY")).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> continuesFirstReady("READY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void scaleContinuationComparisonRejectsFullRowDriftAndTypeChanges() throws Exception {
        JsonNode actual = JSON.readTree("{\"rows\":[{\"id\":1,\"writes\":0,\"nullable\":null}],\"catalog\":{\"admin\":true}}");
        assertSameFullSnapshot(actual, actual.deepCopy());
        for (String changed : List.of(
                "{\"rows\":[{\"id\":1,\"writes\":1,\"nullable\":null}],\"catalog\":{\"admin\":true}}",
                "{\"rows\":[{\"id\":\"1\",\"writes\":0,\"nullable\":null}],\"catalog\":{\"admin\":true}}",
                "{\"rows\":[{\"id\":1,\"writes\":0,\"nullable\":null}],\"catalog\":{\"admin\":false}}"))
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertSameFullSnapshot(actual, JSON.readTree(changed)))
                    .isInstanceOf(AssertionError.class);
    }

    @Test void actualSnapshotUsesEvidenceBoundWhileCredentialBoundRemains64KiB() throws Exception {
        JsonNode binding = JSON.readTree(privateFile(Path.of(required("PRAXIS_BULK_SCALE_BINDING_FILE"))).toFile());
        Path actual = pinned(binding, "continuationBaseline");
        assertThat(Files.size(actual)).isGreaterThan(65536L).isLessThan(2L * 1024 * 1024);
        assertThat(JSON.readTree(actual.toFile()).size()).isEqualTo(6);
        ObjectNode asCredential = JSON.createObjectNode().put("credentialsPath", actual.toString())
                .put("credentialsSha256", hash(actual));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pinned(asCredential, "credentials"))
                .isInstanceOf(AssertionError.class);
    }

    @Test void actualBindingStaticEnvelopePassesBeforeAnyConnection() throws Exception {
        JsonNode binding = JSON.readTree(privateFile(Path.of(required("PRAXIS_BULK_SCALE_BINDING_FILE"))).toFile());
        assertThat(binding.path("status").asText()).isEqualTo("PENDING_ROOT_REVIEW");
        assertThat(binding.path("executionAuthorized").asBoolean()).isFalse();
        assertThat(continuesFirstReady(binding.path("mode").asText())).isTrue();
        assertThat(binding.path("project").asText()).isEqualTo(PROJECT);
        assertThat(binding.path("branch").asText()).isEqualTo(BRANCH);
        assertThat(binding.path("endpoint").asText()).isEqualTo(HOST);
        for (String key : List.of("candidateJar", "control", "credentials", "identities", "setupReceipt", "ownerUri",
                "continuationBaseline", "priorReadbackReceipt", "priorProcess", "priorExit", "migratorSource",
                "continuationPreflightScript", "continuationPreflight", "continuationCurrentSnapshot")) pinned(binding, key);
        Path jar = pinned(binding, "candidateJar"); assertThat(hash(jar)).isEqualTo(JAR_SHA);
        attestClasses(jar); attestTestSourcesAndDependencies(binding);
        JsonNode proof = JSON.readTree(pinned(binding, "continuationPreflight").toFile());
        // Static input compatibility uses its historical observed window only. A new live RO/freshness gate is mandatory before launch.
        Instant historicalWindow = Instant.parse(proof.path("observedAt").asText()).plusSeconds(1);
        validateContinuation(binding, historicalWindow);
        validateControl(JSON.readTree(pinned(binding, "control").toFile()), historicalWindow);
        assertThat(Duration.between(historicalWindow, Instant.parse(binding.path("leaseExpiresAt").asText())))
                .isBetween(Duration.ofMinutes(100), Duration.ofHours(4));
        JsonNode credentials = JSON.readTree(pinned(binding, "credentials").toFile());
        assertThat(credentials.path("branch").asText()).isEqualTo(BRANCH);
        assertThat(credentials.path("endpoint").asText()).isEqualTo(HOST);
        for (String role : ROLE_NAMES.values().stream().filter(x -> !x.equals(OWNER)).toList())
            assertThat(credentials.path("rolePasswords").path(role).asText()).isNotBlank();
        JsonNode setup = JSON.readTree(pinned(binding, "setupReceipt").toFile());
        assertThat(setup.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(setup.path("allConnectionsClosed").asBoolean()).isTrue();
        assertThat(setup.path("branch").asText()).isEqualTo(BRANCH);
        var uri = URI.create(Files.readString(pinned(binding, "ownerUri")).strip());
        assertThat(uri.getHost()).isEqualTo(HOST); assertThat(uri.getPath()).isEqualTo("/neondb");
        assertThat(uri.getQuery()).contains("sslmode=verify-full");
        assertThat(URLDecoder.decode(uri.getRawUserInfo().split(":", 2)[0], StandardCharsets.UTF_8)).isEqualTo(OWNER);
    }

    private static HikariDataSource pool(String database, String role, String password, int max,
            List<HikariDataSource> owned) {
        assertThat(password).isNotBlank();
        var pg = new PGSimpleDataSource(); pg.setServerNames(new String[]{HOST}); pg.setPortNumbers(new int[]{5432});
        pg.setDatabaseName(database); pg.setUser(role); pg.setPassword(password); pg.setSslMode("verify-full");
        pg.setSslRootCert(required("PGSSLROOTCERT")); pg.setConnectTimeout(15);
        pg.setChannelBinding("require");
        var pool = new HikariDataSource(); pool.setDataSource(pg); pool.setMaximumPoolSize(max); pool.setMinimumIdle(max);
        pool.setConnectionTimeout(15000); pool.setInitializationFailTimeout(-1); owned.add(pool); return pool;
    }

    private static ObjectNode warm(Map<String, DataSource> sources) throws Exception {
        ObjectNode receipt = JSON.createObjectNode().put("status", "WARM_DIAGNOSTIC_NOT_COLD_OR_SLA")
                .put("maximumConnections", 37).put("ownerPoolMaxAndMin", 4).put("ordinaryPoolMaxAndMin", 1);
        for (var entry : sources.entrySet()) {
            var loans = new ArrayList<Connection>(); Throwable primary = null;
            try {
                int count = entry.getKey().startsWith("postgres:") ? 4 : 1;
                var pids = new java.util.HashSet<Integer>();
                for (int i = 0; i < count; i++) {
                    Connection c = entry.getValue().getConnection(); loans.add(c);
                    assertThat(c.getAutoCommit()).isTrue();
                    try (var s = c.createStatement()) {
                        s.setQueryTimeout(3);
                        try (var rs = s.executeQuery("select pg_backend_pid()")) {
                            assertThat(rs.next()).isTrue(); assertThat(pids.add(rs.getInt(1))).isTrue();
                        }
                    }
                }
                assertThat(pids).hasSize(count);
                var observed = receipt.putArray(entry.getKey()); pids.stream().sorted().forEach(observed::add);
            } catch (Exception | Error failure) { primary = failure; throw failure; }
            finally {
                Throwable cleanup = null;
                for (Connection c : loans) try { c.close(); }
                catch (Exception | Error failure) { if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure); }
                if (cleanup != null) {
                    if (primary != null) primary.addSuppressed(cleanup);
                    else if (cleanup instanceof Exception e) throw e;
                    else throw (Error) cleanup;
                }
            }
        }
        return receipt;
    }

    private static void preflight(Map<String, DataSource> sources, boolean continuing) throws Exception {
        for (var entry : sources.entrySet()) {
            String dbAlias = entry.getKey().split(":")[1];
            try (Connection c = entry.getValue().getConnection()) {
                c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                    s.setQueryTimeout(3);
                    try (var rs = s.executeQuery("select current_user,session_user,current_setting('server_version_num')::int,"
                            + "rolsuper,rolcreaterole,rolcreatedb,rolreplication,rolbypassrls,rolcanlogin,rolinherit,current_database() "
                            + "from pg_roles where rolname=current_user")) {
                        assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isEqualTo(ROLE_NAMES.get(entry.getKey().split(":")[0]));
                        assertThat(rs.getString(2)).isEqualTo(rs.getString(1)); assertThat(rs.getInt(3)).isBetween(170000, 179999);
                        assertThat(rs.getBoolean(4)).isFalse();
                        String expectedDatabase = dbAlias.equals("capacity_global") ? "scale_authority_20261013"
                                : "scale_domain_" + dbAlias.substring("capacity_local_".length()) + "_20261013";
                        assertThat(rs.getString(11)).isEqualTo(expectedDatabase);
                        if (!rs.getString(1).equals(OWNER)) {
                            for (int column : List.of(5, 6, 7, 8)) assertThat(rs.getBoolean(column)).isFalse();
                            assertThat(rs.getBoolean(9) && rs.getBoolean(10)).isTrue();
                        } else assertThat(rs.getBoolean(5) && rs.getBoolean(6)).isTrue();
                    }
                    if (entry.getKey().startsWith("postgres:")) {
                        try (var rs = s.executeQuery("select count(*) from pg_namespace where nspname like 'praxis_bulk%'")) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong(1)).isEqualTo(continuing
                                    && (dbAlias.equals("capacity_global") || dbAlias.equals("capacity_local_1")) ? 1 : 0);
                        }
                        try (var rs = s.executeQuery("select count(*) from pg_roles where rolname like 'praxis_bulk_%'")) {
                            assertThat(rs.next()).isTrue(); assertThat(rs.getLong(1)).isEqualTo(continuing ? 8 : 0);
                        }
                    }
                } finally { c.rollback(); }
            }
        }
    }

    private static void validateControl(JsonNode raw) throws Exception {
        validateControl(raw, Instant.now());
    }

    private static void validateControl(JsonNode raw, Instant observationTime) throws Exception {
        Instant observed = LocalDateTime.parse(raw.path("observedAt").asText(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")).toInstant(ZoneOffset.UTC);
        assertThat(Duration.between(observed, observationTime)).isBetween(Duration.ZERO, Duration.ofSeconds(899));
        Map<String, JsonNode> results = new HashMap<>();
        for (JsonNode item : raw.path("results")) {
            assertThat(item.path("status").asText()).isEqualTo("fulfilled"); assertThat(item.path("value").path("isError").asBoolean()).isFalse();
            assertThat(results.put(item.path("operation").asText(), JSON.readTree(item.path("value").path("content").get(0).path("text").asText()))).isNull();
        }
        assertThat(results.keySet()).containsExactlyInAnyOrder("describe_project", "get_branch", "list_branch_computes", "list_postgres_databases");
        assertThat(results.get("describe_project").path("id").asText()).isEqualTo(PROJECT);
        assertThat(results.get("describe_project").path("pg_version").asInt()).isEqualTo(17);
        JsonNode branch = results.get("get_branch"); assertThat(branch.path("id").asText()).isEqualTo(BRANCH);
        assertThat(branch.path("project_id").asText()).isEqualTo(PROJECT);
        assertThat(branch.path("parent_id").asText()).isEqualTo("br-solitary-thunder-ads5cf7r");
        for (String field : List.of("protected", "primary", "default")) assertThat(branch.path(field).asBoolean(true)).isFalse();
        var endpoints = new ArrayList<JsonNode>(); results.get("list_branch_computes").forEach(endpoints::add);
        var matching = endpoints.stream().filter(x -> HOST.equals(x.path("host").asText())).toList(); assertThat(matching).hasSize(1);
        assertThat(matching.getFirst().path("branch_id").asText()).isEqualTo(BRANCH);
        assertThat(matching.getFirst().path("project_id").asText()).isEqualTo(PROJECT);
        assertThat(matching.getFirst().path("type").asText()).isEqualTo("read_write");
        assertThat(matching.getFirst().path("disabled").asBoolean(true)).isFalse();
        var dbs = new ArrayList<JsonNode>(); results.get("list_postgres_databases").forEach(dbs::add);
        for (String database : List.of("scale_authority_20261013", "scale_domain_1_20261013", "scale_domain_2_20261013",
                "scale_domain_3_20261013", "scale_domain_4_20261013", "scale_domain_5_20261013")) {
            var found = dbs.stream().filter(x -> database.equals(x.path("name").asText())).toList(); assertThat(found).hasSize(1);
            assertThat(found.getFirst().path("branch_id").asText()).isEqualTo(BRANCH);
            assertThat(found.getFirst().path("owner_name").asText()).isEqualTo(OWNER);
        }
    }

    private static void attestTestSourcesAndDependencies(JsonNode binding) throws Exception {
        List<Class<?>> tests = List.of(BulkCapacityGlobalOccupancyNeonTest.class,
                BulkCapacityGlobalOccupancyPostgresTest.class, BulkCapacityOccupancyPostgresFixture.class);
        assertThat(binding.path("testSources").size()).isEqualTo(tests.size());
        assertThat(binding.path("testClasses").size()).isEqualTo(tests.size());
        for (Class<?> type : tests) {
            JsonNode source = binding.path("testSources").path(type.getSimpleName());
            Path path = Path.of(source.path("path").asText());
            assertThat(path.isAbsolute() && Files.isRegularFile(path) && path.toRealPath().equals(path)).isTrue();
            assertThat(path.getFileName().toString()).isEqualTo(type.getSimpleName() + ".java");
            assertThat(hash(path)).isEqualTo(source.path("sha256").asText());
            try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())))
                        .isEqualTo(binding.path("testClasses").path(type.getSimpleName()).asText());
            }
        }
        List<Class<?>> dependencies = List.of(PGSimpleDataSource.class, HikariDataSource.class,
                org.springframework.jdbc.core.JdbcTemplate.class, org.springframework.jdbc.datasource.DataSourceTransactionManager.class,
                org.flywaydb.core.Flyway.class, ObjectMapper.class);
        assertThat(binding.path("dependencies").size()).isEqualTo(dependencies.size());
        for (Class<?> type : dependencies) {
            Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertThat(path.getFileName().toString()).endsWith(".jar");
            assertThat(hash(path)).isEqualTo(binding.path("dependencies").path(type.getName()).asText());
        }
    }

    private static void attestClasses(Path candidate) throws Exception {
        int compared = 0;
        try (var jar = new JarFile(candidate.toFile())) {
            for (var entry : jar.stream().filter(e -> !e.isDirectory() && ((e.getName().startsWith("org/praxisplatform/uischema/bulk/") && e.getName().endsWith(".class")) || e.getName().startsWith("db/praxis-bulk-"))).toList()) {
                try (var packaged = jar.getInputStream(entry); var loaded = BulkExecutionInfrastructure.class.getClassLoader().getResourceAsStream(entry.getName())) {
                    assertThat(loaded).isNotNull(); assertThat(loaded.readAllBytes()).isEqualTo(packaged.readAllBytes()); compared++;
                }
            }
        }
        assertThat(compared).isGreaterThan(30);
    }
    private static void validateCheckpointName(String phase) {
        assertThat(phase).matches("AUTHORITY_MIGRATION_2|AUTHORITY_VALIDATION_REUSED|DOMAIN1_STORAGE_VALIDATION_REUSED|DOMAIN_MIGRATION_20_[1-5]|FIVE_DISTINCT_AUTHENTICATED_MARKERS|EIGHT_ACTIVE_AND_EIGHTY_QUEUE_RIGHTS_INSTALLED|EIGHT_RUNNING_AND_INITIAL_QUEUE_SLOTS_RELEASED|EIGHT_RUNNING_AND_EIGHTY_QUEUED_SIMULTANEOUSLY|FIFTH_BINDING_DENIED_WITHOUT_EXTRA_OCCUPANCY");
    }

    @Test void recorderWritesBothReusedLabelsAndAcceptsAllEmittedNamesButRejectsUnknown(
            @org.junit.jupiter.api.io.TempDir Path output) throws Exception {
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------"));
        var recorder = new NeonCheckpoints(output, Map.of());
        for (String phase : List.of("AUTHORITY_VALIDATION_REUSED", "DOMAIN1_STORAGE_VALIDATION_REUSED")) {
            recorder.completed(phase);
            JsonNode recorded = JSON.readTree(output.resolve(phase + ".private.json").toFile());
            assertThat(recorded.path("status").asText()).isEqualTo("PASS");
            assertThat(recorded.path("phase").asText()).isEqualTo(phase);
            assertThat(recorded.path("candidateJarSha256").asText()).isEqualTo(JAR_SHA);
        }
        validateCheckpointName("AUTHORITY_MIGRATION_2");
        for (int ordinal = 1; ordinal <= 5; ordinal++) validateCheckpointName("DOMAIN_MIGRATION_20_" + ordinal);
        for (String phase : List.of("FIVE_DISTINCT_AUTHENTICATED_MARKERS", "EIGHT_ACTIVE_AND_EIGHTY_QUEUE_RIGHTS_INSTALLED",
                "EIGHT_RUNNING_AND_INITIAL_QUEUE_SLOTS_RELEASED", "EIGHT_RUNNING_AND_EIGHTY_QUEUED_SIMULTANEOUSLY",
                "FIFTH_BINDING_DENIED_WITHOUT_EXTRA_OCCUPANCY")) validateCheckpointName(phase);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> recorder.completed("UNKNOWN"))
                .isInstanceOf(AssertionError.class);
        try (var files = Files.list(output)) { assertThat(files.toList()).hasSize(2); }
    }

    /** Owner reads are evidence outside SDK budgets, never authority cached for runtime calls. */
    private static final class NeonCheckpoints implements BulkCapacityOccupancyPostgresFixture.NeonCheckpoint {
        private final Path output;
        private final Map<String, DataSource> sources;
        private final long started = System.nanoTime();
        private ObjectNode baseline;
        NeonCheckpoints(Path output, Map<String, DataSource> sources) { this.output = output; this.sources = sources; }

        @Override public void completed(String phase) throws Exception {
            validateCheckpointName(phase);
            if (phase.equals("FIVE_DISTINCT_AUTHENTICATED_MARKERS")) {
                baseline = snapshot(); save(output.resolve("before-occupancy.private.json"), baseline);
            }
            if (phase.equals("FIFTH_BINDING_DENIED_WITHOUT_EXTRA_OCCUPANCY")) {
                ObjectNode after = snapshot(); save(output.resolve("after-occupancy.private.json"), after);
                assertThat(baseline).isNotNull();
                for (String alias : sources.keySet().stream().filter(x -> x.startsWith("postgres:")).toList()) {
                    assertThat(after.path(alias).path("catalog")).isEqualTo(baseline.path(alias).path("catalog"));
                    List<String> stable = alias.equals("postgres:capacity_global")
                            ? List.of("authority_identity", "binding_attestation")
                            : List.of("praxis_bulk_namespace_binding", "praxis_bulk_operation_control", "praxis_bulk_openapi_publication", "praxis_bulk_capacity_marker");
                    for (String table : stable) assertThat(after.path(alias).path("tables").path(table))
                            .isEqualTo(baseline.path(alias).path("tables").path(table));
                }
            }
            save(output.resolve(phase + ".private.json"), JSON.createObjectNode().put("status", "PASS")
                    .put("phase", phase).put("elapsedNanos", System.nanoTime() - started)
                    .put("candidateJarSha256", JAR_SHA).put("at", Instant.now().toString()));
        }

        private ObjectNode snapshot() throws Exception {
            ObjectNode result = JSON.createObjectNode();
            for (var entry : sources.entrySet()) {
                if (!entry.getKey().startsWith("postgres:")) continue;
                String schema = entry.getKey().equals("postgres:capacity_global") ? "praxis_bulk_capacity" : "praxis_bulk";
                try (Connection c = entry.getValue().getConnection()) {
                    c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
                    try (var statement = c.createStatement()) {
                        statement.setQueryTimeout(3);
                        statement.execute("set local statement_timeout='3s';set local lock_timeout='1s';set local transaction_timeout='15s';set local timezone='UTC'");
                        var names = new ArrayList<String>();
                        try (var rs = statement.executeQuery("select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='" + schema + "' and c.relkind='r' order by c.relname")) {
                            while (rs.next()) { String name = rs.getString(1); assertThat(name).matches("[a-z][a-z0-9_]{0,62}"); names.add(name); }
                        }
                        assertThat(names.size()).isBetween(1, 59);
                        String read = names.stream().map(name -> "select '" + name + "' as name,coalesce(jsonb_agg(row_to_json(t)::text order by row_to_json(t)::text),'[]'::jsonb)::text as rows from " + schema + "." + name + " t")
                                .collect(java.util.stream.Collectors.joining(" union all "));
                        ObjectNode database = result.putObject(entry.getKey()), tables = database.putObject("tables");
                        try (var rs = statement.executeQuery(read)) { while (rs.next()) tables.set(rs.getString(1), JSON.readTree(rs.getString(2))); }
                        String catalog = "select jsonb_build_object('roles',(select jsonb_agg(row_to_json(x) order by x.rolname) from (select rolname,rolcanlogin,rolinherit,rolsuper,rolcreaterole,rolcreatedb,rolreplication,rolbypassrls from pg_roles where rolname like 'praxis_bulk_%' or rolname like 'scale_%')x),"
                                + "'members',(select jsonb_agg(row_to_json(x) order by x.role,x.member,x.grantor) from (select r.rolname as role,m.rolname as member,g.rolname as grantor,a.admin_option,a.inherit_option,a.set_option from pg_auth_members a join pg_roles r on r.oid=a.roleid join pg_roles m on m.oid=a.member join pg_roles g on g.oid=a.grantor where r.rolname like 'praxis_bulk_%' or m.rolname like 'scale_%')x),"
                                + "'relations',(select jsonb_agg(row_to_json(x) order by x.relname) from (select c.relname,c.relkind,pg_get_userbyid(c.relowner) as owner,c.relacl::text,c.relrowsecurity,c.relforcerowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='" + schema + "')x),"
                                + "'functions',(select jsonb_agg(row_to_json(x) order by x.signature) from (select p.oid::regprocedure::text as signature,pg_get_userbyid(p.proowner) as owner,p.proacl::text,p.prosecdef,p.proconfig from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='" + schema + "')x),"
                                + "'schema',(select jsonb_build_object('owner',pg_get_userbyid(nspowner),'acl',nspacl::text) from pg_namespace where nspname='" + schema + "'))::text";
                        try (var rs = statement.executeQuery(catalog)) { assertThat(rs.next()).isTrue(); database.set("catalog", JSON.readTree(rs.getString(1))); }
                        if (!schema.equals("praxis_bulk_capacity")) {
                            try (var rs = statement.executeQuery("select coalesce(jsonb_agg(row_to_json(t) order by id),'[]'::jsonb)::text from occupancy_domain_witness t")) {
                                assertThat(rs.next()).isTrue(); database.set("domainWitness", JSON.readTree(rs.getString(1)));
                            }
                            try (var rs = statement.executeQuery("select count(*) from praxis_bulk.praxis_bulk_execution where status in('RUNNING','QUEUED') and deadline_at<=clock_timestamp()")) {
                                assertThat(rs.next()).isTrue(); database.put("observedExpiredDurableStates", rs.getLong(1));
                            }
                        }
                    } finally { c.rollback(); }
                }
            }
            return result;
        }
    }

    private static Path privateFile(Path path) throws Exception { return privateFile(path, 65536L); }
    private static Path privateFile(Path path, long limit) throws Exception {
        assertThat(path.isAbsolute() && path.toRealPath().equals(path) && Files.isRegularFile(path)).isTrue();
        assertThat(Files.size(path)).isBetween(1L, limit);
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(path.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------")); return path;
    }
    private static Path pinned(JsonNode binding, String name) throws Exception {
        long limit = switch (name) {
            case "candidateJar" -> 32L * 1024 * 1024;
            case "continuationBaseline", "continuationCurrentSnapshot", "migratorSource" -> 2L * 1024 * 1024;
            default -> 65536L; // Credential/configuration documents keep the original bound.
        };
        Path path = privateFile(Path.of(binding.path(name + "Path").asText()), limit);
        assertThat(hash(path)).isEqualTo(binding.path(name + "Sha256").asText()); return path;
    }
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static void save(Path path, JsonNode value) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }
    private static String required(String name) { String value = System.getenv(name); assertThat(value).isNotBlank(); return value; }
}
