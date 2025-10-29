package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Interface que define as configurações de validação para campos de formulário UI em APIs.
 */
public enum ValidationProperties {
    REQUIRED("required"),
    MIN_LENGTH("minLength"),
    MAX_LENGTH("maxLength"),
    MIN("min"),
    MAX("max"),
    PATTERN("pattern"),
    REQUIRED_MESSAGE("requiredMessage"),
    MIN_LENGTH_MESSAGE("minLengthMessage"),
    MAX_LENGTH_MESSAGE("maxLengthMessage"),
    PATTERN_MESSAGE("patternMessage"),
    RANGE_MESSAGE("rangeMessage"),
    CUSTOM_VALIDATOR("customValidator"),
    ASYNC_VALIDATOR("asyncValidator"),
    CONDITIONAL_REQUIRED("conditionalRequired"),
    MIN_WORDS("minWords"),
    ALLOWED_FILE_TYPES("allowedFileTypes"),
    FILE_TYPE_MESSAGE("fileTypeMessage"),
    MAX_FILE_SIZE("maxFileSize");

    // --- Store the string value ---
    private final String value;

    ValidationProperties(String value) {
        this.value = value;
    }

    // --- Getter for the string value ---
    @JsonValue
    public String getValue() {
        return value;
    }

    // --- Method to get Enum from string value ---
    public static ValidationProperties fromValue(String value) {
        for (ValidationProperties prop : values()) {
            if (prop.value.equalsIgnoreCase(value)) {
                return prop;
            }
        }
        // Consistent with FieldDataType and FieldConfigProperties
        throw new IllegalArgumentException("Unknown ValidationProperties value: " + value);
    }
}
