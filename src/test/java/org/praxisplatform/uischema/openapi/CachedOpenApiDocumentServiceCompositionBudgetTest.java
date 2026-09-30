package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.slf4j.Logger;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CachedOpenApiDocumentServiceCompositionBudgetTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode OLD = JSON.createObjectNode().put("version", "old");
    private static final JsonNode NEW = JSON.createObjectNode().put("version", "new");

    @Test
    void publicReaderAndInvalidationProceedWhileFreshHttpIsPreparing() throws Exception {
        var source = new Source();
        var service = service(source, Duration.ofSeconds(5));
        service.getDocumentForGroupStrict("inventory");
        String oldHash = service.getOrComputeSchemaHash("id", () -> OLD);
        var fetchEntered = new CountDownLatch(1);
        var releaseFetch = new CountDownLatch(1);
        source.fresh = () -> { fetchEntered.countDown(); await(releaseFetch); return OLD; };
        var callback = new AtomicInteger();
        var guards = new AtomicInteger();
        service.installBulkLifecycleInvalidationGuard(() -> {
            assertThat(service.getOrComputeSchemaHash("id", () -> NEW)).isEqualTo(oldHash);
            guards.incrementAndGet();
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var composition = executor.submit(() -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"), callback::incrementAndGet));
            assertThat(fetchEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> service.getDocumentForGroup("inventory")).get(1, TimeUnit.SECONDS)).isEqualTo(OLD);
            executor.submit(service::clearCaches).get(1, TimeUnit.SECONDS);
            assertThat(guards).hasValue(1);
            releaseFetch.countDown();
            assertThat(assertThrows(ExecutionException.class, () -> composition.get(2, TimeUnit.SECONDS)))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(callback).hasValue(0);
            assertThat(cache(service)).isEmpty();
            assertThat(service.getOrComputeSchemaHash("id", () -> NEW)).isNotEqualTo(oldHash);
        } finally {
            releaseFetch.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedInvalidationGuardStillDiscardsAnAlreadyPreparedGeneration() throws Exception {
        var source = new Source();
        var service = service(source, Duration.ofSeconds(5));
        service.getDocumentForGroupStrict("inventory");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        source.fresh = () -> { entered.countDown(); await(release); return OLD; };
        service.installBulkLifecycleInvalidationGuard(() -> { throw new IllegalStateException("uncertain durable fence"); });
        var executor = Executors.newSingleThreadExecutor();
        var callback = new AtomicInteger();
        try {
            var future = executor.submit(() -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"), callback::incrementAndGet));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThrows(IllegalStateException.class, service::clearCaches);
            release.countDown();
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertThat(callback).hasValue(0);
            assertThat(service.getDocumentForGroupStrict("inventory")).isEqualTo(OLD);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void strictRefreshDuringPreparationDiscardsTheOldGenerationAndKeepsTheRefreshedDocument() throws Exception {
        var source = new Source();
        var service = service(source, Duration.ofSeconds(5));
        service.getDocumentForGroupStrict("inventory");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        source.fresh = () -> {
            if (Thread.currentThread().getName().equals("refresh-preparation")) {
                entered.countDown(); await(release); return OLD;
            }
            return NEW;
        };
        var callbacks = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "refresh-preparation"));
        try {
            var preparing = executor.submit(() -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"), callbacks::incrementAndGet));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.refreshDocumentForGroupStrict("inventory")).isEqualTo(NEW);
            release.countDown();
            assertThrows(ExecutionException.class, () -> preparing.get(2, TimeUnit.SECONDS));
            assertThat(callbacks).hasValue(0);
            assertThat(service.getDocumentForGroupStrict("inventory")).isEqualTo(NEW);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void coldCacheRequiresIndependentOrdinaryAndFreshEqualityBeforeInstallingAnything() {
        var source = new Source();
        source.fresh = () -> NEW;
        var service = service(source, Duration.ofSeconds(2));
        var guards = new AtomicInteger();
        service.installBulkLifecycleInvalidationGuard(guards::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"),
                () -> { throw new AssertionError("cold ordinary/fresh mismatch must fail closed"); }));
        assertThat(source.ordinaryReads).hasValue(1);
        assertThat(source.freshReads).hasValue(1);
        assertThat(guards).hasValue(1);
        assertThat(cache(service)).isEmpty();
    }

    @Test
    void timeoutDuringPreparationDoesNotInstallColdDocumentsOrReplaceWarmDocumentsAndHashes() {
        for (boolean warm : new boolean[] {false, true}) {
            var source = new Source();
            var service = service(source, Duration.ofMillis(40));
            if (warm) service.getDocumentForGroupStrict("inventory");
            String oldHash = service.getOrComputeSchemaHash("id", () -> OLD);
            source.fresh = () -> { delay(80); return NEW; };
            assertThrows(IllegalStateException.class, () -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"),
                    () -> { throw new AssertionError("expired preparation must not enter the callback"); }));
            assertThat(cache(service)).hasSize(warm ? 1 : 0);
            assertThat(service.getOrComputeSchemaHash("id", () -> NEW)).isEqualTo(oldHash);
            if (warm) assertThat(service.getDocumentForGroupStrict("inventory")).isEqualTo(OLD);
        }
    }

    @Test
    void publicationLockWaitConsumesTheSameBudgetAndLeavesWarmCacheUntouched() throws Exception {
        var service = service(new Source(), Duration.ofMillis(150));
        service.getDocumentForGroupStrict("inventory");
        var holdingRead = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var reader = executor.submit(() -> service.withSchemaCacheReadLock(() -> {
                holdingRead.countDown(); await(releaseRead); return null;
            }));
            assertThat(holdingRead.await(2, TimeUnit.SECONDS)).isTrue();
            var composition = executor.submit(() -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"),
                    () -> { throw new AssertionError("write lock wait exhausted the budget"); }));
            assertThrows(ExecutionException.class, () -> composition.get(2, TimeUnit.SECONDS));
            assertThat(service.getDocumentForGroupStrict("inventory")).isEqualTo(OLD);
            releaseRead.countDown();
            reader.get(2, TimeUnit.SECONDS);
        } finally {
            releaseRead.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void preparationQueueConsumesBudgetWithoutStartingAnotherFetch() throws Exception {
        var source = new Source();
        var service = service(source, Duration.ofMillis(150));
        service.getDocumentForGroupStrict("inventory");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        source.fresh = () -> { entered.countDown(); await(release); return OLD; };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"), () -> "first"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var queued = executor.submit(() -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"), () -> "queued"));
            assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS));
            assertThat(source.freshReads).hasValue(1);
            release.countDown();
            assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void publicationBoundaryChecksBudgetBeforeCasButDoesNotRetroactivelyRejectAnAdmittedCas() {
        var service = service(new Source(), Duration.ofMillis(40));
        var transitions = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"), () -> {
            delay(80);
            return service.withBulkLifecycleCompositionLock(transitions::incrementAndGet);
        }));
        assertThat(transitions).hasValue(0);
        assertThat(service.withFreshBulkLifecycleDocuments(Set.of("inventory"), () ->
                service.withBulkLifecycleCompositionLock(() -> { transitions.incrementAndGet(); delay(80); return "committed"; })))
                .isEqualTo("committed");
        assertThat(transitions).hasValue(1);
    }

    @Test
    void cacheClearCannotInterleaveWithThePublicationCallback() throws Exception {
        var service = service(new Source(), Duration.ofSeconds(5));
        var publicationEntered = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var generation = new AtomicInteger();
        service.installBulkLifecycleInvalidationGuard(() -> assertThat(generation.getAndIncrement()).isEqualTo(1));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var publication = executor.submit(() -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"), () -> {
                publicationEntered.countDown();
                await(releasePublication);
                return service.withBulkLifecycleCompositionLock(() -> generation.compareAndSet(0, 1));
            }));
            assertThat(publicationEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var invalidation = executor.submit(service::clearCaches);
            assertThrows(TimeoutException.class, () -> invalidation.get(100, TimeUnit.MILLISECONDS));
            releasePublication.countDown();
            assertThat(publication.get(2, TimeUnit.SECONDS)).isTrue();
            invalidation.get(2, TimeUnit.SECONDS);
            assertThat(generation).hasValue(2);
        } finally {
            releasePublication.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void knownDriftStillRunsTheGuardAfterAdmissionBudgetExpiresAndPreservesCacheIfGuardFails() {
        var source = new Source();
        var service = service(source, Duration.ofMillis(250));
        service.getDocumentForGroupStrict("inventory");
        String hash = service.getOrComputeSchemaHash("id", () -> OLD);
        source.fresh = SlowMismatch::new;
        var guards = new AtomicInteger();
        service.installBulkLifecycleInvalidationGuard(() -> service.withBulkLifecycleCompositionLock(() -> {
            guards.incrementAndGet();
            throw new IllegalStateException("durable suspension failed");
        }));
        assertThat(assertThrows(IllegalStateException.class, () -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"),
                () -> { throw new AssertionError("drift must not publish"); })))
                .hasMessage("durable suspension failed");
        assertThat(guards).hasValue(1);
        assertThat(service.getDocumentForGroupStrict("inventory")).isEqualTo(OLD);
        assertThat(service.getOrComputeSchemaHash("id", () -> NEW)).isEqualTo(hash);
    }

    private static final class SlowMismatch extends com.fasterxml.jackson.databind.node.ObjectNode {
        private SlowMismatch() { super(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance); put("version", "new"); }
        @Override public com.fasterxml.jackson.databind.node.ObjectNode deepCopy() { return this; }
        @Override public boolean equals(Object other) { delay(350); return false; }
    }

    private static CachedOpenApiDocumentService service(Source source, Duration timeout) {
        var http = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
        var service = new CachedOpenApiDocumentService(http, JSON, source, true, timeout);
        ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");
        return service;
    }

    private static Map<?, ?> cache(CachedOpenApiDocumentService service) {
        return (Map<?, ?>) ReflectionTestUtils.getField(service, "documentCache");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("latch was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }

    private static void delay(long millis) {
        try { TimeUnit.MILLISECONDS.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static final class Source extends OpenApiDocsSupport {
        private final AtomicInteger ordinaryReads = new AtomicInteger();
        private final AtomicInteger freshReads = new AtomicInteger();
        private Supplier<JsonNode> fresh = () -> OLD;
        @Override public JsonNode fetchOpenApiDocument(RestTemplate client, String base, String group, Logger logger) {
            ordinaryReads.incrementAndGet(); return OLD.deepCopy();
        }
        @Override public JsonNode fetchOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
            return OLD.deepCopy();
        }
        @Override public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
            freshReads.incrementAndGet(); return fresh.get().deepCopy();
        }
    }
}
