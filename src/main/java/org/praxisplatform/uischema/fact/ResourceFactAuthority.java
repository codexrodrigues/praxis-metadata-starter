package org.praxisplatform.uischema.fact;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative origin vocabulary accepted by {@link ResourceFact#authority()} and
 * {@link ResourceFactDescriptor#authority()}.
 */
public enum ResourceFactAuthority {

    /** Deterministically computed on the server from domain state and parameters. */
    SERVER_COMPUTED("server_computed"),

    /** Backed directly by authoritative persisted backend state. */
    PERSISTED_STATE("persisted_state"),

    /** Derived from verified authenticated principal/tenant claims on the server. */
    AUTHENTICATED_CONTEXT("authenticated_context"),

    /** Resolved from a governed entitlement or grant verified on the server. */
    GOVERNED_GRANT("governed_grant");

    private static final Set<String> ALLOWED_WIRE_VALUES =
            ResourceFactVocabulary.allowedWireValues(values(), ResourceFactAuthority::wireValue);
    private static final Map<String, ResourceFactAuthority> BY_WIRE_VALUE =
            ResourceFactVocabulary.indexByWireValue(values(), ResourceFactAuthority::wireValue);

    private final String wireValue;

    ResourceFactAuthority(String wireValue) {
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

    public static Optional<ResourceFactAuthority> fromWireValue(String value) {
        return ResourceFactVocabulary.fromWireValue(BY_WIRE_VALUE, value);
    }
}
