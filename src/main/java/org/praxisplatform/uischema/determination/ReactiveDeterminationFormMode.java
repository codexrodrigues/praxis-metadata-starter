package org.praxisplatform.uischema.determination;

/** Form mode to which a structural reactive determination binding applies. */
public enum ReactiveDeterminationFormMode {
    CREATE("create"),
    EDIT("edit");

    private final String metadataValue;

    ReactiveDeterminationFormMode(String metadataValue) {
        this.metadataValue = metadataValue;
    }

    public String metadataValue() {
        return metadataValue;
    }
}
