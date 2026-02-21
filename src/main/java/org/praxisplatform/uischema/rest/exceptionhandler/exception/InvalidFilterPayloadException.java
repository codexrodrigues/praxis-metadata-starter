package org.praxisplatform.uischema.rest.exceptionhandler.exception;

/**
 * Exceção para inconsistências de payload enviadas pelo cliente em filtros.
 *
 * <p>Estende {@link IllegalArgumentException} para manter compatibilidade
 * com fluxos legados que já capturam esse tipo de erro.</p>
 */
public class InvalidFilterPayloadException extends IllegalArgumentException {

    public InvalidFilterPayloadException(String message) {
        super(message);
    }
}

