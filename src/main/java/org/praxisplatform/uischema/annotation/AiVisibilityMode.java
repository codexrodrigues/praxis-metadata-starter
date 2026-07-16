package org.praxisplatform.uischema.annotation;

/**
 * Tokens canonicos de visibilidade publicados em {@code x-domain-governance.aiUsage.visibility}.
 */
public enum AiVisibilityMode {
    ALLOW("allow"),
    MASK("mask"),
    SUMMARIZE_ONLY("summarize_only"),
    DENY("deny");

    private final String wireValue;

    AiVisibilityMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
