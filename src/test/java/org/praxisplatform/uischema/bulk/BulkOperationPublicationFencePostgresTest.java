package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PostgreSQL publication fences; fixture digests do not prove production OAS capture. */
class BulkOperationPublicationFencePostgresTest {
    private static final String NS = "publication-fence";
    private static final String PEER = "publication-fence-peer";
    private static final String OTHER = "publication-fence-other";
    private static final String DEP = "deployment-test";
    private static final String OP = "resource.bulk.confirm";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String NEXT = "sha256:" + "b".repeat(64);
    private static final String CONTROL = "fence_control";
    private static final Map<String, String> BINDINGS = Map.of(NS, DEP, PEER, DEP, OTHER, "other-deployment");
    private static final BulkExecutionRoleConfiguration ROLES = new BulkExecutionRoleConfiguration(
            "postgres", Set.of("bulk_runtime_test", "durable_runtime"), Set.of(), Set.of(CONTROL));
    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource runtime;
    private DataSource control;
    private JdbcTemplate sql;

    @BeforeEach
    void setup() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase(); sql = new JdbcTemplate(owner);
        BulkPostgresTestSupport.migrate(owner, BINDINGS);
        BulkPostgresTestSupport.grantControlRole(owner, CONTROL);
        BulkExecutionMigrator.migrate(owner, BINDINGS, ROLES, List.of(
                new BulkOperationControlIdentity(NS, OP), new BulkOperationControlIdentity(PEER, OP),
                new BulkOperationControlIdentity(OTHER, OP)));
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        control = new DriverManagerDataSource(postgres.getJdbcUrl(CONTROL, "postgres"), CONTROL, "");
        BulkExecutionMigrator.validate(owner, ROLES);
    }

    @AfterEach
    void close() throws Exception { if (postgres != null) postgres.close(); }

    @Test
    void denyOnlyBootstrapAndRemovedLegacySignatureCannotComposeReady() throws Exception {
        assertThat(sql.queryForObject("select to_regprocedure('praxis_bulk.transition_operation_control(text,text,bigint,text,text,text)') is null", Boolean.class)).isTrue();
        assertThat(locked(NS).state()).isEqualTo("UNCOMPOSED");
        assertThatThrownBy(() -> ready(NS, 0, 2L, DIGEST)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("55000");
        assertThatThrownBy(() -> ready(NS, 0, null, null)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("22023");
        assertThat(locked(NS).generation()).isZero();
    }

    @Test
    void exactPublishedTupleIsStoredAtomicallyWithReady() throws Exception {
        publish();
        assertThat(ready(NS, 0, 2L, DIGEST)).isEqualTo(new JdbcBulkOperationControl.Transition(true, 1));
        assertThat(sql.queryForMap("select state,generation,publication_generation,publication_document_digest from praxis_bulk.praxis_bulk_operation_control where namespace_id=?", NS))
                .containsEntry("state", "READY").containsEntry("generation", 1L)
                .containsEntry("publication_generation", 2L).containsEntry("publication_document_digest", DIGEST);
        assertThat(locked(NS).ready()).isTrue();
        BulkExecutionMigrator.validate(owner, ROLES);
    }

    @Test
    void wrongGenerationAndDigestCannotMutateControl() throws Exception {
        publish();
        assertThatThrownBy(() -> ready(NS, 0, 1L, DIGEST)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("55000");
        assertThatThrownBy(() -> ready(NS, 0, 2L, NEXT)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("55000");
        assertThat(locked(NS)).isEqualTo(new JdbcBulkOperationControl.Snapshot("UNCOMPOSED", 0, null, null));
    }

    @Test
    void staleControlCasReturnsCurrentGenerationWithoutChangingTheTuple() throws Exception {
        publish(); ready(NS, 0, 2L, DIGEST);
        assertThat(ready(NS, 0, 2L, DIGEST)).isEqualTo(new JdbcBulkOperationControl.Transition(false, 1));
        assertThat(sql.queryForObject("select publication_document_digest from praxis_bulk.praxis_bulk_operation_control where namespace_id=?", String.class, NS)).isEqualTo(DIGEST);
    }

    @Test
    void twoPhysicalCasTransactionsHaveExactlyOneWinner() throws Exception {
        publish();
        var start = new CountDownLatch(1); var entered = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> concurrentReady(start, entered));
            var second = pool.submit(() -> concurrentReady(start, entered));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var one = first.get(5, TimeUnit.SECONDS); var two = second.get(5, TimeUnit.SECONDS);
            assertThat(one.pid()).isNotEqualTo(two.pid());
            assertThat(List.of(one.transition().applied(), two.transition().applied())).containsExactlyInAnyOrder(true, false);
            assertThat(one.transition().generation()).isEqualTo(1); assertThat(two.transition().generation()).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void oldPublicationCannotReviveControlAfterSuspendAndRepublish() throws Exception {
        publish(); ready(NS, 0, 2L, DIGEST);
        global(NS, DEP, 2, JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
        global(NS, DEP, 3, JdbcBulkOpenApiPublication.Target.PUBLISHED, NEXT);
        assertThatThrownBy(() -> ready(NS, 2, 2L, DIGEST)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("55000");
        assertThat(locked(NS).state()).isEqualTo("SUSPENDED");
        assertThat(ready(NS, 2, 4L, NEXT).applied()).isTrue();
        assertThat(locked(NS).generation()).isEqualTo(3);
    }

    @Test
    void namespaceBootstrappedAfterSuspensionCannotUseOldPublication() throws Exception {
        publish(); global(NS, DEP, 2, JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
        String added = "publication-late";
        var bindings = new java.util.HashMap<>(BINDINGS); bindings.put(added, DEP);
        BulkExecutionMigrator.migrate(owner, bindings, ROLES, List.of(new BulkOperationControlIdentity(added, OP)));
        assertThat(locked(added).state()).isEqualTo("UNCOMPOSED");
        assertThatThrownBy(() -> ready(added, 0, 2L, DIGEST)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("55000");
        assertThat(BulkPostgresTestSupport.publication(owner, added).generation()).isEqualTo(3);
    }

    @Test
    void globalSuspensionClearsBothNamespaceTuplesAndPreservesOtherDeployment() throws Exception {
        publish(); ready(NS, 0, 2L, DIGEST); ready(PEER, 0, 2L, DIGEST);
        BulkPostgresTestSupport.ready(owner, OTHER, OP);
        global(PEER, DEP, 2, JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
        assertThat(sql.queryForList("select state from praxis_bulk.praxis_bulk_operation_control where namespace_id in (?,?) order by namespace_id", String.class, NS, PEER)).containsExactly("SUSPENDED", "SUSPENDED");
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control where namespace_id in (?,?) and publication_generation is null and publication_document_digest is null", Integer.class, NS, PEER)).isEqualTo(2);
        assertThat(locked(OTHER).ready()).isTrue();
        assertThat(BulkPostgresTestSupport.publication(owner, OTHER).published()).isTrue();
    }

    @Test
    void newNamespaceBootstrapWaitsForConcurrentGlobalSuspensionAndCannotComposeStaleReady() throws Exception {
        publish(); ready(NS, 0, 2L, DIGEST); ready(PEER, 0, 2L, DIGEST);
        String added = "publication-concurrent-bootstrap";
        var bindings = new java.util.HashMap<>(BINDINGS); bindings.put(added, DEP);
        String application = "publication-fence-bootstrap";
        var bootstrapSource = boundedOwnerSource(application);
        var started = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try (var suspender = control.getConnection()) {
            suspender.setAutoCommit(false); timeout(suspender);
            int suspenderPid = pid(suspender);
            assertThat(JdbcBulkOpenApiPublication.transition(suspender, NS, DEP, 2,
                    JdbcBulkOpenApiPublication.Target.SUSPENDED, null).applied()).isTrue();
            // The suspension has invalidated existing READY rows but is not yet committed.
            // A new namespace is a phantom for namespace SHARE locks; its bootstrap must
            // still acquire the deployment global lock before any new operation control.
            var bootstrap = pool.submit(() -> {
                started.countDown();
                return BulkExecutionMigrator.migrate(bootstrapSource, bindings, ROLES,
                        List.of(new BulkOperationControlIdentity(added, OP)));
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            int bootstrapPid = awaitPublicationWait(application, suspenderPid);
            assertThat(bootstrapPid).isNotEqualTo(suspenderPid);
            assertThat(bootstrap.isDone()).isFalse();
            suspender.commit();
            assertThat(bootstrap.get(5, TimeUnit.SECONDS)).isZero();
        } finally {
            pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(locked(added)).isEqualTo(new JdbcBulkOperationControl.Snapshot("UNCOMPOSED", 0, null, null));
        var observed = BulkPostgresTestSupport.publication(owner, added);
        assertThat(observed.state()).isEqualTo("SUSPENDED");
        assertThat(observed.generation()).isEqualTo(3); assertThat(observed.documentDigest()).isNull();
        assertThatThrownBy(() -> ready(added, 0, 2L, DIGEST)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("55000");
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_operation_control c
                  join praxis_bulk.praxis_bulk_namespace_binding b using(namespace_id)
                 where b.deployment_id=? and c.state='READY'
                """, Integer.class, DEP)).isZero();
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_operation_control
                 where namespace_id in (?,?) and state='SUSPENDED' and generation=2
                   and publication_generation is null and publication_document_digest is null
                """, Integer.class, NS, PEER)).isEqualTo(2);
        BulkExecutionMigrator.validate(owner, ROLES);
    }

    @Test
    void twoDirectPublishersSerializeWithoutShareToUpdateConversionOrStaleReady() throws Exception {
        publish(); ready(NS, 0, 2L, DIGEST); ready(PEER, 0, 2L, DIGEST);
        String application = "publication-fence-publisher";
        var secondSource = boundedControlSource(application);
        var started = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try (var first = control.getConnection()) {
            first.setAutoCommit(false); timeout(first);
            int firstPid = pid(first);
            // Both publishers start with CAS, acquiring namespace SHARE -> global UPDATE.
            // Neither takes its own global SHARE observation and then upgrades it.
            assertThat(JdbcBulkOpenApiPublication.transition(first, NS, DEP, 2,
                    JdbcBulkOpenApiPublication.Target.SUSPENDED, null).applied()).isTrue();
            var second = pool.submit(() -> tx(secondSource, c -> {
                int secondPid = pid(c); started.countDown();
                var suspended = JdbcBulkOpenApiPublication.transition(c, PEER, DEP, 2,
                        JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
                if (suspended.applied()) throw new AssertionError("stale publisher cannot gain publication ownership");
                return new ConcurrentPublication(secondPid, suspended);
            }));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            int blockedPid = awaitPublicationWait(application, firstPid);
            assertThat(blockedPid).isNotEqualTo(firstPid); assertThat(second.isDone()).isFalse();
            var published = JdbcBulkOpenApiPublication.transition(first, NS, DEP, 3,
                    JdbcBulkOpenApiPublication.Target.PUBLISHED, NEXT);
            assertThat(published).isEqualTo(new JdbcBulkOpenApiPublication.Transition(true, 4));
            first.commit();
            var stale = second.get(5, TimeUnit.SECONDS);
            assertThat(stale.pid()).isEqualTo(blockedPid);
            assertThat(stale.transition()).isEqualTo(new JdbcBulkOpenApiPublication.Transition(false, 4));
        } finally {
            pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        var observed = BulkPostgresTestSupport.publication(owner, NS);
        assertThat(observed.state()).isEqualTo("PUBLISHED");
        assertThat(observed.generation()).isEqualTo(4); assertThat(observed.documentDigest()).isEqualTo(NEXT);
        assertThat(locked(NS)).isEqualTo(new JdbcBulkOperationControl.Snapshot("SUSPENDED", 2, null, null));
        assertThat(locked(PEER)).isEqualTo(new JdbcBulkOperationControl.Snapshot("SUSPENDED", 2, null, null));
        assertThatThrownBy(() -> ready(NS, 2, 2L, DIGEST)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("55000");
        assertThat(ready(NS, 2, 4L, NEXT)).isEqualTo(new JdbcBulkOperationControl.Transition(true, 3));
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_operation_control c
                  join praxis_bulk.praxis_bulk_namespace_binding b using(namespace_id)
                  join praxis_bulk.praxis_bulk_openapi_publication p using(deployment_id)
                 where c.state='READY' and (p.state<>'PUBLISHED'
                    or c.publication_generation is distinct from p.generation
                    or c.publication_document_digest is distinct from p.document_digest)
                """, Integer.class)).isZero();
        BulkExecutionMigrator.validate(owner, ROLES);
    }

    @Test
    void suspendedControlsRemainReadableForReplayAndCleanup() throws Exception {
        publish(); ready(NS, 0, 2L, DIGEST); global(NS, DEP, 2, JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
        assertThat(locked(NS)).isEqualTo(new JdbcBulkOperationControl.Snapshot("SUSPENDED", 2, null, null));
        JdbcBulkOperationControl.Transition repeated = tx(control, c -> JdbcBulkOperationControl.transition(c, NS, OP, 2,
                JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null));
        assertThat(repeated).isEqualTo(new JdbcBulkOperationControl.Transition(true, 3));
    }

    @Test
    void forgedReadyTupleIsFilteredAndCatalogValidationFailsClosed() throws Exception {
        publish(); ready(NS, 0, 2L, DIGEST);
        corruptControlAsFixtureOwner(owner, "update praxis_bulk.praxis_bulk_operation_control set publication_document_digest=? where namespace_id=?", NEXT, NS);
        assertThat(locked(NS)).isNull();
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, ROLES)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runtimeCannotTransitionOrWritePublicationTuple() throws Exception {
        publish();
        assertThatThrownBy(() -> tx(runtime, c -> JdbcBulkOperationControl.transition(c, NS, OP, 0,
                JdbcBulkOperationControl.Target.READY, DIGEST, "r1", 2L, DIGEST)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
        assertThatThrownBy(() -> tx(runtime, c -> {
            try (var statement = c.prepareStatement("update praxis_bulk.praxis_bulk_operation_control set publication_generation=2 where namespace_id=?")) {
                statement.setString(1, NS); return statement.executeUpdate();
            }
        })).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
    }

    @Test
    void admissionRetainsGlobalLockUntilItsPhysicalTransactionCompletes() throws Exception {
        publish(); ready(NS, 0, 2L, DIGEST);
        try (var admission = runtime.getConnection(); var publisher = control.getConnection()) {
            admission.setAutoCommit(false); publisher.setAutoCommit(false); timeout(admission); timeout(publisher);
            assertThat(pid(admission)).isNotEqualTo(pid(publisher));
            assertThat(JdbcBulkOperationControl.lockForAdmission(admission, NS, OP).ready()).isTrue();
            try (var statement = publisher.createStatement()) { statement.execute("set local lock_timeout='100ms'"); }
            assertThatThrownBy(() -> JdbcBulkOpenApiPublication.transition(publisher, NS, DEP, 2,
                    JdbcBulkOpenApiPublication.Target.SUSPENDED, null)).isInstanceOf(SQLException.class)
                    .extracting("SQLState").isEqualTo("55P03");
            publisher.rollback(); admission.commit();
            assertThat(JdbcBulkOpenApiPublication.transition(publisher, NS, DEP, 2,
                    JdbcBulkOpenApiPublication.Target.SUSPENDED, null).applied()).isTrue(); publisher.commit();
        }
    }

    @Test
    void readyCasRetainsGlobalLockUntilCommitBeforeSuspensionCanInvalidateIt() throws Exception {
        publish();
        try (var composer = control.getConnection(); var publisher = control.getConnection()) {
            composer.setAutoCommit(false); publisher.setAutoCommit(false); timeout(composer); timeout(publisher);
            assertThat(pid(composer)).isNotEqualTo(pid(publisher));
            assertThat(JdbcBulkOperationControl.transition(composer, NS, OP, 0, JdbcBulkOperationControl.Target.READY,
                    DIGEST, "r1", 2L, DIGEST).applied()).isTrue();
            try (var statement = publisher.createStatement()) { statement.execute("set local lock_timeout='100ms'"); }
            assertThatThrownBy(() -> JdbcBulkOpenApiPublication.transition(publisher, NS, DEP, 2,
                    JdbcBulkOpenApiPublication.Target.SUSPENDED, null)).isInstanceOf(SQLException.class)
                    .extracting("SQLState").isEqualTo("55P03");
            publisher.rollback(); composer.commit();
            JdbcBulkOpenApiPublication.transition(publisher, NS, DEP, 2, JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
            publisher.commit();
        }
        assertThat(locked(NS).ready()).isFalse();
    }

    @Test
    void drainedV14UpgradeInvalidatesPublishedAndReadyButPreservesDenyOnlyGenerations() throws Exception {
        try (var prior = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start()) {
            DataSource source = prior.getPostgresDatabase(); var jdbc = legacyV14(source);
            jdbc.update("update praxis_bulk.praxis_bulk_openapi_publication set state='PUBLISHED',generation=2,document_digest=? where deployment_id=?", DIGEST, DEP);
            BulkPostgresTestSupport.ready(source, NS, OP);
            jdbc.update("update praxis_bulk.praxis_bulk_openapi_publication set state='SUSPENDED',generation=7 where deployment_id='other-deployment'");
            Integer checksum = jdbc.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history where version='14'", Integer.class);
            assertThat(BulkExecutionMigrator.migrate(source, BINDINGS)).isEqualTo(3);
            assertThat(jdbc.queryForMap("select state,generation,publication_generation,publication_document_digest from praxis_bulk.praxis_bulk_operation_control where namespace_id=?", NS)).containsEntry("state", "SUSPENDED").containsEntry("generation", 2L).containsEntry("publication_generation", null).containsEntry("publication_document_digest", null);
            assertThat(jdbc.queryForObject("select generation from praxis_bulk.praxis_bulk_openapi_publication where deployment_id=?", Long.class, DEP)).isEqualTo(3);
            assertThat(jdbc.queryForObject("select generation from praxis_bulk.praxis_bulk_openapi_publication where deployment_id='other-deployment'", Long.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history where version='14'", Integer.class)).isEqualTo(checksum);
            BulkExecutionMigrator.validate(source);
        }
    }

    @Test
    void cutoverOverflowRollsBackDdlAndLegacyStatesWithoutResidualMemberships() throws Exception {
        try (var prior = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start()) {
            DataSource source = prior.getPostgresDatabase(); var jdbc = legacyV14(source);
            jdbc.update("update praxis_bulk.praxis_bulk_openapi_publication set state='PUBLISHED',generation=?,document_digest=? where deployment_id=?", Long.MAX_VALUE, DIGEST, DEP);
            BulkPostgresTestSupport.ready(source, NS, OP);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(source, BINDINGS)).isInstanceOf(RuntimeException.class).hasMessageContaining("cannot advance");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='praxis_bulk' and table_name='praxis_bulk_operation_control' and column_name='publication_generation'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select to_regprocedure('praxis_bulk.transition_operation_control(text,text,bigint,text,text,text)') is not null", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("select state from praxis_bulk.praxis_bulk_operation_control where namespace_id=?", String.class, NS)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("select generation from praxis_bulk.praxis_bulk_openapi_publication where deployment_id=?", Long.class, DEP)).isEqualTo(Long.MAX_VALUE);
            assertThat(jdbc.queryForObject("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid where r.rolname in ('praxis_bulk_control_owner','praxis_bulk_retention_owner')", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select has_schema_privilege('praxis_bulk_control_owner','praxis_bulk','CREATE')", Boolean.class)).isFalse();
        }
    }

    @Test
    void legacyReadyOverflowRollsBackWithoutBumpingDenyOnlyGlobal() throws Exception {
        try (var prior = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start()) {
            DataSource source = prior.getPostgresDatabase(); var jdbc = legacyV14(source);
            BulkPostgresTestSupport.ready(source, NS, OP);
            corruptControlAsFixtureOwner(source, "update praxis_bulk.praxis_bulk_operation_control set generation=? where namespace_id=?", Long.MAX_VALUE, NS);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(source, BINDINGS)).isInstanceOf(RuntimeException.class).hasMessageContaining("cannot advance");
            assertThat(jdbc.queryForObject("select generation from praxis_bulk.praxis_bulk_openapi_publication where deployment_id=?", Long.class, DEP)).isZero();
            assertThat(jdbc.queryForObject("select generation from praxis_bulk.praxis_bulk_operation_control where namespace_id=?", Long.class, NS)).isEqualTo(Long.MAX_VALUE);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='praxis_bulk' and table_name='praxis_bulk_operation_control' and column_name='publication_generation'", Integer.class)).isZero();
        }
    }

    @Test
    void unsafeDefinerOwnerPreflightFailsBeforeCutoverDdl() throws Exception {
        try (var prior = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start()) {
            DataSource source = prior.getPostgresDatabase(); var jdbc = legacyV14(source);
            jdbc.execute("grant praxis_bulk_control_owner to postgres");
            try {
                assertThatThrownBy(() -> BulkExecutionMigrator.migrate(source, BINDINGS)).isInstanceOf(RuntimeException.class).hasMessageContaining("topology is unsafe");
                assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='praxis_bulk' and table_name='praxis_bulk_operation_control' and column_name='publication_generation'", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select to_regprocedure('praxis_bulk.transition_operation_control(text,text,bigint,text,text,text)') is not null", Boolean.class)).isTrue();
            } finally { jdbc.execute("revoke praxis_bulk_control_owner from postgres"); }
        }
    }

    /** Deliberate owner-only corruption fixture, restored in the same physical transaction. */
    private static void corruptControlAsFixtureOwner(DataSource source, String update, Object... arguments) throws Exception {
        tx(source, c -> {
            try (var ddl = c.createStatement()) { ddl.execute("alter table praxis_bulk.praxis_bulk_operation_control disable trigger praxis_bulk_operation_control_protect"); }
            try (var statement = c.prepareStatement(update)) {
                for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            } finally {
                try (var ddl = c.createStatement()) { ddl.execute("alter table praxis_bulk.praxis_bulk_operation_control enable trigger praxis_bulk_operation_control_protect"); }
            }
            return null;
        });
    }

    private static JdbcTemplate legacyV14(DataSource source) {
        Flyway.configure().dataSource(source).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .baselineOnMigrate(false).cleanDisabled(true).target("14").load().migrate();
        var jdbc = new JdbcTemplate(source);
        BINDINGS.forEach((ns, dep) -> jdbc.update("insert into praxis_bulk.praxis_bulk_namespace_binding values (?,?,clock_timestamp())", ns, dep));
        jdbc.update("insert into praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) values (?)", DEP);
        jdbc.update("insert into praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) values ('other-deployment')");
        jdbc.update("insert into praxis_bulk.praxis_bulk_openapi_publication(deployment_id,state,generation,document_digest,updated_at) values (?,'UNCOMPOSED',0,null,clock_timestamp()),('other-deployment','UNCOMPOSED',0,null,clock_timestamp())", DEP);
        return jdbc;
    }

    private DataSource boundedOwnerSource(String application) {
        return boundedSource("postgres", application);
    }
    private DataSource boundedControlSource(String application) {
        return boundedSource(CONTROL, application);
    }
    private DataSource boundedSource(String role, String application) {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(role, "postgres"), role, "");
        var properties = new java.util.Properties();
        properties.setProperty("ApplicationName", application);
        properties.setProperty("options", "-c statement_timeout=3000");
        source.setConnectionProperties(properties);
        return source;
    }

    /** Requires an actual PostgreSQL lock wait with the named physical blocker, not scheduling delay. */
    private int awaitPublicationWait(String application, int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            var waiting = sql.queryForList("""
                    select a.pid from pg_catalog.pg_stat_activity a
                     where a.application_name=? and a.wait_event_type='Lock'
                       and ? = any(pg_catalog.pg_blocking_pids(a.pid))
                       and (a.query like '%praxis_bulk_openapi_publication%'
                            or a.query like '%transition_openapi_publication%')
                    """, Integer.class, application, blockerPid);
            if (!waiting.isEmpty()) {
                assertThat(waiting).hasSize(1); return waiting.getFirst();
            }
            TimeUnit.MILLISECONDS.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("publication lock wait was not observed for " + application);
    }

    private void publish() throws Exception {
        global(NS, DEP, 0, JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
        global(NS, DEP, 1, JdbcBulkOpenApiPublication.Target.PUBLISHED, DIGEST);
    }
    private JdbcBulkOpenApiPublication.Transition global(String ns, String dep, long expected,
            JdbcBulkOpenApiPublication.Target target, String digest) throws Exception {
        return tx(control, c -> JdbcBulkOpenApiPublication.transition(c, ns, dep, expected, target, digest));
    }
    private JdbcBulkOperationControl.Transition ready(String ns, long expected, Long generation, String digest) throws Exception {
        return tx(control, c -> JdbcBulkOperationControl.transition(c, ns, OP, expected, JdbcBulkOperationControl.Target.READY,
                DIGEST, "r1", generation, digest));
    }
    private JdbcBulkOperationControl.Snapshot locked(String ns) throws Exception {
        return tx(runtime, c -> JdbcBulkOperationControl.lockForAdmission(c, ns, OP));
    }
    private ConcurrentResult concurrentReady(CountDownLatch start, CountDownLatch entered) throws Exception {
        return tx(control, c -> {
            int pid = pid(c); entered.countDown(); if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("CAS start timed out");
            return new ConcurrentResult(pid, JdbcBulkOperationControl.transition(c, NS, OP, 0,
                    JdbcBulkOperationControl.Target.READY, DIGEST, "r1", 2L, DIGEST));
        });
    }
    private static void timeout(Connection c) throws SQLException { try (var s = c.createStatement()) { s.execute("set local statement_timeout='3s'"); } }
    private static int pid(Connection c) throws SQLException { try (var s = c.createStatement(); var rows = s.executeQuery("select pg_backend_pid()")) { rows.next(); return rows.getInt(1); } }
    private static <T> T tx(DataSource source, SqlWork<T> work) throws Exception {
        try (var c = source.getConnection()) {
            c.setAutoCommit(false); timeout(c);
            try { T result = work.apply(c); c.commit(); return result; }
            catch (Exception | AssertionError failure) { c.rollback(); throw failure; }
        }
    }
    @FunctionalInterface private interface SqlWork<T> { T apply(Connection connection) throws Exception; }
    private record ConcurrentResult(int pid, JdbcBulkOperationControl.Transition transition) { }
    private record ConcurrentPublication(int pid, JdbcBulkOpenApiPublication.Transition transition) { }
}
