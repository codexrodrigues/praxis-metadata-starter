package org.praxisplatform.uischema.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL proof: serialization of retries, transactional effects and historical receipts. */
class JdbcResourceCommandReceiptStoreTest {
    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static JdbcResourceCommandReceiptStore store;
    static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeAll static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        var ds = postgres.getPostgresDatabase();
        new ResourceDatabasePopulator(new ClassPathResource("db/praxis-command-receipts.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
        store = new JdbcResourceCommandReceiptStore(jdbc, manager, mapper);
        jdbc.execute("create table command_effects(id integer primary key, value integer not null)");
    }
    @AfterAll static void stop() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void reset() {
        jdbc.update("delete from praxis_resource_command_receipts");
        jdbc.update("delete from command_effects"); jdbc.update("insert into command_effects values (1, 0)");
    }

    @Test void retryReplaysHistoricalBodyAndRevisionWithoutRepeatingEffect() {
        var first = store.execute(identity("one"), "payload+etag", Body.class, () -> { }, () -> mutate("APPLIED", 7));
        jdbc.update("update command_effects set value = 10 where id = 1");
        var retry = store.execute(identity("one"), "payload+etag", Body.class, () -> { }, () -> { fail("replay must precede mutation"); return null; });
        assertEquals(first, retry); assertEquals(7, retry.requirePersistedVersion());
        assertEquals(10, effect()); assertEquals(1, jdbc.queryForObject("select count(*) from praxis_resource_command_receipts", Integer.class));
        var conflict = assertThrows(ResponseStatusException.class, () -> store.execute(identity("one"), "other-payload-or-etag", Body.class, () -> { },
                () -> mutate("OTHER", 8)));
        assertEquals(409, conflict.getStatusCode().value()); assertEquals(10, effect());
    }

    @Test void currentAuthorizationRunsInTheReceiptTransactionOnFreshExecutionAndReplay() {
        AtomicInteger checks = new AtomicInteger();
        Runnable authorize = () -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            checks.incrementAndGet();
        };
        store.execute(identity("authorized"), "x", Body.class, authorize, () -> mutate("APPLIED", 1));
        store.execute(identity("authorized"), "x", Body.class, authorize, () -> { fail("replay"); return null; });
        assertEquals(2, checks.get()); assertEquals(1, effect());
        var denied = assertThrows(ResponseStatusException.class, () -> store.execute(identity("authorized"), "x", Body.class,
                () -> { throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN); },
                () -> { fail("denied"); return null; }));
        assertEquals(403, denied.getStatusCode().value()); assertEquals(1, effect()); assertEquals(1, receiptCount());
    }

    @Test void mutationAndReceiptRollbackTogetherIncludingMissingRevisionAndOuterRollback() {
        assertThrows(IllegalStateException.class, () -> store.execute(identity("rollback"), "x", Body.class, () -> { }, () -> {
            mutate("FAILED", 1); throw new IllegalStateException("fail after effect");
        }));
        assertEquals(0, effect()); assertEquals(0, receiptCount());
        var outer = new TransactionTemplate(manager);
        outer.executeWithoutResult(status -> { store.execute(identity("outer"), "x", Body.class, () -> { }, () -> mutate("OUTER", 1)); status.setRollbackOnly(); });
        assertEquals(0, effect()); assertEquals(0, receiptCount());
        assertThrows(IllegalStateException.class, () -> store.execute(identity("unversioned"), "x", Body.class, () -> { }, () -> {
            jdbc.update("update command_effects set value = 1");
            return ResourceRepresentationResult.unversioned(new Body("UNCERTIFIED"));
        }));
        assertEquals(0, effect()); assertEquals(0, receiptCount());
    }

    @Test void concurrentIdenticalRetriesCommitExactlyOnce() throws Exception {
        AtomicInteger mutations = new AtomicInteger();
        try (ExecutorService workers = Executors.newFixedThreadPool(6)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResourceRepresentationResult<Body>>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(workers.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return store.execute(identity("concurrent"), "same", Body.class, () -> { }, () -> {
                    mutations.incrementAndGet(); return mutate("ONCE", 1);
                });
            }));
            start.countDown();
            for (var result : results) assertEquals(new Body("ONCE"), result.get(20, TimeUnit.SECONDS).body());
        }
        assertEquals(1, mutations.get()); assertEquals(1, effect()); assertEquals(1, receiptCount());
    }

    @Test void keysAreIsolatedByActorScopeResourceItemAndCommand() {
        var first = identity("shared");
        var identities = List.of(first,
                new ResourceCommandIdempotencyStore.Identity("other-tenant", "actor", "items", "1", "approve", "shared"),
                new ResourceCommandIdempotencyStore.Identity("tenant", "other-actor", "items", "1", "approve", "shared"),
                new ResourceCommandIdempotencyStore.Identity("tenant", "actor", "other-items", "1", "approve", "shared"),
                new ResourceCommandIdempotencyStore.Identity("tenant", "actor", "items", "2", "approve", "shared"),
                new ResourceCommandIdempotencyStore.Identity("tenant", "actor", "items", "1", "reopen", "shared"));
        identities.forEach(identity -> store.execute(identity, "x", Body.class, () -> { }, () -> mutate("APPLIED", 1)));
        assertEquals(6, effect()); assertEquals(6, receiptCount());
    }

    @Test void fingerprintsCanonicalizeObjectOrderButBindPayloadAndPrecondition() {
        var left = new LinkedHashMap<String, Object>(); left.put("ifMatch", "v1"); left.put("payload", Map.of("x", 1, "y", 2));
        var right = new LinkedHashMap<String, Object>(); right.put("payload", Map.of("y", 2, "x", 1)); right.put("ifMatch", "v1");
        assertEquals(ResourceCommandFingerprint.of(mapper, left), ResourceCommandFingerprint.of(mapper, right));
        right.put("ifMatch", "v2");
        assertNotEquals(ResourceCommandFingerprint.of(mapper, left), ResourceCommandFingerprint.of(mapper, right));
        assertThrows(IllegalArgumentException.class, () -> new JdbcResourceCommandReceiptStore(jdbc, manager, mapper, "a;drop table a"));
    }

    @Test void rejectsAReceiptDatasourceDifferentFromTheTransactionManager() {
        var different = new JdbcTemplate(postgres.getPostgresDatabase());
        assertNotSame(jdbc.getDataSource(), different.getDataSource());
        assertThrows(IllegalArgumentException.class, () -> new JdbcResourceCommandReceiptStore(different, manager, mapper));
    }

    @Test void rejectsEffectiveRepeatableReadEvenWhenOuterTransactionUsesDefaultIsolation() {
        var source = new org.springframework.jdbc.datasource.DelegatingDataSource(jdbc.getDataSource()) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                return connection;
            }
        };
        var transactions = new DataSourceTransactionManager(source);
        var guarded = new JdbcResourceCommandReceiptStore(new JdbcTemplate(source), transactions, mapper);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertNull(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            assertThrows(IllegalStateException.class, () -> guarded.execute(identity("isolation"), "x", Body.class, () -> { },
                    () -> mutate("MUST NOT RUN", 1)));
            status.setRollbackOnly();
        });
        assertEquals(0, effect()); assertEquals(0, receiptCount());
    }

    @Test void jpaMutationAndJdbcReceiptUseTheSameTransactionAndRollbackTogether() {
        var configuration = new org.hibernate.cfg.Configuration().addAnnotatedClass(VersionedItem.class)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD");
        configuration.getProperties().put("hibernate.connection.datasource", postgres.getPostgresDatabase());
        try (var factory = configuration.buildSessionFactory()) {
            var jpaManager = new org.springframework.orm.jpa.JpaTransactionManager(factory);
            jpaManager.setDataSource(jdbc.getDataSource());
            jpaManager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());
            var em = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
            var service = new VersionedItemService(new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em)
                    .getRepository(VersionedItemRepository.class));
            org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", em);
            var receipts = new JdbcResourceCommandReceiptStore(jdbc, jpaManager, mapper);
            jdbc.update("insert into p1_receipt_item (id, revision, state) values (1, 0, 'OPEN')");
            var etags = new org.praxisplatform.uischema.concurrency.ResourceVersionEtagService("jpa-receipt-test");
            var condition = new org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition<>(etags,
                    etags.create("items", 1L, 0), "items", 1L);
            assertThrows(IllegalStateException.class, () -> receipts.execute(identity("jpa-rollback"), "x", Body.class, () -> { }, () -> {
                service.update(1L, "APPROVED", condition);
                throw new IllegalStateException("fail after JPA flush, before receipt");
            }));
            assertEquals("OPEN", jdbc.queryForObject("select state from p1_receipt_item where id = 1", String.class));
            assertEquals(0, receiptCount());
            var first = receipts.execute(identity("jpa-success"), "same", Body.class, () -> { },
                    () -> service.update(1L, "APPROVED", condition));
            assertEquals(1, first.requirePersistedVersion());
            assertEquals("APPROVED", jdbc.queryForObject("select state from p1_receipt_item where id = 1", String.class));
            var replay = receipts.execute(identity("jpa-success"), "same", Body.class, () -> { },
                    () -> { fail("JPA mutation must not run again"); return null; });
            assertEquals(first, replay); assertEquals(1, receiptCount());
            new TransactionTemplate(jpaManager).executeWithoutResult(status -> {
                em.find(VersionedItem.class, 1L).state = "UNAUTHORIZED DIRTY STATE";
                assertThrows(IllegalStateException.class, () -> receipts.execute(identity("dirty-jpa"), "x", Body.class,
                        () -> fail("authorization query could autoflush dirty state"), () -> mutate("MUST NOT RUN", 2)));
                status.setRollbackOnly();
            });
            assertEquals("APPROVED", jdbc.queryForObject("select state from p1_receipt_item where id = 1", String.class));
            assertEquals(1, receiptCount());
        }
    }

    @jakarta.persistence.Entity(name = "P1ReceiptItem") @jakarta.persistence.Table(name = "p1_receipt_item")
    public static class VersionedItem {
        @jakarta.persistence.Id public Long id;
        @jakarta.persistence.Version public Long revision;
        public String state;
    }
    public interface VersionedItemRepository extends org.praxisplatform.uischema.repository.base.BaseCrudRepository<VersionedItem, Long> { }
    public static class Filter implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO { }
    public static class VersionedItemService extends org.praxisplatform.uischema.service.base.AbstractBaseResourceService<VersionedItem, Body, Long, Filter, String, String>
            implements org.praxisplatform.uischema.service.base.VersionedCreateUpdateResourceService<Body, Long, Filter, String, String> {
        VersionedItemService(VersionedItemRepository repository) { super(repository, VersionedItem.class); }
        protected org.praxisplatform.uischema.mapper.base.ResourceMapper<VersionedItem, Body, String, String, Long> getResourceMapper() {
            return new org.praxisplatform.uischema.mapper.base.ResourceMapper<>() {
                public Body toResponse(VersionedItem entity) { return new Body(entity.state); }
                public VersionedItem newEntity(String dto) { throw new UnsupportedOperationException(); }
                public void applyUpdate(VersionedItem entity, String dto) { entity.state = dto; }
                public Long extractId(VersionedItem entity) { return entity.id; }
            };
        }
    }

    static ResourceCommandIdempotencyStore.Identity identity(String key) {
        return new ResourceCommandIdempotencyStore.Identity("tenant", "actor", "items", "1", "approve", key);
    }
    static ResourceRepresentationResult<Body> mutate(String value, long version) {
        jdbc.update("update command_effects set value = value + 1 where id = 1");
        return ResourceRepresentationResult.versioned(new Body(value), version);
    }
    static int effect() { return jdbc.queryForObject("select value from command_effects where id = 1", Integer.class); }
    static int receiptCount() { return jdbc.queryForObject("select count(*) from praxis_resource_command_receipts", Integer.class); }
    public record Body(String state) { }
}
