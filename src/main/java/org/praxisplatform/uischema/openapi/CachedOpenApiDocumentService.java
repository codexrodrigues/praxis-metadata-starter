package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Implementacao padrao de {@link OpenApiDocumentService} com cache em memoria.
 *
 * <p>
 * Mantem cache separado para documentos OpenAPI por grupo e para hashes estruturais por
 * {@code schemaId}. O fetch do documento continua delegando a {@link OpenApiDocsSupport}; esta
 * classe apenas concentra a politica de memoizacao e a traducao de falhas em excecoes estruturais
 * adequadas para os controllers canonicamente expostos.
 * </p>
 *
 * <p>
 * Em termos de plataforma, esta classe e o ponto central de cache para documentos e hashes
 * estruturais. Ela evita que controllers e resolvedores repitam fetch remoto e recalculo de hash
 * com criterios divergentes.
 * </p>
 */
public class CachedOpenApiDocumentService implements OpenApiDocumentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachedOpenApiDocumentService.class);

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String openApiBasePath;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final OpenApiDocsSupport openApiDocsSupport;
    private final boolean springdocCacheDisabled;
    private final SchemaCanonicalizer schemaCanonicalizer = new SchemaCanonicalizer();
    private final Map<String, CachedDocument> documentCache = new ConcurrentHashMap<>();
    private final Map<String, String> schemaHashCache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock cacheLifecycleLock = new ReentrantReadWriteLock(true);
    private final ReentrantLock compositionPreparationLock = new ReentrantLock(true);
    private final Duration bulkCompositionTimeout;
    private final ThreadLocal<OpenApiInternalRestTemplate.Deadline> compositionDeadline = new ThreadLocal<>();
    private final ThreadLocal<Boolean> invalidationFence = ThreadLocal.withInitial(() -> false);
    // Local cache invalidation fence only; this is not a revision of the upstream OpenAPI source.
    private long cacheInvalidationEpoch;
    private volatile Runnable bulkLifecycleInvalidationGuard;
    private final ThreadLocal<Map<String, JsonNode>> lifecycleSnapshot = new ThreadLocal<>();

    public CachedOpenApiDocumentService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            OpenApiDocsSupport openApiDocsSupport
    ) {
        this(restTemplate, objectMapper, openApiDocsSupport, false);
    }

    public CachedOpenApiDocumentService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            OpenApiDocsSupport openApiDocsSupport,
            boolean springdocCacheDisabled
    ) {
        this(restTemplate, objectMapper, openApiDocsSupport, springdocCacheDisabled, Duration.ofSeconds(60));
    }

    public CachedOpenApiDocumentService(RestTemplate restTemplate, ObjectMapper objectMapper,
            OpenApiDocsSupport openApiDocsSupport, boolean springdocCacheDisabled, Duration bulkCompositionTimeout) {
        OpenApiInternalRestTemplate.positiveMillis(bulkCompositionTimeout, "bulkCompositionTimeout");
        this.bulkCompositionTimeout = bulkCompositionTimeout;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.openApiDocsSupport = openApiDocsSupport;
        this.springdocCacheDisabled = springdocCacheDisabled;
    }

    @Override
    public String resolveGroupFromPath(String path) {
        return openApiDocsSupport.resolveGroupFromPath(path);
    }

    @Override
    public JsonNode getDocumentForGroup(String groupName) {
        return withCacheReadLock(() -> {
            Map<String, JsonNode> snapshot = lifecycleSnapshot.get();
            if (snapshot != null) {
                JsonNode document = snapshot.get(groupName);
                if (document == null)
                    throw new IllegalStateException("Group is outside the fresh lifecycle snapshot: " + groupName);
                return document.deepCopy();
            }
            return documentCache.computeIfAbsent(groupName, group -> {
                try {
                    JsonNode groupDoc = openApiDocsSupport.fetchOpenApiDocument(restTemplate, openApiBasePath, group, LOGGER);
                    if (groupDoc != null) {
                        long sizeKB = estimateJsonSize(groupDoc) / 1024;
                        LOGGER.info("Documento OpenAPI especifico cacheado para grupo '{}' (~{}KB)", group, sizeKB);
                        return new CachedDocument(groupDoc, false);
                    }
                    throw new IllegalStateException("OpenAPI document helper returned null for group: " + group);
                } catch (Exception e) {
                    LOGGER.error("Falha critica ao buscar documento OpenAPI para grupo '{}': {}", group, e.getMessage());
                    throw new IllegalStateException("Failed to retrieve the OpenAPI document for group: " + group, e);
                }
            }).document();
        });
    }

    @Override
    public JsonNode getDocumentForGroupStrict(String groupName) {
        return withCacheReadLock(() -> {
            Map<String, JsonNode> snapshot = lifecycleSnapshot.get();
            if (snapshot != null) {
                JsonNode document = snapshot.get(groupName);
                if (document == null) throw new IllegalStateException("Group is outside the fresh lifecycle snapshot: " + groupName);
                return document.deepCopy();
            }
            return documentCache.compute(groupName, (group, cached) -> {
                if (cached != null && cached.exactGroupDocument()) {
                    return cached;
                }
                try {
                    JsonNode groupDoc = openApiDocsSupport.fetchOpenApiGroupDocument(
                            restTemplate, openApiBasePath, group, LOGGER);
                    if (groupDoc == null) {
                        throw new IllegalStateException("OpenAPI strict group helper returned null for: " + group);
                    }
                    // A strict read may prove an existing public entry exact, but cannot replace
                    // its content under the read lock. Changes require the guarded refresh path.
                    if (cached != null && !groupDoc.equals(cached.document())) {
                        throw new IllegalStateException("Strict OpenAPI group differs from the public cache; "
                                + "a guarded refresh is required: " + group);
                    }
                    long sizeKB = estimateJsonSize(groupDoc) / 1024;
                    LOGGER.info("Documento OpenAPI exato cacheado para grupo '{}' (~{}KB)", group, sizeKB);
                    return new CachedDocument(groupDoc, true);
                } catch (Exception e) {
                    LOGGER.error("Falha ao buscar documento OpenAPI estrito para grupo '{}': {}", group, e.getMessage());
                    throw new IllegalStateException("Failed to retrieve the exact OpenAPI document for group: " + group, e);
                }
            }).document();
        });
    }

    @Override
    public JsonNode refreshDocumentForGroupStrict(String groupName) {
        Map<String, JsonNode> snapshot = lifecycleSnapshot.get();
        if (snapshot != null) {
            JsonNode document = snapshot.get(groupName);
            if (document == null) throw new IllegalStateException("Group is outside the fresh lifecycle snapshot: " + groupName);
            return document.deepCopy();
        }
        return withBulkLifecycleCompositionLock(() -> {
            cacheInvalidationEpoch = Math.incrementExact(cacheInvalidationEpoch);
            runBulkLifecycleInvalidationGuard();
            if (groupName == null || groupName.isBlank())
                throw new IllegalArgumentException("An exact published OpenAPI group is required");
            JsonNode refreshed = documentCache.compute(groupName, (group, ignored) -> {
                try {
                    JsonNode groupDoc = openApiDocsSupport.fetchFreshOpenApiGroupDocument(
                            restTemplate, openApiBasePath, group, LOGGER);
                    if (groupDoc == null || !groupDoc.isObject())
                        throw new IllegalStateException("OpenAPI strict group helper returned no object for: " + group);
                    long sizeKB = estimateJsonSize(groupDoc) / 1024;
                    LOGGER.info("Documento OpenAPI exato atualizado para grupo '{}' (~{}KB)", group, sizeKB);
                    return new CachedDocument(groupDoc, true);
                } catch (Exception e) {
                    LOGGER.error("Falha ao atualizar documento OpenAPI estrito para grupo '{}': {}", group, e.getMessage());
                    throw new IllegalStateException("Failed to refresh the exact OpenAPI document for group: " + group, e);
                }
            }).document();
            schemaHashCache.clear();
            return refreshed;
        });
    }

    @Override
    public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
        return withCacheReadLock(() -> schemaHashCache.computeIfAbsent(schemaId, key -> {
                JsonNode payloadNode = payloadSupplier.get();
                JsonNode canonical = schemaCanonicalizer.canonicalize(payloadNode);
                return SchemaHashUtil.sha256Hex(canonical);
            }));
    }

    private <T> T withCacheReadLock(Supplier<T> action) {
        cacheLifecycleLock.readLock().lock();
        try {
            return action.get();
        } finally {
            cacheLifecycleLock.readLock().unlock();
        }
    }

    @Override
    public void clearCaches() {
        // Once a caller holds the publication lock, suspension/cleanup is an obligation, not
        // a new admission. In particular known drift must still fence after its budget expires.
        if (cacheLifecycleLock.isWriteLockedByCurrentThread()) {
            invalidateUnderWriteLock();
        } else {
            withBulkLifecycleCompositionLock(() -> { invalidateUnderWriteLock(); return null; });
        }
    }

    private void invalidateUnderWriteLock() {
        cacheInvalidationEpoch = Math.incrementExact(cacheInvalidationEpoch);
        boolean previousFence = invalidationFence.get();
        invalidationFence.set(true);
        try {
            runBulkLifecycleInvalidationGuard();
            int cacheSize = documentCache.size();
            int schemaCacheSize = schemaHashCache.size();
            documentCache.clear();
            schemaHashCache.clear();
            LOGGER.info("OpenAPI document and schema-hash caches cleared: {} documents, {} hashes", cacheSize, schemaCacheSize);
        } finally {
            if (previousFence) invalidationFence.set(true);
            else invalidationFence.remove();
        }
    }

    @Override
    public synchronized void installBulkLifecycleInvalidationGuard(Runnable guard) {
        if (guard == null) throw new IllegalArgumentException("guard is required");
        if (bulkLifecycleInvalidationGuard != null && bulkLifecycleInvalidationGuard != guard)
            throw new IllegalStateException("A different bulk lifecycle cache invalidation guard is already installed");
        bulkLifecycleInvalidationGuard = guard;
    }

    @Override
    public <T> T withBulkLifecycleCompositionLock(Supplier<T> action) {
        if (action == null) throw new IllegalArgumentException("action is required");
        if (cacheLifecycleLock.isWriteLockedByCurrentThread() && invalidationFence.get())
            return action.get();
        if (cacheLifecycleLock.getReadHoldCount() > 0 && !cacheLifecycleLock.isWriteLockedByCurrentThread())
            throw new IllegalStateException("A schema read cannot upgrade to the bulk lifecycle write lock");
        return withCompositionDeadline(() -> withTimedLock(cacheLifecycleLock.writeLock(), action));
    }

    @Override
    public <T> T withSchemaCacheReadLock(Supplier<T> action) {
        if (action == null) throw new IllegalArgumentException("action is required");
        return withCacheReadLock(action);
    }

    @Override
    public <T> T withFreshBulkLifecycleDocuments(Set<String> groups, Supplier<T> action) {
        if (groups == null || groups.isEmpty() || action == null)
            throw new IllegalArgumentException("groups and action are required");
        if (!supportsFreshBulkLifecycleComposition())
            throw new IllegalStateException("Fresh bulk composition requires a local uncached source and Metadata-owned bounded HTTP transport");
        if (lifecycleSnapshot.get() != null || cacheLifecycleLock.isWriteLockedByCurrentThread()
                || cacheLifecycleLock.getReadHoldCount() > 0)
            throw new IllegalStateException("Fresh lifecycle preparation must start outside cache locks and snapshots");
        for (String group : groups) {
            if (group == null || group.isBlank() || !group.equals(group.strip()))
                throw new IllegalArgumentException("OpenAPI group identities must be canonical");
        }
        var internal = (OpenApiInternalRestTemplate) restTemplate;
        long transportRevision = internal.transportRevision();
        return withCompositionDeadline(() -> withTimedLock(compositionPreparationLock, () -> {
            internal.requireTransportRevision(transportRevision);
            Map<String, JsonNode> publicDocuments = new LinkedHashMap<>();
            long capturedEpoch = withTimedLock(cacheLifecycleLock.readLock(), () -> {
                for (String group : groups) {
                    CachedDocument cached = documentCache.get(group);
                    if (cached != null) publicDocuments.put(group, cached.document());
                }
                return cacheInvalidationEpoch;
            });
            Map<String, JsonNode> fresh = new LinkedHashMap<>();
            for (String group : groups.stream().sorted().toList()) {
                if (!publicDocuments.containsKey(group)) {
                    JsonNode ordinary = ((OpenApiInternalRestTemplate) restTemplate).withDeadline(compositionDeadline.get(),
                            () -> openApiDocsSupport.fetchOpenApiDocument(restTemplate, openApiBasePath, group, LOGGER));
                    if (ordinary == null || !ordinary.isObject())
                        throw new IllegalStateException("Public OpenAPI group document is unavailable: " + group);
                    publicDocuments.put(group, ordinary.deepCopy());
                }
                JsonNode document = ((OpenApiInternalRestTemplate) restTemplate).withDeadline(compositionDeadline.get(),
                        () -> openApiDocsSupport.fetchFreshOpenApiGroupDocument(
                                restTemplate, openApiBasePath, group, LOGGER));
                if (document == null || !document.isObject())
                    throw new IllegalStateException("Fresh exact OpenAPI group document is unavailable: " + group);
                fresh.put(group, document.deepCopy());
            }
            return withBulkLifecycleCompositionLock(() -> {
                if (cacheInvalidationEpoch != capturedEpoch)
                    throw new IllegalStateException("OpenAPI caches were invalidated during fresh lifecycle preparation");
                for (var entry : fresh.entrySet()) {
                    CachedDocument cached = documentCache.get(entry.getKey());
                    if (!entry.getValue().equals(publicDocuments.get(entry.getKey()))
                            || (cached != null && !entry.getValue().equals(cached.document()))) {
                        // The durable guard observes the old cache before either cache is cleared.
                        clearCaches();
                        throw new IllegalStateException("Public OpenAPI cache differs from the fresh lifecycle document: " + entry.getKey());
                    }
                }
                internal.requireTransportRevision(transportRevision);
                compositionDeadline.get().remainingNanos();
                // Only publish cold entries after every independent public/fresh comparison passed.
                for (var entry : fresh.entrySet())
                    documentCache.putIfAbsent(entry.getKey(), new CachedDocument(entry.getValue(), true));
                lifecycleSnapshot.set(Map.copyOf(fresh));
                try {
                    return action.get();
                } finally {
                    lifecycleSnapshot.remove();
                }
            });
        }));
    }

    @Override
    public BulkLifecycleDocumentFence captureBulkLifecycleDocumentFence() {
        if (lifecycleSnapshot.get() == null || !cacheLifecycleLock.isWriteLockedByCurrentThread())
            throw new IllegalStateException("A document fence must be captured inside its fresh lifecycle snapshot");
        var owner = Thread.currentThread();
        var deadline = compositionDeadline.get();
        long epoch = cacheInvalidationEpoch;
        var internal = (OpenApiInternalRestTemplate) restTemplate;
        long transportRevision = internal.transportRevision();
        internal.requireTransportRevision(transportRevision);
        deadline.remainingNanos();
        return new BulkLifecycleDocumentFence() {
            private volatile boolean closed;

            private void validate() {
                if (closed || Thread.currentThread() != owner)
                    throw new IllegalStateException("The lifecycle document fence is closed or belongs to another thread");
                deadline.remainingNanos();
                if (cacheInvalidationEpoch != epoch)
                    throw new IllegalStateException("OpenAPI caches changed after lifecycle descriptor capture");
                internal.requireTransportRevision(transportRevision);
            }

            @Override public <T> T read(Supplier<T> verification) {
                java.util.Objects.requireNonNull(verification, "verification");
                if (closed || Thread.currentThread() != owner)
                    throw new IllegalStateException("The lifecycle document fence is closed or belongs to another thread");
                return withTimedLock(cacheLifecycleLock.readLock(), deadline, () -> {
                    validate();
                    T result = verification.get();
                    validate();
                    return result;
                });
            }

            @Override public void close() { closed = true; }
        };
    }

    private <T> T withCompositionDeadline(Supplier<T> action) {
        boolean owner = compositionDeadline.get() == null;
        if (owner) compositionDeadline.set(new OpenApiInternalRestTemplate.Deadline(bulkCompositionTimeout));
        try {
            compositionDeadline.get().remainingNanos();
            return action.get();
        } finally {
            if (owner) compositionDeadline.remove();
        }
    }

    private <T> T withTimedLock(Lock lock, Supplier<T> action) {
        return withTimedLock(lock, compositionDeadline.get(), action);
    }

    private <T> T withTimedLock(Lock lock, OpenApiInternalRestTemplate.Deadline deadline, Supplier<T> action) {
        try {
            if (!lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS))
                throw new IllegalStateException("Bulk OpenAPI composition admission budget exhausted waiting for a lock");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the bulk OpenAPI composition lock", interrupted);
        }
        try {
            deadline.remainingNanos();
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean supportsFreshBulkLifecycleComposition() {
        return springdocCacheDisabled && !openApiDocsSupport.usesConfiguredInternalBaseUrl()
                && restTemplate instanceof OpenApiInternalRestTemplate internal && internal.hasOwnedRequestFactory();
    }

    @Override
    public boolean supportsFreshBulkLifecyclePublicCacheCoherence() {
        return supportsFreshBulkLifecycleComposition();
    }

    private void runBulkLifecycleInvalidationGuard() {
        Runnable guard = bulkLifecycleInvalidationGuard;
        if (guard != null) guard.run();
    }

    private long estimateJsonSize(JsonNode jsonNode) {
        try {
            return objectMapper.writeValueAsString(jsonNode).length();
        } catch (Exception e) {
            return 0;
        }
    }

    private record CachedDocument(JsonNode document, boolean exactGroupDocument) {
        private CachedDocument {
            if (document == null) throw new IllegalArgumentException("document is required");
            document = document.deepCopy();
        }

        @Override
        public JsonNode document() { return document.deepCopy(); }
    }
}
