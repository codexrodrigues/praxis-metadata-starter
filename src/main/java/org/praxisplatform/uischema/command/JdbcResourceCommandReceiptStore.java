package org.praxisplatform.uischema.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Opt-in PostgreSQL receipts. Use the SAME datasource and transaction manager as the mutation.
 * Install db/praxis-command-receipts.sql explicitly; the starter never migrates host storage.
 * A transaction advisory lock serializes retries before the versioned domain lock is acquired.
 */
public final class JdbcResourceCommandReceiptStore implements ResourceCommandIdempotencyStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ObjectMapper receiptReader;
    private final String table;
    private final javax.sql.DataSource transactionDataSource;
    private final jakarta.persistence.EntityManagerFactory entityManagerFactory;

    public JdbcResourceCommandReceiptStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper) {
        this(jdbc, manager, mapper, "praxis_resource_command_receipts");
    }

    public JdbcResourceCommandReceiptStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper, String table) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
        // A stored response is an internal snapshot, including fields marked READ_ONLY for HTTP.
        this.receiptReader = mapper.copy()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setAnnotationIntrospector(new com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector() {
                    @Override public com.fasterxml.jackson.annotation.JsonProperty.Access findPropertyAccess(
                            com.fasterxml.jackson.databind.introspect.Annotated annotated) {
                        return com.fasterxml.jackson.annotation.JsonProperty.Access.AUTO;
                    }
                });
        javax.sql.DataSource source = Objects.requireNonNull(jdbc.getDataSource(), "receipt datasource");
        if (source instanceof org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy proxy)
            source = Objects.requireNonNull(proxy.getTargetDataSource());
        javax.sql.DataSource managed = manager instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager jdbcManager
                ? jdbcManager.getDataSource() : manager instanceof org.springframework.orm.jpa.JpaTransactionManager jpaManager
                ? jpaManager.getDataSource() : null;
        if (managed != source) throw new IllegalArgumentException("Command receipts require a JDBC/JPA transaction manager bound to the same datasource");
        this.transactionDataSource = source;
        this.entityManagerFactory = manager instanceof org.springframework.orm.jpa.JpaTransactionManager jpaManager
                ? jpaManager.getEntityManagerFactory() : null;
        if (table == null || !table.matches("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?"))
            throw new IllegalArgumentException("Invalid command receipt table name");
        this.table = table;
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        // At READ COMMITTED the row becomes visible after the preceding retry releases its lock.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> ResourceRepresentationResult<T> execute(Identity identity, String fingerprint, Class<T> bodyType,
                                                      Runnable authorize, Supplier<ResourceRepresentationResult<T>> mutation) {
        Objects.requireNonNull(identity); Objects.requireNonNull(fingerprint); Objects.requireNonNull(bodyType); Objects.requireNonNull(authorize); Objects.requireNonNull(mutation);
        Integer isolation = org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED)
            throw new IllegalStateException("Command receipts require READ COMMITTED isolation");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Command receipts require a writable transaction");
        String scopeHash = ResourceCommandFingerprint.of(mapper, identity);
        long lock = Long.parseUnsignedLong(scopeHash.substring(0, 16), 16);
        return transaction.execute(status -> {
            if (entityManagerFactory != null) {
                var entityManager = org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
                if (entityManager == null || !entityManager.isJoinedToTransaction()
                        || entityManager.unwrap(org.hibernate.Session.class).isDirty())
                    throw new IllegalStateException("Command receipts require a clean joined JPA persistence context before authorization");
            }
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                var actual = org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(connection);
                if (actual.getAutoCommit() || actual.isReadOnly()
                        || actual.getTransactionIsolation() != java.sql.Connection.TRANSACTION_READ_COMMITTED
                        || !org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(actual, transactionDataSource))
                    throw new IllegalStateException("Command receipts require a bound writable READ COMMITTED JDBC transaction");
                return null;
            });
            jdbc.query("select pg_advisory_xact_lock(?)", rs -> { }, lock);
            authorize.run(); // Recheck current item access under this transaction, including historical replay.
            var receipts = jdbc.query("select fingerprint, response_body, response_version from " + table + " where scope_hash = ?",
                    (rs, row) -> new Receipt(rs.getString(1), rs.getString(2), rs.getLong(3)), scopeHash);
            if (!receipts.isEmpty()) {
                Receipt receipt = receipts.getFirst();
                if (!receipt.fingerprint.equals(fingerprint)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency-Key was already used with a different command payload or If-Match");
                try { return ResourceRepresentationResult.versioned(receiptReader.readValue(receipt.body, bodyType), receipt.version); }
                catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot read command receipt", ex); }
            }
            ResourceRepresentationResult<T> result = Objects.requireNonNull(mutation.get());
            long version = result.requirePersistedVersion();
            try {
                jdbc.update("insert into " + table + " (scope_hash, fingerprint, response_body, response_version) values (?, ?, ?, ?)",
                        scopeHash, fingerprint, mapper.writeValueAsString(result.body()), version);
            } catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize command receipt", ex); }
            return result;
        });
    }

    private record Receipt(String fingerprint, String body, long version) { }
}
