package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

public enum IconSize {
    SMALL("sm"),
    MEDIUM("md"),
    LARGE("lg"),
    XLARGE("xl"),
    XXLARGE("xxl"),
    XXXLARGE("xxxl"),
    DEFAULT("default"),
    AUTO("auto"),
    NONE("none"),
    FULL("full"),
    FIT("fit"),
    FILL("fill"),
    COVER("cover"),
    CONTAIN("contain"),
    STRETCH("stretch"),
    FIT_CONTENT("fit-content"),
    FIT_VIEWPORT("fit-viewport"),
    FIT_SCREEN("fit-screen"),
    FIT_WINDOW("fit-window"),
    FIT_PARENT("fit-parent"),
    INHERIT("inherit");

    private final String value;
    IconSize(String value) { this.value = value; }

    @JsonValue
    public String getValue() { return value; }
}
