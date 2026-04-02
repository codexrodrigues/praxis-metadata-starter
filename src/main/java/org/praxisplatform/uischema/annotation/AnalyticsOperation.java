package org.praxisplatform.uischema.annotation;

/**
 * Operacoes analiticas canonicas atualmente suportadas sobre {@code praxis.stats}.
 */
public enum AnalyticsOperation {
    GROUP_BY("group-by"),
    TIMESERIES("timeseries"),
    DISTRIBUTION("distribution");

    private final String wireValue;

    AnalyticsOperation(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
