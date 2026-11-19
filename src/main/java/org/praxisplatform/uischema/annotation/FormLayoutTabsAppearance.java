package org.praxisplatform.uischema.annotation;

/**
 * Aparência visual dos seletores quando {@link FormLayout#mode()} é {@link FormLayoutMode#TABS}.
 *
 * @since 8.0.0
 */
public enum FormLayoutTabsAppearance {
    /** Mantém o padrão do cliente sem emitir override no schema. */
    AUTO(""),
    /** Estilo em pílulas arredondadas. */
    PILLS("pills"),
    /** Estilo minimalista sublinhado. */
    UNDERLINE("underline"),
    /** Estilo em botões agrupados. */
    BUTTONS("buttons");

    private final String wireValue;

    FormLayoutTabsAppearance(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Valor serializado em {@code x-ui.formLayout.tabsAppearance}, ou vazio para {@link #AUTO}. */
    public String wireValue() {
        return wireValue;
    }
}
