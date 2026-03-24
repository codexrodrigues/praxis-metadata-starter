package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Formatos semanticos para orientacao de renderizacao numerica e temporal.
 *
 * <p>
 * O formato complementa {@code type} e {@code controlType}, indicando como um valor deve ser
 * apresentado ou interpretado pela UI, por exemplo como moeda, percentual, data ou duracao.
 * </p>
 *
 * @since 1.0.0
 */
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

    /**
     * Identificador de formato para consumo pela UI.
     *
     * @return valor semântico (ex.: {@code currency})
     */
    @JsonValue
    public String getValue() { return value; }
}
