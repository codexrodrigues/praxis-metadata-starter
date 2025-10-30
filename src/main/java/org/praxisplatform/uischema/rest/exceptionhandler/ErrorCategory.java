package org.praxisplatform.uischema.rest.exceptionhandler;

/**
 * Categorias padronizadas para erros retornados pela API.
 * Útil para classificação e tratamento no frontend.
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
