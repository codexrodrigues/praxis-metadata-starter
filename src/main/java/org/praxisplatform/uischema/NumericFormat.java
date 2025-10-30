package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Formatos numéricos e temporais para orientação da camada de UI.
 * <p>
 * Útil para renderizadores que precisam aplicar máscaras, localizações ou
 * componentes específicos (ex.: currency, percent, date-time).
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
