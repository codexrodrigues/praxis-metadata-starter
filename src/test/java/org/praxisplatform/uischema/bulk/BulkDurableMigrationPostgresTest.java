package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drift must be rejected even when Flyway history and migration checksums remain intact. */
class BulkDurableMigrationPostgresTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String EVALUATION_FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(
            "tenant:prod:payroll", "operator", "employees",
            new CanonicalOperationRef("admin", "employee-bulk-approve", "/employees/bulk/approve", "POST"),
            "schema-r1", ActionCollectionAtomicity.PER_ITEM);

    private int migrate(javax.sql.DataSource dataSource) {
        return BulkExecutionMigrator.migrate(dataSource, java.util.Map.of(
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID));
    }

    @Test
    void atomicEffectReferenceBoundsRejectOversizedAndMalformedUtf16WithoutChangingTheAcceptedDigest() {
        // This class has no database lifecycle hooks: this deterministic Java-only test opens no PostgreSQL.
        for (String invalid : List.of("x".repeat(401), "😀".repeat(201), "effect/\uD800", "effect/\uDC00")) {
            assertThatThrownBy(() -> new BulkAtomicMutationResult.Item(
                    0, BulkUnitOutcome.CONFIRMED, List.of(invalid)))
                    .as("constructor rejects invalid reference with UTF-16 length %s", invalid.length())
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid atomic effect reference");
            assertThatThrownBy(() -> BulkTargetDigest.effectsOf(List.of(new BulkTargetDigest.EffectReference(0, invalid))))
                    .as("digest rejects invalid reference with UTF-16 length %s", invalid.length())
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid atomic effect reference");
        }
        String accepted = "😀".repeat(200);
        assertThat(accepted.length()).isEqualTo(400);
        assertThat(accepted.codePointCount(0, accepted.length())).isEqualTo(200);
        assertThat(new BulkAtomicMutationResult.Item(0, BulkUnitOutcome.CONFIRMED, List.of(accepted))
                .effectReferences()).containsExactly(accepted);
        // Independent SHA-256 oracle: four big-endian length frames, prefix /1, count 1, ordinal 0, 800 UTF-8 bytes.
        assertThat(BulkTargetDigest.effectsOf(List.of(new BulkTargetDigest.EffectReference(0, accepted))))
                .isEqualTo("sha256:f9d5d63786fd61ba425fecef33770eae711c35deb793de9ad1a6e83667e3c6ae");
    }

    @Test
    void v15DdlUpgradeProvisionsExactAtomicRuntimeGrantsAndCompletesDurably() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = pendingV15RuntimeFixtureWithoutBootstrapGrants(owner, sql, "bulk_v16_runtime");
            int checksum15 = sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='15'", Integer.class);
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(5);
            assertAtomicBootstrapPhase(sql, "COMPLETE");
            assertAtomicRuntimeGrants(sql, "bulk_v16_runtime");
            assertThat(sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='15'", Integer.class)).isEqualTo(checksum15);
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isZero();
            assertAtomicBootstrapPhase(sql, "COMPLETE");
            assertAtomicRuntimeGrants(sql, "bulk_v16_runtime");
            BulkExecutionMigrator.validate(owner, roles);
        }
    }

    @Test
    void publishedV16CutoverToV20PreservesHistoryAndBucketIdentityAcrossQuotaAndCapacityMigrations()
            throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
            var jar = java.nio.file.Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                    "target", "historical-bulk-sdk", "metadata-v16-rc152.jar").toRealPath();
            var thread = Thread.currentThread();
            var originalLoader = thread.getContextClassLoader();
            try (var published = isolatedPublishedSdk(jar,
                    "4594891d98aec7948c44c40cb399b2cf04b44b42380328774cb9f7aab1aa16de")) {
                thread.setContextClassLoader(published);
                try {
                    var migrator = Class.forName("org.praxisplatform.uischema.bulk.BulkExecutionMigrator", true, published);
                    var roleType = Class.forName("org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration",
                            true, published);
                    for (var type : List.of(migrator, roleType))
                        assertThat(java.nio.file.Path.of(type.getProtectionDomain().getCodeSource()
                                .getLocation().toURI()).toRealPath()).as("published rc152 CodeSource").isEqualTo(jar);
                    assertThat(published.getResource("db/praxis-bulk-migrations/V16__bulk_atomic_set_execution.sql"))
                            .isNotNull();
                    assertThat(published.getResource("db/praxis-bulk-migrations/V17__bulk_pending_quota_snapshot_fence.sql"))
                            .as("published rc152 cannot execute candidate V17 DDL").isNull();
                    Object publishedRoles = roleType.getConstructor(String.class, java.util.Set.class,
                            java.util.Set.class, java.util.Set.class).newInstance(roles.expectedSchemaOwnerRole(),
                            roles.runtimeGranteeRoles(), roles.retentionExecutorMembers(),
                            roles.controlPlaneGranteeRoles());
                    Method publishedMigrate = migrator.getMethod("migrate", DataSource.class, java.util.Map.class,
                            roleType);
                    Method publishedValidate = migrator.getMethod("validate", DataSource.class, roleType);
                    var deployments = java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID);
                    assertThat(invokePublished(publishedMigrate, owner, deployments, publishedRoles)).isEqualTo(1);
                    invokePublished(publishedValidate, owner, publishedRoles);

                    var historyBefore = sql.queryForList("select version, checksum from "
                            + "praxis_bulk.praxis_bulk_schema_history where version is not null order by installed_rank");
                    assertThat(historyBefore).hasSize(16);
                    assertThat(historyBefore.getLast().get("version")).isEqualTo("16");
                    var guardBefore = sql.queryForMap("""
                            select p.oid::text as oid, p.proacl::text as acl, p.prosrc as body
                            from pg_proc p where p.oid='praxis_bulk.guard_bucket_mutation()'::regprocedure
                            """);
                    var triggersBefore = sql.queryForList("""
                            select tgname, tgfoid::text as function_oid, tgenabled, tgtype
                            from pg_trigger
                            where tgfoid='praxis_bulk.guard_bucket_mutation()'::regprocedure
                            order by tgname
                            """);
                    var deploymentRowsBefore = sql.queryForList("""
                            select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket
                            order by deployment_id
                            """);
                    assertThat(triggersBefore).hasSize(2);
                    assertThat((String) guardBefore.get("body")).doesNotContain("new is not distinct from old");
                    // Flyway 11.17 validates pending migrations before the catalog guard body.
                    // Do not weaken this gate with ignoreMigrationPatterns to reach the later check.
                    thread.setContextClassLoader(originalLoader);
                    assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, roles))
                            .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class)
                            .hasMessageContaining("Detected resolved migration not applied to database: 17.");

                    assertThat(BulkExecutionMigrator.migrate(owner, deployments, roles)).isEqualTo(4);
                    BulkExecutionMigrator.validate(owner, roles);
                    assertThat(BulkExecutionMigrator.migrate(owner, deployments, roles)).isZero();
                    assertThat(sql.queryForList("select version, checksum from "
                            + "praxis_bulk.praxis_bulk_schema_history where version is not null and version::integer<=16 "
                            + "order by installed_rank")).isEqualTo(historyBefore);
                    assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                            + "where version is not null order by installed_rank desc limit 1", String.class))
                            .isEqualTo("20");
                    assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                            String.class)).isEqualTo("COMPLETE");
                    var guardAfter = sql.queryForMap("""
                            select p.oid::text as oid, p.proacl::text as acl, p.prosrc as body
                            from pg_proc p where p.oid='praxis_bulk.guard_bucket_mutation()'::regprocedure
                            """);
                    assertThat(guardAfter).containsEntry("oid", guardBefore.get("oid"))
                            .containsEntry("acl", guardBefore.get("acl"));
                    assertThat((String) guardAfter.get("body")).contains("new is not distinct from old")
                            .isNotEqualTo(guardBefore.get("body"));
                    assertThat(sql.queryForList("""
                            select tgname, tgfoid::text as function_oid, tgenabled, tgtype
                            from pg_trigger
                            where tgfoid='praxis_bulk.guard_bucket_mutation()'::regprocedure
                            order by tgname
                            """)).isEqualTo(triggersBefore);
                    assertThat(sql.queryForList("""
                            select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket
                            order by deployment_id
                            """)).isEqualTo(deploymentRowsBefore);
                    thread.setContextClassLoader(published);
                    assertThatThrownBy(() -> invokePublished(publishedValidate, owner, publishedRoles))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Refusing an unknown nonempty praxis_bulk schema");
                    var currentHistory = sql.queryForList("select version, checksum from "
                            + "praxis_bulk.praxis_bulk_schema_history where version is not null order by installed_rank");
                    assertThatThrownBy(() -> invokePublished(publishedMigrate, owner, deployments, publishedRoles))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(sql.queryForList("select version, checksum from "
                            + "praxis_bulk.praxis_bulk_schema_history where version is not null order by installed_rank"))
                            .isEqualTo(currentHistory);
                    thread.setContextClassLoader(originalLoader);

                    var runtime = new DriverManagerDataSource(
                            postgres.getJdbcUrl("bulk_v16_runtime", "postgres"), "bulk_v16_runtime", "");
                    var runtimeSql = new JdbcTemplate(runtime);
                    assertThat(runtimeSql.queryForObject("select current_user", String.class))
                            .isEqualTo("bulk_v16_runtime");
                    String bucketXminBefore = sql.queryForObject("select xmin::text from "
                            + "praxis_bulk.praxis_bulk_deployment_bucket where deployment_id=?",
                            String.class, BulkPostgresTestSupport.DEPLOYMENT_ID);
                    assertThat(runtimeSql.update("update praxis_bulk.praxis_bulk_deployment_bucket "
                            + "set deployment_id=deployment_id where deployment_id=?",
                            BulkPostgresTestSupport.DEPLOYMENT_ID)).isEqualTo(1);
                    assertThat(sql.queryForObject("select xmin::text from "
                            + "praxis_bulk.praxis_bulk_deployment_bucket where deployment_id=?",
                            String.class, BulkPostgresTestSupport.DEPLOYMENT_ID))
                            .as("identity-preserving update creates a new MVCC tuple").isNotEqualTo(bucketXminBefore);
                    assertThat(sql.queryForList("""
                            select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket
                            order by deployment_id
                            """)).isEqualTo(deploymentRowsBefore);
                    String subjectDigest = "sha256:" + "d".repeat(64);
                    assertThat(runtimeSql.update("""
                            insert into praxis_bulk.praxis_bulk_subject_bucket
                                (deployment_id, subject_scope_digest_version, subject_scope_digest)
                            values (?,1,?)
                            """, BulkPostgresTestSupport.DEPLOYMENT_ID, subjectDigest)).isEqualTo(1);
                    var subjectRowsBefore = sql.queryForList("""
                            select deployment_id,subject_scope_digest_version,subject_scope_digest
                            from praxis_bulk.praxis_bulk_subject_bucket
                            order by deployment_id,subject_scope_digest_version,subject_scope_digest
                            """);
                    String subjectXminBefore = sql.queryForObject("select xmin::text from "
                            + "praxis_bulk.praxis_bulk_subject_bucket where deployment_id=? "
                            + "and subject_scope_digest_version=1 and subject_scope_digest=?",
                            String.class, BulkPostgresTestSupport.DEPLOYMENT_ID, subjectDigest);
                    assertThat(runtimeSql.update("""
                            update praxis_bulk.praxis_bulk_subject_bucket set deployment_id=deployment_id
                            where deployment_id=? and subject_scope_digest_version=1 and subject_scope_digest=?
                            """, BulkPostgresTestSupport.DEPLOYMENT_ID, subjectDigest)).isEqualTo(1);
                    assertThat(sql.queryForObject("select xmin::text from "
                            + "praxis_bulk.praxis_bulk_subject_bucket where deployment_id=? "
                            + "and subject_scope_digest_version=1 and subject_scope_digest=?",
                            String.class, BulkPostgresTestSupport.DEPLOYMENT_ID, subjectDigest))
                            .as("subject lock identity also receives a fresh MVCC tuple").isNotEqualTo(subjectXminBefore);
                    assertThat(sql.queryForList("""
                            select deployment_id,subject_scope_digest_version,subject_scope_digest
                            from praxis_bulk.praxis_bulk_subject_bucket
                            order by deployment_id,subject_scope_digest_version,subject_scope_digest
                            """)).isEqualTo(subjectRowsBefore);
                    try (var connection = runtime.getConnection();
                            var changed = connection.prepareStatement("update praxis_bulk.praxis_bulk_deployment_bucket "
                                    + "set deployment_id='different' where deployment_id=?");
                            var subjectChanged = connection.prepareStatement(
                                    "update praxis_bulk.praxis_bulk_subject_bucket "
                                    + "set deployment_id='different' where deployment_id=? "
                                    + "and subject_scope_digest_version=1 and subject_scope_digest=?")) {
                        changed.setString(1, BulkPostgresTestSupport.DEPLOYMENT_ID);
                        subjectChanged.setString(1, BulkPostgresTestSupport.DEPLOYMENT_ID);
                        subjectChanged.setString(2, subjectDigest);
                        assertThatThrownBy(changed::executeUpdate).isInstanceOfSatisfying(java.sql.SQLException.class,
                                error -> assertThat(error.getSQLState()).isEqualTo("55000"));
                        assertThatThrownBy(subjectChanged::executeUpdate)
                                .isInstanceOfSatisfying(java.sql.SQLException.class,
                                        error -> assertThat(error.getSQLState()).isEqualTo("55000"));
                    }
                    try (var connection = owner.getConnection();
                            var changed = connection.prepareStatement("update praxis_bulk.praxis_bulk_deployment_bucket "
                                    + "set deployment_id='different' where deployment_id=?");
                            var deleted = connection.prepareStatement("delete from "
                                    + "praxis_bulk.praxis_bulk_deployment_bucket where deployment_id=?")) {
                        changed.setString(1, BulkPostgresTestSupport.DEPLOYMENT_ID);
                        deleted.setString(1, BulkPostgresTestSupport.DEPLOYMENT_ID);
                        assertThatThrownBy(changed::executeUpdate).isInstanceOfSatisfying(java.sql.SQLException.class,
                                error -> assertThat(error.getSQLState()).isEqualTo("55000"));
                        assertThatThrownBy(deleted::executeUpdate).isInstanceOfSatisfying(java.sql.SQLException.class,
                                error -> assertThat(error.getSQLState()).isEqualTo("55000"));
                    }
                } finally {
                    thread.setContextClassLoader(originalLoader);
                }
            }
        }
    }

    private static Object invokePublished(Method method, Object... arguments) throws Exception {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception failure) throw failure;
            if (cause instanceof Error failure) throw failure;
            throw new IllegalStateException("Published SDK invocation failed", cause);
        }
    }

    @Test
    void v17PreflightRejectsPriorGuardBodyAclAndTriggerDriftWithoutHealingV16() throws Exception {
        var mutations = new java.util.LinkedHashMap<String, List<String>>();
        mutations.put("body", List.of("""
                create or replace function praxis_bulk.guard_bucket_mutation()
                returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
                begin return new; end;
                $$
                """));
        mutations.put("acl", List.of(
                "grant execute on function praxis_bulk.guard_bucket_mutation() to public"));
        mutations.put("disabled trigger", List.of(
                "alter table praxis_bulk.praxis_bulk_deployment_bucket "
                        + "disable trigger praxis_bulk_deployment_bucket_guard_mutation"));
        mutations.put("rebound trigger", List.of(
                "drop trigger praxis_bulk_deployment_bucket_guard_mutation "
                        + "on praxis_bulk.praxis_bulk_deployment_bucket",
                "create trigger praxis_bulk_deployment_bucket_guard_mutation "
                        + "before update or delete on praxis_bulk.praxis_bulk_deployment_bucket "
                        + "for each row execute function praxis_bulk.guard_lifecycle_delete()"));
        mutations.put("third guard binding", List.of(
                "drop trigger praxis_bulk_proposal_guard_delete on praxis_bulk.praxis_bulk_proposal",
                "create trigger praxis_bulk_proposal_guard_delete before delete "
                        + "on praxis_bulk.praxis_bulk_proposal for each row "
                        + "execute function praxis_bulk.guard_bucket_mutation()"));
        for (var mutation : mutations.entrySet()) {
            try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                    .setRegisterShutdownHook(false).start()) {
                var owner = postgres.getPostgresDatabase();
                var sql = new JdbcTemplate(owner);
                var roles = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
                initializeHistoricalV16WithPublishedSdk(owner, roles);
                assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                        + "where version is not null order by installed_rank desc limit 1", String.class))
                        .isEqualTo("16");
                for (String statement : mutation.getValue()) sql.execute(statement);

                var historyBefore = sql.queryForList("""
                        select version,checksum from praxis_bulk.praxis_bulk_schema_history
                        where version is not null order by installed_rank
                        """);
                var guardBefore = sql.queryForMap("""
                        select p.oid::text as oid,p.prosrc as body,p.proacl::text as acl,
                               p.proowner::text as owner,p.proconfig::text as config
                        from pg_proc p where p.oid='praxis_bulk.guard_bucket_mutation()'::regprocedure
                        """);
                var triggersBefore = sql.queryForList("""
                        select t.oid::text as oid,t.tgrelid::text as relation_name,t.tgname,
                               t.tgfoid::text as function_oid,t.tgenabled,t.tgtype
                        from pg_trigger t
                        join pg_class c on c.oid=t.tgrelid
                        join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname='praxis_bulk' and not t.tgisinternal
                        order by t.tgrelid,t.tgname
                        """);
                var bucketsBefore = sql.queryForList("""
                        select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket
                        order by deployment_id
                        """);
                assertThat(historyBefore).hasSize(16);
                assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                        java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                        .as("V17 preflight rejects %s without replacing the prior guard", mutation.getKey())
                        .isInstanceOf(RuntimeException.class)
                        .satisfies(failure -> {
                            Throwable cause = failure;
                            while (cause != null && !(cause instanceof java.sql.SQLException))
                                cause = cause.getCause();
                            assertThat(cause).isInstanceOfSatisfying(java.sql.SQLException.class,
                                    sqlFailure -> assertThat(sqlFailure.getSQLState()).isEqualTo("55000"));
                        });
                assertThat(sql.queryForList("""
                        select version,checksum from praxis_bulk.praxis_bulk_schema_history
                        where version is not null order by installed_rank
                        """)).isEqualTo(historyBefore);
                assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                        + "where version='17'", Integer.class)).isZero();
                assertThat(sql.queryForMap("""
                        select p.oid::text as oid,p.prosrc as body,p.proacl::text as acl,
                               p.proowner::text as owner,p.proconfig::text as config
                        from pg_proc p where p.oid='praxis_bulk.guard_bucket_mutation()'::regprocedure
                        """)).isEqualTo(guardBefore);
                assertThat(sql.queryForList("""
                        select t.oid::text as oid,t.tgrelid::text as relation_name,t.tgname,
                               t.tgfoid::text as function_oid,t.tgenabled,t.tgtype
                        from pg_trigger t
                        join pg_class c on c.oid=t.tgrelid
                        join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname='praxis_bulk' and not t.tgisinternal
                        order by t.tgrelid,t.tgname
                        """)).isEqualTo(triggersBefore);
                assertThat(sql.queryForList("""
                        select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket
                        order by deployment_id
                        """)).isEqualTo(bucketsBefore);
            }
        }
    }

    private static void initializeHistoricalV16WithPublishedSdk(DataSource owner,
            BulkExecutionRoleConfiguration roles) throws Exception {
        var jar = java.nio.file.Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                "target", "historical-bulk-sdk", "metadata-v16-rc152.jar").toRealPath();
        var thread = Thread.currentThread();
        var originalLoader = thread.getContextClassLoader();
        try (var published = isolatedPublishedSdk(jar,
                "4594891d98aec7948c44c40cb399b2cf04b44b42380328774cb9f7aab1aa16de")) {
            thread.setContextClassLoader(published);
            try {
                var migrator = Class.forName("org.praxisplatform.uischema.bulk.BulkExecutionMigrator", true, published);
                var roleType = Class.forName("org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration",
                        true, published);
                for (var type : List.of(migrator, roleType))
                    assertThat(java.nio.file.Path.of(type.getProtectionDomain().getCodeSource()
                            .getLocation().toURI()).toRealPath()).as("published rc152 CodeSource").isEqualTo(jar);
                assertThat(published.getResource("db/praxis-bulk-migrations/V17__bulk_pending_quota_snapshot_fence.sql"))
                        .isNull();
                Object publishedRoles = roleType.getConstructor(String.class, java.util.Set.class,
                        java.util.Set.class, java.util.Set.class).newInstance(roles.expectedSchemaOwnerRole(),
                        roles.runtimeGranteeRoles(), roles.retentionExecutorMembers(),
                        roles.controlPlaneGranteeRoles());
                var deployments = java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID);
                assertThat(invokePublished(migrator.getMethod("migrate", DataSource.class, java.util.Map.class,
                        roleType), owner, deployments, publishedRoles)).isEqualTo(1);
                invokePublished(migrator.getMethod("validate", DataSource.class, roleType), owner, publishedRoles);
            } finally {
                thread.setContextClassLoader(originalLoader);
            }
        }
    }

    @Test
    void genuineV17HistoryUpgradesToV20AndPreservesEveryPriorChecksum() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
            initializeHistoricalV16WithPublishedSdk(owner, roles);
            assertThat(Flyway.configure().dataSource(owner)
                    .locations("classpath:db/praxis-bulk-migrations")
                    .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                    .table("praxis_bulk_schema_history")
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion("17"))
                    .createSchemas(true).baselineOnMigrate(false).cleanDisabled(true)
                    .load().migrate().migrationsExecuted).isEqualTo(1);
            var priorHistory = sql.queryForList("select version, checksum from "
                    + "praxis_bulk.praxis_bulk_schema_history where version is not null order by installed_rank");
            assertThat(priorHistory).hasSize(17);
            assertThat(priorHistory.getLast().get("version")).isEqualTo("17");
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(3);
            assertThat(sql.queryForList("select version, checksum from "
                    + "praxis_bulk.praxis_bulk_schema_history where version is not null and version::integer<=17 "
                    + "order by installed_rank")).isEqualTo(priorHistory);
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "where version is not null order by installed_rank desc limit 1", String.class))
                    .isEqualTo("20");
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime', "
                    + "'praxis_bulk.praxis_bulk_capacity_marker', 'SELECT')", Boolean.class)).isTrue();
            assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime', "
                    + "'praxis_bulk.praxis_bulk_capacity_installation', 'SELECT')", Boolean.class)).isTrue();
            BulkExecutionMigrator.validate(owner, roles);
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isZero();
        }
    }

    @Test
    void v17CatalogDriftBlocksV18BeforeFlywayWritesItsHistory() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
            initializeHistoricalV16WithPublishedSdk(owner, roles);
            assertThat(Flyway.configure().dataSource(owner)
                    .locations("classpath:db/praxis-bulk-migrations")
                    .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                    .table("praxis_bulk_schema_history")
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion("17"))
                    .createSchemas(true).baselineOnMigrate(false).cleanDisabled(true)
                    .load().migrate().migrationsExecuted).isEqualTo(1);
            sql.execute("alter table praxis_bulk.praxis_bulk_proposal "
                    + "drop constraint praxis_bulk_proposal_namespace_id_nonblank_check");
            sql.execute("alter table praxis_bulk.praxis_bulk_proposal "
                    + "add constraint praxis_bulk_proposal_namespace_id_nonblank_check "
                    + "check (btrim(namespace_id) <> '' or true)");
            String driftBefore = sql.queryForObject("select pg_get_constraintdef(oid) from pg_constraint "
                    + "where conrelid='praxis_bulk.praxis_bulk_proposal'::regclass "
                    + "and conname='praxis_bulk_proposal_namespace_id_nonblank_check'", String.class);
            var historyBefore = sql.queryForList("select version, checksum from "
                    + "praxis_bulk.praxis_bulk_schema_history where version is not null order by installed_rank");
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForList("select version, checksum from "
                    + "praxis_bulk.praxis_bulk_schema_history where version is not null order by installed_rank"))
                    .isEqualTo(historyBefore);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='18'", Integer.class)).isZero();
            assertThat(sql.queryForObject("select pg_get_constraintdef(oid) from pg_constraint "
                    + "where conrelid='praxis_bulk.praxis_bulk_proposal'::regclass "
                    + "and conname='praxis_bulk_proposal_namespace_id_nonblank_check'", String.class))
                    .isEqualTo(driftBefore);
        }
    }

    @Test
    void completedV18WithNoMarkersNeverRepairsARevokedRuntimeRead() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = pendingV15RuntimeFixtureWithoutBootstrapGrants(owner, sql, "bulk_v16_runtime");
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(5);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_marker",
                    Integer.class)).isZero();
            sql.execute("revoke select on praxis_bulk.praxis_bulk_capacity_marker from bulk_v16_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime', "
                    + "'praxis_bulk.praxis_bulk_capacity_marker', 'SELECT')", Boolean.class)).isFalse();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_marker",
                    Integer.class)).isZero();
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, roles))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void liveRuntimeReadsV18WithoutHistoryOrBootstrapTablePrivilege() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(5);
            for (String table : List.of("praxis_bulk_schema_history", "praxis_bulk_capacity_read_bootstrap"))
                assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime', ?, 'SELECT')",
                        Boolean.class, "praxis_bulk." + table)).isFalse();
            var runtimeSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_v16_runtime", "postgres"), "bulk_v16_runtime", "");
            var restrictedSql = new JdbcTemplate(runtimeSource);
            assertThatThrownBy(() -> restrictedSql.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_schema_history", Integer.class))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> restrictedSql.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_capacity_read_bootstrap", Integer.class))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            var manager = new DataSourceTransactionManager(runtimeSource);
            var runtime = new BulkExecutionInfrastructure(runtimeSource, manager, CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID, roles);
            Integer markerCount = runtime.withConsistentRead(connection -> {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "select count(*) from praxis_bulk.praxis_bulk_capacity_marker")) {
                    assertThat(rows.next()).isTrue();
                    return rows.getInt(1);
                }
            });
            assertThat(markerCount).isZero();
            Integer installationCount = new TransactionTemplate(manager).execute(status -> runtime.withConnection(connection -> {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "select count(*) from praxis_bulk.praxis_bulk_capacity_installation")) {
                    assertThat(rows.next()).isTrue();
                    return rows.getInt(1);
                }
            }));
            assertThat(installationCount).isZero();
            BulkExecutionMigrator.validate(owner, roles);
        }
    }

    @Test
    void currentHistoryRejectsV18StorageRemovalWithoutPartialCatalog() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            assertThat(migrate(owner)).isEqualTo(20);
            var historyBefore = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank");
            sql.execute("drop table praxis_bulk.praxis_bulk_capacity_installation, "
                    + "praxis_bulk.praxis_bulk_capacity_marker, "
                    + "praxis_bulk.praxis_bulk_capacity_read_bootstrap cascade");
            sql.execute("drop function praxis_bulk.protect_capacity_marker(), "
                    + "praxis_bulk.reject_capacity_installation_mutation(), "
                    + "praxis_bulk.protect_capacity_read_bootstrap()");
            for (String table : List.of("praxis_bulk_capacity_installation", "praxis_bulk_capacity_marker",
                    "praxis_bulk_capacity_read_bootstrap"))
                assertThat(sql.queryForObject("select to_regclass(?) is null", Boolean.class,
                        "praxis_bulk." + table)).isTrue();
            assertThat(sql.queryForObject("select max(version::integer) from "
                    + "praxis_bulk.praxis_bulk_schema_history where success and version is not null",
                    Integer.class)).isEqualTo(20);
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank")).isEqualTo(historyBefore);
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V18 history and physical installation disagree");
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank")).isEqualTo(historyBefore);
        }
    }

    @Test
    void completedV18NeverProvisionsAChangedRuntimeRoleSet() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var original = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), original)).isEqualTo(5);
            // This is explicit test fixture provisioning of a second old-protocol role, not a migrator repair.
            BulkPostgresTestSupport.grantRuntimeRole(owner, "bulk_v18_future_runtime");
            sql.execute("revoke select on praxis_bulk.praxis_bulk_capacity_marker, "
                    + "praxis_bulk.praxis_bulk_capacity_installation from bulk_v18_future_runtime");
            var changed = new BulkExecutionRoleConfiguration("postgres",
                    java.util.Set.of("bulk_v16_runtime", "bulk_v18_future_runtime"),
                    java.util.Set.of(), java.util.Set.of());
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), changed))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForObject("select has_table_privilege('bulk_v18_future_runtime', "
                    + "'praxis_bulk.praxis_bulk_capacity_marker', 'SELECT')", Boolean.class)).isFalse();
            assertThat(sql.queryForObject("select has_table_privilege('bulk_v18_future_runtime', "
                    + "'praxis_bulk.praxis_bulk_capacity_installation', 'SELECT')", Boolean.class)).isFalse();
        }
    }

    @Test
    void v18SourceCatalogRejectsWeakenedBootstrapCheckAndWrongUniqueKey() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            assertThat(migrate(owner)).isEqualTo(20);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            sql.execute("alter table praxis_bulk.praxis_bulk_capacity_read_bootstrap "
                    + "drop constraint capacity_read_bootstrap_phase_check");
            sql.execute("alter table praxis_bulk.praxis_bulk_capacity_read_bootstrap "
                    + "add constraint capacity_read_bootstrap_phase_check "
                    + "check (phase in ('PENDING','COMPLETE') or true)");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner)).isInstanceOf(RuntimeException.class);
            sql.execute("alter table praxis_bulk.praxis_bulk_capacity_read_bootstrap "
                    + "drop constraint capacity_read_bootstrap_phase_check");
            sql.execute("alter table praxis_bulk.praxis_bulk_capacity_read_bootstrap "
                    + "add constraint capacity_read_bootstrap_phase_check check (phase in ('PENDING','COMPLETE'))");
            BulkExecutionMigrator.validate(owner);
            sql.execute("alter table praxis_bulk.praxis_bulk_capacity_marker "
                    + "drop constraint capacity_marker_database_unique");
            sql.execute("alter table praxis_bulk.praxis_bulk_capacity_marker "
                    + "add constraint capacity_marker_database_unique unique (authority_id)");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner)).isInstanceOf(RuntimeException.class);
        }
    }

    /** Real rc154 initialization completes all pre-V18 latches; raw V18 alone models its crash window. */
    private static void initializePendingHistoricalV18(DataSource owner,
            BulkExecutionRoleConfiguration roles) throws Exception {
        var jar = java.nio.file.Path.of(System.getProperty("praxis.bulk.historical.rc154.jar",
                java.nio.file.Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                        "target", "historical-bulk-sdk", "metadata-v17-rc154.jar").toString())).toRealPath();
        var thread = Thread.currentThread();
        var originalLoader = thread.getContextClassLoader();
        try (var published = isolatedPublishedSdk(jar,
                "e2c98ca0a551eb4911400e7fc76247e5ce230c33ae42d187df7e2ba929698a61")) {
            thread.setContextClassLoader(published);
            try {
                var migrator = Class.forName("org.praxisplatform.uischema.bulk.BulkExecutionMigrator", true, published);
                var roleType = Class.forName("org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration", true, published);
                for (var type : List.of(migrator, roleType))
                    assertThat(java.nio.file.Path.of(type.getProtectionDomain().getCodeSource()
                            .getLocation().toURI()).toRealPath()).as("published rc154 CodeSource").isEqualTo(jar);
                assertThat(published.getResource("db/praxis-bulk-migrations/V17__bulk_pending_quota_snapshot_fence.sql"))
                        .isNotNull();
                assertThat(published.getResource("db/praxis-bulk-migrations/V18__bulk_capacity_installation.sql"))
                        .isNull();
                Object publishedRoles = roleType.getConstructor(String.class, java.util.Set.class,
                        java.util.Set.class, java.util.Set.class).newInstance(roles.expectedSchemaOwnerRole(),
                        roles.runtimeGranteeRoles(), roles.retentionExecutorMembers(), roles.controlPlaneGranteeRoles());
                assertThat(invokePublished(migrator.getMethod("migrate", DataSource.class, java.util.Map.class, roleType),
                        owner, java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID),
                        publishedRoles)).isEqualTo(2);
                invokePublished(migrator.getMethod("validate", DataSource.class, roleType), owner, publishedRoles);
            } finally { thread.setContextClassLoader(originalLoader); }
        }
        var sql = new JdbcTemplate(owner);
        for (String table : List.of("manifest_bootstrap", "preview_bootstrap", "preview_integrity_bootstrap",
                "preview_reader_bootstrap", "atomic_bootstrap"))
            assertThat(sql.queryForList("select phase from praxis_bulk.praxis_bulk_" + table, String.class))
                    .as("published V17 completed %s", table).containsExactly("COMPLETE");
        assertThat(sql.queryForObject("select max(version::integer) from praxis_bulk.praxis_bulk_schema_history "
                + "where version is not null", Integer.class)).isEqualTo(17);
        assertThat(Flyway.configure().dataSource(owner).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .target("18").createSchemas(true).baselineOnMigrate(false).cleanDisabled(true)
                .load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                String.class)).isEqualTo("PENDING");
        for (String table : List.of("praxis_bulk_capacity_marker", "praxis_bulk_capacity_installation")) {
            assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table, Integer.class)).isZero();
            for (String role : roles.runtimeGranteeRoles())
                assertThat(sql.queryForObject("select has_table_privilege(?,?,'SELECT')", Boolean.class,
                        role, "praxis_bulk." + table)).isFalse();
        }
    }

    /** Private memory snapshots preserve every V18 row/byte, projecting only columns added by V19. */
    private static java.util.Map<String, List<String>> historicalV18Rows(JdbcTemplate sql) {
        var snapshot = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : sql.queryForList("select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                + "where n.nspname='praxis_bulk' and c.relkind='r' order by c.relname", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Unsafe fixture relation");
            String projection = table.equals("praxis_bulk_proposal") ? "to_jsonb(t)-'execution_mode'"
                    : table.equals("praxis_bulk_execution")
                    ? "to_jsonb(t)-array['execution_mode','queue_token_id','active_token_id']" : "to_jsonb(t)";
            String history = table.equals("praxis_bulk_schema_history")
                    ? " where version is null or version::integer<=18" : "";
            snapshot.put(table, sql.queryForList("select (" + projection + ")::text from praxis_bulk." + table
                    + " t" + history + " order by 1", String.class));
        }
        return snapshot;
    }

    private static void assertHistoricalV18RowsUnchanged(JdbcTemplate sql, java.util.Map<String, List<String>> before) {
        var after = historicalV18Rows(sql);
        for (var entry : before.entrySet())
            assertThat(entry.getValue().equals(after.get(entry.getKey())))
                    .as("original rows/bytes remain unchanged in %s", entry.getKey()).isTrue();
    }

    private static List<java.util.Map<String, Object>> historicalV18ReadPrivileges(JdbcTemplate sql) {
        return sql.queryForList("""
                select c.relname,c.relacl::text as table_acl,a.attname,a.attacl::text as column_acl
                  from pg_class c join pg_namespace n on n.oid=c.relnamespace
                  join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
                 where n.nspname='praxis_bulk' and c.relname in ('praxis_bulk_capacity_marker',
                       'praxis_bulk_capacity_installation','praxis_bulk_capacity_read_bootstrap')
                 order by c.relname,a.attnum
                """);
    }

    private record HistoricalV18AclEntry(String table, String scope, String column, String grantor,
            String grantee, String privilege, boolean grantOption) {
    }

    private record HistoricalV18CompleteAcl(List<HistoricalV18AclEntry> entries,
            List<java.util.Map<String, Object>> bootstrapRawAcl) {
    }

    /** COMPLETE18 keeps every prior grant and gains only the three internal grants declared by V19. */
    private static HistoricalV18CompleteAcl historicalV18CompleteAcl(JdbcTemplate sql) {
        var entries = sql.query("""
                with relations as (
                    select c.oid,c.relname,c.relowner,c.relacl
                      from pg_class c join pg_namespace n on n.oid=c.relnamespace
                     where n.nspname='praxis_bulk' and c.relname in ('praxis_bulk_capacity_marker',
                           'praxis_bulk_capacity_installation','praxis_bulk_capacity_read_bootstrap')
                ), entries as (
                    select c.relname,'TABLE'::text as scope,''::text as column_name,
                           x.grantor,x.grantee,x.privilege_type,x.is_grantable
                      from relations c
                      cross join lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) x
                    union all
                    select c.relname,'COLUMN'::text,a.attname::text,
                           x.grantor,x.grantee,x.privilege_type,x.is_grantable
                      from relations c join pg_attribute a on a.attrelid=c.oid
                           and a.attnum>0 and not a.attisdropped
                      cross join lateral aclexplode(a.attacl) x
                )
                select relname,scope,column_name,pg_get_userbyid(grantor)::text as grantor_name,
                       case when grantee=0 then 'PUBLIC' else pg_get_userbyid(grantee)::text end as grantee_name,
                       privilege_type,is_grantable
                  from entries
                 order by relname,scope,column_name,grantor_name,grantee_name,privilege_type,is_grantable
                """, (row, rowNumber) -> new HistoricalV18AclEntry(row.getString("relname"), row.getString("scope"),
                row.getString("column_name"), row.getString("grantor_name"), row.getString("grantee_name"),
                row.getString("privilege_type"), row.getBoolean("is_grantable")));
        var bootstrap = historicalV18ReadPrivileges(sql).stream()
                .filter(row -> "praxis_bulk_capacity_read_bootstrap".equals(row.get("relname"))).toList();
        return new HistoricalV18CompleteAcl(entries, bootstrap);
    }

    private static void assertExactV19InternalCapacityAclDelta(JdbcTemplate sql, HistoricalV18CompleteAcl before,
            BulkExecutionRoleConfiguration roles) {
        assertThat(before.entries()).noneMatch(entry -> "praxis_bulk_capacity_owner".equals(entry.grantee()));
        var expected = new java.util.ArrayList<>(before.entries());
        String grantor = roles.expectedSchemaOwnerRole();
        expected.add(new HistoricalV18AclEntry("praxis_bulk_capacity_marker", "TABLE", "", grantor,
                "praxis_bulk_capacity_owner", "SELECT", false));
        expected.add(new HistoricalV18AclEntry("praxis_bulk_capacity_installation", "TABLE", "", grantor,
                "praxis_bulk_capacity_owner", "SELECT", false));
        expected.add(new HistoricalV18AclEntry("praxis_bulk_capacity_marker", "COLUMN", "marker_id", grantor,
                "praxis_bulk_capacity_owner", "UPDATE", false));
        var after = historicalV18CompleteAcl(sql);
        assertThat(after.entries()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(after.bootstrapRawAcl()).isEqualTo(before.bootstrapRawAcl());
    }

    /** Sanitized causal backend evidence; no SQL, credentials or protected row identifiers. */
    private static void recordV18OwnerWaitEdges(List<java.util.Map<String, Object>> locks, int holderPid) throws Exception {
        var manifest = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("caseId", "v18-two-owner-latch-upgrade").put("logicalDatabase", "historical_v18_local")
                .put("harnessPid", ProcessHandle.current().pid()).put("subprocesses", 0)
                .put("processExit", "NOT_APPLICABLE_IN_PROCESS_JUNIT").put("barriersUsed", true)
                .put("holderPid", holderPid).put("caseOutcome", "CAUSAL_WAIT_OBSERVED_NOT_TEST_VERDICT");
        var backends = manifest.putArray("waitEdges");
        for (var row : locks) {
            var backend = backends.addObject().put("applicationName", (String) row.get("application_name"))
                    .put("backendPid", ((Number) row.get("pid")).intValue())
                    .put("blockedByHolder", Boolean.TRUE.equals(row.get("blocked_by_holder")))
                    .put("waitingOwnerAdvisory", Boolean.TRUE.equals(row.get("waiting_owner_advisory")))
                    .put("blockedByPeer", Boolean.TRUE.equals(row.get("blocked_by_peer")));
            var blockers = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) row.get("blocker_pids"));
            assertThat(blockers.isArray()).isTrue();
            for (var blocker : blockers) assertThat(blocker.isIntegralNumber() && blocker.intValue() > 0).isTrue();
            backend.set("blockedByPids", blockers);
        }
        String configured = System.getProperty("praxis.bulk.proof.directory");
        var directory = configured == null ? java.nio.file.Files.createTempDirectory("praxis-v18-owner-proof-")
                : java.nio.file.Path.of(configured);
        java.nio.file.Files.createDirectories(directory);
        var file = directory.resolve("v18-two-owner-latch-upgrade.json");
        java.nio.file.Files.writeString(file, manifest.toPrettyString(), java.nio.charset.StandardCharsets.UTF_8);
        if (configured == null) System.out.println("V18 owner wait manifest: " + file.toAbsolutePath());
    }

    @Test
    void genuineV18CompleteHistoryUpgradesThroughV20AndPreservesPriorChecksums() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = v15RuntimeFixture(owner, sql, "bulk_v18_runtime");
            initializePendingHistoricalV18(owner, roles);
            // The real canonical owner bootstrap creates COMPLETE18, never a fabricated marker UPDATE.
            var bootstrap = BulkExecutionMigrator.class.getDeclaredMethod("completeCapacityReadBootstrap",
                    DataSource.class, BulkExecutionRoleConfiguration.class);
            bootstrap.setAccessible(true);
            invokePublished(bootstrap, owner, roles);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            var before = historicalV18Rows(sql);
            var grantsBefore = historicalV18CompleteAcl(sql);
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, roles))
                    .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class)
                    .hasMessageContaining("Detected resolved migration not applied to database: 19.");
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(2);
            assertHistoricalV18RowsUnchanged(sql, before);
            assertExactV19InternalCapacityAclDelta(sql, grantsBefore, roles);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='19' and success", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            BulkExecutionMigrator.validate(owner, roles);
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isZero();
            assertHistoricalV18RowsUnchanged(sql, before);
            assertExactV19InternalCapacityAclDelta(sql, grantsBefore, roles);
        }
    }

    @Test
    void v18PendingBootstrapRejectsRoguePartialGrantThenRetriesFromExactZeroAcl() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = pendingV15RuntimeFixtureWithoutBootstrapGrants(owner, sql, "bulk_v16_runtime");
            initializePendingHistoricalV18(owner, roles);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("PENDING");
            sql.execute("grant select on praxis_bulk.praxis_bulk_capacity_marker to bulk_v16_runtime");
            var before = historicalV18Rows(sql);
            var grantsBefore = historicalV18ReadPrivileges(sql);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bulk runtime privilege exceeds its table allowlist:")
                    .hasMessageContaining("praxis_bulk_capacity_marker");
            assertHistoricalV18RowsUnchanged(sql, before);
            assertThat(historicalV18ReadPrivileges(sql)).isEqualTo(grantsBefore);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='19'", Integer.class)).isZero();
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("PENDING");
            assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime', "
                    + "'praxis_bulk.praxis_bulk_capacity_marker', 'SELECT')", Boolean.class)).isTrue();
            assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime', "
                    + "'praxis_bulk.praxis_bulk_capacity_installation', 'SELECT')", Boolean.class)).isFalse();
            sql.execute("revoke select on praxis_bulk.praxis_bulk_capacity_marker from bulk_v16_runtime");
            assertThat(BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                    .isEqualTo(2);
            for (String version : List.of("19", "20"))
                assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                        + "where version=? and success", Integer.class, version)).isEqualTo(1);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            BulkExecutionMigrator.validate(owner, roles);
        }
    }

    @Test
    void grantThenBeforeCompleteFailureRollsBackBothV18SelectsAndKeepsPending() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = pendingV15RuntimeFixtureWithoutBootstrapGrants(owner, sql, "bulk_v16_runtime");
            initializePendingHistoricalV18(owner, roles);
            var before = historicalV18Rows(sql);
            var grantsBefore = historicalV18ReadPrivileges(sql);
            var failOnCas = new FailCapacityBootstrapCasDataSource(
                    postgres.getJdbcUrl("postgres", "postgres"), "bulk_v16_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(failOnCas,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                    .isInstanceOf(RuntimeException.class)
                    .satisfies(failure -> {
                        Throwable cause = failure;
                        while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
                        assertThat(cause).isInstanceOfSatisfying(SQLException.class,
                                sqlFailure -> assertThat(sqlFailure.getSQLState()).isEqualTo("XX000"));
                    });
            assertHistoricalV18RowsUnchanged(sql, before);
            assertThat(historicalV18ReadPrivileges(sql)).isEqualTo(grantsBefore);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='19'", Integer.class)).isZero();
            assertThat(failOnCas.failedAtCas()).isTrue();
            assertThat(failOnCas.sawBothGrantsInTransaction()).isTrue();
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("PENDING");
            for (String table : List.of("praxis_bulk_capacity_marker", "praxis_bulk_capacity_installation"))
                assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime', ?, 'SELECT')",
                        Boolean.class, "praxis_bulk." + table)).isFalse();
            assertThat(BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                    .isEqualTo(2);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            BulkExecutionMigrator.validate(owner, roles);
        }
    }

    @Test
    void twoIndependentMigratorsSerializeOnPendingV18LatchAndCompleteOnce() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = pendingV15RuntimeFixtureWithoutBootstrapGrants(owner, sql, "bulk_v16_runtime");
            initializePendingHistoricalV18(owner, roles);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("PENDING");
            var retainedRowsBefore = historicalV18Rows(sql);
            // This is the sole original row changed by the authorized PENDING -> COMPLETE CAS.
            retainedRowsBefore.remove("praxis_bulk_capacity_read_bootstrap");
            var historyBefore = sql.queryForList("select version, checksum from "
                    + "praxis_bulk.praxis_bulk_schema_history where version is not null order by installed_rank");
            String firstName = "b5b-v18-latch-first";
            String secondName = "b5b-v18-latch-second";
            String url = postgres.getJdbcUrl("postgres", "postgres");
            String querySeparator = url.contains("?") ? "&" : "?";
            DataSource firstOwner = new DriverManagerDataSource(url + querySeparator + "ApplicationName=" + firstName,
                    "postgres", "");
            DataSource secondOwner = new DriverManagerDataSource(url + querySeparator + "ApplicationName=" + secondName,
                    "postgres", "");
            var workers = Executors.newFixedThreadPool(2);
            Future<Integer> first = null;
            Future<Integer> second = null;
            try (Connection holder = owner.getConnection()) {
                holder.setAutoCommit(false);
                int holderPid;
                try (var statement = holder.createStatement();
                        var rows = statement.executeQuery("select pg_backend_pid()")) {
                    assertThat(rows.next()).isTrue();
                    holderPid = rows.getInt(1);
                    assertThat(rows.next()).isFalse();
                }
                try (var statement = holder.createStatement();
                        var rows = statement.executeQuery("select bootstrap_version from "
                                + "praxis_bulk.praxis_bulk_capacity_read_bootstrap "
                                + "where bootstrap_version=18 for update")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(18);
                    assertThat(rows.next()).isFalse();
                }
                var start = new CountDownLatch(1);
                first = workers.submit(() -> {
                    start.await();
                    int applied = BulkExecutionMigrator.migrate(firstOwner,
                            java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles);
                    assertThat(new JdbcTemplate(firstOwner).queryForObject(
                            "select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap", String.class))
                            .isEqualTo("COMPLETE");
                    return applied;
                });
                second = workers.submit(() -> {
                    start.await();
                    int applied = BulkExecutionMigrator.migrate(secondOwner,
                            java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles);
                    assertThat(new JdbcTemplate(secondOwner).queryForObject(
                            "select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap", String.class))
                            .isEqualTo("COMPLETE");
                    return applied;
                });
                start.countDown();
                boolean observed = false;
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
                try {
                    while (!observed && System.nanoTime() < deadline) {
                        var locks = sql.queryForList("""
                                select a.application_name, a.pid,
                                       array_to_json(pg_blocking_pids(a.pid))::text as blocker_pids,
                                       exists(select 1 from pg_locks l where l.pid=a.pid) as has_lock,
                                       exists(select 1 from pg_locks l where l.pid=a.pid and not l.granted)
                                           as waiting_lock,
                                       (?::integer = any(pg_blocking_pids(a.pid))) as blocked_by_holder,
                                       exists(select 1 from pg_locks l where l.pid=a.pid and not l.granted
                                           and l.locktype='advisory' and l.classid=1347574124
                                           and l.objid=5 and l.objsubid=2) as waiting_owner_advisory,
                                       exists(select 1 from pg_stat_activity blocker
                                           where blocker.pid=any(pg_blocking_pids(a.pid))
                                             and blocker.application_name in (?,?)
                                             and blocker.application_name<>a.application_name) as blocked_by_peer
                                from pg_stat_activity a
                                where a.application_name in (?,?) and a.backend_type='client backend'
                                """, holderPid, firstName, secondName, firstName, secondName);
                        var firstPids = locks.stream().filter(row -> firstName.equals(row.get("application_name")))
                                .filter(row -> Boolean.TRUE.equals(row.get("has_lock")))
                                .map(row -> ((Number) row.get("pid")).intValue()).distinct().toList();
                        var secondPids = locks.stream().filter(row -> secondName.equals(row.get("application_name")))
                                .filter(row -> Boolean.TRUE.equals(row.get("has_lock")))
                                .map(row -> ((Number) row.get("pid")).intValue()).distinct().toList();
                        boolean directBlock = locks.stream().anyMatch(row ->
                                Boolean.TRUE.equals(row.get("waiting_lock"))
                                        && Boolean.TRUE.equals(row.get("blocked_by_holder")));
                        boolean peerAdvisoryBlock = locks.stream().anyMatch(row ->
                                Boolean.TRUE.equals(row.get("waiting_owner_advisory"))
                                        && Boolean.TRUE.equals(row.get("blocked_by_peer")));
                        observed = !firstPids.isEmpty() && !secondPids.isEmpty()
                                && !firstPids.getFirst().equals(secondPids.getFirst()) && directBlock
                                && peerAdvisoryBlock;
                        if (observed) recordV18OwnerWaitEdges(locks, holderPid);
                        if (!observed) Thread.sleep(20);
                    }
                } finally {
                    holder.commit();
                }
                assertThat(observed).as("one owner's V18 bootstrap waits for the holder; its peer waits for owner coordination")
                        .isTrue();
                var counts = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
                assertThat(counts).allMatch(count -> count >= 0 && count <= 2);
                assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
            } finally {
                if (first != null) first.cancel(true);
                if (second != null) second.cancel(true);
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS))
                        .as("both migrator threads stopped after bounded cleanup").isTrue();
            }
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForList("select version, checksum from "
                    + "praxis_bulk.praxis_bulk_schema_history where version is not null and version::integer<=18 "
                    + "order by installed_rank")).isEqualTo(historyBefore);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='19' and success", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='20' and success", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='18'", Integer.class)).isEqualTo(1);
            for (String table : List.of("praxis_bulk_capacity_marker", "praxis_bulk_capacity_installation")) {
                assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime', ?, 'SELECT')",
                        Boolean.class, "praxis_bulk." + table)).isTrue();
                assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table, Integer.class)).isZero();
            }
            assertHistoricalV18RowsUnchanged(sql, retainedRowsBefore);
            BulkExecutionMigrator.validate(owner, roles);
        }
    }

    /** Exact PENDING fixture: only V7 base privileges and the host-owned V14 lock grant. */
    private static BulkExecutionRoleConfiguration pendingV15RuntimeFixtureWithoutBootstrapGrants(
            DataSource owner, JdbcTemplate sql, String role) {
        migrateToVersion(owner, "7");
        var roles = BulkPostgresTestSupport.grantRuntimeRole(owner, role);
        migrateToVersion(owner, "15");
        sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to " + role);
        assertThat(sql.queryForObject("select max(version::integer) from praxis_bulk.praxis_bulk_schema_history "
                + "where version is not null", Integer.class)).isEqualTo(15);
        for (String marker : List.of("manifest", "preview", "preview_integrity", "preview_reader"))
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_" + marker + "_bootstrap",
                    String.class)).isEqualTo("PENDING");
        for (String table : List.of("target_manifest", "preview_state", "target_preview", "preview_item_integrity"))
            assertThat(sql.queryForObject("select has_table_privilege(?, 'praxis_bulk.praxis_bulk_" + table
                    + "', 'SELECT') OR has_table_privilege(?, 'praxis_bulk.praxis_bulk_" + table + "', 'INSERT')",
                    Boolean.class, role, role)).isFalse();
        return roles;
    }

    @Test
    void initializerWaitsForOccupancyPhaseAndAclCommitBeforeAttestation() throws Exception {
        assertInitializerWaitsForOccupancyTransaction(false);
    }

    @Test
    void initializerWaitsForOccupancyRollbackThenCanonicalRetryCompletes() throws Exception {
        assertInitializerWaitsForOccupancyTransaction(true);
    }

    private static void assertInitializerWaitsForOccupancyTransaction(boolean rollBackHolder) throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var observer = new JdbcTemplate(owner);
            migrateToVersion(owner, "7");
            var roles = BulkPostgresTestSupport.grantRuntimeRole(owner, "bulk_c1_runtime");
            migrateToVersion(owner, "15");
            // Host-owned publication lock grant; no premature bootstrap-owned table grants.
            observer.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) "
                    + "to bulk_c1_runtime");
            String url = postgres.getJdbcUrl("postgres", "postgres");
            String separator = url.contains("?") ? "&" : "?";
            String secondName = "b5b-c1-initializer-" + (rollBackHolder ? "rollback" : "commit");
            var holder = new HoldOccupancyBootstrapDataSource(url + separator + "ApplicationName=b5b-c1-holder",
                    rollBackHolder);
            var secondOwner = new DriverManagerDataSource(url + separator + "ApplicationName=" + secondName,
                    "postgres", "");
            var deployments = java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID);
            var workers = Executors.newFixedThreadPool(2);
            Future<Integer> first = null;
            Future<Integer> second = null;
            try {
                first = workers.submit(() -> BulkExecutionMigrator.migrate(holder, deployments, roles));
                assertThat(holder.reached.await(8, TimeUnit.SECONDS)).as("real bootstrap19 owns its row latch").isTrue();
                assertThat(observer.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                        String.class)).isEqualTo("COMPLETE");
                assertThat(observer.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                        String.class)).isEqualTo("PENDING");
                assertThat(observer.queryForObject("select has_table_privilege('bulk_c1_runtime', "
                        + "'praxis_bulk.praxis_bulk_capacity_slot','SELECT')", Boolean.class)).isFalse();
                second = workers.submit(() -> BulkExecutionMigrator.migrate(secondOwner, deployments, roles));
                boolean observed = false;
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(4).toNanos();
                while (!observed && !second.isDone() && System.nanoTime() < deadline) {
                    observed = Boolean.TRUE.equals(observer.queryForObject("""
                            select exists(select 1 from pg_stat_activity a
                              where a.application_name=? and a.pid<>? and a.wait_event_type='Lock'
                                and a.query=? and ?=any(pg_blocking_pids(a.pid)))
                            """, Boolean.class, secondName, holder.pid.get(),
                            "select bootstrap_version, phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap for update",
                            holder.pid.get()));
                    if (!observed) java.util.concurrent.locks.LockSupport.parkNanos(
                            TimeUnit.MILLISECONDS.toNanos(10));
                }
                assertThat(observed).as("initializer PID waits on the real bootstrap19 holder before phase/ACL validation")
                        .isTrue();
                holder.release.countDown();
                if (rollBackHolder) {
                    Future<Integer> failed = first;
                    assertThatThrownBy(() -> failed.get(20, TimeUnit.SECONDS))
                            .isInstanceOf(java.util.concurrent.ExecutionException.class)
                            .hasRootCauseInstanceOf(SQLException.class);
                    assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
                    assertThat(BulkExecutionMigrator.migrate(holder, deployments, roles)).isZero();
                } else {
                    assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(5);
                }
                assertThat(observer.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                        String.class)).isEqualTo("COMPLETE");
                assertThat(observer.queryForObject("select has_table_privilege('bulk_c1_runtime', "
                        + "'praxis_bulk.praxis_bulk_capacity_slot','SELECT')", Boolean.class)).isTrue();
                assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                        + "where version='20' and success", Integer.class)).isEqualTo(1);
                BulkExecutionMigrator.validate(owner, roles);
                assertThat(BulkExecutionMigrator.migrate(secondOwner, deployments, roles)).isZero();
            } finally {
                holder.release.countDown();
                if (first != null) first.cancel(true);
                if (second != null) second.cancel(true);
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(observer.queryForObject("select count(*) from pg_stat_activity where "
                    + "application_name in ('b5b-c1-holder',?)", Integer.class, secondName)).isZero();
        }
    }

    /** Holds the actual bootstrap transaction after its real row lock; no production hooks. */
    private static final class HoldOccupancyBootstrapDataSource extends DriverManagerDataSource {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger pid = new AtomicInteger();
        private final AtomicBoolean held = new AtomicBoolean();
        private final boolean rollback;

        HoldOccupancyBootstrapDataSource(String url, boolean rollback) {
            super(url, "postgres", "");
            this.rollback = rollback;
        }

        @Override public Connection getConnection() throws SQLException {
            Connection physical = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(physical, args);
                            if (!method.getName().equals("createStatement")) return result;
                            var statement = (java.sql.Statement) result;
                            return Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(),
                                    new Class<?>[]{java.sql.Statement.class}, (statementProxy, operation, arguments) -> {
                                        try {
                                            Object value = operation.invoke(statement, arguments);
                                            if (operation.getName().equals("executeQuery") && arguments != null
                                                    && ("select bootstrap_version,phase from praxis_bulk."
                                                        + "praxis_bulk_capacity_occupancy_bootstrap for update").equals(arguments[0])
                                                    && held.compareAndSet(false, true)) {
                                                try (var check = physical.createStatement();
                                                     var rows = check.executeQuery("select pg_backend_pid()")) {
                                                    if (!rows.next()) throw new SQLException("missing fixture holder PID");
                                                    pid.set(rows.getInt(1));
                                                }
                                                reached.countDown();
                                                try {
                                                    if (!release.await(8, TimeUnit.SECONDS))
                                                        throw new SQLException("fixture occupancy barrier timed out", "XX000");
                                                } catch (InterruptedException failure) {
                                                    Thread.currentThread().interrupt();
                                                    throw new SQLException("fixture occupancy barrier interrupted", "XX000", failure);
                                                }
                                                if (rollback) {
                                                    ((java.sql.ResultSet) value).close();
                                                    throw new SQLException("test-only occupancy rollback before grants", "XX000");
                                                }
                                            }
                                            return value;
                                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    });
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }

    /** Test-only SQL failure exactly after observed GRANTs and before the durable phase CAS. */
    private static final class FailCapacityBootstrapCasDataSource extends DriverManagerDataSource {
        private final String runtimeRole;
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicBoolean sawGrants = new AtomicBoolean();

        FailCapacityBootstrapCasDataSource(String url, String runtimeRole) {
            super(url, "postgres", "");
            this.runtimeRole = runtimeRole;
        }

        boolean failedAtCas() { return failed.get(); }
        boolean sawBothGrantsInTransaction() { return sawGrants.get(); }

        @Override public Connection getConnection() throws SQLException {
            Connection physical = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        try {
                            Object value = method.invoke(physical, args);
                            if (method.getName().equals("prepareStatement") && args != null
                                    && args.length > 0 && args[0] instanceof String sql
                                    && sql.contains("set phase='COMPLETE' where bootstrap_version=18")
                                    && value instanceof PreparedStatement statement) {
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class}, (preparedProxy, preparedMethod,
                                                preparedArgs) -> {
                                            if (preparedMethod.getName().equals("executeUpdate")
                                                    && failed.compareAndSet(false, true)) {
                                                try (var check = physical.prepareStatement("""
                                                        select has_table_privilege(?,
                                                                  'praxis_bulk.praxis_bulk_capacity_marker','SELECT')
                                                           and has_table_privilege(?,
                                                                  'praxis_bulk.praxis_bulk_capacity_installation','SELECT')
                                                        """)) {
                                                    check.setString(1, runtimeRole);
                                                    check.setString(2, runtimeRole);
                                                    try (var rows = check.executeQuery()) {
                                                        sawGrants.set(rows.next() && rows.getBoolean(1));
                                                    }
                                                }
                                                throw new SQLException("test-only failure before capacity bootstrap CAS",
                                                        "XX000");
                                            }
                                            try { return preparedMethod.invoke(statement, preparedArgs); }
                                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                                        });
                            }
                            return value;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    @Test
    void v16WrongRuntimeRoleSetDeniesBeforeBootstrapAndCorrectRetryCompletesBeforeV20() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            // Construct the real historical PENDING boundary without pregranting
            // tables/functions whose V8/V9/V11/V12 owner bootstrap has not run.
            migrateToVersion(owner, "7");
            BulkPostgresTestSupport.grantRuntimeRole(owner, "bulk_v16_runtime");
            var incompleteRoles = BulkPostgresTestSupport.grantRuntimeRole(owner, "bulk_v16_second_runtime");
            migrateToVersion(owner, "15");
            // Only this host-owned V14 read grant precedes the owner bootstrap.
            sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) "
                    + "to bulk_v16_runtime,bulk_v16_second_runtime");
            for (String marker : List.of("manifest", "preview", "preview_integrity", "preview_reader"))
                assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_"
                        + marker + "_bootstrap", String.class)).isEqualTo("PENDING");
            for (String role : List.of("bulk_v16_runtime", "bulk_v16_second_runtime")) {
                for (String table : List.of("target_manifest", "preview_state", "target_preview", "preview_item_integrity"))
                    assertThat(sql.queryForObject("select has_table_privilege(?, 'praxis_bulk.praxis_bulk_"
                            + table + "', 'SELECT') OR has_table_privilege(?, 'praxis_bulk.praxis_bulk_"
                            + table + "', 'INSERT')", Boolean.class, role, role)).isFalse();
                assertThat(sql.queryForObject("select has_function_privilege(?, "
                        + "'praxis_bulk.assert_preview_integrity_complete()', 'EXECUTE')", Boolean.class, role)).isFalse();
            }
            var pendingHistory = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
            String runtimeAclSql = """
                    select c.relname, grantee.rolname, acl.privilege_type, acl.is_grantable
                    from pg_class c
                    cross join lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) acl
                    join pg_roles grantee on grantee.oid=acl.grantee
                    where c.relnamespace='praxis_bulk'::regnamespace
                      and grantee.rolname in ('bulk_v16_runtime','bulk_v16_second_runtime')
                    order by c.relname,grantee.rolname,acl.privilege_type
                    """;
            var baseRuntimeAcl = sql.queryForList(runtimeAclSql);
            // Both host base grants are real; omitting one from the declaration is invalid.
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), incompleteRoles))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("governed lifecycle function grants differ");
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank desc limit 1", String.class)).isEqualTo("19");
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                    + "where version is null OR version::integer<=15 order by installed_rank")).isEqualTo(pendingHistory);
            // New internal owner ACLs are expected DDL; existing runtime grants are not.
            assertThat(sql.queryForList(runtimeAclSql)).isEqualTo(baseRuntimeAcl);
            for (String marker : List.of("manifest", "preview", "preview_integrity", "preview_reader"))
                assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_"
                        + marker + "_bootstrap", String.class)).isEqualTo("PENDING");
            var historyBeforeRetry = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank");
            int checksum16 = sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='16'", Integer.class);
            assertAtomicBootstrapPhase(sql, "PENDING");
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("PENDING");
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                    String.class)).isEqualTo("PENDING");
            assertNoAtomicRuntimeGrants(sql, "bulk_v16_runtime");
            assertNoAtomicRuntimeGrants(sql, "bulk_v16_second_runtime");
            for (String role : List.of("bulk_v16_runtime", "bulk_v16_second_runtime")) {
                assertThat(sql.queryForObject("select has_table_privilege(?, "
                        + "'praxis_bulk.praxis_bulk_item_receipt','INSERT')", Boolean.class, role)).isTrue();
                assertThat(sql.queryForObject("select has_table_privilege(?, "
                        + "'praxis_bulk.praxis_bulk_capacity_marker','SELECT')", Boolean.class, role)).isFalse();
            }
            var completeRoles = new BulkExecutionRoleConfiguration("postgres",
                    java.util.Set.of("bulk_v16_runtime", "bulk_v16_second_runtime"),
                    java.util.Set.of(), java.util.Set.of());
            // Retry the real canonical bootstrap; never grant V16 ACLs from the fixture.
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), completeRoles)).isEqualTo(1);
            assertAtomicBootstrapPhase(sql, "COMPLETE");
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                    + "where version is null OR version::integer <= 19 order by installed_rank")).isEqualTo(historyBeforeRetry);
            assertThat(sql.queryForObject("select max(version::integer) from praxis_bulk.praxis_bulk_schema_history",
                    Integer.class)).isEqualTo(20);
            assertAtomicRuntimeGrants(sql, "bulk_v16_runtime");
            assertAtomicRuntimeGrants(sql, "bulk_v16_second_runtime");
            for (String role : List.of("bulk_v16_runtime", "bulk_v16_second_runtime"))
                assertThat(sql.queryForObject("select has_table_privilege(?, "
                        + "'praxis_bulk.praxis_bulk_capacity_marker','SELECT')", Boolean.class, role)).isTrue();
            assertThat(sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='16'", Integer.class)).isEqualTo(checksum16);
            BulkExecutionMigrator.validate(owner, completeRoles);
        }
    }

    @Test
    void v16CompleteBootstrapNeverHealsLateAtomicTableOrFunctionAclCorruption() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = pendingV15RuntimeFixtureWithoutBootstrapGrants(owner, sql, "bulk_v16_runtime");
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(5);
            assertAtomicBootstrapPhase(sql, "COMPLETE");
            for (String table : atomicRuntimeTables()) {
                sql.execute("revoke select on praxis_bulk." + table + " from bulk_v16_runtime");
                assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                        java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, roles))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime',?,'SELECT')",
                        Boolean.class, "praxis_bulk." + table)).isFalse();
                assertThat(sql.queryForObject("select has_table_privilege('bulk_v16_runtime',?,'INSERT')",
                        Boolean.class, "praxis_bulk." + table)).isTrue();
                assertAtomicBootstrapPhase(sql, "COMPLETE");
                // Explicit owner repair is observable and distinct from a migrator rerun.
                sql.execute("grant select on praxis_bulk." + table + " to bulk_v16_runtime");
                BulkExecutionMigrator.validate(owner, roles);
            }
            sql.execute("revoke execute on function praxis_bulk.atomic_evidence_complete(uuid,integer) "
                    + "from bulk_v16_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("governed lifecycle function grants differ");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("governed lifecycle function grants differ");
            assertThat(sql.queryForObject("select has_function_privilege('bulk_v16_runtime', "
                    + "'praxis_bulk.atomic_evidence_complete(uuid,integer)','EXECUTE')", Boolean.class)).isFalse();
            assertAtomicBootstrapPhase(sql, "COMPLETE");
        }
    }

    @Test
    void v16RejectsAPermissiveConstraintEvenWhenItsNameAndValidationFlagRemainCanonical() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(5);
            // Deliberate owner catalog corruption: the named, validated CHECK now permits >400 effects.
            sql.execute("alter table praxis_bulk.praxis_bulk_atomic_receipt "
                    + "drop constraint praxis_bulk_atomic_receipt_effect_count_check");
            sql.execute("alter table praxis_bulk.praxis_bulk_atomic_receipt "
                    + "add constraint praxis_bulk_atomic_receipt_effect_count_check check (effect_count>=0)");
            String definition = sql.queryForObject("select pg_get_constraintdef(oid) from pg_constraint "
                    + "where conrelid='praxis_bulk.praxis_bulk_atomic_receipt'::regclass "
                    + "and conname='praxis_bulk_atomic_receipt_effect_count_check' and convalidated",
                    String.class);
            assertThat(definition).contains("effect_count >= 0").doesNotContain("400");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V16 atomic constraint definition differs");
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("select pg_get_constraintdef(oid) from pg_constraint "
                    + "where conrelid='praxis_bulk.praxis_bulk_atomic_receipt'::regclass "
                    + "and conname='praxis_bulk_atomic_receipt_effect_count_check'", String.class))
                    .isEqualTo(definition);
            assertAtomicBootstrapPhase(sql, "COMPLETE");
        }
    }

    @Test
    void v16BootstrapMarkerRejectsRuntimeRetentionAndControlGrantsWithoutHealingThem() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
            BulkPostgresTestSupport.grantControlRole(owner, "bulk_v16_control");
            sql.execute("create role bulk_v16_retention login");
            sql.execute("grant praxis_bulk_retention_executor to bulk_v16_retention");
            var roles = new BulkExecutionRoleConfiguration("postgres", java.util.Set.of("bulk_v16_runtime"),
                    java.util.Set.of("bulk_v16_retention"), java.util.Set.of("bulk_v16_control"));
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(5);
            for (String role : List.of("bulk_v16_runtime", "bulk_v16_retention", "bulk_v16_control")) {
                for (String privilege : List.of("select", "update(phase)")) {
                    // Only the isolated owner deliberately corrupts a completed marker ACL.
                    sql.execute("grant " + privilege + " on praxis_bulk.praxis_bulk_atomic_bootstrap to " + role);
                    assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, roles))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("V16 bootstrap marker must remain owner-only");
                    assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                            java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(sql.queryForObject("select has_column_privilege(?, "
                            + "'praxis_bulk.praxis_bulk_atomic_bootstrap','phase',?)", Boolean.class,
                            role, privilege.equals("select") ? "SELECT" : "UPDATE")).isTrue();
                    assertAtomicBootstrapPhase(sql, "COMPLETE");
                    sql.execute("revoke " + privilege + " on praxis_bulk.praxis_bulk_atomic_bootstrap from " + role);
                    BulkExecutionMigrator.validate(owner, roles);
                }
            }
            assertAtomicRuntimeGrants(sql, "bulk_v16_runtime");
        }
    }

    @Test
    void v3CommittedProtocolOneReceiptStillReplaysAndAcknowledgesButNoOldWriterCanAppendEvidence()
            throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            migrateToV3(owner);
            var committed = insertExecution(owner, sql, "RUNNING", 0, false, Instant.now().minusSeconds(5),
                    BulkScopeDigests.idempotencyKeyDigest("protocol-one-committed"));
            var uncommitted = insertExecution(owner, sql, "RUNNING", 0, false, Instant.now().minusSeconds(5),
                    BulkScopeDigests.idempotencyKeyDigest("protocol-one-old-writer"));
            UUID committedAttempt = UUID.randomUUID();
            UUID oldAttempt = UUID.randomUUID();
            // These are actual V3 writer shapes before V16 introduces the protocol discriminator.
            for (var fixture : List.of(committed, uncommitted))
                sql.update("""
                        update praxis_bulk.praxis_bulk_execution set status='UNIT_IN_FLIGHT',
                        active_attempt_id=?,active_attempt_ordinal=0,active_target_digest=?,active_attempt_epoch=1,
                        updated_at=clock_timestamp() where execution_id=?
                        """, fixture == committed ? committedAttempt : oldAttempt, fixture.digest0(), fixture.id());
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_item_receipt
                    (execution_id,unit_ordinal,target_digest,expected_version,attempt_id,owner_epoch,outcome,confirmed_at)
                    values (?,0,?,'v1',?,1,'CONFIRMED',clock_timestamp())
                    """, committed.id(), committed.digest0(), committedAttempt);
            sql.update("update praxis_bulk.praxis_bulk_execution set status='UNIT_COMMITTED_PENDING_ACK', "
                    + "updated_at=clock_timestamp() where execution_id=?", committed.id());
            byte[] proposalBytes = sql.queryForObject("select payload from praxis_bulk.praxis_bulk_proposal "
                    + "where proposal_id=?", byte[].class, committed.proposalId());
            var receiptBefore = sql.queryForList("select execution_id,unit_ordinal,target_digest,expected_version,"
                    + "attempt_id,owner_epoch,outcome,confirmed_at from praxis_bulk.praxis_bulk_item_receipt");
            assertThat(migrate(owner)).isEqualTo(17);
            assertThat(sql.queryForObject("select payload from praxis_bulk.praxis_bulk_proposal "
                    + "where proposal_id=?", byte[].class, committed.proposalId())).containsExactly(proposalBytes);
            assertThat(sql.queryForList("select execution_id,unit_ordinal,target_digest,expected_version,"
                    + "attempt_id,owner_epoch,outcome,confirmed_at from praxis_bulk.praxis_bulk_item_receipt"))
                    .isEqualTo(receiptBefore);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution "
                    + "where atomicity='PER_ITEM' and protocol_version=1", Integer.class)).isEqualTo(2);
            BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            var roles = BulkPostgresTestSupport.grantRuntimeRole(owner, "bulk_v16_runtime");
            var runtime = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_v16_runtime", "postgres"),
                    "bulk_v16_runtime", "");
            var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(runtime,
                    new DataSourceTransactionManager(runtime), CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID, roles));
            var callbacks = new AtomicInteger();
            BulkUnitAdmissionCallback admission = unit -> {
                callbacks.incrementAndGet();
                throw new AssertionError("protocol one must never start fresh admission");
            };
            BulkUnitMutationCallback mutation = unit -> {
                callbacks.incrementAndGet();
                throw new AssertionError("historical receipt must never repeat mutation");
            };
            var control = new BulkExecutionControl(committed.id(), "owner", 1);
            var acknowledged = kernel.executeUnit(control, 0, admission, mutation);
            assertThat(acknowledged.replayed()).isTrue();
            assertThat(acknowledged.receiptPresent()).isTrue();
            assertThat(acknowledged.execution().status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
            assertThat(acknowledged.execution().nextOrdinal()).isEqualTo(1);
            assertThat(kernel.executeUnit(control, 0, admission, mutation).replayed()).isTrue();
            assertProtocolOneFreshCallbackDenied(() -> kernel.executeUnit(control, 1, admission, mutation));
            assertProtocolOneFreshCallbackDenied(() -> kernel.executeUnit(
                    new BulkExecutionControl(uncommitted.id(), "owner", 1), 0, admission, mutation));
            var runtimeSql = new JdbcTemplate(runtime);
            assertThatThrownBy(() -> runtimeSql.update("""
                    insert into praxis_bulk.praxis_bulk_item_receipt
                    (execution_id,unit_ordinal,target_digest,expected_version,attempt_id,owner_epoch,outcome,confirmed_at)
                    values (?,0,?,'v1',?,1,'CONFIRMED',clock_timestamp())
                    """, uncommitted.id(), uncommitted.digest0(), oldAttempt))
                    .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                            error -> assertProtocolOneSqlGuard(error));
            assertThatThrownBy(() -> insertAdmissionRaw(runtimeSql, uncommitted.id(), oldAttempt,
                    0, uncommitted.digest0(), "DENIED", "TARGET_DENIED"))
                    .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                            error -> assertProtocolOneSqlGuard(error));
            assertThat(callbacks).hasValue(0);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt", Integer.class))
                    .isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_admission", Integer.class))
                    .isZero();
            BulkExecutionMigrator.validate(owner, roles);
        }
    }

    @Test
    void v16CutoverDrainsHistoricalReadyAndCannotReserveItsProtocolOneProposal() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            var roles = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
            initializeHistoricalV15WithPublishedSdk(owner, roles);
            for (var marker : java.util.Map.of("manifest", 8, "preview", 9,
                    "preview_integrity", 11, "preview_reader", 12).entrySet())
                assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_"
                        + marker.getKey() + "_bootstrap where bootstrap_version=?", String.class, marker.getValue()))
                        .as("historical V%s bootstrap completed by the published SDK", marker.getValue())
                        .isEqualTo("COMPLETE");
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                    + "where success and version is not null", Integer.class)).isEqualTo(15);
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "where version is not null order by installed_rank desc limit 1", String.class)).isEqualTo("15");
            assertThat(sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_atomic_receipt') is null",
                    Boolean.class)).isTrue();
            // The historical migrator initializes deny-only controls; this helper composes the fixture tuple by CAS.
            BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            var expectedControl = BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION;
            assertThat(sql.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_namespace_binding b
                    join praxis_bulk.praxis_bulk_openapi_publication p on p.deployment_id=b.deployment_id
                    join praxis_bulk.praxis_bulk_operation_control c on c.namespace_id=b.namespace_id
                    where b.namespace_id=? and b.deployment_id=? and c.operation_id=?
                      and p.state='PUBLISHED' and p.generation=2 and p.document_digest=?
                      and c.state='READY' and c.generation=? and c.descriptor_fingerprint=?
                      and c.structural_revision=? and c.publication_generation=p.generation
                      and c.publication_document_digest=p.document_digest
                    """, Integer.class, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                    CONTEXT.operationRef().operationId(), "sha256:" + "0".repeat(64), expectedControl.generation(),
                    expectedControl.descriptorFingerprint(), expectedControl.structuralRevision()))
                    .as("before the V15 write: published fixture CAS and the exact proposal control tuple")
                    .isEqualTo(1);
            var fixture = insertProposalAndOptionalExecution(owner, sql, "RUNNING", 0, false,
                    Instant.now().minusSeconds(5), FINGERPRINT, false);
            assertThat(fixture.id()).isNull();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution", Integer.class))
                    .isZero();
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "where version is not null order by installed_rank desc limit 1", String.class)).isEqualTo("15");
            byte[] proposalBytes = sql.queryForObject("select payload from praxis_bulk.praxis_bulk_proposal "
                    + "where proposal_id=?", byte[].class, fixture.proposalId());
            assertThat(BulkExecutionMigrator.migrate(owner, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isEqualTo(5);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal "
                    + "where proposal_id=? and atomicity='PER_ITEM' and protocol_version=1 "
                    + "and control_generation=1 and control_structural_revision='structural-r1'",
                    Integer.class, fixture.proposalId())).isEqualTo(1);
            assertThat(sql.queryForObject("select payload from praxis_bulk.praxis_bulk_proposal "
                    + "where proposal_id=?", byte[].class, fixture.proposalId())).containsExactly(proposalBytes);
            // A real cutover drains serving authority; this proves stale-input denial, not an isolated SDK protocol gate.
            assertThat(sql.queryForMap("""
                    select state,generation,descriptor_fingerprint,structural_revision,
                           publication_generation,publication_document_digest
                    from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?
                    """, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId()))
                    .containsEntry("state", "SUSPENDED").containsEntry("generation", 2L)
                    .containsEntry("descriptor_fingerprint", null).containsEntry("structural_revision", null)
                    .containsEntry("publication_generation", null).containsEntry("publication_document_digest", null);
            assertThat(sql.queryForMap("""
                    select state,generation,document_digest from praxis_bulk.praxis_bulk_openapi_publication
                    where deployment_id=?
                    """, BulkPostgresTestSupport.DEPLOYMENT_ID))
                    .containsEntry("state", "SUSPENDED").containsEntry("generation", 3L)
                    .containsEntry("document_digest", null);
            assertThat(sql.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_proposal p
                    join praxis_bulk.praxis_bulk_operation_control c using(namespace_id,operation_id)
                    where p.proposal_id=? and p.control_generation is distinct from c.generation
                      and p.control_descriptor_fingerprint is distinct from c.descriptor_fingerprint
                      and p.control_structural_revision is distinct from c.structural_revision
                    """, Integer.class, fixture.proposalId())).isEqualTo(1);
            var allocationsBefore = sql.queryForList("select * from praxis_bulk.praxis_bulk_allocation "
                    + "order by kind,proposal_id,execution_id");
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                    + "where kind='PROPOSAL_PENDING' and state='PENDING' and proposal_id=?",
                    Integer.class, fixture.proposalId())).isEqualTo(1);
            var runtime = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_v16_runtime", "postgres"),
                    "bulk_v16_runtime", "");
            var kernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(runtime,
                    new DataSourceTransactionManager(runtime), CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID, roles));
            var callbacks = new AtomicInteger();
            var reservations = new AtomicInteger();
            assertProtocolOneFreshCallbackDenied(() -> {
                var reservation = kernel.reserve(CONTEXT, fixture.proposalId(), "protocol-one-fresh-reserve",
                        "new-owner", "structural-r1", Instant.now().plusSeconds(30));
                reservations.incrementAndGet();
                kernel.executeUnit(reservation.control(), 0, unit -> {
                    callbacks.incrementAndGet();
                    throw new AssertionError("protocol one proposal cannot start admission");
                }, unit -> {
                    callbacks.incrementAndGet();
                    throw new AssertionError("protocol one proposal cannot start mutation");
                });
            });
            assertThat(reservations).hasValue(0);
            assertThat(callbacks).hasValue(0);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution", Integer.class))
                    .isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                    + "where kind='EXECUTION_ACTIVE'", Integer.class)).isZero();
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_allocation "
                    + "order by kind,proposal_id,execution_id")).isEqualTo(allocationsBefore);
            BulkExecutionMigrator.validate(owner, roles);
        }
    }

    private static void assertProtocolOneSqlGuard(org.springframework.dao.DataAccessException error) {
        assertThat(error.getRootCause()).isInstanceOf(java.sql.SQLException.class);
        var cause = (java.sql.SQLException) error.getRootCause();
        assertThat(cause.getSQLState()).isEqualTo("55000");
        assertThat((Throwable) cause).hasMessageContaining("per-item evidence cannot bind an atomic execution");
    }

    private static void assertProtocolOneFreshCallbackDenied(Runnable callback) {
        assertThatThrownBy(callback::run).isInstanceOfSatisfying(BulkDurableExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
    }

    /**
     * Executes only the published V15 SDK's public migrate/validate APIs. Its classes and Flyway
     * resources are isolated from this candidate's class directories and metadata JARs; ordinary
     * dependency JARs remain available. This proves historical bootstrap without editing markers
     * or disabling guards, and does not attribute fixture READY or HTTP publication to the old SDK.
     */
    private static void initializeHistoricalV15WithPublishedSdk(DataSource owner,
            BulkExecutionRoleConfiguration roles) throws Exception {
        var jar = java.nio.file.Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                "target", "historical-bulk-sdk", "metadata-v15-rc149.jar").toRealPath();
        var thread = Thread.currentThread();
        var originalLoader = thread.getContextClassLoader();
        try (var historical = isolatedPublishedSdk(jar,
                "a0bd4137726acdced16fb1c193a8db7e6c6c1e26b23a4ba9294ccb8da5ddb2cc")) {
            thread.setContextClassLoader(historical);
            try {
                var migrator = Class.forName("org.praxisplatform.uischema.bulk.BulkExecutionMigrator", true, historical);
                var roleType = Class.forName("org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration", true,
                        historical);
                for (var type : List.of(migrator, roleType))
                    assertThat(java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .toRealPath()).as("historical public API CodeSource").isEqualTo(jar);
                assertThat(historical.getResource("db/praxis-bulk-migrations/V16__bulk_atomic_set_execution.sql"))
                        .as("the historical Flyway resource set excludes the candidate V16").isNull();
                assertThat(historical.getResource("db/praxis-bulk-migrations/V17__bulk_pending_quota_snapshot_fence.sql"))
                        .as("the historical rc149 Flyway resource set excludes V17").isNull();
                Object historicalRoles = roleType.getConstructor(String.class, java.util.Set.class,
                        java.util.Set.class, java.util.Set.class).newInstance(roles.expectedSchemaOwnerRole(),
                        roles.runtimeGranteeRoles(), roles.retentionExecutorMembers(), roles.controlPlaneGranteeRoles());
                Object applied = migrator.getMethod("migrate", DataSource.class, java.util.Map.class, roleType)
                        .invoke(null, owner, java.util.Map.of(CONTEXT.namespaceId(),
                                BulkPostgresTestSupport.DEPLOYMENT_ID), historicalRoles);
                assertThat(applied).isEqualTo(0);
                migrator.getMethod("validate", DataSource.class, roleType).invoke(null, owner, historicalRoles);
            } catch (InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof Exception failure) throw failure;
                if (cause instanceof Error failure) throw failure;
                throw new IllegalStateException("Historical public SDK invocation failed", cause);
            } finally {
                thread.setContextClassLoader(originalLoader);
            }
        }
    }

    private static java.net.URLClassLoader isolatedPublishedSdk(java.nio.file.Path jar, String sha256)
            throws Exception {
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.nio.file.Files.readAllBytes(jar))))
                .as("published historical artifact from Maven Central").isEqualTo(sha256);
        var jars = new java.util.LinkedHashSet<java.nio.file.Path>();
        jars.add(jar);
        String classPath = System.getProperty("surefire.test.class.path");
        if (classPath == null || classPath.isBlank()) classPath = System.getProperty("java.class.path");
        for (String entry : classPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            var dependency = java.nio.file.Path.of(entry);
            if (!java.nio.file.Files.isRegularFile(dependency) || !entry.endsWith(".jar")) continue;
            dependency = dependency.toRealPath();
            try (var archive = new java.util.jar.JarFile(dependency.toFile())) {
                if (archive.getJarEntry("org/praxisplatform/uischema/bulk/BulkExecutionMigrator.class") != null)
                    continue;
            }
            jars.add(dependency);
        }
        var urls = new java.net.URL[jars.size()];
        int index = 0;
        for (var dependency : jars) urls[index++] = dependency.toUri().toURL();
        return new java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    /** Physical historical V15 DDL and host ACLs; canonical bootstrap is exercised by each test. */
    private static BulkExecutionRoleConfiguration v15RuntimeFixture(DataSource owner, JdbcTemplate sql, String role) {
        migrateToVersion(owner, "15");
        // Flyway's SCHEMA marker has no version and is not one of the fifteen historical migrations.
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history "
                + "where success and version is not null", Integer.class)).isEqualTo(15);
        assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                + "order by installed_rank desc limit 1", String.class)).isEqualTo("15");
        assertThat(sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_atomic_receipt') is null",
                Boolean.class)).isTrue();
        return BulkPostgresTestSupport.grantRuntimeRole(owner, role);
    }

    private static List<String> atomicRuntimeTables() {
        return List.of("praxis_bulk_atomic_receipt", "praxis_bulk_atomic_item_result",
                "praxis_bulk_atomic_effect_ref", "praxis_bulk_atomic_rejection");
    }

    /** Explicit host grants for a newly configured runtime role after a completed HEAD migration. */
    private static void grantCapacityOccupancyRuntimeAccessForFreshRole(JdbcTemplate sql, String role) {
        String quoted = '"' + role.replace("\"", "\"\"") + '"';
        sql.execute("grant select on praxis_bulk.praxis_bulk_capacity_slot, "
                + "praxis_bulk.praxis_bulk_capacity_occupation to " + quoted);
        sql.execute("grant execute on function praxis_bulk.lock_capacity_marker(), "
                + "praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint) to " + quoted);
    }

    private static void grantAtomicRuntimeAccessForFreshRole(JdbcTemplate sql, String role) {
        for (String table : atomicRuntimeTables())
            sql.execute("grant select, insert on praxis_bulk." + table + " to " + role);
        sql.execute("grant execute on function praxis_bulk.atomic_evidence_complete(uuid,integer) to " + role);
    }

    private static void assertAtomicBootstrapPhase(JdbcTemplate sql, String phase) {
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_atomic_bootstrap "
                + "where bootstrap_version=16 and phase=?", Integer.class, phase)).isEqualTo(1);
    }

    private static void assertAtomicRuntimeGrants(JdbcTemplate sql, String role) {
        for (String table : atomicRuntimeTables()) {
            assertThat(sql.queryForList("""
                    select acl.privilege_type from pg_class c
                    cross join lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) acl
                    where c.oid=to_regclass(?) and acl.grantee=(select oid from pg_roles where rolname=?)
                    order by acl.privilege_type
                    """, String.class, "praxis_bulk." + table, role)).containsExactly("INSERT", "SELECT");
            assertThat(sql.queryForObject("select has_table_privilege(?,?,'UPDATE,DELETE')",
                    Boolean.class, role, "praxis_bulk." + table)).isFalse();
        }
        assertThat(sql.queryForObject("select has_function_privilege(?, "
                + "'praxis_bulk.atomic_evidence_complete(uuid,integer)','EXECUTE')", Boolean.class, role)).isTrue();
        assertThat(sql.queryForObject("select has_table_privilege(?, "
                + "'praxis_bulk.praxis_bulk_atomic_bootstrap','SELECT,INSERT,UPDATE,DELETE')",
                Boolean.class, role)).isFalse();
    }

    private static void assertNoAtomicRuntimeGrants(JdbcTemplate sql, String role) {
        for (String table : atomicRuntimeTables())
            assertThat(sql.queryForObject("select has_table_privilege(?,?,'SELECT,INSERT,UPDATE,DELETE')",
                    Boolean.class, role, "praxis_bulk." + table)).isFalse();
        assertThat(sql.queryForObject("select has_function_privilege(?, "
                + "'praxis_bulk.atomic_evidence_complete(uuid,integer)','EXECUTE')", Boolean.class, role)).isFalse();
    }

    @Test
    void v10CancellationCatalogRejectsPrivilegeTriggerConstraintAndOwnerDrift() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkExecutionMigrator.validate(dataSource);

            sql.execute("grant execute on function praxis_bulk.protect_cancel_request() to public");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.execute("revoke execute on function praxis_bulk.protect_cancel_request() from public");
            BulkExecutionMigrator.validate(dataSource);

            sql.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_cancel");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_cancel");
            BulkExecutionMigrator.validate(dataSource);

            sql.execute("create role bulk_v10_wrong_owner nologin");
            sql.execute("alter function praxis_bulk.protect_cancel_request() owner to bulk_v10_wrong_owner");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.execute("alter function praxis_bulk.protect_cancel_request() owner to postgres");
            BulkExecutionMigrator.validate(dataSource);

            sql.execute("alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_cancel_shape_check");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** Genuine historical DDL, transferred to the documented LOGIN/INHERIT/CREATEROLE owner. */
    private static DriverManagerDataSource createDedicatedV7SchemaOwner(EmbeddedPostgres postgres,
                                                                       DataSource admin) {
        var sql = new JdbcTemplate(admin);
        Flyway.configure().dataSource(admin).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("7").load().migrate();
        sql.execute("create role bulk_schema_owner login createrole");
        sql.execute("""
                do $$ declare item record; begin
                  for item in select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                       where n.nspname='praxis_bulk' and c.relkind in ('r','p')
                       and c.relowner=(select oid from pg_roles where rolname='postgres') loop
                    execute format('alter table praxis_bulk.%I owner to bulk_schema_owner',item.relname);
                  end loop;
                  for item in select p.oid::regprocedure as signature from pg_proc p
                       join pg_namespace n on n.oid=p.pronamespace where n.nspname='praxis_bulk'
                       and p.proowner=(select oid from pg_roles where rolname='postgres') loop
                    execute format('alter function %s owner to bulk_schema_owner',item.signature);
                  end loop;
                  alter schema praxis_bulk owner to bulk_schema_owner;
                end $$
                """);
        return new DriverManagerDataSource(postgres.getJdbcUrl("bulk_schema_owner", "postgres"),
                "bulk_schema_owner", "");
    }

    @Test
    void v10MigrationRunsAsDedicatedNonSuperuserOwner() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var admin = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(admin);
            var owner = createDedicatedV7SchemaOwner(postgres, admin);
            String guardAclBefore = sql.queryForObject("select proacl::text from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class);
            assertThat(new JdbcTemplate(owner).queryForObject(
                    "select rolsuper from pg_roles where rolname=current_user", Boolean.class)).isFalse();
            BulkPostgresTestSupport.grantRuntimeRole(admin,"bulk_runtime_test");
            var roles=new BulkExecutionRoleConfiguration("bulk_schema_owner",
                    java.util.Set.of("bulk_runtime_test"),java.util.Set.of(),java.util.Set.of());
            // V14 never repairs host EXECUTE grants: the deployment owner explicitly
            // provisions the new read boundary after the migration has created it.
            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("governed lifecycle function grants differ");
            assertThat(sql.queryForObject("""
                    select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                    cross join lateral aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) acl
                    where n.nspname='praxis_bulk'
                      and p.proname in ('lock_openapi_publication','transition_openapi_publication')
                      and acl.grantee=0 and acl.privilege_type='EXECUTE'
                    """,Integer.class)).isZero();
            assertThat(sql.queryForObject("select has_function_privilege('bulk_runtime_test', "
                    + "'praxis_bulk.lock_openapi_publication(text,text)','EXECUTE')",Boolean.class)).isFalse();
            assertThat(sql.queryForObject("select count(*) from pg_proc p "
                    + "cross join lateral aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) acl "
                    + "where p.oid='praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text)'::regprocedure "
                    + "and acl.grantee=0",Integer.class)).isZero();
            sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime_test");
            assertThat(BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles))
                    .isEqualTo(1);
            BulkExecutionMigrator.validate(owner,roles);
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            assertNoCapacityOwnerCapability(sql, "bulk_schema_owner");
            assertNoCapacityOwnerCapability(sql, "bulk_runtime_test");
            var completeSnapshot = occupancyBootstrapAuthoritySnapshot(sql);
            assertThat(BulkExecutionMigrator.migrate(owner,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles)).isZero();
            assertThat(occupancyBootstrapAuthoritySnapshot(sql)).isEqualTo(completeSnapshot);
            try (var runtime = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_runtime_test", "postgres"),
                    "bulk_runtime_test", "").getConnection(); var statement = runtime.createStatement()) {
                assertThatThrownBy(() -> statement.execute("set role praxis_bulk_capacity_owner"))
                        .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42501");
            }
            assertThat(sql.queryForObject("""
                    select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_manifest','select,insert')
                    """,Boolean.class)).isTrue();
            assertThat(sql.queryForObject("""
                    select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid
                    join pg_roles member on member.oid=m.member
                    where r.rolname='praxis_bulk_retention_owner' and member.rolname='bulk_schema_owner'
                    """,Integer.class)).isZero();
            assertThat(sql.queryForObject("select has_schema_privilege('praxis_bulk_retention_owner', "
                    + "'praxis_bulk', 'CREATE')", Boolean.class)).isFalse();
            assertThat(sql.queryForObject("select owner.rolname from pg_proc p join pg_roles owner "
                    + "on owner.oid=p.proowner where p.oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class))
                    .isEqualTo("praxis_bulk_retention_owner");
            assertThat(sql.queryForObject("select proacl::text from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class))
                    .isEqualTo(guardAclBefore);
            assertThat(sql.queryForObject("""
                    select owner.rolname from pg_class c join pg_roles owner on owner.oid=c.relowner
                     where c.oid='praxis_bulk.praxis_bulk_target_manifest'::regclass
                    """,String.class)).isEqualTo("bulk_schema_owner");
        }
    }

    @Test
    void v19OccupancyBootstrapRollsBackOwnerMembershipAclAndPhaseOnSqlFailure() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var admin = postgres.getPostgresDatabase();
            var observer = new JdbcTemplate(admin);
            var owner = createDedicatedV7SchemaOwner(postgres, admin);
            BulkPostgresTestSupport.grantRuntimeRole(admin, "bulk_runtime_test");
            // DDL-only PENDING fixture: this proves the V19 owner bootstrap transaction,
            // not serving readiness or a one-connection pool for public migration.
            Flyway.configure().dataSource(owner).locations("classpath:db/praxis-bulk-migrations")
                    .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                    .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                    .target("19").load().migrate();
            var roles = new BulkExecutionRoleConfiguration("bulk_schema_owner",
                    java.util.Set.of("bulk_runtime_test"), java.util.Set.of(), java.util.Set.of());
            var pending = occupancyBootstrapAuthoritySnapshot(observer);
            assertThat(observer.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                    String.class)).isEqualTo("PENDING");
            try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
                pool.setDataSource(owner);
                pool.setMaximumPoolSize(1);
                pool.setMinimumIdle(1);
                pool.setConnectionTimeout(1000);
                java.util.Map<String, Object> before;
                try (var connection = pool.getConnection()) {
                    before = bootstrapOwnerSession(connection);
                    assertThat(connection.getAutoCommit()).isTrue();
                }
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
                transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.setTimeout(10);
                var ownerSql = new JdbcTemplate(pool);
                assertThatThrownBy(() -> transaction.execute(status -> ownerSql.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                            try (var statement = connection.createStatement()) {
                                statement.execute("set local search_path to pg_catalog");
                            }
                            BulkCapacityOccupancyCatalog.bootstrap(connection, roles);
                            assertThat(bootstrapOwnerSession(connection).get("current_user"))
                                    .isEqualTo("bulk_schema_owner");
                            assertThat(ownerSql.queryForObject(
                                    "select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                                    String.class)).isEqualTo("COMPLETE");
                            assertNoCapacityOwnerCapability(ownerSql, "bulk_schema_owner");
                            assertThat(ownerSql.queryForObject("select has_function_privilege('bulk_runtime_test', "
                                    + "'praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint)','EXECUTE')",
                                    Boolean.class)).isTrue();
                            // Genuine SQL abort after grants/completion: the existing owner TX
                            // must roll back all effects, without any commit fault or proxy.
                            try (var statement = connection.createStatement()) {
                                statement.execute("select 1/0");
                            }
                            return null;
                        }))).isInstanceOf(org.springframework.dao.DataAccessException.class)
                        .hasRootCauseInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error.getCause()).getSQLState())
                                .isEqualTo("22012"));
                try (var connection = pool.getConnection()) {
                    assertThat(connection.getAutoCommit()).isTrue();
                    assertThat(bootstrapOwnerSession(connection)).isEqualTo(before);
                }
                assertThat(occupancyBootstrapAuthoritySnapshot(observer)).isEqualTo(pending);
                assertNoCapacityOwnerCapability(observer, "bulk_schema_owner");
                assertNoCapacityOwnerCapability(observer, "bulk_runtime_test");
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
            }
        }
    }

    @Test
    void v19OccupancyBootstrapPreservesExplicitSessionRoleAcrossCommitAndCompleteReplay() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var admin = postgres.getPostgresDatabase();
            var observer = new JdbcTemplate(admin);
            var owner = createDedicatedV7SchemaOwner(postgres, admin);
            BulkPostgresTestSupport.grantRuntimeRole(admin, "bulk_runtime_test");
            Flyway.configure().dataSource(owner).locations("classpath:db/praxis-bulk-migrations")
                    .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                    .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                    .target("19").load().migrate();
            var roles = new BulkExecutionRoleConfiguration("bulk_schema_owner",
                    java.util.Set.of("bulk_runtime_test"), java.util.Set.of(), java.util.Set.of());
            try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
                // Same owner login; an explicit session role must survive local role
                // restoration on commit. This is bootstrap-only, not public migrate.
                pool.setDataSource(owner);
                pool.setMaximumPoolSize(1);
                pool.setMinimumIdle(1);
                pool.setConnectionTimeout(1000);
                pool.setConnectionInitSql("set role bulk_schema_owner");
                java.util.Map<String, Object> before;
                try (var connection = pool.getConnection()) {
                    before = bootstrapOwnerSession(connection);
                    assertThat(before.get("role")).isEqualTo("bulk_schema_owner");
                }
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
                transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.setTimeout(10);
                var ownerSql = new JdbcTemplate(pool);
                for (int attempt = 0; attempt < 2; attempt++) {
                    var beforeReplay = attempt == 0 ? null : occupancyBootstrapAuthoritySnapshot(observer);
                    transaction.execute(status -> ownerSql.execute(
                            (org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                                try (var statement = connection.createStatement()) {
                                    statement.execute("set local search_path to pg_catalog");
                                }
                                BulkCapacityOccupancyCatalog.bootstrap(connection, roles);
                                assertThat(bootstrapOwnerSession(connection).get("role"))
                                        .isEqualTo("bulk_schema_owner");
                                return null;
                            }));
                    try (var connection = pool.getConnection()) {
                        assertThat(connection.getAutoCommit()).isTrue();
                        assertThat(bootstrapOwnerSession(connection)).isEqualTo(before);
                    }
                    assertThat(observer.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                            String.class)).isEqualTo("COMPLETE");
                    assertNoCapacityOwnerCapability(observer, "bulk_schema_owner");
                    assertNoCapacityOwnerCapability(observer, "bulk_runtime_test");
                    if (beforeReplay != null) {
                        assertThat(occupancyBootstrapAuthoritySnapshot(observer)).isEqualTo(beforeReplay);
                    }
                }
            }
        }
    }

    private static java.util.Map<String, Object> bootstrapOwnerSession(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "select current_user,session_user,current_setting('role'),current_setting('search_path'),pg_backend_pid()")) {
            assertThat(rows.next()).isTrue();
            var result = java.util.Map.<String, Object>of("current_user", rows.getString(1),
                    "session_user", rows.getString(2), "role", rows.getString(3),
                    "search_path", rows.getString(4), "pid", rows.getInt(5));
            assertThat(rows.next()).isFalse();
            return result;
        }
    }

    private static void assertNoCapacityOwnerCapability(JdbcTemplate sql, String role) {
        assertThat(sql.queryForObject("select pg_has_role(?, 'praxis_bulk_capacity_owner', 'MEMBER')",
                Boolean.class, role)).isFalse();
        assertThat(sql.queryForObject("""
                select count(*) from pg_auth_members m join pg_roles r
                  on r.oid=m.roleid or r.oid=m.member where r.rolname='praxis_bulk_capacity_owner'
                """, Integer.class)).isZero();
    }

    private static java.util.Map<String, Object> occupancyBootstrapAuthoritySnapshot(JdbcTemplate sql) {
        return java.util.Map.of(
                "phase", sql.queryForList("select * from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap"),
                "tables", sql.queryForList("""
                        select c.relname,c.relowner,c.relacl::text as acl from pg_class c
                        join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk'
                          and c.relname in ('praxis_bulk_capacity_slot','praxis_bulk_capacity_occupation',
                                           'praxis_bulk_capacity_occupancy_bootstrap') order by c.relname
                        """),
                "functions", sql.queryForList("""
                        select p.oid,p.proowner,p.proacl::text as acl from pg_proc p
                        where p.oid in ('praxis_bulk.lock_capacity_marker()'::regprocedure,
                          'praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint)'::regprocedure)
                        order by p.oid
                        """),
                "memberships", sql.queryForList("""
                        select m.* from pg_auth_members m join pg_roles r
                          on r.oid=m.roleid or r.oid=m.member where r.rolname='praxis_bulk_capacity_owner'
                        order by m.roleid,m.member,m.grantor
                        """),
                "history", sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank"));
    }

    @Test
    void provisionsDeclaredConfirmationControlAsUncomposedAndRerunNeverResetsIt() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            var identity = new BulkOperationControlIdentity(CONTEXT.namespaceId(),
                    CONTEXT.operationRef().operationId());

            assertThat(BulkExecutionMigrator.migrateWithOperations(dataSource,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID),
                    List.of(identity))).isEqualTo(20);
            assertThat(sql.queryForMap("""
                    select state, generation, descriptor_fingerprint, structural_revision
                    from praxis_bulk.praxis_bulk_operation_control
                    where namespace_id=? and operation_id=?
                    """, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId()))
                    .containsEntry("state", "UNCOMPOSED")
                    .containsEntry("generation", 0L)
                    .containsEntry("descriptor_fingerprint", null)
                    .containsEntry("structural_revision", null);

            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            assertThat(BulkExecutionMigrator.migrateWithOperations(dataSource,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID),
                    List.of(identity))).isZero();
            assertThat(sql.queryForMap("""
                    select state, generation, descriptor_fingerprint, structural_revision
                    from praxis_bulk.praxis_bulk_operation_control
                    where namespace_id=? and operation_id=?
                    """, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId()))
                    .containsEntry("state", "READY")
                    .containsEntry("generation", 1L)
                    .containsEntry("descriptor_fingerprint", "sha256:" + "0".repeat(64))
                    .containsEntry("structural_revision", "structural-r1");
        }
    }

    @Test
    void rejectsDuplicateOrUnboundControlIdentitiesBeforeChangingDatabase() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var identity = new BulkOperationControlIdentity(CONTEXT.namespaceId(),
                    CONTEXT.operationRef().operationId());
            var deployments = java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID);

            assertThatThrownBy(() -> BulkExecutionMigrator.migrateWithOperations(dataSource, deployments,
                    List.of(identity, identity))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BulkExecutionMigrator.migrateWithOperations(dataSource,
                    java.util.Map.of("another-namespace", BulkPostgresTestSupport.DEPLOYMENT_ID),
                    List.of(identity))).isInstanceOf(IllegalArgumentException.class);
            assertThat(new JdbcTemplate(dataSource).queryForObject("select to_regclass('praxis_bulk') is null",
                    Boolean.class)).isTrue();
        }
    }

    @Test
    void lateFailureWhileSeedingDeclaredOperationsRollsBackPriorBindingBucketAndControlRows() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            var first = new BulkOperationControlIdentity(CONTEXT.namespaceId(), "employee-bulk-approve-a");
            var second = new BulkOperationControlIdentity(CONTEXT.namespaceId(), "employee-bulk-approve-b");
            var targetedInserts = new AtomicInteger();
            var failingDataSource = failOnSecondOperationControlInsert(dataSource, targetedInserts);

            assertThatThrownBy(() -> BulkExecutionMigrator.migrateWithOperations(failingDataSource,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID),
                    List.of(first, second)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Injected failure on the second declared operation control");

            assertThat(targetedInserts).hasValue(2);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control", Long.class))
                    .isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_namespace_binding", Long.class))
                    .isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_deployment_bucket", Long.class))
                    .isZero();

            // DDL and Flyway history precede the retryable bootstrap transaction; a clean retry
            // must still provision both identities and bindings atomically.
            assertThat(BulkExecutionMigrator.migrateWithOperations(dataSource,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID),
                    List.of(first, second))).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control", Long.class))
                    .isEqualTo(2L);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_namespace_binding", Long.class))
                    .isEqualTo(1L);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_deployment_bucket", Long.class))
                    .isEqualTo(1L);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control "
                    + "where state <> 'UNCOMPOSED'", Long.class)).isZero();

            assertThat(BulkExecutionMigrator.migrateWithOperations(dataSource,
                    java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID),
                    List.of(first, second))).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control", Long.class))
                    .isEqualTo(2L);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_namespace_binding", Long.class))
                    .isEqualTo(1L);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_deployment_bucket", Long.class))
                    .isEqualTo(1L);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control "
                    + "where state <> 'UNCOMPOSED'", Long.class)).isZero();
        }
    }

    private static DataSource failOnSecondOperationControlInsert(DataSource target, AtomicInteger targetedInserts) {
        return new DelegatingDataSource(target) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                return (java.sql.Connection) Proxy.newProxyInstance(
                        java.sql.Connection.class.getClassLoader(),
                        new Class<?>[] { java.sql.Connection.class },
                        (proxy, method, args) -> {
                            Object value = invokeDelegate(connection, method, args);
                            if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                                    && args[0] instanceof String statementSql
                                    && isDeclaredControlInsert(statementSql)) {
                                return failOnSecondExecution((java.sql.PreparedStatement) value, targetedInserts);
                            }
                            return value;
                        });
            }
        };
    }

    private static java.sql.PreparedStatement failOnSecondExecution(
            java.sql.PreparedStatement statement, AtomicInteger targetedInserts) {
        return (java.sql.PreparedStatement) Proxy.newProxyInstance(
                java.sql.PreparedStatement.class.getClassLoader(),
                new Class<?>[] { java.sql.PreparedStatement.class },
                (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName()) && targetedInserts.incrementAndGet() == 2) {
                        throw new java.sql.SQLException("Injected failure on the second declared operation control");
                    }
                    return invokeDelegate(statement, method, args);
                });
    }

    private static boolean isDeclaredControlInsert(String statementSql) {
        String normalized = statementSql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.contains("insert into praxis_bulk.praxis_bulk_operation_control")
                && normalized.contains("values (?, ?, 'uncomposed'");
    }

    private static Object invokeDelegate(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    void validatesPhysicalDurabilityAndBindingInsteadOfTrustingMigrationHistory() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            for (String mutation : List.of(
                    "alter table praxis_bulk.praxis_bulk_item_receipt set unlogged",
                    "alter table praxis_bulk.praxis_bulk_admission set unlogged",
                    "alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_binding",
                    "alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_terminal_reason",
                    "alter table praxis_bulk.praxis_bulk_item_receipt disable trigger praxis_bulk_item_receipt_reject_mutation",
                    "alter table praxis_bulk.praxis_bulk_admission disable trigger praxis_bulk_admission_reject_mutation",
                    "alter table praxis_bulk.praxis_bulk_execution alter column owner_epoch drop not null",
                    "alter table praxis_bulk.praxis_bulk_admission alter column reason_code drop not null",
                    "alter table praxis_bulk.praxis_bulk_execution alter column next_ordinal set default 0",
                    "alter table praxis_bulk.praxis_bulk_execution alter column terminal_reason_code set default 'DEADLINE_EXCEEDED'",
                    "alter table praxis_bulk.praxis_bulk_execution enable row level security",
                    "alter table praxis_bulk.praxis_bulk_admission enable row level security",
                    "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_epoch_check; "
                            + "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_epoch_check check (owner_epoch >= 0)",
                    "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_proposal_key; "
                            + "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_proposal_key unique (proposal_id) deferrable initially deferred",
                    "alter table praxis_bulk.praxis_bulk_item_receipt drop constraint praxis_bulk_item_receipt_execution_fkey; "
                            + "alter table praxis_bulk.praxis_bulk_item_receipt add constraint praxis_bulk_item_receipt_execution_fkey "
                            + "foreign key (execution_id) references praxis_bulk.praxis_bulk_execution(execution_id) not valid",
                    "alter table praxis_bulk.praxis_bulk_admission drop constraint praxis_bulk_admission_execution_fkey; "
                            + "alter table praxis_bulk.praxis_bulk_admission add constraint praxis_bulk_admission_execution_fkey "
                            + "foreign key (execution_id) references praxis_bulk.praxis_bulk_execution(execution_id) not valid",
                    "alter table praxis_bulk.praxis_bulk_admission drop constraint praxis_bulk_admission_target_key",
                    "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_terminal_reason_check; "
                            + "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_terminal_reason_check "
                            + "check (terminal_reason_code is null or status='STOPPED')",
                    "alter table praxis_bulk.praxis_bulk_admission drop constraint praxis_bulk_admission_outcome_reason_check; "
                            + "alter table praxis_bulk.praxis_bulk_admission add constraint praxis_bulk_admission_outcome_reason_check "
                            + "check (outcome in ('DENIED','INVALID','CONFLICT'))",
                    "alter function praxis_bulk.protect_praxis_bulk_execution_binding() security definer",
                    "alter function praxis_bulk.reject_praxis_bulk_admission_mutation() security definer",
                    "create or replace function praxis_bulk.reject_praxis_bulk_item_receipt_mutation() "
                            + "returns trigger language plpgsql as $$begin return new; end;$$",
                    "create or replace function praxis_bulk.reject_praxis_bulk_admission_mutation() "
                            + "returns trigger language plpgsql as $$begin return new; end;$$",
                    "create function praxis_bulk.protect_praxis_bulk_execution_binding(integer) returns integer language sql as 'select $1'",
                    "create trigger praxis_bulk_execution_protect_binding before update on praxis_bulk.praxis_bulk_item_receipt "
                            + "for each row execute function praxis_bulk.protect_praxis_bulk_execution_binding()",
                    "create table praxis_bulk.unexpected(id integer)",
                    "create type praxis_bulk.unexpected as (id integer)",
                    "create unique index unexpected_execution_owner on praxis_bulk.praxis_bulk_execution(owner_id)",
                    "create index unexpected_expression on praxis_bulk.praxis_bulk_execution ((lower(owner_id)))",
                    "create aggregate praxis_bulk.unexpected_sum(integer) (sfunc=int4pl, stype=integer)",
                    "create rule unexpected_update as on update to praxis_bulk.praxis_bulk_execution do instead nothing",
                    "create policy unexpected_policy on praxis_bulk.praxis_bulk_execution using (true)",
                    "drop index praxis_bulk.praxis_bulk_schema_history_s_idx; "
                            + "create unique index praxis_bulk_schema_history_s_idx on praxis_bulk.praxis_bulk_execution(owner_id)",
                    "create unique index praxis_bulk_fk_bound_owner on praxis_bulk.praxis_bulk_execution(owner_id); "
                            + "create table public.praxis_bulk_external_ref(owner_id text references praxis_bulk.praxis_bulk_execution(owner_id))")) {
                assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
                sql.execute(mutation);
                assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                        .as("reject drift: %s", mutation).isInstanceOf(IllegalStateException.class);
                sql.execute("drop schema praxis_bulk cascade");
            }
        }
    }

    @Test
    void validationIsStableWhenOperationalDatasourceDefaultsToOwnedSchema() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var url = postgres.getJdbcUrl("postgres", "postgres") + "&currentSchema=praxis_bulk";
            try (var physicalConnection = java.sql.DriverManager.getConnection(url, "postgres", "postgres");
                    var scopedDataSource = new SingleConnectionDataSource(physicalConnection, true)) {
                var scopedSql = new JdbcTemplate(scopedDataSource);
                var before = scopedSql.queryForMap("select pg_catalog.pg_backend_pid() as pid, "
                        + "current_setting('search_path') as path, current_setting('transaction_isolation') as isolation, "
                        + "current_setting('statement_timeout') as statement_timeout, current_setting('lock_timeout') as lock_timeout");
                assertThat(physicalConnection.getAutoCommit()).isTrue();
                // Both loans remain alive; SQL observes the actual shared backend rather than wrapper equality.
                try (var first = scopedDataSource.getConnection(); var second = scopedDataSource.getConnection();
                        var firstStatement = first.createStatement(); var secondStatement = second.createStatement();
                        var firstRows = firstStatement.executeQuery("select pg_catalog.pg_backend_pid()");
                        var secondRows = secondStatement.executeQuery("select pg_catalog.pg_backend_pid()")) {
                    assertThat(firstRows.next()).isTrue();
                    assertThat(secondRows.next()).isTrue();
                    assertThat(firstRows.getInt(1)).isEqualTo(secondRows.getInt(1))
                            .isEqualTo(((Number) before.get("pid")).intValue());
                    assertThat(firstRows.next()).isFalse();
                    assertThat(secondRows.next()).isFalse();
                }
                var deployments = java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID);
                assertThatThrownBy(() -> BulkExecutionMigrator.migrate(scopedDataSource, deployments,
                        BulkExecutionRoleConfiguration.none("postgres")))
                        .isExactlyInstanceOf(IllegalStateException.class)
                        .hasMessage("Bulk schema migration requires independent owner connections");
                assertThatThrownBy(() -> migrate(scopedDataSource))
                        .isExactlyInstanceOf(IllegalStateException.class)
                        .hasMessage("Bulk schema migration requires independent owner connections");
                assertThat(scopedSql.queryForMap("select pg_catalog.pg_backend_pid() as pid, "
                        + "current_setting('search_path') as path, current_setting('transaction_isolation') as isolation, "
                        + "current_setting('statement_timeout') as statement_timeout, current_setting('lock_timeout') as lock_timeout"))
                        .isEqualTo(before);
                assertThat(physicalConnection.getAutoCommit()).isTrue();
                assertThat(scopedSql.queryForObject("select pg_catalog.to_regnamespace('praxis_bulk') is null "
                        + "and pg_catalog.to_regclass('praxis_bulk.praxis_bulk_schema_history') is null", Boolean.class))
                        .isTrue();
                // Real independent owner connections initialize this same database/currentSchema.
                var independent = new DriverManagerDataSource(url, "postgres", "postgres");
                assertThat(migrate(independent)).isEqualTo(20);
                assertThat(scopedSql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                        String.class)).isEqualTo("COMPLETE");
                BulkExecutionMigrator.validate(independent);
                BulkExecutionMigrator.validate(scopedDataSource);
                assertThat(scopedSql.queryForObject("select pg_catalog.pg_backend_pid()", Integer.class))
                        .isEqualTo(((Number) before.get("pid")).intValue());
                try (var statement = physicalConnection.createStatement();
                        var result = statement.executeQuery("select current_schema(), current_setting('search_path')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("praxis_bulk");
                    assertThat(result.getString(2)).isEqualTo("praxis_bulk");
                    assertThat(result.next()).isFalse();
                }
                System.out.println("Owner alias/serving proof: callerPid=" + before.get("pid")
                        + ", aliasRejected=true, independentHistory19=true, servingSamePid=true");
            }
        }
    }

    @Test
    void manualCommitOwnerLeaseRejectedWithoutTouchingCallerTransaction() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var source = postgres.getPostgresDatabase();
            var observer = new JdbcTemplate(source);
            observer.execute("create table public.bulk_owner_caller_sentinel (value integer primary key)");
            try (var physical = source.getConnection();
                    var callerSource = new SingleConnectionDataSource(physical, true)) {
                physical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                physical.setAutoCommit(false);
                try {
                    var caller = new JdbcTemplate(callerSource);
                    assertThat(caller.update("insert into public.bulk_owner_caller_sentinel(value) values (1)"))
                            .isEqualTo(1);
                    var before = caller.queryForMap("select pg_catalog.pg_backend_pid() as pid, "
                            + "pg_catalog.txid_current()::text as xid, current_setting('search_path') as path, "
                            + "current_setting('transaction_isolation') as isolation, "
                            + "current_setting('statement_timeout') as statement_timeout, current_setting('lock_timeout') as lock_timeout");
                    int pid = ((Number) before.get("pid")).intValue();
                    assertThat((String) before.get("xid")).matches("[0-9]{1,20}");
                    var observerBefore = observer.queryForMap("select pid, backend_xid::text as xid, state "
                            + "from pg_stat_activity where pid=?", pid);
                    assertThat(observerBefore).containsEntry("state", "idle in transaction");
                    assertThat(observerBefore.get("xid")).isNotNull();
                    assertThat(observer.queryForObject("select count(*) from public.bulk_owner_caller_sentinel",
                            Integer.class)).isZero();
                    var deployments = java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID);
                    var identity = new BulkOperationControlIdentity(CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
                    var attempts = List.<Runnable>of(
                            () -> BulkExecutionMigrator.migrate(callerSource, deployments,
                                    BulkExecutionRoleConfiguration.none("postgres")),
                            () -> BulkExecutionMigrator.migrate(callerSource, deployments),
                            () -> BulkExecutionMigrator.migrateWithOperations(callerSource, deployments, List.of(identity)));
                    for (var attempt : attempts) {
                        assertThatThrownBy(attempt::run).isExactlyInstanceOf(IllegalStateException.class)
                                .hasMessage("Bulk schema migration requires auto-commit owner connections");
                        assertThat(physical.getAutoCommit()).isFalse();
                        assertThat(observer.queryForMap("select pid, backend_xid::text as xid, state "
                                + "from pg_stat_activity where pid=?", pid)).isEqualTo(observerBefore);
                        assertThat(observer.queryForObject("select count(*) from public.bulk_owner_caller_sentinel",
                                Integer.class)).isZero();
                        assertThat(caller.queryForObject("select count(*) from public.bulk_owner_caller_sentinel",
                                Integer.class)).isEqualTo(1);
                        assertThat(caller.queryForMap("select pg_catalog.pg_backend_pid() as pid, "
                                + "pg_catalog.txid_current()::text as xid, current_setting('search_path') as path, "
                                + "current_setting('transaction_isolation') as isolation, "
                                + "current_setting('statement_timeout') as statement_timeout, current_setting('lock_timeout') as lock_timeout"))
                                .isEqualTo(before);
                        assertThat(observer.queryForObject("select pg_catalog.to_regnamespace('praxis_bulk') is null "
                                + "and pg_catalog.to_regclass('praxis_bulk.praxis_bulk_schema_history') is null", Boolean.class))
                                .isTrue();
                    }
                    System.out.println("Owner manual transaction proof: callerPid=" + pid + ", callerXid="
                            + before.get("xid") + ", overloadsRejected=3, sentinelExternalRows=0, callerRows=1");
                } finally {
                    // Only the fixture owns and rolls back this deliberately uncommitted caller transaction.
                    physical.rollback();
                }
                assertThat(observer.queryForObject("select count(*) from public.bulk_owner_caller_sentinel",
                        Integer.class)).isZero();
            }
        }
    }

    @Test
    void ownedSchemaOccupancyBootstrapRejectsForeignKeyDriftAndRestoresEveryPooledBackend() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            String url = postgres.getJdbcUrl("postgres", "postgres") + "&currentSchema=praxis_bulk";
            var ownerSource = new DriverManagerDataSource(url, "postgres", "postgres");
            var observer = new JdbcTemplate(ownerSource);
            String applicationName = "bulk_owner_catalog_scope";
            try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
                pool.setDataSource(ownerSource);
                pool.setPoolName(applicationName);
                pool.setMaximumPoolSize(4);
                pool.setMinimumIdle(0);
                pool.setConnectionTimeout(1000);
                pool.setValidationTimeout(500);
                pool.setInitializationFailTimeout(0);
                pool.setConnectionInitSql("set application_name='" + applicationName + "'");
                var deployments = java.util.Map.of("tenant:prod:owner-catalog-scope", "deployment-owner-catalog-scope");
                var roles = BulkExecutionRoleConfiguration.none("postgres");
                assertThat(BulkExecutionMigrator.migrate(pool, deployments, roles)).isEqualTo(20);
                BulkExecutionMigrator.validate(pool, roles);
                var historyBefore = observer.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                        + "order by installed_rank");
                var bootstrapBefore = observer.queryForMap("select * from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap");
                assertThat(bootstrapBefore).containsEntry("phase", "COMPLETE");
                String table = "praxis_bulk_capacity_occupation";
                String fk = "praxis_bulk_capacity_occupation_execution_id_fkey";
                String restore = "alter table praxis_bulk." + table + " drop constraint " + fk
                        + ", add constraint " + fk + " " + BulkCapacityOccupancyCatalog.constraints(table).get(fk);
                java.util.Map<Integer, List<String>> before = null;
                boolean driftInstalled = false;
                try {
                    for (int stage = 0; stage < 2; stage++) {
                        var snapshot = new java.util.LinkedHashMap<Integer, List<String>>();
                        // All four loans are simultaneously alive; every physical backend is observed.
                        try (var first = pool.getConnection(); var second = pool.getConnection();
                                var third = pool.getConnection(); var fourth = pool.getConnection()) {
                            for (var connection : List.of(first, second, third, fourth)) {
                                assertThat(connection.getAutoCommit()).isTrue();
                                try (var statement = connection.createStatement()) {
                                    statement.setQueryTimeout(1);
                                    try (var rows = statement.executeQuery("select pg_catalog.pg_backend_pid(), "
                                            + "current_setting('search_path'), current_setting('transaction_isolation'), "
                                            + "current_setting('statement_timeout'), current_setting('lock_timeout')")) {
                                        assertThat(rows.next()).isTrue();
                                        int pid = rows.getInt(1);
                                        assertThat(pid).isPositive();
                                        assertThat(rows.getString(2)).isEqualTo("praxis_bulk");
                                        assertThat(snapshot.put(pid, List.of(rows.getString(2), rows.getString(3),
                                                rows.getString(4), rows.getString(5)))).isNull();
                                        assertThat(rows.next()).isFalse();
                                    }
                                }
                            }
                            assertThat(snapshot).hasSize(4);
                        }
                        if (stage == 0) {
                            before = snapshot;
                            // The external fixture owner commits a valid but noncanonical FK; no runtime authority is used.
                            observer.execute("alter table praxis_bulk." + table + " drop constraint " + fk
                                    + ", add constraint " + fk + " foreign key(execution_id) "
                                    + "references praxis_bulk.praxis_bulk_execution(execution_id) on delete cascade");
                            driftInstalled = true;
                            assertThatThrownBy(() -> BulkExecutionMigrator.migrate(pool, deployments, roles))
                                    .isExactlyInstanceOf(IllegalStateException.class)
                                    .hasMessageStartingWith("V19 constraints differ: " + table);
                        } else {
                            assertThat(snapshot).as("the same four backend PIDs retain their complete settings after rollback")
                                    .isEqualTo(before);
                        }
                    }
                    assertThat(observer.queryForObject("select count(*) from pg_catalog.pg_constraint "
                            + "where conrelid='praxis_bulk.praxis_bulk_capacity_occupation'::regclass "
                            + "and conname=? and confdeltype='c' and convalidated "
                            + "and confrelid='praxis_bulk.praxis_bulk_execution'::regclass", Integer.class, fk))
                            .as("the failing owner migration does not heal the committed foreign key drift").isEqualTo(1);
                    assertThat(observer.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                            + "order by installed_rank")).isEqualTo(historyBefore);
                    assertThat(observer.queryForMap("select * from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap"))
                            .isEqualTo(bootstrapBefore);
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                    assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
                    assertThat(observer.queryForObject("select count(*) from pg_stat_activity where "
                            + "datname=current_database() and application_name=? and state like 'idle in transaction%'",
                            Integer.class, applicationName)).isZero();
                } finally {
                    if (driftInstalled) observer.execute(restore);
                }
                assertThat(BulkExecutionMigrator.migrate(pool, deployments, roles)).isZero();
                BulkExecutionMigrator.validate(pool, roles);
                assertThat(observer.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                        + "order by installed_rank")).isEqualTo(historyBefore);
                assertThat(observer.queryForMap("select * from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap"))
                        .isEqualTo(bootstrapBefore);
                System.out.println("Owner catalog rollback proof: pooledPids=" + before.keySet()
                        + ", restoredPaths=true, autoCommitTrue=true, idleTransactionBackends=0, retryMigrations=0");
            }
        }
    }

    @Test
    void v5RejectsUnconfiguredGranteesAndPrivilegesOutsideTheRuntimeAllowlist() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrate(dataSource);
            sql.execute("create role bulk_runtime nologin");
            sql.execute("grant usage on schema praxis_bulk to bulk_runtime");
            sql.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to bulk_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_manifest to bulk_runtime");

            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_preview_state to bulk_runtime");

            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_preview to bulk_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_preview_item_integrity to bulk_runtime");
            sql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to bulk_runtime");
            sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime");
            sql.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to bulk_runtime");
            grantAtomicRuntimeAccessForFreshRole(sql, "bulk_runtime");
            grantCapacityOccupancyRuntimeAccessForFreshRole(sql, "bulk_runtime");
            sql.execute("grant select on praxis_bulk.praxis_bulk_capacity_marker, "
                    + "praxis_bulk.praxis_bulk_capacity_installation to bulk_runtime");
            var configured = new BulkExecutionRoleConfiguration("postgres",
                    java.util.Set.of("bulk_runtime"), java.util.Set.of(), java.util.Set.of());
            BulkExecutionMigrator.validate(dataSource, configured);

            sql.execute("create role bulk_rogue nologin");
            sql.execute("grant insert on praxis_bulk.praxis_bulk_tombstone to bulk_rogue");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource, configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unconfigured bulk table grantee");
            sql.execute("revoke insert on praxis_bulk.praxis_bulk_tombstone from bulk_rogue");

            sql.execute("grant select on praxis_bulk.praxis_bulk_operation_control to bulk_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource, configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bulk runtime privilege exceeds its table allowlist");
            sql.execute("revoke select on praxis_bulk.praxis_bulk_operation_control from bulk_runtime");

            sql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_rogue");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource, configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("governed lifecycle function grants differ");
            sql.execute("revoke execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) from bulk_rogue");
            sql.execute("grant execute on function praxis_bulk.guard_descriptor_fence() to public");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource, configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("governed lifecycle function grants differ");
            sql.execute("revoke execute on function praxis_bulk.guard_descriptor_fence() from public");
            sql.execute("grant praxis_bulk_control_owner to bulk_rogue");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource, configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("internal owner/member topology is unsafe");
            sql.execute("revoke praxis_bulk_control_owner from bulk_rogue");

            sql.execute("grant update on praxis_bulk.praxis_bulk_operation_control to bulk_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource, configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("privilege exceeds its table allowlist");
            sql.execute("revoke update on praxis_bulk.praxis_bulk_operation_control from bulk_runtime");

            sql.execute("grant create on schema praxis_bulk to bulk_rogue");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource, configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schema grants differ");
            sql.execute("revoke create on schema praxis_bulk from bulk_rogue");
            sql.execute("alter table praxis_bulk.praxis_bulk_operation_control owner to bulk_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource, configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("table owner differs");
        }
    }

    @Test
    void operationControlSeparatesRuntimeFenceFromGovernedCas() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var admin = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(admin);
            migrate(admin);
            sql.execute("create role bulk_runtime login");
            sql.execute("create role bulk_control login");
            sql.execute("create role bulk_retention_group nologin noinherit");
            sql.execute("create role bulk_retention_login login");
            sql.execute("grant praxis_bulk_retention_executor to bulk_retention_group");
            sql.execute("grant bulk_retention_group to bulk_retention_login");
            sql.execute("grant usage on schema praxis_bulk to bulk_runtime, bulk_control");
            sql.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to bulk_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_manifest to bulk_runtime");

            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_preview_state to bulk_runtime");

            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_preview to bulk_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_preview_item_integrity to bulk_runtime");
            sql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to bulk_runtime");
            sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime");
            sql.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to bulk_runtime");
            grantAtomicRuntimeAccessForFreshRole(sql, "bulk_runtime");
            grantCapacityOccupancyRuntimeAccessForFreshRole(sql, "bulk_runtime");
            sql.execute("grant select on praxis_bulk.praxis_bulk_capacity_marker, "
                    + "praxis_bulk.praxis_bulk_capacity_installation to bulk_runtime");
            sql.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to bulk_control");
            sql.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to bulk_control");
            var roles = new BulkExecutionRoleConfiguration("postgres", java.util.Set.of("bulk_runtime"),
                    java.util.Set.of("bulk_retention_group", "bulk_retention_login"),
                    java.util.Set.of("bulk_control"));
            BulkExecutionMigrator.validate(admin, roles);

            sql.execute("grant postgres to bulk_control");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(admin, roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("configured bulk roles inherit unexpected PostgreSQL roles");
            sql.execute("revoke postgres from bulk_control");
            BulkExecutionMigrator.validate(admin, roles);
            sql.execute("grant pg_write_all_data to bulk_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(admin, roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("configured bulk roles inherit unexpected PostgreSQL roles");
            sql.execute("revoke pg_write_all_data from bulk_runtime");
            BulkExecutionMigrator.validate(admin, roles);

            sql.update("insert into praxis_bulk.praxis_bulk_namespace_binding values (?, ?, clock_timestamp())",
                    "control-test", "deployment-test");

            sql.update("insert into praxis_bulk.praxis_bulk_operation_control (namespace_id,operation_id,state,generation,descriptor_fingerprint,structural_revision,updated_at) values (?, ?, 'UNCOMPOSED', 0, null, null, clock_timestamp())",
                    "control-test", "approve");

            var runtime = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_runtime", "postgres"), "bulk_runtime", "");
            var governance = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control", "postgres"), "bulk_control", "");
            BulkExecutionMigrator.migrate(admin, java.util.Map.of("control-test", "deployment-test",
                    CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles,
                    List.of(new BulkOperationControlIdentity("control-test", "approve")));
            BulkPostgresTestSupport.publishFixture(admin, "control-test");
            JdbcBulkOperationControl.Transition published;
            try (var connection = governance.getConnection()) {
                published = JdbcBulkOperationControl.transition(connection, "control-test", "approve", 0,
                        JdbcBulkOperationControl.Target.READY, "sha256:" + "c".repeat(64), "descriptor-r1", BulkPostgresTestSupport.publication(admin, "control-test").generation(), BulkPostgresTestSupport.publication(admin, "control-test").documentDigest());
            }
            assertThat(published).isEqualTo(new JdbcBulkOperationControl.Transition(true, 1));
            assertThatThrownBy(() -> new JdbcTemplate(governance).execute(
                    "select * from praxis_bulk.transition_operation_control(" +
                            "'control-test','approve',null,'SUSPENDED',null,null,null,null)"))
                    .isInstanceOf(RuntimeException.class);
            var unchanged = sql.queryForMap("select state,generation,descriptor_fingerprint,structural_revision " +
                    "from praxis_bulk.praxis_bulk_operation_control where namespace_id='control-test' " +
                    "and operation_id='approve'");
            assertThat(unchanged).containsEntry("state", "READY")
                    .containsEntry("generation", 1L)
                    .containsEntry("descriptor_fingerprint", "sha256:" + "c".repeat(64))
                    .containsEntry("structural_revision", "descriptor-r1");
            try (var connection = runtime.getConnection()) {
                assertThat(JdbcBulkOperationControl.lockForAdmission(connection, "control-test", "approve"))
                        .isEqualTo(new JdbcBulkOperationControl.Snapshot("READY", 1,
                                "sha256:" + "c".repeat(64), "descriptor-r1"));
            }
            var runtimeSql = new JdbcTemplate(runtime);
            assertThatThrownBy(() -> runtimeSql.execute("select * from praxis_bulk.transition_operation_control("
                    + "'control-test','approve',1,'SUSPENDED',null,null,null,null)"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> runtimeSql.execute("update praxis_bulk.praxis_bulk_operation_control "
                    + "set state='SUSPENDED',generation=2,descriptor_fingerprint=null,structural_revision=null "
                    + "where namespace_id='control-test'"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(governance).execute(
                    "update praxis_bulk.praxis_bulk_operation_control set state='SUSPENDED'"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new JdbcTemplate(governance).queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_operation_control", Integer.class))
                    .isInstanceOf(RuntimeException.class);

            try (var runtimeConnection = runtime.getConnection();
                 var controlConnection = governance.getConnection()) {
                runtimeConnection.setAutoCommit(false);
                assertThat(JdbcBulkOperationControl.lockForAdmission(
                        runtimeConnection, "control-test", "approve").ready()).isTrue();
                controlConnection.setAutoCommit(false);
                try (var statement = controlConnection.createStatement()) {
                    statement.execute("set local lock_timeout = '100ms'");
                }
                assertThatThrownBy(() -> JdbcBulkOperationControl.transition(controlConnection,
                        "control-test", "approve", 1, JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null))
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("lock timeout");
                controlConnection.rollback();
                runtimeConnection.commit();
            }

            try (var connection = governance.getConnection()) {
                assertThat(JdbcBulkOperationControl.transition(connection, "control-test", "approve", 0,
                        JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null))
                        .isEqualTo(new JdbcBulkOperationControl.Transition(false, 1));
                assertThat(JdbcBulkOperationControl.transition(connection, "control-test", "approve", 1,
                        JdbcBulkOperationControl.Target.SUSPENDED, null, null, null, null))
                        .isEqualTo(new JdbcBulkOperationControl.Transition(true, 2));
            }
            try (var connection = runtime.getConnection()) {
                assertThat(JdbcBulkOperationControl.lockForAdmission(connection, "control-test", "approve").ready())
                        .isFalse();
            }
            BulkExecutionMigrator.validate(admin, roles);
        }
    }

    @Test
    void completeLifecycleNeverRestoresMissingGlobalIdentityDespiteOtherPendingLatches() throws Exception {
        for (int target : List.of(19, 20)) {
            try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                    .setRegisterShutdownHook(false).start()) {
                var owner = postgres.getPostgresDatabase();
                var sql = new JdbcTemplate(owner);
                var roles = v15RuntimeFixture(owner, sql, "bulk_v16_runtime");
                initializePendingHistoricalV18(owner, roles);
                if (target == 19) migrateToVersion(owner, "19");
                else assertThat(BulkExecutionMigrator.migrate(owner,
                        java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                        .isEqualTo(2);
                assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",
                        String.class)).isEqualTo("COMPLETE");
                assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap",
                        String.class)).isEqualTo(target == 19 ? "PENDING" : "COMPLETE");
                assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap",
                        String.class)).isEqualTo(target == 19 ? "PENDING" : "COMPLETE");
                assertThat(sql.update("delete from praxis_bulk.praxis_bulk_openapi_publication")).isEqualTo(1);
                var before = allProtectedRows(sql);
                var acl = sql.queryForList("select relname,relacl::text from pg_class "
                        + "where relnamespace='praxis_bulk'::regnamespace order by relname");
                var functions = sql.queryForList("select p.oid::text,p.proacl::text from pg_proc p "
                        + "where pronamespace='praxis_bulk'::regnamespace order by p.oid");
                assertThatThrownBy(() -> BulkExecutionMigrator.migrate(owner,
                        java.util.Map.of(CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID), roles))
                        .isExactlyInstanceOf(IllegalStateException.class)
                        .hasMessage("every bound deployment requires its durable OpenAPI publication row");
                if (target == 20)
                    assertThatThrownBy(() -> BulkExecutionMigrator.validate(owner, roles))
                            .isExactlyInstanceOf(IllegalStateException.class)
                            .hasMessage("every bound deployment requires its durable OpenAPI publication row");
                assertThat(allProtectedRows(sql)).isEqualTo(before);
                assertThat(sql.queryForList("select relname,relacl::text from pg_class "
                        + "where relnamespace='praxis_bulk'::regnamespace order by relname")).isEqualTo(acl);
                assertThat(sql.queryForList("select p.oid::text,p.proacl::text from pg_proc p "
                        + "where pronamespace='praxis_bulk'::regnamespace order by p.oid")).isEqualTo(functions);
            }
        }
    }

    /** Whole-row snapshots include bytea content as PostgreSQL JSON hex, not byte[] identity. */
    private static java.util.Map<String, List<String>> allProtectedRows(JdbcTemplate sql) {
        var snapshot = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : sql.queryForList("select relname from pg_class "
                + "where relnamespace='praxis_bulk'::regnamespace and relkind='r' order by relname", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Unsafe fixture relation");
            snapshot.put(table, sql.queryForList("select to_jsonb(t)::text from praxis_bulk."
                    + table + " t order by 1", String.class));
        }
        return snapshot;
    }

    @Test
    void retainedV3RowsResumeInterruptedV19BeforeInstallingV20() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            migrateToV3(owner);
            var retained = insertExecution(owner, sql, "STOPPED", 0, true);
            migrateToVersion(owner, "19");
            var history = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
            var proposal = sql.queryForList("select to_jsonb(p)::text persisted_row from praxis_bulk.praxis_bulk_proposal p order by proposal_id");
            var execution = sql.queryForList("select to_jsonb(e)::text persisted_row from praxis_bulk.praxis_bulk_execution e order by execution_id");
            assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",
                    String.class)).isEqualTo("PENDING");
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_namespace_binding",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation",
                    Integer.class)).isZero();

            assertThat(migrate(owner)).isEqualTo(1);
            var independent = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres"));
            assertThat(independent.queryForList("select * from praxis_bulk.praxis_bulk_schema_history "
                    + "where version is null OR version::integer<=19 order by installed_rank")).isEqualTo(history);
            assertThat(independent.queryForList("select to_jsonb(p)::text persisted_row from praxis_bulk.praxis_bulk_proposal p order by proposal_id")).isEqualTo(proposal);
            assertThat(independent.queryForList("select to_jsonb(e)::text persisted_row from praxis_bulk.praxis_bulk_execution e order by execution_id")).isEqualTo(execution);
            assertThat(independent.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation "
                    + "where proposal_id=? or execution_id=?", Integer.class, retained.proposalId(), retained.id()))
                    .isEqualTo(2);
            assertThat(independent.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",
                    String.class)).isEqualTo("COMPLETE");
            BulkExecutionMigrator.validate(owner);
            var allocations = independent.queryForList("select * from praxis_bulk.praxis_bulk_allocation order by kind");
            assertThat(migrate(owner)).isZero();
            assertThat(independent.queryForList("select * from praxis_bulk.praxis_bulk_allocation order by kind"))
                    .isEqualTo(allocations);
        }
    }

    @Test
    void retainedPendingV19ConflictingBindingDeniesBeforeAnyBootstrapWrite() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            migrateToV3(owner);
            insertExecution(owner, sql, "STOPPED", 0, true);
            migrateToVersion(owner, "19");
            sql.update("insert into praxis_bulk.praxis_bulk_namespace_binding "
                    + "values (?, 'conflicting-deployment', clock_timestamp())", CONTEXT.namespaceId());
            var history = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
            var binding = sql.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding");
            var acl = sql.queryForList("select relname,relacl::text from pg_class "
                    + "where relnamespace='praxis_bulk'::regnamespace order by relname");
            var markers = new java.util.LinkedHashMap<String, String>();
            for (String marker : List.of("manifest", "preview", "preview_integrity", "preview_reader",
                    "atomic", "capacity_read", "capacity_occupancy"))
                markers.put(marker, sql.queryForObject("select phase from praxis_bulk.praxis_bulk_"
                        + marker + "_bootstrap", String.class));

            assertThatThrownBy(() -> migrate(owner)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("namespace binding conflicts");
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank"))
                    .isEqualTo(history);
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding")).isEqualTo(binding);
            assertThat(sql.queryForList("select relname,relacl::text from pg_class "
                    + "where relnamespace='praxis_bulk'::regnamespace order by relname")).isEqualTo(acl);
            for (var marker : markers.entrySet())
                assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_"
                        + marker.getKey() + "_bootstrap", String.class)).isEqualTo(marker.getValue());
            for (String table : List.of("allocation", "operation_control", "deployment_bucket", "subject_bucket",
                    "capacity_installation", "capacity_marker"))
                assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_" + table,
                        Integer.class)).as("no bootstrap rows in %s", table).isZero();
            assertThat(sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_execution_worker_queue_idx')",
                    String.class)).isNull();
        }
    }

    @Test
    void v3StopUpgradesWithoutInventingItsCauseAndSurvivesIndependentReadback() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToV3(dataSource);
            UUID executionId = insertExecution(dataSource, sql, "STOPPED", 0, true).id();
            UUID rolledBackAttempt = UUID.randomUUID();
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set active_attempt_id=?, active_attempt_ordinal=0,
                        active_target_digest=?, active_attempt_epoch=1
                    where execution_id=?
                    """, rolledBackAttempt, "sha256:" + "c".repeat(64), executionId);
            int[] checksums = {1, 2, 3};
            for (int version : checksums) {
                checksums[version - 1] = sql.queryForObject(
                        "select checksum from praxis_bulk.praxis_bulk_schema_history where version=?",
                        Integer.class, Integer.toString(version));
            }

            assertThat(migrate(dataSource)).isEqualTo(17);
            for (int version = 1; version <= 3; version++) {
                assertThat(sql.queryForObject(
                        "select checksum from praxis_bulk.praxis_bulk_schema_history where version=?",
                        Integer.class, Integer.toString(version))).isEqualTo(checksums[version - 1]);
            }
            var independent = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres"));
            assertThat(independent.queryForObject("""
                    select terminal_reason_code from praxis_bulk.praxis_bulk_execution where execution_id=?
                    """, String.class, executionId)).isEqualTo("LEGACY_REASON_NOT_RECORDED");
            assertThat(independent.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_execution
                    where execution_id=? and active_attempt_id is null and active_attempt_ordinal is null
                      and active_target_digest is null and active_attempt_epoch is null
                    """, Integer.class, executionId)).isEqualTo(1);
            assertThat(independent.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_admission", Integer.class)).isZero();
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution set terminal_reason_code=null where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            BulkExecutionMigrator.validate(dataSource);
            assertThat(migrate(dataSource)).isZero();
        }
    }

    @Test
    void retentionExecutorCanExpireAndPurgeThroughDefinerFunctions() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToV3(dataSource);
            var terminal = insertExecution(dataSource, sql, "STOPPED", 0, true,
                    Instant.now().minusSeconds(32L * 24 * 60 * 60),
                    BulkScopeDigests.idempotencyKeyDigest("retention-replay-key"));
            String replayKey = "retention-replay-key";
            sql.update("update praxis_bulk.praxis_bulk_execution set terminal_at=clock_timestamp()-interval '31 days' "
                    + "where execution_id=?", terminal.id());
            assertThat(migrate(dataSource)).isEqualTo(17);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            var roles = BulkPostgresTestSupport.testRoleConfiguration();
            BulkPostgresTestSupport.grantRuntimeRole(dataSource, "bulk_runtime_test");
            BulkPostgresTestSupport.grantRuntimeRole(dataSource, "durable_runtime");
            BulkExecutionMigrator.validate(dataSource, roles);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?",
                    Integer.class, terminal.proposalId())).isEqualTo(2);
            var runtimeDataSource = BulkPostgresTestSupport.runtimeDataSource(postgres);

            var now = Instant.now();
            var expired = new BulkStoredProposal(UUID.randomUUID(), now.minusSeconds(120), now.minusSeconds(60),
                    BulkSnapshotStorageCodecTest.snapshot(CONTEXT, BulkMode.DOMAIN_COMMAND,
                            BulkIdentityCodecs.strings(), "\"retention-target\"", "1.0"),
                    BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
            var evaluatedExpired = new BulkStoredProposal(UUID.randomUUID(), now.minusSeconds(120), now.minusSeconds(60),
                    BulkSnapshotStorageCodecTest.snapshot(CONTEXT, BulkMode.DOMAIN_COMMAND,
                            BulkIdentityCodecs.strings(), "\"retention-target-2\"", "1.0"),
                    BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
            var empty = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            var expiredEvidence = new BulkEvaluationSnapshot(evaluatedExpired, now.minusSeconds(90), List.of(
                    new BulkTargetEvidence<>(new BulkTarget<>("retention-target-2", "v1"), "v1", empty, empty,
                            BulkTargetEligibility.executable())),
                    new BulkEvaluationGovernance("retention-evaluator", "retention-grants", List.of(
                            new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                                    "resource:approve", "NEVER_APPLIED", "retention-policy", now.minusSeconds(100)))));
            var proposalStore = new JdbcBulkProposalStore(new BulkExecutionInfrastructure(runtimeDataSource,
                    new DataSourceTransactionManager(runtimeDataSource), CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID, roles));
            new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource))
                    .executeWithoutResult(status -> proposalStore.insert(expired));
            new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource))
                    .executeWithoutResult(status -> proposalStore.insertEvaluated(expiredEvidence, BulkEvaluationSnapshotTest.preview(expiredEvidence)));
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isEqualTo(1);

            sql.execute("grant praxis_bulk_retention_executor to postgres");
            sql.execute("create function public.fail_preview_delete_v11() returns trigger language plpgsql "
                    + "as $$ begin raise exception 'v11 deletion failure'; end; $$");
            sql.execute("create trigger fail_preview_delete_v11 before delete on praxis_bulk.praxis_bulk_target_preview "
                    + "for each row execute function public.fail_preview_delete_v11()");
            try {
                assertThatThrownBy(() -> new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                        .executeWithoutResult(status -> {
                            sql.execute("set role praxis_bulk_retention_executor");
                            sql.queryForObject("select praxis_bulk.expire_unconsumed_proposal(?)",
                                    Boolean.class, evaluatedExpired.id());
                        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("v11 deletion failure");
            } finally {
                sql.execute("drop trigger fail_preview_delete_v11 on praxis_bulk.praxis_bulk_target_preview");
                sql.execute("drop function public.fail_preview_delete_v11()");
            }
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isEqualTo(1);

            try {
                var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                tx.executeWithoutResult(status -> {
                    sql.execute("set role praxis_bulk_retention_executor");
                    assertThat(sql.queryForObject("select praxis_bulk.expire_unconsumed_proposal(?)",
                            Boolean.class, expired.id())).isTrue();
                    assertThat(sql.queryForObject("select praxis_bulk.expire_unconsumed_proposal(?)",
                            Boolean.class, evaluatedExpired.id())).isTrue();
                    assertThat(sql.queryForObject("select praxis_bulk.expire_unconsumed_proposal(?)",
                            Boolean.class, evaluatedExpired.id())).isFalse();
                    assertThat(sql.queryForObject("select praxis_bulk.purge_terminal_execution(?)",
                            Boolean.class, terminal.id())).isTrue();
                    sql.execute("reset role");
                });
            } finally {
                sql.execute("revoke praxis_bulk_retention_executor from postgres");
            }

            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal where proposal_id=?",
                    Integer.class, expired.id())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_preview_state where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation where proposal_id=?",
                    Integer.class, evaluatedExpired.id())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution where execution_id=?",
                    Integer.class, terminal.id())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?",
                    Integer.class, terminal.proposalId())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_preview_state where proposal_id=?",
                    Integer.class, terminal.proposalId())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?",
                    Integer.class, terminal.proposalId())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation where proposal_id=? or execution_id=?",
                    Integer.class, terminal.proposalId(), terminal.id())).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_tombstone where execution_id=?",
                    Integer.class, terminal.id())).isEqualTo(1);
            var durableKernel = new JdbcBulkDurableExecution(new BulkExecutionInfrastructure(runtimeDataSource,
                    new DataSourceTransactionManager(runtimeDataSource), CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID, roles));
            assertThatThrownBy(() -> durableKernel.reserve(CONTEXT, terminal.proposalId(), replayKey,
                    "retention-owner", "structural-r1", Instant.now().plusSeconds(60)))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason())
                                    .isEqualTo(BulkDurableExecutionException.Reason.RESULT_PURGED));
            BulkExecutionMigrator.validate(dataSource, roles);
        }
    }

    @Test
    void v4AdmissionIsAppendOnlyScopedAndRestrictedToClosedOutcomeReasons() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            var fixture = insertExecution(dataSource, sql, "RUNNING", 0, false);
            UUID executionId = fixture.id();
            UUID attemptId = UUID.randomUUID();
            insertAdmission(sql, executionId, attemptId, 0, fixture.digest0(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            assertThat(sql.queryForObject("select reason_code from praxis_bulk.praxis_bulk_admission "
                    + "where execution_id=? and unit_ordinal=0", String.class, executionId))
                    .isEqualTo("TARGET_VERSION_CONFLICT");
            assertThatThrownBy(() -> insertAdmission(sql, executionId, UUID.randomUUID(), 0,
                    "sha256:" + "d".repeat(64), "CONFLICT", "TARGET_STATE_CONFLICT"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> insertAdmission(sql, UUID.randomUUID(), UUID.randomUUID(), 1,
                    "sha256:" + "e".repeat(64), "DENIED", "TARGET_DENIED"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> insertAdmission(sql, executionId, UUID.randomUUID(), 1,
                    "sha256:" + "e".repeat(64), "DENIED", "UNAPPROVED_CODE"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> insertAdmission(sql, executionId, UUID.randomUUID(), 1,
                    "sha256:" + "e".repeat(64), "CONFLICT", "TARGET_DENIED"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.execute("update praxis_bulk.praxis_bulk_admission set outcome='DENIED'"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.execute("delete from praxis_bulk.praxis_bulk_admission"))
                    .isInstanceOf(RuntimeException.class);
            // The unchanged progress is deliberately rejected by physical validation: a
            // separate admission must be acknowledged before the next ordinal is exposed.
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?", executionId);
            BulkExecutionMigrator.validate(dataSource);
            insertAdmission(sql, executionId, UUID.randomUUID(), 1,
                    fixture.digest1(), "DENIED", "TARGET_DENIED");
            // Seed an impossible-but-well-shaped physical state to prove the validator
            // checks the terminal-status evidence. Runtime terminal guards reject it.
            sql.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_guard_terminal");
            sql.execute("alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_release_active_allocation");
            sql.update("""
                    with stamp as materialized (select clock_timestamp() as observed_at)
                    update praxis_bulk.praxis_bulk_execution
                    set next_ordinal=2, status='COMPLETED', terminal_at=stamp.observed_at,
                        updated_at=stamp.observed_at
                    from stamp
                    where execution_id=?
                    """, executionId);
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("update praxis_bulk.praxis_bulk_execution set status='COMPLETED_WITH_ERRORS' where execution_id=?",
                    executionId);
            sql.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_guard_terminal");
            sql.execute("alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_release_active_allocation");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            // Simulate the privileged operator repairing the deliberately trigger-bypassed fixture.
            // A normal terminal transition performs this release in its own transaction.
            sql.update("update praxis_bulk.praxis_bulk_allocation set state='RELEASED', "
                    + "released_at=clock_timestamp(), release_reason='TERMINAL_RECONCILED' where execution_id=? "
                    + "and kind='EXECUTION_ACTIVE' and state='ACTIVE'", executionId);
            BulkExecutionMigrator.validate(dataSource);
            assertThat(sql.queryForObject("select status from praxis_bulk.praxis_bulk_execution "
                    + "where execution_id=?", String.class, executionId)).isEqualTo("COMPLETED_WITH_ERRORS");

            sql.execute("create role admission_runtime login");
            sql.execute("grant usage on schema praxis_bulk to admission_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_admission to admission_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_atomic_receipt, "
                    + "praxis_bulk.praxis_bulk_atomic_item_result, praxis_bulk.praxis_bulk_atomic_effect_ref, praxis_bulk.praxis_bulk_atomic_rejection to admission_runtime");
            sql.execute("grant execute on function praxis_bulk.atomic_evidence_complete(uuid,integer) to admission_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_manifest to admission_runtime");

            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_preview_state to admission_runtime");

            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_target_preview to admission_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_preview_item_integrity to admission_runtime");
            sql.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to admission_runtime");
            sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to admission_runtime");
            sql.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to admission_runtime");
            sql.execute("grant select on praxis_bulk.praxis_bulk_capacity_marker, "
                    + "praxis_bulk.praxis_bulk_capacity_installation to admission_runtime");
            sql.execute("grant select on praxis_bulk.praxis_bulk_capacity_slot, "
                    + "praxis_bulk.praxis_bulk_capacity_occupation to admission_runtime");
            sql.execute("grant execute on function praxis_bulk.lock_capacity_marker(), "
                    + "praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint) to admission_runtime");
            var runtimeRoles = new BulkExecutionRoleConfiguration("postgres",
                    java.util.Set.of("admission_runtime"), java.util.Set.of(), java.util.Set.of());
            BulkExecutionMigrator.validate(dataSource, runtimeRoles);
            var runtime = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl("admission_runtime", "postgres"), "admission_runtime", ""));
            assertThat(runtime.queryForObject("select count(*) from praxis_bulk.praxis_bulk_admission",
                    Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> runtime.execute("update praxis_bulk.praxis_bulk_admission set reason_code='TARGET_DENIED'"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> runtime.execute("delete from praxis_bulk.praxis_bulk_admission"))
                    .isInstanceOf(RuntimeException.class);
            sql.execute("grant update on praxis_bulk.praxis_bulk_admission to admission_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.execute("revoke update on praxis_bulk.praxis_bulk_admission from admission_runtime");
            BulkExecutionMigrator.validate(dataSource, runtimeRoles);
            sql.execute("grant update(reason_code) on praxis_bulk.praxis_bulk_admission to admission_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void v4PhysicalReadbackRejectsReceiptAdmissionOverlapAndPrefixHoles() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            var fixture = insertExecution(dataSource, sql, "RUNNING", 0, false);
            UUID executionId = fixture.id();
            insertAdmission(sql, executionId, UUID.randomUUID(), 0, fixture.digest0(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            sql.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?", executionId);
            sql.execute("alter table praxis_bulk.praxis_bulk_item_receipt disable trigger praxis_bulk_receipt_guard_terminal");
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_item_receipt
                    (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                     owner_epoch, outcome, confirmed_at)
                    values (?, 0, ?, 'v1', ?, 1, 'CONFIRMED', clock_timestamp())
                    """, executionId, fixture.digest0(), UUID.randomUUID());
            sql.execute("alter table praxis_bulk.praxis_bulk_item_receipt enable trigger praxis_bulk_receipt_guard_terminal");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);

            sql.execute("drop schema praxis_bulk cascade");
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            fixture = insertExecution(dataSource, sql, "RUNNING", 0, false);
            executionId = fixture.id();
            sql.execute("alter table praxis_bulk.praxis_bulk_admission disable trigger praxis_bulk_admission_guard_terminal");
            insertAdmissionRaw(sql, executionId, UUID.randomUUID(), 1, fixture.digest1(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            sql.execute("alter table praxis_bulk.praxis_bulk_admission enable trigger praxis_bulk_admission_guard_terminal");
            UUID finalExecutionId = executionId;
            sql.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?", finalExecutionId);
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);

            sql.execute("drop schema praxis_bulk cascade");
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            fixture = insertExecution(dataSource, sql, "RUNNING", 0, false);
            executionId = fixture.id();
            insertAdmission(sql, executionId, UUID.randomUUID(), 0, fixture.digest0(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            sql.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?", executionId);
            BulkExecutionMigrator.validate(dataSource);
            sql.update("update praxis_bulk.praxis_bulk_execution set status='STOPPED', "
                    + "terminal_at=clock_timestamp(), terminal_reason_code='POLICY_BLOCKED' where execution_id=?", executionId);
            // Append-only evidence cannot be patched. A forged matching prefix is still rejected.
            sql.execute("alter table praxis_bulk.praxis_bulk_admission disable trigger praxis_bulk_admission_reject_mutation");
            sql.update("update praxis_bulk.praxis_bulk_admission set expected_version='forged' where execution_id=?", executionId);
            sql.execute("alter table praxis_bulk.praxis_bulk_admission enable trigger praxis_bulk_admission_reject_mutation");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void pendingAckRequiresTheExactReceiptRatherThanAnAdmissionOrAnotherAttempt() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            var fixture = insertExecution(dataSource, sql, "RUNNING", 0, false);
            UUID attemptId = UUID.randomUUID();
            prepareUnit(sql, fixture.id(), attemptId, 0, fixture.digest0());
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_item_receipt
                    (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                     owner_epoch, outcome, confirmed_at)
                    values (?, 0, ?, 'v1', ?, 1, 'CONFIRMED', clock_timestamp())
                    """, fixture.id(), fixture.digest0(), attemptId);
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='UNIT_COMMITTED_PENDING_ACK', active_attempt_id=?, active_attempt_ordinal=0,
                        active_target_digest=?, active_attempt_epoch=1
                    where execution_id=?
                    """, attemptId, fixture.digest0(), fixture.id());
            BulkExecutionMigrator.validate(dataSource);

            sql.update("update praxis_bulk.praxis_bulk_execution set active_attempt_id=? where execution_id=?",
                    UUID.randomUUID(), fixture.id());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("update praxis_bulk.praxis_bulk_execution set active_attempt_id=? where execution_id=?",
                    attemptId, fixture.id());
            sql.update("update praxis_bulk.praxis_bulk_execution set active_target_digest=? where execution_id=?",
                    fixture.digest1(), fixture.id());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("update praxis_bulk.praxis_bulk_execution set active_target_digest=? where execution_id=?",
                    fixture.digest0(), fixture.id());
            sql.update("update praxis_bulk.praxis_bulk_execution set owner_epoch=2, active_attempt_epoch=2 "
                    + "where execution_id=?", fixture.id());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);

            sql.execute("drop schema praxis_bulk cascade");
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            fixture = insertExecution(dataSource, sql, "RUNNING", 0, false);
            attemptId = UUID.randomUUID();
            insertAdmission(sql, fixture.id(), attemptId, 0, fixture.digest0(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='UNIT_COMMITTED_PENDING_ACK', active_attempt_id=?, active_attempt_ordinal=0,
                        active_target_digest=?, active_attempt_epoch=1
                    where execution_id=?
                    """, attemptId, fixture.digest0(), fixture.id());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void newStopsRequireApprovedReasonAndCannotClaimLegacyProvenance() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            UUID executionId = insertExecution(dataSource, sql, "RUNNING", 0, false).id();
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='UNIT_IN_FLIGHT', active_attempt_id=?, active_attempt_ordinal=0,
                        active_target_digest=?, active_attempt_epoch=1
                    where execution_id=?
                    """, UUID.randomUUID(), "sha256:" + "c".repeat(64), executionId);
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp(),
                        terminal_reason_code='UNIT_ROLLED_BACK' where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='RUNNING', active_attempt_id=null, active_attempt_ordinal=null,
                        active_target_digest=null, active_attempt_epoch=null
                    where execution_id=?
                    """, executionId);
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp() where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp(),
                        terminal_reason_code='LEGACY_REASON_NOT_RECORDED' where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp(),
                        terminal_reason_code='RAW_PROVIDER_EXCEPTION' where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp(),
                        terminal_reason_code='POLICY_BLOCKED' where execution_id=?
                    """, executionId);
            var independent = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres"));
            assertThat(independent.queryForObject("select terminal_reason_code from "
                    + "praxis_bulk.praxis_bulk_execution where execution_id=?", String.class, executionId))
                    .isEqualTo("POLICY_BLOCKED");
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set terminal_reason_code='RECOVERY_STOPPED' where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            BulkExecutionMigrator.validate(dataSource);
        }
    }

    private static void migrateToV3(javax.sql.DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("3").load().migrate();
    }

    private static void migrateToV5(javax.sql.DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("5").load().migrate();
    }

    @Test
    void v13UpgradesV5WithoutChangingItsAppliedChecksum() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToV5(dataSource);
            int priorV5Checksum = sql.queryForObject(
                    "select checksum from praxis_bulk.praxis_bulk_schema_history where version='5'", Integer.class);

            assertThat(migrate(dataSource)).isEqualTo(15);

            assertThat(sql.queryForObject(
                    "select checksum from praxis_bulk.praxis_bulk_schema_history where version='5'", Integer.class))
                    .isEqualTo(priorV5Checksum);
            assertThat(sql.queryForObject(
                    "select version from praxis_bulk.praxis_bulk_schema_history order by installed_rank desc limit 1",
                    String.class)).isEqualTo("20");
            BulkExecutionMigrator.validate(dataSource);
        }
    }

    @Test
    void v13RepairsOnlyCertifiedLegacyTerminalSkewAndPreservesV5History() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToV3(dataSource);
            var terminal = insertExecution(dataSource, sql, "STOPPED", 0, true);
            sql.update("update praxis_bulk.praxis_bulk_execution set terminal_at=updated_at + interval '1 microsecond' "
                    + "where execution_id=?", terminal.id());
            migrateToVersion(dataSource, "12");
            int v5Checksum = sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='5'", Integer.class);
            assertThat(sql.queryForObject("select terminal_at > updated_at from "
                    + "praxis_bulk.praxis_bulk_execution where execution_id=?", Boolean.class, terminal.id()))
                    .isTrue();

            assertThat(migrate(dataSource)).isEqualTo(8);
            assertThat(sql.queryForObject("select terminal_at = updated_at from "
                    + "praxis_bulk.praxis_bulk_execution where execution_id=?", Boolean.class, terminal.id()))
                    .isTrue();
            assertThat(sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history "
                    + "where version='5'", Integer.class)).isEqualTo(v5Checksum);
            assertThat(sql.queryForObject("select convalidated from pg_constraint where conname="
                    + "'praxis_bulk_execution_time_order_check'", Boolean.class)).isTrue();
            BulkExecutionMigrator.validate(dataSource);
        }
    }

    @Test
    void v13MalformedHistoryRollsBackFunctionOwnershipTriggerAndPrivilegeChanges() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToV3(dataSource);
            var execution = insertExecution(dataSource, sql, "RUNNING", 0, false);
            migrateToVersion(dataSource, "12");
            sql.update("update praxis_bulk.praxis_bulk_execution set updated_at=created_at - interval '1 second' "
                    + "where execution_id=?", execution.id());
            String oldBody = sql.queryForObject("select md5(prosrc) from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class);

            assertThatThrownBy(() -> migrate(dataSource)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("praxis_bulk_execution_time_order_check");
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank desc limit 1", String.class)).isEqualTo("12");
            assertThat(sql.queryForObject("select md5(prosrc) from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class))
                    .isEqualTo(oldBody);
            assertThat(sql.queryForObject("select tgenabled='O' from pg_trigger where tgname="
                    + "'praxis_bulk_execution_guard_terminal'", Boolean.class)).isTrue();
            assertThat(sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_execution') is not null "
                    + "and not exists(select 1 from pg_constraint where conname="
                    + "'praxis_bulk_execution_time_order_check')", Boolean.class)).isTrue();
            assertThat(sql.queryForObject("select has_schema_privilege('praxis_bulk_retention_owner', "
                    + "'praxis_bulk', 'CREATE')", Boolean.class)).isFalse();
            assertThat(sql.queryForObject("select count(*) from pg_auth_members where roleid="
                    + "'praxis_bulk_retention_owner'::regrole", Integer.class)).isZero();

            sql.update("update praxis_bulk.praxis_bulk_execution set updated_at=clock_timestamp() "
                    + "where execution_id=?", execution.id());
            assertThat(migrate(dataSource)).isEqualTo(8);
            BulkExecutionMigrator.validate(dataSource);
        }
    }

    @Test
    void v13RejectsPriorGuardDriftAndLaterConstraintDriftWithoutHealing() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToVersion(dataSource, "12");
            sql.execute("grant execute on function praxis_bulk.guard_terminal_execution() to public");
            assertThatThrownBy(() -> migrate(dataSource)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("bulk V5/V10 chronology guard attestation failed");
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank desc limit 1", String.class)).isEqualTo("12");
            sql.execute("revoke execute on function praxis_bulk.guard_terminal_execution() from public");
            sql.execute("grant create on schema praxis_bulk to praxis_bulk_retention_owner");
            assertThatThrownBy(() -> migrate(dataSource)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("bulk V5/V10 chronology guard attestation failed");
            sql.execute("revoke create on schema praxis_bulk from praxis_bulk_retention_owner");
            sql.execute("alter function praxis_bulk.guard_terminal_execution() parallel safe");
            assertThatThrownBy(() -> migrate(dataSource)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("bulk V5/V10 chronology guard attestation failed");
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank desc limit 1", String.class)).isEqualTo("12");
            sql.execute("alter function praxis_bulk.guard_terminal_execution() parallel unsafe");
            assertThat(migrate(dataSource)).isEqualTo(8);
            sql.execute("alter table praxis_bulk.praxis_bulk_execution drop constraint "
                    + "praxis_bulk_execution_time_order_check");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> migrate(dataSource)).isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("select count(*) from pg_constraint where conname="
                    + "'praxis_bulk_execution_time_order_check'", Integer.class)).isZero();
        }
    }

    @Test
    void v13RejectsSameNamedTerminalTriggerWithFalseWhenBeforeAnyChange() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToVersion(dataSource, "12");
            String v5Body = sql.queryForObject("select md5(prosrc) from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class);
            String v5Acl = sql.queryForObject("select proacl::text from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class);
            sql.execute("drop trigger praxis_bulk_execution_guard_terminal on "
                    + "praxis_bulk.praxis_bulk_execution");
            sql.execute("""
                    create trigger praxis_bulk_execution_guard_terminal before update
                    on praxis_bulk.praxis_bulk_execution for each row when (false)
                    execute function praxis_bulk.guard_terminal_execution()
                    """);

            assertThatThrownBy(() -> migrate(dataSource)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("bulk V5/V10 chronology guard attestation failed");
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank desc limit 1", String.class)).isEqualTo("12");
            assertThat(sql.queryForObject("select md5(prosrc) from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class))
                    .isEqualTo(v5Body);
            assertThat(sql.queryForObject("select proacl::text from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class))
                    .isEqualTo(v5Acl);
            assertThat(sql.queryForObject("select owner.rolname from pg_proc p join pg_roles owner "
                    + "on owner.oid=p.proowner where p.oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class))
                    .isEqualTo("praxis_bulk_retention_owner");
            assertThat(sql.queryForObject("select has_schema_privilege('praxis_bulk_retention_owner', "
                    + "'praxis_bulk', 'CREATE')", Boolean.class)).isFalse();
            assertThat(sql.queryForObject("select count(*) from pg_auth_members where roleid="
                    + "'praxis_bulk_retention_owner'::regrole", Integer.class)).isZero();
            assertThat(sql.queryForObject("select tgqual is not null from pg_trigger where tgname="
                    + "'praxis_bulk_execution_guard_terminal'", Boolean.class)).isTrue();
        }
    }

    @Test
    void v13RejectsSameNamedCancelTriggerWithFalseWhenBeforeAnyChange() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToVersion(dataSource, "12");
            String v5Body = sql.queryForObject("select md5(prosrc) from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class);
            String v10Body = sql.queryForObject("select md5(prosrc) from pg_proc where oid="
                    + "'praxis_bulk.protect_cancel_request()'::regprocedure", String.class);
            String v10Acl = sql.queryForObject("select proacl::text from pg_proc where oid="
                    + "'praxis_bulk.protect_cancel_request()'::regprocedure", String.class);
            sql.execute("drop trigger praxis_bulk_execution_protect_cancel on "
                    + "praxis_bulk.praxis_bulk_execution");
            sql.execute("""
                    create trigger praxis_bulk_execution_protect_cancel before insert or update
                    on praxis_bulk.praxis_bulk_execution for each row when (false)
                    execute function praxis_bulk.protect_cancel_request()
                    """);

            assertThatThrownBy(() -> migrate(dataSource)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("bulk V5/V10 chronology guard attestation failed");
            assertThat(sql.queryForObject("select version from praxis_bulk.praxis_bulk_schema_history "
                    + "order by installed_rank desc limit 1", String.class)).isEqualTo("12");
            assertThat(sql.queryForObject("select md5(prosrc) from pg_proc where oid="
                    + "'praxis_bulk.guard_terminal_execution()'::regprocedure", String.class))
                    .isEqualTo(v5Body);
            assertThat(sql.queryForObject("select md5(prosrc) from pg_proc where oid="
                    + "'praxis_bulk.protect_cancel_request()'::regprocedure", String.class))
                    .isEqualTo(v10Body);
            assertThat(sql.queryForObject("select proacl::text from pg_proc where oid="
                    + "'praxis_bulk.protect_cancel_request()'::regprocedure", String.class))
                    .isEqualTo(v10Acl);
            assertThat(sql.queryForObject("select has_schema_privilege('praxis_bulk_retention_owner', "
                    + "'praxis_bulk', 'CREATE')", Boolean.class)).isFalse();
            assertThat(sql.queryForObject("select count(*) from pg_auth_members where roleid="
                    + "'praxis_bulk_retention_owner'::regrole", Integer.class)).isZero();
            assertThat(sql.queryForObject("select tgqual is not null from pg_trigger where tgname="
                    + "'praxis_bulk_execution_protect_cancel'", Boolean.class)).isTrue();
        }
    }

    @Test
    void v13TerminalAndV10CancelTriggersPreserveChronologyOnTheSameUpdate() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(migrate(dataSource)).isEqualTo(20);
            BulkPostgresTestSupport.ready(dataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
            var execution = insertExecution(dataSource, sql, "RUNNING", 0, false);
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_reason_code='CANCELLED_BY_USER',
                        cancel_requested_at=clock_timestamp(), terminal_at=clock_timestamp(),
                        updated_at=clock_timestamp()
                    where execution_id=?
                    """, execution.id());
            assertThat(sql.queryForObject("""
                    select created_at <= cancel_requested_at
                       and cancel_requested_at <= terminal_at
                       and terminal_at <= updated_at
                    from praxis_bulk.praxis_bulk_execution where execution_id=?
                    """, Boolean.class, execution.id())).isTrue();
            BulkExecutionMigrator.validate(dataSource);
        }
    }

    private static void migrateToVersion(javax.sql.DataSource dataSource, String version) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target(version).load().migrate();
    }

    private static ExecutionFixture insertExecution(javax.sql.DataSource dataSource, JdbcTemplate sql,
            String status, int nextOrdinal, boolean terminal) {
        return insertExecution(dataSource, sql, status, nextOrdinal, terminal, Instant.now().minusSeconds(5));
    }

    private static ExecutionFixture insertExecution(javax.sql.DataSource dataSource, JdbcTemplate sql,
            String status, int nextOrdinal, boolean terminal, Instant created) {
        return insertExecution(dataSource, sql, status, nextOrdinal, terminal, created, FINGERPRINT);
    }

    private static ExecutionFixture insertExecution(javax.sql.DataSource dataSource, JdbcTemplate sql,
            String status, int nextOrdinal, boolean terminal, Instant created, String keyDigest) {
        return insertProposalAndOptionalExecution(dataSource, sql, status, nextOrdinal, terminal,
                created, keyDigest, true);
    }

    /** A historical proposal-only fixture must not silently migrate to head or fabricate an execution. */
    private static ExecutionFixture insertProposalAndOptionalExecution(javax.sql.DataSource dataSource, JdbcTemplate sql,
            String status, int nextOrdinal, boolean terminal, Instant created, String keyDigest,
            boolean includeExecution) {
        var reader = new BulkProtocolReader<>(BulkIdentityCodecs.strings());
        var request = reader.<com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.JsonNode>readCommand(
                """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[
                  {"id":"1","expectedVersion":"v1"},{"id":"2","expectedVersion":"v2"}]},
                  "parameters":{"reason":"migration-fixture"}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                com.fasterxml.jackson.databind.JsonNode::deepCopy,
                com.fasterxml.jackson.databind.JsonNode::deepCopy);
        var snapshot = BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy,
                com.fasterxml.jackson.databind.JsonNode::deepCopy);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(600), snapshot,
                BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        var empty = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var evaluation = new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), List.of(
                new BulkTargetEvidence<>(new BulkTarget<>("1", "v1"), "observed-v1", empty, empty,
                        BulkTargetEligibility.executable()),
                new BulkTargetEvidence<>(new BulkTarget<>("2", "v2"), "observed-v2", empty, empty,
                        BulkTargetEligibility.executable())),
                new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                        new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                                "resource:approve", "NEVER_APPLIED", "test-policy-r1", created.plusMillis(500)))));
        UUID proposalId = proposal.id();
        UUID executionId = includeExecution ? UUID.randomUUID() : null;
        boolean descriptorFence = Boolean.TRUE.equals(sql.queryForObject("""
                select exists (select 1 from information_schema.columns where table_schema='praxis_bulk'
                    and table_name='praxis_bulk_proposal' and column_name='control_generation')
                """, Boolean.class));
        // Schemas from V16 onward use protocol 2; earlier schemas retain their original insert shape.
        boolean currentProtocol = Boolean.TRUE.equals(sql.queryForObject("""
                select exists (select 1 from information_schema.columns where table_schema='praxis_bulk'
                    and table_name='praxis_bulk_proposal' and column_name='protocol_version')
                """, Boolean.class));
        String protocolColumns = currentProtocol ? ", atomicity, protocol_version" : "";
        String protocolValues = currentProtocol ? ", 'PER_ITEM', 2" : "";
        boolean executionModeInstalled = Boolean.TRUE.equals(sql.queryForObject("""
                select exists (select 1 from information_schema.columns where table_schema='praxis_bulk'
                    and table_name='praxis_bulk_proposal' and column_name='execution_mode')
                """, Boolean.class));
        if (executionModeInstalled) {
            protocolColumns += ", execution_mode";
            protocolValues += ", 'SYNC'";
        }
        if (descriptorFence) {
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_proposal
                    (proposal_id, namespace_id, subject_id, resource_key, operation_id,
                     created_at, expires_at, fingerprint, payload, control_generation,
                     control_descriptor_fingerprint, control_structural_revision%s)
                    values (?, 'tenant:prod:payroll', 'operator', 'employees', 'employee-bulk-approve',
                            ?, ?, ?, ?, 1, ?, 'structural-r1'%s)
                    """.formatted(protocolColumns, protocolValues), proposalId, java.sql.Timestamp.from(proposal.createdAt()),
                    java.sql.Timestamp.from(proposal.expiresAt()), snapshot.fingerprint(),
                    BulkSnapshotStorageCodec.encode(snapshot),
                    BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION.descriptorFingerprint());
        } else {
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_proposal
                    (proposal_id, namespace_id, subject_id, resource_key, operation_id,
                     created_at, expires_at, fingerprint, payload)
                    values (?, 'tenant:prod:payroll', 'operator', 'employees', 'employee-bulk-approve',
                            ?, ?, ?, ?)
                    """, proposalId, java.sql.Timestamp.from(proposal.createdAt()),
                    java.sql.Timestamp.from(proposal.expiresAt()), snapshot.fingerprint(),
                    BulkSnapshotStorageCodec.encode(snapshot));
        }
        boolean manifestInstalled = Boolean.TRUE.equals(sql.queryForObject(
                "select to_regclass('praxis_bulk.praxis_bulk_target_manifest') is not null", Boolean.class));
        boolean previewInstalled = Boolean.TRUE.equals(sql.queryForObject(
                "select to_regclass('praxis_bulk.praxis_bulk_preview_state') is not null", Boolean.class));
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(transaction -> {
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_evaluation
                    (proposal_id, input_fingerprint, evaluation_fingerprint, payload)
                    values (?, ?, ?, ?)
                    """, proposalId, snapshot.fingerprint(), evaluation.fingerprint(),
                    BulkEvaluationStorageCodec.encode(evaluation));
            if (manifestInstalled) sql.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                BulkOrdinalManifest.insert(connection, evaluation);
                if (previewInstalled) BulkPreviewStorage.insert(connection, evaluation,
                        BulkEvaluationSnapshotTest.preview(evaluation));
                return null;
            });
        });
        if (includeExecution) {
            if (descriptorFence) {
                sql.update("""
                        insert into praxis_bulk.praxis_bulk_execution
                        (execution_id, proposal_id, namespace_id, subject_id, resource_key, operation_id,
                         idempotency_key_digest, reservation_fingerprint, input_fingerprint,
                         evaluation_fingerprint, structural_revision, control_generation,
                         control_descriptor_fingerprint, owner_id, owner_epoch, status,
                         next_ordinal, target_count, deadline_at, created_at, updated_at, terminal_at%s)
                        values (?, ?, 'tenant:prod:payroll', 'operator', 'employees', 'employee-bulk-approve',
                                ?, ?, ?, ?, 'structural-r1', 1, ?, 'owner', 1, ?, ?, 2,
                                clock_timestamp() + interval '30 seconds', ?, clock_timestamp(),
                                case when ? then clock_timestamp() else null end%s)
                        """.formatted(protocolColumns, protocolValues), executionId, proposalId, keyDigest, EVALUATION_FINGERPRINT,
                        snapshot.fingerprint(), evaluation.fingerprint(),
                        BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION.descriptorFingerprint(), status, nextOrdinal,
                        java.sql.Timestamp.from(proposal.createdAt()), terminal);
            } else {
                sql.update("""
                        insert into praxis_bulk.praxis_bulk_execution
                        (execution_id, proposal_id, namespace_id, subject_id, resource_key, operation_id,
                         idempotency_key_digest, reservation_fingerprint, input_fingerprint,
                         evaluation_fingerprint, structural_revision, owner_id, owner_epoch, status,
                         next_ordinal, target_count, deadline_at, created_at, updated_at, terminal_at)
                        values (?, ?, 'tenant:prod:payroll', 'operator', 'employees', 'employee-bulk-approve',
                                ?, ?, ?, ?, 'structural-r1', 'owner', 1, ?, ?, 2,
                                clock_timestamp() + interval '30 seconds', ?, clock_timestamp(),
                                case when ? then clock_timestamp() else null end)
                        """, executionId, proposalId, keyDigest, EVALUATION_FINGERPRINT,
                        snapshot.fingerprint(), evaluation.fingerprint(), status, nextOrdinal,
                        java.sql.Timestamp.from(proposal.createdAt()), terminal);
            }
        }
        if (includeExecution && sql.queryForObject(
                "select to_regclass('praxis_bulk.praxis_bulk_allocation') is not null", Boolean.class)) {
            BulkExecutionMigrator.migrate(dataSource, java.util.Map.of(CONTEXT.namespaceId(),
                    BulkPostgresTestSupport.DEPLOYMENT_ID));
        }
        return new ExecutionFixture(executionId, proposalId,
                BulkExecutionMigrator.evidenceTargetDigest(evaluation, 0),
                BulkExecutionMigrator.evidenceTargetDigest(evaluation, 1));
    }

    private static void insertAdmission(JdbcTemplate sql, UUID executionId, UUID attemptId,
            int ordinal, String digest, String outcome, String reason) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource()));
        tx.executeWithoutResult(status -> {
            prepareUnit(sql, executionId, attemptId, ordinal, digest);
            insertAdmissionRaw(sql, executionId, attemptId, ordinal, digest, outcome, reason);
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='RUNNING', active_attempt_id=null, active_attempt_ordinal=null,
                        active_target_digest=null, active_attempt_epoch=null, active_unit_deadline_at=null
                    where execution_id=?
                    """, executionId);
        });
    }

    private static void prepareUnit(JdbcTemplate sql, UUID executionId, UUID attemptId,
            int ordinal, String digest) {
        sql.update("""
                update praxis_bulk.praxis_bulk_execution
                set status='UNIT_IN_FLIGHT', active_attempt_id=?, active_attempt_ordinal=?,
                    active_target_digest=?, active_attempt_epoch=owner_epoch,
                    active_unit_deadline_at=least(deadline_at, clock_timestamp() + interval '5 seconds')
                where execution_id=?
                """, attemptId, ordinal, digest, executionId);
    }

    private static void insertAdmissionRaw(JdbcTemplate sql, UUID executionId, UUID attemptId,
            int ordinal, String digest, String outcome, String reason) {
        sql.update("""
                insert into praxis_bulk.praxis_bulk_admission
                (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                 owner_epoch, outcome, reason_code, recorded_at)
                values (?, ?, ?, ?, ?, 1, ?, ?, clock_timestamp())
                """, executionId, ordinal, digest, ordinal == 0 ? "v1" : "v2", attemptId, outcome, reason);
    }

    private record ExecutionFixture(UUID id, UUID proposalId, String digest0, String digest1) { }
}
