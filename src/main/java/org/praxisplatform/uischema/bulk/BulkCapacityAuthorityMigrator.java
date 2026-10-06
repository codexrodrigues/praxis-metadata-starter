package org.praxisplatform.uischema.bulk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit, independent migration for the deployment-wide capacity-right issuer.
 * It never installs local job storage and is not discovered by the host's default Flyway.
 */
public final class BulkCapacityAuthorityMigrator {
    public static final String SCHEMA = "praxis_bulk_capacity";
    public static final String HISTORY_TABLE = "praxis_bulk_capacity_schema_history";
    static final String PROVISIONER_ROLE = "praxis_bulk_capacity_provisioner";
    static final String ALLOCATOR_ROLE = "praxis_bulk_capacity_allocator";
    static final String READER_ROLE = "praxis_bulk_capacity_reader";
    private static final String DEFINER_ROLE = "praxis_bulk_capacity_definer";
    private static final String V1_RESOURCE = "db/praxis-bulk-capacity-migrations/V1__capacity_authority.sql";
    private static final String RESOURCE = "db/praxis-bulk-capacity-migrations/V2__capacity_binding_attestation.sql";
    private static final Set<String> TABLES = Set.of("authority_identity", "deployment_capacity",
            "tenant_capacity", "capacity_binding", "capacity_request", "capacity_token", "fairness_cursor", "binding_attestation");
    // Independent source manifest. The live schema comment is only an additional exact drift witness.
    private static final Map<String, String> COLUMNS = Map.of(
            "authority_identity", "deployment_id:text,environment:text,authority_id:uuid,authority_epoch:bigint",
            "deployment_capacity", "deployment_id:text",
            "tenant_capacity", "deployment_id:text,tenant_id:text",
            "capacity_binding", "binding_id:text,deployment_id:text,tenant_id:text,environment:text,binding_generation:bigint",
            "capacity_request", "request_id:uuid,deployment_id:text,tenant_id:text,binding_id:text,"
                    + "capacity_class:text,requested_count:integer,issued_count:integer,payload_digest:text,"
                    + "state:text,requested_at:timestamp with time zone",
            "capacity_token", "token_id:uuid,request_id:uuid,token_ordinal:integer,deployment_id:text,"
                    + "tenant_id:text,binding_id:text,capacity_class:text,binding_generation:bigint,"
                    + "state:text,issued_at:timestamp with time zone",
            "fairness_cursor", "deployment_id:text,capacity_class:text,last_tenant_id:text,cursor_epoch:bigint",
            "binding_attestation", "attestation_id:uuid,database_id:uuid,binding_id:text,"
                    + "deployment_id:text,tenant_id:text,environment:text,binding_generation:bigint,"
                    + "authority_id:uuid,authority_epoch:bigint");
    private static final Map<String, String> FUNCTIONS = Map.of(
            "enroll_binding", "text,text,text,bigint",
            "request_capacity", "uuid,text,text,text,text,integer,text",
            "allocate_next", "text,text",
            "find_issue", "uuid",
            "assert_authority_identity", "text,text,uuid,bigint",
            "register_attestation", "text,text,text,text,bigint,uuid,uuid",
            "read_attestation", "uuid", "read_issued_token", "uuid");
    private static final Pattern BODY = Pattern.compile(
            "create function praxis_bulk_capacity\\.([a-z_]+)\\s*\\([^;]*?as \\$\\$(.*?)\\$\\$;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private BulkCapacityAuthorityMigrator() { }

    /** The expected epoch comes from trusted deployment provisioning, outside this database. */
    public record Identity(String deploymentId, String environment, UUID authorityId, long expectedAuthorityEpoch) {
        public Identity {
            deploymentId = canonical(deploymentId);
            environment = canonical(environment);
            Objects.requireNonNull(authorityId, "authorityId");
            if (expectedAuthorityEpoch <= 0) throw new IllegalArgumentException("Authority epoch must be positive");
        }
    }

    /** Explicit owner and per-deployment login principals; group roles remain NOLOGIN. */
    public record RoleConfiguration(String ownerLogin, String provisionerLogin,
                                    String allocatorLogin, String readerLogin) {
        public RoleConfiguration {
            ownerLogin = canonical(ownerLogin);
            provisionerLogin = canonical(provisionerLogin);
            allocatorLogin = canonical(allocatorLogin);
            readerLogin = canonical(readerLogin);
            if (Set.of(ownerLogin, provisionerLogin, allocatorLogin, readerLogin).size() != 4)
                throw new IllegalArgumentException("Capacity authority login roles must be distinct");
        }
    }

    /**
     * Applies V1 only on a dedicated authority database. A pre-existing unknown schema/history
     * fails closed; a repeat invocation validates rather than repairing catalog drift.
     */
    public static int migrate(DataSource owner, Identity identity, RoleConfiguration roles) {
        outsideTransaction();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(roles, "roles");
        if (!roles.ownerLogin().equals(new JdbcTemplate(owner).queryForObject(
                "select current_user", String.class)))
            throw new IllegalStateException("Capacity migrator owner login changed");
        assertDedicatedDatabase(owner);
        var jdbc = new JdbcTemplate(owner);
        Boolean historyExists = jdbc.queryForObject(
                "select to_regclass('praxis_bulk_capacity.praxis_bulk_capacity_schema_history') is not null",
                Boolean.class);
        if (Boolean.TRUE.equals(historyExists)) {
            Integer unknownVersions = jdbc.queryForObject("""
                    select count(*) from praxis_bulk_capacity.praxis_bulk_capacity_schema_history
                    where version is not null and
                          case when version ~ '^[0-9]+$' then version::integer not in (1,2)
                               else true end
                    """, Integer.class);
            if (unknownVersions == null || unknownVersions != 0)
                throw new IllegalStateException("Unsupported capacity authority history version");
            Integer version = jdbc.queryForObject("""
                    select max(version::integer) from praxis_bulk_capacity.praxis_bulk_capacity_schema_history
                    where success and version ~ '^[0-9]+$'
                    """, Integer.class);
            if (Integer.valueOf(1).equals(version)) {
                // Targeting only the exact published V1 avoids treating the legitimate pending V2
                // as drift, while Flyway still verifies the applied V1 checksum and description.
                flyway(owner, org.flywaydb.core.api.MigrationVersion.fromVersion("1")).validate();
                try (Connection connection = owner.getConnection()) {
                    assertV1LiveCatalog(connection, roles.ownerLogin());
                    preflightV1RowsAndCallers(connection, identity, roles);
                } catch (SQLException failure) {
                    throw new IllegalStateException("Capacity authority V1 preflight unavailable", failure);
                }
            } else {
                flyway(owner).validate();
            }
        }
        int applied = flyway(owner).migrate().migrationsExecuted;
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(owner));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        transaction.execute(status -> {
            jdbc.execute("lock table praxis_bulk_capacity.authority_identity in access exclusive mode");
            // A V1-only crash may be completed, but no partially used issuer may be repaired.
            Integer identities = jdbc.queryForObject(
                    "select count(*) from praxis_bulk_capacity.authority_identity", Integer.class);
            if (identities == null || identities > 1)
                throw new IllegalStateException("Capacity authority bootstrap is ambiguous");
            if (identities == 0) {
                for (String table : Set.of("deployment_capacity", "tenant_capacity", "capacity_binding",
                        "capacity_request", "capacity_token", "fairness_cursor")) {
                    Integer existing = jdbc.queryForObject(
                            "select count(*) from praxis_bulk_capacity." + table, Integer.class);
                    if (existing == null || existing != 0)
                        throw new IllegalStateException("Partially used capacity authority cannot bootstrap");
                }
                try {
                    assertLiveCatalog(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(owner),
                            roles.ownerLogin());
                } catch (SQLException failure) {
                    throw new IllegalStateException("Capacity authority catalog unavailable", failure);
                }
                jdbc.update("""
                        insert into praxis_bulk_capacity.authority_identity
                            (deployment_id, environment, authority_id, authority_epoch)
                        values (?, ?, ?, ?)
                        """, identity.deploymentId(), identity.environment(), identity.authorityId(),
                        identity.expectedAuthorityEpoch());
                jdbc.update("insert into praxis_bulk_capacity.deployment_capacity(deployment_id) values (?)",
                        identity.deploymentId());
                configureCallers(jdbc, roles);
                try {
                    assertCallers(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(owner), roles);
                } catch (SQLException failure) {
                    throw new IllegalStateException("Capacity caller catalog unavailable", failure);
                }
            }
            return null;
        });
        validate(owner, identity, roles);
        return applied;
    }

