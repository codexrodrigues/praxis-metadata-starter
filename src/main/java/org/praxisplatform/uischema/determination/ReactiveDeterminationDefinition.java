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
    /** Explicit operation IDs keep CRUD bindings independent of routes and naming heuristics. */
    public static CrudBuilder forCrudResource(String id, String createOperationId, String updateOperationId, String determinationOperationId) {
        return new CrudBuilder(id, createOperationId, updateOperationId, determinationOperationId);
    }

    public static final class CrudBuilder {
        private final String id, operationId;
        private final List<ReactiveDeterminationScope> scopes;
        private ReactiveDeterminationTriggerMode trigger = ReactiveDeterminationTriggerMode.ON_CHANGE;
        private final java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        private final java.util.ArrayList<ReactiveDeterminationInputBinding> inputs = new java.util.ArrayList<>();
        private final java.util.ArrayList<ReactiveDeterminationOutputBinding> outputs = new java.util.ArrayList<>();
        private ReactiveDeterminationProvenance provenance;
        private CrudBuilder(String id, String createId, String updateId, String operationId) {
            for (String value : new String[]{id, createId, updateId, operationId})
                if (value == null || value.isBlank()) throw new IllegalArgumentException("Determination requires explicit identifiers");
            if (createId.equals(updateId)) throw new IllegalArgumentException("CRUD schema operation IDs must differ");
            this.id = id; this.operationId = operationId;
            scopes = List.of(new ReactiveDeterminationScope(createId, ReactiveDeterminationFormMode.CREATE),
                    new ReactiveDeterminationScope(updateId, ReactiveDeterminationFormMode.EDIT));
        }
        public CrudBuilder triggerMode(ReactiveDeterminationTriggerMode value) { trigger = java.util.Objects.requireNonNull(value); return this; }
        public CrudBuilder source(String path) { sources.add(pointer(path)); return this; }
        public CrudBuilder input(String fieldPath, String requestPath) {
            inputs.add(new ReactiveDeterminationInputBinding(pointer(fieldPath), pointer(requestPath))); return this;
        }
        public CrudBuilder output(String responsePath, String fieldPath) {
            outputs.add(new ReactiveDeterminationOutputBinding(pointer(responsePath), pointer(fieldPath))); return this;
        }
        public CrudBuilder provenance(ReactiveDeterminationProvenance value) { provenance = java.util.Objects.requireNonNull(value); return this; }
        public ReactiveDeterminationDefinition build() {
            if (trigger.requiresSourcePaths() && sources.isEmpty()) throw new IllegalArgumentException("Determination requires source paths");
            if (outputs.isEmpty() || provenance == null) throw new IllegalArgumentException("Determination requires outputs and provenance");
            return new ReactiveDeterminationDefinition(id, scopes, operationId, trigger, List.copyOf(sources),
                    List.copyOf(inputs), List.copyOf(outputs), provenance);
        }
        private String pointer(String path) {
            if (path == null || !path.startsWith("/") || path.length() < 2) throw new IllegalArgumentException("Expected a JSON Pointer");
            return path;
        }
    }
}
