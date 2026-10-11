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

/** Source-owned V19 catalog and least-privilege attestation, never runtime authority. */
final class BulkCapacityOccupancyCatalog {
    static final String OWNER = "praxis_bulk_capacity_owner";
    static final String SLOT = "praxis_bulk_capacity_slot";
    static final String HISTORY = "praxis_bulk_capacity_occupation";
    static final String BOOTSTRAP = "praxis_bulk_capacity_occupancy_bootstrap";
    static final Set<String> TABLES = Set.of(SLOT, HISTORY, BOOTSTRAP);
    static final String CLAIM = "claim_capacity_execution(p_execution_id uuid, p_namespace_id text, p_worker_id text, p_active_token_id uuid, p_expected_epoch bigint)";
    static final Set<String> FUNCTIONS = Set.of("protect_capacity_occupancy_bootstrap()", "create_capacity_slot()",
            "lock_capacity_marker()", "capacity_marker_statement_fence()", "guard_capacity_slot()",
            "guard_capacity_occupation()", "guard_capacity_execution()", "guard_capacity_evidence()",
            "materialize_capacity_execution()", CLAIM);
    static final Map<String, String> REPLACED = Map.of(
            "guard_new_bulk_admission", "V19", "protect_praxis_bulk_execution_binding", "V19",
            "protect_allocation_transition", "V19", "validate_allocation_binding", "V19",
            "purge_terminal_execution", "V19", "expire_unconsumed_proposal", "V19",
            "guard_atomic_attempt_transition", "V19", "release_active_allocation_on_terminal", "V19");

    private record Trigger(String table, String function, int type) { }

    private static final Map<String, Trigger> TRIGGERS = Map.ofEntries(
            Map.entry("praxis_bulk_capacity_occupancy_bootstrap_protect",
                    new Trigger(BOOTSTRAP, "protect_capacity_occupancy_bootstrap", 27)),
            Map.entry("praxis_bulk_capacity_installation_create_slot",
                    new Trigger("praxis_bulk_capacity_installation", "create_capacity_slot", 5)),
            Map.entry("praxis_bulk_execution_capacity_statement",
                    new Trigger("praxis_bulk_execution", "capacity_marker_statement_fence", 22)),
            Map.entry("praxis_bulk_receipt_capacity_statement",
                    new Trigger("praxis_bulk_item_receipt", "capacity_marker_statement_fence", 6)),
            Map.entry("praxis_bulk_admission_capacity_statement",
                    new Trigger("praxis_bulk_admission", "capacity_marker_statement_fence", 6)),
            Map.entry("praxis_bulk_capacity_slot_guard",
                    new Trigger(SLOT, "guard_capacity_slot", 31)),
            Map.entry("praxis_bulk_capacity_occupation_guard",
                    new Trigger(HISTORY, "guard_capacity_occupation", 31)),
            Map.entry("praxis_bulk_execution_capacity_guard",
                    new Trigger("praxis_bulk_execution", "guard_capacity_execution", 23)),
            Map.entry("praxis_bulk_receipt_capacity_guard",
                    new Trigger("praxis_bulk_item_receipt", "guard_capacity_evidence", 7)),
            Map.entry("praxis_bulk_admission_capacity_guard",
                    new Trigger("praxis_bulk_admission", "guard_capacity_evidence", 7)),
            Map.entry("praxis_bulk_execution_capacity_materialize",
                    new Trigger("praxis_bulk_execution", "materialize_capacity_execution", 21)));
    private static final Set<String> DEFINERS = Set.of("create_capacity_slot", "lock_capacity_marker",
            "capacity_marker_statement_fence", "materialize_capacity_execution", "claim_capacity_execution");

    private BulkCapacityOccupancyCatalog() { }

    static Set<String> triggerKeys() {
        var keys = new LinkedHashSet<String>();
        TRIGGERS.forEach((name, trigger) -> keys.add(trigger.table() + "." + name));
        return keys;
    }

