package org.praxisplatform.uischema.annotation;

/**
 * Booleano tri-estado para atributos opcionais em anotações de metadados UI, evitando a emissão
 * de chaves padrão quando o autor não declarou override explícito.
 *
 * @since 8.0.0
 */
public enum UiBoolean {
    /** Não emite override explícito; preserva o comportamento padrão do runtime consumidor. */
    AUTO(null),
    /** Override explícito verdadeiro ({@code true}). */
    TRUE(Boolean.TRUE),
    /** Override explícito falso ({@code false}). */
    FALSE(Boolean.FALSE);

    private final Boolean value;

    UiBoolean(Boolean value) {
        this.value = value;
    }

    /** Converte para {@link Boolean} ou {@code null} quando {@link #AUTO}. */
    public Boolean toBoolean() {
        return value;
    }
}
