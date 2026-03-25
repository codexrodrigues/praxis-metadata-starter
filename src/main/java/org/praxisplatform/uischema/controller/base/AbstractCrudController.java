package org.praxisplatform.uischema.controller.base;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.dto.LocateResponse;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseDistributionStatsResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseGroupByStatsResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseTimeSeriesStatsResponse;
import org.praxisplatform.uischema.service.base.BaseCrudService;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.praxisplatform.uischema.util.PageableBuilder;
import org.praxisplatform.uischema.util.SortBuilder;

/**
 * <h2>🏗️ Controller Base com Auto-Detecção de Path e Integração OpenAPI</h2>
 *
 * <h3>✨ O que vem pronto (por números)</h3>
 * <div class="ep-grid">
 *   <div class="ep"><span class="badge method get">GET</span> <code>/{id}</code></div>
 *   <div class="ep"><span class="badge method get">GET</span> <code>/all</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/filter</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/filter/cursor</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/locate</code></div>
 *   <div class="ep"><span class="badge method get">GET</span> <code>/by-ids</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/options/filter</code></div>
 *   <div class="ep"><span class="badge method get">GET</span> <code>/options/by-ids</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/option-sources/{sourceKey}/options/filter</code></div>
 *   <div class="ep"><span class="badge method get">GET</span> <code>/option-sources/{sourceKey}/options/by-ids</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/stats/group-by</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/stats/timeseries</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/stats/distribution</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/</code></div>
 *   <div class="ep"><span class="badge method put">PUT</span> <code>/{id}</code></div>
 *   <div class="ep"><span class="badge method del">DELETE</span> <code>/{id}</code></div>
 *   <div class="ep"><span class="badge method del">DELETE</span> <code>/batch</code></div>
 *   <div class="ep"><span class="badge method misc">GET</span> <code>/schemas</code> → <code>/schemas/filtered</code></div>
 * </div>
 * <p><strong>26 operações de filtro</strong>: texto (like/not/starts/ends), comparações (&gt;, &ge;, &lt;, &le;), listas (in/not),
 * intervalos (between/…​), datas relativas (últimos/próximos), tamanho de coleções, nulidade/booleanos.</p>
 * <p><strong>Documentação viva</strong>: OpenAPI por grupo + cache + ETag, com metadados de UI (x‑ui).</p>
 * 
 * <h3>🎯 Problema Resolvido</h3>
 * <p>Antes desta implementação, era necessário implementar manualmente o método {@code getBasePath()} 
 * em cada controller, causando duplicação de código e possibilidade de inconsistências. 
 * Agora o base path é detectado automaticamente via anotações.</p>
 * 
 * <h3>🔄 Fluxo de Auto-Detecção</h3>
 * <pre>
 * 1. @PostConstruct initializeBasePath() é executado após construção do bean
 * 2. AnnotationUtils.findAnnotation() detecta @RequestMapping/@ApiResource
 * 3. Extrai path das anotações (value[] ou path[])
 * 4. Se não encontrar anotações, emite warning e marca a configuração como pendente
 * 5. Base path disponível para HATEOAS links e documentação
 * </pre>
 * 
 * <h3>🚀 Benefícios da Auto-Detecção</h3>
 * <ul>
 *   <li><strong>Zero Boilerplate:</strong> Elimina necessidade de implementar getBasePath()</li>
 *   <li><strong>Consistência:</strong> Garante que path usado é o mesmo das anotações</li>
 *   <li><strong>Flexibilidade:</strong> Funciona com @RequestMapping, @ApiResource e variações</li>
 *   <li><strong>Integração:</strong> DynamicSwaggerConfig usa mesma lógica para grupos OpenAPI</li>
 *   <li><strong>HATEOAS:</strong> Links automáticos baseados no path detectado</li>
 *   <li><strong>Ordenação Inteligente:</strong> Aplica @DefaultSortColumn automaticamente</li>
 * </ul>
 * 
 * <h3>📊 Ordenação Padrão Automática</h3>
 * <p>Todos os endpoints de listagem aplicam automaticamente ordenação padrão quando nenhuma 
 * ordenação específica é fornecida via parâmetros de requisição.</p>
 * 
 * <h4>🔄 Como Funciona:</h4>
 * <ol>
 *   <li><strong>Sem parâmetro sort:</strong> Aplica ordenação de @DefaultSortColumn na entidade</li>
 *   <li><strong>Com parâmetro sort:</strong> Usa ordenação específica, ignora @DefaultSortColumn</li>
 *   <li><strong>Sem @DefaultSortColumn:</strong> Usa ordenação padrão do banco (imprevisível)</li>
 * </ol>
 * 
 * <h4>📋 Endpoints Afetados:</h4>
 * <ul>
 *   <li><strong>GET /{resource}/all:</strong> Lista completa com ordenação padrão</li>
 *   <li><strong>POST /{resource}/filter:</strong> Lista filtrada com ordenação padrão</li>
 * </ul>
 * 
 * <h4>🌐 Exemplos de Uso:</h4>
 * <pre>
 * // Entidade com ordenação padrão:
 * {@code @Entity}
 * public class Funcionario {
 *     {@code @DefaultSortColumn(priority = 1)}
 *     private String departamento;
 *     
 *     {@code @DefaultSortColumn(priority = 2)}  
 *     private String nomeCompleto;
 * }
 * 
 * // URLs suportadas:
 * GET /api/funcionarios/all                           → ORDER BY departamento ASC, nomeCompleto ASC
 * GET /api/funcionarios/all?sort=salario,desc         → ORDER BY salario DESC
 * POST /api/funcionarios/filter?page=0&amp;size=10        → ORDER BY departamento ASC, nomeCompleto ASC
 * POST /api/funcionarios/filter?page=0&amp;size=10&amp;sort=nome,asc → ORDER BY nome ASC
 * </pre>
 * 
 * <h3>📋 Exemplos de Uso</h3>
 * 
 * <h4>💚 RECOMENDADO - Com Constantes e Meta-Anotações:</h4>
 * <pre>{@code
 * // Criar constantes no projeto da aplicação:
 * public final class ApiPaths {
 *     public static final class HumanResources {
 *         public static final String FUNCIONARIOS = "/api/human-resources/funcionarios";
 *     }
 * }
 * import static com.example.project.constants.ApiPaths.HumanResources.FUNCIONARIOS;
 *
 * @ApiResource(FUNCIONARIOS)              // Meta-anotação: @RestController + @RequestMapping
 * @ApiGroup("human-resources")           // Grupo OpenAPI personalizado
 * public class FuncionarioController extends AbstractCrudController<
 *         Funcionario, FuncionarioDTO, Long, FuncionarioFilterDTO> {
 *
 *     @Autowired
 *     private FuncionarioService service;
 *
 *     @Override
 *     protected FuncionarioService getService() { return service; }
 *     
 *     // ✅ getBasePath() NÃO é necessário - detectado automaticamente
 *     // ✅ Grupo OpenAPI "recursos-humanos" criado automaticamente
 *     // ✅ Links HATEOAS gerados com path correto
 * }
 * }</pre>
 *
 * <h4>💛 TRADICIONAL - Com Anotações Padrão:</h4>
 * <pre>{@code
 * @RestController
 * @RequestMapping("/api/human-resources/funcionarios")
 * public class FuncionarioController extends AbstractCrudController<...> {
 *     
 *     // ✅ getBasePath() detectado automaticamente como "/api/human-resources/funcionarios"
 *     // ✅ Grupo OpenAPI "api-human-resources-funcionarios" criado automaticamente
 * }
 * }</pre>
 * 
 * <h3>⚙️ Integração com Sistema</h3>
 * <ul>
 *   <li><strong>DynamicSwaggerConfig:</strong> Escaneia controllers que estendem esta classe</li>
 *   <li><strong>ApiDocsController:</strong> Resolve grupos baseado nos paths detectados</li>
 *   <li><strong>HATEOAS:</strong> Links automáticos para self, create, update, delete</li>
 *   <li><strong>OpenAPI:</strong> Documentação organizada por grupos específicos</li>
 * </ul>
 * 
 * <h3>🔍 Estratégias de Detecção</h3>
 * <ol>
 *   <li><strong>@ApiResource.value[] / path[]:</strong> primeira prioridade</li>
 *   <li><strong>@RequestMapping.value[] / path[]:</strong> segunda prioridade</li>
 *   <li><strong>Configuração obrigatória:</strong> emite warning se não encontrar anotações e marca o path como pendente</li>
 * </ol>
 *
 * @param <E>  Tipo da Entidade JPA
 * @param <D>  Tipo do DTO (Data Transfer Object)
 * @param <ID> Tipo do identificador (Long, String, UUID, etc.)
 * @param <FD> Tipo do DTO de filtro (deve estender GenericFilterDTO)
 *
 * @see org.praxisplatform.uischema.annotation.ApiResource
 * @see org.praxisplatform.uischema.annotation.ApiGroup
 * @see org.praxisplatform.uischema.configuration.DynamicSwaggerConfig
 * @see org.praxisplatform.uischema.controller.docs.ApiDocsController
 */
