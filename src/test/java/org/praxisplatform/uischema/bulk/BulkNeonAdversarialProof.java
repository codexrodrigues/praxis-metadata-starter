package org.praxisplatform.uischema.bulk;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test-only adversarial catalog checks; every DDL/ACL mutation is rolled back, never healed. */
final class BulkNeonAdversarialProof {
    private BulkNeonAdversarialProof() { }

    static void catalogs(DataSource owner, BulkExecutionRoleConfiguration roles, String runtime) throws Exception {
        String suffix = runtime.substring("bulk_optional_runtime_".length());
        String bridge = "bulk_proof_bridge_" + suffix, leaf = "bulk_proof_leaf_" + suffix;
        String function = "praxis_bulk.guard_capacity_slot()";
        negative(owner, "D-extra-relation", c -> {
            execute(c, "create table praxis_bulk.proof_extra_acl(id integer)",
                    "grant select on praxis_bulk.proof_extra_acl to praxis_bulk_capacity_owner");
            assertThat(BulkCatalogPhase.captureRoles(c, roles).allPrivileges("praxis_bulk_capacity_owner"))
                    .containsKey("proof_extra_acl");
        }, c -> BulkCapacityOccupancyCatalog.validateAccess(c, roles, BulkMigrationLineage.select(c)), "V19 owner table grants differ");
        negative(owner, "D-function-PUBLIC", c -> execute(c, "grant execute on function " + function + " to public"),
                c -> BulkCapacityOccupancyCatalog.validateAccess(c, roles, BulkMigrationLineage.select(c)), "V19 function ACL differs: guard_capacity_slot");
        negative(owner, "D-function-grant-option", c -> execute(c, "grant execute on function " + function + " to " + runtime + " with grant option"),
                c -> BulkCapacityOccupancyCatalog.validateAccess(c, roles, BulkMigrationLineage.select(c)), "V19 grant option forbidden");
        negative(owner, "D-indirect-membership", c -> {
            execute(c, "create role " + bridge + " nologin noinherit", "create role " + leaf + " nologin noinherit",
                    "grant " + runtime + " to " + bridge + " with admin false, inherit true, set true",
                    "grant " + bridge + " to " + leaf + " with admin false, inherit true, set true");
            // Proves recursive capture actually reached the second hop; rejection alone could stop at the first.
            assertThat(BulkCatalogPhase.captureRoles(c, roles).members(Set.of(runtime), "unexpected admin"))
                    .contains(bridge, leaf);
        }, c -> invoke("validateV5RolesAndPrivileges", c, roles), "runtime role membership introduces an unconfigured grantee");
        negative(owner, "D-admin-option", c -> {
            execute(c, "create role " + bridge + " nologin noinherit",
                    "grant " + runtime + " to " + bridge + " with admin true, inherit false, set false");
            assertThat(BulkCatalogPhase.captureRoles(c, roles).facts("member"))
                    .anyMatch(f -> runtime.equals(f.text("root")) && bridge.equals(f.text("child")) && f.flag("admin"));
        }, c -> invoke("validateV5RolesAndPrivileges", c, roles), "configured role memberships cannot have ADMIN OPTION");
        negative(owner, "E-default", c -> execute(c,
                "alter table praxis_bulk.praxis_bulk_execution alter column next_ordinal set default 0"),
                c -> invoke("validateDurableExecution", c), "durable storage defaults, generation, persistence or row security differ");
        negative(owner, "E-same-name-constraint", c -> execute(c,
                "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_epoch_check",
                "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_epoch_check check(owner_epoch>=0)"),
                c -> invoke("validateDurableExecution", c), "durable storage constraints differ");
        negative(owner, "E-index-flag", c -> {
            execute(c, "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_proposal_key",
                    "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_proposal_key unique(proposal_id) deferrable initially deferred");
            try (var s = c.createStatement(); var r = s.executeQuery("select indimmediate from pg_catalog.pg_index where indexrelid='praxis_bulk.praxis_bulk_execution_proposal_key'::regclass")) {
                assertThat(r.next()).isTrue(); assertThat(r.getBoolean(1)).isFalse(); assertThat(r.next()).isFalse();
            }
        }, c -> invoke("validateDurableExecution", c), "durable constraint must be immediate and validated");
        negative(owner, "E-function-body", c -> execute(c, "create or replace function " + function
                + " returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$begin return new; end;$$"),
                c -> BulkCapacityOccupancyCatalog.validateAccess(c, roles, BulkMigrationLineage.select(c)), "V19 function attributes/source differ: guard_capacity_slot");
        negative(owner, "E-function-search-path", c -> execute(c, "alter function " + function + " set search_path=public"),
                c -> BulkCapacityOccupancyCatalog.validateAccess(c, roles, BulkMigrationLineage.select(c)), "V19 function attributes/source differ: guard_capacity_slot");
        negative(owner, "E-function-owner", c -> execute(c, "create role " + bridge + " nologin noinherit",
                "grant " + bridge + " to " + roles.expectedSchemaOwnerRole() + " with inherit false, set true",
                "grant create on schema praxis_bulk to " + bridge, "alter function " + function + " owner to " + bridge),
                c -> BulkCapacityOccupancyCatalog.validateAccess(c, roles, BulkMigrationLineage.select(c)), "V19 function attributes/source differ: guard_capacity_slot");
    }

