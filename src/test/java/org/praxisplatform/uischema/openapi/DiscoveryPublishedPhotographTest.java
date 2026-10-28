package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.*;
import org.praxisplatform.uischema.capability.*;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.*;
import org.slf4j.Logger;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;

/** Concrete installed wire photographs and guard seams; this does not certify durable PG/IAM semantics. */
class DiscoveryPublishedPhotographTest {
    private static final String RESOURCE = "parent.items";
    private static final String PATH = "/parents";
    private static final String BASE = "/v3/api-docs";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OAS = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Discovery\",\"version\":\"1\"},"
            + "\"paths\":{\"/parents\":{\"get\":{\"operationId\":\"parent.all\"}},"
            + "\"/parents/{id}\":{\"get\":{\"operationId\":\"parent.view\"}},"
            + "\"/children\":{\"get\":{\"operationId\":\"child.all\"}},"
            + "\"/children/filter\":{\"post\":{\"operationId\":\"child.filter\"}}}}";
    private static final String CONFIG = "{\"urls\":[{\"name\":\"parent\",\"url\":\"/v3/api-docs/parent\"},"
            + "{\"name\":\"child\",\"url\":\"/v3/api-docs/child\"}]}";

    @Test
    void realParentAndRelatedDocumentsUseBoundedGuardsIndependentOfChildOperationCount() {
        try (var f = new Fixture(true)) {
            var callbacks = new AtomicInteger();
            var service = f.capabilities(() -> { f.assertUnlocked(); callbacks.incrementAndGet(); });
            var snapshot = service.itemCapabilities(RESOURCE, PATH, 42);
            assertThat(snapshot.canonicalOperations().get("all")).isTrue();
            assertThat(snapshot.operations().get("view").supported()).isTrue();
            assertThat(snapshot.surfaces().getFirst().relatedResource().childOperations())
                    .containsExactly(RelatedResourceChildOperation.FILTER, RelatedResourceChildOperation.LIST);
            // Parent structural/final (4), child structural/final (4). Six requested child operations
            // do not induce six separate document pairs. Neither phase invokes the producer again.
            assertThat(f.guards).hasValue(8);
            assertThat(f.source.captures).hasValue(4);
            assertThat(callbacks.get()).isPositive();
            assertThat(f.candidate.response(BASE + "/parent").bytes()).isEqualTo(OAS.getBytes(StandardCharsets.UTF_8));
            assertThat(f.documents.getDocumentForGroup("parent").path("paths").has("/parents")).isTrue();
        }
    }

    @Test
    void callbacksCanStartAnIndependentPublishedReadAndProducerDriftIsNotSilentlyAdopted() {
        try (var f = new Fixture(true)) {
            var calls = new AtomicInteger();
            var service = f.capabilities(() -> {
                f.assertUnlocked();
                if (calls.getAndIncrement() == 0) {
                    f.source.changed = true;
                    Boolean stillPublished = f.documents.withPublishedBulkOpenApiPublication((candidate, generation) ->
                            candidate.groupDocument("parent").path("paths").has("/parents"));
                    assertThat(stillPublished).isTrue();
                }
            });
            var snapshot = service.itemCapabilities(RESOURCE, PATH, 42);
            assertThat(snapshot.canonicalOperations().get("all")).isTrue();
            assertThat(snapshot.operations().get("view").supported()).isTrue();
            assertThat(f.source.captures).hasValue(4);
            assertThat(f.guards).hasValue(10); // The explicit callback read owns its own fresh guard pair.
            f.assertUnlocked();
        }
    }

    @Test
    void suspensionCaughtByHostAvailabilityStillRejectsTheResponseAndNextRequestCanRecover() {
        try (var f = new Fixture(true)) {
            var once = new AtomicInteger();
            var service = f.capabilities(() -> {
                f.assertUnlocked();
                if (once.getAndIncrement() == 0) {
                    f.generation.set(2); // durable seam changed, local photograph deliberately remains old
                    assertThatThrownBy(() -> f.documents.withPublishedBulkOpenApiPublication((c, g) -> true))
                            .isInstanceOf(GovernedOpenApiPublicationUnavailableException.class);
                    // Availability swallows the denial and returns allow; the outer final fence must deny.
                }
            });
            assertThatThrownBy(() -> service.itemCapabilities(RESOURCE, PATH, 42))
                    .isInstanceOf(GovernedOpenApiPublicationUnavailableException.class);
            f.assertUnlocked();
            f.documents.installBulkOpenApiPublication(f.candidate, 2);
            assertThat(service.itemCapabilities(RESOURCE, PATH, 42).canonicalOperations().get("all")).isTrue();
            f.assertUnlocked();
        }
    }

