package org.praxisplatform.uischema.annotation;

/**
 * Modo estrutural de organizacao de secoes de formulario em {@link UiFormLayout}.
 */
public enum LayoutMode {
    FLAT("flat"),
    TABS("tabs"),
    ACCORDION("accordion"),
    STEPPER("stepper");

    private final String wireValue;

    LayoutMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Token canonico serializado em {@code x-ui.formLayout.mode}. */
    public String wireValue() {
        return wireValue;
    }
}