    @FunctionalInterface interface Check { void run(Connection c) throws Exception; }
    private static void negative(DataSource owner, String label, Check mutate, Check validate, String message) throws Exception {
        var before = photograph(owner);
        try (var c = owner.getConnection()) {
            c.setAutoCommit(false);
            try {
                execute(c, "set local search_path=pg_catalog");
                mutate.run(c);
                assertThatThrownBy(() -> validate.run(c)).isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
            } finally { c.rollback(); }
        }
        assertThat(photograph(owner)).as(label + " exact fixture rollback").isEqualTo(before);
        System.out.println("NEON_PROOF_" + label + "=rejected,catalog-restored-by-rollback");
    }

    /** Structural/catalog identity only; operational rows are checked independently by the caller. */
    private static List<String> photograph(DataSource owner) {
        return new JdbcTemplate(owner).queryForList("""
                select kind||':'||body from (
                  select 'class' kind,c.oid::text||':'||c::text body from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk'
                  union all select 'constraint',c.oid::text||':'||c::text from pg_catalog.pg_constraint c join pg_catalog.pg_namespace n on n.oid=c.connamespace where n.nspname='praxis_bulk'
                  union all select 'function',p.oid::text||':'||p::text from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace where n.nspname='praxis_bulk'
                  union all select 'default',d.oid::text||':'||d::text from pg_catalog.pg_attrdef d join pg_catalog.pg_class c on c.oid=d.adrelid join pg_catalog.pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk'
                  union all select 'namespace',n.oid::text||':'||n::text from pg_catalog.pg_namespace n where n.nspname='praxis_bulk'
                  union all select 'role',r.oid::text||':'||r::text from pg_catalog.pg_roles r
                  union all select 'membership',m.oid::text||':'||m::text from pg_catalog.pg_auth_members m
                ) facts order by kind,body
                """, String.class);
    }

    private static void invoke(String name, Connection c, BulkExecutionRoleConfiguration... roles) throws Exception {
        var method = roles.length == 0 ? BulkExecutionMigrator.class.getDeclaredMethod(name, Connection.class)
                : BulkExecutionMigrator.class.getDeclaredMethod(name, Connection.class, BulkExecutionRoleConfiguration.class);
        method.setAccessible(true);
        try { if (roles.length == 0) method.invoke(null, c); else method.invoke(null, c, roles[0]); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    private static void execute(Connection c, String... sql) throws Exception {
        try (var s = c.createStatement()) { for (String value : sql) s.execute(value); }
    }
}
