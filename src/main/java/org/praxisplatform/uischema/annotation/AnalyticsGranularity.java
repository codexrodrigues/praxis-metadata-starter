package org.praxisplatform.uischema.annotation;

public enum AnalyticsGranularity {
    UNSPECIFIED(""),
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    private final String wireValue;

    AnalyticsGranularity(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
