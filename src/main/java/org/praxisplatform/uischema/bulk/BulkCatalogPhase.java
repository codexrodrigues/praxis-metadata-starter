package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Facts for one read-only catalog validation phase. Never retained across writes or calls. */
final class BulkCatalogPhase {
    record Fact(Map<String, Object> values) {
        String text(String key) { Object value = values.get(key); return value == null ? null : value.toString(); }
        boolean flag(String key) { return Boolean.TRUE.equals(values.get(key)); }
        long number(String key) { Object value = values.get(key); return value == null ? 0 : ((Number) value).longValue(); }
        Object value(String key) { return values.get(key); }
    }
    final List<Fact> identity, relations, constraints, indexes, functions, triggers, tableAcl, schemaAcl, roleFacts;

    private enum Scope { STRUCTURE, ACCESS, ROLES, LIVE }

    private BulkCatalogPhase(Connection c, BulkExecutionRoleConfiguration roles, Scope scope) throws SQLException {
        var reader = new Reader(c, scope == Scope.LIVE);
        identity = scope == Scope.LIVE ? reader.read("""
            select session_user session_identity,current_user effective_identity,
              pg_catalog.to_regprocedure('praxis_bulk.transition_operation_control(text,text,bigint,text,text,text)') is null legacy_cas_absent
            """) : List.of();
        relations = reader.read( scope == Scope.STRUCTURE ? """
            select n.nspname,pg_catalog.pg_get_userbyid(n.nspowner) schema_owner,t.relname,
              pg_catalog.pg_get_userbyid(t.relowner) table_owner,t.relkind,t.relpersistence,t.relrowsecurity,
              a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod) data_type,a.attnotnull,a.atthasdef,
              a.attidentity,a.attgenerated,pg_catalog.pg_get_expr(d.adbin,d.adrelid) default_value
            from pg_catalog.pg_namespace n left join pg_catalog.pg_class t on t.relnamespace=n.oid
            left join pg_catalog.pg_attribute a on a.attrelid=t.oid and a.attnum>0 and not a.attisdropped
            left join pg_catalog.pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
            where n.nspname='praxis_bulk'
            """ : """
            select n.nspname,pg_catalog.pg_get_userbyid(n.nspowner) schema_owner,t.relname,
              pg_catalog.pg_get_userbyid(t.relowner) table_owner,t.relkind,t.relpersistence,t.relrowsecurity
            from pg_catalog.pg_namespace n left join pg_catalog.pg_class t on t.relnamespace=n.oid where n.nspname='praxis_bulk'
            """);
        constraints = scope == Scope.STRUCTURE ? reader.read("""
            select t.relname,k.conname,pg_catalog.pg_get_constraintdef(k.oid,false) definition,k.convalidated,
              k.condeferrable,k.condeferred,k.contype,k.conkey::text conkey,k.confkey::text confkey,
              case when k.confrelid=0 then '-' else k.confrelid::regclass::text end foreign_table,
              k.confupdtype,k.confdeltype,k.confmatchtype,i.indisvalid,i.indisready,i.indislive,i.indimmediate
            from pg_catalog.pg_constraint k join pg_catalog.pg_class t on t.oid=k.conrelid
            join pg_catalog.pg_namespace n on n.oid=t.relnamespace left join pg_catalog.pg_index i on i.indexrelid=k.conindid
            where n.nspname='praxis_bulk'
            """) : List.of();
        indexes = scope == Scope.STRUCTURE ? reader.read("""
            select t.relname,i.relname index_name,pg_catalog.pg_get_indexdef(i.oid) definition,x.indkey::text indkey,
              x.indisunique,x.indisprimary,x.indisvalid,x.indisready,x.indislive,
              pg_catalog.pg_get_expr(x.indpred,x.indrelid,false) predicate,x.indexprs is null no_expressions,
              x.indnkeyatts,x.indnatts
            from pg_catalog.pg_index x join pg_catalog.pg_class i on i.oid=x.indexrelid join pg_catalog.pg_class t on t.oid=x.indrelid
            join pg_catalog.pg_namespace n on n.oid=t.relnamespace where n.nspname='praxis_bulk'
            """) : List.of();
        // Each function is one row, including its ACL as an array; no attribute/ACL join multiplication.
        functions = scope != Scope.ROLES ? reader.read("""
            select p.oid,p.proname,p.proname||'('||pg_catalog.pg_get_function_identity_arguments(p.oid)||')' signature,
              p.pronargs,p.prosrc,p.prosecdef,pg_catalog.pg_get_userbyid(p.proowner) owner,p.proconfig,
              p.provolatile,p.proparallel,p.proleakproof,p.prokind,p.prorettype::regtype::text return_type,l.lanname,
              array(select coalesce(r.rolname,'PUBLIC')||'|'||a.privilege_type||'|'||a.is_grantable::text
                from pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a
                left join pg_catalog.pg_roles r on r.oid=a.grantee where a.grantee<>p.proowner) acl
            from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace join pg_catalog.pg_language l on l.oid=p.prolang
            where n.nspname='praxis_bulk'
            """) : List.of();
        triggers = scope != Scope.ROLES ? reader.read("""
            select c.relname,t.tgname,t.tgenabled,t.tgtype,t.tgnargs,t.tgqual is null no_qual,
              t.tgconstraint=0 no_constraint,t.tgoldtable is null no_old_table,t.tgnewtable is null no_new_table,
              pg_catalog.pg_get_triggerdef(t.oid) definition,t.tgattr::text attributes,t.tgargs,t.tgfoid,p.proname,n.nspname function_schema
            from pg_catalog.pg_trigger t join pg_catalog.pg_class c on c.oid=t.tgrelid join pg_catalog.pg_namespace cn on cn.oid=c.relnamespace
            join pg_catalog.pg_proc p on p.oid=t.tgfoid join pg_catalog.pg_namespace n on n.oid=p.pronamespace
            where cn.nspname='praxis_bulk' and not t.tgisinternal order by t.tgname collate "C"
            """) : List.of();
        tableAcl = reader.readTableAcl("""
            select q.relname,coalesce(r.rolname,'PUBLIC') role,q.permission,q.is_grantable,q.grantee=q.relowner is_owner
            from (
              select c.relname,'T:'||a.privilege_type permission,a.is_grantable,a.grantee,c.relowner
              from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
              cross join lateral pg_catalog.aclexplode(coalesce(c.relacl,pg_catalog.acldefault('r',c.relowner))) a where n.nspname='praxis_bulk'
              union all
              select c.relname,'C:'||v.attname||':'||a.privilege_type,a.is_grantable,a.grantee,c.relowner
              from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
              join pg_catalog.pg_attribute v on v.attrelid=c.oid and v.attnum>0 and not v.attisdropped
              cross join lateral pg_catalog.aclexplode(v.attacl) a where n.nspname='praxis_bulk'
            ) q left join pg_catalog.pg_roles r on r.oid=q.grantee
            """);
        schemaAcl = scope != Scope.ACCESS ? reader.read("""
            select coalesce(r.rolname,'PUBLIC') role,a.privilege_type,a.is_grantable,a.grantee=n.nspowner is_owner
            from pg_catalog.pg_namespace n cross join lateral pg_catalog.aclexplode(coalesce(n.nspacl,pg_catalog.acldefault('n',n.nspowner))) a
            left join pg_catalog.pg_roles r on r.oid=a.grantee where n.nspname='praxis_bulk'
            """) : List.of();
        var configured = new LinkedHashSet<>(roles.runtimeGranteeRoles());
        configured.addAll(roles.controlPlaneGranteeRoles()); configured.addAll(roles.retentionExecutorMembers());
        var roots = new LinkedHashSet<>(configured); roots.add("praxis_bulk_retention_executor");
        roleFacts = readRoles(reader, configured, roots, roles.expectedSchemaOwnerRole());
        reader.finish();
    }

