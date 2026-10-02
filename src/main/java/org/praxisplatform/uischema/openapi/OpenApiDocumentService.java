package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Fronteira canonica para documentos OpenAPI usados pelo runtime metadata-driven.
 *
 * <p>
 * Este servico centraliza quatro responsabilidades compartilhadas: resolucao do grupo a partir do
 * path, leitura do documento OpenAPI do grupo, cache estrutural desses documentos e calculo de
 * hash canonico para payloads de schema.
 * </p>
 *
 * <p>
 * No desenho atual do starter, essa interface sustenta tanto endpoints documentais quanto a
 * resolucao estrutural usada por {@code /schemas/filtered}. Ela existe para evitar que cada
 * superfície implemente fetch, cache e hashing de forma divergente.
 * </p>
 */
public interface OpenApiDocumentService {

    /**
     * Resolve o grupo OpenAPI associado ao path informado.
     *
     * <p>
     * Implementacoes podem normalizar o path conforme necessario para reaproveitar a mesma logica
     * de roteamento usada pelos grupos dinamicos do SpringDoc.
     * </p>
     */
    String resolveGroupFromPath(String path);

    /**
     * Retorna o documento OpenAPI do grupo informado.
     *
     * <p>
     * Implementacoes podem buscar o documento remotamente e reutilizar cache interno. Falhas de
     * resolucao ou fetch devem emergir como excecao estrutural, pois essa chamada alimenta
     * superficies canonicas como {@code /schemas/filtered} e {@code /schemas/catalog}.
     * </p>
     */
    JsonNode getDocumentForGroup(String groupName);

    /**
     * Returns the document fetched for exactly the named published group, without substituting
     * the ungrouped document. Strict identity verification must use this method because the
     * ordinary group reader intentionally supports a legacy base-document fallback.
     *
     * <p>Implementations that cannot establish the source group must fail closed.</p>
     */
    default JsonNode getDocumentForGroupStrict(String groupName) {
        throw new UnsupportedOperationException(
                "This OpenApiDocumentService cannot verify exact published group documents");
    }

    /**
     * Fetches the exact published group again, bypassing any process-local document cache.
     * Implementations that cannot establish freshness must fail closed. This is intended for
     * lifecycle publication and invalidation, not request-path schema reads.
     */
    default JsonNode refreshDocumentForGroupStrict(String groupName) {
        throw new UnsupportedOperationException(
                "This OpenApiDocumentService cannot refresh an exact published group document");
    }

    /**
     * Installs the durable bulk lifecycle fence which must run before any public cache clear or
     * strict refresh. Implementations that cannot enforce this centrally must fail closed when
     * the governed bulk lifecycle is enabled.
     */
    default void installBulkLifecycleInvalidationGuard(Runnable guard) {
        throw new UnsupportedOperationException(
                "This OpenApiDocumentService cannot fence cache invalidation for bulk lifecycle");
    }

    /**
     * Serializes lifecycle publication (through its durable READY CAS) against any public cache
     * invalidation (through the cache mutation). Implementations without this exclusion fail closed.
     * Re-entry from a fresh composition must enforce its remaining admission budget before running
     * the action. A committed transition is not retroactively timed out. Do not hold this lock
     * around the HTTP preparation performed by {@link #withFreshBulkLifecycleDocuments}.
     */
    default <T> T withBulkLifecycleCompositionLock(Supplier<T> action) {
        throw new UnsupportedOperationException(
                "This OpenApiDocumentService cannot serialize bulk lifecycle composition and invalidation");
    }

    /**
     * Holds the document/hash-cache read side for one complete public schema materialization.
     * Implementations with mutable caches must prevent invalidation between document selection,
     * payload construction and hash insertion.
     */
    default <T> T withSchemaCacheReadLock(Supplier<T> action) {
        if (action == null) throw new IllegalArgumentException("action is required");
        return action.get();
    }

    /**
     * Fetches the supplied exact groups into an isolated, immutable lifecycle snapshot, verifies
     * that ordinary public document reads on this node resolve the same JSON, and makes the
     * snapshot visible to strict reads only for the duration of {@code action}. If the public
     * cache differs, implementations must run the invalidation guard, clear document and schema
     * hash caches, and fail before executing {@code action}. Preparation must occur outside the
     * public cache write lock; callers must not hold either side of that lock on entry. A local
     * invalidation during preparation discards it without retry or callback. Cold entries require
     * an independent ordinary read matching the fresh document before installation; existing
     * entries are never replaced by the snapshot. The callback, including any durable READY CAS,
     * runs under the publication/cache-invalidation exclusion. Implementations must bound queue,
     * lock and HTTP admission waits; this does not promise cancellation of remote server work,
     * arbitrary custom source code or a database transaction already admitted.
     */
    default <T> T withFreshBulkLifecycleDocuments(Set<String> groups, Supplier<T> action) {
        throw new UnsupportedOperationException(
                "This OpenApiDocumentService cannot provide an isolated fresh lifecycle snapshot");
    }