public abstract class AbstractCrudController<E, D, ID, FD extends GenericFilterDTO> {

    private static final String GROUP_BY_STATS_REQUEST_EXAMPLE = """
            {
              "filter": {},
              "field": "<groupableField>",
              "metric": {
                "operation": "COUNT"
              },
              "limit": 10,
              "orderBy": "VALUE_DESC"
            }
            """;

    private static final String GROUP_BY_STATS_RESPONSE_EXAMPLE = """
            {
              "success": true,
              "data": {
                "field": "<groupableField>",
                "metric": {
                  "operation": "COUNT",
                  "field": null
                },
                "buckets": [
                  {
                    "key": "<bucket-1>",
                    "label": "<bucket-1>",
                    "value": 12,
                    "count": 12
                  },
                  {
                    "key": "<bucket-2>",
                    "label": "<bucket-2>",
                    "value": 4,
                    "count": 4
                  }
                ]
              },
              "links": null
            }
            """;

    private static final String TIME_SERIES_STATS_REQUEST_EXAMPLE = """
            {
              "filter": {},
              "field": "<timeField>",
              "granularity": "DAY",
              "metric": {
                "operation": "SUM",
                "field": "<numericMetricField>"
              },
              "from": "2026-03-01",
              "to": "2026-03-03",
              "fillGaps": true
            }
            """;

    private static final String TIME_SERIES_STATS_RESPONSE_EXAMPLE = """
            {
              "success": true,
              "data": {
                "field": "<timeField>",
                "granularity": "DAY",
                "metric": {
                  "operation": "SUM",
                  "field": "<numericMetricField>"
                },
                "points": [
                  {
                    "start": "2026-03-01",
                    "end": "2026-03-01",
                    "label": "2026-03-01",
                    "value": 25.0,
                    "count": 2
                  },
                  {
                    "start": "2026-03-02",
                    "end": "2026-03-02",
                    "label": "2026-03-02",
                    "value": 0.0,
                    "count": 0
                  }
                ]
              },
              "links": null
            }
            """;

    private static final String DISTRIBUTION_STATS_REQUEST_EXAMPLE = """
            {
              "filter": {},
              "field": "<distributionField>",
              "mode": "HISTOGRAM",
              "metric": {
                "operation": "COUNT"
              },
              "bucketSize": 1000,
              "bucketCount": 5,
              "limit": 5
            }
            """;

    private static final String DISTRIBUTION_STATS_RESPONSE_EXAMPLE = """
            {
              "success": true,
              "data": {
                "field": "<distributionField>",
                "mode": "HISTOGRAM",
                "metric": {
                  "operation": "COUNT",
                  "field": null
                },
                "buckets": [
                  {
                    "from": 0.0,
                    "to": 1000.0,
                    "key": 0.0,
                    "label": "0 - 1000",
                    "value": 3,
                    "count": 3
                  },
                  {
                    "from": 1000.0,
                    "to": 2000.0,
                    "key": 1000.0,
                    "label": "1000 - 2000",
                    "value": 2,
                    "count": 2
                  }
                ]
              },
              "links": null
            }
            """;

    private static final Logger logger = LoggerFactory.getLogger(AbstractCrudController.class);

    // ------------------------------------------------------------------------
    // Configurações e constantes
    // ------------------------------------------------------------------------

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String OPEN_API_BASE_PATH;

    @Value("${server.servlet.contextPath:}")
    private String CONTEXT_PATH;

    @Value("${praxis.query.by-ids.max:200}")
    private int BY_IDS_MAX;

    @Value("${praxis.pagination.max-size:200}")
    private int PAGINATION_MAX_SIZE;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    private boolean isHateoasEnabled() {
        String v = environment != null ? environment.getProperty("praxis.hateoas.enabled", "true") : "true";
        return Boolean.parseBoolean(v);
    }

    private static final String SCHEMAS_PATH = "/schemas";
    private static final String SCHEMAS_FILTERED_PATH = "/schemas/filtered";
    private static final String HDR = "X-Data-Version";

    /**
     * Base path detectado automaticamente a partir das anotações do controller.
     * Inicializado no método {@link #initializeBasePath()} após a construção.
     */
    private String detectedBasePath;

