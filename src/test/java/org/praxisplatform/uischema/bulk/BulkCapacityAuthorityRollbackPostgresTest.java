package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * C2-a private characterization: an old authority snapshot can forget a committed in-transit
 * right without changing UUID/epoch, while administered external quarantine prevents reuse.
 * Neither identity parity nor the cooperative file journal proves monotonic continuity.
 * ADMIN custody, whole-cluster clean cold-copy and fail-closed boot are the reference boundary;
 * this is not authority succession, production restore safety, HA or journal anti-rollback.
 * The six-minute fixture budget does not enlarge unit (5s), lock (3s) or installation (20s) budgets.
 */
class BulkCapacityAuthorityRollbackPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String ORIGIN = "capacity_local_1";
    private static final String RUNTIME = "bulk_runtime_test";
    private static final List<String> LOGINS = List.of("postgres", "occupancy_provisioner",
            "occupancy_allocator", "occupancy_reader", RUNTIME, "durable_runtime");
    private static final Class<?> AGENT = BulkCapacityProvisioningInterlockPostgresTest.ProvisioningAgent.class;
    private static final Class<?> RUNTIME_PROCESS = BulkCapacityExternalQuarantinePostgresTest.RuntimeProcess.class;

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void oldAuthoritySnapshotForgetsCommittedInTransitRightWithoutIdentityDrift() throws Exception {
        try (var proof = new Proof("authority-rollback-counterexample")) {
            proof.manifest.put("barriersUsed", false);
            Backup backup = prepareColdBackup(proof);
            proof.writeHba(proof.cloneHba, hba(false, true));
            // Allowing the restored allocator is deliberate and restricted to this counterexample.
            try (var origin = proof.builder(proof.originData, proof.originHba, proof.originLog, true).start()) {
                var source = opened(proof, origin, proof.originData, proof.originHba, proof.originLog, backup, true, true);
                var sourceAccess = authorityAccess(proof, origin, backup.identity());
                assertPending(sourceAccess.issuer(), source.authority(), backup);
                // No new request, binding, installation, proposal or domain mutation after backup.
                var token2 = allocateKnownCommitted(sourceAccess, source.authority(), backup);
                var sourceAfterCommit = authorityRows(source.authority());
                require(source.local().queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_installation where token_id=?",
                        Long.class, token2.tokenId()) == 0);
                unchanged(fullRows(source.local()), backup.rows());
                proof.phase("SOURCE_IN_TRANSIT_RIGHT_COMMITTED_AND_INDEPENDENTLY_READ_BEFORE_CLONE_START")
                        .put("knownInTransitRights", 1);
                try (var clone = proof.builder(proof.cloneData, proof.cloneHba, proof.cloneLog, true).start()) {
                    var copy = opened(proof, clone, proof.cloneData, proof.cloneHba, proof.cloneLog, backup, false, true);
                    require(source.master().pid() != copy.master().pid() && origin.getPort() != clone.getPort());
                    var copyAccess = authorityAccess(proof, clone, backup.identity());
                    require(copyAccess.reader().readIssuedToken(token2.tokenId()).isEmpty());
                    assertPending(copyAccess.issuer(), copy.authority(), backup);
                    var token3 = allocateKnownCommitted(copyAccess, copy.authority(), backup);
                    var copyAfterCommit = authorityRows(copy.authority());
                    require(sourceAccess.reader().readIssuedToken(token3.tokenId()).isEmpty());
                    var token1Source = sourceAccess.reader().readIssuedToken(backup.installed().tokenId()).orElseThrow();
                    var token1Copy = copyAccess.reader().readIssuedToken(backup.installed().tokenId()).orElseThrow();
                    require(token1Source.equals(backup.installed()) && token1Copy.equals(backup.installed()));
                    assertEffectiveRight(token2, backup); assertEffectiveRight(token3, backup);
                    require(token2.requestId().equals(token3.requestId()) && token2.payloadDigest().equals(token3.payloadDigest()));
                    // Deduplication is by actual right identity, not attempts, request counts or log events.
                    var union = new java.util.HashSet<JdbcBulkCapacityIssuer.IssuedToken>();
                    union.add(token1Source); union.add(token1Copy); union.add(token2); union.add(token3);
                    require(union.size() == 3 && union.stream().map(JdbcBulkCapacityIssuer.IssuedToken::tokenId).distinct().count() == 3);
                    require(union.stream().allMatch(token -> token.capacityClass() == JdbcBulkCapacityIssuer.CapacityClass.ACTIVE
                            && token.tenantId().equals(backup.expected().tenantId())));
                    for (var current : List.of(source, copy)) {
                        require(current.authority().queryForObject("select count(*) from praxis_bulk_capacity.capacity_token where tenant_id=? and capacity_class='ACTIVE'",
                                Long.class, backup.expected().tenantId()) == 2);
                        unchanged(fullRows(current.local()), backup.rows());
                        require(current.local().queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_installation where token_id in (?,?)",
                                Long.class, token2.tokenId(), token3.tokenId()) == 0);
                        require(current.local().queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot where token_id in (?,?)",
                                Long.class, token2.tokenId(), token3.tokenId()) == 0);
                        unchanged(identityRows(current.authority()), backup.identityRows());
                        unchanged(catalog(proof.source(current.postgres(), "postgres", "capacity_global")), backup.authorityCatalog());
                        unchanged(clusterRoles(current.admin()), backup.roles());
                        unchanged(databaseEnvelope(current.admin()), backup.databases());
                        unchanged(settings(current.admin()), backup.settings());
                    }
                    require(sourceAccess.issuer().allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).isEmpty());
                    require(copyAccess.issuer().allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).isEmpty());
                    unchanged(authorityRows(source.authority()), sourceAfterCommit);
                    unchanged(authorityRows(copy.authority()), copyAfterCommit);
                    proof.phase("KNOWN_COMMITTED_IN_TRANSIT_RIGHT_FORGOTTEN_BY_OLD_AUTHORITY_SNAPSHOT")
                            .put("installedActiveRights", 1).put("sourceInTransitRights", 1).put("copyInTransitRights", 1)
                            .put("deduplicatedEffectiveActiveRights", union.size()).put("tenantActiveLimit", 2)
                            .put("samePendingRequestAndPayload", true).put("authorityUuidEpochUnchanged", true)
                            .put("postBackupDomainMutations", 0);
                    proof.originStopOffset = Files.size(proof.originLog); proof.cloneStopOffset = Files.size(proof.cloneLog);
                }
            }
            assertFinalStops(proof);
            proof.complete = true;
        }
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void uncertainAuthorityContinuityKeepsRestoredIssuerAndWritersQuarantinedAcrossRestart() throws Exception {
        try (var proof = new Proof("authority-rollback-quarantine")) {
            Backup backup = prepareColdBackup(proof);
            // External deny exists before the restored postmaster's very first start.
            proof.writeHba(proof.cloneHba, hba(false, false));
            try (var origin = proof.builder(proof.originData, proof.originHba, proof.originLog, true).start()) {
                var source = opened(proof, origin, proof.originData, proof.originHba, proof.originLog, backup, true, true);
                var access = authorityAccess(proof, origin, backup.identity());
                var token2 = allocateKnownCommitted(access, source.authority(), backup);
                var knownAuthority = authorityRows(source.authority());
                require(source.local().queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_installation where token_id=?",
                        Long.class, token2.tokenId()) == 0);
                unchanged(fullRows(source.local()), backup.rows());
                proof.phase("SOURCE_IN_TRANSIT_RIGHT_COMMITTED_AND_INDEPENDENTLY_READ_BEFORE_CLONE_START")
                        .put("knownInTransitRights", 1);
                try (var clone = proof.builder(proof.cloneData, proof.cloneHba, proof.cloneLog, true).start();
                        var childCleanup = (AutoCloseable) proof::stopChildren) {
                    var copy = opened(proof, clone, proof.cloneData, proof.cloneHba, proof.cloneLog, backup, false, false);
                    require(source.master().pid() != copy.master().pid() && origin.getPort() != clone.getPort());
                    var restoredAuthority = authorityRows(copy.authority());
                    require(copy.authority().queryForObject("select count(*) from praxis_bulk_capacity.capacity_token where token_id=?",
                            Long.class, token2.tokenId()) == 0);
                    assertAllNonAdminDenied(proof, clone);
                    var runtimeConfig = runtimeConfiguration(proof, origin, clone, backup.expected(), backup.context(), backup.control());
                    var old = proof.launch(RUNTIME_PROCESS, runtimeConfig, "old-runtime");
                    var ready = proof.await(old, "ready", "ORIGINAL_JVM_CERTIFIED", Duration.ofSeconds(20));
                    require(ready.path("positiveReceiptReplay").asBoolean());
                    var held = session(source.admin(), ready.path("heldPid").asInt());
                    require(held.databaseOid() == databaseOid(source.admin()) && held.role().equals(RUNTIME)
                            && held.applicationName().equals("external_quarantine_runtime"));
                    unchanged(fullRows(source.local()), backup.rows());
                    var agentConfig = intentConfiguration(proof, backup.expected());
                    var intent = proof.launch(AGENT, agentConfig, "intent-only-admin");
                    proof.await(intent, "ready", "AGENT_CODE_SOURCE_VERIFIED", Duration.ofSeconds(20));
                    var forced = proof.await(intent, "result", "INTENT_RECORDED", Duration.ofSeconds(20)); proof.join(intent);
                    require(forced.path("sequence").asLong() == 1 && forced.path("administrativeActions").asInt() == 0);
                    byte[] forcedIntent = Files.readAllBytes(proof.directory.resolve("provisioning.json"));
                    assertIntent(proof, backup.expected());
                    proof.phase("DURABLE_CAS_INTENT_BEFORE_EXTERNAL_ORIGIN_DENY").put("sequence", 1).put("administrativeActions", 0);
                    proof.writeHba(proof.originHba, hba(false, false));
                    require(Boolean.TRUE.equals(source.admin().queryForObject("select pg_reload_conf()", Boolean.class)));
                    awaitAllNonAdminDenied(proof, origin, source.admin());
                    // A successful reload is not exclusion. This known session still writes for real.
                    require(session(source.admin(), held.pid()).equals(held));
                    proof.signal(old, "retire");
                    var probe = proof.await(old, "probe", "RETAINED_SESSION_REAL_UPDATE_UNCOMMITTED", Duration.ofSeconds(2));
                    require(probe.path("backendPid").asInt() == held.pid() && probe.path("writesInsideTransaction").asInt() == 1);
                    require(source.local().queryForObject("select writes from occupancy_domain_witness where id=2", Integer.class) == 0);
                    require(source.admin().queryForObject("select count(*) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=? and backend_xid::text::bigint=? and state='idle in transaction'",
                            Long.class, held.pid(), held.databaseOid(), held.role(), OffsetDateTime.ofInstant(held.start(), java.time.ZoneOffset.UTC),
                            probe.path("transactionId").asLong()) == 1);
                    proof.signal(old, "rollback");
                    proof.await(old, "rolled-back", "RETAINED_UPDATE_ROLLBACK_CONFIRMED", Duration.ofSeconds(3));
                    unchanged(fullRows(source.local()), backup.rows());
                    excludeExactSession(source.admin(), held);
                    require(source.admin().queryForObject("select count(*) from pg_stat_activity where datname=? and usename in (?,?)",
                            Long.class, ORIGIN, RUNTIME, "durable_runtime") == 0);
                    proof.phase("ADMIN_PHYSICAL_EXCLUSION_WITH_FRESH_FULL_TUPLE_READBACK")
                            .put("backendPid", held.pid()).put("databaseOid", held.databaseOid()).put("remainingRuntimeSessions", 0);
                    // Do not invoke RETIRE: D0 is denied, so its constructor path cannot establish fence.
                    require("ACTIVE".equals(source.local().queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)));
                    proof.signal(old, "terminated");
                    assertRuntimeDenied(proof.await(old, "result", "ORIGINAL_RUNTIME_BOTH_DATABASES_DENIED", Duration.ofSeconds(20))); proof.join(old);
                    runtimeConfig.setProperty("restart", "true");
                    var restarted = proof.launch(RUNTIME_PROCESS, runtimeConfig, "restarted-runtime");
                    proof.await(restarted, "ready", "RESTARTED_JVM_CERTIFIED", Duration.ofSeconds(20));
                    assertRuntimeDenied(proof.await(restarted, "result", "RESTARTED_RUNTIME_BOTH_DATABASES_DENIED", Duration.ofSeconds(20))); proof.join(restarted);
                    agentConfig.setProperty("mode", "BOOT"); agentConfig.setProperty("expectedSequence", "1");
                    var boot = proof.launch(AGENT, agentConfig, "restarted-admin");
                    proof.await(boot, "ready", "AGENT_CODE_SOURCE_VERIFIED", Duration.ofSeconds(20));
                    var bootResult = proof.await(boot, "result", "START_DENIED", Duration.ofSeconds(20)); proof.join(boot);
                    require(bootResult.path("sequence").asLong() == 1 && bootResult.path("state").asText().equals("INTENT")
                            && bootResult.path("administrativeActions").asInt() == 0);
                    require(Arrays.equals(forcedIntent, Files.readAllBytes(proof.directory.resolve("provisioning.json"))));
                    assertIntent(proof, backup.expected());
                    for (var current : List.of(source, copy)) {
                        assertAllNonAdminDenied(proof, current.postgres());
                        unchanged(fullRows(current.local()), backup.rows());
                        unchanged(identityRows(current.authority()), backup.identityRows());
                        unchanged(clusterRoles(current.admin()), backup.roles());
                        unchanged(databaseEnvelope(current.admin()), backup.databases());
                        unchanged(catalog(proof.source(current.postgres(), "postgres", ORIGIN)), backup.localCatalog());
                        unchanged(catalog(proof.source(current.postgres(), "postgres", "capacity_global")), backup.authorityCatalog());
                        require(current.admin().queryForObject("select count(*) from pg_stat_activity where datname in (?,?) and usename<>'postgres'",
                                Long.class, ORIGIN, "capacity_global") == 0);
                    }
                    unchanged(authorityRows(source.authority()), knownAuthority);
                    unchanged(authorityRows(copy.authority()), restoredAuthority);
                    require(source.authority().queryForObject("select count(*) from praxis_bulk_capacity.capacity_token where tenant_id=? and capacity_class='ACTIVE'",
                            Long.class, backup.expected().tenantId()) == 2);
                    require(source.local().queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_installation where token_id=?",
                            Long.class, token2.tokenId()) == 0);
                    proof.phase("INTENT_PERSISTS_WITHOUT_RETIREMENT_REISSUE_OR_CONFIRMED_LOCAL_FENCE")
                            .put("sequence", 1).put("installedActiveRights", 1).put("knownInTransitRights", 1)
                            .put("admissions", 0).put("callbacks", 0).put("nativeSqlState", "28000");
                    proof.originStopOffset = Files.size(proof.originLog); proof.cloneStopOffset = Files.size(proof.cloneLog);
                }
            }
            assertFinalStops(proof);
            proof.complete = true;
        }
    }

    private record Backup(BulkCapacityBinding expected, BulkFingerprintContext context,
            BulkExecutionControl control, BulkCapacityAuthorityMigrator.Identity identity,
            JdbcBulkCapacityIssuer.Request pending, JdbcBulkCapacityIssuer.Issue pendingIssue,
            JdbcBulkCapacityIssuer.IssuedToken installed, Map<String, List<String>> rows,
            Map<String, List<String>> authority, Map<String, List<String>> identityRows,
            Map<String, List<String>> localCatalog, Map<String, List<String>> authorityCatalog,
            Map<String, List<String>> roles, Map<String, List<String>> databases, Map<String, String> settings,
            String systemId) { }

    /** Exactly one ACTIVE right is installed; the second immutable demand already exists in D0. */
    private static Backup prepareColdBackup(Proof proof) throws Exception {
        proof.writeHba(proof.originHba, bootstrapHba());
        Backup backup; Postmaster stopped;
        try (var scope = new BulkCapacityOccupancyPostgresFixture.SharedScope(
                proof.builder(proof.originData, proof.originHba, proof.firstLog, false), proof.passwords,
                connection -> { proof.closeBootstrapTrust(connection); return null; })) {
            var local = scope.local(proof.caseId + "-source", 1);
            local.activate();
            UUID queue = local.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            UUID active = local.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            var enqueued = local.enqueue(local.persist(), "authority-rollback-key", queue);
            var claimed = local.kernel.claim(local.context, enqueued.executionId(), "authority-rollback-worker", active).orElseThrow();
            var physical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
            var unit = local.kernel.executeUnit(claimed.control(), 0, ignored -> BulkUnitAdmission.admit(), ignored -> {
                physical.set(local.writeDomain(ignored)); return BulkUnitMutationResult.confirmed();
            });
            require(unit.receiptPresent() && unit.execution().nextOrdinal() == 1
                    && unit.execution().status() == BulkDurableExecutionStatus.RUNNING && claimed.control().epoch() == 2);
            local.assertPhysicalCommit(claimed.executionId(), physical.get());
            var request = new JdbcBulkCapacityIssuer.Request(UUID.randomUUID(), local.expected.deploymentId(),
                    local.expected.tenantId(), local.expected.bindingId(), JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1);
            scope.issuer.requestCapacity(request);
            var pending = scope.issuer.findIssue(request.requestId()).orElseThrow();
            require(pending.issuedCount() == 0 && pending.tokenIds().isEmpty());
            var admin = sql(proof.source(scope.postgres, "postgres", "postgres"));
            var authority = sql(proof.source(scope.postgres, "postgres", "capacity_global"));
            require(authority.queryForObject("select state from praxis_bulk_capacity.capacity_request where request_id=?",
                    String.class, request.requestId()).equals("PENDING"));
            require(authority.queryForObject("select count(*) from praxis_bulk_capacity.capacity_token where tenant_id=? and capacity_class='ACTIVE'",
                    Long.class, local.expected.tenantId()) == 1);
            var installed = scope.reader.readIssuedToken(active).orElseThrow();
            backup = new Backup(local.expected, local.context, claimed.control(), scope.identity, request, pending,
                    installed, fullRows(local.observer), authorityRows(authority), identityRows(authority),
                    catalog(local.ownerSource), catalog(proof.source(scope.postgres, "postgres", "capacity_global")),
                    clusterRoles(admin), databaseEnvelope(admin), settings(admin),
                    admin.queryForObject("select system_identifier::text from pg_control_system()", String.class));
            require(backup.systemId() != null && !backup.systemId().isBlank());
            attestHba(admin, proof.originHba, hba(true, true));
            new BulkCapacityProvisioningInterlockPostgresTest.Journal(proof.directory, local.expected).initializeByFixtureOwner();
            require(admin.queryForObject("select count(*) from pg_stat_activity where backend_type='client backend' and pid<>pg_backend_pid()", Long.class) == 0);
            stopped = postmaster(scope.postgres, proof.originData, admin);
            proof.firstStopOffset = Files.size(proof.firstLog);
            local.assertionsComplete();
            proof.phase("ORIGINAL_RECEIPT_AND_PENDING_DEMAND_KNOWN_BEFORE_COLD_BACKUP")
                    .put("backendPid", physical.get().backendPid()).put("transactionId", physical.get().transactionId())
                    .put("installedActiveRights", 1).put("pendingActiveDemands", 1).put("pendingIssuedCount", 0);
        }
        String cleanStop = assertStopped(stopped, proof.originData, proof.firstLog, proof.firstStopOffset);
        proof.phase("ORIGINAL_NATIVE_CLEAN_SHUTDOWN_AND_EXACT_POSTMASTER_EXIT").put("postmasterPid", stopped.pid())
                .put("stopExitStatusExposed", false).put("nativeRecord", cleanStop);
        coldCopy(proof.originData, proof.cloneData);
        proof.phase("FULL_PGDATA_BACKUP_WAL_XACT_BYTES_PERMISSIONS_COPIED");
        return backup;
    }

    private record Opened(EmbeddedPostgres postgres, JdbcTemplate admin, JdbcTemplate local,
            JdbcTemplate authority, Postmaster master) { }

    /** No migrate, reattestation, binding rewrite or credential reset on either restored cluster. */
    private static Opened opened(Proof proof, EmbeddedPostgres pg, Path data, Path hbaPath, Path log,
            Backup backup, boolean runtimeAllowed, boolean authorityAllowed) throws Exception {
        var admin = sql(proof.source(pg, "postgres", "postgres"));
        var local = sql(proof.source(pg, "postgres", ORIGIN));
        var authority = sql(proof.source(pg, "postgres", "capacity_global"));
        var master = postmaster(pg, data, admin); proof.restartedMasters.add(master);
        var startupRecords = assertCleanStartup(pg, log, master);
        proof.phase(data.equals(proof.originData) ? "ORIGIN_NATIVE_CLEAN_STARTUP" : "CLONE_NATIVE_CLEAN_STARTUP")
                .put("postmasterPid", master.pid()).put("launcherExitCode", 0)
                .set("nativeRecords", JSON.valueToTree(startupRecords));
        attestHba(admin, hbaPath, hba(runtimeAllowed, authorityAllowed));
        require(backup.systemId().equals(admin.queryForObject("select system_identifier::text from pg_control_system()", String.class)));
        unchanged(fullRows(local), backup.rows()); unchanged(authorityRows(authority), backup.authority());
        unchanged(clusterRoles(admin), backup.roles()); unchanged(databaseEnvelope(admin), backup.databases());
        unchanged(settings(admin), backup.settings());
        unchanged(catalog(proof.source(pg, "postgres", ORIGIN)), backup.localCatalog());
        unchanged(catalog(proof.source(pg, "postgres", "capacity_global")), backup.authorityCatalog());
        BulkCapacityAuthorityMigrator.validate(proof.source(pg, "postgres", "capacity_global"), backup.identity(), authorityRoles());
        BulkExecutionMigrator.validate(proof.source(pg, "postgres", ORIGIN), BulkPostgresTestSupport.testRoleConfiguration());
        try (var connection = proof.source(pg, "postgres", ORIGIN).getConnection()) {
            var runtimeSource = proof.source(pg, RUNTIME, ORIGIN);
            var infrastructure = new BulkExecutionInfrastructure(runtimeSource, new DataSourceTransactionManager(runtimeSource),
                    backup.context().namespaceId(), backup.expected().deploymentId(), BulkPostgresTestSupport.testRoleConfiguration());
            JdbcBulkCapacityOccupancy.requireBinding(connection, infrastructure, backup.expected(), true);
        }
        proof.phase(authorityAllowed ? "RESTORED_CLUSTER_CATALOG_AND_FULL_BINDING_EXACT" : "RESTORED_AUTHORITY_FIRST_START_UNDER_EXTERNAL_DENY")
                .put("postmasterPid", master.pid()).put("databaseOid", databaseOid(admin))
                .put("copiedSystemIdentifierEqual", true).put("launcherExitCode", 0);
        return new Opened(pg, admin, local, authority, master);
    }

    private record AuthorityAccess(JdbcBulkCapacityIssuer issuer, JdbcBulkCapacityIssuer.CapacityReader reader) { }
    private static BulkCapacityAuthorityMigrator.RoleConfiguration authorityRoles() {
        return new BulkCapacityAuthorityMigrator.RoleConfiguration("postgres", "occupancy_provisioner", "occupancy_allocator", "occupancy_reader");
    }
    private static AuthorityAccess authorityAccess(Proof proof, EmbeddedPostgres pg, BulkCapacityAuthorityMigrator.Identity identity) {
        var allocatorSource = proof.source(pg, "occupancy_allocator", "capacity_global");
        var readerSource = proof.source(pg, "occupancy_reader", "capacity_global");
        var allocator = new BulkCapacityAuthorityInfrastructure(allocatorSource, new DataSourceTransactionManager(allocatorSource),
                identity, BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR, authorityRoles());
        var reader = new BulkCapacityAuthorityInfrastructure(readerSource, new DataSourceTransactionManager(readerSource),
                identity, BulkCapacityAuthorityInfrastructure.Access.READER, authorityRoles());
        return new AuthorityAccess(new JdbcBulkCapacityIssuer(allocator, reader), new JdbcBulkCapacityIssuer.CapacityReader(reader));
    }

    private static void assertPending(JdbcBulkCapacityIssuer issuer, JdbcTemplate observer, Backup backup) {
        require(issuer.findIssue(backup.pending().requestId()).orElseThrow().equals(backup.pendingIssue()));
        require("PENDING".equals(observer.queryForObject("select state from praxis_bulk_capacity.capacity_request where request_id=?",
                String.class, backup.pending().requestId())));
    }

    /** allocateNext returns after commit; protected reader and independent ADMIN readback agree. */
    private static JdbcBulkCapacityIssuer.IssuedToken allocateKnownCommitted(AuthorityAccess access, JdbcTemplate observer, Backup backup) {
        var token = access.issuer().allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow();
        require(token.requestId().equals(backup.pending().requestId()));
        var known = access.reader().readIssuedToken(token.tokenId()).orElseThrow();
        var issue = access.issuer().findIssue(backup.pending().requestId()).orElseThrow();
        require(issue.issuedCount() == 1 && issue.requestedCount() == 1 && issue.tokenIds().equals(List.of(token.tokenId()))
                && issue.payloadDigest().equals(backup.pendingIssue().payloadDigest()));
        require("FULFILLED".equals(observer.queryForObject("select state from praxis_bulk_capacity.capacity_request where request_id=?",
                String.class, token.requestId())));
        require(observer.queryForObject("select count(*) from praxis_bulk_capacity.capacity_token where token_id=? and request_id=? and capacity_class='ACTIVE'",
                Long.class, known.tokenId(), backup.pending().requestId()) == 1);
        assertEffectiveRight(known, backup);
        return known;
    }

    private static void assertEffectiveRight(JdbcBulkCapacityIssuer.IssuedToken token, Backup backup) {
        var e = backup.expected();
        require(token.deploymentId().equals(e.deploymentId()) && token.tenantId().equals(e.tenantId())
                && token.environment().equals(e.environment()) && token.bindingId().equals(e.bindingId())
                && token.bindingGeneration() == e.generation() && token.databaseId().equals(e.databaseId())
                && token.attestationId().equals(e.attestationId()) && token.authorityId().equals(e.authorityId())
                && token.authorityEpoch() == e.authorityEpoch() && token.capacityClass() == JdbcBulkCapacityIssuer.CapacityClass.ACTIVE
                && token.payloadDigest().equals(backup.pendingIssue().payloadDigest()) && token.ordinal() == 1);
    }

    private static Map<String, List<String>> identityRows(JdbcTemplate observer) {
        return Map.of("identity", observer.queryForList("select to_jsonb(t)::text from praxis_bulk_capacity.authority_identity t order by 1", String.class),
                "attestation", observer.queryForList("select to_jsonb(t)::text from praxis_bulk_capacity.binding_attestation t order by 1", String.class),
                "binding", observer.queryForList("select to_jsonb(t)::text from praxis_bulk_capacity.capacity_binding t order by 1", String.class));
    }

    private static Properties intentConfiguration(Proof proof, BulkCapacityBinding expected) throws Exception {
        var p = new Properties(); p.setProperty("bindingJson", JSON.writeValueAsString(binding(expected)));
        p.setProperty("journalDirectory", proof.directory.toString());
        p.setProperty("mode", "CAS_INTENT"); p.setProperty("barrier", "NONE"); p.setProperty("expectedSequence", "0");
        return p;
    }

    private static void assertIntent(Proof proof, BulkCapacityBinding expected) throws Exception {
        var envelope = JSON.readTree(Files.readAllBytes(proof.directory.resolve("provisioning.json")));
        var payload = envelope.path("payload");
        require(payload.path("state").asText().equals("INTENT") && payload.path("sequence").asLong() == 1);
        require(payload.path("binding").equals(binding(expected)) && payload.path("format").asInt() == 1);
        require(envelope.path("digest").asText().equals(digest(JSON.writeValueAsBytes(payload))));
    }

    private static void assertRuntimeDenied(JsonNode result) {
        require(result.path("nativeSqlState").asText().equals("28000")
                && result.path("admissions").asInt(-1) == 0 && result.path("callbacks").asInt(-1) == 0);
    }

    private static void assertAllNonAdminDenied(Proof proof, EmbeddedPostgres pg) throws SQLException {
        for (String role : LOGINS) if (!role.equals("postgres")) {
            denied(proof.source(pg, role, ORIGIN)); denied(proof.source(pg, role, "capacity_global"));
        }
    }

    private static void awaitAllNonAdminDenied(Proof proof, EmbeddedPostgres pg, JdbcTemplate admin) throws Exception {
        long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (true) {
            boolean allDenied = true;
            for (String role : LOGINS) if (!role.equals("postgres")) {
                for (String database : List.of(ORIGIN, "capacity_global")) {
                    try (var ignored = proof.source(pg, role, database).getConnection()) { allDenied = false; }
                    catch (SQLException rejected) { require("28000".equals(rejected.getSQLState())); }
                }
            }
            if (allDenied) break;
            require(System.nanoTime() < stop); Thread.sleep(10);
        }
        attestHba(admin, proof.originHba, hba(false, false));
        proof.phase("ORIGIN_AND_AUTHORITY_NON_ADMIN_CONNECTIONS_NATIVE_DENIED").put("nativeSqlState", "28000").put("coveredLoginCount", 5);
    }

    private static void excludeExactSession(JdbcTemplate admin, Session held) throws Exception {
        require(session(admin, held.pid()).equals(held));
        require(Boolean.TRUE.equals(admin.queryForObject("select pg_terminate_backend(pid) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=? and application_name=?",
                Boolean.class, held.pid(), held.databaseOid(), held.role(), OffsetDateTime.ofInstant(held.start(), java.time.ZoneOffset.UTC), held.applicationName())));
        long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (admin.queryForObject("select count(*) from pg_stat_activity where pid=? and datid=? and usename=? and backend_start=? and application_name=?",
                Long.class, held.pid(), held.databaseOid(), held.role(), OffsetDateTime.ofInstant(held.start(), java.time.ZoneOffset.UTC), held.applicationName()) != 0) {
            require(System.nanoTime() < stop); Thread.sleep(10);
        }
    }

    private static void assertFinalStops(Proof proof) throws Exception {
        require(proof.restartedMasters.size() == 2);
        String originStop = assertStopped(proof.restartedMasters.get(0), proof.originData, proof.originLog, proof.originStopOffset);
        proof.phase("ORIGIN_FINAL_NATIVE_CLEAN_STOP_AND_POSTMASTER_EXIT")
                .put("postmasterPid", proof.restartedMasters.get(0).pid()).put("nativeRecord", originStop);
        String cloneStop = assertStopped(proof.restartedMasters.get(1), proof.cloneData, proof.cloneLog, proof.cloneStopOffset);
        proof.phase("CLONE_FINAL_NATIVE_CLEAN_STOP_AND_POSTMASTER_EXIT")
                .put("postmasterPid", proof.restartedMasters.get(1).pid()).put("nativeRecord", cloneStop);
        proof.clustersClosed = true;
        proof.phase("BOTH_OWNED_POSTMASTERS_NATIVE_CLEAN_STOPPED_AND_ABSENT");
    }

    private record Session(int pid, long databaseOid, String role, Instant start, String applicationName) { }
    private record Postmaster(long pid, Instant start, String command, Instant postgresStart) { }

    private static Session session(JdbcTemplate admin, int pid) {
        var rows = admin.query("select pid,datid::bigint,usename,backend_start,application_name from pg_stat_activity where pid=?",
                (r, n) -> new Session(r.getInt(1), r.getLong(2), r.getString(3), r.getObject(4, OffsetDateTime.class).toInstant(), r.getString(5)), pid);
        require(rows.size() == 1); return rows.getFirst();
    }

    private static long databaseOid(JdbcTemplate admin) {
        return admin.queryForObject("select oid::bigint from pg_database where datname=?", Long.class, ORIGIN);
    }

    /** The launcher is pg_ctl start, whereas postmaster.pid identifies the actual PostgreSQL process. */
    private static Postmaster postmaster(EmbeddedPostgres postgres, Path data, JdbcTemplate admin) throws Exception {
        var lines = Files.readAllLines(data.resolve("postmaster.pid")); require(lines.size() >= 3);
        long pid = Long.parseLong(lines.get(0)); require(pid > 0);
        require(Path.of(lines.get(1)).toRealPath().equals(data.toRealPath()));
        require(Path.of(admin.queryForObject("show data_directory", String.class)).toRealPath().equals(data.toRealPath()));
        require(Path.of(admin.queryForObject("show config_file", String.class)).toRealPath().equals(data.resolve("postgresql.conf").toRealPath()));
        require(Path.of(admin.queryForObject("show ident_file", String.class)).toRealPath().equals(data.resolve("pg_ident.conf").toRealPath()));
        require(Integer.toString(postgres.getPort()).equals(admin.queryForObject("show port", String.class)));
        var handle = ProcessHandle.of(pid).orElseThrow(); require(handle.isAlive());
        Instant start = handle.info().startInstant().orElseThrow();
        String command = handle.info().command().orElseThrow();
        require(Path.of(command).getFileName().toString().equals("postgres"));
        require(Arrays.asList(handle.info().arguments().orElseThrow()).contains(data.toString()));
        Instant postgresStart = admin.queryForObject("select pg_postmaster_start_time()", OffsetDateTime.class).toInstant();
        require(Math.abs(Duration.between(start, postgresStart).toMillis()) < 2_000);
        require(Math.abs(Instant.ofEpochSecond(Long.parseLong(lines.get(2))).toEpochMilli() - postgresStart.toEpochMilli()) < 2_000);
        require(postgres.getProcess().pid() != pid);
        return new Postmaster(pid, start, command, postgresStart);
    }

    private static String assertStopped(Postmaster prior, Path data, Path log, long offset) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            var current = ProcessHandle.of(prior.pid());
            boolean same = false;
            if (current.isPresent() && current.get().isAlive()) {
                require(current.get().info().startInstant().isPresent());
                same = current.get().info().startInstant().orElseThrow().equals(prior.start());
            }
            if (!same && !Files.exists(data.resolve("postmaster.pid"))) break;
            require(System.nanoTime() < end); Thread.sleep(10);
        }
        byte[] bytes = Files.readAllBytes(log); require(offset >= 0 && bytes.length > offset);
        String stop = new String(Arrays.copyOfRange(bytes, Math.toIntExact(offset), bytes.length), StandardCharsets.UTF_8);
        var records = stop.lines().filter(line -> line.contains("[" + prior.pid() + "]") && line.contains("database system is shut down")).toList();
        require(records.size() == 1);
        require(!stop.contains("PANIC:") && !stop.contains("could not shut down"));
        return records.getFirst();
    }

    private static List<String> assertCleanStartup(EmbeddedPostgres postgres, Path log, Postmaster actual) throws Exception {
        require(postgres.getProcess().waitFor(20, TimeUnit.SECONDS) && postgres.getProcess().exitValue() == 0);
        String startup = Files.readString(log);
        require(startup.contains("database system was shut down") && startup.contains("database system is ready to accept connections"));
        require(startup.lines().anyMatch(line -> line.contains("[" + actual.pid() + "]") && line.contains("database system is ready to accept connections")));
        require(!startup.contains("was interrupted") && !startup.contains("automatic recovery")
                && !startup.contains("redo starts") && !startup.contains("PANIC:"));
        // Only these fixed native status records are exported, never the private log as a whole.
        var records = startup.lines().filter(line -> line.contains("database system was shut down")
                || (line.contains("[" + actual.pid() + "]") && line.contains("database system is ready to accept connections"))).toList();
        require(records.size() == 2); return records;
    }

    /** No symlink, tablespace mount, omitted WAL directory or logical database substitution is allowed. */
    private static void coldCopy(Path from, Path to) throws Exception {
        require(!Files.exists(to) && Files.isDirectory(from) && !Files.exists(from.resolve("postmaster.pid")));
        require(Files.isDirectory(from.resolve("pg_wal")) && Files.isDirectory(from.resolve("pg_xact")));
        try (var walk = Files.walk(from)) {
            for (Path path : walk.sorted().toList()) {
                require(!Files.isSymbolicLink(path));
                Path target = to.resolve(from.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(target, PosixFilePermissions.asFileAttribute(Files.getPosixFilePermissions(path)));
                    require(Files.getPosixFilePermissions(path).equals(Files.getPosixFilePermissions(target)));
                } else {
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                    require(Files.mismatch(path, target) == -1);
                    require(Files.getPosixFilePermissions(path).equals(Files.getPosixFilePermissions(target)));
                }
            }
        }
        try (var left = Files.walk(from); var right = Files.walk(to)) {
            require(left.map(from::relativize).sorted().toList().equals(right.map(to::relativize).sorted().toList()));
        }
    }

    private static String bootstrapHba() {
        return "host all postgres 127.0.0.1/32 trust\nhost all all 127.0.0.1/32 reject\nhost all all ::1/128 reject\nlocal all all reject\n";
    }
    private static String hba(boolean runtime, boolean authority) {
        return "host all postgres 127.0.0.1/32 scram-sha-256\n"
                + (authority ? "host capacity_global occupancy_provisioner,occupancy_allocator,occupancy_reader 127.0.0.1/32 scram-sha-256\n" : "")
                + (runtime ? "host capacity_local_1 bulk_runtime_test 127.0.0.1/32 scram-sha-256\n" : "")
                + "host all all 127.0.0.1/32 reject\nhost all all ::1/128 reject\nlocal all all reject\n";
    }
    private static void attestHba(JdbcTemplate admin, Path path, String expected) throws Exception {
        require(Files.readString(path).equals(expected));
        require(Path.of(admin.queryForObject("show hba_file", String.class)).toRealPath().equals(path.toRealPath()));
        require(!path.toRealPath().startsWith(Path.of(admin.queryForObject("show data_directory", String.class)).toRealPath()));
        require("127.0.0.1".equals(admin.queryForObject("show listen_addresses", String.class)));
        require("".equals(admin.queryForObject("show unix_socket_directories", String.class)));
        require(admin.queryForObject("select count(*) from pg_hba_file_rules where error is not null", Long.class) == 0);
        var methods = admin.queryForList("select auth_method from pg_hba_file_rules order by line_number", String.class);
        var exact = expected.lines().map(line -> line.substring(line.lastIndexOf(' ') + 1)).toList();
        require(methods.equals(exact));
        require(admin.queryForList("select rolname from pg_roles where rolcanlogin order by 1", String.class).equals(LOGINS.stream().sorted().toList()));
    }
    private static void denied(DataSource source) throws SQLException {
        try (var ignored = source.getConnection()) { throw new AssertionError("Private quarantine admitted a non-administrative connection"); }
        catch (SQLException denied) { require("28000".equals(denied.getSQLState())); }
    }
    private static JdbcTemplate sql(DataSource source) { var sql = new JdbcTemplate(source); sql.setQueryTimeout(3); return sql; }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Private authority rollback proof invariant failed"); }
    private static void unchanged(Object actual, Object expected) { require(actual.equals(expected)); }

    /** Entire database envelopes/settings and private authentication rows are never printed. */
    private static Map<String, List<String>> databaseEnvelope(JdbcTemplate admin) {
        return Map.of("databases", admin.queryForList("select to_jsonb(d)::text from pg_database d order by 1", String.class),
                "settings", admin.queryForList("select to_jsonb(s)::text from pg_db_role_setting s order by 1", String.class),
                "tablespaces", admin.queryForList("select to_jsonb(t)::text from pg_tablespace t order by 1", String.class));
    }

    /** Only the five launch-location values differ; each is independently attested to its own cluster. */
    private static Map<String, String> settings(JdbcTemplate admin) {
        var settings = new LinkedHashMap<String, String>();
        admin.query("select name,setting from pg_settings order by name", row -> {
            String name = row.getString(1);
            if (!Set.of("port", "data_directory", "hba_file", "config_file", "ident_file").contains(name))
                settings.put(name, row.getString(2));
        });
        return settings;
    }

    private static Map<String, List<String>> authorityRows(JdbcTemplate observer) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : observer.queryForList("select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk_capacity' and c.relkind='r' order by 1", String.class)) {
            require(table.matches("[a-z_][a-z0-9_]*"));
            result.put(table, observer.queryForList("select to_jsonb(t)::text from (select *,xmin::text as row_xmin from praxis_bulk_capacity."
                    + table + ") t order by 1", String.class));
        }
        require(!result.isEmpty()); return result;
    }

    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String classHash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            require(input != null); return digest(input.readAllBytes());
        }
    }
    private static String codeSource(Class<?> type) { return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm(); }
    private static ObjectNode binding(BulkCapacityBinding e) {
        return JSON.createObjectNode().put("deployment", e.deploymentId()).put("tenant", e.tenantId()).put("environment", e.environment())
                .put("binding", e.bindingId()).put("generation", e.generation()).put("databaseId", e.databaseId().toString())
                .put("attestationId", e.attestationId().toString()).put("authorityId", e.authorityId().toString()).put("authorityEpoch", e.authorityEpoch());
    }

    private static Properties runtimeConfiguration(Proof proof, EmbeddedPostgres origin, EmbeddedPostgres clone,
            BulkCapacityBinding e, BulkFingerprintContext c, BulkExecutionControl control) throws Exception {
        var p = new Properties(); p.setProperty("originUrl", origin.getJdbcUrl(RUNTIME, ORIGIN));
        p.setProperty("copyUrl", clone.getJdbcUrl(RUNTIME, ORIGIN)); p.setProperty("runtimePassword", proof.passwords.get(RUNTIME));
        p.setProperty("runtimeRoles", String.join(",", BulkPostgresTestSupport.testRoleConfiguration().runtimeGranteeRoles()));
        p.setProperty("ownerRole", "postgres");
        p.setProperty("deployment", e.deploymentId()); p.setProperty("tenant", e.tenantId()); p.setProperty("environment", e.environment());
        p.setProperty("binding", e.bindingId()); p.setProperty("generation", Long.toString(e.generation()));
        p.setProperty("databaseId", e.databaseId().toString()); p.setProperty("attestationId", e.attestationId().toString());
        p.setProperty("authorityId", e.authorityId().toString()); p.setProperty("authorityEpoch", Long.toString(e.authorityEpoch()));
        p.setProperty("async.namespace", c.namespaceId()); p.setProperty("async.subject", c.subjectId()); p.setProperty("async.resource", c.resourceKey());
        p.setProperty("async.revision", c.schemaRevision()); p.setProperty("async.group", c.operationRef().group());
        p.setProperty("async.operation", c.operationRef().operationId()); p.setProperty("async.path", c.operationRef().path()); p.setProperty("async.method", c.operationRef().method());
        p.setProperty("async.executionId", control.executionId().toString()); p.setProperty("async.ownerId", control.ownerId()); p.setProperty("async.epoch", Long.toString(control.epoch()));
        p.setProperty("restart", "false"); return p;
    }

    private record Child(Path directory, Process process, Class<?> type) { }

    /** All ADMIN custody, native logs and handoffs are private and removed after owned cleanup. */
    private static final class Proof implements AutoCloseable {
        final Path directory = Files.createTempDirectory("praxis-authority-rollback-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path originData = directory.resolve("origin-pgdata");
        final Path cloneData = directory.resolve("clone-pgdata");
        final Path originHba = privateFile("origin-hba.conf");
        final Path cloneHba = privateFile("clone-hba.conf");
        final Path firstLog = privateFile("initial-native.log");
        final Path originLog = privateFile("origin-native.log");
        final Path cloneLog = privateFile("clone-native.log");
        final List<Child> children = new ArrayList<>();
        final List<Postmaster> restartedMasters = new ArrayList<>();
        final Map<String, String> passwords;
        final String caseId;
        final ObjectNode manifest = JSON.createObjectNode()
                .put("harnessPid", ProcessHandle.current().pid()).put("scope", "AUTHORITY_ROLLBACK_CHARACTERIZATION_AND_EXTERNAL_QUARANTINE_ONLY")
                .put("barriersUsed", true);
        long firstStopOffset;
        long originStopOffset;
        long cloneStopOffset;
        boolean complete;
        boolean clustersClosed;
        boolean childrenStopped;

        Proof(String caseId) throws Exception {
            this.caseId = caseId;
            manifest.put("caseId", caseId);
            var map = new LinkedHashMap<String, String>(); for (String role : LOGINS) map.put(role, UUID.randomUUID().toString());
            passwords = Map.copyOf(map); privateFile("provisioning.lock"); manifest.putArray("events");
        }
        Path privateFile(String name) throws Exception {
            return Files.createFile(directory.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toAbsolutePath();
        }
        ObjectNode phase(String phase) { require(phase.matches("[A-Z][A-Z0-9_]{0,100}")); return manifest.withArray("events").addObject().put("phase", phase); }
        EmbeddedPostgres.Builder builder(Path data, Path hba, Path log, boolean restored) {
            var builder = EmbeddedPostgres.builder().setDataDirectory(data).setCleanDataDirectory(false).setRegisterShutdownHook(false)
                    .setPGStartupWait(Duration.ofSeconds(30)).setServerConfig("hba_file", hba.toString())
                    .setServerConfig("listen_addresses", "127.0.0.1").setServerConfig("unix_socket_directories", "")
                    .setServerConfig("lc_messages", "C").setServerConfig("log_statement", "none")
                    .setServerConfig("log_line_prefix", "%m[%p]")
                    .setOutputRedirector(ProcessBuilder.Redirect.appendTo(log.toFile())).setErrorRedirector(ProcessBuilder.Redirect.appendTo(log.toFile()));
            if (restored) builder.setConnectConfig("password", passwords.get("postgres"));
            return builder;
        }
        DriverManagerDataSource source(EmbeddedPostgres postgres, String role, String db) {
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(role, db), role, passwords.get(role));
            var options = new Properties(); options.setProperty("connectTimeout", "1"); options.setProperty("socketTimeout", "3");
            options.setProperty("ApplicationName", "private_authority_rollback_admin"); source.setConnectionProperties(options); return source;
        }
        void closeBootstrapTrust(Connection connection) throws SQLException {
            require(connection.getAutoCommit()); char[] password = passwords.get("postgres").toCharArray();
            try { connection.unwrap(org.postgresql.PGConnection.class).alterUserPassword("postgres", password, "scram-sha-256"); }
            finally { Arrays.fill(password, '\0'); }
            try { writeHba(originHba, hba(true, true)); }
            catch (Exception failure) { throw new IllegalStateException("Private authenticated bootstrap replacement failed"); }
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (var row = statement.executeQuery("select pg_reload_conf()")) { require(row.next() && row.getBoolean(1) && !row.next()); }
            }
            var wrong = new DriverManagerDataSource(connection.getMetaData().getURL(), "postgres", passwords.get(RUNTIME));
            var options = new Properties(); options.setProperty("connectTimeout", "1"); wrong.setConnectionProperties(options);
            long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (true) {
                try (var ignored = wrong.getConnection()) { require(System.nanoTime() < stop); }
                catch (SQLException rejected) { require("28P01".equals(rejected.getSQLState())); break; }
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Private authenticated bootstrap interrupted"); }
            }
            phase("BOOTSTRAP_TRUST_CLOSED_BEFORE_AUTHORITY_DOMAIN_OR_RUNTIME_SETUP");
        }
        void writeHba(Path target, String value) throws Exception {
            Path staged = Files.createTempFile(directory, "hba-stage-", ".conf", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
                }
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
                require(Arrays.equals(Files.readAllBytes(target), bytes));
            } finally { Files.deleteIfExists(staged); }
        }
        Child launch(Class<?> type, Properties base, String name) throws Exception {
            var p = new Properties(); p.putAll(base);
            p.setProperty("processHash", classHash(type)); p.setProperty("processCodeSource", codeSource(type));
            p.setProperty("kernelHash", classHash(JdbcBulkDurableExecution.class)); p.setProperty("kernelCodeSource", codeSource(JdbcBulkDurableExecution.class));
            Path output = Files.createDirectory(directory.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path config = Files.createFile(output.resolve("private-configuration.properties"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var sink = Files.newOutputStream(config)) { p.store(sink, "Private fixed test process configuration"); }
            Path log = Files.createFile(output.resolve("private-output.log"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var process = new ProcessBuilder(java, "-cp", classpath, type.getName(), config.toString(), output.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            var child = new Child(output, process, type); children.add(child);
            require(children.stream().map(value -> value.process().pid()).distinct().count() == children.size());
            phase(type == AGENT ? "ADMIN_AGENT_JVM_LAUNCHED" : "RUNTIME_JVM_LAUNCHED").put("osPid", process.pid()); return child;
        }
        JsonNode await(Child child, String name, String phase, Duration budget) throws Exception {
            long stop = System.nanoTime() + budget.toNanos(); Path path = child.directory().resolve(name + ".json");
            while (!Files.exists(path)) {
                Path result = child.directory().resolve("result.json");
                if (Files.exists(result)) require(!JSON.readTree(Files.readAllBytes(result)).path("phase").asText().equals("FAILED"));
                require(child.process().isAlive() && System.nanoTime() < stop); Thread.sleep(10);
            }
            var event = JSON.readTree(Files.readAllBytes(path));
            require(event.path("osPid").asLong() == child.process().pid() && event.path("phase").asText().equals(phase));
            if (name.equals("ready")) {
                require(event.path("kernelClassSha256").asText().equals(classHash(JdbcBulkDurableExecution.class)));
                require(event.path("processClassSha256").asText().equals(classHash(child.type())) && event.path("codeSourceMatchesParent").asBoolean());
            }
            manifest.withArray("events").add(event.deepCopy()); return event;
        }
        void signal(Child child, String name) throws Exception {
            require(Set.of("retire", "rollback", "terminated", "release", "abort").contains(name));
            Files.writeString(child.directory().resolve(name), "go");
        }
        void join(Child child) throws Exception {
            require(child.process().waitFor(20, TimeUnit.SECONDS) && child.process().exitValue() == 0);
            phase("OWNED_JVM_EXIT_CONFIRMED").put("osPid", child.process().pid()).put("exitCode", 0);
        }
        void stopChildren() throws Exception {
            if (childrenStopped) return; childrenStopped = true; Throwable failure = null;
            // Abort and release every barrier before any wait, even when a protected assertion fails.
            for (var child : children) if (child.process().isAlive()) {
                for (String name : List.of("abort", "rollback", "release", "terminated", "retire")) {
                    try { signal(child, name); } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
                }
            }
            for (var child : children) {
                try {
                    if (!child.process().waitFor(3, TimeUnit.SECONDS)) child.process().destroyForcibly();
                    require(child.process().waitFor(3, TimeUnit.SECONDS));
                    phase("OWNED_JVM_CLEANUP").put("osPid", child.process().pid()).put("exitCode", child.process().exitValue());
                } catch (Exception | Error cleanup) {
                    child.process().destroyForcibly(); failure = append(failure, cleanup);
                    try { require(child.process().waitFor(3, TimeUnit.SECONDS)); }
                    catch (Exception | Error join) { failure = append(failure, join); }
                }
            }
            rethrow(failure);
        }

        /** Fixed classification codes only: native text, paths and protected values stay private. */
        void incompleteNativeDiagnostics() throws Exception {
            var logs = Map.of("INITIAL_START", firstLog, "ORIGIN_RESTART", originLog, "CLONE_START", cloneLog);
            for (String scope : logs.keySet().stream().sorted().toList()) {
                Path log = logs.get(scope);
                require(Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS));
                require(Files.getPosixFilePermissions(log).equals(PosixFilePermissions.fromString("rw-------")));
                var counts = new LinkedHashMap<String, Integer>();
                int lines = 0;
                try (var records = Files.lines(log, StandardCharsets.UTF_8)) {
                    for (var iterator = records.iterator(); iterator.hasNext();) {
                        String record = iterator.next(); lines++;
                        String code = null;
                        if (record.trim().equals("postgres: invalid argument: \"[%p]\"")) code = "INVALID_LOG_PREFIX_ARGUMENT";
                        else if (record.contains("unrecognized configuration parameter")) code = "UNRECOGNIZED_CONFIGURATION_PARAMETER";
                        else if (record.contains("invalid value for parameter")) code = "INVALID_CONFIGURATION_VALUE";
                        else if (record.contains("could not start server")) code = "PG_CTL_COULD_NOT_START_SERVER";
                        else if (record.contains("PANIC:")) code = "NATIVE_PANIC";
                        else if (record.contains("FATAL:")) code = "NATIVE_FATAL";
                        if (code != null) counts.merge(code, 1, Integer::sum);
                    }
                }
                var diagnostic = phase("INCOMPLETE_PRIVATE_NATIVE_DIAGNOSTIC").put("logicalScope", scope)
                        .put("nativeLogBytes", Files.size(log)).put("nativeRecordCount", lines)
                        .put("rawPreservedPrivately", true).put("rawPermissions0600", true);
                var codes = diagnostic.putArray("classifications");
                counts.forEach((code, count) -> codes.addObject().put("code", code).put("count", count));
                if (counts.isEmpty()) codes.addObject().put("code", lines == 0 ? "NO_NATIVE_OUTPUT" : "UNCLASSIFIED_NATIVE_OUTPUT");
            }
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            try { stopChildren(); } catch (Exception | Error cleanup) { failure = cleanup; }
            // Refuse to erase a live owned PGDATA; never terminate a process by PID alone.
            for (Path data : List.of(originData, cloneData)) {
                if (Files.exists(data.resolve("postmaster.pid"))) failure = append(failure, new AssertionError("Owned PostgreSQL shutdown remains unconfirmed"));
            }
            boolean erasePrivateDirectory = complete && clustersClosed && failure == null;
            // Remove every child credential handoff even when raw diagnostics must remain private.
            for (var child : children) {
                try { Files.deleteIfExists(child.directory().resolve("private-configuration.properties")); }
                catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            }
            if (!erasePrivateDirectory || failure != null) {
                erasePrivateDirectory = false;
                try { incompleteNativeDiagnostics(); }
                catch (Exception | Error diagnostic) { failure = append(failure, diagnostic); }
            }
            manifest.put("assertionsComplete", complete && failure == null).put("clustersClosed", clustersClosed).put("childrenStopped", childrenStopped);
            String output = System.getProperty("praxis.bulk.proof.directory");
            if (output != null && !output.isBlank()) {
                try {
                    Path target = Path.of(output); Files.createDirectories(target);
                    Path file = target.resolve(caseId + "-authority-agent.json"); require(!Files.exists(file));
                    Files.writeString(file, manifest.toPrettyString());
                } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            }
            if (erasePrivateDirectory && failure == null) {
                try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
            }
            rethrow(failure);
        }
    }

    private static Throwable append(Throwable original, Throwable next) {
        if (original == null) return next; original.addSuppressed(next); return original;
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    /** All bulk/Flyway rows and the domain fixture, including bytea content and copied xmin. */
    private static Map<String, List<String>> fullRows(JdbcTemplate sql) {
        var result = new LinkedHashMap<String, List<String>>();
        var tables = sql.queryForList("""
                select n.nspname||'.'||c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where c.relkind='r' and (n.nspname='praxis_bulk'
                    or (n.nspname='public' and c.relname='occupancy_domain_witness')) order by 1
                """, String.class);
        require(tables.contains("public.occupancy_domain_witness") && tables.contains("praxis_bulk.praxis_bulk_capacity_marker")
                && tables.contains("praxis_bulk." + BulkExecutionMigrator.HISTORY_TABLE));
        for (String table : tables) {
            if (!table.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unsafe fixture relation");
            result.put(table, sql.queryForList("select to_jsonb(t)::text from (select *,xmin::text as copy_xmin from "
                    + table + ") t order by 1", String.class));
        }
        return result;
    }

    /** Deparse under pg_catalog so qualification cannot depend on either connection's search_path. */
    private static Map<String, List<String>> catalog(DataSource owner) {
        return new JdbcTemplate(owner).execute((ConnectionCallback<Map<String, List<String>>>) connection -> {
            assertThat(connection.getAutoCommit()).isTrue();
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) { statement.execute("set local search_path=pg_catalog"); }
                var result = new LinkedHashMap<String, List<String>>();
                result.put("schemas", strings(connection, """
                        select jsonb_build_array(nspname,pg_get_userbyid(nspowner),nspacl)::text
                        from pg_namespace where nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("relations", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,c.relkind,pg_get_userbyid(c.relowner),c.relacl,c.reloptions)::text
                        from pg_class c join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("columns", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),
                            a.attnotnull,a.attidentity,a.attgenerated,a.attacl,pg_get_expr(d.adbin,d.adrelid))::text
                        from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace
                        left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') and a.attnum>0 and not a.attisdropped order by 1
                        """));
                result.put("constraints", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,k.conname,k.contype,k.convalidated,k.condeferrable,
                            k.condeferred,pg_get_constraintdef(k.oid))::text
                        from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("indexes", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,i.indisvalid,i.indisready,pg_get_indexdef(i.indexrelid))::text
                        from pg_index i join pg_class c on c.oid=i.indrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("functions", strings(connection, """
                        select jsonb_build_array(n.nspname,p.proname,pg_get_userbyid(p.proowner),p.proacl,
                            p.prosecdef,p.provolatile,p.proconfig,pg_get_functiondef(p.oid))::text
                        from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                result.put("triggers", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,t.tgname,t.tgenabled,t.tgisinternal,pg_get_triggerdef(t.oid))::text
                        from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','praxis_bulk_capacity','public') order by 1
                        """));
                return result;
            } finally { connection.rollback(); }
        });
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static Map<String, List<String>> clusterRoles(JdbcTemplate admin) {
        assertThat(admin.queryForObject("""
                select count(*) from pg_authid where rolname in
                    ('postgres','occupancy_provisioner','occupancy_allocator','occupancy_reader',
                     'bulk_runtime_test','durable_runtime')
                  and rolpassword like 'SCRAM-SHA-256$%'
                """, Long.class) == 6L).as("all six seeded identities use SCRAM before role snapshots").isTrue();
        // Real verifiers and all authentication attributes remain in parent memory only.
        // The caller compares this protected snapshot through unchanged(boolean), never an
        // assertion that prints rows; neither the snapshot nor its values enter certificates.
        var authentication = admin.queryForList("""
                select to_jsonb(r)::text from pg_authid r where rolname in
                    ('postgres','occupancy_provisioner','occupancy_allocator','occupancy_reader',
                     'bulk_runtime_test','durable_runtime') order by 1
                """, String.class);
        return Map.of("roles", admin.queryForList("select to_jsonb(r)::text from pg_roles r order by 1", String.class),
                "seededAuthentication", authentication,
                "memberships", admin.queryForList("select to_jsonb(m)::text from pg_auth_members m order by 1", String.class),
                "globalSettings", admin.queryForList("select to_jsonb(s)::text from pg_db_role_setting s where setdatabase=0 order by 1", String.class));
    }

}
