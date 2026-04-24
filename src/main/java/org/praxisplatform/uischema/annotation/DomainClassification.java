package org.praxisplatform.uischema.annotation;

/**
 * Niveis canonicos de classificacao publicados em {@code x-domain-governance.classification}.
 */
public enum DomainClassification {
    PUBLIC("public"),
    INTERNAL("internal"),
    CONFIDENTIAL("confidential"),
    RESTRICTED("restricted");

    private final String wireValue;

    DomainClassification(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
