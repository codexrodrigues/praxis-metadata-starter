package org.praxisplatform.uischema.determination;

/**
 * Liga uma determinacao a uma operacao de formulario exata.
 *
 * @param schemaOperationId operationId cujo request schema recebe o binding em
 *                          {@code /schemas/filtered}
 * @param formMode modo de formulario representado pela operacao
 */
public record ReactiveDeterminationScope(
        String schemaOperationId,
        ReactiveDeterminationFormMode formMode
) {
}
