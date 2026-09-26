package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.type.TypeFactory;
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
     * referencia completa correspondente. Mais de um mapping com o mesmo ID efetivo e erro,
     * nunca uma escolha dependente da ordem de registro. Ausencia continua retornando empty.
     * </p>
     * @throws IllegalStateException quando o ID identifica mais de um mapping
     */
    Optional<CanonicalOperationRef> resolveByOperationId(String operationId);

    /**
     * Resolve um binding estrutural obrigatorio por operationId explicito, recurso declarado em
     * {@code @ApiResource} e metodo HTTP esperado. A identidade explicita pode vir de
     * {@code @Operation}; para um papel compartilhado de lifecycle bulk, vem de
     * {@code @BulkResourceOperations} e e aplicada ao handler identificado por
     * {@code @BulkResourceOperation}.
     * Exige unicidade global antes de verificar o recurso; nomes Java nunca satisfazem este
     * binding estrito, ainda que continuem aceitos por discovery legado nao estrito.
     * O mapping deve ter exatamente uma rota canonica e um metodo, sem condicoes de
     * params/headers/custom que nao possam ser representadas em {@link CanonicalOperationRef}.
     * Outro mapping nao pode compartilhar o mesmo path/metodo, mesmo com ID ou midia distintos.
     * Operacoes explicitamente ocultas nao podem ser usadas neste binding.
     *
     * <p>Usar depois da inicializacao do registro MVC. Esta prova estrutural nao atesta
     * schema gerado, papel de avaliacao/confirmacao, autorizacao, providers ou execucao.
     * O consumidor deve validar essas garantias adicionais na sua composicao.</p>
     *
     * <p>Resolvers substitutos precisam implementar a garantia completa. O default falha
     * explicitamente, pois o lookup permissivo nao comprova recurso nem unicidade de rota.</p>
     *
     * @throws IllegalArgumentException quando um argumento e vazio ou o metodo HTTP e invalido
     * @throws IllegalStateException quando o binding nao satisfaz as garantias estruturais
     * @throws UnsupportedOperationException quando o resolver nao implementa binding estrito
     */
    default CanonicalOperationRef requireResourceOperation(String resourceKey, String operationId, String method) {
        throw new UnsupportedOperationException("Strict resource operation binding is not implemented by this resolver");
    }

    /**
     * Resolves the required, direct DTO {@code @RequestBody} from the same strict MVC binding.
     * Generic types must be concrete in the registered controller's context; the caller supplies
     * only the configured mapper's TypeFactory, never a guessed DTO type. Does not fetch schemas.
     * Custom resolvers must implement the complete handler/type guarantee; no permissive fallback.
     */
    default CanonicalRequestBodyBinding requireResourceRequestBody(String resourceKey, String operationId,
            String method, TypeFactory typeFactory) {
        throw new UnsupportedOperationException("Strict request body binding is not implemented by this resolver");
    }
}
