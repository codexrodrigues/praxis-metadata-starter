package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Tamanhos e estratégias de ajuste para ícones/imagens em componentes de UI.
 * <p>
 * Permite desde tamanhos semânticos ({@code sm}, {@code md}, {@code lg}) até
 * modos de ajuste como {@code cover}, {@code contain} e {@code fit-content}.
 * </p>
 *
 * @since 1.0.0
 */
public enum IconSize {
    SMALL("sm"),
    MEDIUM("md"),
    LARGE("lg"),
    XLARGE("xl"),
    XXLARGE("xxl"),
    XXXLARGE("xxxl"),
    DEFAULT("default"),
    AUTO("auto"),
    NONE("none"),
    FULL("full"),
    FIT("fit"),
    FILL("fill"),
    COVER("cover"),
    CONTAIN("contain"),
    STRETCH("stretch"),
    FIT_CONTENT("fit-content"),
    FIT_VIEWPORT("fit-viewport"),
    FIT_SCREEN("fit-screen"),
    FIT_WINDOW("fit-window"),
    FIT_PARENT("fit-parent"),
    INHERIT("inherit");

    private final String value;
    IconSize(String value) { this.value = value; }

    /**
     * Valor textual para consumo pelo frontend.
     *
     * @return tamanho/estratégia de ajuste (ex.: {@code lg}, {@code cover})
     */
    @JsonValue
    public String getValue() { return value; }
}