    /** One JDBC multiresult execution, fresh on this exact live operation connection. */
    static BulkCatalogPhase captureLive(Connection c, BulkExecutionRoleConfiguration roles) throws SQLException {
        return new BulkCatalogPhase(c, roles, Scope.LIVE);
    }

    static BulkCatalogPhase capture(Connection c, BulkExecutionRoleConfiguration roles) throws SQLException {
        return new BulkCatalogPhase(c, roles, Scope.STRUCTURE);
    }

    /** Restricted runtime/control validation: no columns, defaults, constraints, indexes or owner rows. */
    static BulkCatalogPhase captureAccess(Connection c, BulkExecutionRoleConfiguration roles) throws SQLException {
        return new BulkCatalogPhase(c, roles, Scope.ACCESS);
    }

    /** V5 role checks need only inventories, ACLs and membership facts. */
    static BulkCatalogPhase captureRoles(Connection c, BulkExecutionRoleConfiguration roles) throws SQLException {
        return new BulkCatalogPhase(c, roles, Scope.ROLES);
    }

    List<Fact> table(List<Fact> facts, String table) {
        return facts.stream().filter(f -> table.equals(f.text("relname"))).toList();
    }
    boolean installed(Set<String> tables, String kindMessage, String partialMessage) {
        var found = new LinkedHashSet<String>();
        for (var fact : relations) if (tables.contains(fact.text("relname"))) {
            require("r".equals(fact.text("relkind")), kindMessage); found.add(fact.text("relname"));
        }
        require(found.isEmpty() || found.equals(tables), partialMessage);
        return !found.isEmpty();
    }
    void requireTable(String table) {
        require(!table(relations, table).isEmpty(), "governed lifecycle table is absent from ACL snapshot: " + table);
    }
    Set<String> privileges(String table, String role) {
        requireTable(table);
        var result = new LinkedHashSet<String>();
        for (var f : table(tableAcl, table)) if (role.equals(f.text("role"))) {
            require(!f.flag("is_grantable"), "grant options are forbidden on governed lifecycle tables");
            result.add(f.text("permission"));
        }
        return result;
    }
    Map<String, Set<String>> allPrivileges(String role) {
        var result = new LinkedHashMap<String, Set<String>>();
        for (var f : tableAcl) if (!f.flag("is_owner") && role.equals(f.text("role"))) {
            require(!f.flag("is_grantable"), "V19 table grant option forbidden");
            result.computeIfAbsent(f.text("relname"), x -> new LinkedHashSet<>()).add(f.text("permission"));
        }
        return result;
    }
    Fact function(String signature) {
        var matches = functions.stream().filter(f -> signature.equals(f.text("signature"))).toList();
        require(matches.size() == 1, "bulk function absent or ambiguous: " + signature);
        return matches.get(0);
    }
    List<Fact> facts(String kind) { return roleFacts.stream().filter(f -> kind.equals(f.text("kind"))).toList(); }
    long count(String kind) {
        var found = facts(kind); require(found.size() == 1, "bulk role fact absent or ambiguous: " + kind);
        return found.get(0).number("amount");
    }
    Set<String> members(Set<String> roots, String adminMessage) {
        var result = new LinkedHashSet<String>();
        for (var f : facts("member")) if (roots.contains(f.text("root"))) {
            require(!f.flag("admin"), adminMessage); result.add(f.text("child"));
        }
        return result;
    }
    private static List<Fact> readRoles(Reader reader, Set<String> configured, Set<String> roots, String owner) throws SQLException {
        String predicate = BulkExecutionMigrator.MANAGED_ADMIN_MEMBERSHIP_PREDICATE
                .replace("count(*)", "pg_catalog.count(*)");
        String sql = """
            with recursive inputs as (select ?::text[] configured,?::text[] roots,?::text owner),
            inherited(root,child) as (
              select m.member,m.roleid from pg_catalog.pg_auth_members m where m.member=any(select oid from pg_catalog.pg_roles,inputs where rolname=any(configured))
              union select i.root,m.roleid from inherited i join pg_catalog.pg_auth_members m on m.member=i.child),
            membership(root,member,admin_option) as (
              select m.roleid,m.member,m.admin_option from pg_catalog.pg_auth_members m
              where m.roleid=any(select oid from pg_catalog.pg_roles,inputs where rolname=any(roots)) and not (%s)
              union select p.root,m.member,m.admin_option from pg_catalog.pg_auth_members m join membership p on m.roleid=p.member where not (%s)),
            owner_members(member) as (
              select m.member from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.roleid,inputs where r.rolname=inputs.owner
              union select m.member from pg_catalog.pg_auth_members m join owner_members p on m.roleid=p.member)
            select 'configured' kind,rolname root,null::text child,false admin,0::bigint amount from pg_catalog.pg_roles,inputs
              where rolname=any(configured) and not rolsuper and not rolcreatedb and not rolcreaterole and not rolreplication and not rolbypassrls
            union all select 'inherited',r.rolname,ch.rolname,false,0 from inherited i join pg_catalog.pg_roles r on r.oid=i.root join pg_catalog.pg_roles ch on ch.oid=i.child
            union all select 'member',r.rolname,ch.rolname,pg_catalog.bool_or(m.admin_option),0 from membership m join pg_catalog.pg_roles r on r.oid=m.root join pg_catalog.pg_roles ch on ch.oid=m.member group by r.rolname,ch.rolname
            union all select 'owner_members',null,null,false,pg_catalog.count(*) from owner_members
            union all select 'internal_attributes',null,null,false,pg_catalog.count(*) from pg_catalog.pg_roles where rolname in ('praxis_bulk_retention_owner','praxis_bulk_retention_executor','praxis_bulk_control_owner')
              and not rolcanlogin and not rolinherit and not rolsuper and not rolcreatedb and not rolcreaterole and not rolreplication and not rolbypassrls
            union all select 'internal_topology',null,null,false,pg_catalog.count(*) from pg_catalog.pg_auth_members m join pg_catalog.pg_roles granted on granted.oid=m.roleid join pg_catalog.pg_roles member on member.oid=m.member
              where (granted.rolname in ('praxis_bulk_retention_owner','praxis_bulk_control_owner') or member.rolname in ('praxis_bulk_retention_owner','praxis_bulk_retention_executor','praxis_bulk_control_owner')) and not (%s)
            union all select 'capacity_attributes',null,null,false,pg_catalog.count(*) from pg_catalog.pg_roles where rolname='praxis_bulk_capacity_owner'
              and not rolcanlogin and not rolinherit and not rolsuper and not rolcreatedb and not rolcreaterole and not rolreplication and not rolbypassrls
            union all select 'capacity_membership',null,null,false,pg_catalog.count(*) from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.roleid or r.oid=m.member
              where r.rolname='praxis_bulk_capacity_owner' and not (%s)
            """.formatted(predicate, predicate, predicate, predicate);
        return reader.roles(sql, configured, roots, owner);
    }

