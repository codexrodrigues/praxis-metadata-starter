package org.praxisplatform.uischema.rest.exceptionhandler;

/**
 * Categorias padronizadas de erro publicadas pela API.
 *
 * <p>
 * Essas categorias complementam o status HTTP e ajudam consumidores a distinguir
 * falhas de validacao, negocio, seguranca e infraestrutura sem depender apenas
 * de parsing textual da mensagem.
 * </p>
 *
 * @since 1.0.0
 */
public enum ErrorCategory {
    BUSINESS_LOGIC,
    VALIDATION,
    SYSTEM,
    SECURITY,
    UNKNOWN
}
