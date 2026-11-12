package org.praxisplatform.uischema.annotation;

/**
 * Tom visual e semantico associado a um {@link QuickFilter} para renderizacao consistente em
 * {@code <praxis-scope-bar>}, toolbars de colecao e catalogos executivos.
 */
public enum QuickFilterTone {
    UNSPECIFIED(""),
    NEUTRAL("neutral"),
    PRIMARY("primary"),
    INFO("info"),
    SUCCESS("success"),
    WARNING("warning"),
    DANGER("danger");

    private final String wireValue;

    QuickFilterTone(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Retorna o token serializado no contrato {@code x-ui.resource.quickFilters} e {@code /schemas/domain},
     * ou string vazia quando {@link #UNSPECIFIED}.
     */
    public String wireValue() {
        return wireValue;
    }
}
