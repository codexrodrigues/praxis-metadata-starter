package org.praxisplatform.uischema.annotation;

/**
 * Tokens canonicos de uso governado publicados para authoring e raciocinio assistidos por IA.
 */
public enum AiControlledUseMode {
    ALLOW("allow"),
    REVIEW_REQUIRED("review_required"),
    DENY("deny");

    private final String wireValue;

    AiControlledUseMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
