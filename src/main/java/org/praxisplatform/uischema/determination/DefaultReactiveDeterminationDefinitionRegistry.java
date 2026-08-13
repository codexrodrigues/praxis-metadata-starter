package org.praxisplatform.uischema.determination;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Implementacao default que captura um snapshot tenant-neutral no bootstrap. */
public final class DefaultReactiveDeterminationDefinitionRegistry
        implements ReactiveDeterminationDefinitionRegistry {

    private final Map<String, List<ReactiveDeterminationDefinition>> definitionsBySchemaOperationId;

    public DefaultReactiveDeterminationDefinitionRegistry(
            Collection<ReactiveDeterminationDefinitionProvider> providers
    ) {
        this.definitionsBySchemaOperationId = snapshot(providers == null ? List.of() : providers);
    }

    @Override
    public List<ReactiveDeterminationDefinition> findBySchemaOperationId(String schemaOperationId) {
        if (schemaOperationId == null || schemaOperationId.isBlank()) {
            return List.of();
        }
        return definitionsBySchemaOperationId.getOrDefault(schemaOperationId, List.of());
    }

    private Map<String, List<ReactiveDeterminationDefinition>> snapshot(
            Collection<ReactiveDeterminationDefinitionProvider> providers
    ) {
        List<ReactiveDeterminationDefinition> definitions = new ArrayList<>();
        for (ReactiveDeterminationDefinitionProvider provider : providers) {
            if (provider == null) {
                throw new IllegalArgumentException("Reactive determination provider must not be null.");
            }
            Collection<ReactiveDeterminationDefinition> supplied = provider.definitions();
            if (supplied != null) {
                for (ReactiveDeterminationDefinition definition : supplied) {
                    definitions.add(immutableCopy(Objects.requireNonNull(
                            definition,
                            "Reactive determination definition must not be null."
                    )));
                }
            }
        }
        definitions.sort(Comparator.comparing(
                ReactiveDeterminationDefinition::id,
                Comparator.nullsFirst(String::compareTo)
        ));

        Set<String> ids = new HashSet<>();
        Map<String, List<ReactiveDeterminationDefinition>> index = new LinkedHashMap<>();
        for (ReactiveDeterminationDefinition definition : definitions) {
            String id = requireText(definition.id(), "Reactive determination id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate reactive determination id: " + id);
            }
            if (definition.scopes() == null || definition.scopes().isEmpty()) {
                throw new IllegalArgumentException("Reactive determination '" + id + "' must declare at least one scope.");
            }
            Set<String> scopedOperations = new HashSet<>();
            for (ReactiveDeterminationScope scope : definition.scopes()) {
                if (scope == null || scope.formMode() == null) {
                    throw new IllegalArgumentException("Reactive determination '" + id + "' has an invalid scope.");
                }
                String operationId = requireText(scope.schemaOperationId(), "Schema operationId");
                if (!scopedOperations.add(operationId)) {
                    throw new IllegalArgumentException(
                            "Reactive determination '" + id + "' repeats scope operationId '" + operationId + "'."
                    );
                }
                index.computeIfAbsent(operationId, ignored -> new ArrayList<>()).add(definition);
            }
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(index);
    }

    private ReactiveDeterminationDefinition immutableCopy(ReactiveDeterminationDefinition definition) {
        return new ReactiveDeterminationDefinition(
                definition.id(),
                definition.scopes() == null ? null : List.copyOf(definition.scopes()),
                definition.operationId(),
                definition.triggerMode(),
                definition.sourcePaths() == null ? null : List.copyOf(definition.sourcePaths()),
                definition.inputs() == null ? null : List.copyOf(definition.inputs()),
                definition.outputs() == null ? null : List.copyOf(definition.outputs()),
                definition.provenance()
        );
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank.");
        }
        return value;
    }
}
