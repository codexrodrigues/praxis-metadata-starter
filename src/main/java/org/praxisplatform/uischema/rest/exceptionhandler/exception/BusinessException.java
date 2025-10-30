package org.praxisplatform.uischema.rest.exceptionhandler.exception;

import lombok.Getter;

/**
 * Exceção para erros de regra de negócio. Lançar quando a operação é válida
 * sintaticamente, mas viola uma regra funcional (ex.: limites, estados).
 *
 * @since 1.0.0
 */
@Getter
public class BusinessException extends RuntimeException{

    /**
     * Cria a exceção com a mensagem de detalhe.
     * @param message detalhe do erro de negócio
     */
    public BusinessException(String message) {
        super(message);
    }
}
