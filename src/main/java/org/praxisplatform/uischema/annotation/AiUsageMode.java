package org.praxisplatform.uischema.annotation;

/**
 * Tokens canonicos de politica de uso por IA publicados em {@code x-domain-governance.aiUsage}.
 */
public enum AiUsageMode {
    ALLOW("allow"),
    DENY("deny"),
    MASK("mask"),
    REVIEW_REQUIRED("review_required"),
    SUMMARIZE_ONLY("summarize_only");

    private final String wireValue;

    AiUsageMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
