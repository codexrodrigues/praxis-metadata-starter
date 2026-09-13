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
    private static final String REJECTION_FUNCTION = "reject_praxis_bulk_proposal_update";
    private static final String REJECTION_TRIGGER = "praxis_bulk_proposal_reject_update";

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
            assertPostgreSql(connection);
            validateProposalTable(connection);
            validateColumns(connection);
            validatePrimaryKey(connection);
            validateChecks(connection);
            validateImmutableUpdateTrigger(connection);
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
                    select p.proname
                    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                    where n.nspname = ? and p.prokind in ('f', 'p')
                    """);
            Set<String> types = queryNames(connection, """
                    select t.typname
                    from pg_type t join pg_namespace n on n.oid = t.typnamespace
                    where n.nspname = ? and t.typrelid = 0 and t.typtype in ('d', 'e', 'r')
                    """);
            Set<String> triggers = queryNames(connection, """
                    select t.tgname
                    from pg_trigger t join pg_class c on c.oid = t.tgrelid
                        join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and not t.tgisinternal
                    """);

            if (relations.isEmpty() && functions.isEmpty() && types.isEmpty() && triggers.isEmpty()) return;
            if (!relations.contains(HISTORY_TABLE)
                    || !relations.stream().allMatch(Set.of(HISTORY_TABLE, PROPOSAL_TABLE)::contains)
                    || !functions.stream().allMatch(Set.of(REJECTION_FUNCTION)::contains)
                    || !types.isEmpty()
                    || !triggers.stream().allMatch(Set.of(REJECTION_TRIGGER)::contains)) {
                throw new IllegalStateException("Refusing an unknown nonempty praxis_bulk schema");
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

    private static void validateImmutableUpdateTrigger(Connection connection) throws SQLException {
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
            statement.setString(2, PROPOSAL_TABLE);
            statement.setString(3, REJECTION_TRIGGER);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "immutable update trigger is missing");
                String definition = normalizeExpression(result.getString(2));
                require("O".equals(result.getString(1))
                                && definition.equals("createtriggerpraxis_bulk_proposal_reject_updatebeforeupdateonpraxis_bulk.praxis_bulk_proposalforeachrowexecutefunctionpraxis_bulk.reject_praxis_bulk_proposal_update()")
                                && "plpgsql".equals(result.getString(4))
                                && "trigger".equals(result.getString(5))
                                && !result.getBoolean(6)
                                && normalizeExpression(result.getString(3)).equals(
                                        "beginraiseexception'praxis_bulk.praxis_bulk_proposal is immutable'usingerrcode='55000';end;")
                                && !result.next(),
                        "immutable update trigger differs from V1");
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

    private record ColumnDefinition(String dataType, boolean nullable, String defaultValue, String generated,
                                    Integer dateTimePrecision) { }
    private record ConstraintDefinition(boolean validated, String expression) { }
}
