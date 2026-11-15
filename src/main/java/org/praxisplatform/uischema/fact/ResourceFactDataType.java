package org.praxisplatform.uischema.fact;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical value data-type vocabulary for {@link ResourceFact#dataType()}.
 */
public enum ResourceFactDataType {

    STRING("string"),
    NUMBER("number"),
    INTEGER("integer"),
    DECIMAL("decimal"),
    CURRENCY("currency"),
    PERCENTAGE("percentage"),
    BOOLEAN("boolean"),
    DATE("date"),
    DATETIME("datetime"),
    ENUM("enum"),
    OBJECT("object"),
    ARRAY("array");

    private static final Set<String> ALLOWED_WIRE_VALUES =
            ResourceFactVocabulary.allowedWireValues(values(), ResourceFactDataType::wireValue);
    private static final Map<String, ResourceFactDataType> BY_WIRE_VALUE =
            ResourceFactVocabulary.indexByWireValue(values(), ResourceFactDataType::wireValue);

    private final String wireValue;

    ResourceFactDataType(String wireValue) {
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

    public static Optional<ResourceFactDataType> fromWireValue(String value) {
        return ResourceFactVocabulary.fromWireValue(BY_WIRE_VALUE, value);
    }
}
