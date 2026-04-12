package org.praxisplatform.uischema.rest.exceptionhandler.exception;

/**
 * Exceção para inconsistências de payload enviadas pelo cliente em filtros.
 *
 * <p>Estende {@link IllegalArgumentException} para preservar a semantica
 * esperada pelos handlers atuais.</p>
 */
public class InvalidFilterPayloadException extends IllegalArgumentException {

    public InvalidFilterPayloadException(String message) {
        super(message);
    }
}