    /**
     * Composes against a prepared response set owned by this service, without HTTP fetch or
     * installing it in public document/hash caches. The synchronous structural callback must
     * not publish READY or mutate the database. This scope proves local capture coherence only;
     * durable publication and installation after commit require their own fences. Implementations
     * must reject foreign/invalidated candidates, nesting and entry from transactions/cache locks,
     * and isolate schema hashes until this unpublished scope closes.
     */
    default <T> T withPreparedBulkOpenApiPublication(OpenApiPublicationCandidate candidate, Supplier<T> composition) {
        throw new UnsupportedOperationException("This OpenApiDocumentService cannot compose a prepared publication");
    }

    /** Prepares root, config and the whole group set through attested producer captures. */
    default OpenApiPublicationCandidate prepareBulkOpenApiPublication(Set<String> groups) {
        throw new UnsupportedOperationException("This service cannot prepare a governed OpenAPI publication");
    }
    /**
     * Registers a durable global tuple validator. Outside operational transactions the validator
     * owns a short read transaction. Inside a writable operational transaction it must use the
     * attested bound connection, retain its SHARE fence until completion and never open another transaction.
     */
    default void installBulkLifecyclePublicationGuard(java.util.function.BiConsumer<Long, String> guard) {
        throw new UnsupportedOperationException("This service cannot validate a durable OpenAPI publication");
    }
    /** Requires a registered Servlet serving boundary before any durable publication attempt. */
    default void requireBulkOpenApiServing() {
        throw new UnsupportedOperationException("This service cannot attest governed OpenAPI serving");
    }
    /** Installs the locally owned photograph only after commit of its exact global tuple. */
    default void installBulkOpenApiPublication(OpenApiPublicationCandidate candidate, long generation) {
        throw new UnsupportedOperationException("This service cannot install a committed OpenAPI publication");
    }
    /** Short writer fence for admission of the prepared candidate's control transaction. No HTTP. */
    default <T> T withPreparedBulkOpenApiCommit(OpenApiPublicationCandidate candidate, Supplier<T> commit) {
        throw new UnsupportedOperationException("This service cannot fence a prepared publication commit");
    }
    /** Local presence only, not authority; any following scope must still validate the durable tuple. */
    default boolean hasLocalPublishedBulkOpenApiPublication() { return false; }
    /**
     * Structural composition against the locally installed, durably validated photograph; no HTTP.
     * Group getters return defensive copies of the requested group only. Bound operational reads
     * must validate the photograph before and after composition without acquiring cache locks;
     * this scope grants neither readiness nor domain authorization.
     */
    default <T> T withPublishedBulkOpenApiPublication(java.util.function.BiFunction<OpenApiPublicationCandidate, Long, T> composition) {
        throw new UnsupportedOperationException("This service cannot compose a published OpenAPI photograph");
    }

    /**
     * Captures an ephemeral local document/transport fence inside a scoped prepared or published lifecycle photograph callback outside operational transactions.
     * The owner may use it after that callback to verify captured descriptors under a short
     * cache read lock, with the original admission deadline. It supplies no documents, source
     * revision, readiness or authorization. Close it when the synchronous discovery response
     * finishes; it must reject later use, other threads and known invalidation/transport changes.
     */
    default BulkLifecycleDocumentFence captureBulkLifecycleDocumentFence() {
        throw new UnsupportedOperationException(
                "This OpenApiDocumentService cannot fence a captured lifecycle descriptor after composition");
    }

    interface BulkLifecycleDocumentFence extends AutoCloseable {
        /** Validates before and after short verification work; never run a response builder here. */
        <T> T read(Supplier<T> verification);

        @Override
        void close();
    }

    /** Whether strict refreshes use a regenerated source rather than a source-side document cache. */
    default boolean supportsFreshBulkLifecycleComposition() { return false; }

    /** Whether lifecycle snapshots also prove equality with this node's public cached documents. */
    default boolean supportsFreshBulkLifecyclePublicCacheCoherence() { return false; }

