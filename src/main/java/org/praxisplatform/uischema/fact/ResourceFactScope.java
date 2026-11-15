package org.praxisplatform.uischema.fact;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cardinality scope vocabulary accepted by {@link ResourceFact#scope()} and
 * {@link ResourceFactDescriptor#scope()}.
 */
public enum ResourceFactScope {

    /** Fact scoped to a single resource record/item. */
    ITEM("item"),

    /** Fact scoped to a resource collection, dataset, or aggregate query. */
    COLLECTION("collection"),

    /** Fact scoped to the resolved tenant context. */
    TENANT("tenant"),

    /** Fact scoped to the authenticated principal context. */
    PRINCIPAL("principal");

    private static final Set<String> ALLOWED_WIRE_VALUES =
            ResourceFactVocabulary.allowedWireValues(values(), ResourceFactScope::wireValue);
    private static final Map<String, ResourceFactScope> BY_WIRE_VALUE =
            ResourceFactVocabulary.indexByWireValue(values(), ResourceFactScope::wireValue);

    private final String wireValue;

    ResourceFactScope(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Set<String> allowedWireValues() {
        return ALLOWED_WIRE_VALUES;
    }

    public static boolean isValidWireValue(String value) {
        return ResourceFactVocabulary.isValidWireValue(ALLOWED_WIRE_VALUES, value);
    }

    public static Optional<ResourceFactScope> fromWireValue(String value) {
        return ResourceFactVocabulary.fromWireValue(BY_WIRE_VALUE, value);
    }
}
