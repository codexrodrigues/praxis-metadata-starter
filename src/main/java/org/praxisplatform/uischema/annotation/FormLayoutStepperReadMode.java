package org.praxisplatform.uischema.annotation;

/**
 * Política de comportamento de {@link FormLayoutMode#STEPPER} em modos de leitura
 * ({@code view}, {@code presentation} e {@code dossier}) no contrato canônico {@code x-ui.formLayout}.
 *
 * @since 8.0.0
 */
public enum FormLayoutStepperReadMode {
    /** Mantém o padrão do cliente ({@code "presentation"}) sem emitir override no schema. */
    AUTO(""),
    /** Mantém etapas navegáveis livremente como capítulos em Apresentação/Dossiê; degrada view bloqueado para vertical. */
    PRESENTATION("presentation"),
    /** Mantém etapas navegáveis livremente em qualquer modo de leitura (apresentação e view bloqueado). */
    ALL("all"),
    /** Degrada para empilhamento vertical em todos os modos de leitura. */
    NONE("none");

    private final String wireValue;

    FormLayoutStepperReadMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Valor serializado em {@code x-ui.formLayout.stepperInReadMode}, ou vazio para {@link #AUTO}. */
    public String wireValue() {
        return wireValue;
    }
}
