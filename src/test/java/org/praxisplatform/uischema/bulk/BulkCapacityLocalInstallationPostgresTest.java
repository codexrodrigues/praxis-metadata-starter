package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Physical local installation against a separate V2 authority and two V18 databases. */
class BulkCapacityLocalInstallationPostgresTest {
    private static final String DEPLOYMENT = "deployment-capacity-install-test";
    private static final String ENVIRONMENT = "prod";
    private static final String NAMESPACE = "tenant:prod:capacity-install-test";
    private static final String TENANT = "tenant-a";

    @Test
    void oneAuthenticatedIssuedRightInstallsOnceAndExactReplaySurvivesReconstruction() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-a", 7);
            local.installation().bootstrap();
            local.installation().bootstrap();
            fixture.provisioner.enrollBinding(TENANT, local.expected().bindingId(), local.expected().generation());
            local.installation().registerAttestation();
            local.installation().activate();
            var token = fixture.issue(local.expected());

            assertThat(local.installation().install(token.tokenId())).isTrue();
            assertThat(fixture.reconstruct(local).install(token.tokenId())).isFalse();
            assertThat(local.sql().queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker where marker_id=1", String.class))
                    .isEqualTo("ACTIVE");
            assertInstalled(local.sql(), token);
            assertThat(local.sql().queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                    .isEqualTo(1);
            var original = local.expected();
            var otherBinding = new JdbcBulkCapacityInstallation.ExpectedBinding(original.deploymentId(),
                    original.tenantId(), original.environment(), "other-binding", original.generation(),
                    original.databaseId(), original.attestationId(), original.authorityId(),
                    original.authorityEpoch());
            var otherGeneration = new JdbcBulkCapacityInstallation.ExpectedBinding(original.deploymentId(),
                    original.tenantId(), original.environment(), original.bindingId(), original.generation() + 1,
                    original.databaseId(), original.attestationId(), original.authorityId(),
                    original.authorityEpoch());
            assertThatThrownBy(() -> fixture.installation(otherBinding, local.ownerSource(), local.runtime())
                    .bootstrap()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> fixture.installation(otherGeneration, local.ownerSource(), local.runtime())
                    .bootstrap()).isInstanceOf(IllegalStateException.class);
            assertThat(local.sql().queryForObject("select binding_id from "
                    + "praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo(original.bindingId());
            assertThat(local.sql().queryForObject("select binding_generation from "
                    + "praxis_bulk.praxis_bulk_capacity_marker", Long.class)).isEqualTo(original.generation());
            assertThatThrownBy(() -> local.sql().update("update praxis_bulk.praxis_bulk_capacity_marker "
                    + "set authority_id=? where marker_id=1", UUID.randomUUID()))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> local.sql().update("update praxis_bulk.praxis_bulk_capacity_installation "
                    + "set token_state='ISSUED' where token_id=?", token.tokenId()))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> local.sql().update("delete from "
                    + "praxis_bulk.praxis_bulk_capacity_installation where token_id=?", token.tokenId()))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(local.runtimeSource()).update(
                    "delete from praxis_bulk.praxis_bulk_capacity_installation"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(local.runtimeSource()).update("""
                    insert into praxis_bulk.praxis_bulk_capacity_installation
                        (token_id, marker_id, database_id, deployment_id, tenant_id, environment,
                         binding_id, binding_generation, attestation_id, authority_id, authority_epoch,
                         request_id, capacity_class, token_ordinal, payload_digest, token_state)
                    values (?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), token.databaseId(), token.deploymentId(), token.tenantId(),
                    token.environment(), token.bindingId(), token.bindingGeneration(), token.attestationId(),
                    token.authorityId(), token.authorityEpoch(), UUID.randomUUID(), "ACTIVE", 2,
                    token.payloadDigest(), "ISSUED"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(local.runtimeSource()).update(
                    "update praxis_bulk.praxis_bulk_capacity_marker set state='FENCED' where marker_id=1"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(local.runtimeSource()).update(
                    "insert into praxis_bulk.praxis_bulk_capacity_marker(marker_id,database_id,deployment_id,"
                    + "tenant_id,environment,binding_id,binding_generation,attestation_id,authority_id,"
                    + "authority_epoch,state) values (1,?,?,?,?,?,?,?,?,?,'ACTIVE')",
                    UUID.randomUUID(), DEPLOYMENT, TENANT, ENVIRONMENT, "forged", 1,
                    UUID.randomUUID(), fixture.identity.authorityId(), fixture.identity.expectedAuthorityEpoch()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(local.sql().queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void localRuntimeOnOtherDatabaseCannotRegisterAttestationAndLeavesProvisionedMarker() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-cross-db", 2);
            var other = fixture.localB("binding-other", 2);
            local.installation().bootstrap();
            fixture.provisioner.enrollBinding(TENANT, local.expected().bindingId(), local.expected().generation());
            var wrongRuntime = fixture.installation(local.expected(), local.ownerSource(), other.runtime());
            assertThatThrownBy(wrongRuntime::registerAttestation).isInstanceOf(RuntimeException.class);
            assertThat(local.sql().queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class))
                    .isEqualTo("PROVISIONED");
            assertThat(fixture.authoritySql.queryForObject(
                    "select count(*) from praxis_bulk_capacity.binding_attestation", Integer.class)).isZero();
            local.installation().registerAttestation();
            assertThat(fixture.reader.readAttestation(local.expected().attestationId())).isPresent();
        }
    }

    @Test
    void failedGlobalRegistrationLeavesCommittedLocalIdentityProvisionedForExactRetry() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-register-retry", 5);
            local.installation().bootstrap();
            assertThatThrownBy(local.installation()::registerAttestation).isInstanceOf(RuntimeException.class);
            assertThat(local.sql().queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class))
                    .isEqualTo("PROVISIONED");
            assertThat(fixture.authoritySql.queryForObject(
                    "select count(*) from praxis_bulk_capacity.binding_attestation", Integer.class)).isZero();
            fixture.provisioner.enrollBinding(TENANT, local.expected().bindingId(), local.expected().generation());
            fixture.reconstruct(local).registerAttestation();
            fixture.reconstruct(local).activate();
            assertThat(local.sql().queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class))
                    .isEqualTo("ACTIVE");
            assertThat(fixture.reader.readAttestation(local.expected().attestationId())).isPresent();
        }
    }

    @Test
    void authorityIdAndEpochArePinnedEvenWhenOtherExpectedFieldsAreCopied() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-pin", 4);
            var token = fixture.prepareIssued(local);
            assertThat(local.installation().install(token.tokenId())).isTrue();
            var original = local.expected();
            var changedId = new JdbcBulkCapacityInstallation.ExpectedBinding(original.deploymentId(),
                    original.tenantId(), original.environment(), original.bindingId(), original.generation(),
                    original.databaseId(), original.attestationId(), UUID.randomUUID(), original.authorityEpoch());
            var changedEpoch = new JdbcBulkCapacityInstallation.ExpectedBinding(original.deploymentId(),
                    original.tenantId(), original.environment(), original.bindingId(), original.generation(),
                    original.databaseId(), original.attestationId(), original.authorityId(),
                    original.authorityEpoch() + 1);
            assertThatThrownBy(() -> fixture.installation(changedId, local.ownerSource(), local.runtime()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.installation(changedEpoch, local.ownerSource(), local.runtime()))
                    .isInstanceOf(IllegalArgumentException.class);
            var markerBefore = local.sql().queryForMap("select * from praxis_bulk.praxis_bulk_capacity_marker");
            var installationBefore = local.sql().queryForMap(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation where token_id=?", token.tokenId());
            for (int variant = 0; variant < 2; variant++) {
                boolean changedAuthorityId = variant == 0;
                String database = changedAuthorityId ? "capacity_global_other_id" : "capacity_global_other_epoch";
                new JdbcTemplate(fixture.postgres.getPostgresDatabase()).execute("create database " + database);
                var alternateIdentity = new BulkCapacityAuthorityMigrator.Identity(DEPLOYMENT, ENVIRONMENT,
                        changedAuthorityId ? UUID.randomUUID() : original.authorityId(),
                        changedAuthorityId ? original.authorityEpoch() : original.authorityEpoch() + 1);
                var alternateOwner = fixture.source("postgres", database);
                assertThat(BulkCapacityAuthorityMigrator.migrate(alternateOwner, alternateIdentity,
                        fixture.authorityRoles)).isEqualTo(2);
                var alternateProvisionerSource = fixture.source("capacity_install_provisioner", database);
                var alternateAllocatorSource = fixture.source("capacity_install_allocator", database);
                var alternateReaderSource = fixture.source("capacity_install_reader", database);
                var alternateProvisioner = new BulkCapacityAuthorityInfrastructure(alternateProvisionerSource,
                        new DataSourceTransactionManager(alternateProvisionerSource), alternateIdentity,
                        BulkCapacityAuthorityInfrastructure.Access.PROVISIONER, fixture.authorityRoles);
                var alternateAllocator = new BulkCapacityAuthorityInfrastructure(alternateAllocatorSource,
                        new DataSourceTransactionManager(alternateAllocatorSource), alternateIdentity,
                        BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR, fixture.authorityRoles);
                var alternateReaderInfrastructure = new BulkCapacityAuthorityInfrastructure(alternateReaderSource,
                        new DataSourceTransactionManager(alternateReaderSource), alternateIdentity,
                        BulkCapacityAuthorityInfrastructure.Access.READER, fixture.authorityRoles);
                var alternateReader = new JdbcBulkCapacityIssuer.CapacityReader(alternateReaderInfrastructure);
                var alternateIssuer = new JdbcBulkCapacityIssuer(alternateAllocator, alternateReaderInfrastructure);
                alternateProvisioner.enrollBinding(original.tenantId(), original.bindingId(), original.generation());
                alternateProvisioner.registerAttestation(original.tenantId(), original.bindingId(),
                        original.generation(), original.databaseId(), original.attestationId());
                var alternateExpected = new JdbcBulkCapacityInstallation.ExpectedBinding(original.deploymentId(),
                        original.tenantId(), original.environment(), original.bindingId(), original.generation(),
                        original.databaseId(), original.attestationId(), alternateIdentity.authorityId(),
                        alternateIdentity.expectedAuthorityEpoch());
                var request = new JdbcBulkCapacityIssuer.Request(UUID.randomUUID(), DEPLOYMENT,
                        original.tenantId(), original.bindingId(), JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1);
                alternateIssuer.requestCapacity(request);
                var allocated = alternateIssuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow();
                var alternateToken = alternateReader.readIssuedToken(allocated.tokenId()).orElseThrow();
                assertThat(alternateReader.readAttestation(original.attestationId())).isPresent();
                assertThat(alternateToken.authorityId()).isEqualTo(alternateIdentity.authorityId());
                assertThat(alternateToken.authorityEpoch()).isEqualTo(alternateIdentity.expectedAuthorityEpoch());
                var reconstructed = new JdbcBulkCapacityInstallation(alternateExpected, local.ownerSource(),
                        new DataSourceTransactionManager(local.ownerSource()), "postgres", local.runtime(),
                        alternateProvisioner, alternateReader, Duration.ofSeconds(20), Duration.ofSeconds(3));
                assertThatThrownBy(reconstructed::bootstrap).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(reconstructed::activate).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> reconstructed.install(alternateToken.tokenId()))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(local.sql().queryForMap("select * from praxis_bulk.praxis_bulk_capacity_marker"))
                        .isEqualTo(markerBefore);
                assertThat(local.sql().queryForMap(
                        "select * from praxis_bulk.praxis_bulk_capacity_installation where token_id=?", token.tokenId()))
                        .isEqualTo(installationBefore);
                assertThat(local.sql().queryForObject(
                        "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                        .isEqualTo(1);
            }
            assertInstalled(local.sql(), token);
        }
    }

    @Test
    void realInsertLockTimeoutRollsBackAndLaterKnownCommitCanBeReadBack() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-lock-rollback", 1);
            var token = fixture.prepareIssued(local);
            var bounded = fixture.installation(local.expected(), local.ownerSource(), local.runtime(),
                    Duration.ofSeconds(4), Duration.ofMillis(300));
            try (var holder = local.ownerSource().getConnection()) {
                holder.setAutoCommit(false);
                try (var statement = holder.createStatement()) {
                    statement.execute("lock table praxis_bulk.praxis_bulk_capacity_installation in share mode");
                }
                assertThatThrownBy(() -> bounded.install(token.tokenId())).isInstanceOf(RuntimeException.class);
                assertThat(local.sql().queryForObject(
                        "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                        .isZero();
                holder.commit();
            }
            assertThat(local.installation().install(token.tokenId())).isTrue();
            assertInstalled(local.sql(), token);
            assertThat(fixture.reconstruct(local).install(token.tokenId())).isFalse();
        }
    }

    @Test
    void authorityReaderCommitPrecedesLocalOwnerTransactionsOnDifferentBackendPids() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-transaction-order", 1);
            var token = fixture.prepareIssued(local);
            var events = new ArrayList<TxEvent>();
            var owner = new TracingDataSource(fixture.postgres.getJdbcUrl("postgres", "capacity_local_a"),
                    "postgres", "owner", events);
            var readerSource = new TracingDataSource(
                    fixture.postgres.getJdbcUrl("capacity_install_reader", "capacity_global"),
                    "capacity_install_reader", "reader", events);
            var readerInfrastructure = new BulkCapacityAuthorityInfrastructure(readerSource,
                    new DataSourceTransactionManager(readerSource), fixture.identity,
                    BulkCapacityAuthorityInfrastructure.Access.READER, fixture.authorityRoles);
            var installation = new JdbcBulkCapacityInstallation(local.expected(), owner,
                    new DataSourceTransactionManager(owner), "postgres", local.runtime(), fixture.provisioner,
                    new JdbcBulkCapacityIssuer.CapacityReader(readerInfrastructure),
                    Duration.ofSeconds(20), Duration.ofSeconds(3));
            events.clear();
            assertThat(installation.install(token.tokenId())).isTrue();
            assertInstalled(local.sql(), token);
            assertThat(events).extracting(TxEvent::kind)
                    .containsSubsequence("reader.open", "reader.commit", "owner.open", "owner.commit",
                            "owner.open", "owner.commit");
            int readerCommit = eventIndex(events, "reader.commit", 0);
            int ownerOpen = eventIndex(events, "owner.open", 0);
            int ownerCommit = eventIndex(events, "owner.commit", 0);
            int ownerReadbackOpen = eventIndex(events, "owner.open", ownerOpen + 1);
            assertThat(readerCommit).isLessThan(ownerOpen);
            assertThat(ownerCommit).isLessThan(ownerReadbackOpen);
            assertThat(events.get(readerCommit).xid()).isPositive();
            assertThat(events.get(ownerCommit).xid()).isPositive();
            assertThat(events.get(readerCommit).pid()).isNotEqualTo(events.get(ownerOpen).pid());
        }
    }

    private static int eventIndex(List<TxEvent> events, String kind, int start) {
        for (int index = start; index < events.size(); index++)
            if (events.get(index).kind().equals(kind)) return index;
        throw new AssertionError("Missing physical transaction event: " + kind);
    }

    private record TxEvent(String kind, long pid, long xid) { }

    /** Test-only physical connection instrumentation; it changes no production source or credential route. */
    private static final class TracingDataSource extends DriverManagerDataSource {
        private final String label;
        private final List<TxEvent> events;

        TracingDataSource(String url, String user, String label, List<TxEvent> events) {
            super(url, user, "");
            this.label = label;
            this.events = events;
        }

        @Override public Connection getConnection() throws SQLException {
            Connection physical = super.getConnection();
            long pid = physicalScalar(physical, "select pg_backend_pid()");
            events.add(new TxEvent(label + ".open", pid, 0));
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        try {
                            if (name.equals("commit") || name.equals("rollback")) {
                                long xid = physicalScalar(physical, "select txid_current()");
                                Object result = method.invoke(physical, args);
                                events.add(new TxEvent(label + "." + name, pid, xid));
                                return result;
                            }
                            Object result = method.invoke(physical, args);
                            if (name.equals("close")) events.add(new TxEvent(label + ".close", pid, 0));
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private static long physicalScalar(Connection connection, String query) throws SQLException {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
                if (!rows.next()) throw new SQLException("Physical transaction identity is absent");
                long value = rows.getLong(1);
                if (rows.next()) throw new SQLException("Physical transaction identity is ambiguous");
                return value;
            }
        }
    }

    @Test
    void fenceFromProvisionedOrActiveIsTerminalAcrossFreshInstallerAndBlocksInstall() throws Exception {
        try (var fixture = new Fixture()) {
            var provisioned = fixture.localA("binding-provisioned", 1);
            provisioned.installation().bootstrap();
            provisioned.installation().fence();
            provisioned.installation().fence();
            assertThatThrownBy(() -> fixture.reconstruct(provisioned).bootstrap())
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(provisioned.installation()::registerAttestation)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(provisioned.sql().queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo("FENCED");

            var active = fixture.localB("binding-active", 1);
            active.installation().bootstrap();
            fixture.provisioner.enrollBinding(TENANT, active.expected().bindingId(), active.expected().generation());
            active.installation().registerAttestation();
            active.installation().activate();
            var installedBeforeFence = fixture.issue(active.expected());
            var attemptedAfterFence = fixture.issue(active.expected());
            assertThat(active.installation().install(installedBeforeFence.tokenId())).isTrue();
            assertInstalled(active.sql(), installedBeforeFence);
            active.installation().fence();
            assertThatThrownBy(() -> fixture.reconstruct(active).activate())
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> fixture.reconstruct(active).install(attemptedAfterFence.tokenId()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(active.sql().queryForObject(
                    "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class)).isEqualTo("FENCED");
            assertThat(active.sql().queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                    .isEqualTo(1);
            assertThat(fixture.reader.readIssuedToken(attemptedAfterFence.tokenId())).isPresent();
        }
    }

    @Test
    void fencedPhysicalDatabaseRejectsValidIssuedTokenFromNewOwnerJvm() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-fenced-process", 2);
            var installedBeforeFence = fixture.prepareIssued(local);
            assertThat(local.installation().install(installedBeforeFence.tokenId())).isTrue();
            var attemptedAfterFence = fixture.issue(local.expected());
            assertThat(fixture.reader.readIssuedToken(attemptedAfterFence.tokenId())).isPresent();
            local.installation().fence();
            var markerBefore = local.sql().queryForMap("select * from praxis_bulk.praxis_bulk_capacity_marker");
            var installationBefore = local.sql().queryForMap(
                    "select * from praxis_bulk.praxis_bulk_capacity_installation where token_id=?",
                    installedBeforeFence.tokenId());
            assertThat(markerBefore.get("state")).isEqualTo("FENCED");

            Path directory = Files.createTempDirectory("capacity-fenced-restart-proof-");
            Process child = null;
            try {
                Path config = directory.resolve("config.properties");
                Path ready = directory.resolve("ready");
                Path start = directory.resolve("start");
                Path result = directory.resolve("result");
                Path output = directory.resolve("output");
                writeChildConfig(config, fixture,
                        new ChildRun(local, attemptedAfterFence, "capacity_local_a", 0));
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                String classpath = System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path"));
                child = new ProcessBuilder(java, "-cp", classpath,
                        BulkCapacityInstallationProcess.class.getName(), config.toString(),
                        ready.toString(), start.toString(), result.toString())
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                long readyDeadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
                while (!Files.exists(ready)) {
                    assertThat(System.nanoTime()).as("new owner JVM reached construction barrier")
                            .isLessThan(readyDeadline);
                    assertThat(child.isAlive()).as("new owner JVM alive before installation").isTrue();
                    Thread.sleep(20);
                }
                assertThat(Long.parseLong(Files.readString(ready).trim())).isEqualTo(child.pid());
                Files.writeString(start, "go", StandardCharsets.UTF_8);
                assertThat(child.waitFor(60, TimeUnit.SECONDS)).as("new owner JVM exited").isTrue();
                assertThat(child.exitValue()).isEqualTo(21);
                assertThat(Files.readString(output)).as("sanitized owner JVM output").isEmpty();
                assertThat(Files.readAllLines(result)).containsExactly("FAIL", Long.toString(child.pid()),
                        "IllegalStateException");
                assertThat(fixture.reader.readIssuedToken(attemptedAfterFence.tokenId())).isPresent();
                assertThat(local.sql().queryForMap("select * from praxis_bulk.praxis_bulk_capacity_marker"))
                        .isEqualTo(markerBefore);
                assertThat(local.sql().queryForMap(
                        "select * from praxis_bulk.praxis_bulk_capacity_installation where token_id=?",
                        installedBeforeFence.tokenId())).isEqualTo(installationBefore);
                assertThat(local.sql().queryForObject(
                        "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                        .isEqualTo(1);
            } finally {
                if (child != null && child.isAlive()) {
                    child.destroy();
                    if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
                    child.waitFor(3, TimeUnit.SECONDS);
                }
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                    });
                }
            }
        }
    }

    @Test
    void concurrentFenceAndInstallSerializeOnTheMarkerWithoutPostFenceAdmission() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-fence-race", 1);
            var token = fixture.prepareIssued(local);
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var install = workers.submit(() -> {
                    start.await();
                    return fixture.reconstruct(local).install(token.tokenId());
                });
                var fence = workers.submit(() -> {
                    start.await();
                    fixture.reconstruct(local).fence();
                    return null;
                });
                start.countDown();
                boolean installed = false;
                try {
                    installed = install.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException rejected) {
                    assertThat(rejected.getCause()).isInstanceOf(IllegalStateException.class);
                }
                assertThat(fence.get(30, TimeUnit.SECONDS)).isNull();
                assertThat(local.sql().queryForObject(
                        "select state from praxis_bulk.praxis_bulk_capacity_marker", String.class))
                        .isEqualTo("FENCED");
                assertThat(local.sql().queryForObject(
                        "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                        .isEqualTo(installed ? 1 : 0);
                if (installed) assertInstalled(local.sql(), token);
                assertThatThrownBy(() -> fixture.reconstruct(local).install(token.tokenId()))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void everyEntryRejectsAmbientWritableAndReadOnlyTransactionsBeforeOpeningConnections() throws Exception {
        try (var fixture = new Fixture()) {
            var local = fixture.localA("binding-ambient", 1);
            var owner = local.ownerSource();
            var runtime = local.runtimeSource();
            var provisioner = fixture.provisionerSource;
            var reader = fixture.readerSource;
            var foreign = new DriverManagerDataSource(fixture.postgres.getJdbcUrl("postgres", "postgres"),
                    "postgres", "");
            for (var manager : List.of(new DataSourceTransactionManager(foreign),
                    new DataSourceTransactionManager(owner))) {
                var transaction = new TransactionTemplate(manager);
                for (boolean readOnly : new boolean[] {false, true}) {
                    transaction.setReadOnly(readOnly);
                    transaction.execute(status -> {
                        int before = owner.opens() + runtime.opens() + provisioner.opens() + reader.opens();
                        assertThatThrownBy(local.installation()::bootstrap).isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(local.installation()::registerAttestation).isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(local.installation()::activate).isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> local.installation().install(UUID.randomUUID()))
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(local.installation()::fence).isInstanceOf(IllegalStateException.class);
                        assertThat(owner.opens() + runtime.opens() + provisioner.opens() + reader.opens())
                                .isEqualTo(before);
                        return null;
                    });
                }
            }
        }
    }

    @Test
    void fourIndependentOwnerJvmsSerializeOneInstallationPerPhysicalDatabase() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.localA("binding-process-a", 3);
            var second = fixture.localB("binding-process-b", 3);
            var firstToken = fixture.prepareIssued(first);
            var secondToken = fixture.prepareIssued(second);
            Path directory = Files.createTempDirectory("capacity-owner-process-proof-");
            var children = new ArrayList<Process>();
            try {
                Path start = directory.resolve("start");
                var runs = List.of(
                        new ChildRun(first, firstToken, "capacity_local_a", 0),
                        new ChildRun(first, firstToken, "capacity_local_a", 1),
                        new ChildRun(second, secondToken, "capacity_local_b", 2),
                        new ChildRun(second, secondToken, "capacity_local_b", 3));
                for (var run : runs) {
                    Path config = directory.resolve("config-" + run.number() + ".properties");
                    writeChildConfig(config, fixture, run);
                    Path ready = directory.resolve("ready-" + run.number());
                    Path result = directory.resolve("result-" + run.number());
                    Path output = directory.resolve("output-" + run.number());
                    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                    String classpath = System.getProperty("surefire.test.class.path",
                            System.getProperty("java.class.path"));
                    Process child = new ProcessBuilder(java, "-cp", classpath,
                            BulkCapacityInstallationProcess.class.getName(), config.toString(),
                            ready.toString(), start.toString(), result.toString())
                            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                    children.add(child);
                }
                long readyDeadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
                while (runs.stream().anyMatch(run -> !Files.exists(directory.resolve("ready-" + run.number())))) {
                    assertThat(System.nanoTime()).as("all four owner JVMs reached barrier")
                            .isLessThan(readyDeadline);
                    assertThat(children.stream().allMatch(Process::isAlive))
                            .as("all four owner JVMs remain alive before barrier").isTrue();
                    Thread.sleep(20);
                }
                Files.writeString(start, "go", StandardCharsets.UTF_8);
                var pids = new java.util.HashSet<Long>();
                var outcomesByDatabase = new java.util.HashMap<String, List<Boolean>>();
                for (var run : runs) {
                    Process child = children.get(run.number());
                    assertThat(child.waitFor(60, TimeUnit.SECONDS)).as("owner JVM exited").isTrue();
                    assertThat(child.exitValue()).as("owner JVM exit code").isZero();
                    assertThat(Files.readString(directory.resolve("output-" + run.number())))
                            .as("owner JVM emitted no data").isEmpty();
                    var lines = Files.readAllLines(directory.resolve("result-" + run.number()));
                    assertThat(lines).hasSize(3);
                    assertThat(lines.get(0)).isEqualTo("OK");
                    long pid = Long.parseLong(lines.get(1));
                    assertThat(pid).isEqualTo(child.pid());
                    assertThat(pids.add(pid)).as("four distinct OS processes").isTrue();
                    assertThat(lines.get(2)).isIn("true", "false");
                    outcomesByDatabase.computeIfAbsent(run.database(), ignored -> new ArrayList<>())
                            .add(Boolean.parseBoolean(lines.get(2)));
                }
                assertThat(pids).hasSize(4);
                assertThat(outcomesByDatabase).hasSize(2);
                assertThat(outcomesByDatabase.get("capacity_local_a"))
                        .containsExactlyInAnyOrder(true, false);
                assertThat(outcomesByDatabase.get("capacity_local_b"))
                        .containsExactlyInAnyOrder(true, false);
                assertInstalled(first.sql(), firstToken);
                assertInstalled(second.sql(), secondToken);
                assertThat(first.sql().queryForObject(
                        "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                        .isEqualTo(1);
                assertThat(second.sql().queryForObject(
                        "select count(*) from praxis_bulk.praxis_bulk_capacity_installation", Integer.class))
                        .isEqualTo(1);
            } finally {
                for (Process child : children) {
                    if (child.isAlive()) {
                        child.destroy();
                        if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
                        child.waitFor(3, TimeUnit.SECONDS);
                    }
                }
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                    });
                }
            }
        }
    }

    private record ChildRun(Local local, JdbcBulkCapacityIssuer.IssuedToken token,
                            String database, int number) { }

    private static void writeChildConfig(Path path, Fixture fixture, ChildRun run) throws Exception {
        var values = new Properties();
        var expected = run.local().expected();
        values.setProperty("deployment", expected.deploymentId());
        values.setProperty("environment", expected.environment());
        values.setProperty("authorityId", expected.authorityId().toString());
        values.setProperty("authorityEpoch", Long.toString(expected.authorityEpoch()));
        values.setProperty("authorityOwner", "postgres");
        values.setProperty("authorityProvisioner", "capacity_install_provisioner");
        values.setProperty("authorityAllocator", "capacity_install_allocator");
        values.setProperty("authorityReader", "capacity_install_reader");
        values.setProperty("provisionerUrl", fixture.postgres.getJdbcUrl("capacity_install_provisioner", "capacity_global"));
        values.setProperty("provisionerUser", "capacity_install_provisioner");
        values.setProperty("provisionerPassword", "");
        values.setProperty("readerUrl", fixture.postgres.getJdbcUrl("capacity_install_reader", "capacity_global"));
        values.setProperty("readerUser", "capacity_install_reader");
        values.setProperty("readerPassword", "");
        values.setProperty("ownerUrl", fixture.postgres.getJdbcUrl("postgres", run.database()));
        values.setProperty("ownerUser", "postgres");
        values.setProperty("ownerPassword", "");
        values.setProperty("runtimeUrl", fixture.postgres.getJdbcUrl("bulk_runtime_test", run.database()));
        values.setProperty("runtimeUser", "bulk_runtime_test");
        values.setProperty("runtimePassword", "");
        values.setProperty("namespace", NAMESPACE);
        values.setProperty("runtimeRoles", String.join(",",
                BulkPostgresTestSupport.testRoleConfiguration().runtimeGranteeRoles()));
        values.setProperty("retentionRoles", "");
        values.setProperty("controlRoles", "");
        values.setProperty("tenant", expected.tenantId());
        values.setProperty("binding", expected.bindingId());
        values.setProperty("bindingGeneration", Long.toString(expected.generation()));
        values.setProperty("databaseId", expected.databaseId().toString());
        values.setProperty("attestationId", expected.attestationId().toString());
        values.setProperty("transactionBudgetMillis", "20000");
        values.setProperty("lockBudgetMillis", "3000");
        values.setProperty("barrierBudgetMillis", "60000");
        values.setProperty("tokenId", run.token().tokenId().toString());
        Files.createFile(path, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(path)) { values.store(output, "capacity owner test fixture"); }
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(
                PosixFilePermissions.fromString("rw-------"));
    }

    private static void assertInstalled(JdbcTemplate sql, JdbcBulkCapacityIssuer.IssuedToken token) {
        var row = sql.queryForMap("select * from praxis_bulk.praxis_bulk_capacity_installation where token_id=?",
                token.tokenId());
        assertThat(row.get("token_id")).isEqualTo(token.tokenId());
        assertThat(row.get("request_id")).isEqualTo(token.requestId());
        assertThat(row.get("deployment_id")).isEqualTo(token.deploymentId());
        assertThat(row.get("tenant_id")).isEqualTo(token.tenantId());
        assertThat(row.get("environment")).isEqualTo(token.environment());
        assertThat(row.get("binding_id")).isEqualTo(token.bindingId());
        assertThat(((Number) row.get("binding_generation")).longValue()).isEqualTo(token.bindingGeneration());
        assertThat(row.get("capacity_class")).isEqualTo(token.capacityClass().name());
        assertThat(((Number) row.get("token_ordinal")).intValue()).isEqualTo(token.ordinal());
        assertThat(row.get("payload_digest")).isEqualTo(token.payloadDigest());
        assertThat(row.get("token_state")).isEqualTo(token.tokenState());
        assertThat(row.get("database_id")).isEqualTo(token.databaseId());
        assertThat(row.get("attestation_id")).isEqualTo(token.attestationId());
        assertThat(row.get("authority_id")).isEqualTo(token.authorityId());
        assertThat(((Number) row.get("authority_epoch")).longValue()).isEqualTo(token.authorityEpoch());
    }

    private record Local(JdbcBulkCapacityInstallation.ExpectedBinding expected, CountingDataSource ownerSource,
                         CountingDataSource runtimeSource, BulkExecutionInfrastructure runtime,
                         JdbcBulkCapacityInstallation installation, JdbcTemplate sql) { }

    private static final class CountingDataSource extends DriverManagerDataSource {
        private final AtomicInteger opens = new AtomicInteger();

        CountingDataSource(String url, String user) { super(url, user, ""); }

        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
            opens.incrementAndGet();
            return super.getConnection();
        }

        int opens() { return opens.get(); }
    }

    private static final class Fixture implements AutoCloseable {
        final EmbeddedPostgres postgres;
        final BulkCapacityAuthorityMigrator.Identity identity;
        final BulkCapacityAuthorityMigrator.RoleConfiguration authorityRoles;
        final JdbcTemplate authoritySql;
        final CountingDataSource provisionerSource;
        final CountingDataSource allocatorSource;
        final CountingDataSource readerSource;
        final BulkCapacityAuthorityInfrastructure provisioner;
        final BulkCapacityAuthorityInfrastructure allocator;
        final JdbcBulkCapacityIssuer.CapacityReader reader;
        final JdbcBulkCapacityIssuer issuer;

        Fixture() throws Exception {
            postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                    .setRegisterShutdownHook(false).start();
            var cluster = new JdbcTemplate(postgres.getPostgresDatabase());
            cluster.execute("create role capacity_install_provisioner login");
            cluster.execute("create role capacity_install_allocator login");
            cluster.execute("create role capacity_install_reader login");
            cluster.execute("create database capacity_global");
            cluster.execute("create database capacity_local_a");
            cluster.execute("create database capacity_local_b");
            DataSource authorityOwner = new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", "capacity_global"), "postgres", "");
            identity = new BulkCapacityAuthorityMigrator.Identity(DEPLOYMENT, ENVIRONMENT,
                    UUID.randomUUID(), 9);
            authorityRoles = new BulkCapacityAuthorityMigrator.RoleConfiguration("postgres",
                    "capacity_install_provisioner", "capacity_install_allocator", "capacity_install_reader");
            assertThat(BulkCapacityAuthorityMigrator.migrate(authorityOwner, identity, authorityRoles))
                    .isEqualTo(2);
            authoritySql = new JdbcTemplate(authorityOwner);
            provisionerSource = source("capacity_install_provisioner", "capacity_global");
            allocatorSource = source("capacity_install_allocator", "capacity_global");
            readerSource = source("capacity_install_reader", "capacity_global");
            provisioner = infrastructure(provisionerSource, BulkCapacityAuthorityInfrastructure.Access.PROVISIONER);
            allocator = infrastructure(allocatorSource, BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR);
            var readerInfrastructure = infrastructure(readerSource, BulkCapacityAuthorityInfrastructure.Access.READER);
            reader = new JdbcBulkCapacityIssuer.CapacityReader(readerInfrastructure);
            issuer = new JdbcBulkCapacityIssuer(allocator, readerInfrastructure);
            migrateLocal("capacity_local_a");
            migrateLocal("capacity_local_b");
        }

        Local localA(String binding, long generation) {
            return local("capacity_local_a", binding, generation);
        }

        Local localB(String binding, long generation) {
            return local("capacity_local_b", binding, generation);
        }

        private Local local(String database, String binding, long generation) {
            var owner = source("postgres", database);
            var runtimeSource = source("bulk_runtime_test", database);
            var runtime = new BulkExecutionInfrastructure(runtimeSource,
                    new DataSourceTransactionManager(runtimeSource), NAMESPACE, DEPLOYMENT,
                    BulkPostgresTestSupport.testRoleConfiguration());
            var expected = new JdbcBulkCapacityInstallation.ExpectedBinding(DEPLOYMENT, TENANT, ENVIRONMENT,
                    binding, generation, UUID.randomUUID(), UUID.randomUUID(), identity.authorityId(),
                    identity.expectedAuthorityEpoch());
            return new Local(expected, owner, runtimeSource, runtime,
                    installation(expected, owner, runtime), new JdbcTemplate(owner));
        }

        JdbcBulkCapacityInstallation reconstruct(Local local) {
            return installation(local.expected(), local.ownerSource(), local.runtime());
        }

        JdbcBulkCapacityInstallation installation(JdbcBulkCapacityInstallation.ExpectedBinding expected,
                                                   CountingDataSource owner, BulkExecutionInfrastructure runtime) {
            return installation(expected, owner, runtime, Duration.ofSeconds(20), Duration.ofSeconds(3));
        }

        JdbcBulkCapacityInstallation installation(JdbcBulkCapacityInstallation.ExpectedBinding expected,
                CountingDataSource owner, BulkExecutionInfrastructure runtime,
                Duration transactionBudget, Duration lockBudget) {
            return new JdbcBulkCapacityInstallation(expected, owner, new DataSourceTransactionManager(owner),
                    "postgres", runtime, provisioner, reader, transactionBudget, lockBudget);
        }

        JdbcBulkCapacityIssuer.IssuedToken issue(JdbcBulkCapacityInstallation.ExpectedBinding expected) {
            var request = new JdbcBulkCapacityIssuer.Request(UUID.randomUUID(), DEPLOYMENT,
                    expected.tenantId(), expected.bindingId(), JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1);
            issuer.requestCapacity(request);
            var token = issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow();
            return reader.readIssuedToken(token.tokenId()).orElseThrow();
        }

        JdbcBulkCapacityIssuer.IssuedToken prepareIssued(Local local) {
            local.installation().bootstrap();
            provisioner.enrollBinding(TENANT, local.expected().bindingId(), local.expected().generation());
            local.installation().registerAttestation();
            local.installation().activate();
            return issue(local.expected());
        }

        private void migrateLocal(String database) {
            DataSource owner = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", database),
                    "postgres", "");
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(NAMESPACE, DEPLOYMENT)))
                    .isEqualTo(18);
            // Explicit fixture role provisioning follows the completed empty-role bootstrap.
            BulkPostgresTestSupport.grantRuntimeRole(owner, "bulk_runtime_test");
            BulkPostgresTestSupport.grantRuntimeRole(owner, "durable_runtime");
            BulkExecutionMigrator.validate(owner, BulkPostgresTestSupport.testRoleConfiguration());
        }

        private CountingDataSource source(String user, String database) {
            return new CountingDataSource(postgres.getJdbcUrl(user, database), user);
        }

        private BulkCapacityAuthorityInfrastructure infrastructure(CountingDataSource source,
                BulkCapacityAuthorityInfrastructure.Access access) {
            return new BulkCapacityAuthorityInfrastructure(source, new DataSourceTransactionManager(source),
                    identity, access, authorityRoles);
        }

        @Override public void close() throws Exception { postgres.close(); }
    }
}
