package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Physical migration and privilege gates for the independent issuer, without a local job store. */
class BulkCapacityAuthorityMigrationPostgresTest {
    private static final String SCHEMA = "praxis_bulk_capacity";
    private static final String HISTORY = SCHEMA + ".praxis_bulk_capacity_schema_history";
    private static final BulkCapacityAuthorityMigrator.Identity IDENTITY =
            new BulkCapacityAuthorityMigrator.Identity("deployment-east", "prod",
                    UUID.fromString("37e114d5-f14b-45f6-bf66-fdbba458f42c"), 7);
    private static final String PROVISIONER = "cap_provisioner_test";
    private static final String ALLOCATOR = "cap_allocator_test";
    private static final String READER = "cap_reader_test";
    private static final String RESTRICTED_OWNER = "cap_authority_owner_test";
    private static final String RESTRICTED_DATABASE = "cap_authority_restricted_test";
    private static final BulkCapacityAuthorityMigrator.RoleConfiguration ROLES =
            new BulkCapacityAuthorityMigrator.RoleConfiguration("postgres", PROVISIONER, ALLOCATOR, READER);

    @Test
    void freshAndRepeatUseOnlyDedicatedCapacityHistory() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);

            assertThat(BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES)).isEqualTo(1);
            BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES);
            assertExactV1History(sql);
            var historyBefore = historySnapshot(sql);
            assertThat(sql.queryForObject("select to_regnamespace('praxis_bulk') is null", Boolean.class)).isTrue();
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".capacity_token", Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".capacity_request", Integer.class)).isZero();
            assertThat(BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES)).isZero();
            assertThat(historySnapshot(sql)).isEqualTo(historyBefore);
        }
    }

    @Test
    void realRestrictedOwnerCanMigrateValidateAndRepeatWithoutPrivilegeDrift() throws Exception {
        try (var postgres = postgres()) {
            var clusterAdmin = new JdbcTemplate(postgres.getPostgresDatabase());
            clusterAdmin.execute("create role " + RESTRICTED_OWNER
                    + " login inherit createrole nosuperuser nocreatedb noreplication nobypassrls");
            logins(clusterAdmin);
            clusterAdmin.execute("create database " + RESTRICTED_DATABASE + " owner " + RESTRICTED_OWNER);
            var databaseAdmin = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", RESTRICTED_DATABASE), "postgres", ""));
            // PG14's public schema starts with a cluster-owner privilege; provision the dedicated owner.
            databaseAdmin.execute("alter schema public owner to " + RESTRICTED_OWNER);

            var owner = new DriverManagerDataSource(
                    postgres.getJdbcUrl(RESTRICTED_OWNER, RESTRICTED_DATABASE), RESTRICTED_OWNER, "");
            var sql = new JdbcTemplate(owner);
            var roles = new BulkCapacityAuthorityMigrator.RoleConfiguration(
                    RESTRICTED_OWNER, PROVISIONER, ALLOCATOR, READER);
            assertThat(sql.queryForObject("select session_user", String.class)).isEqualTo(RESTRICTED_OWNER);
            assertThat(sql.queryForObject("select current_user", String.class)).isEqualTo(RESTRICTED_OWNER);
            assertThat(sql.queryForObject("""
                    select rolcanlogin and rolinherit and rolcreaterole and not rolsuper
                        and not rolcreatedb and not rolreplication and not rolbypassrls
                    from pg_catalog.pg_roles where rolname=?
                    """, Boolean.class, RESTRICTED_OWNER)).isTrue();
            assertThat(sql.queryForObject("""
                    select pg_catalog.pg_get_userbyid(datdba)
                    from pg_catalog.pg_database where datname=current_database()
                    """, String.class)).isEqualTo(RESTRICTED_OWNER);

            assertThat(BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, roles)).isEqualTo(1);
            BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, roles);
            assertThat(sql.queryForList("""
                    select n.nspname from pg_catalog.pg_namespace n
                    where n.nspname in ('public', 'praxis_bulk_capacity')
                        and pg_catalog.pg_get_userbyid(n.nspowner)=?
                    order by n.nspname
                    """, String.class, RESTRICTED_OWNER))
                    .containsExactly("praxis_bulk_capacity", "public");
            assertThat(sql.queryForObject("""
                    select pg_catalog.pg_get_userbyid(c.relowner) from pg_catalog.pg_class c
                    where c.oid=to_regclass(?)
                    """, String.class, HISTORY)).isEqualTo(RESTRICTED_OWNER);
            assertThat(sql.queryForObject("""
                    select count(*) from pg_catalog.pg_auth_members m
                    join pg_catalog.pg_roles r on r.oid=m.roleid
                    where r.rolname='praxis_bulk_capacity_definer'
                    """, Integer.class)).isZero();
            assertThat(sql.queryForObject("select has_schema_privilege(?, 'public', 'CREATE')",
                    Boolean.class, "praxis_bulk_capacity_definer")).isFalse();
            assertThat(sql.queryForObject("select has_schema_privilege(?, ?, 'CREATE')",
                    Boolean.class, "praxis_bulk_capacity_definer", SCHEMA)).isFalse();
            assertThat(sql.queryForObject("select has_database_privilege(?, current_database(), 'CREATE')",
                    Boolean.class, "praxis_bulk_capacity_definer")).isFalse();
            assertThat(sql.queryForObject("select has_database_privilege(?, current_database(), 'TEMPORARY')",
                    Boolean.class, "praxis_bulk_capacity_definer")).isFalse();
            assertThat(callerMemberships(sql)).isEqualTo(3);
            assertThat(sql.queryForList("""
                    select pg_catalog.pg_get_userbyid(a.grantee)
                    from pg_catalog.pg_database d,
                         lateral aclexplode(coalesce(d.datacl, acldefault('d',d.datdba))) a
                    where d.datname=current_database() and a.grantee<>d.datdba
                        and a.privilege_type='CONNECT' and not a.is_grantable
                    """, String.class)).containsExactlyInAnyOrder(PROVISIONER, ALLOCATOR, READER);
            assertThat(sql.queryForObject("""
                    select count(*) from pg_catalog.pg_database d,
                         lateral aclexplode(coalesce(d.datacl, acldefault('d',d.datdba))) a
                    where d.datname=current_database() and a.grantee=0
                    """, Integer.class)).isZero();
            for (String caller : List.of(PROVISIONER, ALLOCATOR, READER)) {
                var callerSql = new JdbcTemplate(new DriverManagerDataSource(
                        postgres.getJdbcUrl(caller, RESTRICTED_DATABASE), caller, ""));
                assertThat(callerSql.queryForObject("select session_user", String.class)).isEqualTo(caller);
                assertThatThrownBy(() -> callerSql.execute(
                        "delete from " + SCHEMA + ".capacity_request"))
                        .isInstanceOf(RuntimeException.class);
            }
            var readerSql = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(READER, RESTRICTED_DATABASE), READER, ""));
            assertThat(readerSql.queryForList("select * from " + SCHEMA + ".find_issue(?::uuid)",
                    UUID.randomUUID())).isEmpty();
            assertExactV1History(sql);
            var historyBefore = historySnapshot(sql);
            var aclBefore = sql.queryForObject("""
                    select datacl::text from pg_catalog.pg_database where datname=current_database()
                    """, String.class);
            var membershipBefore = callerMemberships(sql);

            assertThat(BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, roles)).isZero();
            BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, roles);
            assertThat(historySnapshot(sql)).isEqualTo(historyBefore);
            assertThat(sql.queryForObject("""
                    select datacl::text from pg_catalog.pg_database where datname=current_database()
                    """, String.class)).isEqualTo(aclBefore);
            assertThat(callerMemberships(sql)).isEqualTo(membershipBefore);
        }
    }

    @Test
    void wrongOwnerAndAmbientTransactionRejectBeforeChangingCatalog() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            var wrongOwner = new BulkCapacityAuthorityMigrator.RoleConfiguration(
                    "cap_wrong_owner", PROVISIONER, ALLOCATOR, READER);
            Integer rolesBefore = sql.queryForObject("select count(*) from pg_catalog.pg_roles", Integer.class);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, wrongOwner))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("select to_regnamespace('praxis_bulk_capacity') is null",
                    Boolean.class)).isTrue();
            assertThat(sql.queryForObject("select to_regclass(?) is null", Boolean.class, HISTORY)).isTrue();
            assertThat(sql.queryForObject("select count(*) from pg_catalog.pg_roles", Integer.class))
                    .isEqualTo(rolesBefore);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(owner));
            assertThatThrownBy(() -> transaction.execute(status ->
                    BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("select to_regnamespace('praxis_bulk_capacity') is null", Boolean.class))
                    .isTrue();
            assertThat(BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES)).isEqualTo(1);
            assertThatThrownBy(() -> transaction.execute(status -> {
                BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES);
                return null;
            })).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void unknownPreexistingCapacitySchemaIsNotBaselinedOrRepaired() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            sql.execute("create schema " + SCHEMA);
            sql.execute("create table " + SCHEMA + ".unmanaged (id integer primary key)");
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unmanaged");
            assertThat(sql.queryForObject("select to_regclass(?) is null", Boolean.class, HISTORY)).isTrue();
            assertThat(sql.queryForObject("select to_regclass(?) is not null", Boolean.class,
                    SCHEMA + ".unmanaged")).isTrue();
        }
    }

    @Test
    void identityUuidEpochAndMissingBucketFailClosedWithoutReEnrollment() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            var wrongUuid = new BulkCapacityAuthorityMigrator.Identity(IDENTITY.deploymentId(),
                    IDENTITY.environment(), UUID.randomUUID(), IDENTITY.expectedAuthorityEpoch());
            var wrongEpoch = new BulkCapacityAuthorityMigrator.Identity(IDENTITY.deploymentId(),
                    IDENTITY.environment(), IDENTITY.authorityId(), IDENTITY.expectedAuthorityEpoch() + 1);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, wrongUuid, ROLES))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, wrongEpoch, ROLES))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("select authority_epoch from " + SCHEMA + ".authority_identity",
                    Long.class)).isEqualTo(IDENTITY.expectedAuthorityEpoch());
            sql.update("delete from " + SCHEMA + ".deployment_capacity where deployment_id=?",
                    IDENTITY.deploymentId());
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".deployment_capacity",
                    Integer.class)).isZero();
        }
    }

    @Test
    void bootstrapFailuresRollBackAndEmptyV1CanRetryAfterRepair() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            // V1 is installed through its official isolated Flyway lane; one declared login is absent.
            sql.execute("create role " + PROVISIONER + " login");
            sql.execute("create role " + ALLOCATOR + " login");
            migrateV1Only(owner);
            assertExactV1History(sql);
            var historyBefore = historySnapshot(sql);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThat(historySnapshot(sql)).isEqualTo(historyBefore);
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".authority_identity",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".deployment_capacity",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".capacity_request",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("""
                    select pg_has_role(?, 'praxis_bulk_capacity_allocator', 'MEMBER')
                    """, Boolean.class, ALLOCATOR)).isFalse();
            sql.execute("create role " + READER + " login");
            assertThat(BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES)).isZero();
            BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES);
            assertExactV1History(sql);
            assertThat(historySnapshot(sql)).isEqualTo(historyBefore);
        }
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            migrateV1Only(owner);
            assertExactV1History(sql);
            var historyBefore = historySnapshot(sql);
            // The reader login must never inherit the allocator's function authority.
            sql.execute("grant praxis_bulk_capacity_allocator to " + READER);
            String database = sql.queryForObject("select current_database()", String.class);
            String aclBefore = sql.queryForObject("""
                    select d.datacl::text from pg_catalog.pg_database d where d.datname=?
                    """, String.class, database);
            Integer membershipsBefore = callerMemberships(sql);
            assertThat(membershipsBefore).isEqualTo(1);

            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThat(historySnapshot(sql)).isEqualTo(historyBefore);
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".authority_identity",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".deployment_capacity",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("""
                    select d.datacl::text from pg_catalog.pg_database d where d.datname=?
                    """, String.class, database)).isEqualTo(aclBefore);
            assertThat(callerMemberships(sql)).isEqualTo(membershipsBefore);
            assertThat(sql.queryForObject("""
                    select pg_has_role(?, 'praxis_bulk_capacity_provisioner', 'MEMBER')
                    """, Boolean.class, PROVISIONER)).isFalse();
            assertThat(sql.queryForObject("""
                    select pg_has_role(?, 'praxis_bulk_capacity_allocator', 'MEMBER')
                    """, Boolean.class, ALLOCATOR)).isFalse();
            assertThat(sql.queryForObject("""
                    select pg_has_role(?, 'praxis_bulk_capacity_reader', 'MEMBER')
                    """, Boolean.class, READER)).isFalse();
            assertExactV1History(sql);

            sql.execute("revoke praxis_bulk_capacity_allocator from " + READER);
            assertThat(callerMemberships(sql)).isZero();
            assertThat(BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES)).isZero();
            BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES);
            assertThat(historySnapshot(sql)).isEqualTo(historyBefore);
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".authority_identity",
                    Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void v1OnlyCatalogWithBusinessRowsCannotManufactureMissingAuthorityIdentity() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            migrateV1Only(owner);
            // Privileged test corruption simulates a torn, already-used issuer catalog;
            // runtime and issuer logins never receive this bypass.
            try (Connection connection = owner.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                try {
                    statement.execute("insert into " + SCHEMA
                            + ".deployment_capacity(deployment_id) values ('deployment-east')");
                } finally {
                    statement.execute("set session_replication_role = origin");
                }
            }
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".authority_identity",
                    Integer.class)).isZero();
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Partially used");
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".authority_identity",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".deployment_capacity",
                    Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void structuralDriftIsRejectedWithoutRepairingConstraintOrUnexpectedTable() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            String constraint = sql.queryForObject("""
                    select c.conname from pg_catalog.pg_constraint c
                    where c.conrelid = to_regclass('praxis_bulk_capacity.capacity_token')
                      and c.contype = 'f' and pg_catalog.pg_get_constraintdef(c.oid) like '%capacity_request%'
                    order by c.conname limit 1
                    """, String.class);
            assertThat(constraint).isNotBlank();
            sql.execute("alter table " + SCHEMA + ".capacity_token drop constraint " + constraint);
            // Privileged drift rewrites the stamp with the SAME V1 catalog calculation.
            // Equality between two database-owned values cannot certify the missing FK.
            String forged = liveCatalogManifest(sql);
            sql.execute("comment on schema " + SCHEMA + " is '" + forged.replace("'", "''") + "'");
            assertThat(sql.queryForObject("""
                    select pg_catalog.obj_description(to_regnamespace('praxis_bulk_capacity'), 'pg_namespace')
                    """, String.class)).isEqualTo(forged);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("""
                    select count(*) from pg_catalog.pg_constraint c
                    where c.conrelid = to_regclass('praxis_bulk_capacity.capacity_token')
                      and c.conname = ?
                    """, Integer.class, constraint)).isZero();
        }
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            sql.execute("create table " + SCHEMA + ".unexpected_capacity (id integer)");
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("select to_regclass('praxis_bulk_capacity.unexpected_capacity') is not null",
                    Boolean.class)).isTrue();
        }
    }

    @Test
    void functionBodyAndPublicExecuteDriftFailEvenWithValidFlywayChecksum() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            sql.execute("grant execute on function " + SCHEMA + ".find_issue(uuid) to public");
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("""
                    select count(*) from pg_catalog.pg_proc p,
                         lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                    where p.oid = to_regprocedure('praxis_bulk_capacity.find_issue(uuid)')
                      and a.grantee = 0
                    """, Integer.class)).isPositive();
        }
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            assertExactV1History(sql);
            var historyBefore = historySnapshot(sql);
            sql.execute("""
                    create or replace function praxis_bulk_capacity.find_issue(p_request uuid)
                    returns table(found boolean, deployment_id text, tenant_id text, binding_id text,
                        capacity_class text, requested_count integer, issued_count integer,
                        payload_digest text, token_ids uuid[])
                    language sql stable security definer set search_path = pg_catalog, pg_temp as $$
                        select false, null::text, null::text, null::text, null::text,
                               null::integer, null::integer, null::text, array[]::uuid[]
                    $$
                    """);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThat(historySnapshot(sql)).isEqualTo(historyBefore);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"check", "not-valid-check", "partial-index", "extra-column",
            "column-type", "column-default", "not-null", "pk-to-unique", "extra-function", "function-result",
            "extra-trigger", "function-owner", "function-search-path", "function-volatility",
            "function-strict"})
    void physicalCatalogShapeAndFunctionAuthorityDriftRejectWithoutHealing(String drift) throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            assertExactV1History(sql);
            var historyBefore = historySnapshot(sql);
            switch (drift) {
                case "check" -> {
                    String check = sql.queryForObject("""
                            select c.conname from pg_catalog.pg_constraint c
                            where c.conrelid = to_regclass('praxis_bulk_capacity.capacity_request')
                              and c.contype = 'c'
                              and pg_catalog.pg_get_constraintdef(c.oid) like '%capacity_class%'
                            order by c.conname limit 1
                            """, String.class);
                    assertThat(check).isNotBlank();
                    sql.execute("alter table " + SCHEMA + ".capacity_request drop constraint " + check);
                }
                case "partial-index" ->
                        sql.execute("drop index " + SCHEMA + ".capacity_one_pending_per_binding_class");
                case "extra-column" ->
                        sql.execute("alter table " + SCHEMA + ".capacity_token add column rogue text");
                case "column-type" -> {
                    sql.execute("alter table " + SCHEMA
                            + ".capacity_request alter column payload_digest type varchar(200)");
                    assertThat(sql.queryForObject("""
                            select character_maximum_length from information_schema.columns
                            where table_schema='praxis_bulk_capacity'
                              and table_name='capacity_request' and column_name='payload_digest'
                            """, Integer.class)).isEqualTo(200);
                }
                case "column-default" ->
                        sql.execute("alter table " + SCHEMA
                                + ".capacity_request alter column requested_at drop default");
                case "not-null" ->
                        sql.execute("alter table " + SCHEMA
                                + ".capacity_binding alter column environment drop not null");
                case "pk-to-unique" -> {
                    sql.execute("alter table " + SCHEMA + ".fairness_cursor drop constraint fairness_cursor_pkey");
                    sql.execute("alter table " + SCHEMA
                            + ".fairness_cursor add constraint fairness_cursor_pkey unique "
                            + "(deployment_id, capacity_class)");
                    assertThat(sql.queryForObject("""
                            select c.contype from pg_catalog.pg_constraint c
                            where c.conrelid = to_regclass('praxis_bulk_capacity.fairness_cursor')
                              and c.conname = 'fairness_cursor_pkey'
                            """, String.class)).isEqualTo("u");
                }
                case "not-valid-check" -> {
                    String check = sql.queryForObject("""
                            select c.conname from pg_catalog.pg_constraint c
                            where c.conrelid = to_regclass('praxis_bulk_capacity.capacity_request')
                              and c.contype = 'c'
                              and pg_catalog.pg_get_constraintdef(c.oid) like '%requested_count > 0%'
                            order by c.conname limit 1
                            """, String.class);
                    assertThat(check).isNotBlank();
                    String definition = sql.queryForObject("""
                            select pg_catalog.pg_get_constraintdef(c.oid)
                            from pg_catalog.pg_constraint c
                            where c.conrelid = to_regclass('praxis_bulk_capacity.capacity_request')
                              and c.conname = ?
                            """, String.class, check);
                    sql.execute("alter table " + SCHEMA + ".capacity_request drop constraint " + check);
                    sql.execute("alter table " + SCHEMA + ".capacity_request add constraint "
                            + check + " " + definition + " not valid");
                    assertThat(sql.queryForObject("""
                            select c.convalidated from pg_catalog.pg_constraint c
                            where c.conrelid = to_regclass('praxis_bulk_capacity.capacity_request')
                              and c.conname = ?
                            """, Boolean.class, check)).isFalse();
                }
                case "extra-function" ->
                        sql.execute("create function " + SCHEMA
                                + ".find_issue(text) returns integer language sql as $$ select 1 $$");
                case "function-result" -> {
                    sql.execute("drop function " + SCHEMA + ".find_issue(uuid)");
                    sql.execute("create function " + SCHEMA
                            + ".find_issue(uuid) returns integer language sql security definer "
                            + "set search_path = pg_catalog, pg_temp as $$ select 1 $$");
                    assertThat(sql.queryForObject("""
                            select pg_catalog.pg_get_function_result(
                                to_regprocedure('praxis_bulk_capacity.find_issue(uuid)'))
                            """, String.class)).isEqualTo("integer");
                }
                case "extra-trigger" -> {
                    sql.execute("""
                            create function public.capacity_rogue_trigger() returns trigger
                            language plpgsql as $$ begin return new; end $$
                            """);
                    sql.execute("create trigger capacity_rogue before insert on " + SCHEMA
                            + ".capacity_request for each row execute function public.capacity_rogue_trigger()");
                }
                case "function-owner" ->
                        sql.execute("alter function " + SCHEMA + ".find_issue(uuid) owner to postgres");
                case "function-search-path" ->
                        sql.execute("alter function " + SCHEMA + ".find_issue(uuid) set search_path = public");
                case "function-volatility" ->
                        sql.execute("alter function " + SCHEMA + ".find_issue(uuid) volatile");
                case "function-strict" ->
                        sql.execute("alter function " + SCHEMA + ".find_issue(uuid) strict");
                default -> throw new AssertionError(drift);
            }
            String forged = liveCatalogManifest(sql);
            sql.execute("comment on schema " + SCHEMA + " is '" + forged.replace("'", "''") + "'");
            assertThat(sql.queryForObject("""
                    select pg_catalog.obj_description(to_regnamespace('praxis_bulk_capacity'), 'pg_namespace')
                    """, String.class)).isEqualTo(forged);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .as("physical drift %s must fail despite V1 history", drift)
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .as("repeat migration must not heal %s", drift)
                    .isInstanceOf(RuntimeException.class);
            assertThat(historySnapshot(sql)).isEqualTo(historyBefore);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"column-dml", "schema-create", "unexpected-role", "nested-membership",
            "database-public-connect", "rogue-database-connect"})
    void unexpectedPrivilegeAndMembershipDriftRejectWithoutGrantRepair(String drift) throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            switch (drift) {
                case "column-dml" -> sql.execute("grant update (authority_epoch) on "
                        + SCHEMA + ".authority_identity to " + ALLOCATOR);
                case "schema-create" -> sql.execute("grant create on schema " + SCHEMA + " to " + READER);
                case "unexpected-role" -> {
                    sql.execute("create role cap_rogue_test login");
                    sql.execute("grant usage on schema " + SCHEMA + " to cap_rogue_test");
                }
                case "nested-membership" -> sql.execute("grant " + ALLOCATOR + " to " + READER);
                case "database-public-connect" -> {
                    String database = sql.queryForObject("select current_database()", String.class);
                    sql.execute("grant connect on database \"" + database + "\" to public");
                }
                case "rogue-database-connect" -> {
                    String database = sql.queryForObject("select current_database()", String.class);
                    sql.execute("create role cap_rogue_connect login");
                    sql.execute("grant connect on database \"" + database + "\" to cap_rogue_connect");
                    assertThat(sql.queryForObject("select has_database_privilege(?, ?, 'CONNECT')",
                            Boolean.class, "cap_rogue_connect", database)).isTrue();
                }
                default -> throw new AssertionError(drift);
            }
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .as("privilege drift %s must fail", drift)
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .as("repeat migration must not heal %s", drift)
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void restrictedLoginsCannotWriteCatalogAndRoleDriftIsNotHealed() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            var allocator = login(postgres, ALLOCATOR);
            var reader = login(postgres, READER);
            var allocateSql = new JdbcTemplate(allocator);
            var readerSql = new JdbcTemplate(reader);
            assertThat(allocateSql.queryForObject("select session_user", String.class)).isEqualTo(ALLOCATOR);
            assertThat(readerSql.queryForObject("select session_user", String.class)).isEqualTo(READER);
            assertThat(sql.queryForObject("select session_user", String.class)).isEqualTo(ROLES.ownerLogin());
            assertThatThrownBy(() -> allocateSql.update(
                    "insert into " + SCHEMA + ".deployment_capacity(deployment_id) values ('rogue')"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> readerSql.update(
                    "delete from " + SCHEMA + ".authority_identity"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> allocateSql.execute("truncate " + SCHEMA + ".capacity_token"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> readerSql.execute("create table " + SCHEMA + ".rogue (id int)"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> allocateSql.execute("create table public.cap_rogue (id int)"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> allocateSql.execute("create schema cap_rogue_schema"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> allocateSql.execute("create temporary table cap_rogue_temp (id int)"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(readerSql.queryForList("select * from " + SCHEMA + ".find_issue(?::uuid)",
                    UUID.randomUUID())).isEmpty();
            assertThat(sql.queryForObject("select count(*) from " + SCHEMA + ".capacity_token",
                    Integer.class)).isZero();
            sql.execute("revoke praxis_bulk_capacity_allocator from " + ALLOCATOR);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject(
                    "select pg_has_role('" + ALLOCATOR + "', 'praxis_bulk_capacity_allocator', 'MEMBER')",
                    Boolean.class)).isFalse();
        }
    }

    @Test
    void fixedGroupMayHaveOtherDeploymentMemberOnlyWhileItLacksDatabaseConnect() throws Exception {
        try (var postgres = postgres()) {
            var owner = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(owner);
            logins(sql);
            BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES);
            sql.execute("create role cap_other_deployment login");
            sql.execute("grant praxis_bulk_capacity_allocator to cap_other_deployment");
            String database = sql.queryForObject("select current_database()", String.class);
            assertThat(sql.queryForObject("select has_database_privilege(?, ?, 'CONNECT')",
                    Boolean.class, "cap_other_deployment", database)).isFalse();
            BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES);

            sql.execute("grant connect on database \"" + database + "\" to cap_other_deployment");
            assertThat(sql.queryForObject("select has_database_privilege(?, ?, 'CONNECT')",
                    Boolean.class, "cap_other_deployment", database)).isTrue();
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.validate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.migrate(owner, IDENTITY, ROLES))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private static EmbeddedPostgres postgres() throws Exception {
        return EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
    }

    private static void logins(JdbcTemplate owner) {
        for (String login : List.of(PROVISIONER, ALLOCATOR, READER))
            owner.execute("create role " + login + " login");
    }

    private static DataSource login(EmbeddedPostgres postgres, String role) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(role, "postgres"), role, "");
    }

    private static void assertExactV1History(JdbcTemplate sql) {
        // Flyway records schema creation separately from the sole SQL migration.
        assertThat(sql.queryForList("select type from " + HISTORY + " order by installed_rank", String.class))
                .containsExactly("SCHEMA", "SQL");
        assertThat(sql.queryForList("select version from " + HISTORY + " order by installed_rank", String.class))
                .containsExactly(null, "1");
        assertThat(sql.queryForList("select version from " + HISTORY
                + " where type='SQL' and success order by installed_rank", String.class))
                .containsExactly("1");
        assertThat(sql.queryForObject("select count(*) from " + HISTORY + " where success", Integer.class))
                .isEqualTo(2);
    }

    private static List<Map<String, Object>> historySnapshot(JdbcTemplate sql) {
        return sql.queryForList("select * from " + HISTORY + " order by installed_rank");
    }

    private static void migrateV1Only(DataSource owner) {
        Flyway.configure().dataSource(owner)
                .locations("classpath:db/praxis-bulk-capacity-migrations")
                .schemas(SCHEMA).defaultSchema(SCHEMA)
                .table("praxis_bulk_capacity_schema_history")
                .createSchemas(true).baselineOnMigrate(false).cleanDisabled(true)
                .validateOnMigrate(true).load().migrate();
    }

    private static Integer callerMemberships(JdbcTemplate owner) {
        return owner.queryForObject("""
                select count(*) from pg_catalog.pg_auth_members m
                join pg_catalog.pg_roles group_role on group_role.oid=m.roleid
                join pg_catalog.pg_roles member_role on member_role.oid=m.member
                where group_role.rolname in ('praxis_bulk_capacity_provisioner',
                                             'praxis_bulk_capacity_allocator',
                                             'praxis_bulk_capacity_reader')
                  and member_role.rolname in (?, ?, ?)
                """, Integer.class, PROVISIONER, ALLOCATOR, READER);
    }

    /** Reuses the exact V1 calculation only to forge its mutable database comment. */
    private static String liveCatalogManifest(JdbcTemplate owner) throws Exception {
        Method query = BulkCapacityAuthorityMigrator.class.getDeclaredMethod("catalogQuery");
        query.setAccessible(true);
        return owner.queryForObject((String) query.invoke(null), String.class);
    }
}
