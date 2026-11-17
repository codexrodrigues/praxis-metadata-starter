package org.praxisplatform.uischema.annotation;

/**
 * Papel semantico de uma etapa individual {@link CalculationStep} dentro de {@link CalculationTrail#steps()}.
 */
public enum StepKind {
    BASE("base"),
    ADDITION("addition"),
    DEDUCTION("deduction"),
    FACTOR("factor"),
    CAP("cap"),
    SUBTOTAL("subtotal");

    private final String wireValue;

    StepKind(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Token canonico serializado no payload de etapa da memoria de calculo. */
    public String wireValue() {
        return wireValue;
    }
}
