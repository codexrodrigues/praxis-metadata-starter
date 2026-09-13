package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * An immutable, structural change to one root-level resource field.
 *
 * <p>The {@code field} is an exact literal key. It is never interpreted as a JSON Pointer,
 * JSONPath, or nested-property expression. {@link BulkFieldChanges} checks structural allowlists;
 * authorization and domain validation remain mandatory in the execution layer.</p>
 */
public final class BulkFieldChange {

    private final String field;
    private final BulkChangeOperator operator;
    private final JsonNode value;

    public BulkFieldChange(String field, BulkChangeOperator operator, JsonNode value) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Bulk field change field must not be blank.");
        }

        this.field = field;
        this.operator = Objects.requireNonNull(operator, "Bulk field change operator must not be null.");
        validateValue(operator, value);
        if (value != null) BulkCanonicalJson.preflight(value);
        this.value = copyOf(value);
    }

    public static BulkFieldChange set(String field, JsonNode value) {
        return new BulkFieldChange(field, BulkChangeOperator.SET, value);
    }

    public static BulkFieldChange clear(String field) {
        return new BulkFieldChange(field, BulkChangeOperator.CLEAR, null);
    }

    @JsonProperty("field")
    public String field() {
        return field;
    }

    @JsonProperty("operator")
    public BulkChangeOperator operator() {
        return operator;
    }

    /**
     * Returns a defensive copy so callers cannot mutate this change after validation.
     */
    @JsonProperty("value")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public JsonNode value() {
        return copyOf(value);
    }

    private static void validateValue(BulkChangeOperator operator, JsonNode value) {
        if (operator == BulkChangeOperator.SET
                && (value == null || value.isNull() || value.isMissingNode())) {
            throw new IllegalArgumentException("SET bulk field changes require a non-null JSON value.");
        }

        if (operator == BulkChangeOperator.CLEAR && value != null) {
            throw new IllegalArgumentException("CLEAR bulk field changes must omit value.");
        }
    }

    private static JsonNode copyOf(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BulkFieldChange that)) {
            return false;
        }
        return field.equals(that.field)
                && operator == that.operator
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, operator, value);
    }

    @Override
    public String toString() {
        return "BulkFieldChange[operator=" + operator + "]";
    }
}
