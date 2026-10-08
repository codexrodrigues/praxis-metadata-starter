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

    static String functionBody(String name) {
        var matcher = Pattern.compile("(?is)create\\s+(?:or\\s+replace\\s+)?function\\s+praxis_bulk\\."
                + Pattern.quote(name) + "\\s*\\([^)]*\\).*?\\bas\\s*\\$\\$(.*?)\\$\\$\\s*;").matcher(source());
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

    static String phase(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("select bootstrap_version,phase from praxis_bulk." + BOOTSTRAP)) {
            require(r.next() && r.getInt(1) == 19, "V19 bootstrap absent");
            String phase = r.getString(2);
            require(!r.next() && Set.of("PENDING", "COMPLETE").contains(phase), "V19 bootstrap invalid");
            return phase;
        }
    }

    static void bootstrap(Connection c, BulkExecutionRoleConfiguration roles) throws SQLException {
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
        for (String role : new java.util.TreeSet<>(roles.runtimeGranteeRoles())) {
            String quoted = '"' + role.replace("\"", "\"\"") + '"';
            try (var s = c.createStatement()) {
                s.execute("grant select on praxis_bulk." + SLOT + ",praxis_bulk." + HISTORY + " to " + quoted);
                s.execute("grant execute on function praxis_bulk.lock_capacity_marker(),praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint) to " + quoted);
            }
        }
        try (var s = c.createStatement()) {
            require(s.executeUpdate("update praxis_bulk." + BOOTSTRAP + " set phase='COMPLETE' where bootstrap_version=19 and phase='PENDING'") == 1,
                    "V19 completion lost");
        }
        validate(c, roles, true);
    }

    static void validate(Connection c, BulkExecutionRoleConfiguration roles, boolean complete) throws SQLException {
        require(installed(c), "V19 occupancy absent");
        columns(c, SLOT, Map.of("token_id", "uuid|true", "capacity_class", "text|true", "occupancy_sequence",
                "bigint|true",
                "current_execution_id", "uuid|false", "current_owner_epoch", "bigint|false"));
        columns(c, HISTORY, Map.of("token_id", "uuid|true", "occupancy_sequence", "bigint|true", "execution_id",
                "uuid|true",
                "owner_epoch", "bigint|true", "acquired_at", "timestamp with time zone|true"));
        columns(c, BOOTSTRAP, Map.of("bootstrap_version", "smallint|true", "phase", "text|true"));
        functions(c, roles, complete);
        triggers(c);
        terminalTriggerOrder(c);
        roles(c, roles, complete);
        for (String table : TABLES)
            constraints(c, table, constraints(table));
        rows(c);
    }

    static void validateAccess(Connection connection, BulkExecutionRoleConfiguration roles) throws SQLException {
        require(installed(connection), "V19 occupancy absent");
        functions(connection, roles, true);
        triggers(connection);
        terminalTriggerOrder(connection);
        roles(connection, roles, true);
    }

    private static void columns(Connection c, String table, Map<String, String> expected) throws SQLException {
        var actual = new LinkedHashMap<String, String>();
        try (var s = c.prepareStatement("""
                select a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,a.atthasdef,a.attidentity,a.attgenerated,
                       t.relpersistence,t.relrowsecurity,t.relkind from pg_attribute a join pg_class t on t.oid=a.attrelid
                where a.attrelid=?::regclass and a.attnum>0 and not a.attisdropped
                """)) {
            s.setString(1, "praxis_bulk." + table);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    require(!r.getBoolean(4) && r.getString(5).isEmpty() && r.getString(6).isEmpty()
                            && "p".equals(r.getString(7))
                            && !r.getBoolean(8) && "r".equals(r.getString(9)), "V19 column attributes differ");
                    actual.put(r.getString(1), r.getString(2) + "|" + r.getBoolean(3));
                }
            }
        }
        require(expected.equals(actual), "V19 columns differ: " + table);
    }

    private static void functions(Connection c, BulkExecutionRoleConfiguration roles, boolean complete) throws SQLException {
        for (String signature : FUNCTIONS) {
            String name = signature.substring(0, signature.indexOf('('));
            try (var s = c.prepareStatement("""
                select p.prosrc,p.prosecdef,pg_get_userbyid(p.proowner),p.proconfig,p.provolatile,p.proparallel,
                    p.proleakproof,p.prokind,p.prorettype::regtype::text,l.lanname
                from pg_proc p join pg_namespace n on n.oid=p.pronamespace join pg_language l on l.oid=p.prolang
                where n.nspname='praxis_bulk' and p.proname||'('||pg_get_function_identity_arguments(p.oid)||')'=?
                """)) {
                s.setString(1, signature);
                try (var r = s.executeQuery()) {
                    require(r.next() && normalize(functionBody(name)).equals(normalize(r.getString(1)))
                            && r.getBoolean(2) == DEFINERS.contains(name)
                            && (DEFINERS.contains(name) ? OWNER : roles.expectedSchemaOwnerRole()).equals(r.getString(3))
                            && java.util.Arrays.equals((String[]) r.getArray(4).getArray(),
                                    new String[] {"search_path=pg_catalog, pg_temp"})
                            && "v".equals(r.getString(5)) && "u".equals(r.getString(6)) && !r.getBoolean(7)
                            && "f".equals(r.getString(8))
                            && (name.equals("lock_capacity_marker") ? "text"
                                    : name.equals("claim_capacity_execution") ? "boolean" : "trigger").equals(r.getString(9))
                            && "plpgsql".equals(r.getString(10)) && !r.next(), "V19 function attributes/source differ: " + name);
                }
            }
            var expected = new LinkedHashSet<String>();
            if (name.equals("lock_capacity_marker")) expected.add("praxis_bulk_retention_owner|EXECUTE");
            if (complete && (name.equals("lock_capacity_marker") || name.equals("claim_capacity_execution")))
                roles.runtimeGranteeRoles().forEach(role -> expected.add(role + "|EXECUTE"));
            var actual = new LinkedHashSet<String>();
            try (var s = c.prepareStatement("""
                select coalesce(r.rolname,'PUBLIC'),a.privilege_type,a.is_grantable
                from pg_proc p cross join lateral aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                left join pg_roles r on r.oid=a.grantee
                where p.oid=to_regprocedure(?) and a.grantee<>p.proowner
                """)) {
                s.setString(1, "praxis_bulk." + signature.replaceAll("\\bp_[a-z_]+\\s+", ""));
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        require(!r.getBoolean(3), "V19 grant option forbidden");
                        actual.add(r.getString(1) + "|" + r.getString(2));
                    }
                }
            }
            require(expected.equals(actual), "V19 function ACL differs: " + name);
        }
    }

    private static void triggers(Connection c) throws SQLException {
        for (var entry : TRIGGERS.entrySet()) {
            var expected = entry.getValue();
            try (var s = c.prepareStatement("""
                select t.tgtype,t.tgenabled,t.tgnargs,t.tgqual is null,t.tgconstraint=0,
                    t.tgoldtable is null,t.tgnewtable is null,p.proname,n.nspname,t.tgargs
                from pg_trigger t join pg_proc p on p.oid=t.tgfoid join pg_namespace n on n.oid=p.pronamespace
                where t.tgrelid=?::regclass and t.tgname=? and not t.tgisinternal
                """)) {
                s.setString(1, "praxis_bulk." + expected.table());
                s.setString(2, entry.getKey());
                try (var r = s.executeQuery()) {
                    require(r.next() && r.getInt(1) == expected.type() && "O".equals(r.getString(2))
                            && r.getInt(3) == 0 && r.getBoolean(4) && r.getBoolean(5) && r.getBoolean(6)
                            && r.getBoolean(7)
                            && expected.function().equals(r.getString(8)) && "praxis_bulk".equals(r.getString(9))
                            && r.getBytes(10).length == 0 && !r.next(), "V19 trigger differs: " + entry.getKey());
                }
            }
        }
    }

    /** PostgreSQL orders same-event triggers by name; attest the two actual AFTER names in that order. */
    private static void terminalTriggerOrder(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select t.tgname from pg_trigger t
                where t.tgrelid='praxis_bulk.praxis_bulk_execution'::regclass and not t.tgisinternal
                  and t.tgname in ('praxis_bulk_execution_capacity_materialize',
                                   'praxis_bulk_execution_release_active_allocation')
                  and t.tgenabled='O' and (t.tgtype & 1)=1 and (t.tgtype & 2)=0 and (t.tgtype & 16)=16
                order by t.tgname collate "C"
                """); var rows = statement.executeQuery()) {
            require(rows.next() && "praxis_bulk_execution_capacity_materialize".equals(rows.getString(1))
                    && rows.next() && "praxis_bulk_execution_release_active_allocation".equals(rows.getString(1))
                    && !rows.next(),
                    "V19 terminal materialization must precede the allocation release certificate");
        }
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

    private static void roles(Connection c, BulkExecutionRoleConfiguration roles, boolean complete) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("""
            select count(*) from pg_roles where rolname='praxis_bulk_capacity_owner' and not rolcanlogin and not rolinherit
              and not rolsuper and not rolcreatedb and not rolcreaterole and not rolreplication and not rolbypassrls
            """)) {
            require(r.next() && r.getInt(1) == 1 && !r.next(), "V19 owner attributes differ");
        }
        try (var s = c.createStatement(); var r = s.executeQuery("""
            select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid or r.oid=m.member
            where r.rolname='praxis_bulk_capacity_owner'
            """)) {
            require(r.next() && r.getInt(1) == 0 && !r.next(), "V19 owner membership forbidden");
        }
        var ownerActual = new LinkedHashMap<String, Set<String>>();
        for (var entry : allTablePrivileges(c, OWNER).entrySet())
            if (!entry.getValue().isEmpty())
                ownerActual.put(entry.getKey(), entry.getValue());
        require(ownerTablePrivileges().equals(ownerActual), "V19 owner table grants differ");
        for (String table : TABLES) {
            require(allTablePrivileges(c, "PUBLIC").getOrDefault(table, Set.of()).isEmpty(), "V19 PUBLIC access forbidden");
            for (String role : roles.runtimeGranteeRoles())
                require(allTablePrivileges(c, role).getOrDefault(table, Set.of()).equals(
                        complete && !table.equals(BOOTSTRAP) ? Set.of("T:SELECT") : Set.of()),
                        "V19 runtime table grants differ");
            for (String role : roles.controlPlaneGranteeRoles())
                require(allTablePrivileges(c, role).getOrDefault(table, Set.of()).isEmpty(),
                        "V19 control-plane access forbidden");
            require(allTablePrivileges(c, "praxis_bulk_retention_owner").getOrDefault(table, Set.of()).equals(
                    table.equals(HISTORY) ? Set.of("T:SELECT", "T:DELETE") : table.equals(SLOT) ? Set.of("T:SELECT") : Set.of()),
                    "V19 retention grants differ");
            require(allTablePrivileges(c, "praxis_bulk_retention_executor").getOrDefault(table, Set.of()).isEmpty(),
                    "V19 retention executor table access forbidden");
        }
    }

    private static Map<String, Set<String>> allTablePrivileges(Connection c, String role) throws SQLException {
        var result = new LinkedHashMap<String, Set<String>>();
        try (var s = c.prepareStatement("""
            select q.relname,q.scope,q.col,q.privilege_type,q.is_grantable from (
                select c.relname,'T'::text scope,null::text col,a.privilege_type,a.is_grantable,a.grantee,c.relowner
                  from pg_class c join pg_namespace n on n.oid=c.relnamespace
                  cross join lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a where n.nspname='praxis_bulk'
                union all
                select c.relname,'C',v.attname,a.privilege_type,a.is_grantable,a.grantee,c.relowner
                  from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_attribute v on v.attrelid=c.oid
                  cross join lateral aclexplode(v.attacl) a where n.nspname='praxis_bulk' and v.attnum>0 and not v.attisdropped
            ) q left join pg_roles r on r.oid=q.grantee where coalesce(r.rolname,'PUBLIC')=? and q.grantee<>q.relowner
            """)) {
            s.setString(1, role);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    require(!r.getBoolean(5), "V19 table grant option forbidden");
                    result.computeIfAbsent(r.getString(1), x -> new LinkedHashSet<>()).add(r.getString(2).equals("T") ?
                            "T:" + r.getString(4) : "C:" + r.getString(3) + ":" + r.getString(4));
                }
            }
        }
        return result;
    }

    static void constraints(Connection c, String table, Map<String, String> expected) throws SQLException {
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
