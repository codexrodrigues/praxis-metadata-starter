package org.praxisplatform.uischema.annotation;

/**
 * Dimensao contextual de escopo ativo suportada por {@link ContextScopeBinding}.
 */
public enum ScopeDimension {
    ORGANIZATION("organization"),
    COMPANY_GROUP("company_group"),
    UNIT("unit"),
    PERSON("person"),
    BOND("bond"),
    CONTRACT("contract");

    private final String wireValue;

    ScopeDimension(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Token canonico serializado em {@code x-ui.contextBinding.dimension}. */
    public String wireValue() {
        return wireValue;
    }
}
