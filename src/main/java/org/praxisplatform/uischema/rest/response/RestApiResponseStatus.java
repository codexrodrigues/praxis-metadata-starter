package org.praxisplatform.uischema.rest.response;

/**
 * Valores canônicos de status usados em {@link RestApiResponse}.
 *
 * <p>
 * A plataforma reduz o envelope a dois estados de alto nivel: sucesso e falha.
 * O status HTTP continua sendo a fonte de verdade protocolar; estes valores
 * ajudam clientes a tratar o envelope de forma uniforme.
 * </p>
 *
 * @since 1.0.0
 */
public interface RestApiResponseStatus {
    String SUCCESS = "success";
    String FAILURE = "failure";
}
