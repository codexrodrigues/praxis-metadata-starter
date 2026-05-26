package org.praxisplatform.uischema.surface;

/**
 * Cardinalidade estrutural da resposta publicada por uma surface.
 *
 * <p>
 * O valor descreve o corpo retornado pela operacao HTTP real, sem substituir o schema canonico
 * resolvido por {@code /schemas/filtered}. Runtimes usam essa pista para escolher materializacao
 * adequada quando uma surface item-level projeta uma colecao relacionada.
 * </p>
 */
public enum SurfaceResponseCardinality {
    OBJECT,
    COLLECTION,
    PAGE,
    VOID,
    UNKNOWN
}
