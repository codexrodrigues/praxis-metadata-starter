package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

class CachedOpenApiDocumentServiceRefreshTest {
    @Test
    void freshnessCapabilityRequiresSpringdocSourceCacheToBeDisabled() {
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        assertThat(new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), support)
                .supportsFreshBulkLifecycleComposition()).isFalse();
        assertThat(new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), support, true)
                .supportsFreshBulkLifecycleComposition()).isTrue();
        OpenApiDocsSupport externallyConfigured = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(externallyConfigured, "openApiInternalBaseUrl", "https://other-node.internal");
        assertThat(new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), externallyConfigured, true)
                .supportsFreshBulkLifecycleComposition()).isFalse();
    }

    @Test
    void aCustomOpenApiServiceMustOptIntoLifecycleFreshnessExplicitly() {
        OpenApiDocumentService customService = new OpenApiDocumentService() {
            @Override public String resolveGroupFromPath(String path) { return "inventory"; }
            @Override public JsonNode getDocumentForGroup(String groupName) { return new ObjectMapper().createObjectNode(); }
            @Override public String getOrComputeSchemaHash(String schemaId, java.util.function.Supplier<JsonNode> supplier) { return "hash"; }
            @Override public void clearCaches() { }
        };

        assertThat(customService.supportsFreshBulkLifecycleComposition()).isFalse();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> customService.refreshDocumentForGroupStrict("inventory")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lifecycleRefreshBypassesAndReplacesTheCachedExactGroupDocument() throws Exception {
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        JsonNode cached = new ObjectMapper().readTree("{\"info\":{\"version\":\"cached\"}}");
        JsonNode refreshed = new ObjectMapper().readTree("{\"info\":{\"version\":\"fresh\"}}");
        when(support.fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(cached);
        when(support.fetchOpenApiDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(cached);
        when(support.fetchFreshOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(refreshed);
        var service = new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), support);
        ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");

        assertThat(service.getDocumentForGroupStrict("inventory").at("/info/version").asText()).isEqualTo("cached");
        assertThat(service.refreshDocumentForGroupStrict("inventory").at("/info/version").asText()).isEqualTo("fresh");
        assertThat(service.getDocumentForGroupStrict("inventory").at("/info/version").asText()).isEqualTo("fresh");
        verify(support, times(1)).fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(),
                eq("inventory"), any());
        verify(support, times(1)).fetchFreshOpenApiGroupDocument(any(RestTemplate.class), anyString(),
                eq("inventory"), any());
    }

    @Test
    void lifecycleSnapshotDoesNotReplaceTheSharedDocumentCache() throws Exception {
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        JsonNode cached = new ObjectMapper().readTree("{\"info\":{\"version\":\"cached\"}}");
        JsonNode refreshed = new ObjectMapper().readTree("{\"info\":{\"version\":\"fresh\"}}");
        when(support.fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(cached);
        when(support.fetchOpenApiDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(cached);
        when(support.fetchFreshOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(refreshed);
        var service = new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), support, true);
        ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");
        var guardCalls = new AtomicInteger();
        service.installBulkLifecycleInvalidationGuard(guardCalls::incrementAndGet);

        assertThat(service.getDocumentForGroupStrict("inventory").at("/info/version").asText()).isEqualTo("cached");
        assertThrows(IllegalStateException.class, () -> service.withFreshBulkLifecycleDocuments(
                Set.of("inventory"), () -> { throw new AssertionError("divergent cache must stop the callback"); }));
        assertThat(guardCalls).hasValue(1);
        assertThat(service.getDocumentForGroupStrict("inventory").at("/info/version").asText()).isEqualTo("cached");
        verify(support, times(2)).fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(),
                eq("inventory"), any());
    }

    @Test
    void lifecycleSnapshotRunsWhenPublicCacheMatchesFreshDocument() throws Exception {
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        JsonNode same = new ObjectMapper().readTree("{\"info\":{\"version\":\"same\"}}");
        when(support.fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(same);
        when(support.fetchOpenApiDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(same);
        when(support.fetchFreshOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(same);
        var service = new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), support, true);
        ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");

        assertThat(service.withFreshBulkLifecycleDocuments(Set.of("inventory"), () -> "composed"))
                .isEqualTo("composed");
        assertThat(service.getDocumentForGroupStrict("inventory").at("/info/version").asText()).isEqualTo("same");
    }

    @Test
    void strictRefreshInvalidatesHashesBeforePublishingTheNewDocument() throws Exception {
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        JsonNode cached = new ObjectMapper().readTree("{\"info\":{\"version\":\"cached\"}}");
        JsonNode refreshed = new ObjectMapper().readTree("{\"info\":{\"version\":\"fresh\"}}");
        when(support.fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(cached);
        when(support.fetchFreshOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(refreshed);
        var service = new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), support, true);
        ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");

        String oldHash = service.getOrComputeSchemaHash("same-id", () -> cached);
        service.refreshDocumentForGroupStrict("inventory");
        String newHash = service.getOrComputeSchemaHash("same-id", () -> refreshed);

        assertThat(newHash).isNotEqualTo(oldHash);
    }

    @Test
    void schemaMaterializationReadLockPreventsCacheInvalidationFromInterleaving() throws Exception {
        var service = new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(),
                mock(OpenApiDocsSupport.class), true);
        var readStarted = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var read = executor.submit(() -> service.withSchemaCacheReadLock(() -> {
                readStarted.countDown();
                try {
                    if (!releaseRead.await(2, TimeUnit.SECONDS))
                        throw new IllegalStateException("test read lock was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return null;
            }));
            assertThat(readStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var invalidation = executor.submit(service::clearCaches);
            assertThrows(TimeoutException.class, () -> invalidation.get(100, TimeUnit.MILLISECONDS));
            releaseRead.countDown();
            read.get(2, TimeUnit.SECONDS);
            invalidation.get(2, TimeUnit.SECONDS);
        } finally {
            releaseRead.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cacheMutationCannotInterleaveWithLifecycleComposition() throws Exception {
        var service = new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(),
                mock(OpenApiDocsSupport.class), true);
        var guardCalls = new AtomicInteger();
        service.installBulkLifecycleInvalidationGuard(guardCalls::incrementAndGet);
        var enteredComposition = new CountDownLatch(1);
        var releaseComposition = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var composition = executor.submit(() -> service.withBulkLifecycleCompositionLock(() -> {
                enteredComposition.countDown();
                try {
                    if (!releaseComposition.await(2, TimeUnit.SECONDS))
                        throw new IllegalStateException("test composition was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return null;
            }));
            assertThat(enteredComposition.await(2, TimeUnit.SECONDS)).isTrue();
            var invalidation = executor.submit(service::clearCaches);
            assertThrows(TimeoutException.class, () -> invalidation.get(100, TimeUnit.MILLISECONDS));
            assertThat(guardCalls.get()).isZero();
            releaseComposition.countDown();
            composition.get(2, TimeUnit.SECONDS);
            invalidation.get(2, TimeUnit.SECONDS);
            assertThat(guardCalls.get()).isEqualTo(1);
        } finally {
            releaseComposition.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void callersCannotMutateTheSharedCachedDocumentThroughReturnedJsonTree() throws Exception {
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        JsonNode source = new ObjectMapper().readTree("{\"info\":{\"version\":\"original\"}}");
        when(support.fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenReturn(source);
        var service = new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), support);
        ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");

        JsonNode exposed = service.getDocumentForGroupStrict("inventory");
        ((com.fasterxml.jackson.databind.node.ObjectNode) exposed.path("info")).put("version", "tampered");
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.path("info")).put("version", "source-mutated");

        assertThat(service.getDocumentForGroupStrict("inventory").at("/info/version").asText())
                .isEqualTo("original");
    }

    @Test
    void cacheClearWaitsForAnInFlightFillAndCannotLeaveItsResultBehind() throws Exception {
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        JsonNode stale = new ObjectMapper().readTree("{\"info\":{\"version\":\"stale\"}}");
        JsonNode fresh = new ObjectMapper().readTree("{\"info\":{\"version\":\"fresh\"}}");
        var fetchStarted = new CountDownLatch(1);
        var releaseFetch = new CountDownLatch(1);
        when(support.fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(), eq("inventory"), any()))
                .thenAnswer(invocation -> {
                    fetchStarted.countDown();
                    if (!releaseFetch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("fetch not released");
                    return stale;
                }).thenReturn(fresh);
        var service = new CachedOpenApiDocumentService(new OpenApiInternalRestTemplate(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10)), new ObjectMapper(), support);
        ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var fill = executor.submit(() -> service.getDocumentForGroupStrict("inventory"));
            assertThat(fetchStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var clear = executor.submit(service::clearCaches);
            assertThrows(TimeoutException.class, () -> clear.get(100, TimeUnit.MILLISECONDS));
            releaseFetch.countDown();
            assertThat(fill.get(2, TimeUnit.SECONDS).at("/info/version").asText()).isEqualTo("stale");
            clear.get(2, TimeUnit.SECONDS);
            assertThat(service.getDocumentForGroupStrict("inventory").at("/info/version").asText())
                    .isEqualTo("fresh");
            verify(support, times(2)).fetchOpenApiGroupDocument(any(RestTemplate.class), anyString(),
                    eq("inventory"), any());
        } finally {
            releaseFetch.countDown();
            executor.shutdownNow();
        }
    }
}
