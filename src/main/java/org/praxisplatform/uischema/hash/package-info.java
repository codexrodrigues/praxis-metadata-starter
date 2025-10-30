/**
 * Funções e utilitários para canonização e hash de schemas OpenAPI,
 * garantindo identificadores e ETags estáveis.
 *
 * <p>
 * Utilizado por {@link org.praxisplatform.uischema.controller.docs.ApiDocsController}
 * para calcular ETag das respostas de {@code /schemas/filtered} e por
 * integradores que desejam persistir versões de schema. Relaciona-se aos
 * planos descritos em {@code docs/SCHEMA-HASH-PLAN.md}.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.hash;

