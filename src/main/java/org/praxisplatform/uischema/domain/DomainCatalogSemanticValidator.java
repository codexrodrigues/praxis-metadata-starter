package org.praxisplatform.uischema.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Cross-reference and determination invariants not expressible by the catalog JSON Schema. */
final class DomainCatalogSemanticValidator {
    private DomainCatalogSemanticValidator() { }

    static DomainCatalogResponse validate(DomainCatalogResponse catalog) {
        Set<String> contexts = new HashSet<>();
        catalog.contexts().forEach(context -> require(contexts.add(context.contextKey()), "Duplicate context"));
        Map<String, DomainCatalogResponse.DomainNodeItem> nodes = new HashMap<>();
        catalog.nodes().forEach(node -> {
            require(contexts.contains(node.contextKey()), "Unknown node context: " + node.nodeKey());
            require(nodes.putIfAbsent(node.nodeKey(), node) == null, "Duplicate node: " + node.nodeKey());
        });
        Set<String> evidence = new HashSet<>();
        catalog.evidence().forEach(item -> require(evidence.add(item.evidenceKey()), "Duplicate evidence"));
        catalog.nodes().forEach(node -> require(evidence.containsAll(node.sourceEvidenceKeys()), "Unknown node evidence"));
        Set<String> edgeKeys = new HashSet<>();
        catalog.edges().forEach(edge -> {
            require(edgeKeys.add(edge.edgeKey()), "Duplicate edge");
            require(nodes.containsKey(edge.sourceNodeKey()) && nodes.containsKey(edge.targetNodeKey()), "Dangling edge: " + edge.edgeKey());
            require(evidence.containsAll(edge.evidenceKeys()), "Unknown edge evidence");
            var source = nodes.get(edge.sourceNodeKey());
            var target = nodes.get(edge.targetNodeKey());
            if ("uses_concept".equals(edge.edgeType()))
                require("concept".equals(target.nodeType()), "uses_concept endpoint must be a concept");
            if ("depends_on".equals(edge.edgeType()) && "reactive-determination".equals(source.source()))
                require("operation".equals(source.nodeType()) && "field".equals(target.nodeType()),
                        "Determination dependency endpoint must be a field");
            if ("governed_by".equals(edge.edgeType()) && "reactive-determination".equals(target.source()))
                require(Set.of("concept", "resource", "field").contains(source.nodeType()), "Invalid determination owner");
            if (Set.of("has_state", "allowed_in_state").contains(edge.edgeType())) {
                require("state".equals(nodes.get(edge.targetNodeKey()).nodeType()), "Transition endpoint must be a state");
                if ("has_state".equals(edge.edgeType())) require(Set.of("concept", "resource").contains(source.nodeType()), "State owner must be a resource");
            }
        });
        Set<String> bindingKeys = new HashSet<>();
        catalog.bindings().forEach(binding -> {
            require(bindingKeys.add(binding.bindingKey()), "Duplicate binding");
            require(nodes.containsKey(binding.nodeKey()), "Dangling binding");
            require(evidence.containsAll(binding.evidenceKeys()), "Unknown binding evidence");
        });
        catalog.aliases().forEach(alias -> require(nodes.containsKey(alias.nodeKey()), "Dangling alias"));
        catalog.governance().forEach(item -> require(nodes.containsKey(item.nodeKey()), "Dangling governance"));
        nodes.values().stream().filter(node -> "reactive-determination".equals(node.source())).forEach(node -> {
            require("operation".equals(node.nodeType()), "Determination must be an operation");
            @SuppressWarnings("unchecked") var compiled = (Map<String, Object>) node.metadata().get("reactiveDetermination");
            require(compiled != null, "Missing compiled determination");
            var bindings = catalog.bindings().stream().filter(binding -> node.nodeKey().equals(binding.nodeKey())).toList();
            require(bindings.size() == 2, "Determination requires two bindings");
            var api = bindings.stream().filter(binding -> "api_operation".equals(binding.bindingType())).toList();
            var form = bindings.stream().filter(binding -> "form_config".equals(binding.bindingType())).toList();
            require(api.size() == 1 && form.size() == 1, "Determination binding types differ");
            require(Objects.equals(compiled.get("capability"), api.getFirst().target()), "Capability binding differs from compiler");
            require(Objects.equals(compiled, form.getFirst().target().get("configuration")), "Form binding differs from compiler");
            require(form.getFirst().schemaLinks().size() == 1, "Determination requires its exact form schema");
        });
        return catalog;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
