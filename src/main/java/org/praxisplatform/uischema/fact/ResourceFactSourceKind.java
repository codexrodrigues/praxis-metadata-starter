package org.praxisplatform.uischema.fact;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical provenance kind vocabulary for {@link ResourceFact#sourceKind()}.
 */
public enum ResourceFactSourceKind {

    /** Server-computed derived value or domain indicator. */
    COMPUTED(ResourceFactSourceKind.WIRE_COMPUTED),

    /** Backed directly by a DTO/resource field whose name matches {@link ResourceFact#key()}. */
    FIELD(ResourceFactSourceKind.WIRE_FIELD),

    /** Backed directly by a readable bean property whose name matches {@link ResourceFact#key()}. */
    PROPERTY(ResourceFactSourceKind.WIRE_PROPERTY),

    /** Backed directly by a projected column/property whose name matches {@link ResourceFact#key()}. */
    COLUMN(ResourceFactSourceKind.WIRE_COLUMN),

    /** Computed via a declarative expression. */
    EXPRESSION(ResourceFactSourceKind.WIRE_EXPRESSION),

    /** Aggregated metric over collection items. */
    AGGREGATE(ResourceFactSourceKind.WIRE_AGGREGATE),

    /** Derived from server request/tenant runtime context. */
    CONTEXT(ResourceFactSourceKind.WIRE_CONTEXT),

    /** Derived from a governed grant/capability evaluation. */
    GRANT(ResourceFactSourceKind.WIRE_GRANT);

    public static final String WIRE_COMPUTED = "computed";
    public static final String WIRE_FIELD = "field";
    public static final String WIRE_PROPERTY = "property";
    public static final String WIRE_COLUMN = "column";
    public static final String WIRE_EXPRESSION = "expression";
    public static final String WIRE_AGGREGATE = "aggregate";
    public static final String WIRE_CONTEXT = "context";
    public static final String WIRE_GRANT = "grant";

    private static final Set<String> ALLOWED_WIRE_VALUES =
            ResourceFactVocabulary.allowedWireValues(values(), ResourceFactSourceKind::wireValue);
    private static final Map<String, ResourceFactSourceKind> BY_WIRE_VALUE =
            ResourceFactVocabulary.indexByWireValue(values(), ResourceFactSourceKind::wireValue);

    private final String wireValue;

    ResourceFactSourceKind(String wireValue) {
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

    public static Optional<ResourceFactSourceKind> fromWireValue(String value) {
        return ResourceFactVocabulary.fromWireValue(BY_WIRE_VALUE, value);
    }
}
