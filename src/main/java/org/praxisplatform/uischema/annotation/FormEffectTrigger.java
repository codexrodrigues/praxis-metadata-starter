package org.praxisplatform.uischema.annotation;

/** Events that may activate a form effect in the official runtime. */
public enum FormEffectTrigger {
    VALUE_CHANGE("value-change");

    private final String wireValue;

    FormEffectTrigger(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
