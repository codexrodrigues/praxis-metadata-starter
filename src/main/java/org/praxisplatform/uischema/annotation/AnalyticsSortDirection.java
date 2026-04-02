package org.praxisplatform.uischema.annotation;

/**
 * Direcao canonica de ordenacao em defaults analiticos.
 */
public enum AnalyticsSortDirection {
    ASC("asc"),
    DESC("desc");

    private final String wireValue;

    AnalyticsSortDirection(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
