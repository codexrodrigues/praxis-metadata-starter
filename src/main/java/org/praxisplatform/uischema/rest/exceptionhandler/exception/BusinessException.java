package org.praxisplatform.uischema.rest.exceptionhandler.exception;

import org.praxisplatform.uischema.rest.failure.ResourceOperationFailure;
import org.praxisplatform.uischema.rest.failure.ResourceOperationFailureException;
import org.praxisplatform.uischema.rest.failure.ResourceOperationFailureKind;

/**
 * Exceção para erros de regra de negócio. Lançar quando a operação é válida
 * sintaticamente, mas viola uma regra funcional (ex.: limites, estados).
 *
 * @since 1.0.0
 */
public class BusinessException extends ResourceOperationFailureException {

    /**
     * Cria a exceção com a mensagem de detalhe.
     * @param message detalhe do erro de negócio
     */
    public BusinessException(String message) {
        super(ResourceOperationFailure.of(
                ResourceOperationFailureKind.BUSINESS_RULE_VIOLATION,
                "BUSINESS_RULE_VIOLATION",
                message
        ));
    }
}
