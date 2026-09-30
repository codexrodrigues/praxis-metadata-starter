package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.slf4j.Logger;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CachedOpenApiDocumentServiceDocumentFenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode OLD = JSON.createObjectNode().put("version", "old");
    private static final JsonNode NEW = JSON.createObjectNode().put("version", "new");

    @Test
    void captureRequiresFreshProvenanceAndVerificationUsesOnlyAShortReadLock() {
        try (var client = client()) {
            var service = service(client, new Source(), Duration.ofSeconds(5));
            assertThrows(IllegalStateException.class, service::captureBulkLifecycleDocumentFence);
            var fence = capture(service);
            var lock = lock(service);
            assertThat(lock.isWriteLocked()).isFalse();
            assertThat(((ReentrantLock) ReflectionTestUtils.getField(service, "compositionPreparationLock")).isLocked()).isFalse();
            assertThat(fence.read(() -> {
                assertThat(lock.getReadHoldCount()).isPositive();
                assertThat(lock.isWriteLocked()).isFalse();
                return "verified";
            })).isEqualTo("verified");
            fence.close();
            assertThrows(IllegalStateException.class, () -> fence.read(() -> "escaped"));
            try (var next = capture(service)) {
                assertThat(next.read(() -> "next response")).isEqualTo("next response");
            }
        }
    }

    @Test
    void independentInvalidationCompletesAfterCaptureAndRejectsTheOldFence() throws Exception {
        try (var client = client()) {
            var service = service(client, new Source(), Duration.ofSeconds(5));
            var guards = new AtomicInteger();
            service.installBulkLifecycleInvalidationGuard(guards::incrementAndGet);
            try (var fence = capture(service); var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(service::clearCaches).get(1, TimeUnit.SECONDS);
                assertThat(guards).hasValue(1);
                assertThrows(IllegalStateException.class, () -> fence.read(() -> {
                    throw new AssertionError("invalidated capture must not verify durable readiness");
                }));
            }
        }
    }

    @Test
    void failedGuardPreservesCacheAndHashButInvalidatesCapturedFence() {
        try (var client = client()) {
            var service = service(client, new Source(), Duration.ofSeconds(5));
            service.installBulkLifecycleInvalidationGuard(() -> { throw new IllegalStateException("guard failed"); });
            try (var fence = capture(service)) {
                String hash = service.getOrComputeSchemaHash("schema", () -> OLD);
                assertThrows(IllegalStateException.class, service::clearCaches);
                assertThat(service.getDocumentForGroupStrict("inventory")).isEqualTo(OLD);
                assertThat(service.getOrComputeSchemaHash("schema", () -> NEW)).isEqualTo(hash);
                assertThrows(IllegalStateException.class, () -> fence.read(() -> true));
            }
        }
    }

    @Test
    void strictRefreshAndTransportReplacementIncludingAbaInvalidateTheCapture() {
        try (var client = client()) {
            var source = new Source();
            var service = service(client, source, Duration.ofSeconds(5));
            try (var fence = capture(service)) {
                source.document = NEW;
                service.refreshDocumentForGroupStrict("inventory");
                assertThrows(IllegalStateException.class, () -> fence.read(() -> true));
            }
            try (var fence = capture(service)) {
                var originalFactory = client.getRequestFactory();
                client.setRequestFactory(new SimpleClientHttpRequestFactory());
                client.setRequestFactory(originalFactory);
                assertThat(client.hasOwnedRequestFactory()).isTrue();
                assertThrows(IllegalStateException.class, () -> fence.read(() -> true));
            }
        }
    }

    @Test
    void fenceRejectsOtherThreadsAndChecksTransportAgainAfterVerification() throws Exception {
        try (var client = client()) {
            var service = service(client, new Source(), Duration.ofSeconds(5));
            try (var fence = capture(service); var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> assertThrows(IllegalStateException.class,
                        () -> fence.read(() -> true))).get(1, TimeUnit.SECONDS);
                assertThat(fence.read(() -> true)).isTrue();
                assertThrows(IllegalStateException.class, () -> fence.read(() -> {
                    client.setRequestFactory(new SimpleClientHttpRequestFactory());
                    return true;
                }));
            }
        }
    }

    @Test
    void readLockWaitUsesOriginalDeadlineInsteadOfStartingANewBudget() throws Exception {
        try (var client = client()) {
            var service = service(client, new Source(), Duration.ofMillis(500));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try (var fence = capture(service)) {
                var writer = executor.submit(() -> service.withBulkLifecycleCompositionLock(() -> {
                    entered.countDown();
                    await(release);
                    return null;
                }));
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                long started = System.nanoTime();
                assertThrows(IllegalStateException.class, () -> fence.read(() -> {
                    throw new AssertionError("expired read-lock admission must not enter verification");
                }));
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                release.countDown();
                writer.get(1, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void deadlineIsCheckedAfterVerificationAndOriginalExceptionIsPreserved() {
        try (var client = client()) {
            var service = service(client, new Source(), Duration.ofMillis(200));
            try (var fence = capture(service)) {
                var failure = new IllegalArgumentException("verification failed");
                assertThat(assertThrows(IllegalArgumentException.class,
                        () -> fence.read(() -> { throw failure; }))).isSameAs(failure);
                assertThrows(IllegalStateException.class, () -> fence.read(() -> {
                    try { TimeUnit.MILLISECONDS.sleep(250); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    return true;
                }));
            }
        }
    }

    private static OpenApiInternalRestTemplate client() {
        return new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    private static CachedOpenApiDocumentService service(OpenApiInternalRestTemplate client, Source source, Duration budget) {
        var service = new CachedOpenApiDocumentService(client, JSON, source, true, budget);
        ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");
        return service;
    }

    private static OpenApiDocumentService.BulkLifecycleDocumentFence capture(CachedOpenApiDocumentService service) {
        return service.withFreshBulkLifecycleDocuments(Set.of("inventory"), service::captureBulkLifecycleDocumentFence);
    }

    private static ReentrantReadWriteLock lock(CachedOpenApiDocumentService service) {
        return (ReentrantReadWriteLock) ReflectionTestUtils.getField(service, "cacheLifecycleLock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("latch was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Source extends OpenApiDocsSupport {
        private JsonNode document = OLD;
        @Override public JsonNode fetchOpenApiDocument(RestTemplate client, String base, String group, Logger logger) {
            return document.deepCopy();
        }
        @Override public JsonNode fetchOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
            return document.deepCopy();
        }
        @Override public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
            return document.deepCopy();
        }
    }
}
