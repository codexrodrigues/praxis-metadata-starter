package org.praxisplatform.uischema.openapi;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.util.Optional;

/**
 * Resolve a identidade canonica de operacoes OpenAPI documentadas.
 *
 * <p>
 * Esta fronteira desacopla controllers e catalogos da heuristica concreta usada para chegar em
 * {@code group + operationId + path + method}. Ela atende tanto fluxos baseados apenas em
 * {@code path + method} quanto fluxos que partem de {@code HandlerMethod}.
 * </p>
 *
 * <p>
 * A importancia desta interface e centralizar a fonte de verdade usada por schemas filtrados,
 * surfaces, actions e capabilities. Se a heuristica canonica mudar, ela deve mudar aqui, e nao
 * ser duplicada em consumidores documentais ou controllers.
 * </p>
 */
public interface CanonicalOperationResolver {

    /**
     * Resolve apenas o grupo canonico associado ao path informado.
     */
    String resolveGroup(String path);

    /**
     * Resolve uma referencia canonica de rota a partir de {@code path + method}.
     *
     * <p>
     * Esta sobrecarga garante {@code group}, {@code path} e {@code method} canonicos, mas pode
     * retornar {@code operationId = null} porque nao existe contexto de handler associado.
     * </p>
     */
    CanonicalOperationRef resolve(String path, String method);

    /**
     * Resolve uma referencia canonica completa a partir do handler e do mapping do Spring MVC.
     *
     * <p>
     * Implementacoes podem aplicar heuristicas para escolher o path e o metodo canonicos quando o
     * mapping tiver multiplos valores. Essas heuristicas devem permanecer documentadas porque
     * superficies de discovery e resolucao de schema dependem delas.
     * </p>
     */
    CanonicalOperationRef resolve(HandlerMethod handlerMethod, RequestMappingInfo mappingInfo);

    /**
     * Procura uma operacao documentada pelo seu {@code operationId}.
     *
     * <p>
     * Implementacoes podem precisar varrer o registro de handlers do Spring para produzir a
     * referencia completa correspondente.
     * </p>
     */
    Optional<CanonicalOperationRef> resolveByOperationId(String operationId);
}
