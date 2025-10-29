package org.praxisplatform.uischema;

/**
 * Enum que define os tipos de dados (`TYPE`) disponíveis para configuração dos campos de formulário.
 */
public enum FieldDataType {
    /**
     * Represents a text data type.
     */
    TEXT("text"),

    /**
     * Represents a numeric data type.
     */
    NUMBER("number"),

    /**
     * Represents an email address data type.
     */
    EMAIL("email"),

    /**
     * Represents a date data type.
     */
    DATE("date"),

    /**
     * Represents a password data type.
     */
    PASSWORD("password"),

    /**
     * Represents a file data type.
     */
    FILE("file"),

    /**
     * Represents a URL data type.
     */
    URL("url"),

    /**
     * Represents a boolean data type.
     */
    BOOLEAN("boolean"),

    /**
     * Represents a JSON object data type.
     */
    JSON("json");

    private final String value;

    FieldDataType(String value) {
        this.value = value;
    }

    /**
     * Gets the string value of the data type.
     * @return The string value.
     */
    public String getValue() {
        return value;
    }

    /**
     * Converts a string value to the corresponding FieldDataType Enum.
     * The comparison is case-insensitive.
     *
     * @param value The string value to convert.
     * @return The matching FieldDataType.
     * @throws IllegalArgumentException if the value does not match any known data type.
     */
    public static FieldDataType fromValue(String value) {
        for (FieldDataType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Tipo de dado desconhecido: " + value);
    }
}
