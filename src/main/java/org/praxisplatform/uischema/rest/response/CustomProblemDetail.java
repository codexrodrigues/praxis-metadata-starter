package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.ProblemDetail;

/**
 * Extensão de {@link ProblemDetail} com campos adicionais padronizados
 * para mensagens e categorização de erros.
 *
 * @since 1.0.0
 */
@Getter
@Setter
public class CustomProblemDetail extends ProblemDetail {

    /** Mensagem específica do problema reportado. */
    private String message;

    /** Categoria do erro, para uso em UI e métricas. */
    private ErrorCategory category;

    /**
     * Constrói o detalhe de problema com a mensagem informada.
     * @param message detalhe textual do problema
     */
    public CustomProblemDetail(String message) {
        this.message = message;
        this.category = null;
    }

}
