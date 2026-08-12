package org.praxisplatform.uischema.annotation;

/** Controls when a determination result may replace a form control value. */
public enum FormEffectWritePolicy {
    IF_PRISTINE("if-pristine"),
    IF_EMPTY("if-empty"),
    REPLACE("replace");

    private final String wireValue;

    FormEffectWritePolicy(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
