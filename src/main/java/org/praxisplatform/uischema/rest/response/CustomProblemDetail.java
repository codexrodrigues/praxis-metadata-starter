package org.praxisplatform.uischema.rest.response;

import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.ProblemDetail;

/**
 * Extensao padronizada de {@link ProblemDetail} usada pela plataforma.
 *
 * <p>
 * Alem dos campos RFC 7807, este tipo carrega uma mensagem resumida e uma
 * {@link ErrorCategory} padronizada, permitindo tratamento mais previsivel em UI,
 * observabilidade e integrações clientes.
 * </p>
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
     *
     * @param message detalhe textual do problema
     */
    public CustomProblemDetail(String message) {
        this.message = message;
        this.category = null;
    }

}
