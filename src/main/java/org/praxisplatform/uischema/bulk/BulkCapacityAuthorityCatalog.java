package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Source-owned authority catalog rules. Database photographs are supplementary only. */
final class BulkCapacityAuthorityCatalog {
    // PG14 V1 source-owned exact structural tuples; the database COMMENT is never an expected value.
    private static final Set<String> EXPECTED_CONSTRAINTS = Set.of("""
authority_identity|authority_identity_authority_epoch_check|c|CHECK ((authority_epoch > 0))|t|f|f|{4}|-|||
authority_identity|authority_identity_authority_id_key|u|UNIQUE (authority_id)|t|f|f|{3}|-|||
authority_identity|authority_identity_check|c|CHECK (((deployment_id !~ '[[:cntrl:]]'::text) AND (environment !~ '[[:cntrl:]]'::text)))|t|f|f|{1,2}|-|||
authority_identity|authority_identity_deployment_id_check|c|CHECK (((deployment_id <> ''::text) AND (deployment_id = btrim(deployment_id))))|t|f|f|{1}|-|||
authority_identity|authority_identity_deployment_id_environment_key|u|UNIQUE (deployment_id, environment)|t|f|f|{1,2}|-|||
authority_identity|authority_identity_environment_check|c|CHECK (((environment <> ''::text) AND (environment = btrim(environment))))|t|f|f|{2}|-|||
authority_identity|authority_identity_pkey|p|PRIMARY KEY (deployment_id)|t|f|f|{1}|-|||
capacity_binding|capacity_binding_binding_generation_check|c|CHECK ((binding_generation > 0))|t|f|f|{5}|-|||
capacity_binding|capacity_binding_binding_id_check|c|CHECK (((binding_id <> ''::text) AND (binding_id = btrim(binding_id)) AND (binding_id !~ '[[:cntrl:]]'::text)))|t|f|f|{1}|-|||
capacity_binding|capacity_binding_binding_id_deployment_id_tenant_id_key|u|UNIQUE (binding_id, deployment_id, tenant_id)|t|f|f|{1,2,3}|-|||
capacity_binding|capacity_binding_deployment_id_environment_fkey|f|FOREIGN KEY (deployment_id, environment) REFERENCES authority_identity(deployment_id, environment) ON DELETE RESTRICT|t|f|f|{2,4}|{1,2}|a|r|s
capacity_binding|capacity_binding_deployment_id_tenant_id_fkey|f|FOREIGN KEY (deployment_id, tenant_id) REFERENCES tenant_capacity(deployment_id, tenant_id) ON DELETE RESTRICT|t|f|f|{2,3}|{1,2}|a|r|s
capacity_binding|capacity_binding_pkey|p|PRIMARY KEY (binding_id)|t|f|f|{1}|-|||
capacity_request|capacity_request_binding_id_deployment_id_tenant_id_fkey|f|FOREIGN KEY (binding_id, deployment_id, tenant_id) REFERENCES capacity_binding(binding_id, deployment_id, tenant_id) ON DELETE RESTRICT|t|f|f|{4,2,3}|{1,2,3}|a|r|s
capacity_request|capacity_request_capacity_class_check|c|CHECK ((capacity_class = ANY (ARRAY['QUEUE'::text, 'ACTIVE'::text])))|t|f|f|{5}|-|||
capacity_request|capacity_request_check|c|CHECK ((issued_count <= requested_count))|t|f|f|{7,6}|-|||
capacity_request|capacity_request_check1|c|CHECK ((((state = 'PENDING'::text) AND (issued_count < requested_count)) OR ((state = 'FULFILLED'::text) AND (issued_count = requested_count))))|t|f|f|{9,7,6}|-|||
capacity_request|capacity_request_check2|c|CHECK ((((capacity_class = 'ACTIVE'::text) AND (requested_count <= 2)) OR ((capacity_class = 'QUEUE'::text) AND (requested_count <= 20))))|t|f|f|{5,6}|-|||
capacity_request|capacity_request_issued_count_check|c|CHECK ((issued_count >= 0))|t|f|f|{7}|-|||
capacity_request|capacity_request_payload_digest_check|c|CHECK ((payload_digest ~ '^sha256:[0-9a-f]{64}$'::text))|t|f|f|{8}|-|||
capacity_request|capacity_request_pkey|p|PRIMARY KEY (request_id)|t|f|f|{1}|-|||
capacity_request|capacity_request_request_id_deployment_id_tenant_id_binding_key|u|UNIQUE (request_id, deployment_id, tenant_id, binding_id, capacity_class)|t|f|f|{1,2,3,4,5}|-|||
capacity_request|capacity_request_requested_count_check|c|CHECK ((requested_count > 0))|t|f|f|{6}|-|||
capacity_request|capacity_request_state_check|c|CHECK ((state = ANY (ARRAY['PENDING'::text, 'FULFILLED'::text])))|t|f|f|{9}|-|||
capacity_token|capacity_token_binding_generation_check|c|CHECK ((binding_generation > 0))|t|f|f|{8}|-|||
capacity_token|capacity_token_binding_id_deployment_id_tenant_id_fkey|f|FOREIGN KEY (binding_id, deployment_id, tenant_id) REFERENCES capacity_binding(binding_id, deployment_id, tenant_id) ON DELETE RESTRICT|t|f|f|{6,4,5}|{1,2,3}|a|r|s
capacity_token|capacity_token_capacity_class_check|c|CHECK ((capacity_class = ANY (ARRAY['QUEUE'::text, 'ACTIVE'::text])))|t|f|f|{7}|-|||
capacity_token|capacity_token_pkey|p|PRIMARY KEY (token_id)|t|f|f|{1}|-|||
capacity_token|capacity_token_request_id_deployment_id_tenant_id_binding__fkey|f|FOREIGN KEY (request_id, deployment_id, tenant_id, binding_id, capacity_class) REFERENCES capacity_request(request_id, deployment_id, tenant_id, binding_id, capacity_class) ON DELETE RESTRICT|t|f|f|{2,4,5,6,7}|{1,2,3,4,5}|a|r|s
capacity_token|capacity_token_request_id_fkey|f|FOREIGN KEY (request_id) REFERENCES capacity_request(request_id) ON DELETE RESTRICT|t|f|f|{2}|{1}|a|r|s
capacity_token|capacity_token_request_id_token_ordinal_key|u|UNIQUE (request_id, token_ordinal)|t|f|f|{2,3}|-|||
capacity_token|capacity_token_state_check|c|CHECK ((state = 'ISSUED'::text))|t|f|f|{9}|-|||
capacity_token|capacity_token_token_ordinal_check|c|CHECK ((token_ordinal > 0))|t|f|f|{3}|-|||
deployment_capacity|deployment_capacity_deployment_id_fkey|f|FOREIGN KEY (deployment_id) REFERENCES authority_identity(deployment_id) ON DELETE RESTRICT|t|f|f|{1}|{1}|a|r|s
deployment_capacity|deployment_capacity_pkey|p|PRIMARY KEY (deployment_id)|t|f|f|{1}|-|||
fairness_cursor|fairness_cursor_capacity_class_check|c|CHECK ((capacity_class = ANY (ARRAY['QUEUE'::text, 'ACTIVE'::text])))|t|f|f|{2}|-|||
fairness_cursor|fairness_cursor_cursor_epoch_check|c|CHECK ((cursor_epoch >= 0))|t|f|f|{4}|-|||
fairness_cursor|fairness_cursor_deployment_id_fkey|f|FOREIGN KEY (deployment_id) REFERENCES deployment_capacity(deployment_id) ON DELETE RESTRICT|t|f|f|{1}|{1}|a|r|s
fairness_cursor|fairness_cursor_pkey|p|PRIMARY KEY (deployment_id, capacity_class)|t|f|f|{1,2}|-|||
praxis_bulk_capacity_schema_history|praxis_bulk_capacity_schema_history_pk|p|PRIMARY KEY (installed_rank)|t|f|f|{1}|-|||
tenant_capacity|tenant_capacity_deployment_id_fkey|f|FOREIGN KEY (deployment_id) REFERENCES deployment_capacity(deployment_id) ON DELETE RESTRICT|t|f|f|{1}|{1}|a|r|s
tenant_capacity|tenant_capacity_pkey|p|PRIMARY KEY (deployment_id, tenant_id)|t|f|f|{1,2}|-|||
tenant_capacity|tenant_capacity_tenant_id_check|c|CHECK (((tenant_id <> ''::text) AND (tenant_id = btrim(tenant_id)) AND (tenant_id !~ '[[:cntrl:]]'::text)))|t|f|f|{2}|-|||
            """.strip().lines().toList().toArray(String[]::new));
    private static final Set<String> EXPECTED_INDEXES = Set.of("""
authority_identity|authority_identity_authority_id_key|CREATE UNIQUE INDEX authority_identity_authority_id_key ON authority_identity USING btree (authority_id)|t|f|t|t|t|-
authority_identity|authority_identity_deployment_id_environment_key|CREATE UNIQUE INDEX authority_identity_deployment_id_environment_key ON authority_identity USING btree (deployment_id, environment)|t|f|t|t|t|-
authority_identity|authority_identity_pkey|CREATE UNIQUE INDEX authority_identity_pkey ON authority_identity USING btree (deployment_id)|t|t|t|t|t|-
capacity_binding|capacity_binding_binding_id_deployment_id_tenant_id_key|CREATE UNIQUE INDEX capacity_binding_binding_id_deployment_id_tenant_id_key ON capacity_binding USING btree (binding_id, deployment_id, tenant_id)|t|f|t|t|t|-
capacity_binding|capacity_binding_pkey|CREATE UNIQUE INDEX capacity_binding_pkey ON capacity_binding USING btree (binding_id)|t|t|t|t|t|-
capacity_request|capacity_one_pending_per_binding_class|CREATE UNIQUE INDEX capacity_one_pending_per_binding_class ON capacity_request USING btree (deployment_id, tenant_id, binding_id, capacity_class) WHERE (state = 'PENDING'::text)|t|f|t|t|t|(state = 'PENDING'::text)
capacity_request|capacity_request_pkey|CREATE UNIQUE INDEX capacity_request_pkey ON capacity_request USING btree (request_id)|t|t|t|t|t|-
capacity_request|capacity_request_request_id_deployment_id_tenant_id_binding_key|CREATE UNIQUE INDEX capacity_request_request_id_deployment_id_tenant_id_binding_key ON capacity_request USING btree (request_id, deployment_id, tenant_id, binding_id, capacity_class)|t|f|t|t|t|-
capacity_token|capacity_token_deployment_class|CREATE INDEX capacity_token_deployment_class ON capacity_token USING btree (deployment_id, capacity_class)|f|f|t|t|t|-
capacity_token|capacity_token_pkey|CREATE UNIQUE INDEX capacity_token_pkey ON capacity_token USING btree (token_id)|t|t|t|t|t|-
capacity_token|capacity_token_request_id_token_ordinal_key|CREATE UNIQUE INDEX capacity_token_request_id_token_ordinal_key ON capacity_token USING btree (request_id, token_ordinal)|t|f|t|t|t|-
capacity_token|capacity_token_tenant_class|CREATE INDEX capacity_token_tenant_class ON capacity_token USING btree (deployment_id, tenant_id, capacity_class)|f|f|t|t|t|-
deployment_capacity|deployment_capacity_pkey|CREATE UNIQUE INDEX deployment_capacity_pkey ON deployment_capacity USING btree (deployment_id)|t|t|t|t|t|-
fairness_cursor|fairness_cursor_pkey|CREATE UNIQUE INDEX fairness_cursor_pkey ON fairness_cursor USING btree (deployment_id, capacity_class)|t|t|t|t|t|-
praxis_bulk_capacity_schema_history|praxis_bulk_capacity_schema_history_pk|CREATE UNIQUE INDEX praxis_bulk_capacity_schema_history_pk ON praxis_bulk_capacity_schema_history USING btree (installed_rank)|t|t|t|t|t|-
praxis_bulk_capacity_schema_history|praxis_bulk_capacity_schema_history_s_idx|CREATE INDEX praxis_bulk_capacity_schema_history_s_idx ON praxis_bulk_capacity_schema_history USING btree (success)|f|f|t|t|t|-
tenant_capacity|tenant_capacity_pkey|CREATE UNIQUE INDEX tenant_capacity_pkey ON tenant_capacity USING btree (deployment_id, tenant_id)|t|t|t|t|t|-
            """.strip().lines().toList().toArray(String[]::new));