    /** Validates Flyway history, protected functions, owner and grant boundaries on every startup. */
    public static void validate(DataSource owner, Identity identity, RoleConfiguration roles) {
        outsideTransaction();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(roles, "roles");
        flyway(owner).validate();
        assertDedicatedDatabase(owner);
        var jdbc = new JdbcTemplate(owner);
        String current = jdbc.queryForObject("select current_user", String.class);
        if (!roles.ownerLogin().equals(current))
            throw new IllegalStateException("Capacity migrator owner login changed");
        try (Connection connection = owner.getConnection()) {
            assertLiveCatalog(connection, roles.ownerLogin());
            assertCallers(connection, roles);
        } catch (SQLException failure) {
            throw new IllegalStateException("Capacity authority catalog unavailable", failure);
        }
        var actual = jdbc.queryForMap("""
                select deployment_id, environment, authority_id, authority_epoch
                from praxis_bulk_capacity.authority_identity
                """);
        if (actual.size() != 4
                || !identity.deploymentId().equals(actual.get("deployment_id"))
                || !identity.environment().equals(actual.get("environment"))
                || !identity.authorityId().equals(actual.get("authority_id"))
                || !Long.valueOf(identity.expectedAuthorityEpoch()).equals(actual.get("authority_epoch"))) {
            throw new IllegalStateException("Capacity authority identity or expected epoch changed");
        }
        Integer rowCount = jdbc.queryForObject(
                "select count(*) from praxis_bulk_capacity.authority_identity", Integer.class);
        if (rowCount == null || rowCount != 1)
            throw new IllegalStateException("Capacity authority must contain exactly one deployment");
        Integer deploymentRows = jdbc.queryForObject(
                "select count(*) from praxis_bulk_capacity.deployment_capacity where deployment_id=?",
                Integer.class, identity.deploymentId());
        if (deploymentRows == null || deploymentRows != 1)
            throw new IllegalStateException("Capacity deployment bucket is missing");
        Integer unexpected = jdbc.queryForObject("""
                select count(*) from pg_catalog.pg_class c
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relkind in ('r','p')
                  and c.relname not in ('authority_identity','deployment_capacity',
                      'tenant_capacity','capacity_binding','capacity_request','capacity_token',
                      'fairness_cursor','binding_attestation','praxis_bulk_capacity_schema_history')
                """, Integer.class);
        if (unexpected == null || unexpected != 0)
            throw new IllegalStateException("Unexpected capacity authority table");
        for (String table : TABLES) {
            var row = jdbc.queryForMap("""
                    select pg_catalog.pg_get_userbyid(c.relowner) as owner,
                           c.relrowsecurity as rls,
                           c.relkind as kind
                    from pg_catalog.pg_class c
                    where c.oid=to_regclass(?)
                    """, SCHEMA + "." + table);
            if (!current.equals(row.get("owner")) || Boolean.TRUE.equals(row.get("rls"))
                    || !"r".equals(row.get("kind")))
                throw new IllegalStateException("Capacity authority table catalog changed");
            for (String role : Set.of(PROVISIONER_ROLE, ALLOCATOR_ROLE, READER_ROLE)) {
                Boolean dml = jdbc.queryForObject(
                        "select has_table_privilege(?, ?, 'INSERT') or has_table_privilege(?, ?, 'UPDATE') "
                                + "or has_table_privilege(?, ?, 'DELETE')",
                        Boolean.class, role, SCHEMA + "." + table, role, SCHEMA + "." + table,
                        role, SCHEMA + "." + table);
                if (Boolean.TRUE.equals(dml))
                    throw new IllegalStateException("Capacity authority direct DML grant changed");
            }
        }
        Map<String, String> expectedBodies = expectedBodies();
        for (var entry : FUNCTIONS.entrySet()) {
            String signature = SCHEMA + "." + entry.getKey() + "(" + entry.getValue() + ")";
            var definition = jdbc.queryForMap("""
                    select p.prosrc as body, p.prosecdef as definer, p.proconfig::text as config,
                           pg_catalog.pg_get_userbyid(p.proowner) as owner
                    from pg_catalog.pg_proc p where p.oid=to_regprocedure(?)
                    """, signature);
            if (!expectedBodies.get(entry.getKey()).strip().equals(((String) definition.get("body")).strip())
                    || !Boolean.TRUE.equals(definition.get("definer"))
                    || !DEFINER_ROLE.equals(definition.get("owner"))
                    || !String.valueOf(definition.get("config")).contains("search_path=pg_catalog, pg_temp"))
                throw new IllegalStateException("Capacity authority function catalog changed");
        }
        validateRole(jdbc, DEFINER_ROLE);
        validateRole(jdbc, PROVISIONER_ROLE);
        validateRole(jdbc, ALLOCATOR_ROLE);
        validateRole(jdbc, READER_ROLE);
        for (String name : FUNCTIONS.keySet()) {
            String signature = SCHEMA + "." + name + "(" + FUNCTIONS.get(name) + ")";
            for (String role : Set.of(PROVISIONER_ROLE, ALLOCATOR_ROLE, READER_ROLE)) {
                boolean expected = (name.equals("enroll_binding") || name.equals("register_attestation"))
                        && role.equals(PROVISIONER_ROLE)
                        || (name.equals("request_capacity") || name.equals("allocate_next"))
                        && role.equals(ALLOCATOR_ROLE)
                        || (name.equals("find_issue") || name.equals("read_attestation")
                                || name.equals("read_issued_token")) && role.equals(READER_ROLE)
                        || name.equals("assert_authority_identity");
                Boolean permitted = jdbc.queryForObject(
                        "select has_function_privilege(?, ?, 'EXECUTE')",
                        Boolean.class, role, signature);
                if (permitted == null || permitted != expected)
                    throw new IllegalStateException("Capacity authority function ACL changed");
            }
            Integer publicGrants = jdbc.queryForObject("""
                    select count(*) from pg_catalog.pg_proc p,
                         lateral aclexplode(coalesce(p.proacl, acldefault('f',p.proowner))) a
                    where p.oid=to_regprocedure(?) and a.grantee=0
                    """, Integer.class, signature);
            if (publicGrants == null || publicGrants != 0)
                throw new IllegalStateException("Capacity authority function became PUBLIC");
        }
    }

