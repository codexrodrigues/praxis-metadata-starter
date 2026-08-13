package org.praxisplatform.uischema.determination;

/**
 * Binding entre um campo do draft e o request da operacao de determinacao.
 *
 * <p>Ambos os paths usam JSON Pointer. Paths executaveis, SpEL e callbacks nao fazem parte do
 * contrato.</p>
 */
public record ReactiveDeterminationInputBinding(
        String fieldPath,
        String requestPath
) {
}
