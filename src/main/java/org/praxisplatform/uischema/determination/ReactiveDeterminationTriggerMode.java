package org.praxisplatform.uischema.determination;

/**
 * Gatilhos estruturais suportados por uma determinacao reativa.
 *
 * <p>A primeira versao e deliberadamente focal: a execucao acontece quando uma das fontes
 * declaradas muda. Novos modos exigem semantica runtime e validacao proprias; nao devem ser
 * introduzidos como aliases de eventos locais de UI.</p>
 */
public enum ReactiveDeterminationTriggerMode {
    ON_CHANGE("on-change");

    private final String metadataValue;

    ReactiveDeterminationTriggerMode(String metadataValue) {
        this.metadataValue = metadataValue;
    }

    public String metadataValue() {
        return metadataValue;
    }
}
