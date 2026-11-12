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

    /** Composes two governance objects when both are present, or returns a defensive copy of the single present side. */
    public static ObjectNode mergeOrCopy(JsonNode ancestor, JsonNode descendant) {
        boolean hasAncestor = ancestor != null && ancestor.isObject();
        boolean hasDescendant = descendant != null && descendant.isObject();
        if (!hasAncestor && !hasDescendant) return null;
        if (!hasAncestor) return ((ObjectNode) descendant).deepCopy();
        if (!hasDescendant) return ((ObjectNode) ancestor).deepCopy();
        return merge(ancestor, descendant);
    }

    /** Evaluates whether the effective governance declaration permits collection export. */
    public static boolean isExportable(JsonNode governance) {
        if (governance == null || !governance.isObject()) return true;
        if (governance.path("exportable").isBoolean() && !governance.path("exportable").booleanValue()) {
            return false;
        }
        for (JsonNode tag : governance.path("complianceTags")) {
            if (tag != null && "NON_EXPORTABLE".equalsIgnoreCase(tag.asText(null))) {
                return false;
            }
        }
        return true;
    }

    private static final List<String> CLASSIFICATION_ORDER =
            List.of("public", "internal", "confidential", "restricted");
    private static final List<String> ANNOTATION_TYPE_ORDER =
            List.of("quality", "retention", "ownership", "llm_visibility", "compliance", "privacy", "security");

    public static ObjectNode merge(JsonNode ancestor, JsonNode descendant) {
        boolean ancestorHeuristic = isHeuristic(ancestor);
        boolean descendantHeuristic = isHeuristic(descendant);
        JsonNode preferred;
        if (ancestorHeuristic != descendantHeuristic) {
            preferred = !ancestorHeuristic ? ancestor : descendant;
        } else if (ancestorHeuristic && confidenceOf(ancestor) > confidenceOf(descendant)) {
            preferred = ancestor;
        } else {
            preferred = descendant;
        }
        JsonNode secondary = preferred == ancestor ? descendant : ancestor;

        ObjectNode result = secondary != null && secondary.isObject()
                ? ((ObjectNode) secondary).deepCopy()
                : ancestor.deepCopy();
        if (preferred != null && preferred.isObject()) {
            preferred.fields().forEachRemaining(entry -> {
                if (!"aiUsage".equals(entry.getKey())
                        && (!entry.getValue().isNull() || !result.hasNonNull(entry.getKey()))) {
                    result.set(entry.getKey(), entry.getValue().deepCopy());
                }
            });
        }

        strongest(result, "classification", ancestor, descendant, CLASSIFICATION_ORDER);

        String aCategory = normalizeCategory(ancestor.path("dataCategory").asText(null));
        String dCategory = normalizeCategory(descendant.path("dataCategory").asText(null));
        if (aCategory != null || dCategory != null) {
            if (!ancestorHeuristic && !descendantHeuristic) {
                if (aCategory != null && dCategory != null && !aCategory.equals(dCategory)) {
                    if ("operational".equals(aCategory)) {
                        result.put("dataCategory", dCategory);
                    } else if ("operational".equals(dCategory)) {
                        result.put("dataCategory", aCategory);
                    } else if (("personal".equals(aCategory) && "sensitive_personal".equals(dCategory))
                            || ("sensitive_personal".equals(aCategory) && "personal".equals(dCategory))) {
                        result.put("dataCategory", "sensitive_personal");
                    } else {
                        throw new IllegalArgumentException("Conflicting domain governance dataCategory on nested field");
                    }
                } else {
                    result.put("dataCategory", aCategory != null ? aCategory : dCategory);
                }
            } else {
                String preferredCategory = normalizeCategory(preferred.path("dataCategory").asText(null));
                String fallbackCategory = normalizeCategory(secondary.path("dataCategory").asText(null));
                result.put("dataCategory", preferredCategory != null ? preferredCategory : fallbackCategory);
            }
        }

        if (ancestorHeuristic != descendantHeuristic || ancestorHeuristic) {
            copyNonNullText(preferred, secondary, result, "annotationType");
            copyNonNullText(preferred, secondary, result, "source");
            if (preferred.path("confidence").isNumber()) {
                result.put("confidence", preferred.path("confidence").doubleValue());
            } else if (secondary.path("confidence").isNumber()) {
                result.put("confidence", secondary.path("confidence").doubleValue());
            }
        } else {
            String mergedAnnotationType = pickHigherOrder(
                    blankToNull(ancestor.path("annotationType").asText(null)),
                    blankToNull(descendant.path("annotationType").asText(null)),
                    ANNOTATION_TYPE_ORDER);
            if (mergedAnnotationType != null) {
                result.put("annotationType", mergedAnnotationType);
            }
            if (ancestor.path("confidence").isNumber() && descendant.path("confidence").isNumber()) {
                result.put("confidence", Math.max(
                        ancestor.path("confidence").doubleValue(),
                        descendant.path("confidence").doubleValue()));
            }
        }

        String nodeKey = blankToNull(result.path("nodeKey").asText(null));
        String annotationType = blankToNull(result.path("annotationType").asText(null));
        String governanceKey = blankToNull(result.path("governanceKey").asText(null));
        if (nodeKey != null && annotationType != null && governanceKey != null
                && governanceKey.startsWith("governance:" + nodeKey + ":")) {
            result.put("governanceKey", "governance:" + nodeKey + ":" + annotationType);
        }

        JsonNode aAi = ancestor.path("aiUsage");
        JsonNode dAi = descendant.path("aiUsage");
        if (aAi.isObject() || dAi.isObject()) {
            ObjectNode ai = result.withObject("/aiUsage");
            JsonNode firstAi = preferred == ancestor ? dAi : aAi;
            JsonNode secondAi = preferred == ancestor ? aAi : dAi;
            if (firstAi.isObject()) {
                firstAi.fields().forEachRemaining(entry -> {
                    if (!entry.getValue().isNull() || !ai.hasNonNull(entry.getKey())) {
                        ai.set(entry.getKey(), entry.getValue().deepCopy());
                    }
                });
            }
            if (secondAi.isObject()) {
                secondAi.fields().forEachRemaining(entry -> {
                    if (!entry.getValue().isNull() || !ai.hasNonNull(entry.getKey())) {
                        ai.set(entry.getKey(), entry.getValue().deepCopy());
                    }
                });
            }
            for (String key : List.of("visibility", "trainingUse", "ruleAuthoring", "reasoningUse")) {
                strongest(ai, key, aAi, dAi,
                        "visibility".equals(key)
                                ? List.of("allow", "summarize_only", "mask", "deny")
                                : List.of("allow", "review_required", "deny"));
            }
            if ((aAi.path("catalogMetadataDiscoverable").isBoolean() && !aAi.path("catalogMetadataDiscoverable").booleanValue())
                    || (dAi.path("catalogMetadataDiscoverable").isBoolean() && !dAi.path("catalogMetadataDiscoverable").booleanValue())) {
                ai.put("catalogMetadataDiscoverable", false);
            }
        }

        Set<String> tags = new LinkedHashSet<>();
        ancestor.path("complianceTags").forEach(tag -> tags.add(tag.asText()));
        descendant.path("complianceTags").forEach(tag -> tags.add(tag.asText()));
        if (!tags.isEmpty()) {
            var array = result.putArray("complianceTags");
            tags.stream().sorted().forEach(array::add);
        }
        if (Boolean.FALSE.equals(ancestor.path("exportable").isBoolean() ? ancestor.path("exportable").booleanValue() : null)
                || Boolean.FALSE.equals(descendant.path("exportable").isBoolean() ? descendant.path("exportable").booleanValue() : null)
                || tags.stream().anyMatch("NON_EXPORTABLE"::equalsIgnoreCase)) {
            result.put("exportable", false);
        }
        return result;
    }

    private static boolean isHeuristic(JsonNode node) {
        return node != null && "dto-field-heuristic".equalsIgnoreCase(node.path("source").asText(null));
    }

    private static double confidenceOf(JsonNode node) {
        return node != null && node.path("confidence").isNumber() ? node.path("confidence").doubleValue() : 0.0d;
    }

    private static String normalizeCategory(String value) {
        String normalized = blankToNull(value);
        return normalized == null || "none".equalsIgnoreCase(normalized) || "undefined".equalsIgnoreCase(normalized)
                ? null
                : normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void copyNonNullText(JsonNode preferred, JsonNode secondary, ObjectNode target, String key) {
        String value = blankToNull(preferred.path(key).asText(null));
        if (value == null) {
            value = blankToNull(secondary.path(key).asText(null));
        }
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String pickHigherOrder(String first, String second, List<String> order) {
        if (first == null) return second;
        if (second == null) return first;
        int firstIdx = order.indexOf(first);
        int secondIdx = order.indexOf(second);
        if (firstIdx < 0 && secondIdx < 0) return second;
        return firstIdx >= secondIdx ? first : second;
    }

    private static void strongest(ObjectNode target, String key, JsonNode ancestor, JsonNode descendant, List<String> order) {
        String parent = ancestor.path(key).asText(null), child = descendant.path(key).asText(null);
        if (parent == null && child == null) return;
        if (parent != null && !order.contains(parent) || child != null && !order.contains(child))
            throw new IllegalArgumentException("Unknown domain governance " + key + " value");
        target.put(key, (parent == null ? -1 : order.indexOf(parent)) >= (child == null ? -1 : order.indexOf(child)) ? parent : child);
    }
}
