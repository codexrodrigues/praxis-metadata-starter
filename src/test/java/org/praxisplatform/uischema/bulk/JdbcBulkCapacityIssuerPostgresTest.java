package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual PostgreSQL grants, separate login connections and durable issuer transactions. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class JdbcBulkCapacityIssuerPostgresTest {
    private static final String DEPLOYMENT = "capacity-deployment";
    private static final String PROVISIONER = "cap_provisioner_issuer";
    private static final String ALLOCATOR = "cap_allocator_issuer";
    private static final String READER = "cap_reader_issuer";

    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource provisionerSource;
    private DataSource allocatorSource;
    private DataSource readerSource;
    private BulkCapacityAuthorityMigrator.Identity identity;
    private BulkCapacityAuthorityMigrator.RoleConfiguration roles;
    private BulkCapacityAuthorityInfrastructure provisioner;
    private BulkCapacityAuthorityInfrastructure allocator;
    private BulkCapacityAuthorityInfrastructure reader;
    private JdbcBulkCapacityIssuer issuer;

    @BeforeAll
    void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        var admin = new JdbcTemplate(owner);
        for (String login : List.of(PROVISIONER, ALLOCATOR, READER))
            admin.execute("create role " + login + " login");
        provisionerSource = login(PROVISIONER);
        allocatorSource = login(ALLOCATOR);
        readerSource = login(READER);
        roles = new BulkCapacityAuthorityMigrator.RoleConfiguration(
                "postgres", PROVISIONER, ALLOCATOR, READER);
    }

    @AfterAll
    void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void reset() {
        var admin = new JdbcTemplate(owner);
        admin.execute("drop schema if exists praxis_bulk_capacity cascade");
        identity = new BulkCapacityAuthorityMigrator.Identity(
                DEPLOYMENT, "test", UUID.randomUUID(), 7);
        assertThat(BulkCapacityAuthorityMigrator.migrate(owner, identity, roles)).isEqualTo(1);
        provisioner = infrastructure(provisionerSource, BulkCapacityAuthorityInfrastructure.Access.PROVISIONER);
        allocator = infrastructure(allocatorSource, BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR);
        reader = infrastructure(readerSource, BulkCapacityAuthorityInfrastructure.Access.READER);
        issuer = new JdbcBulkCapacityIssuer(allocator, reader);
    }

    @Test
    void requestIsImmutableAndReplayReadsTheSameProgressAndTokensAfterKnownCommit() {
        enroll("tenant-a", "binding-a");
        var request = request("tenant-a", "binding-a", JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 2);
        issuer.requestCapacity(request);
        assertThat(issuer.findIssue(request.requestId()).orElseThrow().issuedCount()).isZero();
        issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow();
        // Drop the Java response only after an independent owner connection sees the committed row.
        UUID observedToken = new JdbcTemplate(owner).queryForObject("""
                select token_id from praxis_bulk_capacity.capacity_token
                where request_id=? and token_ordinal=1
                """, UUID.class, request.requestId());
        assertThat(observedToken).isNotNull();
        var reopened = new JdbcBulkCapacityIssuer(
                infrastructure(allocatorSource, BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR),
                infrastructure(readerSource, BulkCapacityAuthorityInfrastructure.Access.READER));
        reopened.requestCapacity(request);
        var readback = reopened.findIssue(request.requestId()).orElseThrow();
        assertThat(readback.issuedCount()).isEqualTo(1);
        assertThat(readback.tokenIds()).containsExactly(observedToken);
        assertThat(readback.payloadDigest()).startsWith("sha256:").hasSize(71);
        var second = reopened.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow();
        assertThat(second.ordinal()).isEqualTo(2);
        assertThat(reopened.findIssue(request.requestId()).orElseThrow().tokenIds())
                .containsExactly(observedToken, second.tokenId());
        assertThatThrownBy(() -> reopened.requestCapacity(new JdbcBulkCapacityIssuer.Request(
                request.requestId(), DEPLOYMENT, "tenant-a", "binding-a",
                JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1)))
                .isInstanceOfSatisfying(JdbcBulkCapacityIssuer.CapacityFailure.class,
                        error -> assertThat(error.reason()).isEqualTo(JdbcBulkCapacityIssuer.Failure.CONFLICT));
        enroll("tenant-a", "binding-other");
        for (var changed : List.of(
                new JdbcBulkCapacityIssuer.Request(request.requestId(), DEPLOYMENT, "tenant-a",
                        "binding-other", JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 2),
                new JdbcBulkCapacityIssuer.Request(request.requestId(), DEPLOYMENT, "tenant-a",
                        "binding-a", JdbcBulkCapacityIssuer.CapacityClass.QUEUE, 2))) {
            assertThatThrownBy(() -> reopened.requestCapacity(changed))
                    .isInstanceOfSatisfying(JdbcBulkCapacityIssuer.CapacityFailure.class,
                            error -> assertThat(error.reason()).isEqualTo(JdbcBulkCapacityIssuer.Failure.CONFLICT));
        }
        assertThat(reopened.findIssue(request.requestId()).orElseThrow().tokenIds())
                .containsExactly(observedToken, second.tokenId());
    }

    @Test
    void persistentCursorIssuesOneQuantumAcrossEligibleTenantsAndSkipsCappedTenant() {
        for (String tenant : List.of("a", "b", "c")) {
            enroll(tenant, "binding-" + tenant);
            issuer.requestCapacity(request(tenant, "binding-" + tenant,
                    JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 2));
            issuer.requestCapacity(request(tenant, "binding-" + tenant,
                    JdbcBulkCapacityIssuer.CapacityClass.QUEUE, 1));
        }
        var order = new ArrayList<String>();
        for (int index = 0; index < 2; index++)
            order.add(issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE)
                    .orElseThrow().tenantId());
        var reopened = new JdbcBulkCapacityIssuer(
                infrastructure(allocatorSource, BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR),
                infrastructure(readerSource, BulkCapacityAuthorityInfrastructure.Access.READER));
        assertThat(reopened.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.QUEUE)
                .orElseThrow().tenantId()).isEqualTo("a");
        for (int index = 2; index < 6; index++)
            order.add(reopened.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE)
                    .orElseThrow().tenantId());
        assertThat(order).containsExactly("a", "b", "c", "a", "b", "c");
        assertThat(reopened.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE)).isEmpty();
    }

    @Test
    void fiveTenantsReachExactGlobalActiveAndQueueCapsWithoutExceedingPerTenantCaps() {
        for (int index = 1; index <= 5; index++) {
            String tenant = "tenant-" + index;
            String binding = "binding-" + index;
            enroll(tenant, binding);
            issuer.requestCapacity(request(tenant, binding,
                    JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 2));
            issuer.requestCapacity(request(tenant, binding,
                    JdbcBulkCapacityIssuer.CapacityClass.QUEUE, 20));
        }
        var active = new ArrayList<JdbcBulkCapacityIssuer.Token>();
        var queued = new ArrayList<JdbcBulkCapacityIssuer.Token>();
        for (int index = 0; index < 8; index++)
            active.add(issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow());
        for (int index = 0; index < 80; index++)
            queued.add(issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.QUEUE).orElseThrow());
        assertThat(issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE)).isEmpty();
        assertThat(issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.QUEUE)).isEmpty();
        assertThat(active).hasSize(8);
        assertThat(queued).hasSize(80);
        assertThat(active).allMatch(token -> DEPLOYMENT.equals(token.deploymentId()));
        assertThat(queued).allMatch(token -> DEPLOYMENT.equals(token.deploymentId()));
        assertThat(active.stream().map(JdbcBulkCapacityIssuer.Token::tokenId).collect(java.util.stream.Collectors.toSet()))
                .hasSize(8);
        assertThat(queued.stream().map(JdbcBulkCapacityIssuer.Token::tokenId).collect(java.util.stream.Collectors.toSet()))
                .hasSize(80);
        assertThat(active.stream().collect(java.util.stream.Collectors.groupingBy(
                JdbcBulkCapacityIssuer.Token::tenantId, java.util.stream.Collectors.counting()))
                .values()).allMatch(count -> count <= 2);
        assertThat(queued.stream().collect(java.util.stream.Collectors.groupingBy(
                JdbcBulkCapacityIssuer.Token::tenantId, java.util.stream.Collectors.counting()))
                .values()).allMatch(count -> count <= 20);
    }

    @Test
    void multipleBindingsOfOneTenantShareTheSameTwoActiveRights() {
        enroll("same", "binding-a");
        enroll("same", "binding-b");
        issuer.requestCapacity(request("same", "binding-a", JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 2));
        issuer.requestCapacity(request("same", "binding-b", JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 2));
        var tokens = List.of(issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow(),
                issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow());
        assertThat(tokens).allMatch(token -> token.tenantId().equals("same"));
        assertThat(issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE)).isEmpty();
        assertThat(new JdbcTemplate(owner).queryForObject("""
                select count(*) from praxis_bulk_capacity.capacity_token
                where tenant_id='same' and capacity_class='ACTIVE'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void pendingSecondUuidIsBackpressureWithoutInsertOrUnboundedHistory() {
        enroll("a", "binding-a");
        var first = request("a", "binding-a", JdbcBulkCapacityIssuer.CapacityClass.QUEUE, 2);
        issuer.requestCapacity(first);
        assertThatThrownBy(() -> issuer.requestCapacity(
                request("a", "binding-a", JdbcBulkCapacityIssuer.CapacityClass.QUEUE, 2)))
                .isInstanceOfSatisfying(JdbcBulkCapacityIssuer.CapacityFailure.class,
                        error -> assertThat(error.reason()).isEqualTo(JdbcBulkCapacityIssuer.Failure.PENDING));
        assertThat(new JdbcTemplate(owner).queryForObject(
                "select count(*) from praxis_bulk_capacity.capacity_request", Integer.class)).isEqualTo(1);
        assertThat(issuer.findIssue(first.requestId())).isPresent();
    }

    @Test
    void rollbackAfterIssuingInsideTheActualAuthorityTransactionRestoresTokenRequestAndCursor() {
        enroll("a", "binding-a");
        var request = request("a", "binding-a", JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1);
        issuer.requestCapacity(request);
        assertThatThrownBy(() -> allocator.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "select * from praxis_bulk_capacity.allocate_next(?,?)")) {
                statement.setString(1, DEPLOYMENT);
                statement.setString(2, "ACTIVE");
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("token_ordinal")).isEqualTo(1);
                }
            }
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);
        var sql = new JdbcTemplate(owner);
        assertThat(sql.queryForObject(
                "select count(*) from praxis_bulk_capacity.capacity_token", Integer.class)).isZero();
        assertThat(sql.queryForObject(
                "select issued_count from praxis_bulk_capacity.capacity_request where request_id=?",
                Integer.class, request.requestId())).isZero();
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk_capacity.fairness_cursor
                where deployment_id=? and capacity_class='ACTIVE'
                """, Integer.class, DEPLOYMENT)).isZero();
        var committed = issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE).orElseThrow();
        assertThat(committed.ordinal()).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select cursor_epoch from praxis_bulk_capacity.fairness_cursor
                where deployment_id=? and capacity_class='ACTIVE'
                """, Long.class, DEPLOYMENT)).isEqualTo(1L);
        assertThat(issuer.findIssue(request.requestId()).orElseThrow().tokenIds())
                .containsExactly(committed.tokenId());
    }

    @Test
    void twoIndependentConnectionsSerializeAtTheDeploymentBucketWithoutOverIssuing() throws Exception {
        for (int index = 1; index <= 5; index++) {
            String tenant = "t" + index;
            enroll(tenant, "b" + index);
            issuer.requestCapacity(request(tenant, "b" + index,
                    JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 2));
        }
        var other = new JdbcBulkCapacityIssuer(
                infrastructure(allocatorSource, BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR), reader);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<List<UUID>> first = () -> allocateUntilCap(issuer, start);
            Callable<List<UUID>> second = () -> allocateUntilCap(other, start);
            var one = pool.submit(first);
            var two = pool.submit(second);
            start.countDown();
            var all = new HashSet<UUID>(one.get(30, TimeUnit.SECONDS));
            all.addAll(two.get(30, TimeUnit.SECONDS));
            assertThat(all).hasSize(8);
            assertThat(new JdbcTemplate(owner).queryForObject(
                    "select count(*) from praxis_bulk_capacity.capacity_token", Integer.class)).isEqualTo(8);
        }
    }

    @Test
    void outerDomainTransactionAndWrongRoleOrEpochCannotAcquireCapacity() {
        enroll("a", "binding-a");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(owner));
        assertThatThrownBy(() -> tx.execute(status -> {
            issuer.requestCapacity(request("a", "binding-a",
                    JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1));
            return null;
        })).isInstanceOfSatisfying(JdbcBulkCapacityIssuer.CapacityFailure.class,
                error -> assertThat(error.reason()).isEqualTo(JdbcBulkCapacityIssuer.Failure.UNAVAILABLE));
        assertThatThrownBy(() -> new BulkCapacityAuthorityInfrastructure(allocatorSource,
                new DataSourceTransactionManager(allocatorSource), identity,
                BulkCapacityAuthorityInfrastructure.Access.READER, roles))
                .isInstanceOf(IllegalStateException.class);
        var stale = new BulkCapacityAuthorityMigrator.Identity(
                DEPLOYMENT, "test", identity.authorityId(), 8);
        assertThatThrownBy(() -> new BulkCapacityAuthorityInfrastructure(allocatorSource,
                new DataSourceTransactionManager(allocatorSource), stale,
                BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR, roles))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new JdbcTemplate(owner).queryForObject(
                "select count(*) from praxis_bulk_capacity.capacity_request", Integer.class)).isZero();
    }

    @Test
    void aThirdPartyMemberOfAnyCallerLoginCannotInheritCapacityAuthority() {
        enroll("a", "binding-a");
        var admin = new JdbcTemplate(owner);
        admin.execute("create role cap_rogue_member login");
        try {
            for (String caller : List.of(PROVISIONER, ALLOCATOR, READER)) {
                admin.execute("grant " + caller + " to cap_rogue_member");
                try {
                    if (caller.equals(PROVISIONER)) {
                        assertThatThrownBy(() -> provisioner.enrollBinding("rogue", "binding-rogue", 1))
                                .isInstanceOf(IllegalStateException.class);
                    } else if (caller.equals(ALLOCATOR)) {
                        assertThatThrownBy(() -> issuer.requestCapacity(request("a", "binding-a",
                                JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1)))
                                .isInstanceOfSatisfying(JdbcBulkCapacityIssuer.CapacityFailure.class,
                                        error -> assertThat(error.reason())
                                                .isEqualTo(JdbcBulkCapacityIssuer.Failure.UNAVAILABLE));
                    } else {
                        assertThatThrownBy(() -> issuer.findIssue(UUID.randomUUID()))
                                .isInstanceOfSatisfying(JdbcBulkCapacityIssuer.CapacityFailure.class,
                                        error -> assertThat(error.reason())
                                                .isEqualTo(JdbcBulkCapacityIssuer.Failure.UNAVAILABLE));
                    }
                    assertThat(admin.queryForObject(
                            "select count(*) from praxis_bulk_capacity.capacity_request", Integer.class)).isZero();
                    assertThat(admin.queryForObject(
                            "select count(*) from praxis_bulk_capacity.capacity_token", Integer.class)).isZero();
                    assertThat(admin.queryForObject(
                            "select count(*) from praxis_bulk_capacity.capacity_binding", Integer.class)).isEqualTo(1);
                } finally {
                    admin.execute("revoke " + caller + " from cap_rogue_member");
                }
            }
        } finally {
            admin.execute("drop role cap_rogue_member");
        }
        var legitimate = request("a", "binding-a", JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1);
        issuer.requestCapacity(legitimate);
        assertThat(issuer.findIssue(legitimate.requestId())).isPresent();
    }

    @Test
    void callersHaveOnlyFunctionsAndCannotMutateTablesOrCreateLocalObjects() {
        assertThatThrownBy(() -> new JdbcTemplate(allocatorSource).execute("""
                insert into praxis_bulk_capacity.capacity_token(token_id,request_id,token_ordinal,
                    deployment_id,tenant_id,binding_id,capacity_class,binding_generation)
                values (gen_random_uuid(),gen_random_uuid(),1,'x','y','z','ACTIVE',1)
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new JdbcTemplate(allocatorSource)
                .execute("create table public.capacity_rogue(id integer)"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new JdbcTemplate(allocatorSource)
                .execute("create temporary table capacity_rogue(id integer)"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new JdbcTemplate(readerSource)
                .execute("select praxis_bulk_capacity.allocate_next('capacity-deployment','ACTIVE')"))
                .isInstanceOf(Exception.class);
        enroll("a", "binding-a");
        issuer.requestCapacity(request("a", "binding-a", JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 1));
    }

    private BulkCapacityAuthorityInfrastructure infrastructure(DataSource source,
            BulkCapacityAuthorityInfrastructure.Access access) {
        return new BulkCapacityAuthorityInfrastructure(source,
                new DataSourceTransactionManager(source), identity, access, roles);
    }

    private DataSource login(String role) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(role, "postgres"), role, "");
    }

    private void enroll(String tenant, String binding) {
        provisioner.enrollBinding(tenant, binding, 1);
    }

    private JdbcBulkCapacityIssuer.Request request(String tenant, String binding,
            JdbcBulkCapacityIssuer.CapacityClass kind, int count) {
        return new JdbcBulkCapacityIssuer.Request(UUID.randomUUID(), DEPLOYMENT, tenant, binding, kind, count);
    }

    private static List<UUID> allocateUntilCap(JdbcBulkCapacityIssuer issuer, CountDownLatch start)
            throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Workers did not start together");
        var tokens = new ArrayList<UUID>();
        for (int index = 0; index < 9; index++) {
            var next = issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
            if (next.isEmpty()) break;
            tokens.add(next.orElseThrow().tokenId());
        }
        return List.copyOf(tokens);
    }

    private static final class DeliberateRollback extends RuntimeException { }
}