    private static void assertCompositeForeignKey(Connection connection, String table,
                                                   String key, String reference) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*) from pg_catalog.pg_constraint k
                where k.conrelid=to_regclass(?) and k.contype='f'
                    and pg_catalog.pg_get_constraintdef(k.oid) like ?
                    and pg_catalog.pg_get_constraintdef(k.oid) like ?
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setString(2, "%FOREIGN KEY " + key + "%");
            statement.setString(3, "%REFERENCES praxis_bulk_capacity." + reference + "%");
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1)
                    throw new IllegalStateException("Capacity composite foreign key changed");
            }
        }
    }

    private static void validateRole(JdbcTemplate jdbc, String role) {
        Integer bad = jdbc.queryForObject("""
                select count(*) from pg_catalog.pg_roles r
                where r.rolname=? and (r.rolcanlogin or r.rolinherit or r.rolsuper
                   or r.rolcreatedb or r.rolcreaterole or r.rolreplication or r.rolbypassrls)
                """, Integer.class, role);
        Integer present = jdbc.queryForObject(
                "select count(*) from pg_catalog.pg_roles where rolname=?", Integer.class, role);
        if (present == null || present != 1 || bad == null || bad != 0)
            throw new IllegalStateException("Capacity authority role catalog changed");
    }

    private static Map<String, String> expectedBodies() {
        var result = new HashMap<String, String>();
        for (String resource : Set.of(V1_RESOURCE, RESOURCE)) {
            try (var input = BulkCapacityAuthorityMigrator.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing capacity authority migration");
                var matcher = BODY.matcher(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (result.putIfAbsent(matcher.group(1), matcher.group(2)) != null)
                        throw new IllegalStateException("Duplicate capacity authority function");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to inspect capacity authority migration", failure);
            }
        }
        if (!result.keySet().equals(FUNCTIONS.keySet()))
            throw new IllegalStateException("Capacity authority function manifest changed");
        return Map.copyOf(result);
    }

    private static void assertV1LiveCatalog(Connection connection, String owner) throws SQLException {
        BulkCapacityAuthorityCatalog.validateV1(connection, owner);
        Map<String, String> bodies = expectedBodies();
        for (var entry : v1Functions().entrySet()) {
            try (var statement = connection.prepareStatement("""
                    select prosrc from pg_catalog.pg_proc where oid=to_regprocedure(?)
                    """)) {
                statement.setString(1, SCHEMA + "." + entry.getKey() + "(" + entry.getValue() + ")");
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !bodies.get(entry.getKey()).strip().equals(rows.getString(1).strip())
                            || rows.next())
                        throw new IllegalStateException("Capacity authority V1 function changed");
                }
            }
        }
        String installed = singleText(connection, """
                select pg_catalog.obj_description(to_regnamespace('praxis_bulk_capacity'), 'pg_namespace')
                """);
        String actual = singleText(connection, catalogQuery(V1_RESOURCE));
        if (installed == null || !installed.replace("praxis_bulk_capacity.", "")
                .equals(actual.replace("praxis_bulk_capacity.", "")))
            throw new IllegalStateException("Capacity authority V1 structural catalog changed");
    }

    private static void preflightV1RowsAndCallers(Connection connection, Identity identity,
            RoleConfiguration roles) throws SQLException {
        int identities = singleInt(connection,
                "select count(*) from praxis_bulk_capacity.authority_identity");
        if (identities == 1) {
            try (var statement = connection.prepareStatement("""
                    select deployment_id, environment, authority_id, authority_epoch
                    from praxis_bulk_capacity.authority_identity
                    """); var rows = statement.executeQuery()) {
                if (!rows.next() || !identity.deploymentId().equals(rows.getString(1))
                        || !identity.environment().equals(rows.getString(2))
                        || !identity.authorityId().equals(rows.getObject(3, UUID.class))
                        || identity.expectedAuthorityEpoch() != rows.getLong(4) || rows.next())
                    throw new IllegalStateException("V1 authority identity differs before upgrade");
            }
            assertCallers(connection, roles);
            return;
        }
        if (identities != 0)
            throw new IllegalStateException("V1 authority identity is ambiguous");
        for (String table : Set.of("deployment_capacity", "tenant_capacity", "capacity_binding",
                "capacity_request", "capacity_token", "fairness_cursor")) {
            if (singleInt(connection, "select count(*) from praxis_bulk_capacity." + table) != 0)
                throw new IllegalStateException("Used V1 authority cannot bootstrap");
        }
        for (String login : Set.of(roles.provisionerLogin(), roles.allocatorLogin(), roles.readerLogin())) {
            try (var statement = connection.prepareStatement("""
                    select count(*) from pg_catalog.pg_roles
                    where rolname=? and rolcanlogin and rolinherit and not rolsuper
                      and not rolcreatedb and not rolcreaterole and not rolreplication and not rolbypassrls
                    """)) {
                statement.setString(1, login);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1 || rows.next())
                        throw new IllegalStateException("V1 caller login is not bounded");
                }
            }
            try (var statement = connection.prepareStatement("""
                    select count(*) from pg_catalog.pg_auth_members m
                    join pg_catalog.pg_roles caller on caller.oid=m.member where caller.rolname=?
                    """)) {
                statement.setString(1, login);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 0 || rows.next())
                        throw new IllegalStateException("V1 caller has premature role membership");
                }
            }
        }
    }

    /** Compares the live catalog to the photograph committed by V1, on this physical connection. */
    static void assertLiveCatalog(Connection connection, String expectedOwner) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        expectedOwner = canonical(expectedOwner);
        BulkCapacityAuthorityCatalog.validate(connection, expectedOwner);
        String installed = singleText(connection, """
                select pg_catalog.obj_description(to_regnamespace('praxis_bulk_capacity'), 'pg_namespace')
                """);
        String actual = singleText(connection, catalogQuery(RESOURCE));
        // pg_get_* qualifies this same schema according to the caller's search_path.
        // The independent source manifest above still checks actual OIDs and definitions.
        if (installed == null || !installed.replace("praxis_bulk_capacity.", "")
                .equals(actual.replace("praxis_bulk_capacity.", "")))
            throw new IllegalStateException("Capacity authority structural catalog changed");
        Integer tableCount = singleInt(connection, """
                select count(*) from pg_catalog.pg_class c
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relkind in ('r','p')
                """);
        // Flyway owns the eighth relation; its history is validated separately by Flyway.validate().
        if (tableCount == null || tableCount != TABLES.size() + 1)
            throw new IllegalStateException("Capacity authority table manifest changed");
        Integer functionCount = singleInt(connection, """
                select count(*) from pg_catalog.pg_proc p
                join pg_catalog.pg_namespace n on n.oid=p.pronamespace
                where n.nspname='praxis_bulk_capacity'
                """);
        if (functionCount == null || functionCount != FUNCTIONS.size())
            throw new IllegalStateException("Capacity authority function manifest changed");
        for (String table : TABLES) {
            try (var statement = connection.prepareStatement("""
                    select pg_catalog.pg_get_userbyid(c.relowner), c.relkind, c.relrowsecurity
                    from pg_catalog.pg_class c where c.oid=to_regclass(?)
                    """)) {
                statement.setString(1, SCHEMA + "." + table);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !expectedOwner.equals(rows.getString(1))
                            || !"r".equals(rows.getString(2)) || rows.getBoolean(3) || rows.next())
                        throw new IllegalStateException("Capacity authority relation owner or RLS changed");
                }
            }
        }
        assertCompositeForeignKey(connection, "capacity_binding", "(deployment_id, environment)",
                "authority_identity(deployment_id, environment)");
        assertCompositeForeignKey(connection, "capacity_token",
                "(request_id, deployment_id, tenant_id, binding_id, capacity_class)",
                "capacity_request(request_id, deployment_id, tenant_id, binding_id, capacity_class)");
        try (var statement = connection.prepareStatement("""
                select pg_catalog.pg_get_indexdef(i.oid) from pg_catalog.pg_class i
                where i.oid=to_regclass('praxis_bulk_capacity.capacity_one_pending_per_binding_class')
                """); var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getString(1) == null
                    || !rows.getString(1).contains("WHERE (state = 'PENDING'::text)"))
                throw new IllegalStateException("Capacity pending uniqueness changed");
        }
        Map<String, String> bodies = expectedBodies();
        for (var entry : FUNCTIONS.entrySet()) {
            String signature = SCHEMA + "." + entry.getKey() + "(" + entry.getValue() + ")";
            try (var statement = connection.prepareStatement("""
                    select p.prosrc, p.prosecdef, pg_catalog.pg_get_userbyid(p.proowner),
                        p.proconfig = array['search_path=pg_catalog, pg_temp']::text[]
                    from pg_catalog.pg_proc p where p.oid=to_regprocedure(?)
                    """)) {
                statement.setString(1, signature);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !bodies.get(entry.getKey()).strip().equals(rows.getString(1).strip())
                            || !rows.getBoolean(2) || !DEFINER_ROLE.equals(rows.getString(3))
                            || !rows.getBoolean(4) || rows.next())
                        throw new IllegalStateException("Capacity authority protected function changed");
                }
            }
        }
        for (String role : Set.of(DEFINER_ROLE, PROVISIONER_ROLE, ALLOCATOR_ROLE, READER_ROLE)) {
            try (var statement = connection.prepareStatement("""
                    select count(*) from pg_catalog.pg_roles
                    where rolname=? and not rolcanlogin and not rolinherit and not rolsuper
                        and not rolcreatedb and not rolcreaterole and not rolreplication and not rolbypassrls
                    """)) {
                statement.setString(1, role);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1)
                        throw new IllegalStateException("Capacity authority role catalog changed");
                }
            }
        }
    }

    private static String catalogQuery(String resource) {
        try (var input = BulkCapacityAuthorityMigrator.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing capacity authority migration");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.lastIndexOf("select pg_catalog.jsonb_build_object(");
            int end = sql.indexOf(")::text into v_manifest;", start);
            if (start < 0 || end < 0) throw new IllegalStateException("Missing capacity catalog manifest");
            return sql.substring(start, end + ")::text".length());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to inspect capacity migration", failure);
        }
    }

    private static String singleText(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Capacity catalog query returned no row");
            String result = rows.getString(1);
            if (rows.next()) throw new IllegalStateException("Capacity catalog query returned multiple rows");
            return result;
        }
    }

    private static Integer singleInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Capacity catalog query returned no row");
            int result = rows.getInt(1);
            if (rows.next()) throw new IllegalStateException("Capacity catalog query returned multiple rows");
            return result;
        }
    }

    private static String quoted(String identifier) {
        return "\"" + canonical(identifier).replace("\"", "\"\"") + "\"";
    }

    private static void configureCallers(JdbcTemplate jdbc, RoleConfiguration roles) {
        String database = jdbc.queryForObject("select current_database()", String.class);
        if (database == null) throw new IllegalStateException("Capacity database name unavailable");
        for (String login : Set.of(roles.ownerLogin(), roles.provisionerLogin(),
                roles.allocatorLogin(), roles.readerLogin())) {
            Integer present = jdbc.queryForObject("""
                    select count(*) from pg_catalog.pg_roles
                    where rolname=? and rolcanlogin and rolinherit and not rolsuper and not rolcreatedb
                        and not rolcreaterole and not rolreplication and not rolbypassrls
                    """, Integer.class, login);
            if ((present == null || present != 1) && !login.equals(roles.ownerLogin()))
                throw new IllegalStateException("Capacity caller role is not a bounded login");
        }
        jdbc.execute("revoke all on database " + quoted(database) + " from public");
        jdbc.execute("grant connect on database " + quoted(database) + " to "
                + quoted(roles.ownerLogin()) + ", " + quoted(roles.provisionerLogin()) + ", "
                + quoted(roles.allocatorLogin()) + ", " + quoted(roles.readerLogin()));
        jdbc.execute("grant " + quoted(PROVISIONER_ROLE) + " to " + quoted(roles.provisionerLogin()));
        jdbc.execute("grant " + quoted(ALLOCATOR_ROLE) + " to " + quoted(roles.allocatorLogin()));
        jdbc.execute("grant " + quoted(READER_ROLE) + " to " + quoted(roles.readerLogin()));
    }

    static Set<String> tableNames() { return TABLES; }
    static Map<String, String> columns() { return COLUMNS; }
    static Map<String, String> functions() { return FUNCTIONS; }
    static Map<String, String> v1Columns() {
        var result = new HashMap<>(COLUMNS);
        result.remove("binding_attestation");
        return Map.copyOf(result);
    }
    static Map<String, String> v1Functions() {
        var result = new HashMap<>(FUNCTIONS);
        result.remove("register_attestation");
        result.remove("read_attestation");
        result.remove("read_issued_token");
        return Map.copyOf(result);
    }

    static void assertCallers(Connection connection, RoleConfiguration roles) throws SQLException {
        String database = singleText(connection, "select current_database()");
        if (database == null) throw new IllegalStateException("Capacity database unavailable");
        for (var entry : Map.of(PROVISIONER_ROLE, roles.provisionerLogin(),
                ALLOCATOR_ROLE, roles.allocatorLogin(), READER_ROLE, roles.readerLogin()).entrySet()) {
            try (var statement = connection.prepareStatement("""
                    select m.admin_option, to_jsonb(m)->>'inherit_option',
                           to_jsonb(m)->>'set_option'
                    from pg_catalog.pg_auth_members m
                    join pg_catalog.pg_roles group_role on group_role.oid=m.roleid
                    join pg_catalog.pg_roles member_role on member_role.oid=m.member
                    where group_role.rolname=? and member_role.rolname=?
                    """)) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getBoolean(1)
                            || rows.getString(2) != null && !"true".equals(rows.getString(2))
                            || rows.getString(3) != null && !"true".equals(rows.getString(3))
                            || rows.next())
                        throw new IllegalStateException("Capacity caller membership changed");
                }
            }
            try (var statement = connection.prepareStatement("""
                    select count(*) from pg_catalog.pg_auth_members m
                    join pg_catalog.pg_roles caller on caller.oid=m.roleid
                    where caller.rolname=?
                    """)) {
                statement.setString(1, entry.getValue());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 0)
                        throw new IllegalStateException("Capacity caller login was granted to another role");
                }
            }
        }
        for (var entry : Map.of(PROVISIONER_ROLE, roles.provisionerLogin(),
                ALLOCATOR_ROLE, roles.allocatorLogin(), READER_ROLE, roles.readerLogin()).entrySet()) {
            try (var statement = connection.prepareStatement("""
                    select pg_catalog.pg_get_userbyid(m.roleid)
                    from pg_catalog.pg_auth_members m
                    join pg_catalog.pg_roles caller on caller.oid=m.member
                    where caller.rolname=?
                    """)) {
                statement.setString(1, entry.getValue());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !entry.getKey().equals(rows.getString(1)) || rows.next())
                        throw new IllegalStateException("Capacity caller has unexpected role membership");
                }
            }
        }
        for (String caller : Set.of(roles.provisionerLogin(), roles.allocatorLogin(), roles.readerLogin())) {
            try (var statement = connection.prepareStatement("""
                    select rolcanlogin, rolinherit, rolsuper, rolcreatedb, rolcreaterole,
                           rolreplication, rolbypassrls from pg_catalog.pg_roles where rolname=?
                    """)) {
                statement.setString(1, caller);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1) || !rows.getBoolean(2)
                            || rows.getBoolean(3) || rows.getBoolean(4) || rows.getBoolean(5)
                            || rows.getBoolean(6) || rows.getBoolean(7) || rows.next())
                        throw new IllegalStateException("Capacity caller role attributes changed");
                }
            }
            for (var entry : Map.of(PROVISIONER_ROLE, roles.provisionerLogin(),
                    ALLOCATOR_ROLE, roles.allocatorLogin(), READER_ROLE, roles.readerLogin()).entrySet()) {
                try (var statement = connection.prepareStatement(
                        "select pg_has_role(?, ?, 'MEMBER')")) {
                    statement.setString(1, caller);
                    statement.setString(2, entry.getKey());
                    try (var rows = statement.executeQuery()) {
                        boolean expected = caller.equals(entry.getValue());
                        if (!rows.next() || rows.getBoolean(1) != expected || rows.next())
                            throw new IllegalStateException("Capacity nested caller membership changed");
                    }
                }
            }
            try (var statement = connection.prepareStatement("""
                    select count(*) from pg_catalog.pg_namespace n
                    where n.nspname not like 'pg_%' and n.nspname<>'information_schema'
                      and has_schema_privilege(?, n.oid, 'CREATE')
                    """)) {
                statement.setString(1, caller);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 0)
                        throw new IllegalStateException("Capacity caller acquired schema DDL");
                }
            }
        }
        try (var statement = connection.prepareStatement("""
                select count(*) from pg_catalog.pg_auth_members m
                join pg_catalog.pg_roles r on r.oid=m.roleid
                where r.rolname='praxis_bulk_capacity_definer'
                """)) {
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 0)
                    throw new IllegalStateException("Capacity definer membership changed");
            }
        }
        for (String caller : Set.of(roles.provisionerLogin(), roles.allocatorLogin(), roles.readerLogin())) {
            try (var statement = connection.prepareStatement("""
                    select has_database_privilege(?, ?, 'CONNECT'),
                           has_database_privilege(?, ?, 'CREATE'),
                           has_database_privilege(?, ?, 'TEMPORARY')
                    """)) {
                statement.setString(1, caller); statement.setString(2, database);
                statement.setString(3, caller); statement.setString(4, database);
                statement.setString(5, caller); statement.setString(6, database);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1) || rows.getBoolean(2)
                            || rows.getBoolean(3) || rows.next())
                        throw new IllegalStateException("Capacity caller database privileges changed");
                }
            }
        }
        // Internal functional groups may serve other databases, but may not inherit another role.
        try (var statement = connection.prepareStatement("""
                select count(*) from pg_catalog.pg_auth_members m
                join pg_catalog.pg_roles member_role on member_role.oid=m.member
                where member_role.rolname in ('praxis_bulk_capacity_definer',
                    'praxis_bulk_capacity_provisioner', 'praxis_bulk_capacity_allocator',
                    'praxis_bulk_capacity_reader')
                """); var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 0)
                throw new IllegalStateException("Capacity functional role acquired nested membership");
        }
        try (var statement = connection.prepareStatement("""
                select count(*) from pg_catalog.pg_database d,
                   lateral aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
                where d.datname=? and a.grantee=0
                """)) {
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 0)
                    throw new IllegalStateException("Capacity database became PUBLIC");
            }
        }
        try (var statement = connection.prepareStatement("""
                select pg_catalog.pg_get_userbyid(a.grantee), a.privilege_type,
                       a.is_grantable, pg_catalog.pg_get_userbyid(a.grantor)
                from pg_catalog.pg_database d,
                     lateral aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
                where d.datname=? and a.grantee<>d.datdba
                """)) {
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) {
                Set<String> expected = Set.of(roles.provisionerLogin(), roles.allocatorLogin(), roles.readerLogin());
                var seen = new java.util.HashSet<String>();
                while (rows.next()) {
                    String login = rows.getString(1);
                    if (!expected.contains(login) || !"CONNECT".equals(rows.getString(2))
                            || rows.getBoolean(3) || !roles.ownerLogin().equals(rows.getString(4))
                            || !seen.add(login))
                        throw new IllegalStateException("Capacity database ACL changed");
                }
                if (!seen.equals(expected))
                    throw new IllegalStateException("Capacity database caller ACL missing");
            }
        }
    }

    private static Flyway flyway(DataSource source) {
        return flyway(source, null);
    }

    private static Flyway flyway(DataSource source, org.flywaydb.core.api.MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(source)
                .locations("classpath:db/praxis-bulk-capacity-migrations")
                .schemas(SCHEMA).defaultSchema(SCHEMA).table(HISTORY_TABLE)
                .createSchemas(true).baselineOnMigrate(false).cleanDisabled(true)
                .validateOnMigrate(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static void assertDedicatedDatabase(DataSource source) {
        try (Connection connection = source.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
                throw new IllegalStateException("Capacity authority requires PostgreSQL");
            if (connection.isReadOnly())
                throw new IllegalStateException("Capacity authority migration requires writable database");
            var jdbc = new JdbcTemplate(source);
            Boolean unknown = jdbc.queryForObject("""
                    select to_regnamespace('praxis_bulk_capacity') is not null
                       and to_regclass('praxis_bulk_capacity.praxis_bulk_capacity_schema_history') is null
                    """, Boolean.class);
            if (Boolean.TRUE.equals(unknown))
                throw new IllegalStateException("Unmanaged capacity authority schema exists");
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect capacity authority database", failure);
        }
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Capacity migration requires an independent deployment step");
    }

    static String canonical(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid capacity authority identity");
        return value;
    }
}
