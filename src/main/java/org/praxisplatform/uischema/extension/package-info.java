/**
 * Implementações que estendem o pipeline do SpringDoc para gerar documentos
 * OpenAPI enriquecidos com {@code x-ui}.
 *
 * <p>
 * Inclui o {@link org.praxisplatform.uischema.extension.CustomOpenApiResolver},
 * responsável por aplicar a precedência de metadados descrita na
 * documentação arquitetural, e utilitários de apoio como
 * {@link org.praxisplatform.uischema.extension.ResolverUtils}. A anotação
 * {@link org.praxisplatform.uischema.extension.annotation.UISchema}
 * reside em subpacote dedicado para reduzir dependências transitivas.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.extension;