    @Test
    void sameBytesRepublishedBetweenParentAndChildPhasesInvalidateTheParentFence() {
        try (var f = new Fixture(true)) {
            var once = new AtomicInteger();
            SurfaceCatalogService surfaces = f.surfaces(() -> {
                f.assertUnlocked();
                if (once.getAndIncrement() == 0) {
                    f.generation.set(2);
                    f.documents.installBulkOpenApiPublication(f.candidate, 2);
                }
            }, () -> {});
            var service = f.capabilities(surfaces, () -> {});
            assertThatThrownBy(() -> service.itemCapabilities(RESOURCE, PATH, 42))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Published photograph changed");
            f.assertUnlocked();
            f.assertFencesClosed();
            assertThat(service.itemCapabilities(RESOURCE, PATH, 42).operations().get("view").supported()).isTrue();
        }
    }

    @Test
    void standaloneSurfaceEntrypointsKeepContextsAndAvailabilityUnlockedAndFinallyFenced() {
        try (var f = new Fixture(true)) {
            var calls = new AtomicInteger();
            var surfaces = f.surfaces(() -> { f.assertUnlocked(); calls.incrementAndGet(); });
            for (int variant = 0; variant < 3; variant++) {
                f.guards.set(0);
                SurfaceCatalogResponse response = switch (variant) {
                    case 0 -> surfaces.findByResourceKey(RESOURCE);
                    case 1 -> surfaces.findByGroup("parent");
                    default -> surfaces.findItemSurfaces(RESOURCE, 42);
                };
                assertThat(response.surfaces().getFirst().relatedResource().childOperations()).hasSize(2);
                assertThat(f.guards).hasValue(4);
            }
            assertThat(calls).hasValue(6); // each context + evaluator exactly once per entrypoint
            var deny = f.surfaces(() -> { f.assertUnlocked(); f.generation.set(2); });
            assertThatThrownBy(() -> deny.findItemSurfaces(RESOURCE, 42))
                    .isInstanceOf(GovernedOpenApiPublicationUnavailableException.class);
            f.assertUnlocked();
            f.assertFencesClosed();
        }
    }

    @Test
    void surfacesWithoutRelatedDocumentsDoNotAddPublicationReads() {
        try (var f = new Fixture(true)) {
            var related = definition();
            var plain = new SurfaceDefinition(related.id(), related.resourceKey(), related.resourcePath(), related.group(),
                    related.kind(), related.scope(), related.title(), related.description(), related.intent(), related.schemaType(),
                    related.responseCardinality(), related.operation(), related.schema(), related.order(),
                    related.requiredAuthorities(), related.allowedStates(), related.tags());
            SurfaceDefinitionRegistry registry = new SurfaceDefinitionRegistry() {
                @Override public List<SurfaceDefinition> findByResourceKey(String key) { return List.of(plain); }
                @Override public List<SurfaceDefinition> findByGroup(String group) { return List.of(plain); }
            };
            var surfaces = new SurfaceCatalogService(registry, (definition, context) -> {
                f.assertUnlocked(); return AvailabilityDecision.allowAll();
            }, (key, path, id) -> {
                f.assertUnlocked(); return new SurfaceAvailabilityContext(key, path, id, null, Locale.ROOT, null, Set.of(), null);
            }, f.resolver, f.documents);
            assertThat(surfaces.findByResourceKey(RESOURCE).surfaces()).hasSize(1);
            assertThat(f.guards).hasValue(0);
            assertThat(f.capabilities(surfaces, f::assertUnlocked).itemCapabilities(RESOURCE, PATH, 42)
                    .canonicalOperations().get("all")).isTrue();
            assertThat(f.guards).hasValue(4); // same parent guard count as its former two isolated reads
            f.assertFencesClosed();
        }
    }

