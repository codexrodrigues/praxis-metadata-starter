package org.praxisplatform.uischema.determination;

/**
 * Origem estrutural publica do binding.
 *
 * <p>Este enum nao identifica tenant, usuario, decisao aplicada nem estado de publicacao do
 * Config Starter. Esses dados pertencem ao endpoint backend autenticado que avalia a decisao.</p>
 */
public enum ReactiveDeterminationProvenanceKind {
    PLATFORM("platform"),
    HOST("host");

    private final String metadataValue;

    ReactiveDeterminationProvenanceKind(String metadataValue) {
        this.metadataValue = metadataValue;
    }

    public String metadataValue() {
        return metadataValue;
    }
}
