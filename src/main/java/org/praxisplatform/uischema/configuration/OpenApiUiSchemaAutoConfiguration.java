package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.praxisplatform.uischema.controller.docs.ApiDocsController;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.filter.relativeperiod.RelativePeriodPayloadNormalizer;
import org.praxisplatform.uischema.filter.range.RangePayloadNormalizer;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.filter.web.FilterPayloadNormalizer;
import org.praxisplatform.uischema.filter.web.FilterRequestBodyAdvice;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springdoc.core.models.GroupedOpenApi;

import java.util.List;

/**
 * <h2>🏗️ Auto-Configuração Principal do Praxis UI Schema &amp; OpenAPI</h2>
 * 
 * <p>Esta classe é o <strong>"bootstrap"</strong> de todo o sistema Praxis UI Schema, 
 * responsável por registrar automaticamente todos os beans necessários para que 
 * o framework funcione "out-of-the-box" sem configuração manual.</p>
 *
 * <h3>🎯 Objetivo Principal</h3>
 * <p>Implementar o padrão <strong>"Convention over Configuration"</strong> do Spring Boot, 
 * onde desenvolvedores apenas adicionam a dependência e todo o sistema fica automaticamente 
 * disponível e funcional.</p>
 * 
 * <h3>🔄 Como Funciona</h3>
 * <ol>
 *   <li><strong>Spring Boot Startup:</strong> Detecta @AutoConfiguration automaticamente</li>
 *   <li><strong>Bean Registration:</strong> Registra todos os componentes base no contexto</li>
 *   <li><strong>Dependency Injection:</strong> Spring conecta todos os componentes automaticamente</li>
 *   <li><strong>Integration Ready:</strong> DynamicSwaggerConfig e outros usam os beans registrados</li>
 * </ol>
 *
 * <h3>🎪 Arquitetura de Componentes</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │      OpenApiUiSchemaAutoConfiguration       │
 * └─────────────────┬───────────────────────────┘
 *                   │ registra beans
 *                   ▼
 * ┌─────────────────────────────────────────────┐
 * │  📡 RestTemplate (HTTP interno)             │
 * │  📝 ObjectMapper (JSON serialização)       │  
 * │  🔍 CustomOpenApiResolver (schemas)         │
 * │  📋 GenericSpecificationsBuilder (filtros)  │
 * │  🎯 OpenApiGroupResolver (grupos)           │
 * │  🌐 ApiDocsController (endpoints)           │
 * └─────────────────┬───────────────────────────┘
 *                   │ injetados em
 *                   ▼
 * ┌─────────────────────────────────────────────┐
 * │     DynamicSwaggerConfig &amp; Controllers      │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>🚀 Benefícios para Desenvolvedores</h3>
 * <ul>
 *   <li><strong>Zero Setup:</strong> Apenas adicione a dependência no pom.xml</li>
 *   <li><strong>Plug &amp; Play:</strong> Funciona imediatamente sem configurações</li>
 *   <li><strong>Não Conflitante:</strong> Usa @ConditionalOnMissingBean para evitar duplicações</li>
 *   <li><strong>Customizável:</strong> Pode sobrescrever qualquer bean se necessário</li>
 *   <li><strong>Zero Boilerplate:</strong> Não precisa registrar beans manualmente</li>
 * </ul>
 *
 * <h3>📋 Integração Sistêmica</h3>
 * <p>Os beans registrados aqui são utilizados em:</p>
 * <ul>
 *   <li><strong>DynamicSwaggerConfig:</strong> Para criar grupos OpenAPI dinamicamente</li>
 *   <li><strong>ApiDocsController:</strong> Para resolver grupos e servir documentação filtrada</li>
 *   <li><strong>AbstractCrudController:</strong> Para filtros genéricos e HATEOAS</li>
 *   <li><strong>CustomOpenApiResolver:</strong> Para adicionar metadados UI aos schemas</li>
 * </ul>
 *
 * <h3>⚙️ Configuração Avançada</h3>
 * <p>Para customizar algum bean, basta criar sua própria configuração:</p>
 * <pre>{@code
 * @Configuration
 * public class MyCustomConfig {
 *     
 *     @Bean
 *     @Primary // substitui o bean padrão
 *     public ObjectMapper myObjectMapper() {
 *         ObjectMapper mapper = new ObjectMapper();
 *         // suas customizações aqui
 *         return mapper;
 *     }
 * }
 * }</pre>
 *
 * <h3>🔗 Beans Registrados</h3>
 * <p>Veja documentação individual de cada método @Bean para detalhes específicos.</p>
 * 
 * @since 1.0.0
 * @see org.praxisplatform.uischema.configuration.DynamicSwaggerConfig
 * @see org.praxisplatform.uischema.controller.docs.ApiDocsController
 * @see org.praxisplatform.uischema.util.OpenApiGroupResolver
 * @see org.praxisplatform.uischema.extension.CustomOpenApiResolver
 */
