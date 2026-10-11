package org.praxisplatform.uischema.validation;

/**
 * Propriedades de governança para validação estática de expressões declarativas de domínio.
 *
 * @param enabled se a validação estática está ativa durante o startup/build do OpenAPI (padrão true)
 * @param strict se true, aborta o startup em caso de erro mesmo fora de perfis dev/test/ci (padrão false)
 */
public record ExpressionValidationProperties(
        boolean enabled,
        boolean strict
) {
    public static ExpressionValidationProperties defaults() {
        return new ExpressionValidationProperties(true, false);
    }
}
