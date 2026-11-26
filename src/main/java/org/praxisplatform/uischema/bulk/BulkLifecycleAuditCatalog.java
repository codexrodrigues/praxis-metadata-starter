package org.praxisplatform.uischema.bulk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Source-owned V21 lifecycle audit and atomic deployment-suspension catalog validation.
 * Keeps control-owner least privilege sealed behind bounded SECURITY DEFINER functions.
 */
final class BulkLifecycleAuditCatalog {
    static final String OWNER = "praxis_bulk_control_owner";
    static final String AUDIT = "praxis_bulk_lifecycle_audit";
    static final String BOOTSTRAP = "praxis_bulk_lifecycle_audit_bootstrap";
    static final String INDEX = "praxis_bulk_lifecycle_audit_ns_executed_idx";
    static final Set<String> TABLES = Set.of(AUDIT, BOOTSTRAP);

    static final String SUSPEND_FN = "suspend_operation_and_publication(p_namespace_id text, p_deployment_id text, p_operation_id text, p_expected_generation bigint, p_expected_publication_generation bigint)";
    static final String RECORD_FN = "record_lifecycle_audit(p_audit_id uuid, p_namespace_id text, p_deployment_id text, p_operation_id text, p_intent text, p_actor text, p_realm text, p_previous_generation bigint, p_resulting_generation bigint, p_resulting_state text, p_correlation_id text)";
    static final String READ_FN = "read_lifecycle_audit(p_namespace_id text, p_limit integer)";
    static final String PROTECT_BOOTSTRAP_FN = "protect_lifecycle_audit_bootstrap()";
    static final String REJECT_AUDIT_FN = "reject_lifecycle_audit_mutation()";

    static final Set<String> FUNCTIONS = Set.of(
            PROTECT_BOOTSTRAP_FN,
            REJECT_AUDIT_FN,
            SUSPEND_FN,
            RECORD_FN,
            READ_FN);

    private static final Set<String> DEFINER_NAMES = Set.of(
            "suspend_operation_and_publication",
            "record_lifecycle_audit",
            "read_lifecycle_audit");

    private record Trigger(String table, String function, int type) { }

    private static final Map<String, Trigger> TRIGGERS = Map.ofEntries(
            Map.entry("praxis_bulk_lifecycle_audit_bootstrap_protect",
                    new Trigger(BOOTSTRAP, "protect_lifecycle_audit_bootstrap", 27)),
            Map.entry("praxis_bulk_lifecycle_audit_immutable",
                    new Trigger(AUDIT, "reject_lifecycle_audit_mutation", 27)));

    private BulkLifecycleAuditCatalog() { }

    static Set<String> triggerKeys() {
        var keys = new LinkedHashSet<String>();
        TRIGGERS.forEach((name, trigger) -> keys.add(trigger.table() + "." + name));
        return keys;
    }

    static String source() {
        try (var stream = BulkLifecycleAuditCatalog.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V21__bulk_lifecycle_audit.sql")) {
            if (stream == null) throw new IllegalStateException("V21 migration is absent");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Unable to read V21 source", unavailable);
        }
    }

    static String functionBody(BulkMigrationLineage source, String name) {
        return functionBody(source.sql(21), name);
    }

    private static String functionBody(String sql, String name) {
        var matcher = Pattern.compile("(?is)create\\s+(?:or\\s+replace\\s+)?function\\s+praxis_bulk\\."
                + Pattern.quote(name) + "\\s*\\([^)]*\\).*?\\bas\\s*\\$\\$(.*?)\\$\\$\\s*;").matcher(sql);
        require(matcher.find(), "V21 function source absent: " + name);
        String body = matcher.group(1);
        require(!matcher.find(), "V21 function duplicated: " + name);
        return body;
    }