    /** Only constant catalog SQL is combined; all external role values remain parameters. */
    private static final class Reader {
        private final Connection connection;
        private final boolean batch;
        private final List<String> queries = new ArrayList<>();
        private final List<List<Fact>> results = new ArrayList<>();
        private Set<String> configured, roots;
        private String owner;
        Reader(Connection connection, boolean batch) { this.connection = connection; this.batch = batch; }
        List<Fact> readTableAcl(String sql) throws SQLException {
            if (!batch) return read(sql);
            // Compress repeated identities only. Grant options, ownership, column grants and
            // duplicate privileges remain in this invocation's result, without an allowlist filter.
            return read("select relname,role,is_grantable,is_owner,"
                    + "array_agg(permission order by permission) permissions from (" + sql
                    + ") grants group by relname,role,is_grantable,is_owner");
        }
        List<Fact> read(String sql) throws SQLException {
            if (!batch) {
                try (var s = connection.createStatement(); var r = s.executeQuery(sql)) { return BulkCatalogPhase.read(r); }
            }
            // Fresh server digests compress only byte-identical packaged bodies. All existing
            // validators still see the exact original prosrc, including on a cache miss.
            if (sql.contains("p.prosrc")) sql = sql.replace("p.prosrc", """
                case when pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8')),'hex')
                    =any(?::text[]) then null else p.prosrc end prosrc,
                pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8')),'hex') prosrc_digest
                """);
            var facts = new ArrayList<Fact>(); queries.add(sql); results.add(facts);
            return Collections.unmodifiableList(facts);
        }
        List<Fact> roles(String sql, Set<String> configured, Set<String> roots, String owner) throws SQLException {
            this.configured = configured; this.roots = roots; this.owner = owner;
            if (batch) return read(sql);
            try (var statement = connection.prepareStatement(sql)) {
                java.sql.Array configuredArray = null;
                java.sql.Array rootsArray = null;
                try {
                    configuredArray = connection.createArrayOf("text", configured.toArray());
                    rootsArray = connection.createArrayOf("text", roots.toArray());
                    statement.setArray(1, configuredArray); statement.setArray(2, rootsArray); statement.setString(3, owner);
                    try (var rows = statement.executeQuery()) { return BulkCatalogPhase.read(rows); }
                } finally {
                    try { if (configuredArray != null) configuredArray.free(); }
                    finally { if (rootsArray != null) rootsArray.free(); }
                }
            }
        }
        void finish() throws SQLException {
            if (!batch) return;
            require(queries.size() == 7, "Live catalog query inventory differs");
            try (var statement = connection.prepareStatement(String.join(";\n", queries))) {
                var bodyDigests = connection.createArrayOf("text", BulkCatalogFunctionBodies.packaged().keySet().toArray());
                java.sql.Array configuredArray = null;
                java.sql.Array rootsArray = null;
                try {
                    configuredArray = connection.createArrayOf("text", configured.toArray());
                    rootsArray = connection.createArrayOf("text", roots.toArray());
                    statement.setArray(1, bodyDigests); statement.setArray(2, configuredArray); statement.setArray(3, rootsArray); statement.setString(4, owner);
                    boolean result = statement.execute();
                    for (int i = 0; i < results.size(); i++) {
                        require(result, "Live catalog result set absent or replaced by update count: " + i);
                        try (var rows = statement.getResultSet()) {
                            require(rows != null, "Live catalog result set is null: " + i);
                            for (var fact : BulkCatalogPhase.read(rows)) {
                                if (i==2) {
                                    var values = new LinkedHashMap<>(fact.values());
                                    values.put("prosrc", BulkCatalogFunctionBodies.restore(fact.text("prosrc_digest"),fact.text("prosrc")));
                                    results.get(i).add(new Fact(Collections.unmodifiableMap(values)));
                                } else if (i==4) results.get(i).addAll(expandTableAcl(fact));
                                else results.get(i).add(fact);
                            }
                        }
                        result = statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT);
                    }
                    require(!result && statement.getUpdateCount() == -1, "Live catalog returned unexpected extra results");
                } finally {
                    try { if (configuredArray != null) configuredArray.free(); }
                    finally {
                        try { if (rootsArray != null) rootsArray.free(); }
                        finally { if (bodyDigests != null) bodyDigests.free(); }
                    }
                }
            }
        }
    }
    static List<Fact> expandTableAcl(Fact grouped) {
        require(grouped.value("permissions") instanceof Object[], "Live table ACL permissions are absent or malformed");
        Object[] permissions = (Object[]) grouped.value("permissions");
        require(permissions.length > 0, "Live table ACL permissions are empty");
        var expanded = new ArrayList<Fact>();
        for (Object permission : permissions) {
            require(permission instanceof String, "Live table ACL permission is not text");
            var values = new LinkedHashMap<>(grouped.values());
            values.remove("permissions");
            values.put("permission", permission);
            expanded.add(new Fact(Collections.unmodifiableMap(values)));
        }
        return List.copyOf(expanded);
    }
    static List<Fact> read(java.sql.ResultSet r) throws SQLException {
        var result = new ArrayList<Fact>(); var metadata = r.getMetaData();
        while (r.next()) {
            var values = new LinkedHashMap<String,Object>();
            for (int i=1;i<=metadata.getColumnCount();i++) {
                Object value = r.getObject(i);
                if (value instanceof java.sql.Array array) { value = array.getArray(); array.free(); }
                values.put(metadata.getColumnLabel(i), value);
            }
            result.add(new Fact(Collections.unmodifiableMap(values)));
        }
        return List.copyOf(result);
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
