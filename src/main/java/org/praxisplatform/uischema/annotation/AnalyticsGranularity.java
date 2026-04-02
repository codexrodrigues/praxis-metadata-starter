package org.praxisplatform.uischema.annotation;

public enum AnalyticsGranularity {
    UNSPECIFIED(""),
    HOUR("hour"),
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    QUARTER("quarter"),
    YEAR("year");

    private final String wireValue;

    AnalyticsGranularity(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