    static String source() {
        try (var stream = BulkCapacityOccupancyCatalog.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V19__bulk_capacity_occupancy.sql")) {
            if (stream == null) throw new IllegalStateException("V19 migration is absent");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Unable to read V19 source");
        }
    }

    static String functionBody(Connection connection, String name) throws SQLException {
        return functionBody(BulkMigrationLineage.select(connection).sql(19), name);
    }

    static String functionBody(BulkMigrationLineage source, String name) {
        return functionBody(source.sql(19), name);
    }

    static String functionBody(String name) {
        return functionBody(source(), name);
    }

    private static String functionBody(String sql, String name) {
        var matcher = Pattern.compile("(?is)create\\s+(?:or\\s+replace\\s+)?function\\s+praxis_bulk\\."
                + Pattern.quote(name) + "\\s*\\([^)]*\\).*?\\bas\\s*\\$\\$(.*?)\\$\\$\\s*;").matcher(sql);
        require(matcher.find(), "V19 function source absent: " + name);
        String body = matcher.group(1);
        require(!matcher.find(), "V19 function duplicated: " + name);
        return body;
    }

    static boolean installed(Connection c) throws SQLException {
        var found = new LinkedHashSet<String>();
        try (var s = c.prepareStatement("select relname,relkind from pg_catalog.pg_class where relnamespace='praxis_bulk'::regnamespace and relname=any(?::text[])")) {
            s.setArray(1, c.createArrayOf("text", TABLES.toArray()));
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    require("r".equals(r.getString(2)), "V19 table kind differs");
                    found.add(r.getString(1));
                }
            }
        }
        require(found.isEmpty() || found.equals(TABLES), "V19 physical occupancy is partial");
        return !found.isEmpty();
    }

    static boolean installed(BulkCatalogPhase catalog) {
        return catalog.installed(TABLES, "V19 table kind differs", "V19 physical occupancy is partial");
    }

    static String phase(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("select bootstrap_version,phase from praxis_bulk." + BOOTSTRAP)) {
            require(r.next() && r.getInt(1) == 19, "V19 bootstrap absent");
            String phase = r.getString(2);
            require(!r.next() && Set.of("PENDING", "COMPLETE").contains(phase), "V19 bootstrap invalid");
            return phase;
        }
    }

    /**
     * Completes the owner-controlled ACL phase in the caller's owned transaction.
     * The schema owner needs native PostgreSQL authority to grant the dedicated
     * function-owner role, just as the V19 DDL does; inherited function ownership
     * is not assumed. Temporary membership and role changes end on success or
     * on the owner transaction's rollback.
     */
    static void bootstrap(Connection c, BulkExecutionRoleConfiguration roles) throws SQLException {
        require(!c.getAutoCommit(), "V19 bootstrap requires an owner transaction");
        String initialRole;
        try (var s = c.createStatement();
             var r = s.executeQuery("select current_user,current_setting('role')")) {
            require(r.next() && roles.expectedSchemaOwnerRole().equals(r.getString(1)),
                    "V19 bootstrap requires explicit owner credential");
            initialRole = r.getString(2);
            require("none".equals(initialRole) || roles.expectedSchemaOwnerRole().equals(initialRole),
                    "V19 bootstrap owner role differs");
            require(!r.next(), "V19 bootstrap owner ambiguous");
        }
        try (var s = c.createStatement(); var r = s.executeQuery("select bootstrap_version,phase from praxis_bulk." + BOOTSTRAP + " for update")) {
            require(r.next() && r.getInt(1) == 19, "V19 bootstrap absent");
            String phase = r.getString(2);
            require(!r.next(), "V19 bootstrap ambiguous");
            if ("COMPLETE".equals(phase)) {
                validate(c, roles, true);
                return;
            }
            require("PENDING".equals(phase), "V19 bootstrap changed");
        }
        validate(c, roles, false);
        // SET LOCAL ROLE and all grants are owned by the caller's transaction.
        // Propagate failures without cleanup SQL in an aborted transaction; the
        // owner transaction rolls back membership, ACLs and role changes together.
        for (String role : new java.util.TreeSet<>(roles.runtimeGranteeRoles())) {
            try (var s = c.createStatement()) {
                s.execute("grant select on praxis_bulk." + SLOT + ",praxis_bulk." + HISTORY
                        + " to " + quotedRole(role));
            }
        }
        if (!roles.runtimeGranteeRoles().isEmpty()) {
            try (var s = c.createStatement()) {
                s.execute("grant " + quotedRole(OWNER) + " to " + quotedRole(roles.expectedSchemaOwnerRole())
                        + " with inherit false, set true granted by current_user");
                s.execute("set local role " + quotedRole(OWNER));
                for (String role : new java.util.TreeSet<>(roles.runtimeGranteeRoles())) {
                    s.execute("grant execute on function praxis_bulk.lock_capacity_marker(),"
                            + "praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint) to " + quotedRole(role));
                }
                // Restore only the transaction-local role: RESET ROLE would also
                // change a pre-existing session role when this transaction commits.
                s.execute("set local role " + ("none".equals(initialRole) ? "none" : quotedRole(initialRole)));
                s.execute("revoke " + quotedRole(OWNER) + " from " + quotedRole(roles.expectedSchemaOwnerRole())
                        + " granted by current_user");
            }
        }
        // Certify the completed ACLs after revocation, before publishing COMPLETE.
        validate(c, roles, true);
        try (var s = c.createStatement()) {
            require(s.executeUpdate("update praxis_bulk." + BOOTSTRAP + " set phase='COMPLETE' where bootstrap_version=19 and phase='PENDING'") == 1,
                    "V19 completion lost");
        }
    }

    private static String quotedRole(String role) {
        return '"' + role.replace("\"", "\"\"") + '"';
    }

    static void validate(Connection c, BulkExecutionRoleConfiguration roles, boolean complete) throws SQLException {
        var catalog = BulkCatalogPhase.capture(c, roles);
        require(installed(catalog), "V19 occupancy absent");
        columns(catalog, SLOT, Map.of("token_id", "uuid|true", "capacity_class", "text|true", "occupancy_sequence",
                "bigint|true",
                "current_execution_id", "uuid|false", "current_owner_epoch", "bigint|false"));
        columns(catalog, HISTORY, Map.of("token_id", "uuid|true", "occupancy_sequence", "bigint|true", "execution_id",
                "uuid|true",
                "owner_epoch", "bigint|true", "acquired_at", "timestamp with time zone|true"));
        columns(catalog, BOOTSTRAP, Map.of("bootstrap_version", "smallint|true", "phase", "text|true"));
        functions(catalog, roles, complete, BulkMigrationLineage.select(c));
        triggers(catalog);
        terminalTriggerOrder(catalog);
        roles(catalog, roles, complete);
        for (String table : TABLES)
            constraints(catalog, table, constraints(table));
        rows(c);
    }

    static void validateAccess(Connection connection, BulkExecutionRoleConfiguration roles,
            BulkMigrationLineage source) throws SQLException {
        validateAccess(BulkCatalogPhase.captureAccess(connection, roles), roles, source);
    }

    static void validateAccess(BulkCatalogPhase catalog, BulkExecutionRoleConfiguration roles,
            BulkMigrationLineage source) {
        require(installed(catalog), "V19 occupancy absent");
        functions(catalog, roles, true, source);
        triggers(catalog);
        terminalTriggerOrder(catalog);
        roles(catalog, roles, true);
    }

    private static void columns(BulkCatalogPhase catalog, String table, Map<String, String> expected) {
        var actual = new LinkedHashMap<String, String>();
        for (var row : catalog.table(catalog.relations, table)) {
            if (row.text("attname") == null) continue;
            require(!row.flag("atthasdef") && "".equals(row.text("attidentity")) && "".equals(row.text("attgenerated"))
                    && "p".equals(row.text("relpersistence")) && !row.flag("relrowsecurity")
                    && "r".equals(row.text("relkind")), "V19 column attributes differ");
            actual.put(row.text("attname"), row.text("data_type") + "|" + row.flag("attnotnull"));
        }
        require(expected.equals(actual), "V19 columns differ: " + table);
    }

    private static void functions(BulkCatalogPhase catalog, BulkExecutionRoleConfiguration roles, boolean complete,
            BulkMigrationLineage source) {
        for (String signature : FUNCTIONS) {
            String name = signature.substring(0, signature.indexOf('('));
            var row = catalog.function(signature);
            require(normalize(functionBody(source, name)).equals(normalize(row.text("prosrc")))
                    && row.flag("prosecdef") == DEFINERS.contains(name)
                    && (DEFINERS.contains(name) ? OWNER : roles.expectedSchemaOwnerRole()).equals(row.text("owner"))
                    && java.util.Arrays.equals((String[]) row.value("proconfig"), new String[] {"search_path=pg_catalog, pg_temp"})
                    && "v".equals(row.text("provolatile")) && "u".equals(row.text("proparallel")) && !row.flag("proleakproof")
                    && "f".equals(row.text("prokind"))
                    && (name.equals("lock_capacity_marker") ? "text" : name.equals("claim_capacity_execution") ? "boolean" : "trigger").equals(row.text("return_type"))
                    && "plpgsql".equals(row.text("lanname")), "V19 function attributes/source differ: " + name);
            var expected = new LinkedHashSet<String>();
            if (name.equals("lock_capacity_marker")) expected.add("praxis_bulk_retention_owner|EXECUTE");
            if (complete && (name.equals("lock_capacity_marker") || name.equals("claim_capacity_execution")))
                roles.runtimeGranteeRoles().forEach(role -> expected.add(role + "|EXECUTE"));
            var actual = new LinkedHashSet<String>();
            for (String grant : (String[]) row.value("acl")) {
                int separator = grant.lastIndexOf('|');
                require("false".equals(grant.substring(separator + 1)), "V19 grant option forbidden");
                actual.add(grant.substring(0, separator));
            }
            require(expected.equals(actual), "V19 function ACL differs: " + name);
        }
    }

    private static void triggers(BulkCatalogPhase catalog) {
        for (var entry : TRIGGERS.entrySet()) {
            var expected = entry.getValue();
            var found = catalog.table(catalog.triggers, expected.table()).stream()
                    .filter(r -> entry.getKey().equals(r.text("tgname"))).toList();
            require(found.size() == 1, "V19 trigger differs: " + entry.getKey());
            var row = found.get(0);
            require(row.number("tgtype") == expected.type() && "O".equals(row.text("tgenabled"))
                    && row.number("tgnargs") == 0 && row.flag("no_qual") && row.flag("no_constraint")
                    && row.flag("no_old_table") && row.flag("no_new_table")
                    && expected.function().equals(row.text("proname")) && "praxis_bulk".equals(row.text("function_schema"))
                    && ((byte[]) row.value("tgargs")).length == 0, "V19 trigger differs: " + entry.getKey());
        }
    }

    /** Captured in PostgreSQL C collation order, preserving the two actual AFTER names. */
    private static void terminalTriggerOrder(BulkCatalogPhase catalog) {
        var actual = catalog.table(catalog.triggers, "praxis_bulk_execution").stream()
                .filter(r -> Set.of("praxis_bulk_execution_capacity_materialize", "praxis_bulk_execution_release_active_allocation").contains(r.text("tgname")))
                .filter(r -> "O".equals(r.text("tgenabled")) && (r.number("tgtype") & 1) == 1
                        && (r.number("tgtype") & 2) == 0 && (r.number("tgtype") & 16) == 16)
                .map(r -> r.text("tgname")).toList();
        require(actual.equals(java.util.List.of("praxis_bulk_execution_capacity_materialize", "praxis_bulk_execution_release_active_allocation")),
                "V19 terminal materialization must precede the allocation release certificate");
    }

    static Map<String, Set<String>> ownerTablePrivileges() {
        var expected = new LinkedHashMap<String, Set<String>>();
        for (String table : Set.of("praxis_bulk_capacity_installation", "praxis_bulk_proposal",
                "praxis_bulk_namespace_binding", "praxis_bulk_deployment_bucket", "praxis_bulk_subject_bucket",
                "praxis_bulk_capacity_marker", "praxis_bulk_execution"))
            expected.put(table, new LinkedHashSet<>(Set.of("T:SELECT")));
        expected.get("praxis_bulk_proposal").add("C:proposal_id:UPDATE");
        for (String table : Set.of("praxis_bulk_namespace_binding", "praxis_bulk_deployment_bucket",
                "praxis_bulk_subject_bucket"))
            expected.get(table).add("C:deployment_id:UPDATE");
        expected.get("praxis_bulk_capacity_marker").add("C:marker_id:UPDATE");
        for (String column : Set.of("status", "owner_id", "owner_epoch", "active_token_id", "updated_at", "terminal_at",
                "terminal_reason_code"))
            expected.get("praxis_bulk_execution").add("C:" + column + ":UPDATE");
        expected.put(SLOT, Set.of("T:SELECT", "T:INSERT", "T:UPDATE"));
        expected.put(HISTORY, Set.of("T:SELECT", "T:INSERT"));
        expected.put("praxis_bulk_allocation", Set.of("T:SELECT", "T:INSERT", "C:state:UPDATE", "C:released_at:UPDATE",
                "C:release_reason:UPDATE"));
        return expected;
    }

    private static void roles(BulkCatalogPhase catalog, BulkExecutionRoleConfiguration roles, boolean complete) {
        require(catalog.count("capacity_attributes") == 1, "V19 owner attributes differ");
        require(catalog.count("capacity_membership") == 0, "V19 owner membership forbidden");
        var ownerActual = new LinkedHashMap<String, Set<String>>();
        for (var entry : catalog.allPrivileges(OWNER).entrySet())
            if (!entry.getValue().isEmpty())
                ownerActual.put(entry.getKey(), entry.getValue());
        require(ownerTablePrivileges().equals(ownerActual), "V19 owner table grants differ");
        for (String table : TABLES) {
            require(catalog.allPrivileges("PUBLIC").getOrDefault(table, Set.of()).isEmpty(), "V19 PUBLIC access forbidden");
            for (String role : roles.runtimeGranteeRoles())
                require(catalog.allPrivileges(role).getOrDefault(table, Set.of()).equals(
                        complete && !table.equals(BOOTSTRAP) ? Set.of("T:SELECT") : Set.of()),
                        "V19 runtime table grants differ");
            for (String role : roles.controlPlaneGranteeRoles())
                require(catalog.allPrivileges(role).getOrDefault(table, Set.of()).isEmpty(),
                        "V19 control-plane access forbidden");
            require(catalog.allPrivileges("praxis_bulk_retention_owner").getOrDefault(table, Set.of()).equals(
                    table.equals(HISTORY) ? Set.of("T:SELECT", "T:DELETE") : table.equals(SLOT) ? Set.of("T:SELECT") : Set.of()),
                    "V19 retention grants differ");
            require(catalog.allPrivileges("praxis_bulk_retention_executor").getOrDefault(table, Set.of()).isEmpty(),
                    "V19 retention executor table access forbidden");
        }
    }

    static void constraints(Connection c, String table, Map<String, String> expected) throws SQLException {
        // Other historical validators keep their narrow query and privilege surface.
        var actual = new LinkedHashMap<String, String>();
        try (var s = c.prepareStatement("""
            select k.conname,pg_get_constraintdef(k.oid),k.convalidated,k.condeferrable,k.condeferred,
                i.indisvalid,i.indisready,i.indislive,i.indimmediate
            from pg_constraint k left join pg_index i on i.indexrelid=k.conindid where k.conrelid=?::regclass
            """)) {
            s.setString(1, "praxis_bulk." + table);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    require(r.getBoolean(3) && !r.getBoolean(4) && !r.getBoolean(5)
                            && (r.getObject(6) == null || r.getBoolean(6) && r.getBoolean(7) && r.getBoolean(8)
                            && r.getBoolean(9)), "V19 constraint attributes differ");
                    actual.put(r.getString(1), normalize(r.getString(2)));
                }
            }
        }
        compareConstraints(table, expected, actual);
    }

    private static void constraints(BulkCatalogPhase catalog, String table, Map<String, String> expected) {
        catalog.requireTable(table);
        var actual = new LinkedHashMap<String, String>();
        for (var r : catalog.table(catalog.constraints, table)) {
            require(r.flag("convalidated") && !r.flag("condeferrable") && !r.flag("condeferred")
                    && (r.value("indisvalid") == null || r.flag("indisvalid") && r.flag("indisready")
                        && r.flag("indislive") && r.flag("indimmediate")), "V19 constraint attributes differ");
            actual.put(r.text("conname"), normalize(r.text("definition")));
        }
        compareConstraints(table, expected, actual);
    }

    private static void compareConstraints(String table, Map<String, String> expected, Map<String, String> actual) {
        var normalized = new LinkedHashMap<String, String>();
        expected.forEach((name, definition) -> normalized.put(name, normalize(definition)));
        require(normalized.equals(actual), "V19 constraints differ: " + table + " actual=" + actual);
    }

    static Map<String, String> constraints(String table) {
        return switch (table) {
            case SLOT -> Map.ofEntries(
                    Map.entry("praxis_bulk_capacity_slot_pkey", "PRIMARY KEY (token_id)"),
                    Map.entry("praxis_bulk_capacity_slot_occupancy_sequence_check", "CHECK ((occupancy_sequence >= 0))"),
                    Map.entry("praxis_bulk_capacity_slot_current_execution_id_fkey",
                            "FOREIGN KEY (current_execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id) ON DELETE RESTRICT"),
                    Map.entry("capacity_slot_installation_fk",
                            "FOREIGN KEY (token_id, capacity_class) REFERENCES praxis_bulk.praxis_bulk_capacity_installation(token_id, capacity_class) ON DELETE RESTRICT"),
                    Map.entry("capacity_slot_shape_check",
                            "CHECK ((((current_execution_id IS NULL) AND (current_owner_epoch IS NULL)) OR ((current_execution_id IS NOT NULL) AND (current_owner_epoch IS NOT NULL) AND (current_owner_epoch >= 1) AND (occupancy_sequence > 0))))"),
                    Map.entry("capacity_slot_execution_class_key", "UNIQUE (current_execution_id, capacity_class)"));
            case HISTORY -> Map.ofEntries(
                    Map.entry("praxis_bulk_capacity_occupation_pkey", "PRIMARY KEY (token_id, occupancy_sequence)"),
                    Map.entry("praxis_bulk_capacity_occupation_token_id_fkey",
                            "FOREIGN KEY (token_id) REFERENCES praxis_bulk.praxis_bulk_capacity_slot(token_id) ON DELETE RESTRICT"),
                    Map.entry("praxis_bulk_capacity_occupation_execution_id_fkey",
                            "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id) ON DELETE RESTRICT"),
                    Map.entry("praxis_bulk_capacity_occupation_occupancy_sequence_check", "CHECK ((occupancy_sequence > 0))"),
                    Map.entry("praxis_bulk_capacity_occupation_owner_epoch_check", "CHECK ((owner_epoch >= 1))"));
            case BOOTSTRAP -> Map.of("praxis_bulk_capacity_occupancy_bootstrap_pkey", "PRIMARY KEY (bootstrap_version)",
                    "capacity_occupancy_bootstrap_version_check", "CHECK ((bootstrap_version = 19))",
                    "praxis_bulk_capacity_occupancy_bootstrap_phase_check", "CHECK ((phase = ANY (ARRAY['PENDING'::text, 'COMPLETE'::text])))");
            default -> throw new IllegalArgumentException("Unknown V19 table");
        };
    }

    static String proposalModeExpression() {
        String projection = "(((replace(convert_from(payload, 'UTF8'::name), (chr(92) || 'u0000'::text), (chr(92) || 'uFFFD'::text)))::json -> 'intent'::text) ->> 'executionMode'::text)";
        return "((execution_mode = ANY (ARRAY['SYNC'::text, 'ASYNC'::text])) AND (" + projection + " IS NOT NULL) AND (NOT (execution_mode IS DISTINCT FROM " + projection + ")))";
    }

    /** Extends only the complete V19 inventories; indexes and unique-only subsets are not accepted. */
    static Map<String, String> extendConstraints(String table, Map<String, String> previous) {
        if (!table.equals("praxis_bulk_execution") && !table.equals("praxis_bulk_allocation")) {
            throw new IllegalArgumentException("V19 has no constraint extension for this table");
        }
        var expected = new LinkedHashMap<>(previous);
        if (table.equals("praxis_bulk_execution")) {
            expected.put("praxis_bulk_execution_queue_token_id_fkey",
                    "FOREIGN KEY (queue_token_id) REFERENCES praxis_bulk.praxis_bulk_capacity_installation(token_id) ON DELETE RESTRICT");
            expected.put("praxis_bulk_execution_active_token_id_fkey",
                    "FOREIGN KEY (active_token_id) REFERENCES praxis_bulk.praxis_bulk_capacity_installation(token_id) ON DELETE RESTRICT");
            expected.put("execution_proposal_mode_fk",
                    "FOREIGN KEY (proposal_id, execution_mode) REFERENCES praxis_bulk.praxis_bulk_proposal(proposal_id, execution_mode) ON DELETE RESTRICT");
            expected.put("execution_capacity_mode_check",
                    "CHECK ((((execution_mode = 'SYNC'::text) AND (queue_token_id IS NULL) AND (active_token_id IS NULL) AND (status <> 'QUEUED'::text)) OR ((execution_mode = 'ASYNC'::text) AND (queue_token_id IS NOT NULL) AND (atomicity = 'PER_ITEM'::text) AND (protocol_version = 2) AND (deadline_at <= (created_at + '00:30:00'::interval)) AND ((status <> 'QUEUED'::text) OR ((active_token_id IS NULL) AND (next_ordinal = 0) AND (owner_epoch = 1) AND (active_attempt_id IS NULL) AND (terminal_at IS NULL) AND (terminal_reason_code IS NULL))))))");
            expected.put("praxis_bulk_execution_status_check",
                    "CHECK ((status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'UNIT_IN_FLIGHT'::text, 'UNIT_COMMITTED_PENDING_ACK'::text, 'COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text, 'STOPPED'::text, 'RECONCILIATION_REQUIRED'::text])))");
            expected.put("praxis_bulk_execution_state_shape_check",
                    "CHECK ((((status = 'QUEUED'::text) AND (execution_mode = 'ASYNC'::text) AND (next_ordinal = 0) AND (active_attempt_id IS NULL) AND (terminal_at IS NULL)) OR ((status = 'RUNNING'::text) AND (active_attempt_id IS NULL) AND (next_ordinal < target_count) AND (terminal_at IS NULL)) OR ((status = ANY (ARRAY['UNIT_IN_FLIGHT'::text, 'UNIT_COMMITTED_PENDING_ACK'::text])) AND (active_attempt_id IS NOT NULL) AND (terminal_at IS NULL)) OR ((status = ANY (ARRAY['COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text])) AND (active_attempt_id IS NULL) AND (next_ordinal = target_count) AND (terminal_at IS NOT NULL)) OR ((status = 'STOPPED'::text) AND (terminal_at IS NOT NULL) AND (active_attempt_id IS NULL) AND (active_attempt_ordinal IS NULL) AND (active_target_digest IS NULL) AND (active_attempt_epoch IS NULL)) OR ((status = 'RECONCILIATION_REQUIRED'::text) AND (terminal_at IS NULL))))");
        } else if (table.equals("praxis_bulk_allocation")) {
            expected.put("praxis_bulk_allocation_shape_check",
                    "CHECK ((((kind = 'PROPOSAL_PENDING'::text) AND (proposal_id IS NOT NULL) AND (execution_id IS NULL) AND (state = ANY (ARRAY['PENDING'::text, 'CONSUMED'::text, 'RELEASED'::text]))) OR ((kind = 'EXECUTION_ACTIVE'::text) AND (execution_id IS NOT NULL) AND (proposal_id IS NULL) AND (state = ANY (ARRAY['ACTIVE'::text, 'RELEASED'::text]))) OR ((kind = 'EXECUTION_ASYNC'::text) AND (execution_id IS NOT NULL) AND (proposal_id IS NULL) AND (state = ANY (ARRAY['QUEUED'::text, 'ACTIVE'::text, 'RELEASED'::text])))))");
            expected.put("praxis_bulk_allocation_release_check",
                    "CHECK ((((state = ANY (ARRAY['PENDING'::text, 'ACTIVE'::text, 'CONSUMED'::text, 'QUEUED'::text])) AND (released_at IS NULL) AND (release_reason IS NULL)) OR ((kind = 'PROPOSAL_PENDING'::text) AND (state = 'RELEASED'::text) AND (release_reason = 'PROPOSAL_EXPIRED'::text) AND (release_reason IS NOT NULL) AND (released_at IS NOT NULL) AND (released_at >= created_at)) OR ((kind = ANY (ARRAY['EXECUTION_ACTIVE'::text, 'EXECUTION_ASYNC'::text])) AND (state = 'RELEASED'::text) AND (release_reason = 'TERMINAL_RECONCILED'::text) AND (release_reason IS NOT NULL) AND (released_at IS NOT NULL) AND (released_at >= created_at))))");
        }
        return expected;
    }

    private static void rows(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("""
            select count(*) from praxis_bulk.praxis_bulk_capacity_installation i
            full join praxis_bulk.praxis_bulk_capacity_slot s on s.token_id=i.token_id
            left join praxis_bulk.praxis_bulk_execution e on e.execution_id=s.current_execution_id
            left join praxis_bulk.praxis_bulk_capacity_occupation h
                on h.token_id=s.token_id and h.occupancy_sequence=s.occupancy_sequence
            where i.token_id is null or s.token_id is null or i.capacity_class<>s.capacity_class
              or s.current_execution_id is not null and
                 (e.execution_id is null or e.execution_mode<>'ASYNC' or s.current_owner_epoch is distinct from e.owner_epoch
                  or h.execution_id is distinct from e.execution_id or h.owner_epoch>e.owner_epoch
                  or e.status in ('COMPLETED','COMPLETED_WITH_ERRORS','STOPPED')
                  or (s.capacity_class='QUEUE') is distinct from (e.status='QUEUED')
                  or s.token_id is distinct from case when e.status='QUEUED' then e.queue_token_id else e.active_token_id end)
            """)) {
            require(r.next() && r.getLong(1) == 0 && !r.next(), "V19 slot/history rows differ");
        }
        try (var s = c.createStatement(); var r = s.executeQuery("""
            select count(*) from praxis_bulk.praxis_bulk_execution e where e.execution_mode='ASYNC'
              and ((select count(*) from praxis_bulk.praxis_bulk_capacity_slot s where s.current_execution_id=e.execution_id)
                <>case when e.status in ('COMPLETED','COMPLETED_WITH_ERRORS','STOPPED') then 0 else 1 end
                or e.protocol_version<>2 or e.atomicity<>'PER_ITEM')
            """)) {
            require(r.next() && r.getLong(1) == 0 && !r.next(), "V19 execution occupation is incomplete");
        }
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
                } else
                    quoted = !quoted;
            } else if (quoted || !Character.isWhitespace(ch))
                out.append(quoted ? ch : Character.toLowerCase(ch));
        }
        return out.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
