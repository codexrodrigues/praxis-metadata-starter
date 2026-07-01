package org.praxisplatform.uischema.options;

/**
 * Public policy that documents how invalid sort keys are handled by an option source.
 */
public enum OptionSourceInvalidSortPolicy {
    REJECT("reject"),
    IGNORE("ignore"),
    UNSUPPORTED("unsupported");

    private final String wireValue;

    OptionSourceInvalidSortPolicy(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
