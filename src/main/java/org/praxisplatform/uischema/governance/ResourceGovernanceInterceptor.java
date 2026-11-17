package org.praxisplatform.uischema.governance;

import org.praxisplatform.uischema.exporting.CollectionExportField;
import org.praxisplatform.uischema.exporting.CollectionExportRequest;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.service.base.ResourceFilterAccessScope;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;

import java.util.Collection;
import java.util.List;

/**
 * SPI extensivel de governanca transversal para servicos resource-oriented do starter.
 *
 * <p>Permite que starters externos (como engines de autorizacao ou auditoria) componham escopo SQL,
 * verificacao de acesso a itens, decoracao de DTOs, allowlist/autorizacao de exportacao, registro de
 * metricas analiticas e interceptacao transacional de mutacoes sem alterar metodos {@code final} nem
 * exigir overrides manuais em cada servico do host.</p>
 *
 * <p>Implementacoes podem declarar {@link org.springframework.core.annotation.Order} ou implementar
 * {@link org.springframework.core.Ordered}; os interceptors sao ordenados pelo
 * {@link org.springframework.core.annotation.AnnotationAwareOrderComparator}.</p>
 */
public interface ResourceGovernanceInterceptor {

    /**
     * Refina ou restringe o escopo de filtros SQL aplicado antes de consultas, options, stats e exportacao.
     */
    default <E> ResourceFilterAccessScope<E> customizeFilterAccessScope(
            BaseResourceQueryService<?, ?, ?> service,
            ResourceFilterAccessScope<E> baseScope
    ) {
        return baseScope;
    }

    /**
     * Valida acesso a uma entidade carregada para o {@link AccessMode} informado.
     */
    default <E> void verifyEntityAccess(
            BaseResourceQueryService<?, ?, ?> service,
            E entity,
            AccessMode mode
    ) {
    }

    /**
     * Valida acesso em lote sobre uma colecao de entidades carregadas.
     */
    default <E> void verifyEntitiesAccess(
            BaseResourceQueryService<?, ?, ?> service,
            Collection<E> entities,
            AccessMode mode
    ) {
        if (entities != null) {
            entities.forEach(entity -> verifyEntityAccess(service, entity, mode));
        }
    }

    /**
     * Decora (ou mascara) um DTO individual de resposta antes da entrega ao chamador.
     */
    default <E, R> R decorateResponse(
            BaseResourceQueryService<?, ?, ?> service,
            E entity,
            R dto,
            String surfaceOrOperation
    ) {
        return dto;
    }

    /**
     * Decora (ou mascara) um lote de DTOs de resposta ja enriquecidos.
     */
    default <R> List<R> decorateResponses(
            BaseResourceQueryService<?, ?, ?> service,
            List<R> dtos,
            String surfaceOrOperation
    ) {
        return dtos;
    }

    /**
     * Filtra ou ajusta a allowlist efetiva de campos exportaveis do recurso.
     */
    default List<CollectionExportField> filterExportFields(
            BaseResourceQueryService<?, ?, ?> service,
            List<CollectionExportField> fields
    ) {
        return fields;
    }

    /**
     * Autoriza a execucao de uma exportacao antes da leitura paginada de linhas.
     */
    default void authorizeExport(
            BaseResourceQueryService<?, ?, ?> service,
            CollectionExportRequest<?> request
    ) {
    }

    /**
     * Filtra ou ajusta os descritores analiticos visiveis e consultaveis em {@code /stats/*}.
     */
    default StatsFieldRegistry filterStatsRegistry(
            BaseResourceQueryService<?, ?, ?> service,
            StatsFieldRegistry registry
    ) {
        return registry;
    }

    /**
     * Executado na mesma transacao imediatamente antes da persistencia (save/delete) da mutacao.
     */
    default <E> void beforeMutation(
            BaseResourceQueryService<?, ?, ?> service,
            MutationContext mutationContext,
            E candidate,
            E previous
    ) {
    }

    /**
     * Executado na mesma transacao apos persistencia/hooks e antes do commit final.
     */
    default <E> void afterMutationBeforeCommit(
            BaseResourceQueryService<?, ?, ?> service,
            MutationContext mutationContext,
            E persistedEntity
    ) {
    }
}
