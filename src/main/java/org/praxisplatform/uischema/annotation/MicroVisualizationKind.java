package org.praxisplatform.uischema.annotation;

/**
 * Modalidades canonicas de micro-visualizacao suportadas pela plataforma Praxis.
 *
 * <p>
 * Mapeadas diretamente para as estrategias de renderizacao ultraleves e cell-safe do
 * {@code @praxisui/table}, {@code @praxisui/charts} e {@code @praxisui/core}.
 * </p>
 */
public enum MicroVisualizationKind {
    LINE("line"),
    AREA("area"),
    COLUMN("column"),
    COMPARISON("comparison"),
    STACKED_BAR("stackedBar"),
    RADIAL("radial"),
    HARVEY_BALL("harveyBall"),
    BULLET("bullet"),
    DELTA("delta"),
    PROCESS_FLOW("processFlow");

    private final String wireValue;

    MicroVisualizationKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
