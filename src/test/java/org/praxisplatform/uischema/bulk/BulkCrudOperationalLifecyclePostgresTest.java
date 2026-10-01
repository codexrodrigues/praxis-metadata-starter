package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BulkCrudOperationalLifecyclePostgresTest {
    static final String NAMESPACE = "crud-test";

    @Test
    void bothFamiliesPublishWithCasAndShareOneUnlockedResponseCapture() throws Exception {
        try (var fixture = fixture()) {
            for (var id : List.of("crud.uniform", "crud.items")) {
                assertEquals(1, fixture.lifecycle.publish(identity(id), 0).generation());
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.publish(identity(id), 0));
            }
            int before = fixture.fresh.get();
            var projected = fixture.lifecycle.projectReadyCapabilities("crud.items", contracts -> {
                assertUnlocked(fixture.documents);
                assertEquals(Set.of("bulk-update", "bulk-update-items"), contracts.keySet());
                for (var id : List.of("crud.uniform", "crud.items")) {
                    assertEquals(1, fixture.lifecycle.requireReady(identity(id)).generation());
                    assertEquals(1, fixture.lifecycle.requireReady(identity(id)).generation());
                }
                assertTrue(fixture.lifecycle.projectReadyActions(List.of()).isEmpty());
                return contracts;
            });
            assertEquals(before + 1, fixture.fresh.get());
            assertEquals(BulkMode.PER_ITEM_UPDATE, projected.get("bulk-update-items").mode());
            fixture.lifecycle.projectReadyCapabilities("crud.items", contracts -> contracts);
            assertEquals(before + 2, fixture.fresh.get(), "each response owns a new fresh composition");
            assertEquals(2, fixture.lifecycle.suspend(identity("crud.uniform"), 1));
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(identity("crud.items")));
            assertTrue(fixture.lifecycle.<Boolean>projectReadyCapabilities("crud.items", Map::isEmpty).booleanValue());
            assertEquals(3, fixture.lifecycle.publish(identity("crud.items"), 2).generation());
        }
    }

    @Test
    void finalFenceRejectsSuspensionRepublishAndCaughtProviderDriftWithoutRetry() throws Exception {
        try (var fixture = fixture()) {
            fixture.lifecycle.publish(identity("crud.uniform"), 0);
            fixture.lifecycle.publish(identity("crud.items"), 0);
            var calls = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.projectReadyCapabilities("crud.items", contracts -> {
                calls.incrementAndGet();
                assertUnlocked(fixture.documents);
                fixture.lifecycle.suspend(identity("crud.uniform"), 1);
                fixture.lifecycle.publish(identity("crud.uniform"), 2);
                return contracts;
            }));
            assertEquals(1, calls.get());
            var provider = fixture.providers.getFirst();
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.projectReadyCapabilities("crud.items", contracts -> {
                provider.revision = "r2";
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(identity("crud.uniform")));
                provider.revision = "r1";
                return false; // host swallowed its denial; the final fence must still fail.
            }));
            assertFalse(fixture.lifecycle.<Boolean>projectReadyCapabilities("crud.items", Map::isEmpty).booleanValue());
            var failure = new IllegalArgumentException("consumer failure");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> fixture.lifecycle.projectReadyCapabilities("crud.items", contracts -> { throw failure; })));
            assertFalse(fixture.lifecycle.<Boolean>projectReadyCapabilities("crud.items", Map::isEmpty).booleanValue());
        }
    }

    @Test
    void removingProviderClosesReadinessButStillAllowsDurableSuspensionAndCacheInvalidation() throws Exception {
        try (var fixture = fixture()) {
            fixture.lifecycle.publish(identity("crud.uniform"), 0);
            // A replacement host composition no longer installs that provider; the existing row
            // must still be suspendable without trusting the removed provider's getters.
            var source = (OpenApiDocsSupport) ReflectionTestUtils.getField(fixture.documents, "openApiDocsSupport");
            try (var replacementClient = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10))) {
            var replacementDocuments = new CachedOpenApiDocumentService(replacementClient, new ObjectMapper(), source, true);
            ReflectionTestUtils.setField(replacementDocuments, "openApiBasePath", "/v3/api-docs");
            var without = new BulkOperationLifecycle(fixture.bindings,
                    new OpenApiCanonicalOperationResolver(replacementDocuments,
                            fixture.context.getBean(RequestMappingHandlerMapping.class), fixture.bindings, List.of("crud")), replacementDocuments,
                    mock(ActionDefinitionRegistry.class), BulkCrudStructuralCompilerTest.mapper(),
                    new FilteredSchemaReferenceResolver(), fixture.store.runtime, fixture.store.control, List.of());
            assertThrows(IllegalStateException.class, () -> without.requireReady(identity("crud.uniform")));
            assertThrows(IllegalStateException.class, () -> without.publish(identity("crud.uniform"), 1));
            assertTrue(without.<Boolean>projectReadyCapabilities("crud.items", Map::isEmpty).booleanValue());
            assertEquals(2, without.suspend(identity("crud.uniform"), 1));
            assertEquals("SUSPENDED", fixture.store.state("crud.uniform").state());
            }
        }
    }

    static BulkOperationControlIdentity identity(String id) { return new BulkOperationControlIdentity(NAMESPACE, id); }

    static void assertUnlocked(CachedOpenApiDocumentService documents) {
        var lock = (java.util.concurrent.locks.ReentrantReadWriteLock) ReflectionTestUtils.getField(documents, "cacheLifecycleLock");
        var prepare = (java.util.concurrent.locks.ReentrantLock) ReflectionTestUtils.getField(documents, "compositionPreparationLock");
        assertFalse(lock.isWriteLockedByCurrentThread());
        assertEquals(0, lock.getReadHoldCount());
        assertFalse(prepare.isHeldByCurrentThread());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private Fixture fixture() throws Exception {
        var store = new Store(List.of("crud.uniform", "crud.items"));
        var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class);
        var fresh = new AtomicInteger();
        JsonNode value = BulkCrudOperationalCompositionTest.document();
        var source = new OpenApiDocsSupport() {
            @Override public String resolveGroupFromPath(String path) { return "crud"; }
            @Override public JsonNode fetchOpenApiDocument(RestTemplate client, String base, String group, org.slf4j.Logger logger) { return value.deepCopy(); }
            @Override public JsonNode fetchOpenApiGroupDocument(RestTemplate client, String base, String group, org.slf4j.Logger logger) { return value.deepCopy(); }
            @Override public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate client, String base, String group, org.slf4j.Logger logger) {
                fresh.incrementAndGet(); return value.deepCopy();
            }
        };
        var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
        var documents = new CachedOpenApiDocumentService(client, new ObjectMapper(), source, true);
        ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");
        var mvc = context.getBean(RequestMappingHandlerMapping.class);
        var bindings = BulkResourceOperationBindings.from(mvc);
        var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("crud"));
        var providers = List.of(new BulkCrudOperationalCompositionTest.Provider("crud.uniform", "r1",
                        BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, store.runtime),
                new BulkCrudOperationalCompositionTest.Provider("crud.items", "r1",
                        BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, store.runtime));
        var lifecycle = new BulkOperationLifecycle(bindings, resolver, documents, mock(ActionDefinitionRegistry.class),
                BulkCrudStructuralCompilerTest.mapper(), new FilteredSchemaReferenceResolver(), store.runtime, store.control,
                List.copyOf(providers));
        return new Fixture(store, context, client, documents, bindings, resolver, providers, lifecycle, fresh);
    }

    record Fixture(Store store, org.springframework.web.context.support.AnnotationConfigWebApplicationContext context,
            OpenApiInternalRestTemplate client, CachedOpenApiDocumentService documents, BulkResourceOperationBindings bindings,
            OpenApiCanonicalOperationResolver resolver, List<BulkCrudOperationalCompositionTest.Provider> providers,
            BulkOperationLifecycle lifecycle, AtomicInteger fresh) implements AutoCloseable {
        public void close() throws Exception { try { context.close(); } finally { try { client.close(); } finally { store.close(); } } }
    }

    static final class Store implements AutoCloseable {
        final EmbeddedPostgres postgres;
        final BulkExecutionInfrastructure runtime;
        final BulkControlPlaneInfrastructure control;
        Store(List<String> operations) throws Exception {
            postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
            var admin = postgres.getPostgresDatabase();
            BulkExecutionMigrator.migrateWithOperations(admin, Map.of(NAMESPACE, "deployment-test"),
                    operations.stream().map(BulkCrudOperationalLifecyclePostgresTest::identity).toList());
            BulkPostgresTestSupport.grantRuntimeRole(admin, "bulk_runtime_test");
            BulkPostgresTestSupport.grantControlRole(admin, "bulk_control_test");
            var roles = new BulkExecutionRoleConfiguration("postgres", Set.of("bulk_runtime_test"), Set.of(), Set.of("bulk_control_test"));
            var runtimeDs = BulkPostgresTestSupport.runtimeDataSource(postgres);
            var controlDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control_test", "postgres"), "bulk_control_test", "");
            runtime = new BulkExecutionInfrastructure(runtimeDs, new DataSourceTransactionManager(runtimeDs), NAMESPACE, "deployment-test", roles);
            control = new BulkControlPlaneInfrastructure(controlDs, new DataSourceTransactionManager(controlDs), NAMESPACE, "deployment-test", "bulk_control_test", runtime);
        }
        JdbcBulkOperationControl.Snapshot state(String operation) {
            return runtime.withLifecycleRead(connection -> JdbcBulkOperationControl.lockForAdmission(connection, NAMESPACE, operation));
        }
        public void close() throws Exception { postgres.close(); }
    }
}