    /**
     * Reads an explicit operation's JSON request schema for backend compilation.
     * The default verifies the document binding and declared dialect, resolves supported
     * local references and fails on unsupported/ambiguous structure using this service's source.
     * This does not bind a Java DTO,
     * authorize execution, or replace the UI projection at /schemas/filtered.
     * Call only when the implementation's document source is available (the default uses HTTP).
     */
    default CanonicalRequestSchema requireRequestSchema(CanonicalOperationRef operation) {
        return OpenApiRequestSchemaReader.read(this, operation);
    }

    /**
     * Retorna o hash estrutural canonico para o {@code schemaId} informado.
     *
     * <p>
     * O supplier deve produzir o payload estrutural canonico do schema. O hash nao deve refletir
     * campos puramente documentais ou ruido nao estrutural.
     * </p>
     */
    String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier);

    /**
     * Limpa os caches estruturais mantidos pela implementacao.
     *
     * <p>
     * A limpeza deve abranger tanto documentos OpenAPI quanto hashes estruturais ja calculados.
     * </p>
     */
    void clearCaches();

    /**
     * Resolve o path real dentro de {@code paths}, aceitando equivalencia estrutural entre
     * templates OpenAPI que usam nomes diferentes para parametros posicionais.
     */
    default String resolveDocumentPath(JsonNode pathsNode, String requestedPath) {
        return resolveDocumentPath(pathsNode, requestedPath, null);
    }

    /**
     * Resolve o path real dentro de {@code paths}, aceitando equivalencia estrutural entre
     * templates OpenAPI que usam nomes diferentes para parametros posicionais quando a operacao
     * HTTP tambem e compativel.
     *
     * <p>
     * Matches exatos continuam tendo precedencia. A equivalencia estrutural so e usada quando
     * existe um unico candidato compativel; multiplos candidatos indicam ambiguidade canonica e
     * devem ser corrigidos na publicacao OpenAPI em vez de inferidos por heuristica local.
     * </p>
     */
    default String resolveDocumentPath(JsonNode pathsNode, String requestedPath, String operation) {
        if (pathsNode == null || pathsNode.isMissingNode()) {
            return requestedPath;
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(requestedPath);

        String normalized = normalizeOpenApiPath(requestedPath);
        String normalizedOperation = normalizeOperation(operation);
        candidates.add(normalized);
        if ("/".equals(normalized)) {
            candidates.add("/");
        } else {
            candidates.add(normalized + "/");
        }

        for (String candidate : candidates) {
            if (hasText(candidate) && hasOperation(pathsNode, candidate, normalizedOperation)) {
                return candidate;
            }
        }

        String structurallyEquivalentPath = findStructurallyEquivalentPath(pathsNode, normalized, operation);
        if (hasText(structurallyEquivalentPath)) {
            return structurallyEquivalentPath;
        }

        return normalized;
    }

    default String normalizeOpenApiPath(String path) {
        if (!hasText(path)) {
            return "/";
        }

        String normalized = path.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String findStructurallyEquivalentPath(JsonNode pathsNode, String requestedPath, String operation) {
        String requestedSignature = templatedPathSignature(requestedPath);
        if (!hasText(requestedSignature)) {
            return null;
        }

        String normalizedOperation = normalizeOperation(operation);
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        pathsNode.fieldNames().forEachRemaining(candidatePath -> {
            if (!requestedSignature.equals(templatedPathSignature(candidatePath))) {
                return;
            }
            if (!hasOperation(pathsNode, candidatePath, normalizedOperation)) {
                return;
            }
            matches.add(candidatePath);
        });
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous OpenAPI template path resolution for '" + requestedPath + "'."
            );
        }
        return matches.stream().findFirst().orElse(null);
    }

    private String templatedPathSignature(String path) {
        String normalized = normalizeOpenApiPath(path);
        if (!hasText(normalized)) {
            return null;
        }
        return normalized.replaceAll("\\{[^}/]+}", "{}");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeOperation(String operation) {
        return hasText(operation) ? operation.trim().toLowerCase(Locale.ROOT) : null;
    }

    private static boolean hasOperation(JsonNode pathsNode, String candidate, String normalizedOperation) {
        JsonNode pathNode = pathsNode.path(candidate);
        if (pathNode.isMissingNode()) {
            return false;
        }
        return !hasText(normalizedOperation) || !pathNode.path(normalizedOperation).isMissingNode();
    }
}
