package org.praxisplatform.uischema.bulk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private static final String ADMISSION_TABLE = "praxis_bulk_admission";
    private static final String BINDING_FUNCTION = "protect_praxis_bulk_execution_binding";
    private static final String BINDING_TRIGGER = "praxis_bulk_execution_protect_binding";
    private static final String TERMINAL_REASON_FUNCTION = "protect_praxis_bulk_terminal_reason";
    private static final String TERMINAL_REASON_TRIGGER = "praxis_bulk_execution_protect_terminal_reason";
    private static final String RECEIPT_FUNCTION = "reject_praxis_bulk_item_receipt_mutation";
    private static final String RECEIPT_TRIGGER = "praxis_bulk_item_receipt_reject_mutation";
    private static final String ADMISSION_FUNCTION = "reject_praxis_bulk_admission_mutation";
    private static final String ADMISSION_TRIGGER = "praxis_bulk_admission_reject_mutation";

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
                validateDurableAdmission(connection);
                validateAdmissionRows(connection);
                validateEvidenceBinding(connection);
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
                              'praxis_bulk_evaluation', 'praxis_bulk_execution', 'praxis_bulk_item_receipt',
                              'praxis_bulk_admission'))
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
                    || !relations.stream().allMatch(Set.of(HISTORY_TABLE, PROPOSAL_TABLE, EVALUATION_TABLE,
                            EXECUTION_TABLE, RECEIPT_TABLE, ADMISSION_TABLE)::contains)
                    || !functions.stream().allMatch(Set.of(REJECTION_FUNCTION + "()", EVALUATION_REJECTION_FUNCTION + "()",
                            BINDING_FUNCTION + "()", TERMINAL_REASON_FUNCTION + "()",
                            RECEIPT_FUNCTION + "()", ADMISSION_FUNCTION + "()")::contains)
                    || !types.isEmpty()
                    || !orphanIndexes.isEmpty()
                    || !rules.isEmpty() || !policies.isEmpty()
                    || !triggers.stream().allMatch(Set.of(PROPOSAL_TABLE + "." + REJECTION_TRIGGER,
                            EVALUATION_TABLE + "." + EVALUATION_REJECTION_TRIGGER,
                            EXECUTION_TABLE + "." + BINDING_TRIGGER, RECEIPT_TABLE + "." + RECEIPT_TRIGGER,
                            EXECUTION_TABLE + "." + TERMINAL_REASON_TRIGGER,
                            ADMISSION_TABLE + "." + ADMISSION_TRIGGER)::contains)) {
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
                          'praxis_bulk_evaluation', 'praxis_bulk_execution', 'praxis_bulk_item_receipt',
                          'praxis_bulk_admission'))
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
        require(relations.equals(Set.of(HISTORY_TABLE, PROPOSAL_TABLE, EVALUATION_TABLE,
                                EXECUTION_TABLE, RECEIPT_TABLE, ADMISSION_TABLE))
                        && functions.equals(Set.of(REJECTION_FUNCTION + "()", EVALUATION_REJECTION_FUNCTION + "()",
                                BINDING_FUNCTION + "()", TERMINAL_REASON_FUNCTION + "()",
                                RECEIPT_FUNCTION + "()", ADMISSION_FUNCTION + "()"))
                        && types.isEmpty()
                        && orphanIndexes.isEmpty()
                        && rules.isEmpty() && policies.isEmpty()
                        && triggers.equals(Set.of(PROPOSAL_TABLE + "." + REJECTION_TRIGGER,
                                EVALUATION_TABLE + "." + EVALUATION_REJECTION_TRIGGER,
                                EXECUTION_TABLE + "." + BINDING_TRIGGER, RECEIPT_TABLE + "." + RECEIPT_TRIGGER,
                                EXECUTION_TABLE + "." + TERMINAL_REASON_TRIGGER,
                                ADMISSION_TABLE + "." + ADMISSION_TRIGGER)),
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

    /** V3 execution and receipt plus V4 execution changes, checked against real PostgreSQL. */
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
                Map.entry("active_unit_deadline_at", "timestamp(6) with time zone|false"),
                Map.entry("created_at", "timestamp(6) with time zone|true"),
                Map.entry("updated_at", "timestamp(6) with time zone|true"),
                Map.entry("terminal_at", "timestamp(6) with time zone|false"),
                Map.entry("terminal_reason_code", "text|false")));
        validateDurableColumns(connection, "praxis_bulk_item_receipt", Map.ofEntries(
                Map.entry("execution_id", "uuid|true"),
                Map.entry("unit_ordinal", "integer|true"),
                Map.entry("target_digest", "text|true"),
                Map.entry("expected_version", "text|true"),
                Map.entry("attempt_id", "uuid|true"),
                Map.entry("owner_epoch", "bigint|true"),
                Map.entry("outcome", "text|true"),
                Map.entry("confirmed_at", "timestamp(6) with time zone|true"),
                Map.entry("unit_deadline_at", "timestamp(6) with time zone|false")));
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
                Map.entry("praxis_bulk_execution_state_shape_check", "CHECK ((((status = 'RUNNING'::text) AND (active_attempt_id IS NULL) AND (next_ordinal < target_count) AND (terminal_at IS NULL)) OR ((status = ANY (ARRAY['UNIT_IN_FLIGHT'::text, 'UNIT_COMMITTED_PENDING_ACK'::text])) AND (active_attempt_id IS NOT NULL) AND (terminal_at IS NULL)) OR ((status = ANY (ARRAY['COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text])) AND (active_attempt_id IS NULL) AND (next_ordinal = target_count) AND (terminal_at IS NOT NULL)) OR ((status = 'STOPPED'::text) AND (terminal_at IS NOT NULL) AND (active_attempt_id IS NULL) AND (active_attempt_ordinal IS NULL) AND (active_target_digest IS NULL) AND (active_attempt_epoch IS NULL)) OR ((status = 'RECONCILIATION_REQUIRED'::text) AND (terminal_at IS NULL))))"),
                Map.entry("praxis_bulk_execution_status_check", "CHECK ((status = ANY (ARRAY['RUNNING'::text, 'UNIT_IN_FLIGHT'::text, 'UNIT_COMMITTED_PENDING_ACK'::text, 'COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text, 'STOPPED'::text, 'RECONCILIATION_REQUIRED'::text])))"),
                Map.entry("praxis_bulk_execution_terminal_reason_check", "CHECK ((((status = 'STOPPED'::text) = (terminal_reason_code IS NOT NULL)) AND ((terminal_reason_code IS NULL) OR (terminal_reason_code = ANY (ARRAY['LEGACY_REASON_NOT_RECORDED'::text, 'DEADLINE_EXCEEDED'::text, 'AUTHORIZATION_REVOKED'::text, 'POLICY_BLOCKED'::text, 'COMMON_GOVERNANCE_CHANGED'::text, 'COMMON_GOVERNANCE_UNAVAILABLE'::text, 'DEPENDENCY_UNAVAILABLE'::text, 'UNIT_ROLLED_BACK'::text, 'RECOVERY_STOPPED'::text, 'EVALUATOR_UNAVAILABLE'::text, 'STRUCTURAL_REVISION_CHANGED'::text])))))"),
                Map.entry("praxis_bulk_execution_active_unit_deadline_check", "CHECK (((active_unit_deadline_at IS NULL) OR ((active_attempt_id IS NOT NULL) AND (active_unit_deadline_at <= deadline_at))))"),
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
                Map.entry("praxis_bulk_item_receipt_unit_deadline_check", "CHECK (((unit_deadline_at IS NULL) OR (confirmed_at < unit_deadline_at)))"),
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
        validateDurableTrigger(connection, EXECUTION_TABLE, TERMINAL_REASON_TRIGGER, TERMINAL_REASON_FUNCTION,
                "CREATE TRIGGER praxis_bulk_execution_protect_terminal_reason BEFORE INSERT OR UPDATE ON praxis_bulk.praxis_bulk_execution FOR EACH ROW EXECUTE FUNCTION praxis_bulk.protect_praxis_bulk_terminal_reason()",
                """
                begin
                    if tg_op = 'INSERT' then
                        if new.terminal_reason_code = 'LEGACY_REASON_NOT_RECORDED' then
                            raise exception 'legacy stop reason is reserved for migration history' using errcode = '55000';
                        end if;
                    else
                        if old.status = 'STOPPED'
                           and new.terminal_reason_code is distinct from old.terminal_reason_code then
                            raise exception 'terminal stop reason is immutable' using errcode = '55000';
                        end if;
                        if old.status is distinct from 'STOPPED'
                           and new.status = 'STOPPED'
                           and new.terminal_reason_code = 'LEGACY_REASON_NOT_RECORDED' then
                            raise exception 'legacy stop reason is reserved for migration history' using errcode = '55000';
                        end if;
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

    /** V4 never widens the meaning of a V3 receipt: admission certifies no mutation. */
    private static void validateDurableAdmission(Connection connection) throws SQLException {
        validateDurableColumns(connection, ADMISSION_TABLE, Map.ofEntries(
                Map.entry("execution_id", "uuid|true"),
                Map.entry("unit_ordinal", "integer|true"),
                Map.entry("target_digest", "text|true"),
                Map.entry("expected_version", "text|true"),
                Map.entry("attempt_id", "uuid|true"),
                Map.entry("owner_epoch", "bigint|true"),
                Map.entry("outcome", "text|true"),
                Map.entry("reason_code", "text|true"),
                Map.entry("recorded_at", "timestamp(6) with time zone|true")));
        validateDurableConstraints(connection, ADMISSION_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_admission_pkey", "PRIMARY KEY (execution_id, unit_ordinal)"),
                Map.entry("praxis_bulk_admission_target_key", "UNIQUE (execution_id, target_digest)"),
                Map.entry("praxis_bulk_admission_attempt_key", "UNIQUE (attempt_id)"),
                Map.entry("praxis_bulk_admission_execution_fkey", "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id)"),
                Map.entry("praxis_bulk_admission_ordinal_check", "CHECK ((unit_ordinal >= 0))"),
                Map.entry("praxis_bulk_admission_target_digest_check", "CHECK ((target_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_admission_expected_version_nonblank_check", "CHECK ((btrim(expected_version) <> ''::text))"),
                Map.entry("praxis_bulk_admission_epoch_check", "CHECK ((owner_epoch >= 1))"),
                Map.entry("praxis_bulk_admission_outcome_reason_check", "CHECK ((((outcome = 'DENIED'::text) AND (reason_code = 'TARGET_DENIED'::text)) OR ((outcome = 'INVALID'::text) AND (reason_code = ANY (ARRAY['TARGET_NOT_FOUND'::text, 'TARGET_INVALID'::text]))) OR ((outcome = 'CONFLICT'::text) AND (reason_code = ANY (ARRAY['TARGET_VERSION_CONFLICT'::text, 'TARGET_STATE_CONFLICT'::text, 'TARGET_DEPENDENCY_CHANGED'::text])))))")));
        validateDurableTrigger(connection, ADMISSION_TABLE, ADMISSION_TRIGGER, ADMISSION_FUNCTION,
                "CREATE TRIGGER praxis_bulk_admission_reject_mutation BEFORE DELETE OR UPDATE ON praxis_bulk.praxis_bulk_admission FOR EACH ROW EXECUTE FUNCTION praxis_bulk.reject_praxis_bulk_admission_mutation()",
                """
                begin
                    raise exception 'praxis_bulk.praxis_bulk_admission is immutable' using errcode = '55000';
                end;
                """);
        // The host grants SELECT/INSERT to its runtime role explicitly. Both table- and
        // column-level ACLs must reject PUBLIC, mutation rights and grant options.
        try (var statement = connection.prepareStatement("""
                select count(*)
                from (
                    select acl.grantee, acl.privilege_type, acl.is_grantable, c.relowner
                    from pg_class c,
                         lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl
                    where c.oid = ?::regclass
                    union all
                    select acl.grantee, acl.privilege_type, acl.is_grantable, c.relowner
                    from pg_class c join pg_attribute a on a.attrelid = c.oid,
                         lateral aclexplode(a.attacl) acl
                    where c.oid = ?::regclass and a.attnum > 0 and not a.attisdropped
                      and a.attacl is not null
                ) grants
                where grantee <> relowner
                  and (grantee = 0 or privilege_type not in ('SELECT', 'INSERT') or is_grantable)
                """)) {
            statement.setString(1, SCHEMA + "." + ADMISSION_TABLE);
            statement.setString(2, SCHEMA + "." + ADMISSION_TABLE);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                        "admission storage grants must be limited to scoped SELECT/INSERT roles");
            }
        }
    }

    /** Reject contradictory or noncontiguous durable evidence, including cross-table duplicates. */
    private static void validateAdmissionRows(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                with evidence as (
                    select execution_id, unit_ordinal, target_digest, attempt_id, owner_epoch, false as admission
                    from praxis_bulk.praxis_bulk_item_receipt
                    union all
                    select execution_id, unit_ordinal, target_digest, attempt_id, owner_epoch, true as admission
                    from praxis_bulk.praxis_bulk_admission
                ), totals as (
                    select execution_id, count(*) as evidence_count,
                           count(*) filter (where admission) as admission_count,
                           count(distinct unit_ordinal) as ordinal_count,
                           count(distinct target_digest) as target_count,
                           count(distinct attempt_id) as attempt_count,
                           min(unit_ordinal) as first_ordinal, max(unit_ordinal) as last_ordinal,
                           max(owner_epoch) as latest_epoch
                    from evidence group by execution_id
                )
                select count(*)
                from praxis_bulk.praxis_bulk_execution e left join totals t using (execution_id)
                where coalesce(t.evidence_count, 0) <> coalesce(t.ordinal_count, 0)
                   or coalesce(t.evidence_count, 0) <> coalesce(t.target_count, 0)
                   or coalesce(t.evidence_count, 0) <> coalesce(t.attempt_count, 0)
                   or (t.evidence_count > 0 and (t.first_ordinal <> 0 or t.last_ordinal <> t.evidence_count - 1))
                   or t.latest_epoch > e.owner_epoch
                   or t.last_ordinal >= e.target_count
                   or (e.status in ('RUNNING', 'UNIT_IN_FLIGHT', 'STOPPED', 'COMPLETED', 'COMPLETED_WITH_ERRORS')
                       and coalesce(t.evidence_count, 0) <> e.next_ordinal)
                   or (e.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS')
                       and coalesce(t.evidence_count, 0) <> e.target_count)
                   or (e.status = 'COMPLETED' and coalesce(t.admission_count, 0) <> 0)
                   or (e.status = 'COMPLETED_WITH_ERRORS' and coalesce(t.admission_count, 0) = 0)
                   or (e.status = 'UNIT_COMMITTED_PENDING_ACK'
                       and coalesce(t.evidence_count, 0) <> e.next_ordinal + 1)
                   or (e.status = 'UNIT_COMMITTED_PENDING_ACK' and not exists (
                       select 1 from praxis_bulk.praxis_bulk_item_receipt r
                       where r.execution_id = e.execution_id and r.unit_ordinal = e.next_ordinal
                         and r.attempt_id = e.active_attempt_id
                         and r.target_digest = e.active_target_digest
                         and r.owner_epoch = e.active_attempt_epoch))
                   or (e.status = 'UNIT_COMMITTED_PENDING_ACK' and exists (
                       select 1 from praxis_bulk.praxis_bulk_admission a
                       where a.execution_id = e.execution_id and a.unit_ordinal = e.next_ordinal))
                   or (e.status = 'RECONCILIATION_REQUIRED'
                       and coalesce(t.evidence_count, 0) not between e.next_ordinal and e.next_ordinal + 1)
                """)) {
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                        "admission and receipt evidence must form one unambiguous contiguous prefix");
            }
        }
    }

    /** Decode the protected evaluation once per execution and bind each durable outcome to its ordered target. */
    private static void validateEvidenceBinding(Connection connection) throws SQLException {
        var evaluations = new LinkedHashMap<UUID, BulkEvaluationSnapshot>();
        try (var statement = connection.prepareStatement("""
                with evidence as (
                    select execution_id, unit_ordinal, target_digest, expected_version
                    from praxis_bulk.praxis_bulk_item_receipt
                    union all
                    select execution_id, unit_ordinal, target_digest, expected_version
                    from praxis_bulk.praxis_bulk_admission
                )
                select d.execution_id, d.unit_ordinal, d.target_digest, d.expected_version,
                       x.proposal_id, x.namespace_id, x.subject_id, x.resource_key, x.operation_id,
                       x.input_fingerprint, x.evaluation_fingerprint, x.target_count,
                       p.created_at, p.expires_at, p.fingerprint, p.payload,
                       v.input_fingerprint, v.evaluation_fingerprint, v.payload
                from evidence d
                join praxis_bulk.praxis_bulk_execution x using (execution_id)
                join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=x.proposal_id
                join praxis_bulk.praxis_bulk_evaluation v on v.proposal_id=x.proposal_id
                """)) {
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID executionId = rows.getObject(1, UUID.class);
                    try {
                        BulkEvaluationSnapshot evaluation = evaluations.get(executionId);
                        if (evaluation == null) {
                            require(rows.getString(10).equals(rows.getString(15))
                                            && rows.getString(10).equals(rows.getString(17))
                                            && rows.getString(11).equals(rows.getString(18)),
                                    "durable evidence fingerprint binding differs");
                            var intent = BulkSnapshotStorageCodec.decode(rows.getBytes(16), rows.getString(15));
                            var scope = intent.context();
                            require(scope.namespaceId().equals(rows.getString(6))
                                            && scope.subjectId().equals(rows.getString(7))
                                            && scope.resourceKey().equals(rows.getString(8))
                                            && scope.operationRef().operationId().equals(rows.getString(9)),
                                    "durable evidence scope differs");
                            var proposal = new BulkStoredProposal(rows.getObject(5, UUID.class),
                                    rows.getObject(13, OffsetDateTime.class).toInstant(),
                                    rows.getObject(14, OffsetDateTime.class).toInstant(), intent);
                            evaluation = BulkEvaluationStorageCodec.decode(proposal, rows.getBytes(19), rows.getString(11));
                            require(evaluation.targets().size() == rows.getInt(12),
                                    "durable evidence target count differs");
                            evaluations.put(executionId, evaluation);
                        }
                        int ordinal = rows.getInt(2);
                        require(ordinal >= 0 && ordinal < evaluation.targets().size(),
                                "durable evidence ordinal differs");
                        var target = evaluation.targets().get(ordinal).target();
                        require(target.expectedVersion().equals(rows.getString(4))
                                        && evidenceTargetDigest(evaluation, ordinal).equals(rows.getString(3)),
                                "durable evidence target or version differs");
                    } catch (RuntimeException corrupt) {
                        throw new IllegalStateException("durable evidence does not match protected evaluation", corrupt);
                    }
                }
            }
        }
    }

    static String evidenceTargetDigest(BulkEvaluationSnapshot evaluation, int ordinal) {
        var target = evaluation.targets().get(ordinal).target();
        Object id = target.id();
        String type = id instanceof Integer ? "integer" : "string";
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {"praxis.bulk.unit/1", evaluation.fingerprint(),
                    Integer.toString(ordinal), type, id.toString(), target.expectedVersion()}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                hash.update(bytes);
            }
            return "sha256:" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
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
