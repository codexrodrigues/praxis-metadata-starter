package org.praxisplatform.uischema.annotation;

/**
 * Familias de apresentacao semanticamente compatíveis com uma projection analitica.
 */
public enum AnalyticsPresentationFamily {
    CHART("chart"),
    ANALYTIC_TABLE("analytic-table"),
    KPI("kpi"),
    SUMMARY_LIST("summary-list");

    private final String wireValue;

    AnalyticsPresentationFamily(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