    private BulkCapacityAuthorityCatalog() { }

    static void validate(Connection connection, String owner) throws SQLException {
        validate(connection, owner, true);
    }

    static void validateV1(Connection connection, String owner) throws SQLException {
        validate(connection, owner, false);
    }

    private static void validate(Connection connection, String owner, boolean v2) throws SQLException {
        owner = BulkCapacityAuthorityMigrator.canonical(owner);
        try (var statement = connection.prepareStatement("""
                select pg_catalog.pg_get_userbyid(nspowner)
                from pg_catalog.pg_namespace where nspname='praxis_bulk_capacity'
                """); var rows = statement.executeQuery()) {
            if (!rows.next() || !owner.equals(rows.getString(1)) || rows.next())
                throw new IllegalStateException("Capacity authority schema owner changed");
        }
        try (var statement = connection.prepareStatement("""
                select pg_catalog.pg_get_userbyid(relowner), relkind, relrowsecurity
                from pg_catalog.pg_class
                where oid=to_regclass('praxis_bulk_capacity.praxis_bulk_capacity_schema_history')
                """); var rows = statement.executeQuery()) {
            if (!rows.next() || !owner.equals(rows.getString(1)) || !"r".equals(rows.getString(2))
                    || rows.getBoolean(3) || rows.next())
                throw new IllegalStateException("Capacity authority history owner changed");
        }
        for (var entry : (v2 ? BulkCapacityAuthorityMigrator.columns()
                : BulkCapacityAuthorityMigrator.v1Columns()).entrySet()) {
            String table = entry.getKey();
            String[] expected = entry.getValue().split(",");
            try (var statement = connection.prepareStatement("""
                    select a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod),
                           a.attnotnull, pg_catalog.pg_get_expr(d.adbin,d.adrelid),
                           a.attgenerated, a.attidentity
                    from pg_catalog.pg_attribute a
                    left join pg_catalog.pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                    where a.attrelid=to_regclass(?) and a.attnum>0 and not a.attisdropped
                    order by a.attnum
                    """)) {
                statement.setString(1, BulkCapacityAuthorityMigrator.SCHEMA + "." + table);
                try (var rows = statement.executeQuery()) {
                    for (String column : expected) {
                        String[] parts = column.split(":", 2);
                        if (!rows.next() || !parts[0].equals(rows.getString(1))
                                || !parts[1].equals(rows.getString(2))
                                || rows.getBoolean(3) == (table.equals("fairness_cursor")
                                        && parts[0].equals("last_tenant_id"))
                                || !expectedDefault(table, parts[0]).equals(normalizeDefault(rows.getString(4)))
                                || !"".equals(rows.getString(5)) || !"".equals(rows.getString(6)))
                            throw new IllegalStateException("Capacity authority column contract changed");
                    }
                    if (rows.next()) throw new IllegalStateException("Extra capacity authority column");
                }
            }
        }
        try (var statement = connection.prepareStatement("""
                select count(*) from pg_catalog.pg_trigger t
                join pg_catalog.pg_class c on c.oid=t.tgrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and not t.tgisinternal
                """); var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 0)
                throw new IllegalStateException("Capacity authority trigger contract changed");
        }
        try (var statement = connection.prepareStatement("""
                select count(*) from pg_catalog.pg_trigger t
                join pg_catalog.pg_class c on c.oid=t.tgrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and t.tgisinternal and t.tgenabled<>'O'
                """); var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 0)
                throw new IllegalStateException("Capacity authority FK trigger was disabled");
        }
        assertStructuralTuples(connection, v2);
        var functions = new java.util.HashMap<>(Map.of(
                "enroll_binding", "plpgsql:v:void:false",
                "request_capacity", "plpgsql:v:void:false",
                "allocate_next", "plpgsql:v:record:true",
                "find_issue", "sql:s:record:true",
                "assert_authority_identity", "sql:s:boolean:false"));
        var results = new java.util.HashMap<>(Map.of(
                "enroll_binding", "void", "request_capacity", "void",
                "assert_authority_identity", "boolean",
                "allocate_next", "TABLE(token_id uuid, request_id uuid, deployment_id text,"
                        + " tenant_id text, binding_id text,"
                        + " capacity_class text, binding_generation bigint, token_ordinal integer)",
                "find_issue", "TABLE(found boolean, deployment_id text, tenant_id text, binding_id text,"
                        + " capacity_class text, requested_count integer, issued_count integer,"
                        + " payload_digest text, token_ids uuid[])"));
        if (v2) {
            functions.put("register_attestation", "plpgsql:v:void:false");
            functions.put("read_attestation", "sql:s:record:true");
            functions.put("read_issued_token", "sql:s:record:true");
            results.put("register_attestation", "void");
            results.put("read_attestation", "TABLE(attestation_id uuid, database_id uuid, binding_id text,"
                    + " deployment_id text, tenant_id text, environment text, binding_generation bigint,"
                    + " authority_id uuid, authority_epoch bigint)");
            results.put("read_issued_token", "TABLE(token_id uuid, request_id uuid, deployment_id text,"
                    + " tenant_id text, environment text, binding_id text, binding_generation bigint,"
                    + " capacity_class text, token_ordinal integer, payload_digest text, token_state text,"
                    + " database_id uuid, attestation_id uuid, authority_id uuid, authority_epoch bigint)");
        }
        for (var entry : (v2 ? BulkCapacityAuthorityMigrator.functions()
                : BulkCapacityAuthorityMigrator.v1Functions()).entrySet()) {
            try (var statement = connection.prepareStatement("""
                    select l.lanname, p.provolatile, p.prorettype::regtype::text, p.proretset,
                           p.proisstrict, p.prosecdef,
                           p.proconfig = array['search_path=pg_catalog, pg_temp']::text[],
                           pg_catalog.pg_get_userbyid(p.proowner),
                           pg_catalog.pg_get_function_result(p.oid), p.proparallel, p.proleakproof
                    from pg_catalog.pg_proc p join pg_catalog.pg_language l on l.oid=p.prolang
                    where p.oid=to_regprocedure(?)
                    """)) {
                statement.setString(1, BulkCapacityAuthorityMigrator.SCHEMA + "." + entry.getKey()
                        + "(" + entry.getValue() + ")");
                try (var rows = statement.executeQuery()) {
                    String[] expected = functions.get(entry.getKey()).split(":");
                    if (!rows.next() || !expected[0].equals(rows.getString(1))
                            || !expected[1].equals(rows.getString(2))
                            || !expected[2].equals(rows.getString(3))
                            || Boolean.parseBoolean(expected[3]) != rows.getBoolean(4)
                            || rows.getBoolean(5) || !rows.getBoolean(6) || !rows.getBoolean(7)
                            || !"praxis_bulk_capacity_definer".equals(rows.getString(8))
                            || !results.get(entry.getKey()).equals(rows.getString(9))
                            || !"u".equals(rows.getString(10)) || rows.getBoolean(11) || rows.next())
                        throw new IllegalStateException("Capacity authority function signature changed");
                }
            }
        }
        validateAcls(connection, owner, v2);
        // The source-owned sets above and below are primary; V1's catalog photograph adds a drift witness.
    }

    private static void validateAcls(Connection connection, String owner, boolean v2) throws SQLException {
        var schemaExpected = Set.of("praxis_bulk_capacity_definer|USAGE",
                "praxis_bulk_capacity_provisioner|USAGE", "praxis_bulk_capacity_allocator|USAGE",
                "praxis_bulk_capacity_reader|USAGE");
        assertAcl(connection, """
                select pg_catalog.pg_get_userbyid(a.grantee), a.privilege_type, a.is_grantable,
                       pg_catalog.pg_get_userbyid(a.grantor)
                from pg_catalog.pg_namespace n,
                     lateral aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
                where n.nspname='praxis_bulk_capacity' and a.grantee<>n.nspowner
                """, schemaExpected, "schema", owner);
        var relationExpected = new HashSet<String>();
        for (String table : v2 ? BulkCapacityAuthorityMigrator.tableNames()
                : BulkCapacityAuthorityMigrator.v1Columns().keySet())
            relationExpected.add(table + "|praxis_bulk_capacity_definer|SELECT");
        for (String table : Set.of("tenant_capacity", "capacity_binding", "capacity_request",
                "capacity_token", "fairness_cursor"))
            relationExpected.add(table + "|praxis_bulk_capacity_definer|INSERT");
        if (v2) {
            relationExpected.add("binding_attestation|praxis_bulk_capacity_definer|SELECT");
            relationExpected.add("binding_attestation|praxis_bulk_capacity_definer|INSERT");
        }
        assertNamedAcl(connection, """
                select c.relname, pg_catalog.pg_get_userbyid(a.grantee), a.privilege_type,
                       a.is_grantable, pg_catalog.pg_get_userbyid(a.grantor)
                from pg_catalog.pg_class c
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace,
                     lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                where n.nspname='praxis_bulk_capacity' and c.relkind in ('r','p')
                  and a.grantee<>c.relowner
                """, relationExpected, "relation", owner);
        var columnExpected = Set.of(
                "deployment_capacity.deployment_id|praxis_bulk_capacity_definer|UPDATE",
                "tenant_capacity.tenant_id|praxis_bulk_capacity_definer|UPDATE",
                "capacity_binding.binding_id|praxis_bulk_capacity_definer|UPDATE",
                "capacity_request.issued_count|praxis_bulk_capacity_definer|UPDATE",
                "capacity_request.state|praxis_bulk_capacity_definer|UPDATE",
                "fairness_cursor.last_tenant_id|praxis_bulk_capacity_definer|UPDATE",
                "fairness_cursor.cursor_epoch|praxis_bulk_capacity_definer|UPDATE");
        assertNamedAcl(connection, """
                select c.relname || '.' || a.attname, pg_catalog.pg_get_userbyid(x.grantee),
                       x.privilege_type, x.is_grantable,
                       pg_catalog.pg_get_userbyid(x.grantor)
                from pg_catalog.pg_attribute a
                join pg_catalog.pg_class c on c.oid=a.attrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace,
                     lateral aclexplode(a.attacl) x
                where n.nspname='praxis_bulk_capacity' and c.relkind in ('r','p')
                  and a.attnum>0 and x.grantee<>c.relowner
                """, columnExpected, "column", owner);
        var functionExpected = new HashSet<>(Set.of(
                "enroll_binding(text,text,text,bigint)|praxis_bulk_capacity_provisioner|EXECUTE",
                "request_capacity(uuid,text,text,text,text,integer,text)|praxis_bulk_capacity_allocator|EXECUTE",
                "allocate_next(text,text)|praxis_bulk_capacity_allocator|EXECUTE",
                "find_issue(uuid)|praxis_bulk_capacity_reader|EXECUTE",
                "assert_authority_identity(text,text,uuid,bigint)|praxis_bulk_capacity_provisioner|EXECUTE",
                "assert_authority_identity(text,text,uuid,bigint)|praxis_bulk_capacity_allocator|EXECUTE",
                "assert_authority_identity(text,text,uuid,bigint)|praxis_bulk_capacity_reader|EXECUTE"));
        if (v2) {
            functionExpected.add("register_attestation(text,text,text,text,bigint,uuid,uuid)"
                    + "|praxis_bulk_capacity_provisioner|EXECUTE");
            functionExpected.add("read_attestation(uuid)|praxis_bulk_capacity_reader|EXECUTE");
            functionExpected.add("read_issued_token(uuid)|praxis_bulk_capacity_reader|EXECUTE");
        }
        assertNamedAcl(connection, """
                select p.oid::regprocedure::text, pg_catalog.pg_get_userbyid(a.grantee), a.privilege_type,
                       a.is_grantable, pg_catalog.pg_get_userbyid(a.grantor)
                from pg_catalog.pg_proc p
                join pg_catalog.pg_namespace n on n.oid=p.pronamespace,
                     lateral aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                where n.nspname='praxis_bulk_capacity' and a.grantee<>p.proowner
                """, functionExpected, "function", "praxis_bulk_capacity_definer");
    }

    private static void assertAcl(Connection connection, String sql, Set<String> expected,
                                  String surface, String expectedGrantor) throws SQLException {
        var actual = new HashSet<String>();
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            while (rows.next()) {
                if (rows.getBoolean(3) || !expectedGrantor.equals(rows.getString(4))
                        || !actual.add(rows.getString(1) + "|" + rows.getString(2)))
                    throw new IllegalStateException("Capacity authority " + surface + " ACL changed");
            }
        }
        if (!actual.equals(expected))
            throw new IllegalStateException("Capacity authority " + surface + " ACL changed");
    }

    private static void assertNamedAcl(Connection connection, String sql, Set<String> expected,
                                       String surface, String expectedGrantor) throws SQLException {
        var actual = new HashSet<String>();
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            while (rows.next()) {
                if (rows.getBoolean(4) || !expectedGrantor.equals(rows.getString(5))
                        || !actual.add(rows.getString(1)
                        .replace("praxis_bulk_capacity.", "") + "|" + rows.getString(2)
                        + "|" + rows.getString(3)))
                    throw new IllegalStateException("Capacity authority " + surface + " ACL changed");
            }
        }
        if (!actual.equals(expected))
            throw new IllegalStateException("Capacity authority " + surface + " ACL changed");
    }

    private static void assertStructuralTuples(Connection connection, boolean v2) throws SQLException {
        var constraints = new HashSet<String>();
        try (var statement = connection.prepareStatement("""
                select c.relname, k.conname, k.contype,
                       pg_catalog.pg_get_constraintdef(k.oid,false),
                       k.convalidated, k.condeferrable, k.condeferred,
                       k.conkey::text, k.confkey::text,
                       k.confupdtype, k.confdeltype, k.confmatchtype
                from pg_catalog.pg_constraint k
                join pg_catalog.pg_class c on c.oid=k.conrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relname<>'binding_attestation'
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                String tuple = structuralTuple(rows, 12);
                if (!constraints.add(tuple))
                    throw new IllegalStateException("Duplicate capacity constraint tuple");
            }
        }
        if (!constraints.equals(EXPECTED_CONSTRAINTS))
            throw new IllegalStateException("Capacity authority constraint manifest changed");
        var indexes = new HashSet<String>();
        try (var statement = connection.prepareStatement("""
                select c.relname, i.relname, pg_catalog.pg_get_indexdef(i.oid),
                       x.indisunique, x.indisprimary, x.indisvalid, x.indisready,
                       x.indislive, pg_catalog.pg_get_expr(x.indpred,x.indrelid,false)
                from pg_catalog.pg_index x
                join pg_catalog.pg_class c on c.oid=x.indrelid
                join pg_catalog.pg_class i on i.oid=x.indexrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relname<>'binding_attestation'
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                String tuple = structuralTuple(rows, 9);
                if (!indexes.add(tuple))
                    throw new IllegalStateException("Duplicate capacity index tuple");
            }
        }
        if (!indexes.equals(EXPECTED_INDEXES))
            throw new IllegalStateException("Capacity authority index manifest changed");
        if (v2) assertV2AttestationStructure(connection);
    }

    private static String structuralTuple(java.sql.ResultSet rows, int size) throws SQLException {
        var parts = new String[size];
        for (int index = 1; index <= size; index++) {
            String value = rows.getString(index);
            parts[index - 1] = value == null ? "-" : value.replace("praxis_bulk_capacity.", "").strip();
        }
        return String.join("|", parts);
    }

    private static void assertV2AttestationStructure(Connection connection) throws SQLException {
        var expected = Map.ofEntries(
                Map.entry("binding_attestation_pkey", new V2Constraint("p", "PRIMARY KEY (attestation_id)",
                        "1", "-", "-", " ")),
                Map.entry("binding_attestation_database_unique", new V2Constraint("u", "UNIQUE (database_id)",
                        "2", "-", "-", " ")),
                Map.entry("binding_attestation_binding_unique", new V2Constraint("u", "UNIQUE (binding_id)",
                        "3", "-", "-", " ")),
                Map.entry("binding_attestation_generation_positive", new V2Constraint("c",
                        "CHECK ((binding_generation > 0))", "7", "-", "-", " ")),
                Map.entry("binding_attestation_epoch_positive", new V2Constraint("c",
                        "CHECK ((authority_epoch > 0))", "9", "-", "-", " ")),
                Map.entry("binding_attestation_binding_fk", new V2Constraint("f",
                        "FOREIGN KEY (binding_id, deployment_id, tenant_id) REFERENCES "
                        + "praxis_bulk_capacity.capacity_binding(binding_id, deployment_id, tenant_id) "
                        + "ON DELETE RESTRICT", "3 4 5", "1 2 3", "capacity_binding", "r")),
                Map.entry("binding_attestation_identity_fk", new V2Constraint("f",
                        "FOREIGN KEY (deployment_id, environment) REFERENCES "
                        + "praxis_bulk_capacity.authority_identity(deployment_id, environment) "
                        + "ON DELETE RESTRICT", "4 6", "1 2", "authority_identity", "r")),
                Map.entry("binding_attestation_binding_canonical", new V2Constraint("c",
                        "CHECK (((binding_id <> ''::text) AND (binding_id = btrim(binding_id)) "
                        + "AND (binding_id !~ '[[:cntrl:]]'::text)))", "3", "-", "-", " ")),
                Map.entry("binding_attestation_deployment_canonical", new V2Constraint("c",
                        "CHECK (((deployment_id <> ''::text) AND (deployment_id = btrim(deployment_id)) "
                        + "AND (deployment_id !~ '[[:cntrl:]]'::text)))", "4", "-", "-", " ")),
                Map.entry("binding_attestation_tenant_canonical", new V2Constraint("c",
                        "CHECK (((tenant_id <> ''::text) AND (tenant_id = btrim(tenant_id)) "
                        + "AND (tenant_id !~ '[[:cntrl:]]'::text)))", "5", "-", "-", " ")),
                Map.entry("binding_attestation_environment_canonical", new V2Constraint("c",
                        "CHECK (((environment <> ''::text) AND (environment = btrim(environment)) "
                        + "AND (environment !~ '[[:cntrl:]]'::text)))", "6", "-", "-", " ")));
        var seen = new HashSet<String>();
        try (var statement = connection.prepareStatement("""
                select k.conname, pg_catalog.pg_get_constraintdef(k.oid,false),
                       k.convalidated, k.condeferrable, k.condeferred, k.contype,
                       k.conkey::text, k.confkey::text,
                       case when k.confrelid=0 then '-' else k.confrelid::regclass::text end,
                       k.confupdtype, k.confdeltype, k.confmatchtype
                from pg_catalog.pg_constraint k
                where k.conrelid=to_regclass('praxis_bulk_capacity.binding_attestation')
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString(1);
                V2Constraint source = expected.get(name);
                if (!seen.add(name) || source == null
                        || !constraintShape(rows.getString(2)).equals(constraintShape(source.definition()))
                        || !rows.getBoolean(3) || rows.getBoolean(4) || rows.getBoolean(5)
                        || !source.type().equals(rows.getString(6))
                        || !source.key().equals(arrayKey(rows.getString(7)))
                        || !source.foreignKey().equals(arrayKey(rows.getString(8)))
                        || !source.foreignTable().equals(
                                rows.getString(9).replace("praxis_bulk_capacity.", ""))
                        || !(source.type().equals("f") ? "a" : " ").equals(rows.getString(10))
                        || !source.deleteAction().equals(rows.getString(11))
                        || !(source.type().equals("f") ? "s" : " ").equals(rows.getString(12)))
                    throw new IllegalStateException("Capacity attestation constraint changed");
            }
        }
        if (!seen.equals(expected.keySet()))
            throw new IllegalStateException("Capacity attestation constraint manifest changed");
        var expectedIndexes = Map.of(
                "binding_attestation_pkey", new V2Index("attestation_id", "1", true),
                "binding_attestation_database_unique", new V2Index("database_id", "2", false),
                "binding_attestation_binding_unique", new V2Index("binding_id", "3", false));
        var indexes = new HashSet<String>();
        try (var statement = connection.prepareStatement("""
                select i.relname, pg_catalog.pg_get_indexdef(i.oid), x.indkey::text,
                       x.indisunique, x.indisprimary, x.indisvalid,
                       x.indisready, x.indislive, pg_catalog.pg_get_expr(x.indpred,x.indrelid,false),
                       x.indexprs is null, x.indnkeyatts, x.indnatts
                from pg_catalog.pg_index x
                join pg_catalog.pg_class i on i.oid=x.indexrelid
                where x.indrelid=to_regclass('praxis_bulk_capacity.binding_attestation')
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString(1);
                V2Index source = expectedIndexes.get(name);
                if (!indexes.add(name) || source == null || !rows.getBoolean(4)
                        || !rows.getBoolean(6) || !rows.getBoolean(7) || !rows.getBoolean(8)
                        || rows.getString(9) != null || !rows.getBoolean(10)
                        || rows.getInt(11) != 1 || rows.getInt(12) != 1
                        || !constraintShape(rows.getString(2)).equals(constraintShape(
                                "CREATE UNIQUE INDEX " + name + " ON "
                                        + "praxis_bulk_capacity.binding_attestation USING btree ("
                                        + source.column() + ")"))
                        || !arrayKey(rows.getString(3)).equals(source.key())
                        || rows.getBoolean(5) != source.primary())
                    throw new IllegalStateException("Capacity attestation index changed");
            }
        }
        if (!indexes.equals(expectedIndexes.keySet()))
            throw new IllegalStateException("Capacity attestation index manifest changed");
    }

    private record V2Constraint(String type, String definition, String key, String foreignKey,
                                String foreignTable, String deleteAction) { }

    private record V2Index(String column, String key, boolean primary) { }

    private static String arrayKey(String value) {
        return value == null ? "-" : value.replace("{", "").replace("}", "").replace(",", " ");
    }

    private static String constraintShape(String value) {
        if (value == null) return "-";
        var result = new StringBuilder(value.length());
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\'') {
                result.append(current);
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    result.append(value.charAt(++index));
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && Character.isWhitespace(current)) {
                continue;
            } else {
                result.append(quoted ? current : Character.toLowerCase(current));
            }
        }
        // pg_get_constraintdef qualifies FK targets according to the caller's search_path.
        // Only that catalog reference is normalized; literals and index/table definitions stay exact.
        return result.toString().replace("referencespraxis_bulk_capacity.", "references");
    }

    private static String expectedDefault(String table, String column) {
        return switch (table + "." + column) {
            case "capacity_request.issued_count", "fairness_cursor.cursor_epoch" -> "0";
            case "capacity_request.state" -> "'PENDING'::text";
            case "capacity_token.state" -> "'ISSUED'::text";
            case "capacity_request.requested_at", "capacity_token.issued_at" -> "clock_timestamp()";
            default -> "";
        };
    }

    private static String normalizeDefault(String value) { return value == null ? "" : value; }
}
