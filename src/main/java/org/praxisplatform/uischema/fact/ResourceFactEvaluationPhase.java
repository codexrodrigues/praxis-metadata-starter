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
    PRE_AUTHORIZE(ResourceFactEvaluationPhase.WIRE_PRE_AUTHORIZE),

    /** Resolved after reading or hydrating an authoritative resource item. */
    POST_READ(ResourceFactEvaluationPhase.WIRE_POST_READ),

    /** Evaluated before mutating resource state during command or write handling. */
    PRE_WRITE(ResourceFactEvaluationPhase.WIRE_PRE_WRITE),

    /** Evaluated after persisting a state transition. */
    POST_WRITE(ResourceFactEvaluationPhase.WIRE_POST_WRITE),

    /** Resolved as part of query/read projection composition. */
    QUERY_PROJECTION(ResourceFactEvaluationPhase.WIRE_QUERY_PROJECTION);

    public static final String WIRE_PRE_AUTHORIZE = "pre_authorize";
    public static final String WIRE_POST_READ = "post_read";
    public static final String WIRE_PRE_WRITE = "pre_write";
    public static final String WIRE_POST_WRITE = "post_write";
    public static final String WIRE_QUERY_PROJECTION = "query_projection";

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
