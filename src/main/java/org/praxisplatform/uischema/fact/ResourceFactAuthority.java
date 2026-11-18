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
    SERVER_COMPUTED(ResourceFactAuthority.WIRE_SERVER_COMPUTED),

    /** Backed directly by authoritative persisted backend state. */
    PERSISTED_STATE(ResourceFactAuthority.WIRE_PERSISTED_STATE),

    /** Derived from verified authenticated principal/tenant claims on the server. */
    AUTHENTICATED_CONTEXT(ResourceFactAuthority.WIRE_AUTHENTICATED_CONTEXT),

    /** Resolved from a governed entitlement or grant verified on the server. */
    GOVERNED_GRANT(ResourceFactAuthority.WIRE_GOVERNED_GRANT);

    public static final String WIRE_SERVER_COMPUTED = "server_computed";
    public static final String WIRE_PERSISTED_STATE = "persisted_state";
    public static final String WIRE_AUTHENTICATED_CONTEXT = "authenticated_context";
    public static final String WIRE_GOVERNED_GRANT = "governed_grant";

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
