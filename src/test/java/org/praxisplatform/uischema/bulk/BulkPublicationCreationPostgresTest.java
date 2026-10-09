package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled current-resource V13 prefixes for callback faults, not public native predecessors.
 * Authentic public146 upgrade remains covered by BulkHistoricalPre14ProvisionedUpgradePostgresTest.
 */
class BulkPublicationCreationPostgresTest {
    private static final String HISTORY = "praxis_bulk.praxis_bulk_schema_history";
    private static final String PUBLICATION = "praxis_bulk.praxis_bulk_openapi_publication";
    private static final Map<String,String> BINDING = Map.of("origin-namespace", "origin-deployment");

    @Test
    void zeroBindingsCreateNoSyntheticIdentityDuringV14() throws Exception {
        try (var pg = postgres()) {
            var source = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(source);
            prefix13(source);
            try (var coordinator = source.getConnection()) {
                assertThat(migrate14(source, callback(coordinator, Map.of()))).isEqualTo(1);
            }
            assertThat(sql.queryForObject("select count(*) from " + PUBLICATION, Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_namespace_binding", Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_deployment_bucket", Integer.class)).isZero();
            assertThat(version(sql)).isEqualTo(14);
        }
    }

    @Test
    void failureAfterSeedRollsBackBothDdlAndIdentityThenRetryCreatesExactlyOne() throws Exception {
        try (var pg = postgres()) {
            var source = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(source);
            prefix13(source); bind(sql);
            var retained = sql.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding");
            try (var coordinator = source.getConnection()) {
                var origin = callback(coordinator, BINDING);
                Callback fault = new Callback() {
                    public boolean supports(Event event, Context context) {
                        return event == Event.AFTER_EACH_MIGRATE && context.getMigrationInfo() != null
                                && "14".equals(context.getMigrationInfo().getVersion().getVersion());
                    }
                    public boolean canHandleInTransaction(Event event, Context context) { return true; }
                    public String getCallbackName() { return "zz-test-failure-after-publication-seed"; }
                    public void handle(Event event, Context context) {
                        assertThat(context.getConnection()).isNotNull();
                        try (var st = context.getConnection().createStatement();
                                var rows = st.executeQuery("select count(*) from " + PUBLICATION)) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getInt(1)).isEqualTo(1);
                        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
                        throw new IllegalStateException("test failure after seed before history");
                    }
                };
                assertThatThrownBy(() -> migrate14(source, origin, fault))
                        .hasStackTraceContaining("test failure after seed before history");
                assertNoV14(sql);
                assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding")).isEqualTo(retained);
                assertThat(migrate14(source, callback(coordinator, BINDING))).isEqualTo(1);
            }
            assertDenyOnlyIdentity(sql);
        }
    }

    @Test
    void deterministicHistoryInsertFailureRollsBackCreationBeforeRetry() throws Exception {
        try (var pg = postgres()) {
            var source = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(source);
            prefix13(source); bind(sql);
            // Explicit test-only history fault. It is not part of native source or production migration.
            sql.execute("create function public.reject_origin14_history() returns trigger language plpgsql as $$ "
                    + "begin if new.version='14' then raise exception 'test history14 rejected'; end if; return new; end $$");
            sql.execute("create trigger reject_origin14_history before insert on " + HISTORY
                    + " for each row execute function public.reject_origin14_history()");
            try (var coordinator = source.getConnection()) {
                assertThatThrownBy(() -> migrate14(source, callback(coordinator, BINDING)))
                        .hasStackTraceContaining("test history14 rejected");
                assertNoV14(sql);
                sql.execute("drop trigger reject_origin14_history on " + HISTORY);
                sql.execute("drop function public.reject_origin14_history()");
                assertThat(migrate14(source, callback(coordinator, BINDING))).isEqualTo(1);
            }
            assertDenyOnlyIdentity(sql);
        }
    }

    @Test
    void wrongMappingDeniesBeforeDdlWithoutChangingBindings() throws Exception {
        rejectedPredecessor(sql -> { }, Map.of("origin-namespace", "wrong-deployment"),
                "Publication predecessor binding differs from explicit deployment map");
    }

    @Test
    void missingBucketDeniesBeforeDdl() throws Exception {
        // Owner test fault explicitly removes the bucket; the V5 guard must first be disabled.
        rejectedPredecessor(sql -> {
            sql.execute("alter table praxis_bulk.praxis_bulk_deployment_bucket disable trigger praxis_bulk_deployment_bucket_guard_mutation");
            sql.execute("delete from praxis_bulk.praxis_bulk_deployment_bucket");
            sql.execute("alter table praxis_bulk.praxis_bulk_deployment_bucket enable trigger praxis_bulk_deployment_bucket_guard_mutation");
        }, BINDING, "Publication predecessor binding has no durable deployment bucket");
    }

    @Test
    void changedImmutabilityTriggerDeniesBeforeDdl() throws Exception {
        rejectedPredecessor(sql -> sql.execute("alter table praxis_bulk.praxis_bulk_namespace_binding "
                + "disable trigger praxis_bulk_namespace_binding_immutable"), BINDING,
                "Publication predecessor immutable trigger differs");
    }

    @Test
    void enabledConditionalImmutabilityTriggerDeniesBeforeDdl() throws Exception {
        rejectedPredecessor(sql -> {
            sql.execute("drop trigger praxis_bulk_namespace_binding_immutable "
                    + "on praxis_bulk.praxis_bulk_namespace_binding");
            sql.execute("create trigger praxis_bulk_namespace_binding_immutable "
                    + "before update or delete on praxis_bulk.praxis_bulk_namespace_binding "
                    + "for each row when (false) execute function praxis_bulk.protect_namespace_binding()");
            assertThat(sql.queryForObject("select tgenabled::text from pg_catalog.pg_trigger "
                    + "where tgrelid = 'praxis_bulk.praxis_bulk_namespace_binding'::regclass "
                    + "and tgname = 'praxis_bulk_namespace_binding_immutable'", String.class)).isEqualTo("O");
        }, BINDING, "Publication predecessor immutable trigger differs");
    }

    @Test
    void publicTableGrantDeniesBeforeDdl() throws Exception {
        rejectedPredecessor(sql -> sql.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to public"),
                BINDING, "Publication predecessor table has unexpected grantee");
    }

    @Test
    void changedBindingShapeDeniesBeforeDdl() throws Exception {
        rejectedPredecessor(sql -> sql.execute("alter table praxis_bulk.praxis_bulk_namespace_binding add column untrusted text"),
                BINDING, "durable storage columns differ");
    }

    @Test
    void twoCanonicalOwnersConvergeOnFreshInstallation() throws Exception {
        try (var pg = postgres(); var executor = Executors.newFixedThreadPool(2)) {
            var source = pg.getPostgresDatabase();
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return BulkExecutionMigrator.migrate(source, BINDING); });
            var second = executor.submit(() -> { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return BulkExecutionMigrator.migrate(source, BINDING); });
            start.countDown();
            assertThat(first.get(60, TimeUnit.SECONDS) + second.get(60, TimeUnit.SECONDS)).isEqualTo(20);
            var sql = new JdbcTemplate(source);
            assertThat(version(sql)).isEqualTo(20);
            assertDenyOnlyIdentity(sql);
            BulkExecutionMigrator.validate(source, BulkExecutionRoleConfiguration.none("postgres"));
        }
    }

    @Test
    void wrongPackagedChecksumAndScriptDenyBeforeConnectionAccess() throws Exception {
        try (var pg = postgres(); var coordinator = pg.getPostgresDatabase().getConnection()) {
            var origin = callback(coordinator, Map.of());
            var field = origin.getClass().getDeclaredField("checksum");
            field.setAccessible(true);
            int checksum = field.getInt(origin);
            var info = mock(org.flywaydb.core.api.MigrationInfo.class);
            when(info.getVersion()).thenReturn(org.flywaydb.core.api.MigrationVersion.fromVersion("14"));
            when(info.getScript()).thenReturn("V14__bulk_openapi_publication.sql");
            when(info.getChecksum()).thenReturn(checksum + 1);
            var context = mock(Context.class);
            when(context.getMigrationInfo()).thenReturn(info);
            assertThatThrownBy(() -> origin.handle(Event.BEFORE_EACH_MIGRATE, context))
                    .hasMessage("Bulk publication creation migration differs from its packaged resource");
            when(info.getChecksum()).thenReturn(checksum);
            when(info.getScript()).thenReturn("V14__other.sql");
            assertThatThrownBy(() -> origin.handle(Event.BEFORE_EACH_MIGRATE, context))
                    .hasMessage("Bulk publication creation migration differs from its packaged resource");
            verify(context, never()).getConnection();
        }
    }

    @Test
    void nontransactionalMigrationConnectionIsRejectedBeforeCatalogAccess() throws Exception {
        try (var pg = postgres(); var coordinator = pg.getPostgresDatabase().getConnection()) {
            var origin = callback(coordinator, Map.of());
            var connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            var context = context(origin, connection);
            assertThatThrownBy(() -> origin.handle(Event.BEFORE_EACH_MIGRATE, context))
                    .hasMessage("Bulk publication creation requires a migration transaction");
            verify(connection, never()).createStatement();
        }
    }

    @Test
    void effectiveRoleChangeCannotProvideCreationAuthority() throws Exception {
        try (var pg = postgres()) {
            var source = pg.getPostgresDatabase();
            prefix13(source);
            new JdbcTemplate(source).execute("create role origin_untrusted login");
            try (var coordinator = source.getConnection(); var migration = source.getConnection()) {
                migration.setAutoCommit(false);
                try {
                    try (var statement = migration.createStatement()) { statement.execute("set role origin_untrusted"); }
                    var origin = callback(coordinator, Map.of());
                    assertThatThrownBy(() -> origin.handle(Event.BEFORE_EACH_MIGRATE, context(origin, migration)))
                            .hasMessage("Bulk connection session and effective PostgreSQL identities must match");
                } finally { migration.rollback(); }
            }
            assertNoV14(new JdbcTemplate(source));
        }
    }

    @Test
    void changedBackendOrTransactionCannotReuseTheBeforeWitness() throws Exception {
        try (var pg = postgres()) {
            var source = pg.getPostgresDatabase();
            prefix13(source); bind(new JdbcTemplate(source));
            try (var coordinator = source.getConnection(); var first = source.getConnection();
                    var second = source.getConnection()) {
                first.setAutoCommit(false); second.setAutoCommit(false);
                try {
                    var origin = callback(coordinator, BINDING);
                    origin.handle(Event.BEFORE_EACH_MIGRATE, context(origin, first));
                    assertThatThrownBy(() -> origin.handle(Event.AFTER_EACH_MIGRATE, context(origin, second)))
                            .hasMessage("Bulk publication creation lost its migration transaction origin");
                    first.commit(); // The next transaction has a new xid even on this same backend.
                    assertThatThrownBy(() -> origin.handle(Event.AFTER_EACH_MIGRATE, context(origin, first)))
                            .hasMessage("Bulk publication creation lost its migration transaction origin");
                } finally { first.rollback(); second.rollback(); }
            }
            assertNoV14(new JdbcTemplate(source));
        }
    }

    @Test
    void wrongCoordinatorDatabaseCannotProvideCreationAuthority() throws Exception {
        try (var pg = postgres(); var migration = pg.getPostgresDatabase().getConnection()) {
            var ownerType = Class.forName(BulkExecutionMigrator.class.getName() + "$MigrationOwnerBackend");
            var ownerConstructor = ownerType.getDeclaredConstructor(int.class, String.class, String.class);
            ownerConstructor.setAccessible(true);
            Object wrong = ownerConstructor.newInstance(Integer.MAX_VALUE, "postgres", "different-database");
            var callbackType = Class.forName(BulkExecutionMigrator.class.getName() + "$PublicationCreationCallback");
            var constructor = callbackType.getDeclaredConstructor(BulkExecutionRoleConfiguration.class, Map.class, ownerType);
            constructor.setAccessible(true);
            var origin = (Callback) constructor.newInstance(BulkExecutionRoleConfiguration.none("postgres"), Map.of(), wrong);
            migration.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> origin.handle(Event.BEFORE_EACH_MIGRATE, context(origin, migration)))
                        .hasMessage("Bulk publication migration identity differs from coordinator");
            } finally { migration.rollback(); }
        }
    }

    private static Context context(Callback origin, Connection connection) throws Exception {
        var field = origin.getClass().getDeclaredField("checksum");
        field.setAccessible(true);
        var info = mock(org.flywaydb.core.api.MigrationInfo.class);
        when(info.getVersion()).thenReturn(org.flywaydb.core.api.MigrationVersion.fromVersion("14"));
        when(info.getScript()).thenReturn("V14__bulk_openapi_publication.sql");
        when(info.getChecksum()).thenReturn(field.getInt(origin));
        var context = mock(Context.class);
        when(context.getMigrationInfo()).thenReturn(info);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }

    @Test
    void changedImmutableFunctionBodyDeniesWithTriggerStillEnabled() throws Exception {
        rejectedPredecessor(sql -> {
            sql.execute("create or replace function praxis_bulk.protect_namespace_binding() "
                    + "returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$ "
                    + "begin raise exception 'test forged immutability body' using errcode='55000'; end; $$");
            assertThat(sql.queryForObject("select tgenabled::text from pg_catalog.pg_trigger "
                    + "where tgrelid='praxis_bulk.praxis_bulk_namespace_binding'::regclass "
                    + "and tgname='praxis_bulk_namespace_binding_immutable'", String.class)).isEqualTo("O");
        }, BINDING, "Publication predecessor immutable trigger differs");
    }

    @Test
    void authenticatedNonownerIsRejectedEvenWhenSessionAndEffectiveRoleMatch() throws Exception {
        try (var pg = postgres()) {
            var source = pg.getPostgresDatabase();
            prefix13(source);
            new JdbcTemplate(source).execute("create role origin_authenticated_nonowner login");
            var nonowner = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    pg.getJdbcUrl("origin_authenticated_nonowner", "postgres"), "origin_authenticated_nonowner", "");
            try (var coordinator = source.getConnection(); var migration = nonowner.getConnection()) {
                migration.setAutoCommit(false);
                try {
                    try (var statement = migration.createStatement();
                            var rows = statement.executeQuery("select session_user,current_user")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("origin_authenticated_nonowner");
                        assertThat(rows.getString(2)).isEqualTo(rows.getString(1));
                        assertThat(rows.next()).isFalse();
                    }
                    var origin = callback(coordinator, Map.of());
                    assertThatThrownBy(() -> origin.handle(Event.BEFORE_EACH_MIGRATE, context(origin, migration)))
                            .hasMessage("Bulk connection identity is not an allowlisted PostgreSQL role");
                } finally { migration.rollback(); }
            }
            assertNoV14(new JdbcTemplate(source));
        }
    }

    private static void rejectedPredecessor(java.util.function.Consumer<JdbcTemplate> fault,
            Map<String,String> mapping, String diagnostic) throws Exception {
        try (var pg = postgres()) {
            var source = pg.getPostgresDatabase();
            var sql = new JdbcTemplate(source);
            prefix13(source); bind(sql); fault.accept(sql);
            var retained = sql.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding");
            var history = sql.queryForList("select * from " + HISTORY + " order by installed_rank");
            try (var coordinator = source.getConnection()) {
                assertThatThrownBy(() -> migrate14(source, callback(coordinator, mapping)))
                        .hasStackTraceContaining(diagnostic);
            }
            assertNoV14(sql);
            assertThat(sql.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding")).isEqualTo(retained);
            assertThat(sql.queryForList("select * from " + HISTORY + " order by installed_rank")).isEqualTo(history);
        }
    }

    private static EmbeddedPostgres postgres() throws Exception {
        return EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
    }

    private static void prefix13(DataSource source) {
        Flyway.configure().dataSource(source).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .createSchemas(true).baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true)
                .target("13").load().migrate();
    }

    private static void bind(JdbcTemplate sql) {
        sql.execute("insert into praxis_bulk.praxis_bulk_deployment_bucket values ('origin-deployment')");
        sql.execute("insert into praxis_bulk.praxis_bulk_namespace_binding values ('origin-namespace','origin-deployment',clock_timestamp())");
    }

    private static Callback callback(Connection coordinator, Map<String,String> bindings) throws Exception {
        var identity = BulkExecutionMigrator.class.getDeclaredMethod("readMigrationOwnerBackend", Connection.class);
        identity.setAccessible(true);
        Object owner = identity.invoke(null, coordinator);
        var type = Class.forName(BulkExecutionMigrator.class.getName() + "$PublicationCreationCallback");
        var constructor = type.getDeclaredConstructor(BulkExecutionRoleConfiguration.class, Map.class, owner.getClass());
        constructor.setAccessible(true);
        return (Callback) constructor.newInstance(BulkExecutionRoleConfiguration.none("postgres"), bindings, owner);
    }

    private static int migrate14(DataSource source, Callback... callbacks) throws Exception {
        var factory = BulkExecutionMigrator.class.getDeclaredMethod("flyway", DataSource.class, int.class, Callback[].class);
        factory.setAccessible(true);
        try { return ((Flyway) factory.invoke(null, source, 14, callbacks)).migrate().migrationsExecuted; }
        catch (InvocationTargetException failure) { throw new IllegalStateException(failure.getCause()); }
    }

    private static int version(JdbcTemplate sql) {
        return sql.queryForObject("select max(version::integer) from " + HISTORY + " where success and version is not null", Integer.class);
    }

    private static void assertNoV14(JdbcTemplate sql) {
        assertThat(version(sql)).isEqualTo(13);
        assertThat(sql.queryForObject("select to_regclass('" + PUBLICATION + "') is null", Boolean.class)).isTrue();
    }

    private static void assertDenyOnlyIdentity(JdbcTemplate sql) {
        assertThat(sql.queryForObject("select count(*) from " + PUBLICATION, Integer.class)).isEqualTo(1);
        var row = sql.queryForMap("select * from " + PUBLICATION);
        assertThat(row.get("deployment_id")).isEqualTo("origin-deployment");
        assertThat(row.get("state")).isEqualTo("UNCOMPOSED");
        assertThat(((Number) row.get("generation")).longValue()).isZero();
        assertThat(row.get("document_digest")).isNull();
    }
}
