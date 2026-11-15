package org.praxisplatform.uischema.fact;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lifecycle evaluation phase vocabulary accepted by {@link ResourceFact#evaluationPhase()} and
 * {@link ResourceFactDescriptor#evaluationPhase()}.
 */
public enum ResourceFactEvaluationPhase {

    /** Evaluated prior to authorization policy checks. */
    PRE_AUTHORIZE("pre_authorize"),

    /** Resolved after reading or hydrating an authoritative resource item. */
    POST_READ("post_read"),

    /** Evaluated before mutating resource state during command or write handling. */
    PRE_WRITE("pre_write"),

    /** Evaluated after persisting a state transition. */
    POST_WRITE("post_write"),

    /** Resolved as part of query/read projection composition. */
    QUERY_PROJECTION("query_projection");

    private static final Set<String> ALLOWED_WIRE_VALUES =
            ResourceFactVocabulary.allowedWireValues(values(), ResourceFactEvaluationPhase::wireValue);
    private static final Map<String, ResourceFactEvaluationPhase> BY_WIRE_VALUE =
            ResourceFactVocabulary.indexByWireValue(values(), ResourceFactEvaluationPhase::wireValue);

    private final String wireValue;

    ResourceFactEvaluationPhase(String wireValue) {
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

    public static Optional<ResourceFactEvaluationPhase> fromWireValue(String value) {
        return ResourceFactVocabulary.fromWireValue(BY_WIRE_VALUE, value);
    }
}
