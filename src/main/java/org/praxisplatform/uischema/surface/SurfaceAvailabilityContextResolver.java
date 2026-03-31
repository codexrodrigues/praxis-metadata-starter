package org.praxisplatform.uischema.surface;

/**
 * Resolve o contexto canonico usado pela avaliacao de availability de surfaces.
 *
 * <p>
 * Esta fronteira separa a coleta de sinais contextuais do runtime HTTP da regra de availability
 * propriamente dita. Assim, `SurfaceCatalogService` continua orquestrando o catalogo sem acoplar
 * diretamente a detalhes de servlet, locale ou tenancy.
 * </p>
 */
public interface SurfaceAvailabilityContextResolver {

    SurfaceAvailabilityContext resolve(SurfaceDefinition definition, Object resourceId);
}
