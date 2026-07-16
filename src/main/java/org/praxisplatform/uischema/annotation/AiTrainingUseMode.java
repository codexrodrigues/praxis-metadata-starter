package org.praxisplatform.uischema.annotation;

/**
 * Tokens canonicos de uso para treino publicados em {@code x-domain-governance.aiUsage.trainingUse}.
 */
public enum AiTrainingUseMode {
    ALLOW("allow"),
    DENY("deny");

    private final String wireValue;

    AiTrainingUseMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
