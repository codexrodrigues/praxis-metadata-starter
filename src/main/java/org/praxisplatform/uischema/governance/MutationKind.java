package org.praxisplatform.uischema.governance;

/**
 * Tipo de mutacao executada no ciclo de escrita de um servico resource-oriented.
 */
public enum MutationKind {
    CREATE,
    UPDATE,
    PARTIAL_INTENT,
    PATCH,
    DELETE,
    BULK_UPDATE,
    DOMAIN_COMMAND
}
