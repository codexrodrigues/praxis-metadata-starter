package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AllowedFileTypes {
    ALL("*/*"),
    IMAGES("image/*"),
    JSON("application/json"),
    XML("application/xml,text/xml"),
    HTML("text/html"),
    CSV("text/csv,application/csv,application/vnd.ms-excel"),
    PDF("application/pdf"),
    WORD("application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    EXCEL("application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    TEXT("text/plain"),
    PPT("application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    ZIP("application/zip,application/x-zip-compressed"),
    AUDIO("audio/*"),
    VIDEO("video/*"),
    CUSTOM(""); // para casos onde o dev quer customizar manualmente

    private final String value;
    AllowedFileTypes(String value) { this.value = value; }
    @JsonValue
    public String getValue() { return value; }
}

