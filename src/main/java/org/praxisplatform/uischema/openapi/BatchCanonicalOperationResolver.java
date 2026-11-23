package org.praxisplatform.uischema.openapi;

import org.springframework.web.method.HandlerMethod;

import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Optional batch-resolution extension of {@link CanonicalOperationResolver}.
 *
 * <p>
 * Introducing these operations as interface methods allows Spring AOP proxies (both CGLIB and JDK
 * dynamic proxies) to expose batch MVC and scoped bulk resolution without callers downcasting to
 * {@link OpenApiCanonicalOperationResolver} or falling back to per-operation scans when beans are
 * proxied.
 * </p>
 *
 * <p>
 * <strong>Trust boundary:</strong> {@link #resolveMvcOperationsByIds(Collection)} inspects Spring
 * MVC handler mappings only and intentionally does <em>not</em> inspect OpenAPI documents across
 * groups. Callers enforcing cross-group bulk identity uniqueness must use
 * {@link #resolveByOperationIdsForScope(Collection, Predicate)} or
 * {@link #requireResourceOperations(String, java.util.List, CanonicalOpenApiGroupSnapshot)}.
 * </p>
 */
public interface BatchCanonicalOperationResolver extends CanonicalOperationResolver {

    /** Canonical operation reference paired with its resolved Spring MVC {@link HandlerMethod}. */
    record ResolvedMvcOperation(CanonicalOperationRef operation, HandlerMethod handler) { }

    /**
     * Resolves a batch of {@code operationIds} from Spring MVC handler mappings only, validating
     * incomplete bulk declarations and rejecting duplicate effective mappings without inspecting
     * OpenAPI documents across groups.
     *
     * <p>
     * This method does not verify cross-group OpenAPI collisions and must not replace strict
     * bulk publication verification.
     * </p>
     *
     * @param operationIds operation IDs to resolve
     * @return unmodifiable map keyed by resolved {@code operationId} in requested order
     * @throws IllegalStateException when a bulk operation binding is incomplete/ambiguous or an
     *                               effective {@code operationId} maps to multiple handlers
     */
    Map<String, ResolvedMvcOperation> resolveMvcOperationsByIds(Collection<String> operationIds);

    /**
     * Resolves a batch of {@code operationIds} from Spring MVC handler mappings first, applies
     * {@code scopeFilter} before inspecting OpenAPI documents, and verifies any in-scope declared
     * bulk operations against published OpenAPI documents under a single
     * {@link OpenApiDocumentService#withSchemaCacheReadLock(java.util.function.Supplier)} fence.
     *
     * @param operationIds operation IDs to resolve
     * @param scopeFilter  optional filter applied before bulk OpenAPI collision verification; {@code null} includes all matched IDs
     * @return unmodifiable map of in-scope {@link CanonicalOperationRef} entries keyed by {@code operationId}
     * @throws IllegalStateException when bindings are incomplete/ambiguous, duplicate, tampered, or collide across groups
     */
    Map<String, CanonicalOperationRef> resolveByOperationIdsForScope(
            Collection<String> operationIds,
            Predicate<CanonicalOperationRef> scopeFilter
    );
}
