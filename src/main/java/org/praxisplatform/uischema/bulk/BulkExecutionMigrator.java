package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit PostgreSQL deployment migration for protected bulk-proposal input.
 *
 * <p>This class is intentionally not an auto-configuration component. A host calls it during an
 * explicit deployment step with the same operational datasource used by the protected store. It
 * owns only the {@value #SCHEMA} schema and never baselines, cleans, grants, evaluates, admits or
 * executes proposals.</p>
 */
public final class BulkExecutionMigrator {
    static final String SCHEMA = "praxis_bulk";
    static final String HISTORY_TABLE = "praxis_bulk_schema_history";
    private static final String PROPOSAL_TABLE = "praxis_bulk_proposal";
    private static final String EVALUATION_TABLE = "praxis_bulk_evaluation";
    private static final String REJECTION_FUNCTION = "reject_praxis_bulk_proposal_update";
    private static final String REJECTION_TRIGGER = "praxis_bulk_proposal_reject_update";
    private static final String EVALUATION_REJECTION_FUNCTION = "reject_praxis_bulk_evaluation_update";
    private static final String EVALUATION_REJECTION_TRIGGER = "praxis_bulk_evaluation_reject_update";

    private static final String EXECUTION_TABLE = "praxis_bulk_execution";
    private static final String RECEIPT_TABLE = "praxis_bulk_item_receipt";
    private static final String BINDING_FUNCTION = "protect_praxis_bulk_execution_binding";
    private static final String BINDING_TRIGGER = "praxis_bulk_execution_protect_binding";
    private static final String RECEIPT_FUNCTION = "reject_praxis_bulk_item_receipt_mutation";
    private static final String RECEIPT_TRIGGER = "praxis_bulk_item_receipt_reject_mutation";

    private BulkExecutionMigrator() { }

    /**
     * Applies pending protected-storage migrations and validates the resulting PostgreSQL catalog.
     * The call must happen outside a Spring transaction because it owns deployment DDL, not a
     * proposal write transaction.
     *
     * @return the number of migrations executed by Flyway
     */
    public static int migrate(DataSource dataSource) {
        requireOutsideSpringTransaction();
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        assertKnownDedicatedSchema(operationalDataSource);
        int migrationsExecuted = flyway(operationalDataSource).migrate().migrationsExecuted;
        validate(operationalDataSource);
        return migrationsExecuted;
    }

    /**
     * Validates Flyway history/checksums and the durable PostgreSQL structure used for proposals.
     * This is stronger than migration-name validation: it rejects catalog drift that weakens the
     * immutable protected-input contract.
     */
    public static void validate(DataSource dataSource) {
        requireOutsideSpringTransaction();
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        assertKnownDedicatedSchema(operationalDataSource);
        flyway(operationalDataSource).validate();
        try (Connection connection = operationalDataSource.getConnection()) {
            String previousSearchPath;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select current_setting('search_path')")) {
                require(rows.next(), "Unable to read current PostgreSQL search_path");
                previousSearchPath = rows.getString(1);
            }
            try {
                setCatalogSearchPath(connection, "pg_catalog");
                assertPostgreSql(connection);
                validateProposalTable(connection);
                validateColumns(connection);
                validatePrimaryKey(connection);
                validateChecks(connection);
                validateProposalUnique(connection);
                validateImmutableUpdateTrigger(connection, PROPOSAL_TABLE, REJECTION_TRIGGER, REJECTION_FUNCTION,
                        "praxis_bulk.praxis_bulk_proposal");
                validateEvaluationTable(connection);
                validateEvaluationColumns(connection);
                validateEvaluationPrimaryKey(connection);
                validateEvaluationForeignKey(connection);
                validateEvaluationChecks(connection);
                validateImmutableUpdateTrigger(connection, EVALUATION_TABLE, EVALUATION_REJECTION_TRIGGER,
                        EVALUATION_REJECTION_FUNCTION, "praxis_bulk.praxis_bulk_evaluation");
                validateDurableExecution(connection);
                validateOwnedSchema(connection);
            } finally {
                setCatalogSearchPath(connection, previousSearchPath);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to validate protected bulk proposal storage", error);
        }
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/praxis-bulk-migrations")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .table(HISTORY_TABLE)
                .createSchemas(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .load();
    }

    private static void requireOutsideSpringTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bulk storage migration must not run inside an active Spring transaction");
        }
    }

    private static void assertKnownDedicatedSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            assertPostgreSql(connection);
            if (!schemaExists(connection)) return;

            Set<String> relations = queryNames(connection, """
                    select c.relname
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and c.relkind in ('r', 'p', 'v', 'm', 'S', 'f')
                    """);
            Set<String> functions = queryNames(connection, """
                    select p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                    where n.nspname = ? and p.prokind in ('f', 'p', 'a', 'w')
                    """);
            Set<String> types = queryNames(connection, """
                    select t.typname
                    from pg_type t join pg_namespace n on n.oid = t.typnamespace
                    where n.nspname = ? and t.typelem = 0 and t.typtype in ('b', 'c', 'd', 'e', 'm', 'r')
                      and not (t.typtype = 'c' and t.typrelid <> 0
                          and t.typname in ('praxis_bulk_schema_history', 'praxis_bulk_proposal',
                              'praxis_bulk_evaluation', 'praxis_bulk_execution', 'praxis_bulk_item_receipt'))
                    """);
            Set<String> orphanIndexes = unexpectedOrphanIndexes(connection);
            Set<String> triggers = queryNames(connection, """
                    select c.relname || '.' || t.tgname
                    from pg_trigger t join pg_class c on c.oid = t.tgrelid
                        join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and not t.tgisinternal
                    """);
            Set<String> rules = userRules(connection);
            Set<String> policies = rowSecurityPolicies(connection);

            if (relations.isEmpty() && functions.isEmpty() && types.isEmpty()
                    && orphanIndexes.isEmpty() && triggers.isEmpty() && rules.isEmpty() && policies.isEmpty()) return;
            if (!relations.contains(HISTORY_TABLE)
                    || !relations.stream().allMatch(Set.of(HISTORY_TABLE, PROPOSAL_TABLE, EVALUATION_TABLE, EXECUTION_TABLE, RECEIPT_TABLE)::contains)
                    || !functions.stream().allMatch(Set.of(REJECTION_FUNCTION + "()", EVALUATION_REJECTION_FUNCTION + "()", BINDING_FUNCTION + "()", RECEIPT_FUNCTION + "()")::contains)
                    || !types.isEmpty()
                    || !orphanIndexes.isEmpty()
                    || !rules.isEmpty() || !policies.isEmpty()
                    || !triggers.stream().allMatch(Set.of(PROPOSAL_TABLE + "." + REJECTION_TRIGGER, EVALUATION_TABLE + "." + EVALUATION_REJECTION_TRIGGER, EXECUTION_TABLE + "." + BINDING_TRIGGER, RECEIPT_TABLE + "." + RECEIPT_TRIGGER)::contains)) {
                throw new IllegalStateException("Refusing an unknown nonempty praxis_bulk schema: relations="
                        + relations + ", functions=" + functions + ", types=" + types + ", indexes="
                        + orphanIndexes + ", triggers=" + triggers + ", rules=" + rules + ", policies=" + policies);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to inspect dedicated bulk storage schema", error);
        }
    }

    private static boolean schemaExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select exists (select 1 from pg_namespace where nspname = ?)")) {
            statement.setString(1, SCHEMA);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static Set<String> queryNames(Connection connection, String sql) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCHEMA);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString(1));
            }
        }
        return names;
    }

    private static Set<String> unexpectedOrphanIndexes(Connection connection) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement("""
                select index_class.relname
                from pg_index i
                join pg_class index_class on index_class.oid = i.indexrelid
                join pg_class table_class on table_class.oid = i.indrelid
                join pg_namespace n on n.oid = index_class.relnamespace
                join pg_am am on am.oid = index_class.relam
                left join lateral unnest(i.indkey) with ordinality key_column(attnum, ordinal_position)
                    on key_column.ordinal_position = 1
                left join pg_attribute key_attribute on key_attribute.attrelid = i.indrelid
                    and key_attribute.attnum = key_column.attnum
                left join pg_opclass opclass on opclass.oid = i.indclass[0]
                left join pg_constraint c on c.conindid = i.indexrelid and c.conrelid = i.indrelid
                    and c.contype in ('p', 'u', 'x')
                where n.nspname = ? and c.oid is null
                  and not coalesce((index_class.relname = ? and table_class.relname = ?
                    and not i.indisunique and i.indisvalid and i.indisready and i.indislive
                    and i.indpred is null and i.indexprs is null and i.indnatts = 1 and i.indnkeyatts = 1
                    and am.amname = 'btree' and key_attribute.attname = 'success'
                    and i.indoption[0] = 0 and i.indcollation[0] = key_attribute.attcollation
                    and opclass.opcdefault), false)
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, "praxis_bulk_schema_history_s_idx");
            statement.setString(3, HISTORY_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString(1));
            }
        }
        return names;
    }

    private static Set<String> userRules(Connection connection) throws SQLException {
        return queryNames(connection, """
                select c.relname || '.' || r.rulename
                from pg_rewrite r join pg_class c on c.oid = r.ev_class
                    join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and r.rulename <> '_RETURN'
                """);
    }

    private static Set<String> rowSecurityPolicies(Connection connection) throws SQLException {
        return queryNames(connection, """
                select c.relname || '.' || p.polname
                from pg_policy p join pg_class c on c.oid = p.polrelid
                    join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ?
                """);
    }

    private static void validateOwnedSchema(Connection connection) throws SQLException {
        Set<String> relations = queryNames(connection, """
                select c.relname
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relkind in ('r', 'p', 'v', 'm', 'S', 'f')
                """);
        Set<String> functions = queryNames(connection, """
                select p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = ? and p.prokind in ('f', 'p', 'a', 'w')
                """);
        Set<String> types = queryNames(connection, """
                select t.typname
                from pg_type t join pg_namespace n on n.oid = t.typnamespace
                where n.nspname = ? and t.typelem = 0 and t.typtype in ('b', 'c', 'd', 'e', 'm', 'r')
                  and not (t.typtype = 'c' and t.typrelid <> 0
                      and t.typname in ('praxis_bulk_schema_history', 'praxis_bulk_proposal',
                          'praxis_bulk_evaluation', 'praxis_bulk_execution', 'praxis_bulk_item_receipt'))
                """);
        Set<String> orphanIndexes = unexpectedOrphanIndexes(connection);
        Set<String> triggers = queryNames(connection, """
                select c.relname || '.' || t.tgname
                from pg_trigger t join pg_class c on c.oid = t.tgrelid
                    join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and not t.tgisinternal
                """);
        Set<String> rules = userRules(connection);
        Set<String> policies = rowSecurityPolicies(connection);
        require(relations.equals(Set.of(HISTORY_TABLE, PROPOSAL_TABLE, EVALUATION_TABLE, EXECUTION_TABLE, RECEIPT_TABLE))
                        && functions.equals(Set.of(REJECTION_FUNCTION + "()", EVALUATION_REJECTION_FUNCTION + "()", BINDING_FUNCTION + "()", RECEIPT_FUNCTION + "()"))
                        && types.isEmpty()
                        && orphanIndexes.isEmpty()
                        && rules.isEmpty() && policies.isEmpty()
                        && triggers.equals(Set.of(PROPOSAL_TABLE + "." + REJECTION_TRIGGER, EVALUATION_TABLE + "." + EVALUATION_REJECTION_TRIGGER, EXECUTION_TABLE + "." + BINDING_TRIGGER, RECEIPT_TABLE + "." + RECEIPT_TRIGGER)),
                "protected bulk storage schema contains unexpected owned objects: indexes=" + orphanIndexes
                        + ", rules=" + rules + ", policies=" + policies);
    }

    private static void validateColumns(Connection connection) throws SQLException {
        Map<String, ColumnDefinition> expected = Map.ofEntries(
                Map.entry("proposal_id", new ColumnDefinition("uuid", false, null, "NEVER", null)),
                Map.entry("namespace_id", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("subject_id", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("resource_key", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("operation_id", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("created_at", new ColumnDefinition("timestamp with time zone", false, null, "NEVER", 6)),
                Map.entry("expires_at", new ColumnDefinition("timestamp with time zone", false, null, "NEVER", 6)),
                Map.entry("fingerprint", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("payload", new ColumnDefinition("bytea", false, null, "NEVER", null)));
        Map<String, ColumnDefinition> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select column_name, data_type, is_nullable, column_default, is_generated, datetime_precision
                from information_schema.columns
                where table_schema = ? and table_name = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.put(result.getString(1), new ColumnDefinition(
                            result.getString(2), "YES".equals(result.getString(3)), result.getString(4), result.getString(5),
                            result.getObject(6, Integer.class)));
                }
            }
        }
        require(expected.equals(actual), "proposal table columns, types or nullability differ from V1");
    }

    private static void validateProposalTable(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select c.relkind, c.relpersistence
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relname = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "r".equals(result.getString(1)) && "p".equals(result.getString(2))
                                && !result.next(),
                        "proposal storage must be an ordinary permanent table");
            }
        }
    }

    private static void validateEvaluationTable(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select c.relkind, c.relpersistence
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relname = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "r".equals(result.getString(1)) && "p".equals(result.getString(2))
                                && !result.next(),
                        "evaluation storage must be an ordinary permanent table");
            }
        }
    }

    private static void validateEvaluationColumns(Connection connection) throws SQLException {
        Map<String, ColumnDefinition> expected = Map.ofEntries(
                Map.entry("proposal_id", new ColumnDefinition("uuid", false, null, "NEVER", null)),
                Map.entry("input_fingerprint", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("evaluation_fingerprint", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("payload", new ColumnDefinition("bytea", false, null, "NEVER", null)));
        Map<String, ColumnDefinition> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select column_name, data_type, is_nullable, column_default, is_generated, datetime_precision
                from information_schema.columns
                where table_schema = ? and table_name = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.put(result.getString(1), new ColumnDefinition(
                            result.getString(2), "YES".equals(result.getString(3)), result.getString(4), result.getString(5),
                            result.getObject(6, Integer.class)));
                }
            }
        }
        require(expected.equals(actual), "evaluation table columns, types or nullability differ from V2");
    }

    private static void validatePrimaryKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select string_agg(a.attname, ',' order by key_columns.ordinality), c.condeferrable
                from pg_constraint c
                join unnest(c.conkey) with ordinality as key_columns(attnum, ordinality) on true
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key_columns.attnum
                join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'p'
                group by c.oid
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "proposal_id".equals(result.getString(1)) && !result.getBoolean(2) && !result.next(),
                        "proposal_id must be the only primary key column");
            }
        }
    }

    private static void validateProposalUnique(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select string_agg(a.attname, ',' order by key_columns.ordinality),
                       c.condeferrable, c.condeferred, c.convalidated
                from pg_constraint c
                join unnest(c.conkey) with ordinality as key_columns(attnum, ordinality) on true
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key_columns.attnum
                join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'u'
                group by c.oid
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "proposal_id,fingerprint".equals(result.getString(1))
                                && !result.getBoolean(2) && !result.getBoolean(3) && result.getBoolean(4) && !result.next(),
                        "proposal_id and fingerprint must have the only immediate validated unique key");
            }
        }
    }

    private static void validateEvaluationPrimaryKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select string_agg(a.attname, ',' order by key_columns.ordinality), c.condeferrable, c.condeferred
                from pg_constraint c
                join unnest(c.conkey) with ordinality as key_columns(attnum, ordinality) on true
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key_columns.attnum
                join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'p'
                group by c.oid
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "proposal_id".equals(result.getString(1))
                                && !result.getBoolean(2) && !result.getBoolean(3) && !result.next(),
                        "evaluation proposal_id must be the only immediate primary key column");
            }
        }
    }

    private static void validateEvaluationForeignKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select string_agg(source_column.attname, ',' order by source_key.ordinality),
                       string_agg(target_column.attname, ',' order by target_key.ordinality),
                       target_namespace.nspname, target_relation.relname,
                       c.condeferrable, c.condeferred, c.convalidated,
                       c.confmatchtype, c.confupdtype, c.confdeltype
                from pg_constraint c
                join unnest(c.conkey) with ordinality as source_key(attnum, ordinality) on true
                join pg_attribute source_column on source_column.attrelid = c.conrelid and source_column.attnum = source_key.attnum
                join unnest(c.confkey) with ordinality as target_key(attnum, ordinality)
                    on target_key.ordinality = source_key.ordinality
                join pg_attribute target_column on target_column.attrelid = c.confrelid and target_column.attnum = target_key.attnum
                join pg_class target_relation on target_relation.oid = c.confrelid
                join pg_namespace target_namespace on target_namespace.oid = target_relation.relnamespace
                join pg_namespace source_namespace on source_namespace.oid = c.connamespace
                where source_namespace.nspname = ? and c.conrelid = ?::regclass and c.contype = 'f'
                group by c.oid, target_namespace.nspname, target_relation.relname
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next()
                                && "proposal_id,input_fingerprint".equals(result.getString(1))
                                && "proposal_id,fingerprint".equals(result.getString(2))
                                && SCHEMA.equals(result.getString(3)) && PROPOSAL_TABLE.equals(result.getString(4))
                                && !result.getBoolean(5) && !result.getBoolean(6) && result.getBoolean(7)
                                && "s".equals(result.getString(8)) && "a".equals(result.getString(9))
                                && "a".equals(result.getString(10)) && !result.next(),
                        "evaluation must have the only immediate validated proposal and input-fingerprint foreign key");
            }
        }
    }

    private static void validateChecks(Connection connection) throws SQLException {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("praxis_bulk_proposal_namespace_id_nonblank_check",
                        "(btrim(namespace_id)<>''::text)"),
                Map.entry("praxis_bulk_proposal_subject_id_nonblank_check",
                        "(btrim(subject_id)<>''::text)"),
                Map.entry("praxis_bulk_proposal_resource_key_nonblank_check",
                        "(btrim(resource_key)<>''::text)"),
                Map.entry("praxis_bulk_proposal_operation_id_nonblank_check",
                        "(btrim(operation_id)<>''::text)"),
                Map.entry("praxis_bulk_proposal_valid_window_check", "(expires_at>created_at)"),
                Map.entry("praxis_bulk_proposal_fingerprint_format_check", "(fingerprint~'^sha256:[0-9a-f]{64}$'::text)"),
                Map.entry("praxis_bulk_proposal_payload_length_check",
                        "((octet_length(payload)>=1)and(octet_length(payload)<=8388608))"));
        Map<String, ConstraintDefinition> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select c.conname, c.convalidated, pg_get_expr(c.conbin, c.conrelid)
                from pg_constraint c join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'c'
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) actual.put(result.getString(1),
                        new ConstraintDefinition(result.getBoolean(2), normalizeExpression(result.getString(3))));
            }
        }
        require(actual.keySet().equals(expected.keySet()), "proposal table check constraints differ from V1");
        for (Map.Entry<String, String> check : expected.entrySet()) {
            ConstraintDefinition definition = actual.get(check.getKey());
            require(definition.validated() && check.getValue().equals(definition.expression()),
                    "proposal table check constraint is invalid: " + check.getKey());
        }
    }

    private static void validateEvaluationChecks(Connection connection) throws SQLException {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("praxis_bulk_evaluation_fingerprint_format_check",
                        "(evaluation_fingerprint~'^sha256:[0-9a-f]{64}$'::text)"),
                Map.entry("praxis_bulk_evaluation_payload_length_check",
                        "((octet_length(payload)>=1)and(octet_length(payload)<=8388608))"));
        Map<String, ConstraintDefinition> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select c.conname, c.convalidated, pg_get_expr(c.conbin, c.conrelid)
                from pg_constraint c join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'c'
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) actual.put(result.getString(1),
                        new ConstraintDefinition(result.getBoolean(2), normalizeExpression(result.getString(3))));
            }
        }
        require(actual.keySet().equals(expected.keySet()), "evaluation table check constraints differ from V2");
        for (Map.Entry<String, String> check : expected.entrySet()) {
            ConstraintDefinition definition = actual.get(check.getKey());
            require(definition.validated() && check.getValue().equals(definition.expression()),
                    "evaluation table check constraint is invalid: " + check.getKey());
        }
    }

    private static void validateImmutableUpdateTrigger(Connection connection, String table, String trigger,
            String function, String relation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select t.tgenabled, pg_get_triggerdef(t.oid), p.prosrc, l.lanname, p.prorettype::regtype::text, p.prosecdef
                from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                join pg_namespace n on n.oid = c.relnamespace
                join pg_proc p on p.oid = t.tgfoid
                join pg_language l on l.oid = p.prolang
                where n.nspname = ? and c.relname = ? and t.tgname = ? and not t.tgisinternal
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, trigger);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "immutable update trigger is missing");
                String definition = normalizeExpression(result.getString(2));
                require("O".equals(result.getString(1))
                                && definition.equals("createtrigger" + trigger + "beforeupdateon" + SCHEMA + "." + table
                                        + "foreachrowexecutefunction" + SCHEMA + "." + function + "()")
                                && "plpgsql".equals(result.getString(4))
                                && "trigger".equals(result.getString(5))
                                && !result.getBoolean(6)
                                && normalizeExpression(result.getString(3)).equals(
                                        "beginraiseexception'" + relation + " is immutable'usingerrcode='55000';end;")
                                && !result.next(),
                        "immutable update trigger differs from protected storage migration");
            }
        }
    }

    /** Frozen V3 catalog expectations, checked against real PostgreSQL; no runtime DDL here. */
    private static void validateDurableExecution(Connection connection) throws SQLException {
        validateDurableColumns(connection, "praxis_bulk_execution", Map.ofEntries(
                Map.entry("execution_id", "uuid|true"),
                Map.entry("proposal_id", "uuid|true"),
                Map.entry("namespace_id", "text|true"),
                Map.entry("subject_id", "text|true"),
                Map.entry("resource_key", "text|true"),
                Map.entry("operation_id", "text|true"),
                Map.entry("idempotency_key_digest", "text|true"),
                Map.entry("reservation_fingerprint", "text|true"),
                Map.entry("input_fingerprint", "text|true"),
                Map.entry("evaluation_fingerprint", "text|true"),
                Map.entry("structural_revision", "text|true"),
                Map.entry("owner_id", "text|true"),
                Map.entry("owner_epoch", "bigint|true"),
                Map.entry("status", "text|true"),
                Map.entry("next_ordinal", "integer|true"),
                Map.entry("target_count", "integer|true"),
                Map.entry("deadline_at", "timestamp(6) with time zone|true"),
                Map.entry("active_attempt_id", "uuid|false"),
                Map.entry("active_attempt_ordinal", "integer|false"),
                Map.entry("active_target_digest", "text|false"),
                Map.entry("active_attempt_epoch", "bigint|false"),
                Map.entry("created_at", "timestamp(6) with time zone|true"),
                Map.entry("updated_at", "timestamp(6) with time zone|true"),
                Map.entry("terminal_at", "timestamp(6) with time zone|false")));
        validateDurableColumns(connection, "praxis_bulk_item_receipt", Map.ofEntries(
                Map.entry("execution_id", "uuid|true"),
                Map.entry("unit_ordinal", "integer|true"),
                Map.entry("target_digest", "text|true"),
                Map.entry("expected_version", "text|true"),
                Map.entry("attempt_id", "uuid|true"),
                Map.entry("owner_epoch", "bigint|true"),
                Map.entry("outcome", "text|true"),
                Map.entry("confirmed_at", "timestamp(6) with time zone|true")));
        validateDurableConstraints(connection, "praxis_bulk_evaluation", true, Map.ofEntries(
                Map.entry("praxis_bulk_evaluation_proposal_fingerprint_key", "UNIQUE (proposal_id, evaluation_fingerprint)")));
        validateDurableConstraints(connection, "praxis_bulk_execution", false, Map.ofEntries(
                Map.entry("praxis_bulk_execution_attempt_shape_check", "CHECK ((((active_attempt_id IS NULL) = (active_attempt_ordinal IS NULL)) AND ((active_attempt_id IS NULL) = (active_target_digest IS NULL)) AND ((active_attempt_id IS NULL) = (active_attempt_epoch IS NULL)) AND ((active_attempt_id IS NULL) OR ((active_attempt_ordinal = next_ordinal) AND ((active_attempt_ordinal >= 0) AND (active_attempt_ordinal <= (target_count - 1))) AND ((active_attempt_epoch >= 1) AND (active_attempt_epoch <= owner_epoch)) AND (active_target_digest ~ '^sha256:[0-9a-f]{64}$'::text)))))"),
                Map.entry("praxis_bulk_execution_deadline_check", "CHECK ((deadline_at > created_at))"),
                Map.entry("praxis_bulk_execution_digest_format_check", "CHECK ((idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_execution_epoch_check", "CHECK ((owner_epoch >= 1))"),
                Map.entry("praxis_bulk_execution_evaluation_fingerprint_check", "CHECK ((evaluation_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_execution_evaluation_fkey", "FOREIGN KEY (proposal_id, evaluation_fingerprint) REFERENCES praxis_bulk.praxis_bulk_evaluation(proposal_id, evaluation_fingerprint)"),
                Map.entry("praxis_bulk_execution_input_fingerprint_check", "CHECK ((input_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_execution_namespace_nonblank_check", "CHECK ((btrim(namespace_id) <> ''::text))"),
                Map.entry("praxis_bulk_execution_operation_nonblank_check", "CHECK ((btrim(operation_id) <> ''::text))"),
                Map.entry("praxis_bulk_execution_owner_nonblank_check", "CHECK ((btrim(owner_id) <> ''::text))"),
                Map.entry("praxis_bulk_execution_pkey", "PRIMARY KEY (execution_id)"),
                Map.entry("praxis_bulk_execution_progress_check", "CHECK ((((target_count >= 1) AND (target_count <= 10000)) AND ((next_ordinal >= 0) AND (next_ordinal <= target_count))))"),
                Map.entry("praxis_bulk_execution_proposal_key", "UNIQUE (proposal_id)"),
                Map.entry("praxis_bulk_execution_reservation_fingerprint_check", "CHECK ((reservation_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_execution_resource_nonblank_check", "CHECK ((btrim(resource_key) <> ''::text))"),
                Map.entry("praxis_bulk_execution_revision_nonblank_check", "CHECK ((btrim(structural_revision) <> ''::text))"),
                Map.entry("praxis_bulk_execution_scoped_idempotency_key", "UNIQUE (namespace_id, subject_id, resource_key, operation_id, idempotency_key_digest)"),
                Map.entry("praxis_bulk_execution_state_shape_check", "CHECK ((((status = 'RUNNING'::text) AND (active_attempt_id IS NULL) AND (next_ordinal < target_count) AND (terminal_at IS NULL)) OR ((status = ANY (ARRAY['UNIT_IN_FLIGHT'::text, 'UNIT_COMMITTED_PENDING_ACK'::text])) AND (active_attempt_id IS NOT NULL) AND (terminal_at IS NULL)) OR ((status = 'COMPLETED'::text) AND (active_attempt_id IS NULL) AND (next_ordinal = target_count) AND (terminal_at IS NOT NULL)) OR ((status = 'STOPPED'::text) AND (terminal_at IS NOT NULL)) OR ((status = 'RECONCILIATION_REQUIRED'::text) AND (terminal_at IS NULL))))"),
                Map.entry("praxis_bulk_execution_status_check", "CHECK ((status = ANY (ARRAY['RUNNING'::text, 'UNIT_IN_FLIGHT'::text, 'UNIT_COMMITTED_PENDING_ACK'::text, 'COMPLETED'::text, 'STOPPED'::text, 'RECONCILIATION_REQUIRED'::text])))"),
                Map.entry("praxis_bulk_execution_subject_nonblank_check", "CHECK ((btrim(subject_id) <> ''::text))")));
        validateDurableConstraints(connection, "praxis_bulk_item_receipt", false, Map.ofEntries(
                Map.entry("praxis_bulk_item_receipt_attempt_key", "UNIQUE (attempt_id)"),
                Map.entry("praxis_bulk_item_receipt_epoch_check", "CHECK ((owner_epoch >= 1))"),
                Map.entry("praxis_bulk_item_receipt_execution_fkey", "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id)"),
                Map.entry("praxis_bulk_item_receipt_expected_version_nonblank_check", "CHECK ((btrim(expected_version) <> ''::text))"),
                Map.entry("praxis_bulk_item_receipt_ordinal_check", "CHECK ((unit_ordinal >= 0))"),
                Map.entry("praxis_bulk_item_receipt_outcome_check", "CHECK ((outcome = ANY (ARRAY['CONFIRMED'::text, 'UNCHANGED'::text])))"),
                Map.entry("praxis_bulk_item_receipt_pkey", "PRIMARY KEY (execution_id, unit_ordinal)"),
                Map.entry("praxis_bulk_item_receipt_target_digest_check", "CHECK ((target_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_item_receipt_target_key", "UNIQUE (execution_id, target_digest)")));
        validateDurableTrigger(connection, "praxis_bulk_execution", "praxis_bulk_execution_protect_binding", "protect_praxis_bulk_execution_binding",
                "CREATE TRIGGER praxis_bulk_execution_protect_binding BEFORE UPDATE ON praxis_bulk.praxis_bulk_execution FOR EACH ROW EXECUTE FUNCTION praxis_bulk.protect_praxis_bulk_execution_binding()",
                """
                begin
                    if new.execution_id is distinct from old.execution_id
                       or new.proposal_id is distinct from old.proposal_id
                       or new.namespace_id is distinct from old.namespace_id
                       or new.subject_id is distinct from old.subject_id
                       or new.resource_key is distinct from old.resource_key
                       or new.operation_id is distinct from old.operation_id
                       or new.idempotency_key_digest is distinct from old.idempotency_key_digest
                       or new.reservation_fingerprint is distinct from old.reservation_fingerprint
                       or new.input_fingerprint is distinct from old.input_fingerprint
                       or new.evaluation_fingerprint is distinct from old.evaluation_fingerprint
                       or new.structural_revision is distinct from old.structural_revision
                       or new.target_count is distinct from old.target_count
                       or new.deadline_at is distinct from old.deadline_at
                       or new.created_at is distinct from old.created_at then
                        raise exception 'praxis_bulk.praxis_bulk_execution binding is immutable' using errcode = '55000';
                    end if;
                    return new;
                end;
                """);
        validateDurableTrigger(connection, "praxis_bulk_item_receipt", "praxis_bulk_item_receipt_reject_mutation", "reject_praxis_bulk_item_receipt_mutation",
                "CREATE TRIGGER praxis_bulk_item_receipt_reject_mutation BEFORE DELETE OR UPDATE ON praxis_bulk.praxis_bulk_item_receipt FOR EACH ROW EXECUTE FUNCTION praxis_bulk.reject_praxis_bulk_item_receipt_mutation()",
                """
                begin
                    raise exception 'praxis_bulk.praxis_bulk_item_receipt is immutable' using errcode = '55000';
                end;
                """);
    }

    private static void validateDurableColumns(Connection connection, String table,
            Map<String, String> expected) throws SQLException {
        var actual = new LinkedHashMap<String, String>();
        try (var statement = connection.prepareStatement("""
                select a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                       a.atthasdef, a.attidentity, a.attgenerated, r.relkind, r.relpersistence, r.relrowsecurity
                from pg_attribute a join pg_class r on r.oid = a.attrelid
                where a.attrelid = ?::regclass and a.attnum > 0 and not a.attisdropped
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require(!rows.getBoolean(4) && rows.getString(5).isEmpty() && rows.getString(6).isEmpty()
                                    && "r".equals(rows.getString(7)) && "p".equals(rows.getString(8))
                                    && !rows.getBoolean(9),
                            "durable storage defaults, generation, persistence or row security differ: " + table);
                    actual.put(rows.getString(1), rows.getString(2) + "|" + rows.getBoolean(3));
                }
            }
        }
        require(expected.equals(actual), "durable storage columns differ: " + table);
    }

    private static void validateDurableConstraints(Connection connection, String table, boolean onlyNewUnique,
            Map<String, String> expected) throws SQLException {
        var actual = new LinkedHashMap<String, String>();
        try (var statement = connection.prepareStatement("""
                select c.conname, pg_get_constraintdef(c.oid), c.convalidated, c.condeferrable,
                       c.condeferred, i.indisvalid, i.indisready, i.indislive, i.indimmediate
                from pg_constraint c left join pg_index i on i.indexrelid = c.conindid
                where c.conrelid = ?::regclass and (not ? or c.contype = 'u')
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setBoolean(2, onlyNewUnique);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require(rows.getBoolean(3) && !rows.getBoolean(4) && !rows.getBoolean(5),
                            "durable constraint must be immediate and validated: " + rows.getString(1));
                    if (rows.getObject(6) != null) {
                        require(rows.getBoolean(6) && rows.getBoolean(7) && rows.getBoolean(8) && rows.getBoolean(9),
                                "durable constraint index is invalid: " + rows.getString(1));
                    }
                    actual.put(rows.getString(1), normalizeExpression(rows.getString(2)));
                }
            }
        }
        var normalized = new LinkedHashMap<String, String>();
        expected.forEach((name, definition) -> normalized.put(name, normalizeExpression(definition)));
        require(normalized.equals(actual), "durable storage constraints differ: " + table);
    }

    private static void validateDurableTrigger(Connection connection, String table, String trigger,
            String function, String expectedDefinition, String expectedBody) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select t.tgenabled, pg_get_triggerdef(t.oid), p.prosrc, l.lanname,
                       p.prorettype::regtype::text, p.prosecdef, p.proname,
                       pn.nspname, p.pronargs, p.proconfig
                from pg_trigger t join pg_class r on r.oid = t.tgrelid
                join pg_proc p on p.oid = t.tgfoid join pg_namespace pn on pn.oid = p.pronamespace
                join pg_language l on l.oid = p.prolang
                where t.tgrelid = ?::regclass and t.tgname = ? and not t.tgisinternal
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setString(2, trigger);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && "O".equals(rows.getString(1))
                                && normalizeExpression(expectedDefinition).equals(normalizeExpression(rows.getString(2)))
                                && normalizeExpression(expectedBody).equals(normalizeExpression(rows.getString(3)))
                                && "plpgsql".equals(rows.getString(4)) && "trigger".equals(rows.getString(5))
                                && !rows.getBoolean(6) && function.equals(rows.getString(7))
                                && SCHEMA.equals(rows.getString(8)) && rows.getInt(9) == 0
                                && rows.getObject(10) == null && !rows.next(),
                        "durable storage trigger or function differs: " + trigger);
            }
        }
    }

    private static String normalizeExpression(String expression) {
        StringBuilder normalized = new StringBuilder(expression.length());
        boolean quoted = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == 39) {
                normalized.append(current);
                if (quoted && index + 1 < expression.length() && expression.charAt(index + 1) == 39) {
                    normalized.append(current);
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && Character.isWhitespace(current)) {
                continue;
            } else {
                normalized.append(quoted ? current : Character.toLowerCase(current));
            }
        }
        return normalized.toString();
    }

    private static void assertPostgreSql(Connection connection) throws SQLException {
        require("PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()),
                "bulk storage migration requires PostgreSQL");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void setCatalogSearchPath(Connection connection, String searchPath) throws SQLException {
        try (var statement = connection.prepareStatement("select set_config('search_path', ?, false)")) {
            statement.setString(1, searchPath);
            statement.execute();
        }
    }

    private record ColumnDefinition(String dataType, boolean nullable, String defaultValue, String generated,
                                    Integer dateTimePrecision) { }
    private record ConstraintDefinition(boolean validated, String expression) { }
}
