package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

public enum NumericFormat {
    INTEGER("integer"),
    DECIMAL("decimal"),
    CURRENCY("currency"),
    SCIENTIFIC("scientific"),
    TIME("time"),
    DATE("date"),
    DATE_TIME("date-time"),
    DURATION("duration"),
    NUMBER("number"),
    FRACTION("fraction"),
    PERCENT("percent");

    private final String value;
    NumericFormat(String value) { this.value = value; }
    @JsonValue
    public String getValue() { return value; }
}
