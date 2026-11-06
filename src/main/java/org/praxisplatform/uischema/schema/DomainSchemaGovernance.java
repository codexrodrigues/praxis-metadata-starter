package org.praxisplatform.uischema.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Restrictive inheritance of declared schema governance when a nested field is projected independently. */
public final class DomainSchemaGovernance {
    public static final String EXTENSION = "x-domain-governance";
    private DomainSchemaGovernance() { }

    /** Does not mutate either source schema; a descendant cannot broaden an ancestor's permission. */
    public static JsonNode inherit(JsonNode parent, JsonNode child) {
        if (child == null || !child.isObject() || parent == null || !parent.path(EXTENSION).isObject()) return child;
        ObjectNode result = child.deepCopy();
        result.set(EXTENSION, merge(parent.path(EXTENSION), child.path(EXTENSION)));
        return result;
    }

    public static ObjectNode merge(JsonNode ancestor, JsonNode descendant) {
        ObjectNode result = ancestor.deepCopy();
        if (descendant.isObject()) descendant.fields().forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue().deepCopy()));
        strongest(result, "classification", ancestor, descendant, List.of("public", "internal", "confidential", "restricted"));
        String inheritedCategory = ancestor.path("dataCategory").asText("none");
        String declaredCategory = descendant.path("dataCategory").asText("none");
        if (!"none".equals(inheritedCategory)) {
            if (!"none".equals(declaredCategory) && !inheritedCategory.equals(declaredCategory))
                throw new IllegalArgumentException("Conflicting domain governance dataCategory on nested field");
            result.put("dataCategory", inheritedCategory);
        }
        ObjectNode ai = result.withObject("/aiUsage");
        for (String key : List.of("visibility", "trainingUse", "ruleAuthoring", "reasoningUse"))
            strongest(ai, key, ancestor.path("aiUsage"), descendant.path("aiUsage"),
                    "visibility".equals(key) ? List.of("allow", "summarize_only", "mask", "deny") : List.of("allow", "review_required", "deny"));
        Set<String> tags = new LinkedHashSet<>();
        ancestor.path("complianceTags").forEach(tag -> tags.add(tag.asText()));
        descendant.path("complianceTags").forEach(tag -> tags.add(tag.asText()));
        if (!tags.isEmpty()) { var array = result.putArray("complianceTags"); tags.stream().sorted().forEach(array::add); }
        if (Boolean.FALSE.equals(ancestor.path("exportable").isBoolean() ? ancestor.path("exportable").booleanValue() : null)
                || Boolean.FALSE.equals(descendant.path("exportable").isBoolean() ? descendant.path("exportable").booleanValue() : null)
                || tags.stream().anyMatch("NON_EXPORTABLE"::equalsIgnoreCase)) result.put("exportable", false);
        return result;
    }

    private static void strongest(ObjectNode target, String key, JsonNode ancestor, JsonNode descendant, List<String> order) {
        String parent = ancestor.path(key).asText(null), child = descendant.path(key).asText(null);
        if (parent == null && child == null) return;
        if (parent != null && !order.contains(parent) || child != null && !order.contains(child))
            throw new IllegalArgumentException("Unknown domain governance " + key + " value");
        target.put(key, (parent == null ? -1 : order.indexOf(parent)) >= (child == null ? -1 : order.indexOf(child)) ? parent : child);
    }
}
