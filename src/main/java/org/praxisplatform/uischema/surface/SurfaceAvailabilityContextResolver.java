package org.praxisplatform.uischema.surface;

/**
 * Resolve o contexto canonico usado pela avaliacao de availability de surfaces.
 *
 * <p>
 * Esta fronteira separa a coleta de sinais contextuais do runtime HTTP da regra de availability
 * propriamente dita. Assim, `SurfaceCatalogService` continua orquestrando o catalogo sem acoplar
 * diretamente a detalhes de servlet, locale, tenancy, authorities ou snapshots de estado.
 * </p>
 *
 * <p>
 * Implementacoes devem privilegiar agregacao por recurso e request, e nao lookup isolado por
 * surface, para evitar custo repetido e N+1 contextual.
 * </p>
 */
public interface SurfaceAvailabilityContextResolver {

    /**
     * Resolve um contexto compartilhavel por todas as surfaces do mesmo recurso/catalogo.
     *
     * <p>
     * Implementacoes devem tratar este metodo como ponto de agregacao por request, e nao como
     * lookup por surface individual.
     * </p>
     */
    SurfaceAvailabilityContext resolve(String resourceKey, String resourcePath, Object resourceId);
}
