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

    /** Codigo publico estavel para tratamento sem parsing da mensagem. */
    private String code;

    /** Caminho opcional no contrato publico que o consumidor pode corrigir. */
    private String target;

    /**
     * Constrói o detalhe de problema com a mensagem informada.
     *
     * @param message detalhe textual do problema
     */
    public CustomProblemDetail(String message) {
        this.message = message;
        this.category = null;
    }

    /**
     * Publishes the stable code both as a typed field and in the legacy
     * {@link ProblemDetail} properties map consumed by existing clients.
     */
    public void setCode(String code) {
        this.code = code;
        setProperty("code", code);
    }

    /**
     * Publishes the correction target without breaking clients that still read
     * RFC 7807 extension members from the properties map.
     */
    public void setTarget(String target) {
        this.target = target;
        if (target != null && !target.isBlank()) {
            setProperty("target", target);
        }
    }

}
