package org.praxisplatform.uischema.governance;

/**
 * Modo de acesso verificado por {@link ResourceGovernanceInterceptor} sobre entidades ou operacoes.
 */
public enum AccessMode {
    READ,
    WRITE,
    DELETE,
    EXPORT,
    STATS
}
