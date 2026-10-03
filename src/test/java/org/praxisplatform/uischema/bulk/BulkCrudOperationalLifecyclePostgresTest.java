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
    void ordinaryMetadataAndHashesUseTheInstalledPhotographWithoutDynamicFallback() throws Exception {
        try (var fixture = fixture()) {
            var calls = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> fixture.documents.getOrComputeSchemaHash("crud-proof", () -> {
                calls.incrementAndGet(); return fixture.sourceDocument;
            }));
            assertEquals(0, calls.get(), "cold governance denies before invoking the payload builder");
            fixture.lifecycle.publish(identity("crud.uniform"), 0);
            var published = fixture.documents.getDocumentForGroup("crud");
            String hash = fixture.documents.getOrComputeSchemaHash("crud-proof", () -> fixture.documents.getDocumentForGroup("crud"));
            fixture.sourceDocument.withObject("/info").put("version", "unpublished-source-change");
            assertEquals(published, fixture.documents.getDocumentForGroupStrict("crud"));
            assertEquals(hash, fixture.documents.getOrComputeSchemaHash("crud-proof", () -> {
                fail("a committed hash must not rebuild from the unpublished source"); return null;
            }));
            assertThrows(IllegalStateException.class, () -> fixture.documents.getDocumentForGroup("missing"));
            assertEquals(1, fixture.fresh.get());
            fixture.lifecycle.suspend(identity("crud.uniform"), 1);
            assertThrows(IllegalStateException.class, () -> fixture.documents.getDocumentForGroup("crud"));
            assertThrows(IllegalStateException.class, () -> fixture.documents.getOrComputeSchemaHash("crud-proof", () -> published));
        }
    }

    @Test
    void publishedSchemaAndHashesReadTheSamePhotographInsideTheBoundOperationalTransaction() throws Exception {
        try (var fixture = fixture()) {
            fixture.lifecycle.publish(identity("crud.uniform"), 0);
            var published = fixture.documents.getDocumentForGroup("crud");
            var source = updateSourceOperation();
            var expectedSchema = fixture.documents.requireRequestSchema(source).schema();
            int producerReads = fixture.fresh.get();
            fixture.sourceDocument.withObject("/info").put("version", "unpublished-transactional-change");
            var transaction = new org.springframework.transaction.support.TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.execute(status -> fixture.store.runtime.withConnection(connection -> {
                int boundPid = backendPid(connection);
                var publication = JdbcBulkOpenApiPublication.lockForRead(connection, NAMESPACE, "deployment-test");
                assertTrue(publication.published());
                fixture.documents.withPublishedBulkOpenApiPublication((candidate, generation) -> {
                    assertNoCacheLocks(fixture.documents);
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    assertEquals(publication.generation(), generation.longValue());
                    assertEquals(publication.documentDigest(), candidate.digest());
                    assertEquals(published, fixture.documents.getDocumentForGroupStrict("crud"));
                    var schema = fixture.documents.requireRequestSchema(source);
                    assertEquals(expectedSchema, schema.schema());
                    ((com.fasterxml.jackson.databind.node.ObjectNode) schema.schema()).put("tampered", true);
                    assertFalse(fixture.documents.requireRequestSchema(source).schema().has("tampered"),
                            "a unit receives a defensive schema copy from the immutable photograph");
                    var document = fixture.documents.getDocumentForGroup("crud");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) document).put("tampered", true);
                    assertEquals(published, fixture.documents.getDocumentForGroup("crud"));
                    assertEquals(boundPid, fixture.store.runtime.withConnection(BulkCrudOperationalLifecyclePostgresTest::backendPid).intValue());
                    return null;
                });
                String hash = fixture.documents.getOrComputeSchemaHash("bound-update-schema", () ->
                        fixture.documents.requireRequestSchema(source).schema());
                assertEquals(hash, fixture.documents.getOrComputeSchemaHash("bound-update-schema", () -> {
                    fail("a committed schema hash cannot rebuild from the dynamic producer"); return null;
                }));
                assertNoCacheLocks(fixture.documents);
                assertEquals(boundPid, backendPid(connection), "schema and hash reads preserve the operational connection");
                assertEquals(producerReads, fixture.fresh.get());
                return null;
            }));
            assertEquals(producerReads, fixture.fresh.get(), "transactional metadata reads perform no producer I/O");
            assertEquals(published, fixture.documents.getDocumentForGroupStrict("crud"));
        }
    }

    @Test
    void publishedReadsRejectReadOnlyRollbackOnlyAndUnrelatedTransactionsBeforePayloadWork() throws Exception {
        try (var fixture = fixture()) {
            fixture.lifecycle.publish(identity("crud.uniform"), 0);
            int producerReads = fixture.fresh.get();
            var payloadCalls = new AtomicInteger();
            java.util.function.Supplier<JsonNode> payload = () -> {
                payloadCalls.incrementAndGet(); return fixture.sourceDocument;
            };
            var readOnly = new org.springframework.transaction.support.TransactionTemplate(fixture.store.runtime.transactionManager());
            readOnly.setReadOnly(true);
            assertThrows(IllegalStateException.class, () -> readOnly.execute(status ->
                    fixture.documents.getOrComputeSchemaHash("read-only-denied", payload)));
            var rollbackOnly = new org.springframework.transaction.support.TransactionTemplate(fixture.store.runtime.transactionManager());
            assertThrows(IllegalStateException.class, () -> rollbackOnly.execute(status -> {
                // A failed MANDATORY participant marks the shared operational resource rollback-only.
                var rejectedUnit = new IllegalArgumentException("unit rejected before schema read");
                assertSame(rejectedUnit, assertThrows(IllegalArgumentException.class, () ->
                        fixture.store.runtime.withConnection(connection -> { throw rejectedUnit; })));
                return fixture.documents.getOrComputeSchemaHash("rollback-only-denied", payload);
            }));
            // The same physical database and credentials do not make another datasource/manager the operational binding.
            var unrelatedSource = new DriverManagerDataSource(fixture.store.postgres.getJdbcUrl("bulk_runtime_test", "postgres"),
                    "bulk_runtime_test", "");
            var unrelated = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(unrelatedSource));
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> unrelated.execute(status ->
                    fixture.documents.getOrComputeSchemaHash("unrelated-denied", payload)));
            assertEquals(0, payloadCalls.get(), "transaction attestation denies before schema payload construction");
            assertEquals(producerReads, fixture.fresh.get());
            assertNoCacheLocks(fixture.documents);
            assertEquals(1, fixture.lifecycle.requireReady(identity("crud.uniform")).generation(),
                    "denied callers cannot mutate the durable publication or leak their response scope");
        }
    }

    @Test
    void readinessAndDiscoveryRejectAnAmbientOperationalTransactionBeforeEvenEmptyConsumers() throws Exception {
        try (var fixture = fixture()) {
            var ready = fixture.lifecycle.publish(identity("crud.uniform"), 0);
            var publication = fixture.store.publication();
            int producerReads = fixture.fresh.get();
            var consumerCalls = new AtomicInteger();
            var frames = (ThreadLocal<?>) ReflectionTestUtils.getField(fixture.lifecycle, "responseProjection");
            var transaction = new org.springframework.transaction.support.TransactionTemplate(fixture.store.runtime.transactionManager());
            transaction.execute(status -> {
                fixture.store.runtime.withConnection(connection -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    assertNull(frames.get());
                    assertNoCacheLocks(fixture.documents);
                    var readinessDenied = assertThrows(IllegalStateException.class, () ->
                            fixture.lifecycle.requireReady(identity("crud.uniform")));
                    assertEquals("Readiness composition must start outside operational transactions", readinessDenied.getMessage());
                    // Empty actions must not bypass the entry guard through their consumer short circuit.
                    var actionsDenied = assertThrows(IllegalStateException.class, () ->
                            fixture.lifecycle.projectReadyActions(List.of(), contracts -> {
                                consumerCalls.incrementAndGet(); return contracts;
                            }));
                    assertEquals("Response projection must start outside operational transactions", actionsDenied.getMessage());
                    // Cover both the actual resource and a resource with no declared bindings.
                    for (String resource : List.of("crud.items", "crud-items")) {
                        var capabilitiesDenied = assertThrows(IllegalStateException.class, () ->
                                fixture.lifecycle.projectReadyCapabilities(resource, contracts -> {
                                    consumerCalls.incrementAndGet(); return contracts;
                                }));
                        assertEquals("Response projection must start outside operational transactions", capabilitiesDenied.getMessage());
                    }
                    assertEquals(0, consumerCalls.get());
                    assertEquals(producerReads, fixture.fresh.get());
                    assertNull(frames.get(), "entry denial cannot create or retain a response frame");
                    assertNoCacheLocks(fixture.documents);
                    return null;
                });
                assertFalse(status.isRollbackOnly(), "entrypoint denial does not enlist a failing runtime participant");
                status.setRollbackOnly();
                return null;
            });
            assertEquals(0, consumerCalls.get());
            assertEquals(producerReads, fixture.fresh.get());
            assertEquals(publication, fixture.store.publication(), "the rolled-back caller cannot mutate the publication");
            assertEquals(ready, fixture.lifecycle.requireReady(identity("crud.uniform")));
            assertEquals("READY", fixture.store.state("crud.uniform").state());
            assertNull(frames.get());
            assertNoCacheLocks(fixture.documents);
        }
    }

    @Test
    void boundSchemaReadCompletesWhileCacheWriterWaitsForTheReadersGlobalShareLock() throws Exception {
        try (var fixture = fixture(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            fixture.lifecycle.publish(identity("crud.uniform"), 0);
            var expected = fixture.documents.requireRequestSchema(updateSourceOperation()).schema();
            int producerReads = fixture.fresh.get();
            var writerEntered = new java.util.concurrent.CountDownLatch(1);
            var writerPid = new AtomicInteger();
            var monitor = new JdbcTemplate(fixture.store.postgres.getPostgresDatabase());
            var transaction = new org.springframework.transaction.support.TransactionTemplate(fixture.store.runtime.transactionManager());
            java.util.concurrent.Future<JdbcBulkOpenApiPublication.Transition> writer = transaction.execute(status ->
                    fixture.store.runtime.withConnection(connection -> {
                        int readerPid = backendPid(connection);
                        var publication = JdbcBulkOpenApiPublication.lockForRead(connection, NAMESPACE, "deployment-test");
                        assertTrue(publication.published());
                        var pending = executor.submit(() -> fixture.documents.withBulkLifecycleCompositionLock(() ->
                                fixture.store.control.withConnection(controlConnection -> {
                                    writerPid.set(backendPid(controlConnection));
                                    writerEntered.countDown();
                                    return JdbcBulkOpenApiPublication.transition(controlConnection, NAMESPACE, "deployment-test",
                                            publication.generation(), JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
                                })));
                        try { assertTrue(writerEntered.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                        }
                        assertNotEquals(readerPid, writerPid.get(), "the writer uses its independent control-plane connection");
                        awaitGlobalPublicationWait(monitor, writerPid.get(), readerPid);
                        var lock = (java.util.concurrent.locks.ReentrantReadWriteLock)
                                ReflectionTestUtils.getField(fixture.documents, "cacheLifecycleLock");
                        assertTrue(lock.isWriteLocked(), "the waiting publisher owns CACHE WRITE");
                        assertFalse(lock.isWriteLockedByCurrentThread());
                        assertEquals(0, lock.getReadHoldCount());
                        // This writer deliberately holds the canonical cache fence without changing its epoch yet.
                        // A bound reader must finish successfully, not wait for CACHE READ or open REQUIRES_NEW.
                        assertTimeout(Duration.ofMillis(500), () -> {
                            var actual = fixture.documents.requireRequestSchema(updateSourceOperation());
                            assertEquals(expected, actual.schema());
                            assertNoCacheLocks(fixture.documents);
                            assertEquals(readerPid, backendPid(connection));
                        });
                        assertFalse(pending.isDone(), "the writer remains blocked until this outer transaction releases its SHARE");
                        assertEquals(producerReads, fixture.fresh.get());
                        return pending;
                    }));
            assertNotNull(writer);
            var transition = writer.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(transition.applied(), "the writer completes after the unit transaction commits");
            assertEquals(3, transition.generation());
            assertEquals("SUSPENDED", fixture.store.publication().state());
            assertThrows(IllegalStateException.class, () -> fixture.documents.requireRequestSchema(updateSourceOperation()),
                    "the later read must reject the now-suspended durable photograph");
            assertEquals(producerReads, fixture.fresh.get());
        }
    }

    private static org.praxisplatform.uischema.openapi.CanonicalOperationRef updateSourceOperation() {
        return new org.praxisplatform.uischema.openapi.CanonicalOperationRef("crud", BulkCrudStructuralCompilerTest.SOURCE,
                "/crud-items/{id}", "PUT");
    }

    private static int backendPid(java.sql.Connection connection) throws java.sql.SQLException {
        try (var query = connection.createStatement(); var rows = query.executeQuery("select pg_backend_pid()")) {
            assertTrue(rows.next());
            int pid = rows.getInt(1);
            assertFalse(rows.next());
            return pid;
        }
    }

    private static void assertNoCacheLocks(CachedOpenApiDocumentService documents) {
        var lock = (java.util.concurrent.locks.ReentrantReadWriteLock) ReflectionTestUtils.getField(documents, "cacheLifecycleLock");
        var preparation = (java.util.concurrent.locks.ReentrantLock) ReflectionTestUtils.getField(documents, "compositionPreparationLock");
        assertFalse(lock.isWriteLockedByCurrentThread());
        assertEquals(0, lock.getReadHoldCount(), "bound reads cannot acquire CACHE READ after the durable SHARE");
        assertFalse(preparation.isHeldByCurrentThread());
    }

    private static void awaitGlobalPublicationWait(JdbcTemplate monitor, int writerPid, int readerPid) {
        // The control connection has a 1s lock_timeout; observe its real wait before that boundary.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(700);
        do {
            if (Boolean.TRUE.equals(monitor.queryForObject("""
                    select exists (select 1 from pg_catalog.pg_stat_activity a
                     where a.pid=? and a.wait_event_type='Lock'
                       and ? = any(pg_catalog.pg_blocking_pids(a.pid))
                       and a.query like '%transition_openapi_publication%')
                    """, Boolean.class, writerPid, readerPid))) return;
            try { java.util.concurrent.TimeUnit.MILLISECONDS.sleep(5); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
            }
        } while (System.nanoTime() < deadline);
        fail("the control-plane global UPDATE was not observed waiting for this operational SHARE");
    }

    @Test
    void aStaleReplicaReconcilesTheOtherNodesPublicationWithoutSuspendingIt() throws Exception {
        try (var a = fixture(); var b = fixture(a.store, false)) {
            a.lifecycle.publish(identity("crud.uniform"), 0);
            assertEquals(1, b.lifecycle.reconcilePublished(identity("crud.uniform"), 1).generation());
            b.lifecycle.suspend(identity("crud.uniform"), 1);
            assertEquals(3, b.lifecycle.publish(identity("crud.uniform"), 2).generation());
            var current = a.store.publication();
            assertThrows(IllegalStateException.class, () -> a.lifecycle.requireReady(identity("crud.uniform")));
            assertEquals(3, a.lifecycle.reconcilePublished(identity("crud.uniform"), 3).generation());
            assertEquals(current, a.store.publication());
            assertEquals(3, b.lifecycle.requireReady(identity("crud.uniform")).generation());
            assertEquals(3, a.lifecycle.requireReady(identity("crud.uniform")).generation());
            assertEquals(2, a.fresh.get());
            assertEquals(2, b.fresh.get());
        }
    }

    @Test
    void uncertainCommittedPublicationIsReconciledWithoutRepeatingTheTransition() throws Exception {
        try (var fixture = fixture()) {
            fixture.store.commitFault.set("after");
            assertEquals(1, fixture.lifecycle.publish(identity("crud.uniform"), 0).generation());
            assertEquals(1, fixture.store.publicationCommits.get());
            assertEquals(2, fixture.store.publication().generation());
            assertEquals(1, fixture.lifecycle.requireReady(identity("crud.uniform")).generation());
            assertEquals(1, fixture.fresh.get());
        }
    }

    @Test
    void failedPublicationCommitRollsBackBothGlobalAndOperationAndInstallsNothing() throws Exception {
        try (var fixture = fixture()) {
            fixture.store.commitFault.set("before");
            assertThrows(RuntimeException.class, () -> fixture.lifecycle.publish(identity("crud.uniform"), 0));
            assertEquals("SUSPENDED", fixture.store.publication().state());
            assertEquals(1, fixture.store.publication().generation());
            assertEquals("UNCOMPOSED", fixture.store.state("crud.uniform").state());
            assertEquals(0, fixture.store.state("crud.uniform").generation());
            assertFalse(fixture.documents.hasLocalPublishedBulkOpenApiPublication());
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(identity("crud.uniform")));
        }
    }

    @Test
    void coldAndStaleLocalPhotographsReconcileOnlyTheExactDurablePublication() throws Exception {
        try (var fixture = fixture()) {
            fixture.lifecycle.publish(identity("crud.uniform"), 0);
            Object oldSnapshot = ReflectionTestUtils.getField(fixture.documents, "publishedSnapshot");
            ReflectionTestUtils.setField(fixture.documents, "publishedSnapshot", null);
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(identity("crud.uniform")));
            var committed = fixture.store.publication();
            assertEquals(1, fixture.lifecycle.reconcilePublished(identity("crud.uniform"), 1).generation());
            assertEquals(committed, fixture.store.publication(), "recovery performs no control-plane mutation");
            fixture.lifecycle.suspend(identity("crud.uniform"), 1);
            fixture.lifecycle.publish(identity("crud.uniform"), 2);
            ReflectionTestUtils.setField(fixture.documents, "publishedSnapshot", oldSnapshot);
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(identity("crud.uniform")));
            committed = fixture.store.publication();
            assertEquals(3, fixture.lifecycle.reconcilePublished(identity("crud.uniform"), 3).generation());
            assertEquals(committed, fixture.store.publication());
            fixture.sourceDocument.withObject("/info").put("version", "divergent");
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.reconcilePublished(identity("crud.uniform"), 3));
            assertEquals(committed, fixture.store.publication(), "a divergent source cannot change the valid durable tuple");
        }
    }

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
            assertEquals(before, fixture.fresh.get());
            assertEquals(BulkMode.PER_ITEM_UPDATE, projected.get("bulk-update-items").mode());
            fixture.lifecycle.projectReadyCapabilities("crud.items", contracts -> contracts);
            assertEquals(before, fixture.fresh.get(), "responses reuse the committed immutable photograph without producer I/O");
            assertEquals(2, fixture.lifecycle.suspend(identity("crud.uniform"), 1));
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(identity("crud.items")));
            assertTrue(fixture.lifecycle.<Boolean>projectReadyCapabilities("crud.items", Map::isEmpty).booleanValue());
            assertEquals(3, fixture.lifecycle.publish(identity("crud.items"), 2).generation());
        }
    }

    @Test
    void fourCrudVariantsPublishIndependentlyInsideOneGovernedPhotograph() throws Exception {
        try (var fixture = atomicFixture()) {
            var ids = List.of("crud.uniform", "crud.items", "crud.uniform-atomic", "crud.items-atomic");
            for (String id : ids) assertEquals(1, fixture.lifecycle.publish(identity(id), 0).generation());
            assertEquals(1, fixture.fresh.get(), "one global OpenAPI photograph covers all four operations");
            var projected = fixture.lifecycle.projectReadyCapabilities("crud.items", contracts -> {
                assertUnlocked(fixture.documents);
                assertEquals(Set.of("bulk-update", "bulk-update-items", "bulk-update-atomic", "bulk-update-items-atomic"),
                        contracts.keySet());
                for (String id : ids) assertEquals(1, fixture.lifecycle.requireReady(identity(id)).generation());
                return contracts;
            });
            assertEquals(org.praxisplatform.uischema.action.ActionCollectionAtomicity.ATOMIC,
                    projected.get("bulk-update-atomic").atomicity());
            assertEquals(org.praxisplatform.uischema.action.ActionCollectionAtomicity.PER_ITEM,
                    projected.get("bulk-update").atomicity());
            assertEquals(1, fixture.fresh.get(), "discovery must use the installed photograph");
            var atomicProvider = fixture.providers.get(2);
            atomicProvider.revision = "changed";
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(identity("crud.uniform-atomic")));
            assertEquals(1, fixture.lifecycle.requireReady(identity("crud.uniform")).generation(),
                    "provider drift is bound to the exact confirmation identity");
            atomicProvider.revision = "r1";
            assertEquals(2, fixture.lifecycle.suspend(identity("crud.uniform-atomic"), 1));
            for (String id : ids) assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(identity(id)),
                    "global suspension invalidates every variant, including still READY operation rows");
            assertTrue(fixture.lifecycle.<Boolean>projectReadyCapabilities("crud.items", Map::isEmpty).booleanValue());
        }
    }

    @Test
    void duplicateModeAndAtomicityInOneResourceCannotPublishAnyVariant() throws Exception {
        try (var fixture = fixture(new Store(List.of("crud.uniform", "crud.items", "crud.uniform-atomic", "crud.items-atomic")),
                true, true, true)) {
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.publish(identity("crud.uniform"), 0));
            assertEquals("UNCOMPOSED", fixture.store.state("crud.uniform").state());
            assertEquals("UNCOMPOSED", fixture.store.state("crud.uniform-atomic").state());
            assertTrue(fixture.lifecycle.<Boolean>projectReadyCapabilities("crud.items", Map::isEmpty).booleanValue());
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
        return fixture(new Store(List.of("crud.uniform", "crud.items")), true);
    }

    private Fixture atomicFixture() throws Exception {
        return fixture(new Store(List.of("crud.uniform", "crud.items", "crud.uniform-atomic", "crud.items-atomic")), true, true);
    }

    private Fixture fixture(Store store, boolean ownsStore) throws Exception {
        return fixture(store, ownsStore, false);
    }

    private Fixture fixture(Store store, boolean ownsStore, boolean atomic) throws Exception {
        return fixture(store, ownsStore, atomic, false);
    }

    private Fixture fixture(Store store, boolean ownsStore, boolean atomic, boolean duplicate) throws Exception {
        var controller = duplicate ? BulkCrudStructuralCompilerTest.DuplicateAtomicCrudController.class
                : atomic ? BulkCrudStructuralCompilerTest.AtomicCrudController.class
                : BulkCrudStructuralCompilerTest.CrudController.class;
        var context = BulkCrudStructuralCompilerTest.context(controller);
        var fresh = new AtomicInteger();
        var value = duplicate ? BulkCrudOperationalCompositionTest.duplicateAtomicDocument()
                : atomic ? BulkCrudOperationalCompositionTest.atomicDocument() : BulkCrudOperationalCompositionTest.document();
        value.put("openapi", "3.0.3");
        value.putObject("info").put("title", "CRUD publication fixture").put("version", "1");
        var filterHolder = new org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter[1];
        var registration = mock(jakarta.servlet.FilterRegistration.class);
        org.mockito.Mockito.when(registration.getClassName()).thenReturn(org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter.class.getName());
        org.mockito.Mockito.when(registration.getUrlPatternMappings()).thenReturn(List.of("/*"));
        org.mockito.Mockito.when(registration.getServletNameMappings()).thenReturn(List.of());
        var servletContext = new org.springframework.mock.web.MockServletContext() {
            @Override public jakarta.servlet.FilterRegistration getFilterRegistration(String name) { return registration; }
        };
        var source = new OpenApiDocsSupport() {
            @Override public String localPublicationContextPath() { return ""; }
            @Override public org.praxisplatform.uischema.openapi.OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(
                    RestTemplate client, String path, org.slf4j.Logger logger, ObjectMapper mapper) {
                // Servlet admission fixture; actual HTTP/security is proved separately by the Boot test.
                var request = new org.springframework.mock.web.MockHttpServletRequest(servletContext, "GET", path);
                request.setServletPath(path);
                request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER,
                        ((OpenApiInternalRestTemplate) client).producerCaptureToken(path));
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                try {
                    filterHolder[0].doFilter(request, response, (input, output) -> {
                        if (path.endsWith("/crud")) fresh.incrementAndGet();
                        byte[] bytes = path.endsWith("/swagger-config")
                                ? "{\"urls\":[{\"name\":\"crud\",\"url\":\"/v3/api-docs/crud\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                : mapper.writeValueAsBytes(value);
                        output.getOutputStream().write(bytes);
                    });
                    assertEquals(200, response.getStatus());
                    return org.praxisplatform.uischema.openapi.OpenApiDocumentCapture.parse(response.getContentAsByteArray(), mapper);
                } catch (Exception failure) { throw new IllegalStateException("Servlet producer fixture failed", failure); }
            }
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
        filterHolder[0] = new org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter(documents);
        filterHolder[0].init(new org.springframework.mock.web.MockFilterConfig(servletContext, "bulkPublication"));
        var mvc = context.getBean(RequestMappingHandlerMapping.class);
        var bindings = BulkResourceOperationBindings.from(mvc);
        var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("crud"));
        var providers = new java.util.ArrayList<BulkCrudOperationalCompositionTest.Provider>();
        providers.add(new BulkCrudOperationalCompositionTest.Provider("crud.uniform", "r1",
                BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, store.runtime));
        providers.add(new BulkCrudOperationalCompositionTest.Provider("crud.items", "r1",
                BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, store.runtime));
        if (atomic) {
            providers.add(new BulkCrudOperationalCompositionTest.Provider("crud.uniform-atomic", "r1",
                    BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, store.runtime));
            providers.add(new BulkCrudOperationalCompositionTest.Provider("crud.items-atomic", "r1",
                    BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, store.runtime));
        }
        var lifecycle = new BulkOperationLifecycle(bindings, resolver, documents, mock(ActionDefinitionRegistry.class),
                BulkCrudStructuralCompilerTest.mapper(), new FilteredSchemaReferenceResolver(), store.runtime, store.control,
                List.copyOf(providers));
        return new Fixture(store, context, client, documents, bindings, resolver, providers, lifecycle, fresh, value, ownsStore);
    }

    record Fixture(Store store, org.springframework.web.context.support.AnnotationConfigWebApplicationContext context,
            OpenApiInternalRestTemplate client, CachedOpenApiDocumentService documents, BulkResourceOperationBindings bindings,
            OpenApiCanonicalOperationResolver resolver, List<BulkCrudOperationalCompositionTest.Provider> providers,
            BulkOperationLifecycle lifecycle, AtomicInteger fresh, com.fasterxml.jackson.databind.node.ObjectNode sourceDocument, boolean ownsStore) implements AutoCloseable {
        public void close() throws Exception { try { context.close(); } finally { try { client.close(); } finally { if (ownsStore) store.close(); } } }
    }

    static final class Store implements AutoCloseable {
        final EmbeddedPostgres postgres;
        final BulkExecutionInfrastructure runtime;
        final BulkControlPlaneInfrastructure control;
        final java.util.concurrent.atomic.AtomicReference<String> commitFault = new java.util.concurrent.atomic.AtomicReference<>();
        final AtomicInteger publicationCommits = new AtomicInteger();
        final AtomicInteger skipFaultCommits = new AtomicInteger(1);
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
            var controlManager = new DataSourceTransactionManager(controlDs) {
                @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                    // A publish has a separate suspension commit followed by the atomic publication commit.
                    // Fault only the latter; do not grant the control role table SELECT for test instrumentation.
                    String fault = commitFault.get() != null && skipFaultCommits.getAndDecrement() == 0
                            ? commitFault.getAndSet(null) : null;
                    if ("before".equals(fault)) throw new org.springframework.transaction.TransactionSystemException("injected pre-commit failure");
                    super.doCommit(status);
                    if (fault != null) publicationCommits.incrementAndGet();
                    if ("after".equals(fault)) throw new org.springframework.transaction.TransactionSystemException("injected lost commit acknowledgement");
                }
            };
            controlManager.setRollbackOnCommitFailure(true);
            control = new BulkControlPlaneInfrastructure(controlDs, controlManager, NAMESPACE, "deployment-test", "bulk_control_test", runtime);
        }
        JdbcBulkOpenApiPublication.Snapshot publication() {
            return runtime.withLifecycleRead(connection -> JdbcBulkOpenApiPublication.lockForRead(connection, NAMESPACE, "deployment-test"));
        }
        JdbcBulkOperationControl.Snapshot state(String operation) {
            return runtime.withLifecycleRead(connection -> JdbcBulkOperationControl.lockForAdmission(connection, NAMESPACE, operation));
        }
        public void close() throws Exception { postgres.close(); }
    }
}
