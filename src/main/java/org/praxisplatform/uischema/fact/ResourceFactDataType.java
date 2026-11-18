package org.praxisplatform.uischema.fact;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical value data-type vocabulary for {@link ResourceFact#dataType()}.
 */
public enum ResourceFactDataType {

    STRING(ResourceFactDataType.WIRE_STRING),
    NUMBER(ResourceFactDataType.WIRE_NUMBER),
    INTEGER(ResourceFactDataType.WIRE_INTEGER),
    DECIMAL(ResourceFactDataType.WIRE_DECIMAL),
    CURRENCY(ResourceFactDataType.WIRE_CURRENCY),
    PERCENTAGE(ResourceFactDataType.WIRE_PERCENTAGE),
    BOOLEAN(ResourceFactDataType.WIRE_BOOLEAN),
    DATE(ResourceFactDataType.WIRE_DATE),
    DATETIME(ResourceFactDataType.WIRE_DATETIME),
    ENUM(ResourceFactDataType.WIRE_ENUM),
    OBJECT(ResourceFactDataType.WIRE_OBJECT),
    ARRAY(ResourceFactDataType.WIRE_ARRAY);

    public static final String WIRE_STRING = "string";
    public static final String WIRE_NUMBER = "number";
    public static final String WIRE_INTEGER = "integer";
    public static final String WIRE_DECIMAL = "decimal";
    public static final String WIRE_CURRENCY = "currency";
    public static final String WIRE_PERCENTAGE = "percentage";
    public static final String WIRE_BOOLEAN = "boolean";
    public static final String WIRE_DATE = "date";
    public static final String WIRE_DATETIME = "datetime";
    public static final String WIRE_ENUM = "enum";
    public static final String WIRE_OBJECT = "object";
    public static final String WIRE_ARRAY = "array";

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
