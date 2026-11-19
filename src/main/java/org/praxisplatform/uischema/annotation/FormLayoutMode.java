package org.praxisplatform.uischema.annotation;

/**
 * Modos de layout suportados pelo contrato canônico {@code x-ui.formLayout}.
 *
 * @since 8.0.0
 */
public enum FormLayoutMode {
    /** Empilhamento vertical padrão (serializado como {@code "flat"} no schema). */
    FLAT("flat"),
    /** Alias semântico equivalente a {@link #FLAT} no contrato JSON wire atual. */
    VERTICAL("flat"),
    /** Organização das seções do formulário em abas. */
    TABS("tabs"),
    /** Organização das seções do formulário em painéis sanfonados. */
    ACCORDION("accordion"),
    /** Fluxo guiado por etapas lineares nativas sobre o mesmo formulário raiz. */
    STEPPER("stepper"),
    /**
     * Resolução heurística automática baseada na cardinalidade de grupos {@code @UISchema(group)}
     * e quantidade de campos do DTO: {@code >= 4} grupos (ou {@code >= 3} grupos e {@code >= 12} campos)
     * resolve para {@code "stepper"} (habilitando {@code stepperReviewStep = true} quando {@code >= 4} grupos);
     * {@code >= 2} grupos e {@code >= 6} campos resolve para {@code "tabs"}; caso contrário {@code "flat"}.
     */
    AUTO("auto");

    private final String wireValue;

    FormLayoutMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Valor serializado em {@code x-ui.formLayout.mode}. */
    public String wireValue() {
        return wireValue;
    }
}