    /**
     * <h3>🔍 Auto-Detecção do Base Path</h3>
     * <p>Este método detecta automaticamente o base path do controller através das
     * anotações @RequestMapping ou @ApiResource, executado pelo Spring após a 
     * construção do bean (@PostConstruct).</p>
     * 
     * <h4>🎯 Estratégias de Detecção (ordem de prioridade):</h4>
     * <ol>
     *   <li><strong>🎯 @ApiResource.value[] / path[]:</strong> Extrai o primeiro path declarado na meta-anotação</li>
     *   <li><strong>📋 @RequestMapping.value[] / path[]:</strong> Extrai o primeiro path declarado no controller</li>
     *   <li><strong>⚠️ Configuração obrigatória:</strong> Emite warning se não encontrar e usa um placeholder explícito</li>
     * </ol>
     * 
     * <h4>🔄 Exemplos de Detecção:</h4>
 * <pre>
 * {@code @RequestMapping("/api/human-resources/funcionarios")}
 * → detectedBasePath = "/api/human-resources/funcionarios"
 * 
 * {@code @ApiResource("/api/human-resources/eventos-folha")}  
 * → detectedBasePath = "/api/human-resources/eventos-folha"
     * 
     * FuncionarioController sem anotações
 * → ⚠️ WARNING: Controller precisa usar {@code @RequestMapping} ou {@code @ApiResource}
 * </pre>
     * 
     * <h4>🔗 Integração Sistêmica:</h4>
     * <p>O path detectado é usado pelo DynamicSwaggerConfig para criar grupos OpenAPI
     * e pelo sistema HATEOAS para gerar links automaticamente. Esta consistência
     * garante que toda a documentação e navegação fique sincronizada.</p>
     * 
     * <h4>🚨 Importante:</h4>
     * <p>Controllers que estendem AbstractCrudController DEVEM usar {@code @RequestMapping} 
     * ou {@code @ApiResource}. Não há fallback automático para evitar configurações implícitas.</p>
     */
    @PostConstruct
    protected void initializeBasePath() {
        if (detectedBasePath == null) {
            // 🎯 ESTRATÉGIA 1: Detectar anotação @ApiResource diretamente
            ApiResource apiResource = AnnotationUtils.findAnnotation(getClass(), ApiResource.class);
            
            if (apiResource != null) {
                // 🎯 Prioridade 1: @ApiResource.value[]
                if (apiResource.value().length > 0) {
                    detectedBasePath = apiResource.value()[0];
                } 
                // 📋 Prioridade 2: @ApiResource.path[]
                else if (apiResource.path().length > 0) {
                    detectedBasePath = apiResource.path()[0];
                }
            } else {
                // 🔄 ESTRATÉGIA 2: Fallback para @RequestMapping diretamente
                RequestMapping requestMapping = AnnotationUtils.findAnnotation(getClass(), RequestMapping.class);
                
                if (requestMapping != null) {
                    // 🎯 Prioridade 1: @RequestMapping.value[]
                    if (requestMapping.value().length > 0) {
                        detectedBasePath = requestMapping.value()[0];
                    } 
                    // 📋 Prioridade 2: @RequestMapping.path[]
                    else if (requestMapping.path().length > 0) {
                        detectedBasePath = requestMapping.path()[0];
                    }
                }
            }

            // ⚠️ ESTRATÉGIA 3: Configuração obrigatória - sem fallback automático
            if (detectedBasePath == null) {
                logger.warn("⚠️ CONFIGURAÇÃO OBRIGATÓRIA: {} não possui @RequestMapping ou @ApiResource. " +
                    "Controllers que estendem AbstractCrudController DEVEM usar uma dessas anotações " +
                    "para definir o base path. Considere migrar para @ApiResource(ApiPaths.CONSTANT) " +
                    "para aproveitar os benefícios da resolução automática de grupos OpenAPI.",
                    getClass().getSimpleName());
                    
                // Define um path temporário para evitar NPE, mas claramente problemático
                detectedBasePath = "/CONFIGURACAO-PENDENTE/" + getClass().getSimpleName();
            } else {
                logger.debug("✅ Base path detectado automaticamente para {}: {}",
                    getClass().getSimpleName(), detectedBasePath);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Métodos abstratos obrigatórios
    // ------------------------------------------------------------------------

    /**
     * Retorna o serviço base (CRUD) que será usado internamente.
     *
     * @return instância do serviço CRUD
     */
    protected abstract BaseCrudService<E, D, ID, FD> getService();

    /**
     * Converte uma entidade JPA em seu DTO correspondente.
     *
     * @param entity a entidade a ser convertida
     * @return o DTO correspondente
     */
    protected abstract D toDto(E entity);

    /**
     * Converte um DTO em sua entidade JPA correspondente.
     *
     * @param dto o DTO a ser convertido
     * @return a entidade correspondente
     */
    protected abstract E toEntity(D dto);

    /**
     * Retorna a classe concreta do controller (ex.: TipoTelefoneController.class)
     * para uso no método methodOn(...) do HATEOAS.
     *
     * <p>Fornece uma implementação padrão baseada em {@link #getClass()},
     * mas pode ser sobrescrito caso o comportamento padrão não seja
     * adequado.</p>
     */
    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractCrudController<E, D, ID, FD>> getControllerClass() {
        return (Class<? extends AbstractCrudController<E, D, ID, FD>>) getClass();
    }

    /**
     * Extrai o identificador da entidade para construção de links HATEOAS.
     *
     * @param entity a entidade da qual extrair o ID
     * @return o identificador da entidade
     */
    protected abstract ID getEntityId(E entity);

    /**
     * Extrai o identificador do DTO para construção de links HATEOAS.
     *
     * @param dto o DTO do qual extrair o ID
     * @return o identificador do DTO
     */
    protected abstract ID getDtoId(D dto);

    /**
     * Returns the primary key field name used by the resource DTO.
     * Default is "id". Override in resource controllers when the identifier
     * property has a different name (e.g., "codigo").
     */
    protected String getIdFieldName() {
        try {
            Class<E> entityClass = getService() != null ? getService().getEntityClass() : null;
            if (entityClass != null) {
                Class<?> c = entityClass;
                while (c != null && c != Object.class) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.util.Arrays.stream(f.getAnnotations())
                                .anyMatch(a -> a.annotationType().getName().equals("jakarta.persistence.Id"))) {
                            return f.getName();
                        }
                    }
                    c = c.getSuperclass();
                }
            }
        } catch (Exception ignore) {
            // fallback below
        }
        return "id";
    }

    /**
     * Indica se o recurso é somente leitura. Subclasses podem sobrescrever.
     */
    protected boolean isReadOnlyResource() { return false; }

    /**
     * Retorna o base path do controller.
     *
     * <p>Por padrão, este método detecta automaticamente o path a partir das
     * anotações @RequestMapping ou @ApiResource. Pode ser sobrescrito se
     * necessário um comportamento customizado.</p>
     *
     * <p><strong>Detecção automática:</strong></p>
     * <ul>
     *   <li>Prioridade 1: valor/path da anotação {@code @ApiResource}</li>
     *   <li>Prioridade 2: valor/path da anotação {@code @RequestMapping}</li>
     *   <li>Sem fallback implícito por naming convention; ausência de anotação gera warning e placeholder explícito</li>
     * </ul>
     *
     * @return o base path do controller
     */
    protected String getBasePath() {
        if (detectedBasePath == null) {
            initializeBasePath();
        }
        return detectedBasePath;
    }

    /**
     * Permite definir manualmente o base path, sobrescrevendo a detecção automática.
     *
     * @param basePath o novo base path
     */
    protected void setBasePath(String basePath) {
        this.detectedBasePath = basePath;
        logger.info("Base path manualmente definido para {}: {}",
            getClass().getSimpleName(), basePath);
    }

    // ------------------------------------------------------------------------
    // Endpoints CRUD
    // ------------------------------------------------------------------------

