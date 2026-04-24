package org.praxisplatform.uischema.annotation;

/**
 * Categorias canonicas de governanca publicadas em {@code x-domain-governance.annotationType}.
 */
public enum DomainGovernanceKind {
    PRIVACY("privacy"),
    SECURITY("security"),
    COMPLIANCE("compliance");

    private final String wireValue;

    DomainGovernanceKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
