package org.praxisplatform.uischema.determination;

import java.util.List;

/**
 * Declaracao estrutural host-neutral de uma determinacao reativa.
 *
 * <p>A definicao referencia somente operationIds e bindings tipados. O path HTTP publicado e
 * sempre derivado do {@code CanonicalOperationResolver}; providers nao podem autorar URLs,
 * headers, scripts, expressoes ou payloads arbitrarios.</p>
 */
public record ReactiveDeterminationDefinition(
        String id,
        List<ReactiveDeterminationScope> scopes,
        String operationId,
        ReactiveDeterminationTriggerMode triggerMode,
        List<String> sourcePaths,
        List<ReactiveDeterminationInputBinding> inputs,
        List<ReactiveDeterminationOutputBinding> outputs,
        ReactiveDeterminationProvenance provenance
) {
}