    /**
     * Endpoint para filtrar entidades com paginação.
     *
     * <h4>Problema que resolve</h4>
     * <ul>
     *   <li>Prover paginação tradicional com ordenação consistente por padrão quando nenhum <code>sort</code> é enviado.</li>
     *   <li>Permitir injetar registros via <code>includeIds</code> na primeira página sem duplicar itens nas seguintes.</li>
     * </ul>
     *
     * <h4>Como funciona internamente</h4>
     * <ul>
     *   <li>Valida <code>size</code> ≤ <code>praxis.pagination.max-size</code>; 422 quando excede.</li>
     *   <li>Monta <code>Pageable</code> com {@link org.praxisplatform.uischema.util.PageableBuilder} e fallback de sort via {@link BaseCrudService#getDefaultSort()}.</li>
     *   <li>Constrói {@code Specification} via {@link org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder} a partir do DTO.</li>
     *   <li>Enriquece cada item com links HATEOAS através de {@link #toEntityModel(Object)} quando habilitado.</li>
     *   <li>Adiciona links de documentação de schema (request/response) e <code>X-Data-Version</code> se disponível.</li>
     * </ul>
     *
     * @param filterDTO   DTO contendo os critérios de filtro
     * @param page        número da página (inicia em 0)
     * @param size        quantidade de registros por página
     * @param includeIds  IDs adicionais que devem aparecer no topo da primeira página
     *                    (repetir nas páginas subsequentes para evitar duplicação,
     *                    sem nova injeção)
     * @param queryParams parâmetros de query adicionais (ex.: {@code sort})
     * @return página de entidades filtradas com links HATEOAS
     * @throws org.springframework.web.server.ResponseStatusException quando o parâmetro
     * <code>size</code> excede o limite configurado (422)
     *
     * <h4>Erros comuns</h4>
     * <ul>
     *   <li>422: <code>size</code> acima do limite configurado.</li>
     *   <li>400: validação do body (campos inválidos).</li>
     * </ul>
     */
    @PostMapping("/filter")
    @Operation(
            summary = "Filtrar registros",
            description = "Aplica filtros aos registros com base nos critérios fornecidos no DTO. Suporta paginação.",
            parameters = {
                    @Parameter(
                            name = "pageable",
                            description = "Informações de paginação, como página e tamanho",
                            required = false
                    ),
                    @Parameter(
                            name = "includeIds",
                            description = "IDs que devem aparecer no topo da primeira página. Repetir nas páginas seguintes para evitar duplicação, sem reinjeção",
                            required = false
                    )
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Lista de registros filtrados retornada com sucesso.",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = RestApiResponse.class)
                            )
                    )
            }
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(mediaType = "application/json")
    )
    public ResponseEntity<RestApiResponse<Page<EntityModel<D>>>> filter(
            @RequestBody FD filterDTO,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "includeIds", required = false) List<ID> includeIds,
            @RequestParam MultiValueMap<String, String> queryParams
    ) {
        if (size > PAGINATION_MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + PAGINATION_MAX_SIZE);
        }
        // Avoid Spring's default comma-splitting when binding to collections
        List<String> sort = queryParams.get("sort");
        Pageable pageable = PageableBuilder.from(page, size, sort, getService().getDefaultSort());
        Page<D> result = getService().filterMappedWithIncludeIds(filterDTO, pageable, includeIds, this::toDto);
        Page<EntityModel<D>> entityModels = result.map(this::toEntityModel);

        Links links = Links.of(
                linkToAll(),
                linkToUiSchema("/filter", "post", "request"),
                linkToUiSchema("/filter", "post", "response")
        );

        var response = RestApiResponse.success(entityModels, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.ok(), response);
    }

    /**
     * Endpoint para paginação baseada em cursor (keyset), oferecendo resultados estáveis
     * durante listas longas.
     *
     * <h4>Problema que resolve</h4>
     * <ul>
     *   <li>Evitar saltos/itens repetidos entre navegações quando dados mudam.</li>
     * </ul>
     *
     * <h4>Como funciona internamente</h4>
     * <ul>
     *   <li>Valida <code>size</code> ≤ <code>praxis.pagination.max-size</code>.</li>
     *   <li>Monta {@link Sort} com {@link org.praxisplatform.uischema.util.SortBuilder} e fallback do service.</li>
     *   <li>Delegado para {@link BaseCrudService#filterByCursorMapped(GenericFilterDTO, org.springframework.data.domain.Sort, String, String, int, java.util.function.Function)}.</li>
     *   <li>Mapeia conteúdo para <code>EntityModel&lt;D&gt;</code> e preserva <code>next</code>/<code>prev</code>/<code>size</code>.</li>
     * </ul>
     *
     * @param filterDTO   critérios de filtro
     * @param after       cursor para avançar
     * @param before      cursor para retroceder
     * @param size        quantidade de registros
     * @param queryParams parâmetros de query adicionais (ex.: {@code sort})
     * @return página baseada em cursor
     * @throws org.springframework.web.server.ResponseStatusException quando <code>size</code>
     * excede o limite (422) ou a operação não é suportada (501)
     *
     * <h4>Erros comuns</h4>
     * <ul>
     *   <li>501: service não implementa keyset.</li>
     *   <li>422: <code>size</code> acima do limite.</li>
     * </ul>
     */
    @PostMapping("/filter/cursor")
    @Operation(summary = "Filtrar com paginação por cursor",
            description = "Retorna registros usando keyset pagination. Entidades sem suporte respondem 501.")
    public ResponseEntity<RestApiResponse<CursorPage<EntityModel<D>>>> filterByCursor(
            @RequestBody FD filterDTO,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam MultiValueMap<String, String> queryParams) {
        if (size > PAGINATION_MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + PAGINATION_MAX_SIZE);
        }
        List<String> sort = queryParams.get("sort");
        Sort sortObj = SortBuilder.from(sort, getService().getDefaultSort());
        CursorPage<D> result;
        try {
            result = getService().filterByCursorMapped(filterDTO, sortObj, after, before, size, this::toDto);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }

        CursorPage<EntityModel<D>> mapped = new CursorPage<>(
                result.content().stream().map(this::toEntityModel).toList(),
                result.next(),
                result.prev(),
                result.size()
        );

        Links links = Links.of(
                linkToAll(),
                linkToUiSchema("/filter/cursor", "post", "request"),
                linkToUiSchema("/filter/cursor", "post", "response")
        );

        var response = RestApiResponse.success(mapped, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.ok(), response);
    }

    /**
     * Localiza a posição de um registro considerando filtro e ordenação.
     *
     * <h4>Problema que resolve</h4>
     * <ul>
     *   <li>Permitir que a UI navegue diretamente até a página onde um ID aparece.</li>
     * </ul>
     *
     * <h4>Como funciona internamente</h4>
     * <ul>
     *   <li>Valida <code>size</code> ≤ <code>praxis.pagination.max-size</code>.</li>
     *   <li>Calcula {@link Sort} e chama {@link BaseCrudService#locate(GenericFilterDTO, org.springframework.data.domain.Sort, Object)}.</li>
     *   <li>Monta {@link LocateResponse} com índice absoluto e página (<code>index/size</code>).</li>
     * </ul>
     *
     * @param filterDTO   critérios de filtro
     * @param id          identificador do registro de interesse
     * @param size        tamanho de página utilizado para derivar a página a partir do índice absoluto
     * @param queryParams parâmetros de query adicionais (ex.: {@code sort})
     * @return resposta com {@link LocateResponse} contendo índice e página
     * @throws org.springframework.web.server.ResponseStatusException quando <code>size</code>
     *                            excede o limite (422) ou a operação não é suportada (501)
     *
     * <h4>Erros comuns</h4>
     * <ul>
     *   <li>501: service não implementa localização.</li>
     *   <li>422: <code>size</code> acima do limite.</li>
     * </ul>
     */
    @PostMapping("/locate")
    @Operation(summary = "Localizar posição de registro",
            description = "Retorna o índice absoluto e a página de um ID com base no filtro e sort.")
    public ResponseEntity<LocateResponse> locate(
            @RequestBody FD filterDTO,
            @RequestParam("id") ID id,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam MultiValueMap<String, String> queryParams) {
        if (size > PAGINATION_MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + PAGINATION_MAX_SIZE);
        }
        List<String> sort = queryParams.get("sort");
        Sort sortObj = SortBuilder.from(sort, getService().getDefaultSort());
        var position = getService().locate(filterDTO, sortObj, id);
        if (position.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
        long pos = position.getAsLong();
        long page = size > 0 ? pos / size : 0;
        return withVersion(ResponseEntity.ok(), new LocateResponse(pos, page));
    }

    /**
     * Calcula agregacoes de group-by sobre o conjunto filtrado do recurso.
     *
     * <p>
     * Este endpoint e a superficie canonica para contagens e metricas agregadas por buckets,
     * sem exigir que cada aplicacao exponha rotas ad hoc para analytics basicos. Ele reaproveita
     * o mesmo vocabulário de filtro do recurso principal e retorna buckets normalizados para consumo
     * por tabelas analiticas, cards de resumo e graficos.
     * </p>
     *
     * @param request request canonico contendo filtro, campo de agrupamento e metrica agregada
     * @return envelope com {@link GroupByStatsResponse}
     * @throws org.springframework.web.server.ResponseStatusException {@code 400} quando o request
     * e invalido ou {@code 501} quando o recurso nao suporta stats
     */
    @PostMapping("/stats/group-by")
    @Operation(summary = "Group-by stats sobre o conjunto filtrado",
            description = "Retorna buckets agregados canônicos sobre o conjunto filtrado do recurso.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "groupByCount",
                                    summary = "Agrupamento por status com COUNT",
                                    value = GROUP_BY_STATS_REQUEST_EXAMPLE
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Group-by calculado com sucesso",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = RestApiResponseGroupByStatsResponse.class),
                                    examples = @ExampleObject(
                                            name = "groupByCountResponse",
                                            summary = "Buckets agregados por status",
                                            value = GROUP_BY_STATS_RESPONSE_EXAMPLE
                                    )
                            )
                    ),
                    @ApiResponse(responseCode = "400", description = "Request de stats inválido"),
                    @ApiResponse(responseCode = "501", description = "Stats não suportado pelo recurso")
            })
    public ResponseEntity<RestApiResponse<GroupByStatsResponse>> groupByStats(
            @RequestBody GroupByStatsRequest<FD> request) {
        GroupByStatsResponse result;
        try {
            result = getService().groupByStats(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }

        Links links = Links.of(
                linkToFilter(),
                linkToUiSchema("/stats/group-by", "post", "request"),
                linkToUiSchema("/stats/group-by", "post", "response")
        );

        var response = RestApiResponse.success(result, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.ok(), response);
    }

    /**
     * Calcula serie temporal agregada sobre o conjunto filtrado.
     *
     * <p>
     * A resposta segue a superficie canonica de time-series da plataforma, permitindo dashboards
     * e componentes metadata-driven sem reimplementar contratos por dominio. O filtro principal do
     * recurso continua valendo, e a agregacao e controlada por granularidade, campo temporal e metrica.
     * </p>
     *
     * @param request request canonico com filtro, campo temporal, granularidade e metrica
     * @return envelope com {@link TimeSeriesStatsResponse}
     * @throws org.springframework.web.server.ResponseStatusException {@code 400} quando o request
     * e invalido ou {@code 501} quando o recurso nao suporta stats
     */
    @PostMapping("/stats/timeseries")
    @Operation(summary = "Time-series stats sobre o conjunto filtrado",
            description = "Retorna série temporal canônica agregada sobre o conjunto filtrado do recurso.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "timeSeriesSum",
                                    summary = "Série diária com SUM e fillGaps",
                                    value = TIME_SERIES_STATS_REQUEST_EXAMPLE
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Série temporal calculada com sucesso",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = RestApiResponseTimeSeriesStatsResponse.class),
                                    examples = @ExampleObject(
                                            name = "timeSeriesSumResponse",
                                            summary = "Pontos de série temporal com datas ISO",
                                            value = TIME_SERIES_STATS_RESPONSE_EXAMPLE
                                    )
                            )
                    ),
                    @ApiResponse(responseCode = "400", description = "Request de stats inválido"),
                    @ApiResponse(responseCode = "501", description = "Stats não suportado pelo recurso")
            })
    public ResponseEntity<RestApiResponse<TimeSeriesStatsResponse>> timeSeriesStats(
            @RequestBody TimeSeriesStatsRequest<FD> request) {
        TimeSeriesStatsResponse result;
        try {
            result = getService().timeSeriesStats(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }

        Links links = Links.of(
                linkToFilter(),
                linkToUiSchema("/stats/timeseries", "post", "request"),
                linkToUiSchema("/stats/timeseries", "post", "response")
        );

        var response = RestApiResponse.success(result, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.ok(), response);
    }

    /**
     * Calcula distribuicoes agregadas sobre o conjunto filtrado do recurso.
     *
     * <p>
     * Esta operacao cobre cenarios como histogramas, faixas e distribuicoes quantitativas usando
     * o contrato estatistico canonico da plataforma. Ela evita proliferacao de endpoints customizados
     * e mantém a mesma semantica de filtro do restante da superficie do recurso.
     * </p>
     *
     * @param request request canonico com filtro, campo numerico e definicao da distribuicao
     * @return envelope com {@link DistributionStatsResponse}
     * @throws org.springframework.web.server.ResponseStatusException {@code 400} quando o request
     * e invalido ou {@code 501} quando o recurso nao suporta stats
     */
    @PostMapping("/stats/distribution")
    @Operation(summary = "Distribution stats sobre o conjunto filtrado",
            description = "Retorna distribuicoes canonicas sobre o conjunto filtrado do recurso.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "distributionHistogram",
                                    summary = "Distribuição por histograma com COUNT",
                                    value = DISTRIBUTION_STATS_REQUEST_EXAMPLE
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Distribuição calculada com sucesso",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = RestApiResponseDistributionStatsResponse.class),
                                    examples = @ExampleObject(
                                            name = "distributionHistogramResponse",
                                            summary = "Buckets de histograma canônicos",
                                            value = DISTRIBUTION_STATS_RESPONSE_EXAMPLE
                                    )
                            )
                    ),
                    @ApiResponse(responseCode = "400", description = "Request de stats inválido"),
                    @ApiResponse(responseCode = "501", description = "Stats não suportado pelo recurso")
            })
    public ResponseEntity<RestApiResponse<DistributionStatsResponse>> distributionStats(
            @RequestBody DistributionStatsRequest<FD> request) {
        DistributionStatsResponse result;
        try {
            result = getService().distributionStats(request);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        Links links = Links.of(
                linkToAll(),
                linkToUiSchema("/stats/distribution", "post", "request"),
                linkToUiSchema("/stats/distribution", "post", "response")
        );

        var response = RestApiResponse.success(result, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.ok(), response);
    }

    // -------------------------------------------------------------------------
    // Métodos de CRUD
    // -------------------------------------------------------------------------

    /**
     * Lista todos os registros aplicando @DefaultSortColumn quando nenhum sort é enviado.
     * @return envelope de resposta com a lista de entidades como {@link EntityModel}
     */
    @GetMapping("/all")
    @Operation(summary = "Listar todos os registros", description = "Retorna todos os registros, aplicando @DefaultSortColumn quando nenhum sort é enviado.")
    public ResponseEntity<RestApiResponse<List<EntityModel<D>>>> getAll() {
        List<D> dtos = getService().findAllMapped(this::toDto);
        List<EntityModel<D>> entityModels = dtos.stream().map(this::toEntityModel).toList();

        Links links = Links.of(
                linkToFilter(),
                linkToFilterCursor(),
                linkToUiSchema("/all", "get", "response")
        );

        var response = RestApiResponse.success(entityModels, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.ok(), response);
    }

    /**
     * Recupera múltiplos registros pelos seus identificadores em uma única chamada.
     * <p>
     * Ideal para interfaces corporativas que precisam pré-carregar registros
     * selecionados. Retorna uma lista vazia quando nenhum ID é informado e
     * preserva a ordem dos parâmetros recebidos. O número máximo de IDs
     * permitidos pode ser configurado pela propriedade
     * {@code praxis.query.by-ids.max} (padrão: 200).
     * </p>
     *
     * @param ids lista de identificadores a serem buscados
     * @return lista de DTOs na mesma ordem dos IDs solicitados
     * @throws ResponseStatusException se a quantidade de IDs exceder o limite configurado
     */
    @GetMapping("/by-ids")
    @Operation(summary = "Buscar registros por IDs", description = "Retorna múltiplos registros pelos IDs fornecidos.")
    public ResponseEntity<List<D>> getByIds(@RequestParam(name = "ids", required = false) List<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return withVersion(ResponseEntity.ok(), List.of());
        }
        if (ids.size() > BY_IDS_MAX) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum number of IDs exceeded: " + BY_IDS_MAX);
        }
        List<D> list = getService().findAllByIdMapped(ids, this::toDto);
        var byId = list.stream().collect(Collectors.toMap(this::getDtoId, Function.identity()));
        List<D> ordered = ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        return withVersion(ResponseEntity.ok(), ordered);
    }

    /**
     * Retorna opções paginadas para popular selects.
     *
     * <h4>Problema que resolve</h4>
     * <ul>
     *   <li>Fornecer payload leve (id/label) com filtros e paginação para componentes de seleção.</li>
     * </ul>
     *
     * <h4>Como funciona internamente</h4>
     * <ul>
     *   <li>Valida <code>size</code> ≤ <code>praxis.pagination.max-size</code>.</li>
     *   <li>Aplica fallback de ordenação do service quando <code>sort</code> ausente.</li>
     *   <li>Label resolvido por {@code @OptionLabel} ou heurísticas do {@link BaseCrudService#getOptionMapper()}.</li>
     * </ul>
     *
     * <h4>Uso em DTOs (@UISchema)</h4>
     * <p>Para referenciar este endpoint em um campo de seleção, anote o DTO com
     * {@code @UISchema} configurando {@code endpoint}, {@code valueField} e {@code displayField}:</p>
     * <pre>{@code
     * // 1) Consumindo OptionDTO diretamente (payload leve)
     * @UISchema(
     *   controlType = FieldControlType.SELECT,
     *   endpoint = ApiPaths.Catalog.CATEGORIAS + "/options/filter",
     *   valueField = "id",     // OptionDTO.id
     *   displayField = "label" // OptionDTO.label (via @OptionLabel/heurísticas)
     * )
     * private Long categoriaId;
     *
     * // 2) Alternativa: usar /filter do recurso (DTO completo) e mapear os campos
     * @UISchema(
     *   controlType = FieldControlType.SELECT,
     *   endpoint = ApiPaths.Catalog.CATEGORIAS + "/filter", // ✅ sempre /filter
     *   valueField = "id",
     *   displayField = "nome"
     * )
     * private Long categoriaId;
     * }
     * </pre>
     * <p>Dica: para combos dependentes, a propriedade {@code endpoint} suporta interpolação
     * de parâmetros com {@code ${campo}}.</p>
     *
     * @param filterDTO   critérios de filtro
     * @param page        número da página (0‑based)
     * @param size        tamanho da página
     * @param queryParams parâmetros adicionais (ex.: {@code sort})
     * @return página de {@link OptionDTO}
     * @throws org.springframework.web.server.ResponseStatusException quando <code>size</code> excede o limite (422)
     */
  @PostMapping("/options/filter")
  @Operation(summary = "Listar opções filtradas", description = "Retorna projeções id/label para selects.")
  public ResponseEntity<Page<OptionDTO<ID>>> filterOptions(
          @RequestBody FD filterDTO,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam MultiValueMap<String, String> queryParams) {
        if (size > PAGINATION_MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + PAGINATION_MAX_SIZE);
        }
        List<String> sort = queryParams.get("sort");
        Pageable pageable = PageableBuilder.from(page, size, sort, getService().getDefaultSort());
      Page<OptionDTO<ID>> result = getService().filterOptions(filterDTO, pageable);
      return withVersion(ResponseEntity.ok(), result);
  }

  /**
   * Retorna opcoes paginadas a partir de uma fonte canonica registrada em {@code option-sources}.
   *
   * <p>
   * Diferentemente de {@link #filterOptions(GenericFilterDTO, int, int, MultiValueMap)}, aqui a
   * origem das opcoes nao precisa coincidir com a entidade principal do recurso. Isso permite
   * compor combos e seletores derivados sem quebrar a semantica canonica da plataforma.
   * </p>
   *
   * <p>
   * A consulta suporta filtro do recurso atual, busca textual opcional, inclusao de IDs
   * previamente selecionados e ordenacao paginada.
   * </p>
   *
   * @param sourceKey chave da fonte canonica registrada
   * @param filterDTO filtro principal do recurso
   * @param search busca textual opcional aplicada pela implementacao da fonte
   * @param includeIds IDs que devem ser preservados no resultado para reidratacao de selecao
   * @param page pagina atual
   * @param size tamanho da pagina
   * @param queryParams parametros adicionais, como {@code sort}
   * @return pagina de {@link OptionDTO}
   * @throws org.springframework.web.server.ResponseStatusException {@code 404} quando a fonte
   * nao existe, {@code 422} quando o tamanho excede o limite ou {@code 501} quando a capacidade
   * nao e suportada
   */
  @PostMapping("/option-sources/{sourceKey}/options/filter")
  @Operation(summary = "Listar opções filtradas por fonte derivada",
          description = "Retorna projeções id/label para uma fonte canônica registrada em option-sources.")
  public ResponseEntity<Page<OptionDTO<Object>>> filterOptionSourceOptions(
          @PathVariable String sourceKey,
          @RequestBody FD filterDTO,
          @RequestParam(name = "search", required = false) String search,
          @RequestParam(name = "includeIds", required = false) List<String> includeIds,
          @RequestParam(name = "page", defaultValue = "0") int page,
          @RequestParam(name = "size", defaultValue = "20") int size,
          @RequestParam MultiValueMap<String, String> queryParams) {
      if (size > PAGINATION_MAX_SIZE) {
          throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                  "Maximum page size exceeded: " + PAGINATION_MAX_SIZE);
      }
      List<String> sort = queryParams.get("sort");
      Pageable pageable = PageableBuilder.from(page, size, sort, getService().getDefaultSort());
      try {
          Page<OptionDTO<Object>> result = getService().filterOptionSourceOptions(
                  sourceKey,
                  filterDTO,
                  search,
                  pageable,
                  includeIds == null ? List.of() : List.copyOf(includeIds)
          );
          return withVersion(ResponseEntity.ok(), result);
      } catch (IllegalArgumentException ex) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
      } catch (UnsupportedOperationException ex) {
          throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
      }
  }

    /**
     * Retorna opções (id/label) para uma coleção de IDs, preservando a ordem solicitada.
     *
     * <h4>Uso em DTOs (@UISchema)</h4>
     * <p>Use em conjunto com {@code /options/filter} para reidratar valores já salvos
     * quando a UI precisa montar o valor inicial de selects multivalorados:</p>
     * <pre>{@code
     * // Busca dinâmica enquanto o usuário digita
     * endpoint = ApiPaths.Catalog.CATEGORIAS + "/options/filter"
     * valueField = "id"
     * displayField = "label"
     *
     * // Reidratação (IDs → id/label) ao abrir a tela
     * GET ApiPaths.Catalog.CATEGORIAS + "/options/by-ids?ids=10&ids=7"
     * }
     * </pre>
     *
     * @param ids lista de identificadores (opcional)
     * @return lista de {@link OptionDTO} na mesma ordem dos IDs
     * @throws org.springframework.web.server.ResponseStatusException quando a quantidade de IDs excede o limite configurado (422)
     */
  @GetMapping("/options/by-ids")
  @Operation(summary = "Buscar opções por IDs", description = "Retorna projeções id/label na ordem solicitada. Limite configurável por 'praxis.query.by-ids.max'.")
  public ResponseEntity<List<OptionDTO<ID>>> getOptionsByIds(
          @RequestParam(name = "ids", required = false) List<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return withVersion(ResponseEntity.ok(), List.of());
        }
        if (ids.size() > BY_IDS_MAX) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum number of IDs exceeded: " + BY_IDS_MAX);
      }
      return withVersion(ResponseEntity.ok(), getService().byIdsOptions(ids));
  }

  /**
   * Reidrata opcoes por IDs a partir de uma fonte canonica registrada em {@code option-sources}.
   *
   * <p>
   * O endpoint e voltado principalmente para carregamento inicial de selects e multiselects
   * quando a UI possui apenas os IDs persistidos e precisa recuperar os pares {@code id/label}
   * na mesma ordem da selecao original.
   * </p>
   *
   * @param sourceKey chave da fonte canonica registrada
   * @param ids identificadores a reidratar
   * @return lista de {@link OptionDTO} na ordem solicitada
   * @throws org.springframework.web.server.ResponseStatusException {@code 404} quando a fonte
   * nao existe, {@code 422} quando a quantidade de IDs excede o limite ou {@code 501} quando
   * a capacidade nao e suportada
   */
  @GetMapping("/option-sources/{sourceKey}/options/by-ids")
  @Operation(summary = "Buscar opções por IDs em fonte derivada",
          description = "Retorna projeções id/label para uma fonte canônica registrada em option-sources, preservando a ordem solicitada.")
  public ResponseEntity<List<OptionDTO<Object>>> getOptionSourceOptionsByIds(
          @PathVariable String sourceKey,
          @RequestParam(name = "ids", required = false) List<String> ids) {
      if (ids == null || ids.isEmpty()) {
          return withVersion(ResponseEntity.ok(), List.of());
      }
      if (ids.size() > BY_IDS_MAX) {
          throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                  "Maximum number of IDs exceeded: " + BY_IDS_MAX);
      }
      try {
          return withVersion(ResponseEntity.ok(), getService().byIdsOptionSourceOptions(sourceKey, List.copyOf(ids)));
      } catch (IllegalArgumentException ex) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
      } catch (UnsupportedOperationException ex) {
          throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
      }
  }

    /**
     * Recupera um registro pelo seu identificador.
     *
     * <p>
     * Alem do payload principal, o endpoint pode anexar links HATEOAS para navegacao entre
     * leitura, filtros, atualizacao e exclusao, de acordo com as capacidades habilitadas
     * pelo recurso. Isso torna a resposta adequada tanto para integradores HTTP quanto para
     * consumidores metadata-driven.
     * </p>
     *
     * @param id identificador do registro
     * @return envelope de resposta com o DTO e links relevantes
     * @throws jakarta.persistence.EntityNotFoundException quando o registro nao e encontrado
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Buscar registro por ID",
            description = "Retorna um registro pelo ID com links HATEOAS úteis. 404 quando não encontrado.",
            parameters = {
                    @Parameter(
                            name = "id",
                            description = "ID do registro a ser buscado",
                            required = true,
                            example = "123"
                    )
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Registro encontrado com sucesso.",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = Object.class) // Substituir pelo DTO genérico, se aplicável
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Registro não encontrado para o ID fornecido."
                    )
            }
    )
    public ResponseEntity<RestApiResponse<D>> getById(@PathVariable ID id) {
        // Se não existir, o service pode lançar ResourceNotFoundException
        D dto = getService().findByIdMapped(id, this::toDto);

        java.util.List<Link> linkList = new java.util.ArrayList<>();
        linkList.add(linkToSelf(id));
        linkList.add(linkToAll());
        linkList.add(linkToFilter());
        linkList.add(linkToFilterCursor());
        if (allowUpdate()) linkList.add(linkToUpdate(id));
        if (allowDelete()) linkList.add(linkToDelete(id));
        linkList.add(linkToUiSchema("/{id}", "get", "response"));
        Links links = Links.of(linkList);

        var response = RestApiResponse.success(dto, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.ok(), response);
    }

    /**
     * Cria um novo registro a partir do DTO informado.
     *
     * <p>
     * O fluxo converte o DTO em entidade, delega a persistencia ao service e retorna
     * {@code 201 Created} com header {@code Location} apontando para o recurso criado.
     * O corpo segue o envelope canonico {@link RestApiResponse}, alinhado ao restante da API.
     * </p>
     *
     * @param dto DTO de entrada validado
     * @return {@code 201 Created} com {@code Location} derivado da entidade persistida e envelope de resposta contendo o DTO salvo
     */
    @PostMapping
    @Operation(summary = "Criar novo registro", description = "Cria um novo registro. Retorna 201 + Location + envelope padronizado.")
    public ResponseEntity<RestApiResponse<D>> create(@jakarta.validation.Valid @RequestBody D dto) {
        E entityToSave = toEntity(dto);
        BaseCrudService.SavedResult<ID, D> saved = getService().saveResultMapped(entityToSave, this::toDto);
        ID newId = saved.id();
        D savedDto = saved.body();
        Link selfLink = linkToSelf(newId);

        Links links = Links.of(
                selfLink,
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToDelete(newId),
                linkToUiSchema("/", "post", "request")
        );

        var response = RestApiResponse.success(savedDto, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.created(selfLink.toUri()), response);
    }

    /**
     * Atualiza um registro existente.
     *
     * <p>
     * O endpoint reutiliza o mesmo mapeamento DTO → entidade do fluxo de criacao, mas delega a
     * persistencia ao service com o identificador explicito do recurso. Em respostas bem-sucedidas,
     * o cliente recebe o DTO atualizado dentro do envelope padronizado da plataforma.
     * </p>
     *
     * @param id identificador do registro a atualizar
     * @param dto DTO com os novos dados, sujeito a validacao
     * @return envelope de resposta com o DTO atualizado
     * @throws jakarta.persistence.EntityNotFoundException quando o registro nao e encontrado
     */
    @PutMapping("/{id}")
    @Operation(
            summary = "Atualizar registro existente",
            description = "Atualiza um registro pelo ID. 404 quando não encontrado. Retorna envelope padronizado.",
            parameters = {
                    @Parameter(
                            name = "id",
                            description = "ID do registro a ser atualizado",
                            required = true,
                            example = "123"
                    )
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Registro atualizado com sucesso.",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = Object.class) // Substituir pelo DTO genérico, se aplicável
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Registro não encontrado para o ID fornecido."
                    )
            }
    )
    public ResponseEntity<RestApiResponse<D>> update(@PathVariable ID id, @jakarta.validation.Valid @RequestBody D dto) {
        E entityToUpdate = toEntity(dto);
        D updatedDto = getService().updateMapped(id, entityToUpdate, this::toDto);

        Links links = Links.of(
                linkToSelf(id),
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToUpdate(id),
                linkToDelete(id),
                linkToUiSchema("/{id}", "put", "request")
        );

        var response = RestApiResponse.success(updatedDto, isHateoasEnabled() ? links : null);
        return withVersion(ResponseEntity.ok(), response);
    }

    /**
     * Exclui um registro pelo seu identificador.
     *
     * <p>
     * A semantica segue o padrao REST classico da plataforma: em caso de sucesso retorna
     * {@code 204 No Content}; em caso de ausencia do recurso, a implementacao do service pode
     * sinalizar o erro apropriado para a camada HTTP.
     * </p>
     *
     * @param id identificador do registro a excluir
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Excluir registro",
            description = "Remove o registro pelo ID fornecido. Retorna 204 se bem-sucedido ou 404 se não encontrado.",
            parameters = {
                    @Parameter(
                            name = "id",
                            description = "ID do registro a ser excluído",
                            required = true,
                            example = "123"
                    )
            },
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Registro excluído com sucesso."
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Registro não encontrado."
                    )
            }
    )
    public ResponseEntity<Void> delete(@PathVariable ID id) {
        getService().deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Exclui multiplos registros pelos seus identificadores.
     *
     * <p>
     * O endpoint e util para operacoes de selecao em massa vindas de grids e listas administrativas.
     * A lista de IDs deve ser enviada no corpo da requisicao; quando nula ou vazia, a resposta e
     * {@code 400 Bad Request} para evitar sucesso silencioso de uma operacao vazia.
     * </p>
     *
     * @param ids lista de IDs a excluir
     * @return {@code 204 No Content} quando a exclusao em lote e aceita
     */
    @DeleteMapping("/batch")
    @Operation(
            summary = "Excluir registros em lote",
            description = "Remove múltiplos registros pelos IDs fornecidos.",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Registros excluídos com sucesso."
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Lista de IDs vazia ou nula."
                    )
            }
    )
    public ResponseEntity<Void> deleteBatch(@RequestBody List<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        getService().deleteAllById(ids);
        return ResponseEntity.noContent().build();
    }

    /**
     * Redireciona para o endpoint de schemas filtrados do recurso atual.
     *
     * <p>
     * Em vez de duplicar a logica de resolucao de schema em cada controller, esta operacao
     * calcula a URL correta para {@code /schemas/filtered} com base no path e na operacao
     * representativa do recurso, preservando a semantica metadata-driven canonica da plataforma.
     * </p>
     *
     * <p>
     * Isso permite que consumidores descubram o contrato x-ui do recurso diretamente a partir da
     * superficie do proprio controller, sem precisar reconstruir manualmente query parameters.
     * </p>
     *
     * @return resposta {@code 302 Found} com {@code Location} apontando para o schema do recurso
     */
    @GetMapping(SCHEMAS_PATH)
    @Operation(
            summary = "Obter esquema da entidade para configuração dinâmica",
            description = """
                        Este endpoint retorna informações detalhadas sobre o esquema de dados da entidade atual.
                        Os dados retornados são baseados na documentação OpenAPI e incluem configurações específicas para interfaces dinâmicas, 
                        como formulários e grids.
                    
                        O endpoint redireciona automaticamente para o controlador responsável por processar 
                        e filtrar os esquemas OpenAPI, fornecendo os parâmetros corretos para o recurso atual.
                    """,
            responses = {
                    @ApiResponse(
                            responseCode = "302",
                            description = "Redireciona para o endpoint de esquemas com os parâmetros corretos."
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Caso o esquema da entidade não seja encontrado."
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Erro interno ao processar a solicitação."
                    )
            }
    )
    public ResponseEntity<Void> getSchema() {
        // Constrói o link para o endpoint de metadados
        Link metadataLink = linkToUiSchema("/all", "get", "response");

        // Retorna um redirecionamento HTTP para o link montado
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(metadataLink.getHref()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Métodos auxiliares de HATEOAS
    // -------------------------------------------------------------------------
    protected EntityModel<D> toEntityModel(D dto) {
        ID id = getDtoId(dto);
        java.util.List<Link> links = new java.util.ArrayList<>();
        links.add(linkToSelf(id));
        if (allowCreate()) links.add(linkToCreate());
        if (allowUpdate()) links.add(linkToUpdate(id));
        if (allowDelete()) links.add(linkToDelete(id));
        return EntityModel.of(dto, links);
    }

    /**
     * Link para GET /{id}.
     */
    protected Link linkToSelf(ID id) {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).getById(id)
        ).withSelfRel();
    }

    /**
     * Constrói a resposta anexando o cabeçalho {@code X-Data-Version} quando a
     * implementação do serviço disponibiliza essa informação. O cabeçalho permite
     * que aplicações corporativas utilizem estratégias de cache HTTP para evitar
     * carregamentos desnecessários de listagens.
     */
    private <T> ResponseEntity<T> withVersion(ResponseEntity.BodyBuilder builder, T body) {
        getService().getDatasetVersion().ifPresent(v -> builder.header(HDR, v));
        return builder.body(body);
    }

    /**
     * Link para GET /all.
     */
    protected Link linkToAll() {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).getAll()
        ).withRel("all");
    }

    /**
     * Link para GET /filter.
     */
    protected Link linkToFilter() {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).filter(null, 0, 0, null, null)
        ).withRel("filter");
    }

    /**
     * Link para POST /filter/cursor.
     */
    protected Link linkToFilterCursor() {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).filterByCursor(null, null, null, 0, null)
        ).withRel("filter-cursor");
    }

    /**
     * Link para POST /.
     */
    protected Link linkToCreate() {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).create(null)
        ).withRel("create");
    }

    /**
     * Link para PUT /{id}.
     */
    protected Link linkToUpdate(ID id) {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).update(id, null)
        ).withRel("update");
    }

    /**
     * Link para DELETE /{id}.
     */
    protected Link linkToDelete(ID id) {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).delete(id)
        ).withRel("delete");
    }

    // Capability toggles for HATEOAS links. By default, full CRUD; read-only subclasses disable write links.
    protected boolean allowCreate() { return !isReadOnlyResource(); }
    protected boolean allowUpdate() { return !isReadOnlyResource(); }
    protected boolean allowDelete() { return !isReadOnlyResource(); }

    /**
     * Link para documentação (ex.: Swagger UI).
     */
    protected Link linkToDocs() {
        String docsUrl = String.format("%s%s", OPEN_API_BASE_PATH, getBasePath());
        return Link.of(docsUrl, "docs");
    }

    /**
     * Gera um link HATEOAS para a documentação filtrada de um schema específico da API, baseado no caminho e na operação HTTP fornecidos.
     * <p>
     * Este método constrói uma URL relativa que aponta para o endpoint de documentação filtrada, permitindo que os consumidores
     * da API acessem a descrição detalhada do schema correspondente a uma operação específica em um determinado caminho.
     *
     * <p><strong>Exemplo de Uso:</strong></p>
     * <pre>{@code
     * // Gerar link para a operação GET no caminho "/dados-pessoa-fisica/all"
     * Link docsLink = linkToUiSchema("/all", "get", "response");
     * }</pre>
     *
     * @param methodPath O caminho específico do método dentro da API para o qual a documentação do schema é necessária.
     *                   Deve começar com "/" e representar um dos endpoints existentes (por exemplo, "/filter", "/all", "/{id}").
     *                   <p><strong>Exemplos:</strong></p>
     *                   <ul>
     *                       <li><code>"/filter"</code> para a operação de filtro de registros.</li>
     *                       <li><code>"/all"</code> para listar todos os registros.</li>
     *                       <li><code>"/{id}"</code> para operações específicas de um registro identificado por ID.</li>
     *                   </ul>
     * @param operation  A operação HTTP associada ao caminho fornecido. Deve ser um dos métodos válidos do HTTP, como
     *                   <code>"get"</code>, <code>"post"</code>, <code>"put"</code>, <code>"delete"</code>, etc.
     * @param schemaType Tipo de schema desejado: <code>response</code> (padrão) ou <code>request</code>.
     *                   <p><strong>Exemplos:</strong></p>
     *                   <ul>
     *                       <li><code>"get"</code> para operações de leitura.</li>
     *                       <li><code>"post"</code> para operações de criação.</li>
     *                       <li><code>"put"</code> para operações de atualização.</li>
     *                       <li><code>"delete"</code> para operações de exclusão.</li>
     *                   </ul>
     * @return Um objeto {@link Link} contendo a URL relativa para a documentação filtrada do schema correspondente à operação e caminho fornecidos.
     * O rel deste link é definido como <code>"schema"</code>, indicando que ele aponta para a documentação do schema.
     * @throws IllegalArgumentException Se o {@code methodPath} estiver vazio ou malformado.
     * @throws IllegalStateException    Se ocorrer um erro ao construir a URL para a documentação filtrada.
     * @see Link
     * @see UriComponentsBuilder
     */
    protected Link linkToUiSchema(String methodPath, String operation, String schemaType) {
        // Validação básica dos parâmetros
        if (methodPath == null || methodPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameter 'methodPath' must not be null or blank.");
        }
        if (operation == null || operation.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameter 'operation' must not be null or blank.");
        }
        if (schemaType == null || schemaType.trim().isEmpty()) {
            schemaType = "response";
        }

        // Constrói o caminho completo combinando o path base com o método específico
        String fullPath = getBasePath();
        if (!methodPath.startsWith("/")) {
            fullPath += "/" + methodPath;
        } else {
            fullPath += methodPath;
        }

        try {
            // Utiliza UriComponentsBuilder para construir a URL relativa para a documentação filtrada
            String docsPath = UriComponentsBuilder.fromPath(CONTEXT_PATH + SCHEMAS_FILTERED_PATH)
                    .queryParam("path", fullPath)
                    .queryParam("operation", operation.toLowerCase())
                    .queryParam("schemaType", schemaType.toLowerCase())
                    .queryParam("idField", getIdFieldName())
                    .queryParam("readOnly", Boolean.toString(isReadOnlyResource()))
                    .build()
                    .toUriString();

            // Retorna o Link HATEOAS com rel definido como "schema"
            return Link.of(docsPath, "schema");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the filtered schema link.", e);
        }
    }

}
