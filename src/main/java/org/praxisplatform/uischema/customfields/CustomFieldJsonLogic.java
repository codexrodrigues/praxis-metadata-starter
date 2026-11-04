package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicValidationOptions;

/** One canonical engine and closed root vocabulary for custom-field constraints. */
final class CustomFieldJsonLogic {
    static final PraxisJsonLogicEngine ENGINE = new PraxisJsonLogicEngine();
    private static final com.fasterxml.jackson.databind.ObjectMapper NATIVE_JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final List<String> ROOTS = List.of("value", "customFields", "draft", "entity");
    static final List<String> ROOTS_WITHOUT_ENTITY = List.of("value", "customFields", "draft");
    private static final Set<String> CONTEXTUAL_OPERATORS = ENGINE.listOperatorDescriptors().stream()
            .filter(descriptor -> "contextual".equals(descriptor.purity()))
            .map(org.praxisplatform.rules.jsonlogic.operators.JsonLogicOperatorDescriptor::operator)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private CustomFieldJsonLogic() { }

    @SuppressWarnings("unchecked")
    static Map<String, Object> snapshot(Map<String, Object> rules) {
        return NATIVE_JSON.convertValue(
                PraxisFactTreeBuilder.toObjectNode(rules), Map.class);
    }

    static void validateDefinition(Map<String, Object> rules) {
        if (rules.isEmpty()) return;
        if (!Set.of("jsonLogic", "message").containsAll(rules.keySet()) || !rules.containsKey("jsonLogic"))
            throw new IllegalArgumentException("validationRules requires jsonLogic and an optional message");
        if (rules.containsKey("message") && (!(rules.get("message") instanceof String message) || message.isBlank()))
            throw new IllegalArgumentException("validationRules.message must be non-blank text");
        JsonNode expression = expression(rules);
        ENGINE.validate(expression, new JsonLogicValidationOptions(ROOTS, null, false, null, null, true));
        requireContextFree(expression);
    }

    private static void requireContextFree(JsonNode node) {
        var pending = new ArrayDeque<JsonNode>();
        pending.push(node);
        while (!pending.isEmpty()) {
            JsonNode current = pending.pop();
            if (current.isObject() && current.size() == 1) {
                String operator = current.fieldNames().next();
                if (CONTEXTUAL_OPERATORS.contains(operator))
                    throw new IllegalArgumentException("Custom-field JSON Logic has no temporal context for operator: " + operator);
            }
            if (current.isContainerNode()) current.forEach(pending::push);
        }
    }

    static JsonNode expression(Map<String, Object> rules) {
        return PraxisFactTreeBuilder.toJsonNode(rules.get("jsonLogic"));
    }

    static boolean requiresEntity(JsonNode expression) {
        return requiresRoot(expression, "entity");
    }

    static boolean requiresDraft(JsonNode expression) {
        return requiresRoot(expression, "draft");
    }

    static boolean observesOtherFields(JsonNode expression) {
        return requiresDraft(expression) || requiresEntity(expression) || requiresRoot(expression, "customFields");
    }

    private static boolean requiresRoot(JsonNode expression, String root) {
        // The dialect validator parses every admitted var spelling, including nested defaults.
        // Whole-context var observes every root even without an explicit root token.
        return !ENGINE.validateResult(expression,
                new JsonLogicValidationOptions(ROOTS.stream().filter(value -> !value.equals(root)).toList(),
                        null, false, null, null, true)).valid()
                || readsWholeContext(expression);
    }

    private static boolean readsWholeContext(JsonNode expression) {
        record Visit(JsonNode node, boolean iteratorScope) {}
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(expression, false));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            JsonNode node = visit.node();
            if (node.isObject() && node.size() == 1) {
                String operator = node.fieldNames().next();
                JsonNode args = node.get(operator);
                if ("var".equals(operator) && !visit.iteratorScope()) {
                    JsonNode path = args.isArray() ? args.path(0) : args;
                    if (path.isTextual() && path.textValue().isEmpty()) return true;
                }
                if (args.isArray() && Set.of("all", "some", "none", "map", "filter").contains(operator)) {
                    for (int index = 0; index < args.size(); index++)
                        pending.push(new Visit(args.get(index), visit.iteratorScope() || index == 1));
                    continue;
                }
            }
            if (node.isContainerNode()) node.forEach(child -> pending.push(new Visit(child, visit.iteratorScope())));
        }
        return false;
    }

    static ObjectNode context(Map<String, Object> values, Object draft, Object entity) {
        PraxisFactTreeBuilder builder = PraxisFactTreeBuilder.create().put("customFields", values);
        if (draft != null) builder.put("draft", draft);
        if (entity != null) builder.put("entity", entityFacts(entity));
        return builder.build();
    }

    /** Explicit JSON contexts are already projections; beans expose scalars and the admitted JSON container. */
    static ObjectNode entityFacts(Object entity) {
        if (entity instanceof Map<?, ?> || entity instanceof ObjectNode) return PraxisFactTreeBuilder.toObjectNode(entity);
        Object bean = org.hibernate.Hibernate.unproxy(entity);
        ObjectNode facts = PraxisFactTreeBuilder.toScalarObjectNode(bean);
        CustomFieldSupport.property(bean.getClass()).ifPresent(property ->
                facts.set(property.name(), PraxisFactTreeBuilder.toObjectNode(property.read(bean))));
        return PraxisFactTreeBuilder.toObjectNode(facts);
    }
}
