package org.praxisplatform.uischema.annotation;

/**
 * Categorias canonicas de dados publicadas em {@code x-domain-governance.dataCategory}.
 */
public enum DomainDataCategory {
    CREDENTIAL("credential"),
    SENSITIVE_PERSONAL("sensitive_personal"),
    PERSONAL("personal"),
    FINANCIAL("financial"),
    OPERATIONAL("operational"),
    LEGAL("legal");

    private final String wireValue;

    DomainDataCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
