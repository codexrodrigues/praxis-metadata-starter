package org.praxisplatform.uischema.annotation;

/**
 * Intencao analitica canonica publicada em {@code x-ui.analytics}.
 */
public enum AnalyticsIntent {
    RANKING("ranking"),
    TREND("trend"),
    DISTRIBUTION("distribution"),
    COMPOSITION("composition"),
    COMPARISON("comparison"),
    CORRELATION("correlation");

    private final String wireValue;

    AnalyticsIntent(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
