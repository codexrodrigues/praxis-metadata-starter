package org.praxisplatform.uischema.validation;

/**
 * Politica de tratamento para conflitos semanticos entre anotacoes canonicamente exclusivas.
 */
public enum AnnotationConflictMode {
    WARN,
    FAIL,
    IGNORE
}
