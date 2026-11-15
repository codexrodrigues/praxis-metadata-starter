package org.praxisplatform.uischema.fact;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fail-closed missing-value behavior vocabulary accepted by {@link ResourceFact#missingValuePolicy()}
 * and {@link ResourceFactDescriptor#missingValuePolicy()}.
 */
public enum ResourceFactMissingValuePolicy {

    /** Treat unresolved facts as non-satisfiable in fail-closed governed decisions. */
    FAIL_CLOSED("fail_closed"),

    /** Explicitly deny the dependent decision when the fact cannot be resolved. */
    DENY("deny"),

    /** Surface an explicit null value without fabricating defaults or allowing silent fallback. */
    EXPLICIT_NULL("explicit_null"),

    /** Fail execution with an explicit resolution error when the fact value is unavailable. */
    ERROR("error");

    private static final Set<String> ALLOWED_WIRE_VALUES =
            ResourceFactVocabulary.allowedWireValues(values(), ResourceFactMissingValuePolicy::wireValue);
    private static final Map<String, ResourceFactMissingValuePolicy> BY_WIRE_VALUE =
            ResourceFactVocabulary.indexByWireValue(values(), ResourceFactMissingValuePolicy::wireValue);

    private final String wireValue;

    ResourceFactMissingValuePolicy(String wireValue) {
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

    public static Optional<ResourceFactMissingValuePolicy> fromWireValue(String value) {
        return ResourceFactVocabulary.fromWireValue(BY_WIRE_VALUE, value);
    }
}