    static boolean installed(Connection c) throws SQLException {
        var found = new LinkedHashSet<String>();
        try (var s = c.prepareStatement(
                "select relname,relkind from pg_catalog.pg_class where relnamespace='praxis_bulk'::regnamespace and relname=any(?::text[])")) {
            s.setArray(1, c.createArrayOf("text", TABLES.toArray()));
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    require("r".equals(r.getString(2)), "V21 table kind differs");
                    found.add(r.getString(1));
                }
            }
        }
        require(found.isEmpty() || found.equals(TABLES), "V21 physical lifecycle audit is partial");
        return !found.isEmpty();
    }

    static boolean installed(BulkCatalogPhase catalog) {
        return catalog.installed(TABLES, "V21 table kind differs", "V21 physical lifecycle audit is partial");
    }

    static String phase(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("select bootstrap_version,phase from praxis_bulk." + BOOTSTRAP)) {
            require(r.next() && r.getInt(1) == 21, "V21 bootstrap absent");
            String phase = r.getString(2);
            require(!r.next() && Set.of("PENDING", "COMPLETE").contains(phase), "V21 bootstrap invalid");
            return phase;
        }
    }

    static void bootstrap(Connection c, BulkExecutionRoleConfiguration roles) throws SQLException {
        require(!c.getAutoCommit(), "V21 bootstrap requires an owner transaction");
        String initialRole;
        try (var s = c.createStatement(); var r = s.executeQuery("select current_user,current_setting('role')")) {
            require(r.next() && roles.expectedSchemaOwnerRole().equals(r.getString(1)),
                    "V21 bootstrap requires explicit owner credential");
            initialRole = r.getString(2);
            require("none".equals(initialRole) || roles.expectedSchemaOwnerRole().equals(initialRole),
                    "V21 bootstrap owner role differs");
            require(!r.next(), "V21 bootstrap owner ambiguous");
        }
        try (var s = c.createStatement(); var r = s.executeQuery(
                "select bootstrap_version,phase from praxis_bulk." + BOOTSTRAP + " for update")) {
            require(r.next() && r.getInt(1) == 21, "V21 bootstrap absent");
            String phase = r.getString(2);
            require(!r.next(), "V21 bootstrap ambiguous");
            if ("COMPLETE".equals(phase)) {
                validate(c, roles, true);
                return;
            }
            require("PENDING".equals(phase), "V21 bootstrap changed");
        }
        validate(c, roles, false);

        if (!roles.runtimeGranteeRoles().isEmpty() || !roles.controlPlaneGranteeRoles().isEmpty()) {
            try (var s = c.createStatement()) {
                s.execute("grant " + quotedRole(OWNER) + " to " + quotedRole(roles.expectedSchemaOwnerRole())
                        + " with inherit false, set true granted by current_user");
                s.execute("set local role " + quotedRole(OWNER));
                for (String role : new java.util.TreeSet<>(roles.controlPlaneGranteeRoles())) {
                    String qRole = quotedRole(role);
                    s.execute("grant execute on function praxis_bulk.suspend_operation_and_publication(text,text,text,bigint,bigint),"
                            + "praxis_bulk.record_lifecycle_audit(uuid,text,text,text,text,text,text,bigint,bigint,text,text) to "
                            + qRole);
                }
                for (String role : new java.util.TreeSet<>(roles.runtimeGranteeRoles())) {
                    s.execute("grant execute on function praxis_bulk.read_lifecycle_audit(text,integer) to "
                            + quotedRole(role));
                }
                s.execute("set local role " + ("none".equals(initialRole) ? "none" : quotedRole(initialRole)));
                s.execute("revoke " + quotedRole(OWNER) + " from " + quotedRole(roles.expectedSchemaOwnerRole())
                        + " granted by current_user");
            }
        }

        validate(c, roles, true);
        try (var s = c.createStatement()) {
            require(s.executeUpdate("update praxis_bulk." + BOOTSTRAP
                    + " set phase='COMPLETE' where bootstrap_version=21 and phase='PENDING'") == 1,
                    "V21 completion lost");
        }
    }

    static void validate(Connection c, BulkExecutionRoleConfiguration roles, boolean complete) throws SQLException {
        var catalog = BulkCatalogPhase.capture(c, roles);
        require(installed(catalog), "V21 lifecycle audit absent");
        columns(catalog, BOOTSTRAP, Map.of("bootstrap_version", "smallint|true", "phase", "text|true"));
        var auditCols = new LinkedHashMap<String, String>();
        auditCols.put("audit_id", "uuid|true");
        auditCols.put("namespace_id", "text|true");
        auditCols.put("deployment_id", "text|true");
        auditCols.put("operation_id", "text|true");
        auditCols.put("intent", "text|true");
        auditCols.put("actor", "text|true");
        auditCols.put("realm", "text|true");
        auditCols.put("previous_generation", "bigint|false");
        auditCols.put("resulting_generation", "bigint|true");
        auditCols.put("resulting_state", "text|true");
        auditCols.put("correlation_id", "text|true");
        auditCols.put("executed_at", "timestamp with time zone|true");
        columns(catalog, AUDIT, auditCols);
        functions(catalog, roles, complete, BulkMigrationLineage.select(c));
        triggers(catalog);
        index(catalog);
        roles(catalog, roles);
        for (String table : TABLES) {
            constraints(catalog, table, constraints(table));
        }
    }

    static void validateAccess(BulkCatalogPhase catalog, BulkExecutionRoleConfiguration roles,
            BulkMigrationLineage source) {
        require(installed(catalog), "V21 lifecycle audit absent");
        functions(catalog, roles, true, source);
        triggers(catalog);
        roles(catalog, roles);
    }

    private static void columns(BulkCatalogPhase catalog, String table, Map<String, String> expected) {
        var actual = new LinkedHashMap<String, String>();
        for (var row : catalog.table(catalog.relations, table)) {
            if (row.text("attname") == null) continue;
            require(!row.flag("atthasdef") && "".equals(row.text("attidentity")) && "".equals(row.text("attgenerated"))
                    && "p".equals(row.text("relpersistence")) && !row.flag("relrowsecurity")
                    && "r".equals(row.text("relkind")), "V21 column attributes differ");
            actual.put(row.text("attname"), row.text("data_type") + "|" + row.flag("attnotnull"));
        }
        require(expected.equals(actual), "V21 columns differ: " + table + " actual=" + actual);
    }

    private static void functions(BulkCatalogPhase catalog, BulkExecutionRoleConfiguration roles, boolean complete,
            BulkMigrationLineage source) {
        for (String signature : FUNCTIONS) {
            String name = signature.substring(0, signature.indexOf('('));
            var row = catalog.function(signature);
            boolean definer = DEFINER_NAMES.contains(name);
            String expectedReturn = switch (name) {
                case "suspend_operation_and_publication", "read_lifecycle_audit" -> "record";
                case "record_lifecycle_audit" -> "timestamp with time zone";
                default -> "trigger";
            };
            require(normalize(functionBody(source, name)).equals(normalize(row.text("prosrc")))
                    && row.flag("prosecdef") == definer
                    && (definer ? OWNER : roles.expectedSchemaOwnerRole()).equals(row.text("owner"))
                    && java.util.Arrays.equals((String[]) row.value("proconfig"),
                            new String[] {"search_path=pg_catalog, pg_temp"})
                    && "v".equals(row.text("provolatile"))
                    && "u".equals(row.text("proparallel"))
                    && !row.flag("proleakproof")
                    && "f".equals(row.text("prokind"))
                    && expectedReturn.equals(row.text("return_type"))
                    && "plpgsql".equals(row.text("lanname")), "V21 function attributes/source differ: " + name);

            var expected = new LinkedHashSet<String>();
            if (complete) {
                if (name.equals("suspend_operation_and_publication") || name.equals("record_lifecycle_audit")) {
                    roles.controlPlaneGranteeRoles().forEach(role -> expected.add(role + "|EXECUTE"));
                } else if (name.equals("read_lifecycle_audit")) {
                    roles.runtimeGranteeRoles().forEach(role -> expected.add(role + "|EXECUTE"));
                }
            }
            var actual = new LinkedHashSet<String>();
            for (String grant : (String[]) row.value("acl")) {
                int separator = grant.lastIndexOf('|');
                require("false".equals(grant.substring(separator + 1)), "V21 grant option forbidden");
                actual.add(grant.substring(0, separator));
            }
            require(expected.equals(actual), "V21 function ACL differs: " + name + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void triggers(BulkCatalogPhase catalog) {
        for (var entry : TRIGGERS.entrySet()) {
            var expected = entry.getValue();
            var found = catalog.table(catalog.triggers, expected.table()).stream()
                    .filter(r -> entry.getKey().equals(r.text("tgname"))).toList();
            require(found.size() == 1, "V21 trigger differs: " + entry.getKey());
            var row = found.get(0);
            require(row.number("tgtype") == expected.type() && "O".equals(row.text("tgenabled"))
                    && row.number("tgnargs") == 0 && row.flag("no_qual") && row.flag("no_constraint")
                    && row.flag("no_old_table") && row.flag("no_new_table")
                    && expected.function().equals(row.text("proname")) && "praxis_bulk".equals(row.text("function_schema"))
                    && ((byte[]) row.value("tgargs")).length == 0, "V21 trigger differs: " + entry.getKey());
        }
    }

    private static void index(BulkCatalogPhase catalog) {
        var found = catalog.table(catalog.indexes, AUDIT).stream()
                .filter(r -> INDEX.equals(r.text("index_name"))).toList();
        require(found.size() == 1, "V21 audit index is absent");
        var row = found.get(0);
        String expectedDef = "CREATE INDEX praxis_bulk_lifecycle_audit_ns_executed_idx ON praxis_bulk.praxis_bulk_lifecycle_audit USING btree (namespace_id, executed_at DESC, audit_id DESC)";
        require(!row.flag("indisunique") && !row.flag("indisprimary")
                && row.flag("indisvalid") && row.flag("indisready") && row.flag("indislive")
                && row.flag("no_expressions") && row.text("predicate") == null
                && row.number("indnatts") == 3 && row.number("indnkeyatts") == 3
                && normalize(expectedDef).equals(normalize(row.text("definition"))),
                "V21 audit index definition differs: " + row.text("definition"));
    }

    private static void roles(BulkCatalogPhase catalog, BulkExecutionRoleConfiguration roles) {
        require(catalog.allPrivileges(OWNER).getOrDefault(AUDIT, Set.of()).equals(Set.of("T:SELECT", "T:INSERT")),
                "V21 control-owner audit privileges differ");
        require(catalog.allPrivileges(OWNER).getOrDefault(BOOTSTRAP, Set.of()).isEmpty(),
                "V21 control-owner bootstrap privileges forbidden");
        for (String table : TABLES) {
            require(catalog.allPrivileges("PUBLIC").getOrDefault(table, Set.of()).isEmpty(), "V21 PUBLIC access forbidden");
            for (String role : roles.runtimeGranteeRoles()) {
                require(catalog.allPrivileges(role).getOrDefault(table, Set.of()).isEmpty(),
                        "V21 runtime table access forbidden: " + table);
            }
            for (String role : roles.controlPlaneGranteeRoles()) {
                require(catalog.allPrivileges(role).getOrDefault(table, Set.of()).isEmpty(),
                        "V21 control-plane direct table access forbidden: " + table);
            }
            require(catalog.allPrivileges("praxis_bulk_retention_owner").getOrDefault(table, Set.of()).isEmpty(),
                    "V21 retention-owner table access forbidden");
            require(catalog.allPrivileges("praxis_bulk_retention_executor").getOrDefault(table, Set.of()).isEmpty(),
                    "V21 retention-executor table access forbidden");
            require(catalog.allPrivileges(BulkCapacityOccupancyCatalog.OWNER).getOrDefault(table, Set.of()).isEmpty(),
                    "V21 capacity-owner table access forbidden");
        }
    }

    private static void constraints(BulkCatalogPhase catalog, String table, Map<String, String> expected) {
        catalog.requireTable(table);
        var actual = new LinkedHashMap<String, String>();
        for (var r : catalog.table(catalog.constraints, table)) {
            require(r.flag("convalidated") && !r.flag("condeferrable") && !r.flag("condeferred")
                    && (r.value("indisvalid") == null || r.flag("indisvalid") && r.flag("indisready")
                    && r.flag("indislive") && r.flag("indimmediate")), "V21 constraint attributes differ");
            actual.put(r.text("conname"), normalize(r.text("definition")));
        }
        var normalized = new LinkedHashMap<String, String>();
        expected.forEach((name, definition) -> normalized.put(name, normalize(definition)));
        require(normalized.equals(actual), "V21 constraints differ: " + table + " actual=" + actual);
    }

    private static Map<String, String> constraints(String table) {
        return switch (table) {
            case BOOTSTRAP -> Map.of(
                    "praxis_bulk_lifecycle_audit_bootstrap_pkey", "PRIMARY KEY (bootstrap_version)",
                    "lifecycle_audit_bootstrap_version_check", "CHECK ((bootstrap_version = 21))",
                    "praxis_bulk_lifecycle_audit_bootstrap_phase_check",
                    "CHECK ((phase = ANY (ARRAY['PENDING'::text, 'COMPLETE'::text])))");
            case AUDIT -> Map.ofEntries(
                    Map.entry("praxis_bulk_lifecycle_audit_pkey", "PRIMARY KEY (audit_id)"),
                    Map.entry("praxis_bulk_lifecycle_audit_namespace_fkey",
                            "FOREIGN KEY (namespace_id) REFERENCES praxis_bulk.praxis_bulk_namespace_binding(namespace_id) ON DELETE RESTRICT"),
                    Map.entry("praxis_bulk_lifecycle_audit_deployment_fkey",
                            "FOREIGN KEY (deployment_id) REFERENCES praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) ON DELETE RESTRICT"),
                    Map.entry("praxis_bulk_lifecycle_audit_operation_check",
                            "CHECK (((operation_id <> ''::text) AND (operation_id !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'::text) AND (char_length(operation_id) <= 200)))"),
                    Map.entry("praxis_bulk_lifecycle_audit_intent_check",
                            "CHECK ((intent = ANY (ARRAY['PUBLISH'::text, 'RECONCILE'::text, 'SUSPEND'::text, 'PUBLISH_ALL'::text, 'RECONCILE_ALL'::text, 'SUSPEND_ALL'::text])))"),
                    Map.entry("praxis_bulk_lifecycle_audit_actor_check",
                            "CHECK (((actor <> ''::text) AND (actor !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'::text) AND (char_length(actor) <= 128)))"),
                    Map.entry("praxis_bulk_lifecycle_audit_realm_check",
                            "CHECK (((realm <> ''::text) AND (realm !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'::text) AND (char_length(realm) <= 64)))"),
                    Map.entry("praxis_bulk_lifecycle_audit_correlation_check",
                            "CHECK (((correlation_id <> ''::text) AND (correlation_id !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'::text) AND (char_length(correlation_id) <= 128)))"),
                    Map.entry("praxis_bulk_lifecycle_audit_generation_check",
                            "CHECK ((((previous_generation IS NULL) OR (previous_generation >= 0)) AND (resulting_generation >= 0)))"),
                    Map.entry("praxis_bulk_lifecycle_audit_state_check",
                            "CHECK ((resulting_state = ANY (ARRAY['READY'::text, 'SUSPENDED'::text, 'UNCOMPOSED'::text, 'PUBLISHED'::text])))"));
            default -> throw new IllegalArgumentException("Unknown V21 table: " + table);
        };
    }

    private static String quotedRole(String role) {
        return '"' + role.replace("\"", "\"\"") + '"';
    }

    private static String normalize(String value) {
        var out = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'') {
                out.append(ch);
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                    out.append(value.charAt(++i));
                } else {
                    quoted = !quoted;
                }
            } else if (quoted || !Character.isWhitespace(ch)) {
                out.append(quoted ? ch : Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