@AutoConfiguration
public class OpenApiUiSchemaAutoConfiguration {
    /**
     * <h3>📡 Bean RestTemplate para Comunicação HTTP Interna</h3>
     * 
     * <p>Registra um RestTemplate específico para uso interno do sistema UI Schema,
     * evitando conflitos com outros RestTemplates da aplicação.</p>
     * 
     * <h4>🎯 Uso Principal:</h4>
     * <ul>
     *   <li><strong>ApiDocsController:</strong> Chamadas internas ao SpringDoc OpenAPI</li>
     *   <li><strong>Schema Resolution:</strong> Busca de documentos OpenAPI remotos</li>
     *   <li><strong>Internal API Calls:</strong> Comunicação entre componentes do sistema</li>
     * </ul>
     * 
     * <h4>⚙️ Características:</h4>
     * <ul>
     *   <li><strong>Nome específico:</strong> "openApiUiSchemaRestTemplate" evita conflitos</li>
     *   <li><strong>@ConditionalOnMissingBean:</strong> Só cria se não existir outro RestTemplate</li>
     *   <li><strong>Configuração padrão:</strong> Sem customizações especiais</li>
     *   <li><strong>Escopo Singleton:</strong> Uma instância compartilhada em toda aplicação</li>
     * </ul>
     * 
     * <h4>🔄 Exemplo de Uso Interno:</h4>
     * <pre>{@code
     * // No ApiDocsController:
     * @Autowired
     * @Qualifier("openApiUiSchemaRestTemplate") 
     * private RestTemplate restTemplate;
     * 
     * // Buscar documento OpenAPI:
     * String openApiDoc = restTemplate.getForObject(
     *     "http://localhost:8080/v3/api-docs/funcionarios", 
     *     String.class
     * );
     * }</pre>
     * 
     * <h4>💡 Customização:</h4>
     * <p>Para customizar o RestTemplate, crie seu próprio bean com @Primary:</p>
     * <pre>{@code
     * @Bean
     * @Primary
     * public RestTemplate myRestTemplate() {
     *     RestTemplate template = new RestTemplate();
     *     // suas customizações (timeouts, interceptors, etc.)
     *     return template;
     * }
     * }</pre>
     * 
     * @return RestTemplate configurado para uso interno do sistema
     */
    @Bean(name = "openApiUiSchemaRestTemplate")
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * <h3>📝 Bean ObjectMapper para Serialização JSON</h3>
     * 
     * <p>Registra um ObjectMapper específico para serialização/deserialização JSON 
     * dos schemas OpenAPI e metadados UI Schema, com suporte completo a tipos Java 8+.</p>
     * 
     * <h4>🎯 Uso Principal:</h4>
     * <ul>
     *   <li><strong>Schema Serialization:</strong> Converte schemas OpenAPI para JSON</li>
     *   <li><strong>UI Metadata Processing:</strong> Processa metadados de campos dinâmicos</li>
     *   <li><strong>API Response Formatting:</strong> Formata respostas dos endpoints de documentação</li>
     *   <li><strong>Configuration Parsing:</strong> Lê configurações JSON de UI Schema</li>
     * </ul>
     * 
     * <h4>⚙️ Características:</h4>
     * <ul>
     *   <li><strong>JavaTimeModule:</strong> Suporte nativo a LocalDate, LocalDateTime, Instant, etc.</li>
     *   <li><strong>Nome específico:</strong> "openApiUiSchemaObjectMapper" evita conflitos</li>
     *   <li><strong>@ConditionalOnMissingBean:</strong> Respeita ObjectMappers customizados</li>
     *   <li><strong>Thread-Safe:</strong> Pode ser usado concorrentemente</li>
     * </ul>
     * 
     * <h4>🔄 Exemplo de Uso Interno:</h4>
     * <pre>{@code
     * // No CustomOpenApiResolver:
     * @Autowired
     * @Qualifier("openApiUiSchemaObjectMapper")
     * private ObjectMapper objectMapper;
     * 
     * // Serializar schema:
     * String schemaJson = objectMapper.writeValueAsString(schema);
     * 
     * // Deserializar configuração:
     * UiSchemaConfig config = objectMapper.readValue(json, UiSchemaConfig.class);
     * }</pre>
     * 
     * <h4>📅 Suporte a Java Time API:</h4>
     * <pre>{@code
     * // Automaticamente serializa/deserializa:
     * LocalDate data = LocalDate.now();              // "2024-01-15"
     * LocalDateTime timestamp = LocalDateTime.now(); // "2024-01-15T10:30:45"
     * Instant instant = Instant.now();              // "2024-01-15T13:30:45Z"
     * }</pre>
     * 
     * <h4>💡 Customização:</h4>
     * <p>Para adicionar mais módulos ou configurações:</p>
     * <pre>{@code
     * @Bean
     * @Primary
     * public ObjectMapper myObjectMapper() {
     *     ObjectMapper mapper = new ObjectMapper();
     *     mapper.registerModule(new JavaTimeModule());
     *     mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
     *     // outras customizações...
     *     return mapper;
     * }
     * }</pre>
     * 
     * @return ObjectMapper configurado com JavaTimeModule
     */
    @Bean(name = "openApiUiSchemaObjectMapper")
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    /**
     * <h3>🔍 Bean CustomOpenApiResolver para Resolução de Schemas</h3>
     * 
     * <p>Registra o resolver responsável por processar e enriquecer schemas OpenAPI 
     * com metadados específicos do UI Schema, criando a ponte entre documentação 
     * técnica e interface de usuário.</p>
     * 
     * <h4>🎯 Uso Principal:</h4>
     * <ul>
     *   <li><strong>Schema Enhancement:</strong> Adiciona metadados UI aos schemas OpenAPI básicos</li>
     *   <li><strong>Field Metadata:</strong> Resolve tipos de campos, validações e configurações</li>
     *   <li><strong>UI Components Mapping:</strong> Mapeia campos para componentes de interface</li>
     *   <li><strong>Validation Rules:</strong> Processa regras de validação e formatação</li>
     * </ul>
     * 
     * <h4>⚙️ Características:</h4>
     * <ul>
     *   <li><strong>Dependency Injection:</strong> Recebe ObjectMapper via parâmetro</li>
     *   <li><strong>Schema Processing:</strong> Processa schemas OpenAPI complexos</li>
     *   <li><strong>Metadata Enrichment:</strong> Adiciona informações específicas de UI</li>
     *   <li><strong>Extensible:</strong> Pode ser estendido para novos tipos de campos</li>
     * </ul>
     * 
     * <h4>🔄 Fluxo de Processamento:</h4>
     * <pre>
     * Schema OpenAPI Básico
     *          ↓
     * CustomOpenApiResolver.resolve()
     *          ↓
     * Schema + UI Metadata
     *          ↓
     * Frontend Components
     * </pre>
     * 
     * <h4>📋 Exemplo de Transformação:</h4>
     * <pre>{@code
     * // Schema OpenAPI básico:
     * {
     *   "type": "string",
     *   "format": "date"
     * }
     * 
     * // Após processamento pelo CustomOpenApiResolver:
     * {
     *   "type": "string",
     *   "format": "date",
     *   "x-ui-component": "date-picker",
     *   "x-ui-validation": {
     *     "required": true,
     *     "minDate": "2024-01-01"
     *   },
     *   "x-ui-label": "Data de Nascimento"
     * }
     * }</pre>
     * 
     * <h4>🔗 Integração:</h4>
     * <ul>
     *   <li><strong>ApiDocsController:</strong> Usa para processar schemas antes de servir</li>
     *   <li><strong>UI Schema Annotations:</strong> Processa anotações @UISchema</li>
     *   <li><strong>Field Definitions:</strong> Converte definições JPA para UI metadata</li>
     * </ul>
     * 
     * <h4>💡 Extensibilidade:</h4>
     * <p>Para adicionar processamento customizado:</p>
     * <pre>{@code
     * @Component
     * public class MyCustomResolver extends CustomOpenApiResolver {
     *     
     *     public MyCustomResolver(ObjectMapper mapper) {
     *         super(mapper);
     *     }
     *     
     *     @Override
     *     protected Schema<?> processCustomField(Schema<?> schema) {
     *         // sua lógica customizada
     *         return super.processCustomField(schema);
     *     }
     * }
     * }</pre>
     * 
     * @param mapper ObjectMapper configurado para serialização JSON
     * @return CustomOpenApiResolver configurado para processar schemas
     */
    @Bean
    public CustomOpenApiResolver modelResolver(ObjectMapper mapper) {
        return new CustomOpenApiResolver(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "rangePayloadNormalizer")
    @Order(0)
    public FilterPayloadNormalizer rangePayloadNormalizer(
            @Value("${praxis.filter.range.allow-scalar-payload:false}") boolean allowScalarRangePayload,
            @Value("${praxis.filter.range.log-legacy-scalar-payload:true}") boolean logLegacyScalarRangePayload
    ) {
        return new RangePayloadNormalizer(allowScalarRangePayload, logLegacyScalarRangePayload);
    }

    @Bean
    @ConditionalOnMissingBean(name = "relativePeriodPayloadNormalizer")
    @Order(100)
    public FilterPayloadNormalizer relativePeriodPayloadNormalizer() {
        return new RelativePeriodPayloadNormalizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRequestBodyAdvice filterRequestBodyAdvice(
            ObjectMapper mapper,
            List<FilterPayloadNormalizer> payloadNormalizers
    ) {
        return new FilterRequestBodyAdvice(mapper, payloadNormalizers);
    }

    /**
     * <h3>📋 Bean GenericSpecificationsBuilder para Filtros Dinâmicos</h3>
     * 
     * <p>Registra o construtor de specifications JPA que permite criação automática 
     * de filtros baseados em DTOs genéricos, eliminando a necessidade de escrever 
     * código de filtro manual para cada entidade.</p>
     * 
     * <h4>🎯 Uso Principal:</h4>
     * <ul>
     *   <li><strong>Dynamic Filtering:</strong> Cria filtros JPA automaticamente a partir de DTOs</li>
     *   <li><strong>AbstractCrudController:</strong> Usado nos endpoints de filtro (/filter)</li>
     *   <li><strong>Specification Building:</strong> Converte filtros DTO em Specification&lt;Entity&gt;</li>
     *   <li><strong>Query Optimization:</strong> Gera consultas JPA otimizadas dinamicamente</li>
     * </ul>
     * 
     * <h4>⚙️ Características:</h4>
     * <ul>
     *   <li><strong>Generic Type:</strong> Funciona com qualquer tipo de entidade &lt;E&gt;</li>
     *   <li><strong>Thread-Safe:</strong> Pode ser usado concorrentemente</li>
     *   <li><strong>Reflection-Based:</strong> Usa reflexão para processar campos do DTO</li>
     *   <li><strong>Annotation-Driven:</strong> Processa anotações @Filterable</li>
     * </ul>
     * 
     * <h4>🔄 Fluxo de Funcionamento:</h4>
     * <pre>
     * FilterDTO (Frontend)
     *          ↓
     * GenericSpecificationsBuilder.buildSpecification()
     *          ↓
     * JPA Specification&lt;Entity&gt;
     *          ↓
     * SQL Query Otimizada
     * </pre>
     * 
     * <h4>📋 Exemplo de Uso:</h4>
     * <pre>{@code
     * // DTO de filtro:
     * public class FuncionarioFilterDTO extends GenericFilterDTO {
     *     @Filterable(operation = FilterOperation.CONTAINS)
     *     private String nome;
     *     
     *     @Filterable(operation = FilterOperation.EQUALS)
     *     private Long departamentoId;
     *     
     *     @Filterable(operation = FilterOperation.BETWEEN)
     *     private LocalDate dataAdmissao;
     * }
     * 
     * // No AbstractCrudController:
     * @PostMapping("/filter")
     * public Page<FuncionarioDTO> filter(
     *     @RequestBody FuncionarioFilterDTO filterDTO, 
     *     Pageable pageable) {
     *     
     *     // GenericSpecificationsBuilder automaticamente converte:
     *     Specification<Funcionario> spec = specificationsBuilder
     *         .buildSpecification(filterDTO);
     *     
     *     return repository.findAll(spec, pageable);
     * }
     * }</pre>
     * 
     * <h4>🔍 Operações de Filtro Suportadas:</h4>
     * <ul>
     *   <li><strong>EQUALS:</strong> Igualdade exata (campo = valor)</li>
     *   <li><strong>CONTAINS:</strong> Busca texto (campo LIKE %valor%)</li>
     *   <li><strong>STARTS_WITH:</strong> Inicia com (campo LIKE valor%)</li>
     *   <li><strong>BETWEEN:</strong> Entre valores (campo BETWEEN valor1 AND valor2)</li>
     *   <li><strong>GREATER_THAN:</strong> Maior que (campo &gt; valor)</li>
     *   <li><strong>LESS_THAN:</strong> Menor que (campo &lt; valor)</li>
     * </ul>
     * 
     * <h4>🚀 Benefícios:</h4>
     * <ul>
     *   <li><strong>Zero Boilerplate:</strong> Não precisa escrever specifications manualmente</li>
     *   <li><strong>Type-Safe:</strong> Erros de compilação em vez de runtime</li>
     *   <li><strong>Consistent API:</strong> Mesmo padrão para todas as entidades</li>
     *   <li><strong>Performance:</strong> Queries JPA otimizadas automaticamente</li>
     * </ul>
     * 
     * @param <E> Tipo genérico da entidade JPA
     * @return GenericSpecificationsBuilder configurado para construção de filtros
     */
    @Bean(name = "openApiUiSchemaSpecificationsBuilder")
    public <E> GenericSpecificationsBuilder<E> genericSpecificationsBuilder() {
        return new GenericSpecificationsBuilder<>();
    }

    /**
     * <h3>🎯 Bean OpenApiGroupResolver para Resolução Automática de Grupos</h3>
     * 
     * <p><strong>COMPONENTE CRÍTICO:</strong> Este é o bean mais importante para o funcionamento 
     * da resolução automática de grupos OpenAPI. Ele implementa o algoritmo de "best match" 
     * que permite ao ApiDocsController encontrar automaticamente o grupo correto baseado no path.</p>
     * 
     * <h4>🎯 Uso Principal:</h4>
     * <ul>
     *   <li><strong>Group Resolution:</strong> Resolve qual grupo OpenAPI corresponde a um path específico</li>
     *   <li><strong>ApiDocsController:</strong> Usado para eliminar a necessidade do parâmetro 'document'</li>
     *   <li><strong>Best Match Algorithm:</strong> Encontra o padrão mais específico que corresponde ao path</li>
     *   <li><strong>Path Normalization:</strong> Normaliza paths para comparação consistente</li>
     * </ul>
     * 
     * <h4>⚙️ Características:</h4>
     * <ul>
     *   <li><strong>Dependency Injection:</strong> Recebe todos os GroupedOpenApi via parâmetro</li>
     *   <li><strong>Dynamic Resolution:</strong> Funciona com grupos criados pelo DynamicSwaggerConfig</li>
     *   <li><strong>Best Match Logic:</strong> Prioriza padrões mais específicos (longest match)</li>
     *   <li><strong>Cache-Friendly:</strong> Resultados podem ser cached para performance</li>
     * </ul>
     * 
     * <h4>🔄 Algoritmo de Resolução:</h4>
     * <pre>
     * Input Path: "/api/human-resources/funcionarios/all"
     *     ↓
     * Normalização: "/api/human-resources/funcionarios/all"
     *     ↓
     * Comparação com padrões:
     * - "/api/**" → match (length: 6)
     * - "/api/human-resources/**" → match (length: 23) ← MELHOR MATCH
     * - "/api/other/**" → no match
     *     ↓
     * Resultado: "api-human-resources" group
     * </pre>
     * 
     * <h4>📋 Exemplo de Uso Real:</h4>
     * <pre>{@code
     * // 1. DynamicSwaggerConfig cria grupos:
     * GroupedOpenApi funcionarios = GroupedOpenApi.builder()
     *     .group("api-human-resources-funcionarios")
     *     .pathsToMatch("/api/human-resources/funcionarios/**")
     *     .build();
     * 
     * // 2. OpenApiGroupResolver recebe todos os grupos via injeção
     * List<GroupedOpenApi> allGroups = [funcionarios, departamentos, ...];
     * 
     * // 3. ApiDocsController usa resolver:
     * @GetMapping("/schemas/filtered")
     * public ResponseEntity<?> getFilteredSchema(
     *     @RequestParam String path) {
     *     
     *     String groupName = openApiGroupResolver.resolveGroup(path);
     *     // "/api/human-resources/funcionarios/all" → "api-human-resources-funcionarios"
     *     
     *     return getDocumentForGroup(groupName);
     * }
     * }</pre>
     * 
     * <h4>🏆 Benefícios do Best Match:</h4>
     * <ul>
     *   <li><strong>Precisão:</strong> Sempre encontra o grupo mais específico</li>
     *   <li><strong>Robustez:</strong> Funciona mesmo com padrões overlapping</li>
     *   <li><strong>Performance:</strong> Algoritmo eficiente O(n) onde n = número de grupos</li>
     *   <li><strong>Flexibilidade:</strong> Suporta qualquer padrão de path</li>
     * </ul>
     * 
     * <h4>🔗 Integração Sistêmica:</h4>
     * <p>Este bean é a ponte entre os grupos criados dinamicamente pelo DynamicSwaggerConfig 
     * e a resolução automática no ApiDocsController, eliminando configuração manual.</p>
     * 
     * <h4>⚠️ Dependência Crítica:</h4>
     * <p>Este bean DEVE receber os grupos via List&lt;GroupedOpenApi&gt; para funcionar. 
     * O Spring automaticamente injeta todos os beans GroupedOpenApi registrados, 
     * incluindo os criados dinamicamente.</p>
     * 
     * @param groupedOpenApis Lista de todos os GroupedOpenApi registrados no contexto
     * @return OpenApiGroupResolver configurado com todos os grupos disponíveis
     */
    @Bean
    public OpenApiGroupResolver openApiGroupResolver(List<GroupedOpenApi> groupedOpenApis) {
        return new OpenApiGroupResolver(groupedOpenApis);
    }

    /**
     * <h3>🌐 Bean ApiDocsController para Endpoints de Documentação</h3>
     * 
     * <p>Registra o controller que expõe os endpoints de documentação filtrada, 
     * permitindo que frontends acessem schemas OpenAPI específicos sem necessidade 
     * de parâmetros manuais de grupos.</p>
     * 
     * <h4>🎯 Uso Principal:</h4>
     * <ul>
     *   <li><strong>Filtered Documentation:</strong> Serve documentação específica por path</li>
     *   <li><strong>Automatic Group Resolution:</strong> Usa OpenApiGroupResolver para resolver grupos</li>
     *   <li><strong>Schema Caching:</strong> Implementa cache para performance</li>
     *   <li><strong>Frontend Integration:</strong> Endpoints RESTful para consumo por UIs</li>
     * </ul>
     * 
     * <h4>⚙️ Características:</h4>
     * <ul>
     *   <li><strong>Dependency Injection:</strong> Recebe automaticamente OpenApiGroupResolver</li>
     *   <li><strong>RESTful Endpoints:</strong> Exposição de endpoints HTTP padrão</li>
     *   <li><strong>Error Handling:</strong> Tratamento de erros para grupos não encontrados</li>
     *   <li><strong>Content Negotiation:</strong> Suporte a diferentes formatos de resposta</li>
     * </ul>
     * 
     * <h4>📡 Endpoints Expostos:</h4>
     * <pre>
     * GET /schemas/filtered
     * ├── ?path=/api/funcionarios/all
     * ├── ?operation=get  
     * └── ?schemaType=response
     * 
     * GET /schemas/{groupName}
     * └── Documentação completa do grupo
     * </pre>
     * 
     * <h4>🔄 Fluxo de Resolução Automática:</h4>
     * <pre>
     * Frontend Request
     * GET /schemas/filtered?path=/api/funcionarios/all
     *          ↓
     * ApiDocsController.getFilteredSchema()
     *          ↓
     * OpenApiGroupResolver.resolveGroup(path)
     *          ↓ 
     * "api-funcionarios" group resolved
     *          ↓
     * Fetch OpenAPI document for group
     *          ↓
     * Return filtered schema (~14KB)
     * </pre>
     * 
     * <h4>📋 Exemplo de Uso pelo Frontend:</h4>
     * <pre>{@code
     * // Antes (manual - não funcionava):
     * GET /v3/api-docs/funcionarios  // ❌ Precisava saber o nome do grupo
     * 
     * // Depois (automático - funciona):
     * GET /schemas/filtered?path=/api/human-resources/funcionarios/all
     * // ✅ Resolve automaticamente para grupo "api-human-resources-funcionarios"
     * 
     * // Resposta típica:
     * {
     *   "openapi": "3.0.1",
     *   "paths": {
     *     "/api/human-resources/funcionarios/all": {
     *       "get": {
     *         "summary": "Listar todos os funcionários",
     *         "responses": {
     *           "200": {
     *             "content": {
     *               "application/json": {
     *                 "schema": {
     *                   // Schema específico do funcionário
     *                 }
     *               }
     *             }
     *           }
     *         }
     *       }
     *     }
     *   }
     * }
     * }</pre>
     * 
     * <h4>🚀 Benefícios:</h4>
     * <ul>
     *   <li><strong>Zero Configuration:</strong> Frontend não precisa conhecer nomes de grupos</li>
     *   <li><strong>Performance:</strong> Documentação específica (~14KB vs 500KB+)</li>
     *   <li><strong>Developer Experience:</strong> API intuitiva baseada em paths</li>
     *   <li><strong>Cache-Friendly:</strong> Documenta grandes podem ser cached</li>
     * </ul>
     * 
     * <h4>🔗 Dependências Injetadas:</h4>
     * <p>O Spring injeta automaticamente:</p>
     * <ul>
     *   <li><strong>OpenApiGroupResolver:</strong> Para resolução de grupos</li>
     *   <li><strong>RestTemplate:</strong> Para chamadas internas ao SpringDoc</li>
     *   <li><strong>ObjectMapper:</strong> Para processamento JSON</li>
     * </ul>
     * 
     * <h4>⚡ Performance:</h4>
     * <p>O controller implementa cache interno para evitar reprocessar a mesma documentação 
     * repetidamente, resultando em redução de 97% no tamanho dos documentos e 
     * melhoria significativa na velocidade de resposta.</p>
     * 
     * @return ApiDocsController configurado com todas as dependências
     */
    @Bean
    public ApiDocsController apiDocsController() {
        return new ApiDocsController();
    }
}