    @Test
    void installedGuardWithoutLocalPhotographFailsBeforeAnyHostCallback() {
        try (var f = new Fixture(false)) {
            var calls = new AtomicInteger();
            var service = f.capabilities(calls::incrementAndGet);
            assertThatThrownBy(() -> service.itemCapabilities(RESOURCE, PATH, 42))
                    .isInstanceOf(GovernedOpenApiPublicationUnavailableException.class);
            assertThatThrownBy(() -> f.surfaces(calls::incrementAndGet).findByResourceKey(RESOURCE))
                    .isInstanceOf(GovernedOpenApiPublicationUnavailableException.class);
            assertThat(calls).hasValue(0);
            assertThat(f.source.captures).hasValue(0);
            f.assertUnlocked();
        }
    }

    @Test
    void ambientTransactionIsRejectedBeforeStructuralResolversOrCallbacks() {
        try (var f = new Fixture(true)) {
            var calls = new AtomicInteger();
            var service = f.capabilities(calls::incrementAndGet);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> service.itemCapabilities(RESOURCE, PATH, 42))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside operational transactions");
                assertThatThrownBy(() -> f.surfaces(calls::incrementAndGet).findByResourceKey(RESOURCE))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside operational transactions");
                assertThat(calls).hasValue(0);
                assertThat(f.guards).hasValue(0);
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertThat(service.itemCapabilities(RESOURCE, PATH, 42).canonicalOperations().get("all")).isTrue();
        }
    }

    @Test
    void consumerExceptionIsNotRetriedAndDoesNotPoisonTheFollowingRequest() {
        try (var f = new Fixture(true)) {
            var calls = new AtomicInteger();
            var failure = new IllegalArgumentException("state unavailable");
            var service = new DefaultCapabilityService(f.resolver, f.surfaces(() -> {}), f.actions(), f.documents,
                    context -> AvailabilityDecision.allowAll(), (key, id) -> {
                        f.assertUnlocked();
                        if (calls.getAndIncrement() == 0) throw failure;
                        return java.util.Optional.empty();
                    });
            assertThatThrownBy(() -> service.itemCapabilities(RESOURCE, PATH, 42)).isSameAs(failure);
            assertThat(calls).hasValue(1);
            f.assertUnlocked();
            f.assertFencesClosed();
            assertThat(service.itemCapabilities(RESOURCE, PATH, 42).canonicalOperations().get("all")).isTrue();
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void failedPostPreparationGuardClosesTheAlreadyCapturedFenceBeforeAnyCallback() {
        try (var f = new Fixture(true)) {
            f.failOnGuard = 2;
            var calls = new AtomicInteger();
            var service = f.capabilities(calls::incrementAndGet);
            assertThatThrownBy(() -> service.itemCapabilities(RESOURCE, PATH, 42))
                    .isInstanceOf(GovernedOpenApiPublicationUnavailableException.class);
            assertThat(calls).hasValue(0);
            assertThat(f.fences).hasSize(1);
            f.assertUnlocked();
            f.assertFencesClosed();
            f.failOnGuard = 0;
            assertThat(service.itemCapabilities(RESOURCE, PATH, 42).canonicalOperations().get("all")).isTrue();
            f.assertFencesClosed();
        }
    }

    @Test
    void infrastructureFailuresInsideTheDurableGuardAreUnavailableWithTheirPrivateCause() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{
                new org.springframework.transaction.TransactionTimedOutException("PRIVATE_TIMEOUT"),
                new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_DATABASE")}) {
            for(int guardIndex:new int[]{1,2}) try (var f = new Fixture(true)) {
                f.failOnGuard=guardIndex;f.guardFailure=failure;
                var calls=new AtomicInteger();
                var error=org.junit.jupiter.api.Assertions.assertThrows(GovernedOpenApiPublicationUnavailableException.class,
                        ()->f.documents.withPublishedBulkOpenApiPublication((candidate,generation)->calls.incrementAndGet()));
                assertThat(error.getCause()).isSameAs(failure);assertThat(calls).hasValue(guardIndex-1);
                var request=new org.springframework.web.context.request.ServletWebRequest(new org.springframework.mock.web.MockHttpServletRequest());
                var response=new org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler()
                        .handleGovernedOpenApiPublicationUnavailable(error,request);
                assertThat(response.getStatusCode().value()).isEqualTo(503);
                assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response.getBody()))
                        .contains("GOVERNED_OPENAPI_PUBLICATION_UNAVAILABLE").doesNotContain("PRIVATE_");
            }
        }
    }

    @Test
    void callbackFailuresOutsideTheGuardKeepTheirOriginalClassification() {
        try (var f=new Fixture(true)) {
            var bug=new IllegalArgumentException("callback bug");
            assertThatThrownBy(()->f.documents.withPublishedBulkOpenApiPublication((candidate,generation)->{throw bug;})).isSameAs(bug);
        }
    }

    @Test
    void genuineLegacyDocumentServiceKeepsItsExistingGettersAndCustomResolverSemantics() throws Exception {
        // This is the legacy caller's transaction boundary, not governed unlocked materialization.
        for (boolean ambient : new boolean[]{false, true}) {
            var reads = new AtomicInteger();
            var callbacks = new AtomicInteger();
            JsonNode document = JSON.readTree(OAS);
            OpenApiDocumentService legacy = new OpenApiDocumentService() {
                @Override public String resolveGroupFromPath(String path) { return "legacy"; }
                @Override public JsonNode getDocumentForGroup(String group) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isEqualTo(ambient);
                    reads.incrementAndGet(); return document.deepCopy();
                }
                @Override public String getOrComputeSchemaHash(String id, java.util.function.Supplier<JsonNode> payload) { return "unused"; }
                @Override public void clearCaches() {}
            };
            var resolver = new OpenApiCanonicalCapabilityResolver(legacy);
            var definition = definition();
            SurfaceDefinitionRegistry registry = new SurfaceDefinitionRegistry() {
                @Override public List<SurfaceDefinition> findByResourceKey(String key) { return List.of(definition); }
                @Override public List<SurfaceDefinition> findByGroup(String group) { return List.of(definition); }
            };
            var surfaces = new SurfaceCatalogService(registry, (d, c) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isEqualTo(ambient);
                callbacks.incrementAndGet(); return AvailabilityDecision.allowAll();
            }, (key, path, id) -> new SurfaceAvailabilityContext(key, path, id, null, Locale.ROOT, null, Set.of(), null),
                    resolver, legacy);
            ActionCatalogService actions = new ActionCatalogService(new ActionDefinitionRegistry() {
                @Override public List<ActionDefinition> findByResourceKey(String key) { return List.of(); }
                @Override public List<ActionDefinition> findByGroup(String group) { return List.of(); }
            }, (d, c) -> AvailabilityDecision.allowAll(), (key, path, id) -> null);
            var service = new DefaultCapabilityService(resolver, surfaces, actions, legacy, context -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isEqualTo(ambient);
                callbacks.incrementAndGet(); return AvailabilityDecision.allowAll();
            }, (key, id) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isEqualTo(ambient);
                return java.util.Optional.empty();
            });
            CapabilitySnapshot snapshot;
            TransactionSynchronizationManager.setActualTransactionActive(ambient);
            try { snapshot = service.itemCapabilities(RESOURCE, PATH, 42); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertThat(snapshot.canonicalOperations().get("all")).isTrue();
            assertThat(snapshot.surfaces().getFirst().relatedResource().childOperations()).hasSize(2);
            assertThat(callbacks.get()).isPositive();
            assertThat(reads).hasValue(4); // two parent reads plus one capability/CRUD pair per distinct child path
        }
    }


    @Test
    void oldSurfaceConstructorsKeepTheLegacyAmbientTransactionBoundary() throws Exception {
        JsonNode document = JSON.readTree(OAS);
        var reads = new AtomicInteger();
        OpenApiDocumentService legacy = new OpenApiDocumentService() {
            @Override public String resolveGroupFromPath(String path) { return "legacy"; }
            @Override public JsonNode getDocumentForGroup(String group) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                reads.incrementAndGet(); return document.deepCopy();
            }
            @Override public String getOrComputeSchemaHash(String id, java.util.function.Supplier<JsonNode> payload) { return "unused"; }
            @Override public void clearCaches() {}
        };
        SurfaceDefinitionRegistry registry = new SurfaceDefinitionRegistry() {
            @Override public List<SurfaceDefinition> findByResourceKey(String key) { return List.of(definition()); }
            @Override public List<SurfaceDefinition> findByGroup(String group) { return List.of(definition()); }
        };
        var callbacks = new AtomicInteger();
        SurfaceAvailabilityEvaluator evaluator = (definition, context) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            callbacks.incrementAndGet(); return AvailabilityDecision.allowAll();
        };
        SurfaceAvailabilityContextResolver contexts = (key, path, id) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            callbacks.incrementAndGet(); return new SurfaceAvailabilityContext(key, path, id, null, Locale.ROOT, null, Set.of(), null);
        };
        for (boolean resolveChildren : new boolean[]{false, true}) {
            SurfaceCatalogService surfaces = resolveChildren
                    ? new SurfaceCatalogService(registry, evaluator, contexts, new OpenApiCanonicalCapabilityResolver(legacy))
                    : new SurfaceCatalogService(registry, evaluator, contexts);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                var response = surfaces.findItemSurfaces(RESOURCE, 42);
                assertThat(response.surfaces().getFirst().relatedResource().childOperations()).hasSize(resolveChildren ? 2 : 6);
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
        assertThat(callbacks).hasValue(4);
        assertThat(reads).hasValue(2); // one capability/CRUD pair for the child path; ambient boundary is preserved
    }

    @Test
    void publicationPresenceThatAppearsInsideTheScopeStillRejectsAmbientTransaction() {
        try (var f = new Fixture(true)) {
            var callbacks = new AtomicInteger();
            // False before admission, true inside the actual concrete photograph: the hint
            // cannot authorize an ambient transaction if installation won the entry race.
            OpenApiDocumentService hintChanges = new OpenApiDocumentService() {
                int presenceReads;
                @Override public boolean hasLocalPublishedBulkOpenApiPublication() {
                    return ++presenceReads > 1 && f.documents.hasLocalPublishedBulkOpenApiPublication();
                }
                @Override public <T> T withSchemaCacheReadLock(java.util.function.Supplier<T> reader) {
                    return f.documents.withSchemaCacheReadLock(reader);
                }
                @Override public String resolveGroupFromPath(String path) { return f.documents.resolveGroupFromPath(path); }
                @Override public JsonNode getDocumentForGroup(String group) { throw new AssertionError("transaction rejection precedes structural getters"); }
                @Override public String getOrComputeSchemaHash(String id, java.util.function.Supplier<JsonNode> payload) { throw new AssertionError("no hash work"); }
                @Override public void clearCaches() { throw new AssertionError("no invalidation"); }
            };
            var service = new DefaultCapabilityService(f.resolver, f.surfaces(() -> {}), f.actions(), hintChanges,
                    context -> { callbacks.incrementAndGet(); return AvailabilityDecision.allowAll(); },
                    (key, id) -> { callbacks.incrementAndGet(); return java.util.Optional.empty(); });
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> service.itemCapabilities(RESOURCE, PATH, 42))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside operational transactions");
                assertThat(callbacks).hasValue(0);
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            f.assertUnlocked();
            assertThat(f.fences).isEmpty();
        }
    }

    private static class Fixture implements AutoCloseable {
        final OpenApiInternalRestTemplate client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(3));
        final Source source = new Source();
        final List<OpenApiDocumentService.BulkLifecycleDocumentFence> fences = new java.util.ArrayList<>();
        final CachedOpenApiDocumentService documents = new CachedOpenApiDocumentService(client, JSON, source, true) {
            @Override public BulkLifecycleDocumentFence captureBulkLifecycleDocumentFence() {
                var fence = super.captureBulkLifecycleDocumentFence();
                fences.add(fence);
                return fence;
            }
        };
        final OpenApiCanonicalCapabilityResolver resolver = new OpenApiCanonicalCapabilityResolver(documents);
        final AtomicInteger guards = new AtomicInteger();
        final AtomicLong generation = new AtomicLong(1);
        int failOnGuard;
        RuntimeException guardFailure=new IllegalStateException("guard unavailable");
        OpenApiPublicationCandidate candidate;

        Fixture(boolean install) {
            ReflectionTestUtils.setField(documents, "openApiBasePath", BASE);
            source.owner = documents;
            documents.markBulkOpenApiServingInstalled("");
            documents.installBulkLifecyclePublicationGuard((observed, digest) -> {
                if (guards.incrementAndGet() == failOnGuard) throw guardFailure;
                if (observed != generation.get() || candidate == null || !candidate.digest().equals(digest))
                    throw new IllegalStateException("durable tuple changed");
            });
            if (install) {
                candidate = documents.prepareBulkOpenApiPublication(Set.of("parent", "child"));
                documents.installBulkOpenApiPublication(candidate, 1);
                guards.set(0);
            }
        }

        DefaultCapabilityService capabilities(Runnable callback) { return capabilities(surfaces(() -> {}), callback); }
        DefaultCapabilityService capabilities(SurfaceCatalogService surfaces, Runnable callback) {
            return new DefaultCapabilityService(resolver, surfaces, actions(), documents,
                    context -> { callback.run(); return AvailabilityDecision.allowAll(); },
                    (key, id) -> { assertUnlocked(); return java.util.Optional.empty(); });
        }
        ActionCatalogService actions() {
            ActionDefinitionRegistry registry = new ActionDefinitionRegistry() {
                @Override public List<ActionDefinition> findByResourceKey(String key) { return List.of(); }
                @Override public List<ActionDefinition> findByGroup(String group) { return List.of(); }
            };
            return new ActionCatalogService(registry, (definition, context) -> AvailabilityDecision.allowAll(),
                    (key, path, id) -> { throw new AssertionError("empty actions must not load context"); });
        }
        SurfaceCatalogService surfaces(Runnable callback) { return surfaces(() -> {}, callback); }
        SurfaceCatalogService surfaces(Runnable registryLookup, Runnable callback) {
            SurfaceDefinitionRegistry registry = new SurfaceDefinitionRegistry() {
                @Override public List<SurfaceDefinition> findByResourceKey(String key) { registryLookup.run(); return List.of(definition()); }
                @Override public List<SurfaceDefinition> findByGroup(String group) { registryLookup.run(); return List.of(definition()); }
            };
            return new SurfaceCatalogService(registry, (definition, context) -> {
                assertUnlocked(); callback.run(); return AvailabilityDecision.allowAll();
            }, (key, path, id) -> {
                assertUnlocked(); callback.run();
                return new SurfaceAvailabilityContext(key, path, id, null, Locale.ROOT, null, Set.of(), null);
            }, resolver, documents);
        }
        void assertUnlocked() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var lock = (ReentrantReadWriteLock) ReflectionTestUtils.getField(documents, "cacheLifecycleLock");
            assertThat(lock.getReadHoldCount()).isZero();
            assertThat(lock.isWriteLockedByCurrentThread()).isFalse();
            assertThat(ReflectionTestUtils.getField(documents, "publishedScope"))
                    .satisfies(scope -> assertThat(((ThreadLocal<?>) scope).get()).isNull());
        }
        void assertFencesClosed() {
            for (var fence : fences) assertThatThrownBy(() -> fence.read(() -> true))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
        @Override public void close() { client.close(); }
    }

    private static SurfaceDefinition definition() {
        return new SurfaceDefinition("children", RESOURCE, PATH, "parent", SurfaceKind.READ_PROJECTION, SurfaceScope.ITEM,
                "Children", "", "parent-children", "response", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("parent", "parent.view", PATH + "/{id}", "GET"),
                new CanonicalSchemaRef("child-schema", "response", "/schemas/filtered?path=/children"), 1,
                List.of(), List.of(), List.of(), new RelatedResourceSurface(RESOURCE, "id", "child.items", "/children",
                        "parentId", true, "id", List.of(RelatedResourceChildOperation.FILTER, RelatedResourceChildOperation.LIST,
                        RelatedResourceChildOperation.CREATE, RelatedResourceChildOperation.UPDATE,
                        RelatedResourceChildOperation.DELETE, RelatedResourceChildOperation.DUPLICATE_DRAFT)));
    }

    private static class Source extends OpenApiDocsSupport {
        final AtomicInteger captures = new AtomicInteger();
        boolean changed;
        @Override public String resolveGroupFromPath(String path) { return path.startsWith("/children") ? "child" : "parent"; }
        CachedOpenApiDocumentService owner;
        @Override public String localPublicationContextPath() { return ""; }
        @Override public OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(RestTemplate client, String path, Logger logger, ObjectMapper parser) {
            // Same direct producer-admission fixture seam as OpenApiPublicationPreparationTest;
            // concrete wire bytes and installed photograph, without a listener, DB or IAM claim.
            var request = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
            request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER,
                    ((OpenApiInternalRestTemplate) client).producerCaptureToken(path));
            assertThat(owner.consumeBulkOpenApiProducer(request)).isTrue();
            captures.incrementAndGet();
            String json = path.endsWith("/swagger-config") ? CONFIG : (changed ? OAS.replace("/parents", "/changed") : OAS);
            return OpenApiDocumentCapture.parse(json.getBytes(StandardCharsets.UTF_8), parser);
        }
        @Override public JsonNode fetchOpenApiDocument(RestTemplate client, String base, String group, Logger logger) {
            throw new AssertionError("governed reads must not fetch mutable producer data");
        }
    }
}
