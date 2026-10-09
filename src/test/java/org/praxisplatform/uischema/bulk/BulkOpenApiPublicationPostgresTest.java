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

/** R1 is a preparatory ledger, not an admission/serving integration or an R2 readiness fence. */
class BulkOpenApiPublicationPostgresTest {
    private static final String NS = "publication-test";
    private static final String NS2 = "publication-peer";
    private static final String DEPLOYMENT = "deployment-test";
    private static final String A = "a.bulk.confirm";
    private static final String B = "b.bulk.confirm";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String CONTROL = "publication_control";
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
        owner = postgres.getPostgresDatabase();
        sql = new JdbcTemplate(owner);
        BulkPostgresTestSupport.migrate(owner, Map.of(NS, DEPLOYMENT, NS2, DEPLOYMENT));
        BulkPostgresTestSupport.grantControlRole(owner, CONTROL);
        BulkExecutionMigrator.migrate(owner, Map.of(NS, DEPLOYMENT, NS2, DEPLOYMENT), ROLES,
                List.of(new BulkOperationControlIdentity(NS, A), new BulkOperationControlIdentity(NS2, B)));
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        control = new DriverManagerDataSource(postgres.getJdbcUrl(CONTROL, "postgres"), CONTROL, "");
        BulkExecutionMigrator.validate(owner, ROLES);
    }

    @AfterEach
    void close() throws Exception { if (postgres != null) postgres.close(); }

    @Test
    void bootstrapIsDenyOnlyAndRerunPreservesAnImmutablePublishedIdentity() throws Exception {
        var initial = read();
        assertThat(initial.state()).isEqualTo("UNCOMPOSED");
        assertThat(initial.generation()).isZero();
        assertThat(initial.documentDigest()).isNull();
        assertThat(initial.published()).isFalse();
        assertThat(initial.updatedAt()).isNotNull();
        assertThatThrownBy(() -> publish(0, DIGEST)).isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("55000");
        assertThat(suspend(0)).isEqualTo(new JdbcBulkOpenApiPublication.Transition(true, 1));
        assertThat(publish(1, DIGEST)).isEqualTo(new JdbcBulkOpenApiPublication.Transition(true, 2));
        assertThat(BulkExecutionMigrator.migrate(owner, Map.of(NS, DEPLOYMENT, NS2, DEPLOYMENT), ROLES)).isZero();
        var retained = read();
        assertThat(retained.state()).isEqualTo("PUBLISHED");
        assertThat(retained.generation()).isEqualTo(2);
        assertThat(retained.documentDigest()).isEqualTo(DIGEST);
        assertThatThrownBy(() -> publish(2, "sha256:" + "b".repeat(64)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55000");
        assertThat(read()).isEqualTo(retained);
    }

    @Test
    void upgradeFromV13BootstrapsAnExistingNamespaceWithoutRewritingItsHistory() throws Exception {
        try (var prior = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start()) {
            var source = prior.getPostgresDatabase();
            var admin = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/praxis-bulk-migrations")
                    .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                    .baselineOnMigrate(false).cleanDisabled(true).target("13").load().migrate();
            // This synthetic V13 namespace must already have its durable deployment bucket.
            admin.update("insert into praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) values (?)", DEPLOYMENT);
            admin.update("insert into praxis_bulk.praxis_bulk_namespace_binding values (?, ?, clock_timestamp())", NS, DEPLOYMENT);
            BulkPostgresTestSupport.ready(source, NS, A);
            var checksum = admin.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history where version='13'", Integer.class);
            var originalHistory = admin.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
            var originalBinding = admin.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding");
            var originalBucket = admin.queryForList("select * from praxis_bulk.praxis_bulk_deployment_bucket");
            assertThat(BulkExecutionMigrator.migrate(source, Map.of(NS, DEPLOYMENT))).isEqualTo(7);
            assertThat(admin.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history where version='13'", Integer.class))
                    .isEqualTo(checksum);
            assertThat(admin.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                    + "where version is null or version::integer<=13 order by installed_rank")).isEqualTo(originalHistory);
            assertThat(admin.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding")).isEqualTo(originalBinding);
            assertThat(admin.queryForList("select * from praxis_bulk.praxis_bulk_deployment_bucket")).isEqualTo(originalBucket);
            assertThat(admin.queryForMap("select state,generation,document_digest from praxis_bulk.praxis_bulk_openapi_publication"))
                    .containsEntry("state", "UNCOMPOSED").containsEntry("generation", 0L).containsEntry("document_digest", null);
            assertThatThrownBy(() -> tx(source, c -> JdbcBulkOpenApiPublication.transition(c, NS, DEPLOYMENT, 0,
                    JdbcBulkOpenApiPublication.Target.PUBLISHED, DIGEST)))
                    .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55000");
            assertThat(admin.queryForObject("select state from praxis_bulk.praxis_bulk_operation_control", String.class)).isEqualTo("SUSPENDED");
            tx(source, c -> JdbcBulkOpenApiPublication.transition(c, NS, DEPLOYMENT, 0,
                    JdbcBulkOpenApiPublication.Target.SUSPENDED, null));
            assertThat(admin.queryForObject("select state from praxis_bulk.praxis_bulk_operation_control", String.class)).isEqualTo("SUSPENDED");
            assertThat(tx(source, c -> JdbcBulkOpenApiPublication.transition(c, NS, DEPLOYMENT, 1,
                    JdbcBulkOpenApiPublication.Target.PUBLISHED, DIGEST)).applied()).isTrue();
            BulkExecutionMigrator.validate(source);
        }
    }

    @Test
    void twoPhysicalConnectionsWithSameGenerationHaveExactlyOneCasWinner() throws Exception {
        suspend(0);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> concurrentPublish(start, ready, DIGEST));
            var second = pool.submit(() -> concurrentPublish(start, ready, "sha256:" + "b".repeat(64)));
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var one = first.get(5, TimeUnit.SECONDS);
            var two = second.get(5, TimeUnit.SECONDS);
            assertThat(one.pid()).isNotEqualTo(two.pid());
            assertThat(List.of(one.transition().applied(), two.transition().applied())).containsExactlyInAnyOrder(true, false);
            assertThat(one.transition().generation()).isEqualTo(2);
            assertThat(two.transition().generation()).isEqualTo(2);
            assertThat(read().documentDigest()).isEqualTo(one.transition().applied() ? one.digest() : two.digest());
            assertThat(suspend(1)).isEqualTo(new JdbcBulkOpenApiPublication.Transition(false, 2));
            assertThat(read().state()).isEqualTo("PUBLISHED");
        } finally {
            start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void suspensionInvalidatesEveryCurrentlyReadyOperationBeforeChangingGlobalIdentity() throws Exception {
        suspend(0); publish(1, DIGEST); readyOperations();
        assertThat(suspend(2)).isEqualTo(new JdbcBulkOpenApiPublication.Transition(true, 3));
        assertSuspendedOperations();
        assertThat(read().state()).isEqualTo("SUSPENDED");
        assertThat(read().documentDigest()).isNull();
        assertThat(suspend(3)).isEqualTo(new JdbcBulkOpenApiPublication.Transition(true, 4));
        assertSuspendedOperations(); // Repeated suspension does not bump already suspended operations.
        assertThat(publish(4, "sha256:" + "c".repeat(64))).isEqualTo(new JdbcBulkOpenApiPublication.Transition(true, 5));
    }

    @Test
    void removedLegacyCasCannotRepublishAfterGlobalSuspension() throws Exception {
        readyOperations(); suspend(2);
        assertSuspendedOperations();
        assertThat(sql.queryForObject("select to_regprocedure('praxis_bulk.transition_operation_control(text,text,bigint,text,text,text)') is null", Boolean.class)).isTrue();
        assertThatThrownBy(() -> tx(control, c -> JdbcBulkOperationControl.transition(c, NS, A, 2,
                JdbcBulkOperationControl.Target.READY, DIGEST, "structural-r2", 2L, "sha256:" + "0".repeat(64))))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55000");
        assertSuspendedOperations();
        assertThat(read().state()).isEqualTo("SUSPENDED");
    }

    @Test
    void failureAfterOperationUpdatesRollsBackTheEntireGlobalSuspension() throws Exception {
        suspend(0); publish(1, DIGEST); readyOperations();
        sql.execute("""
                create function public.reject_global_publication() returns trigger language plpgsql as $$
                begin
                  if NEW.state='SUSPENDED' then
                    if exists (select 1 from praxis_bulk.praxis_bulk_operation_control where state='READY') then
                      raise exception 'global update happened before operation invalidation';
                    end if;
                    raise exception 'late global write failure';
                  end if;
                  return NEW;
                end $$
                """);
        sql.execute("create trigger reject_global_publication before update on praxis_bulk.praxis_bulk_openapi_publication "
                + "for each row execute function public.reject_global_publication()");
        assertThatThrownBy(() -> suspend(2)).isInstanceOf(SQLException.class).hasMessageContaining("late global write failure");
        assertThat(read().state()).isEqualTo("PUBLISHED");
        assertThat(read().generation()).isEqualTo(2);
        assertThat(read().documentDigest()).isEqualTo(DIGEST);
        assertThat(sql.queryForList("select state from praxis_bulk.praxis_bulk_operation_control order by operation_id", String.class))
                .containsExactly("READY", "READY");
        assertThat(sql.queryForList("select generation from praxis_bulk.praxis_bulk_operation_control order by operation_id", Long.class))
                .containsExactly(1L, 1L);
        assertThat(sql.queryForList("select descriptor_fingerprint from praxis_bulk.praxis_bulk_operation_control order by operation_id", String.class))
                .containsExactly("sha256:" + "0".repeat(64), "sha256:" + "0".repeat(64));
        sql.execute("drop trigger reject_global_publication on praxis_bulk.praxis_bulk_openapi_publication");
        sql.execute("drop function public.reject_global_publication()");
        BulkExecutionMigrator.validate(owner, ROLES);
    }

    @Test
    void everyReadyGenerationIsPreflightedBeforeAnyInvalidation() throws Exception {
        suspend(0); publish(1, DIGEST); readyOperations();
        sql.execute("alter table praxis_bulk.praxis_bulk_operation_control disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_operation_control set generation=? where operation_id=?", Long.MAX_VALUE, B);
        sql.execute("alter table praxis_bulk.praxis_bulk_operation_control enable trigger user");
        assertThatThrownBy(() -> suspend(2)).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("22003");
        assertThat(read().generation()).isEqualTo(2);
        assertThat(sql.queryForList("select state from praxis_bulk.praxis_bulk_operation_control order by operation_id", String.class))
                .containsExactly("READY", "READY");
        assertThat(sql.queryForList("select generation from praxis_bulk.praxis_bulk_operation_control order by operation_id", Long.class))
                .containsExactly(1L, Long.MAX_VALUE);
    }

    @Test
    void globalGenerationOverflowIsRejectedWithoutTouchingOperations() throws Exception {
        readyOperations();
        sql.update("update praxis_bulk.praxis_bulk_openapi_publication set generation=?", Long.MAX_VALUE);
        assertThatThrownBy(() -> suspend(Long.MAX_VALUE)).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("22003");
        assertThat(read().generation()).isEqualTo(Long.MAX_VALUE);
        assertThat(sql.queryForList("select state from praxis_bulk.praxis_bulk_operation_control order by operation_id", String.class))
                .containsExactly("READY", "READY");
    }

    @Test
    void deploymentMismatchAndNonCanonicalArgumentsFailClosed() throws Exception {
        assertThatThrownBy(() -> tx(runtime, c -> JdbcBulkOpenApiPublication.lockForRead(c, NS, "other-deployment")))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55000");
        assertThatThrownBy(() -> tx(control, c -> JdbcBulkOpenApiPublication.transition(c, NS, "other-deployment", 0,
                JdbcBulkOpenApiPublication.Target.PUBLISHED, DIGEST)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55000");
        assertThatThrownBy(() -> tx(runtime, c -> JdbcBulkOpenApiPublication.lockForRead(c, " " + NS, DEPLOYMENT)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("22023");
        assertThatThrownBy(() -> publish(0, "SHA256:" + "a".repeat(64)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("22023");
        assertThatThrownBy(() -> tx(control, c -> JdbcBulkOpenApiPublication.transition(c, NS, DEPLOYMENT, 0,
                JdbcBulkOpenApiPublication.Target.SUSPENDED, DIGEST)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("22023");
        assertThatThrownBy(() -> sql.update("insert into praxis_bulk.praxis_bulk_openapi_publication values (?,'UNCOMPOSED',0,null,clock_timestamp())",
                "other-deployment")).isInstanceOf(RuntimeException.class).hasMessageContaining("foreign key constraint");
        assertThat(read().generation()).isZero();
    }

    @Test
    void runtimeAndControlCannotWriteRawLedgerOrUseEachOthersBoundary() throws Exception {
        assertThatThrownBy(() -> tx(runtime, c -> JdbcBulkOpenApiPublication.transition(c, NS, DEPLOYMENT, 0,
                JdbcBulkOpenApiPublication.Target.PUBLISHED, DIGEST)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
        assertThatThrownBy(() -> tx(control, c -> JdbcBulkOpenApiPublication.lockForRead(c, NS, DEPLOYMENT)))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
        for (var source : List.of(runtime, control)) {
            assertThatThrownBy(() -> tx(source, c -> { try (var s = c.createStatement()) {
                return s.executeUpdate("update praxis_bulk.praxis_bulk_openapi_publication set generation=1");
            } })).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
            assertThatThrownBy(() -> new JdbcTemplate(source).queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_openapi_publication", Integer.class)).isInstanceOf(RuntimeException.class);
        }
        assertThat(sql.queryForObject("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                + "join pg_roles member on member.oid=m.member where r.rolname='praxis_bulk_control_owner' "
                + "or member.rolname='praxis_bulk_control_owner'", Integer.class)).isZero();
        assertThat(sql.queryForObject("select has_schema_privilege('praxis_bulk_control_owner','praxis_bulk','CREATE')", Boolean.class)).isFalse();
        assertThat(read().state()).isEqualTo("UNCOMPOSED");
    }

    @Test
    void shareReaderRetainsNamespaceAndGlobalLocksUntilPhysicalTransactionEnds() throws Exception {
        suspend(0);
        try (var reader = runtime.getConnection(); var writer = control.getConnection()) {
            reader.setAutoCommit(false); writer.setAutoCommit(false);
            assertThat(pid(reader)).isNotEqualTo(pid(writer));
            JdbcBulkOpenApiPublication.lockForRead(reader, NS, DEPLOYMENT);
            assertThatThrownBy(() -> tx(owner, c -> { try (var s = c.createStatement()) {
                return s.execute("select * from praxis_bulk.praxis_bulk_namespace_binding for update nowait");
            } })).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55P03");
            assertThatThrownBy(() -> tx(owner, c -> { try (var s = c.createStatement()) {
                return s.execute("select * from praxis_bulk.praxis_bulk_openapi_publication for update nowait");
            } })).isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55P03");
            try (var s = writer.createStatement()) { s.execute("set local lock_timeout='100ms'"); }
            assertThatThrownBy(() -> JdbcBulkOpenApiPublication.transition(writer, NS, DEPLOYMENT, 1,
                    JdbcBulkOpenApiPublication.Target.PUBLISHED, DIGEST))
                    .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55P03");
            writer.rollback(); reader.commit();
            assertThat(JdbcBulkOpenApiPublication.transition(writer, NS, DEPLOYMENT, 1,
                    JdbcBulkOpenApiPublication.Target.PUBLISHED, DIGEST).applied()).isTrue();
            writer.commit();
        }
        assertThat(read().generation()).isEqualTo(2);
    }

    @Test
    void missingExecuteAndUnexpectedRawGrantsAreRejectedWithoutAutomaticRepair() throws Exception {
        sql.execute("revoke execute on function praxis_bulk.lock_openapi_publication(text,text) from bulk_runtime_test");
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner, Map.of(NS, DEPLOYMENT, NS2, DEPLOYMENT), ROLES))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("governed lifecycle function grants differ");
        assertThat(sql.queryForObject("select has_function_privilege('bulk_runtime_test', "
                + "'praxis_bulk.lock_openapi_publication(text,text)', 'EXECUTE')", Boolean.class)).isFalse();
        sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime_test");
        sql.execute("revoke execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) from " + CONTROL);
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, ROLES))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("governed lifecycle function grants differ");
        sql.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to " + CONTROL);
        sql.execute("grant update on praxis_bulk.praxis_bulk_openapi_publication to " + CONTROL);
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, ROLES)).isInstanceOf(IllegalStateException.class);
        sql.execute("revoke update on praxis_bulk.praxis_bulk_openapi_publication from " + CONTROL);
        BulkExecutionMigrator.validate(owner, ROLES);
    }


    @Test
    void namespacesInOneDeploymentShareGenerationAndDigestWhileAnotherDeploymentIsIsolated() throws Exception {
        suspend(0); publish(1, DIGEST);
        JdbcBulkOpenApiPublication.Snapshot peer = tx(runtime,
                c -> JdbcBulkOpenApiPublication.lockForRead(c, NS2, DEPLOYMENT));
        assertThat(peer).isEqualTo(read());
        JdbcBulkOpenApiPublication.Transition stale = tx(control,
                c -> JdbcBulkOpenApiPublication.transition(c, NS2, DEPLOYMENT, 1,
                        JdbcBulkOpenApiPublication.Target.SUSPENDED, null));
        assertThat(stale).isEqualTo(new JdbcBulkOpenApiPublication.Transition(false, 2));
        assertThat(read().documentDigest()).isEqualTo(DIGEST);
        BulkExecutionMigrator.migrate(owner,
                Map.of(NS, DEPLOYMENT, NS2, DEPLOYMENT, "isolated", "other-deployment"), ROLES,
                List.of(new BulkOperationControlIdentity("isolated", "isolated.confirm")));
        BulkPostgresTestSupport.ready(owner, "isolated", "isolated.confirm");
        readyOperations(); suspend(2);
        assertThat(sql.queryForList("select state from praxis_bulk.praxis_bulk_operation_control "
                + "where namespace_id in (?,?) order by operation_id", String.class, NS, NS2))
                .containsExactly("SUSPENDED", "SUSPENDED");
        assertThat(sql.queryForObject("select state from praxis_bulk.praxis_bulk_operation_control "
                + "where namespace_id='isolated'", String.class)).isEqualTo("READY");
        assertThat(tx(runtime, c -> JdbcBulkOpenApiPublication.lockForRead(c, "isolated", "other-deployment")).generation()).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_openapi_publication", Integer.class)).isEqualTo(2);
    }

    @Test
    void bootstrapRetryAndRealQuotaLockingDoNotReverseGlobalControlBucketOrder() throws Exception {
        readyOperations();
        var context = new BulkFingerprintContext(NS, "operator", "items",
                new org.praxisplatform.uischema.openapi.CanonicalOperationRef("admin", A, "/items/bulk/confirm", "POST"),
                "schema-r1", org.praxisplatform.uischema.action.ActionCollectionAtomicity.PER_ITEM);
        var empty = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var request = new BulkCommandEvaluationRequest<com.fasterxml.jackson.databind.JsonNode, String, com.fasterxml.jackson.databind.JsonNode>(
                BulkExecutionMode.SYNC, new BulkSelection<>(BulkSelectionMode.EXPLICIT,
                        List.of(new BulkTarget<>("target", "v1")), null, null), empty);
        var snapshot = BulkIntentSnapshot.command(context, BulkIdentityCodecs.strings(), request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy, com.fasterxml.jackson.databind.JsonNode::deepCopy);
        var created = java.time.Instant.now();
        var proposal = new BulkStoredProposal(java.util.UUID.randomUUID(), created, created.plusSeconds(60), snapshot,
                new BulkOperationControlExpectation(1, "sha256:" + "0".repeat(64), "structural-r1"));
        var infrastructure = new BulkExecutionInfrastructure(runtime,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(runtime), NS, DEPLOYMENT, ROLES);
        var pool = Executors.newSingleThreadExecutor();
        try (var quota = runtime.getConnection()) {
            quota.setAutoCommit(false);
            var scope = BulkQuotaLedger.lockProposal(quota, infrastructure, proposal, false, true);
            assertThat(scope.deploymentId()).isEqualTo(DEPLOYMENT);
            int quotaPid = pid(quota);
            var bootstrap = pool.submit(() -> BulkExecutionMigrator.migrate(owner,
                    Map.of(NS, DEPLOYMENT, NS2, DEPLOYMENT), ROLES));
            // V17's quota touch may block the bootstrap's identity INSERT ON CONFLICT
            // before it reaches the final FOR UPDATE. Observe the real quota blocker
            // and one of those exact bucket statements, not just scheduling delay.
            Integer bootstrapPid = null;
            String blockedStage = null;
            Map<String, Object> observedWait = Map.of();
            long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < expires && bootstrapPid == null) {
                var waiting = sql.queryForList("""
                        select a.pid, a.query, a.wait_event, pg_catalog.pg_blocking_pids(a.pid)::text as blockers
                          from pg_catalog.pg_stat_activity a
                         where a.wait_event_type='Lock'
                           and pg_catalog.pg_blocking_pids(a.pid)=array[?]::integer[]
                        """, quotaPid);
                for (var candidate : waiting) {
                    observedWait = candidate;
                    String query = ((String) candidate.get("query")).replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
                    boolean identityInsert = query.contains("insert into praxis_bulk.praxis_bulk_deployment_bucket(deployment_id)")
                            && query.contains("on conflict (deployment_id) do nothing");
                    boolean finalLock = query.contains("select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket")
                            && query.contains("order by deployment_id for update");
                    if (identityInsert && "transactionid".equals(candidate.get("wait_event")))
                        blockedStage = "V17 identity insert awaits quota transaction ID";
                    else if (finalLock) blockedStage = "final deployment FOR UPDATE";
                    if (blockedStage != null) {
                        bootstrapPid = ((Number) candidate.get("pid")).intValue();
                        break;
                    }
                }
                if (bootstrapPid == null) Thread.sleep(10);
            }
            assertThat(bootstrapPid).as("expected exact quota-blocked bucket statement; observed=%s", observedWait)
                    .isNotNull().isNotEqualTo(quotaPid);
            assertThat(blockedStage).as("observed=%s", observedWait)
                    .isIn("V17 identity insert awaits quota transaction ID", "final deployment FOR UPDATE");
            assertThat(observedWait.get("blockers")).as("%s: %s", blockedStage, observedWait)
                    .isEqualTo("{" + quotaPid + "}");
            System.out.printf("Observed bootstrap bucket wait: stage=%s waiterPid=%d blockerPids=%s sql=%s%n",
                    blockedStage, bootstrapPid, observedWait.get("blockers"),
                    ((String) observedWait.get("query")).replaceAll("\\s+", " "));
            assertThat(bootstrap.isDone()).isFalse();
            // A held quota transaction can acquire the new SHARE publication lock while
            // the bootstrap waits on its bucket: no reverse exclusive global/bucket cycle.
            assertThat(JdbcBulkOpenApiPublication.lockForRead(quota, NS, DEPLOYMENT).state()).isEqualTo("PUBLISHED");
            quota.commit();
            assertThat(bootstrap.get(5, TimeUnit.SECONDS)).isZero();
        } finally {
            pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void unsafePreMigrationOwnerFailsBeforeDdlAndLeavesNoMembershipOrCreateGrant() throws Exception {
        try (var prior = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start()) {
            var source = prior.getPostgresDatabase(); var admin = new JdbcTemplate(source);
            var flyway = Flyway.configure().dataSource(source).locations("classpath:db/praxis-bulk-migrations")
                    .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                    .baselineOnMigrate(false).cleanDisabled(true).target("13").load();
            flyway.migrate();
            admin.execute("alter role praxis_bulk_control_owner login");
            var originalHistory = admin.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
            var originalSchemaAcl = admin.queryForObject("select nspacl::text from pg_namespace where nspname='praxis_bulk'", String.class);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(source, Map.of(NS, DEPLOYMENT)))
                    .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                    .hasMessageContaining("beforeEachMigrate")
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Publication predecessor dedicated role identity differs");
            assertThat(admin.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank")).isEqualTo(originalHistory);
            assertThat(admin.queryForObject("select nspacl::text from pg_namespace where nspname='praxis_bulk'", String.class)).isEqualTo(originalSchemaAcl);
            assertThat(admin.queryForObject("select rolcanlogin from pg_roles where rolname='praxis_bulk_control_owner'", Boolean.class)).isTrue();
            assertThat(admin.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_openapi_publication') is null", Boolean.class)).isTrue();
            assertThat(admin.queryForObject("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid "
                    + "join pg_roles member on member.oid=m.member where r.rolname='praxis_bulk_control_owner' "
                    + "or member.rolname='praxis_bulk_control_owner'", Integer.class)).isZero();
            assertThat(admin.queryForObject("select has_schema_privilege('praxis_bulk_control_owner','praxis_bulk','CREATE')", Boolean.class)).isFalse();
            assertThat(admin.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history order by installed_rank desc limit 1", String.class)).isEqualTo("13");
        }
    }

    private void readyOperations() {
        BulkPostgresTestSupport.ready(owner, NS, A); BulkPostgresTestSupport.ready(owner, NS2, B);
    }
    private void assertSuspendedOperations() {
        assertThat(sql.queryForList("select state from praxis_bulk.praxis_bulk_operation_control order by operation_id", String.class))
                .containsExactly("SUSPENDED", "SUSPENDED");
        assertThat(sql.queryForList("select generation from praxis_bulk.praxis_bulk_operation_control order by operation_id", Long.class))
                .containsExactly(2L, 2L);
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control "
                + "where descriptor_fingerprint is not null or structural_revision is not null", Integer.class)).isZero();
    }
    private JdbcBulkOpenApiPublication.Snapshot read() throws Exception {
        return tx(runtime, c -> JdbcBulkOpenApiPublication.lockForRead(c, NS, DEPLOYMENT));
    }
    private JdbcBulkOpenApiPublication.Transition publish(long expected, String digest) throws Exception {
        return tx(control, c -> JdbcBulkOpenApiPublication.transition(c, NS, DEPLOYMENT, expected,
                JdbcBulkOpenApiPublication.Target.PUBLISHED, digest));
    }
    private JdbcBulkOpenApiPublication.Transition suspend(long expected) throws Exception {
        return tx(control, c -> JdbcBulkOpenApiPublication.transition(c, NS, DEPLOYMENT, expected,
                JdbcBulkOpenApiPublication.Target.SUSPENDED, null));
    }
    private RaceResult concurrentPublish(CountDownLatch start, CountDownLatch ready, String digest) throws Exception {
        return tx(control, c -> { int pid = pid(c); ready.countDown();
            if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("CAS start was not released");
            return new RaceResult(pid, JdbcBulkOpenApiPublication.transition(c, NS, DEPLOYMENT, 1,
                    JdbcBulkOpenApiPublication.Target.PUBLISHED, digest), digest);
        });
    }
    private static int pid(Connection c) throws SQLException {
        try (var s = c.createStatement(); var rows = s.executeQuery("select pg_backend_pid()")) {
            rows.next(); return rows.getInt(1);
        }
    }
    private static <T> T tx(DataSource source, SqlWork<T> work) throws Exception {
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) { s.execute("set local statement_timeout='3s'"); }
            try { T result = work.apply(c); c.commit(); return result; }
            catch (Exception failure) { c.rollback(); throw failure; }
        }
    }
    @FunctionalInterface private interface SqlWork<T> { T apply(Connection c) throws Exception; }
    private record RaceResult(int pid, JdbcBulkOpenApiPublication.Transition transition, String digest) { }
}
