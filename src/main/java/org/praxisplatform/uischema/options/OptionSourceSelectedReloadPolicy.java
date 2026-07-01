package org.praxisplatform.uischema.options;

/**
 * Public policy that tells runtimes how safe selected-value reload is for an option source.
 */
public enum OptionSourceSelectedReloadPolicy {
    REQUIRED("required"),
    SUPPORTED("supported"),
    UNSUPPORTED_WITH_WAIVER("unsupported-with-waiver"),
    NOT_APPLICABLE("not-applicable");

    private final String wireValue;

    OptionSourceSelectedReloadPolicy(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
