package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.bulk.persistence.fixture.InfrastructureDomainRow;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL processes, JPA/JDBC physical transactions and independent observer connections. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkExecutionInfrastructurePostgresTest {
    private EmbeddedPostgres postgres;
    private DataSource dataSource;
    private EntityManagerFactory emf;
    private JpaTransactionManager manager;
    private BulkExecutionInfrastructure infrastructure;
    private JdbcTemplate observer;

    @BeforeAll
    void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        dataSource = postgres.getPostgresDatabase();
        observer = new JdbcTemplate(dataSource);
        System.out.println("Bulk infrastructure proof PostgreSQL: " + observer.queryForObject("select version()", String.class));
        observer.execute("create table bulk_test_domain(id bigint primary key)");
        observer.execute("create table bulk_test_receipt(id bigint primary key, marker text unique deferrable initially deferred)");
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(InfrastructureDomainRow.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.afterPropertiesSet();
        emf = factory.getObject();
        manager = new JpaTransactionManager(emf);
        infrastructure = new BulkExecutionInfrastructure(dataSource, manager, "tenant-a:production:payroll");
    }

    @AfterAll
    void stop() throws Exception {
        try { if (emf != null) emf.close(); }
        finally { if (postgres != null) postgres.close(); }
    }

    @BeforeEach
    void reset() { observer.execute("truncate bulk_test_domain, bulk_test_receipt"); }

    @Test
    void jpaAndJdbcSharePhysicalTransactionAndBecomeVisibleOnlyAfterCommit() {
        new TransactionTemplate(manager).execute(status -> {
            var em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
            em.persist(new InfrastructureDomainRow(1));
            em.flush();
            int jpaPid = ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
            infrastructure.withConnection(connection -> {
                assertThat(number(connection, "select pg_backend_pid()")).isEqualTo(jpaPid);
                assertThat(number(connection, "select count(*) from bulk_test_domain")).isEqualTo(1);
                execute(connection, "insert into bulk_test_receipt values (1, 'one')");
                try (var second = dataSource.getConnection()) {
                    assertThat(number(second, "select pg_backend_pid()")).isNotEqualTo(jpaPid);
                    assertThat(number(second, "select count(*) from bulk_test_domain")).isZero();
                    assertThat(number(second, "select count(*) from bulk_test_receipt")).isZero();
                }
                return null;
            });
            return null;
        });
        assertCounts(1, 1);
    }

    @Test
    void outerExceptionRollsBackBothWrites() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            writeBoth();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertCounts(0, 0);
    }

    @Test
    void explicitOuterRollbackRollsBackBothWrites() {
        new TransactionTemplate(manager).execute(status -> { writeBoth(); status.setRollbackOnly(); return null; });
        assertCounts(0, 0);
    }

    @Test
    void caughtCallbackFailureStillMarksOuterTransactionRollbackOnly() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            writeBoth();
            assertThatThrownBy(() -> infrastructure.withConnection(connection -> {
                throw new IllegalArgumentException("callback failed");
            })).isInstanceOf(IllegalArgumentException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertCounts(0, 0);
    }

    @Test
    void deferredCommitFailureDoesNotLeaveEitherWriteDurable() {
        var callbackCompleted = new AtomicBoolean();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            writeBoth();
            infrastructure.withConnection(connection -> {
                execute(connection, "insert into bulk_test_receipt values (2, 'one')");
                return null;
            });
            callbackCompleted.set(true);
            return null; // the deferred UNIQUE is checked by PostgreSQL at COMMIT
        })).isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(SQLException.class);
        assertThat(callbackCompleted).isTrue();
        assertCounts(0, 0);
    }

    @Test
    void absenceOfTransactionFailsBeforeCallback() {
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> infrastructure.withConnection(connection -> { called.set(true); return null; }))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(called).isFalse();
    }

    @Test
    void readonlyAndRollbackOnlyTransactionsRejectCallback() {
        var readonly = new TransactionTemplate(manager); readonly.setReadOnly(true);
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> readonly.execute(status -> infrastructure.withConnection(connection -> {
            called.set(true); return null;
        }))).isInstanceOf(IllegalStateException.class);
        new TransactionTemplate(manager).execute(status -> {
            EntityManagerFactoryUtils.getTransactionalEntityManager(emf).getTransaction().setRollbackOnly();
            status.setRollbackOnly();
            assertThatThrownBy(() -> infrastructure.withConnection(connection -> { called.set(true); return null; }))
                    .isInstanceOf(IllegalStateException.class);
            return null;
        });
        assertThat(called).isFalse();
    }

    @Test
    void unrelatedManagerOrJdbcOnlyTransactionCannotMasqueradeAsJpaTransaction() {
        DataSource other = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "");
        for (var txManager : new DataSourceTransactionManager[] {
                new DataSourceTransactionManager(other), new DataSourceTransactionManager(dataSource) }) {
            assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(status ->
                    infrastructure.withConnection(connection -> { throw new AssertionError("must not run"); })))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }
        assertCounts(0, 0);
    }

    @Test
    void jdbcManagerParticipatesAndRollsBackWithoutStartingASecondTransaction() {
        var jdbcManager = new DataSourceTransactionManager(dataSource);
        var jdbcInfrastructure = new BulkExecutionInfrastructure(dataSource, jdbcManager, "jdbc-test");
        new TransactionTemplate(jdbcManager).execute(status -> {
            jdbcInfrastructure.withConnection(connection -> {
                execute(connection, "insert into bulk_test_receipt values (1, 'one')");
                return null;
            });
            status.setRollbackOnly();
            return null;
        });
        assertCounts(0, 0);
    }

    @Test
    void independentConnectionCannotAcquireHeldRowLockUntilOwnerCompletes() throws Exception {
        observer.update("insert into bulk_test_domain values (1)");
        var jdbcManager = new DataSourceTransactionManager(dataSource);
        var binding = new BulkExecutionInfrastructure(dataSource, jdbcManager, "lock-test");
        var secondPid = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(jdbcManager).execute(status -> binding.withConnection(connection -> {
                int firstPid = number(connection, "select pg_backend_pid()");
                number(connection, "select id from bulk_test_domain where id=1 for update");
                var waiter = executor.submit(() -> new TransactionTemplate(jdbcManager).execute(otherStatus ->
                        binding.withConnection(other -> {
                            secondPid.set(number(other, "select pg_backend_pid()"));
                            execute(other, "set local lock_timeout = '200ms'");
                            return number(other, "select id from bulk_test_domain where id=1 for update");
                        })));
                try {
                    assertThatThrownBy(() -> waiter.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(SQLException.class)
                            .satisfies(error -> {
                                Throwable cause = error;
                                while (cause.getCause() != null) cause = cause.getCause();
                                assertThat(((SQLException) cause).getSQLState()).isEqualTo("55P03");
                            });
                    assertThat(secondPid.get()).isPositive().isNotEqualTo(firstPid);
                } finally { waiter.cancel(true); }
                return null;
            }));
        }
        new TransactionTemplate(jdbcManager).execute(status -> binding.withConnection(connection -> {
            assertThat(number(connection, "select id from bulk_test_domain where id=1 for update nowait")).isEqualTo(1);
            return null;
        }));
    }

    private void writeBoth() {
        var em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
        em.persist(new InfrastructureDomainRow(1)); em.flush();
        infrastructure.withConnection(connection -> {
            execute(connection, "insert into bulk_test_receipt values (1, 'one')");
            return null;
        });
    }

    private void assertCounts(int domain, int receipt) {
        assertThat(observer.queryForObject("select count(*) from bulk_test_domain", Integer.class)).isEqualTo(domain);
        assertThat(observer.queryForObject("select count(*) from bulk_test_receipt", Integer.class)).isEqualTo(receipt);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private int number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getInt(1);
        }
    }
}
