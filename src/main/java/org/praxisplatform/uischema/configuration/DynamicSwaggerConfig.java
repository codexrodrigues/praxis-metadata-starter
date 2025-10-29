package org.praxisplatform.uischema.configuration;

import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractCrudController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <h1>🎯 Configuração Dinâmica de Grupos OpenAPI com Estratégia Dupla</h1>
 * 
 * <p>Cria automaticamente grupos OpenAPI otimizados para performance usando uma estratégia dupla:
 * <strong>Grupos Individuais Ultra-Específicos</strong> para CRUDs + <strong>Grupos Agregados por Contexto</strong> via @ApiGroup.</p>
 * 
 * <h2>🎯 Problema Resolvido</h2>
 * <p>Antes desta implementação, era necessário registrar grupos OpenAPI manualmente e especificar parâmetros 
 * 'document' no ApiDocsController. Esta classe resolve automaticamente ambos os problemas com performance otimizada.</p>
 *
 * <h2>🚀 Estratégia Dupla de Grupos</h2>
 * 
 * <h3>📊 1. Grupos Individuais Ultra-Específicos</h3>
 * <ul>
 *   <li><strong>Escopo:</strong> Apenas controllers que estendem {@code AbstractCrudController}</li>
 *   <li><strong>Performance:</strong> ~3-5KB por documento (ultra-rápido)</li>
 *   <li><strong>Uso:</strong> Consultas específicas como {@code /schemas/filtered?path=/api/human-resources/eventos-folha/all}</li>
 * </ul>
 * 
 * <h3>🏷️ 2. Grupos Agregados por Contexto</h3>
 * <ul>
 *   <li><strong>Escopo:</strong> QUALQUER controller com anotação {@code @ApiGroup}</li>
 *   <li><strong>Performance:</strong> ~50-100KB por documento (ainda otimizado)</li>
 *   <li><strong>Uso:</strong> Visualização de contextos completos no Swagger UI</li>
 * </ul>
 *
 * <h2>🔄 Fluxo de Funcionamento</h2>
 * <ol>
 *   <li><strong>Startup da Aplicação:</strong> @PostConstruct é executado automaticamente</li>
 *   <li><strong>Escaneamento Dual:</strong> 
 *       <ul>
 *         <li>1ª passada: Identifica {@code AbstractCrudController} para grupos individuais</li>
 *         <li>2ª passada: Identifica QUALQUER controller com {@code @ApiGroup} para grupos agregados</li>
 *       </ul>
 *   </li>
 *   <li><strong>Extração de Paths:</strong> Usa {@code AnnotationUtils} para detectar @ApiResource/@RequestMapping</li>
 *   <li><strong>Registro Inteligente:</strong> Evita duplicação de beans e otimiza padrões agregados</li>
 * </ol>
 *
 * <h2>📋 Exemplos de Uso</h2>
 * <pre>{@code
 * // ✅ CRUD Controller - Cria grupo individual + participa do agregado
 * @ApiResource("/api/human-resources/funcionarios")
 * @ApiGroup("human-resources") 
 * public class FuncionarioController extends AbstractCrudController<...> {
 *     // Grupo individual: "api-human-resources-funcionarios" (ultra-específico)
 *     // Grupo agregado: "recursos-humanos" (contexto completo)
 * }
 * 
 * // ✅ Bulk Controller - Apenas participa do grupo agregado
 * @ApiResource("/api/human-resources/funcionarios")
 * @ApiGroup("human-resources-bulk")
 * public class FuncionarioBulkController extends AbstractBulkController<...> {
 *     // Grupo agregado: "recursos-humanos-bulk" (contexto bulk)
 * }
 * 
 * // ✅ Qualquer Controller - Participa apenas do grupo agregado
 * @RestController
 * @RequestMapping("/api/custom/reports")
 * @ApiGroup("relatorios")
 * public class CustomReportController {
 *     // Grupo agregado: "relatorios" (contexto customizado)
 * }
 * }</pre>
 *
 * <h2>📊 Resultado Típico</h2>
 * <p>Para uma aplicação com 8 controllers CRUD e 8 controllers Bulk:</p>
 * <pre>
 * 📋 Grupos Individuais (8):
 * ├── api-human-resources-funcionarios     (3KB - ultra-rápido)
 * ├── api-human-resources-cargos          (3KB - ultra-rápido) 
 * ├── api-human-resources-departamentos   (3KB - ultra-rápido)
 * └── ... (5 mais)
 * 
 * 🏷️ Grupos Agregados (2):
 * ├── recursos-humanos        (50KB - 8 controllers CRUD)
 * └── recursos-humanos-bulk   (30KB - 8 controllers Bulk)
 * 
 * 📈 Total: 10 grupos vs documento completo (500KB+)
 * </pre>
 *
 * <h2>🚀 Benefícios</h2>
 * <ul>
 *   <li><strong>Performance Extrema:</strong> Grupos individuais ~99% menores que documento completo</li>
 *   <li><strong>Flexibilidade Total:</strong> Qualquer controller pode participar de contextos via @ApiGroup</li>
 *   <li><strong>Semântica Clara:</strong> Grupos individuais para CRUDs, agregados para contextos</li>
 *   <li><strong>Zero Configuração:</strong> Detecção automática de anotações @ApiResource/@RequestMapping</li>
 *   <li><strong>Integração Perfeita:</strong> ApiDocsController resolve grupos automaticamente</li>
 * </ul>
 *
 * <h2>⚙️ Configuração Necessária</h2>
 * <p>Esta classe deve estar incluída no @ComponentScan da PraxisMetadataAutoConfiguration:</p>
 * <pre>{@code
 * @ComponentScan(basePackages = {"org.praxisplatform.uischema.configuration"})
 * }</pre>
 *
 * @see org.praxisplatform.uischema.annotation.ApiGroup
 * @see org.praxisplatform.uischema.annotation.ApiResource
 * @see org.praxisplatform.uischema.controller.docs.ApiDocsController
 * @see org.praxisplatform.uischema.util.OpenApiGroupResolver
 */
@Configuration
public class DynamicSwaggerConfig {

    private static final Logger logger = LoggerFactory.getLogger(DynamicSwaggerConfig.class);

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ConfigurableListableBeanFactory beanFactory;

    // Injeta o bean específico para evitar ambiguidade
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * Configuração para validar se controllers que estendem AbstractCrudController usam @ApiResource.
     * 
     * <h4>📋 Valores possíveis:</h4>
     * <ul>
     *   <li><strong>WARN (padrão):</strong> Apenas emite warnings no log</li>
     *   <li><strong>FAIL:</strong> Falha o startup da aplicação</li>
     *   <li><strong>IGNORE:</strong> Desabilita a validação completamente</li>
     * </ul>
     * 
     * <h4>⚙️ Configuração:</h4>
     * <pre>
     * # application.properties
     * praxis.openapi.validation.api-resource-required=WARN
     * </pre>
     */
    @Value("${praxis.openapi.validation.api-resource-required:WARN}")
    private String apiResourceValidationMode;

    /**
     * <h3>🎯 Método Principal - Criação Dinâmica com Estratégia Dupla</h3>
     * <p>Este método é executado automaticamente durante o startup da aplicação (@PostConstruct)
     * e implementa a estratégia dupla de criação de grupos:</p>
     * 
     * <h4>📊 1ª Passada - Grupos Individuais Ultra-Específicos:</h4>
     * <ul>
     *   <li>Escaneia apenas controllers que estendem {@code AbstractCrudController}</li>
     *   <li>Cria grupos individuais baseados no path completo (ex: "api-human-resources-funcionarios")</li>
     *   <li>Performance ultra-otimizada: ~3-5KB por documento</li>
     * </ul>
     * 
     * <h4>🏷️ 2ª Passada - Grupos Agregados por Contexto:</h4>
     * <ul>
     *   <li>Escaneia TODOS os controllers em busca de anotação {@code @ApiGroup}</li>
     *   <li>Agrupa controllers por contexto de negócio (ex: "recursos-humanos", "recursos-humanos-bulk")</li>
     *   <li>Calcula padrões agregados inteligentes que englobam múltiplos paths</li>
     * </ul>
     * 
     * <h4>📊 Exemplo de Saída no Log:</h4>
     * <pre>
     * Total de handlers encontrados: 247
     * Controllers qualificados para grupos individuais (AbstractCrudController): 8
     * Grupo individual 'api-human-resources-funcionarios' registrado para FuncionarioController
     * Grupo individual 'api-human-resources-cargos' registrado para CargoController
     * ... (6 mais grupos individuais)
     * 
     * Grupo agregado 'recursos-humanos' registrado com 8 controller(s) (pattern: /api/human-resources)
     * Grupo agregado 'recursos-humanos-bulk' registrado com 8 controller(s) (pattern: /api/human-resources)
     * 
     * Total: 10 grupos (8 individuais + 2 agregados)
     * </pre>
     * 
     * <h4>🚀 Performance Resultante:</h4>
     * <ul>
     *   <li><strong>Consultas específicas:</strong> 3-5KB (grupos individuais)</li>
     *   <li><strong>Contextos de negócio:</strong> 50-100KB (grupos agregados)</li>
     *   <li><strong>Vs documento completo:</strong> 500KB+ (90-99% de economia)</li>
     * </ul>
     */
    @PostConstruct
    public void createDynamicGroups() {
        logger.info("Iniciando escaneamento dinâmico de grupos para controllers que estendem AbstractCrudController...");

        // 📋 PASSO 1: Obter todos os handler methods registrados no Spring MVC
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        Set<Class<?>> qualifyingControllers = new HashSet<>();

        // 🗂️ PASSO 2: Mapas para organizar grupos e paths
        Map<Class<?>, String> controllerGroups = new HashMap<>();
        Map<Class<?>, String> controllerPaths = new HashMap<>();

        int totalHandlers = handlerMethods.size();
        logger.info("Total de handlers encontrados: {}", totalHandlers);

        // 🔍 PASSO 3A: Escanear controllers AbstractCrudController para grupos individuais
        Map<String, Set<String>> aggregatedGroupToPaths = new HashMap<>();
        Set<String> registeredGroupNames = new HashSet<>();
        
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            Class<?> controllerClass = handlerMethod.getBeanType();

            // ✅ GRUPOS INDIVIDUAIS: Apenas controllers que estendem AbstractCrudController
            if (AbstractCrudController.class.isAssignableFrom(controllerClass)) {
                qualifyingControllers.add(controllerClass);

                logger.debug("🔍 DEBUG: Analisando controller CRUD: {}", controllerClass.getSimpleName());
                
                String basePath = extractControllerBasePath(controllerClass);
                
                if (basePath != null && !basePath.isEmpty() && !"/".equals(basePath)) {
                    // 🎯 Criar grupo individual ultra-específico
                    String individualGroupName = basePath.replaceAll("^/|/$", "").replace("/", "-");
                    if (!registeredGroupNames.contains(individualGroupName)) {
                        registerGroupBeanWithCustomName(basePath, individualGroupName);
                        registeredGroupNames.add(individualGroupName);
                        logger.info("Grupo individual '{}' registrado para {} (path: {})",
                            individualGroupName, controllerClass.getSimpleName(), basePath);
                    }
                } else {
                    logger.warn("BasePath inválido ou vazio para controller CRUD: {} (basePath='{}')",
                        controllerClass.getSimpleName(), basePath);
                }
            }
        }

        logger.info("Controllers qualificados para grupos individuais (AbstractCrudController): {}", qualifyingControllers.size());

        // 🔍 PASSO 3B: Escanear TODOS os controllers para grupos agregados via @ApiGroup
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            Class<?> controllerClass = handlerMethod.getBeanType();
            
            // 🏷️ GRUPOS AGREGADOS: Qualquer controller com @ApiGroup
            ApiGroup apiGroup = AnnotationUtils.findAnnotation(controllerClass, ApiGroup.class);
            if (apiGroup != null) {
                logger.debug("🔍 DEBUG: Controller com @ApiGroup encontrado: {} → '{}'", 
                    controllerClass.getSimpleName(), apiGroup.value());
                
                String basePath = extractControllerBasePath(controllerClass);
                
                if (basePath != null && !basePath.isEmpty() && !"/".equals(basePath)) {
                    String customGroupName = apiGroup.value();
                    aggregatedGroupToPaths.computeIfAbsent(customGroupName, k -> new HashSet<>()).add(basePath);
                } else {
                    logger.warn("Controller {} tem @ApiGroup mas basePath inválido: '{}'",
                        controllerClass.getSimpleName(), basePath);
                }
            }
        }
        
        // 🎯 PASSO 4: Registrar grupos agregados (@ApiGroup)
        for (Map.Entry<String, Set<String>> entry : aggregatedGroupToPaths.entrySet()) {
            String customGroupName = entry.getKey();
            Set<String> paths = entry.getValue();
            
            if (!registeredGroupNames.contains(customGroupName)) {
                // Criar padrão agregado que inclui todos os paths do grupo
                String aggregatedPattern = determineAggregatedPattern(paths);
                registerGroupBeanWithCustomName(aggregatedPattern, customGroupName);
                registeredGroupNames.add(customGroupName);
                
                logger.info("Grupo agregado '{}' registrado com {} controller(s) (pattern: {}, paths: {})",
                    customGroupName, paths.size(), aggregatedPattern, paths);
            }
        }

        // 🚨 Aviso caso nenhum controller qualificado seja encontrado
        if (qualifyingControllers.isEmpty()) {
            logger.warn("Nenhum controller qualificado encontrado. Verifique se os controllers estendem AbstractCrudController e têm @RequestMapping.");
        }
    }

    /**
     * <h3>✅ Validação de Conformidade com @ApiResource</h3>
     * <p>Executa após o startup completo da aplicação para validar se todos os controllers 
     * que estendem AbstractCrudController estão usando @ApiResource conforme esperado.</p>
     * 
     * <h4>🎯 Objetivo:</h4>
     * <p>Garantir que developers sigam o padrão arquitetural correto, evitando inconsistências
     * e problemas de manutenção futuros. Força migração para @ApiResource para aproveitar
     * os benefícios da resolução automática de grupos OpenAPI.</p>
     * 
     * <h4>📋 Comportamentos:</h4>
     * <ul>
     *   <li><strong>WARN:</strong> Emite warnings informativos no log (padrão)</li>
     *   <li><strong>FAIL:</strong> Interrompe o startup com exceção</li>
     *   <li><strong>IGNORE:</strong> Pula a validação completamente</li>
     * </ul>
     * 
     * <h4>⚠️ Sem Exceções:</h4>
     * <p>Todos os controllers que estendem AbstractCrudController devem migrar para @ApiResource.
     * Não há anotação de exceção ou contorno - a validação é direta e obrigatória.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateApiResourceUsage() {
        if ("IGNORE".equalsIgnoreCase(apiResourceValidationMode)) {
            logger.debug("Validação de @ApiResource desabilitada via configuração");
            return;
        }

        logger.info("🔍 Iniciando validação de uso de @ApiResource em controllers AbstractCrud...");
        
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        Set<Class<?>> violatingControllers = new HashSet<>();
        Set<Class<?>> compliantControllers = new HashSet<>();
        
        // 📋 PASSO 1: Identificar controllers que estendem AbstractCrudController
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            Class<?> controllerClass = handlerMethod.getBeanType();

            // ✅ Filtra apenas controllers que estendem AbstractCrudController
            if (AbstractCrudController.class.isAssignableFrom(controllerClass)) {
                
                // 🔍 Verifica se usa @ApiResource ou pelo menos @RequestMapping + @RestController
                boolean hasApiResource = AnnotationUtils.findAnnotation(controllerClass, ApiResource.class) != null;
                boolean hasRequestMapping = AnnotationUtils.findAnnotation(controllerClass, RequestMapping.class) != null;
                
                if (hasApiResource) {
                    compliantControllers.add(controllerClass);
                    logger.debug("✅ {} usa @ApiResource corretamente", controllerClass.getSimpleName());
                } else if (hasRequestMapping) {
                    violatingControllers.add(controllerClass);
                    logger.debug("⚠️ {} usa @RequestMapping em vez de @ApiResource", controllerClass.getSimpleName());
                } else {
                    violatingControllers.add(controllerClass);
                    logger.debug("❌ {} não tem anotação de mapeamento detectável", controllerClass.getSimpleName());
                }
            }
        }
        
        // 📊 PASSO 2: Reportar resultados
        int totalControllers = compliantControllers.size() + violatingControllers.size();
        int compliantCount = compliantControllers.size();
        int violatingCount = violatingControllers.size();
        
        logger.info("📊 Relatório de Conformidade @ApiResource:");
        logger.info("   ✅ Conformes: {}/{} controllers", compliantCount, totalControllers);
        logger.info("   ⚠️ Precisam migração: {}/{} controllers", violatingCount, totalControllers);
        
        if (!violatingControllers.isEmpty()) {
            String violatingNames = violatingControllers.stream()
                .map(Class::getSimpleName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("nenhum");
                
            String message = String.format(
                "🚨 Controllers que precisam migrar para @ApiResource: %s. " +
                "Recomenda-se substituir @RestController + @RequestMapping por @ApiResource(ApiPaths.CONSTANT) " +
                "para aproveitar os benefícios da resolução automática de grupos OpenAPI.",
                violatingNames
            );
            
            // 🎯 PASSO 3: Ação baseada na configuração
            if ("FAIL".equalsIgnoreCase(apiResourceValidationMode)) {
                throw new IllegalStateException(message + " Configurado para falhar no startup (praxis.openapi.validation.api-resource-required=FAIL)");
            } else {
                logger.warn(message);
                logger.info("💡 Para desabilitar esta validação: praxis.openapi.validation.api-resource-required=IGNORE");
                logger.info("💡 Para falhar o startup: praxis.openapi.validation.api-resource-required=FAIL");
            }
        } else {
            logger.info("🎉 Todos os controllers AbstractCrud estão usando @ApiResource corretamente!");
        }
    }

    /**
     * <h3>🔍 Extrai Base Path de um Controller</h3>
     * <p>Método utilitário que extrai o path base de qualquer controller, 
     * tentando primeiro @ApiResource e depois @RequestMapping.</p>
     * 
     * <h4>🎯 Estratégias de Extração:</h4>
     * <ol>
     *   <li><strong>@ApiResource:</strong> Detecta meta-anotação primeiro</li>
     *   <li><strong>@RequestMapping:</strong> Fallback para anotação direta</li>
     * </ol>
     * 
     * @param controllerClass a classe do controller a ser analisada
     * @return o basePath extraído ou null se não encontrado
     */
    private String extractControllerBasePath(Class<?> controllerClass) {
        // 🎯 ESTRATÉGIA 1: Detectar @ApiResource diretamente
        ApiResource apiResource = AnnotationUtils.findAnnotation(controllerClass, ApiResource.class);
        
        if (apiResource != null) {
            String basePath = extractBasePath(apiResource);
            logger.debug("🔍 DEBUG: Controller {}: @ApiResource={}, basePath extraído={}", 
                controllerClass.getSimpleName(), 
                java.util.Arrays.toString(apiResource.value()),
                basePath);
            return basePath;
        }
        
        // 🔄 ESTRATÉGIA 2: Fallback para @RequestMapping
        RequestMapping requestMapping = AnnotationUtils.findAnnotation(controllerClass, RequestMapping.class);
        if (requestMapping != null) {
            String basePath = extractBasePath(requestMapping);
            logger.debug("🔍 DEBUG: Controller {}: @RequestMapping={}, basePath extraído={}", 
                controllerClass.getSimpleName(), 
                java.util.Arrays.toString(requestMapping.value()),
                basePath);
            return basePath;
        }
        
        logger.debug("🔍 DEBUG: Controller {}: Nenhuma anotação @ApiResource ou @RequestMapping encontrada", 
            controllerClass.getSimpleName());
        return null;
    }

    /**
     * <h3>🎯 Determina Padrão Agregado para Grupos @ApiGroup</h3>
     * <p>Calcula o padrão comum mais específico que engloba todos os paths de um grupo @ApiGroup.</p>
     * 
     * <h4>📊 Exemplos de Funcionamento:</h4>
     * <pre>
     * Input:  ["/api/human-resources/funcionarios", "/api/human-resources/departamentos"]
     * Output: "/api/human-resources"
     * 
     * Input:  ["/api/human-resources/funcionarios"]  
     * Output: "/api/human-resources/funcionarios"
     * 
     * Input:  ["/api/financeiro/contas", "/api/financeiro/extratos"]
     * Output: "/api/financeiro"
     * </pre>
     * 
     * <h4>🎯 Estratégia:</h4>
     * <p>Encontra o prefixo comum mais longo entre todos os paths, garantindo que o
     * padrão agregado capture todos os endpoints do grupo sem ser excessivamente genérico.</p>
     * 
     * @param paths conjunto de paths que pertencem ao mesmo grupo @ApiGroup
     * @return padrão agregado que engloba todos os paths
     */
    private String determineAggregatedPattern(Set<String> paths) {
        if (paths.isEmpty()) {
            return "/";
        }
        
        if (paths.size() == 1) {
            // Se só há um path, usa ele mesmo
            return paths.iterator().next();
        }
        
        // 🔍 Encontrar o prefixo comum mais longo
        String[] pathArray = paths.toArray(new String[0]);
        String commonPrefix = pathArray[0];
        
        for (int i = 1; i < pathArray.length; i++) {
            commonPrefix = findCommonPrefix(commonPrefix, pathArray[i]);
        }
        
        // 🧹 Limpar o prefixo comum para remover segmentos incompletos
        // Ex: "/api/human-reso" → "/api" (remove segmento incompleto)
        if (commonPrefix.length() > 1 && commonPrefix.endsWith("/")) {
            commonPrefix = commonPrefix.substring(0, commonPrefix.length() - 1);
        } else if (commonPrefix.length() > 1) {
            // Remove último segmento se incompleto
            int lastSlash = commonPrefix.lastIndexOf('/');
            if (lastSlash > 0) {
                final String prefixToCheck = commonPrefix;
                if (!paths.stream().allMatch(p -> p.startsWith(prefixToCheck + "/"))) {
                    commonPrefix = commonPrefix.substring(0, lastSlash);
                }
            }
        }
        
        return commonPrefix.isEmpty() ? "/" : commonPrefix;
    }
    
    /**
     * Encontra o prefixo comum entre duas strings.
     */
    private String findCommonPrefix(String str1, String str2) {
        int minLength = Math.min(str1.length(), str2.length());
        int i = 0;
        
        while (i < minLength && str1.charAt(i) == str2.charAt(i)) {
            i++;
        }
        
        return str1.substring(0, i);
    }

    /**
     * Extrai o basePath de uma anotação @ApiResource, priorizando value[] sobre path[].
     * 
     * @param apiResource a anotação @ApiResource do controller
     * @return o basePath extraído ou null se não encontrado
     */
    private String extractBasePath(ApiResource apiResource) {
        // Tenta obter o path de value[] primeiro
        if (apiResource.value().length > 0) {
            return apiResource.value()[0];
        }
        // Se não encontrar, tenta path[]
        else if (apiResource.path().length > 0) {
            return apiResource.path()[0];
        }
        return null;
    }
    
    /**
     * Extrai o basePath de uma anotação @RequestMapping, priorizando value[] sobre path[].
     * 
     * @param requestMapping a anotação @RequestMapping do controller
     * @return o basePath extraído ou null se não encontrado
     */
    private String extractBasePath(RequestMapping requestMapping) {
        // Tenta obter o path de value[] primeiro
        if (requestMapping.value().length > 0) {
            return requestMapping.value()[0];
        }
        // Se não encontrar, tenta path[]
        else if (requestMapping.path().length > 0) {
            return requestMapping.path()[0];
        }
        return null;
    }

    /**
     * <h3>🏗️ Registra Grupo com Nome Auto-Gerado</h3>
     * <p>Cria um bean GroupedOpenApi com nome gerado automaticamente baseado no path.</p>
     * 
     * <h4>📝 Exemplo de Transformação:</h4>
     * <pre>
     * Input:  "/api/human-resources/funcionarios"
     * Output: "api-human-resources-funcionarios"
     * </pre>
     *
     * @param basePath o path base do controller (ex: "/api/human-resources/funcionarios")
     */
    private void registerGroupBean(String basePath) {
        // 🧹 Gera um nome limpo para o grupo removendo barras iniciais/finais e substituindo "/" por "-"
        String groupName = basePath.replaceAll("^/|/$", "").replace("/", "-");
        registerGroupBeanWithCustomName(basePath, groupName);
    }

    /**
     * <h3>🎨 Registra Grupo com Nome Personalizado</h3>
     * <p>Cria um bean GroupedOpenApi no Spring Bean Factory com nome definido via @ApiGroup.</p>
     * 
     * <h4>🔧 Processo Interno:</h4>
     * <ol>
     *   <li>Cria padrão de path com wildcard: basePath + "/**"</li>
     *   <li>Constrói GroupedOpenApi com nome e padrões</li>
     *   <li>Registra como singleton no ConfigurableListableBeanFactory</li>
     *   <li>Nome do bean único para evitar conflitos: groupName + "_ApiGroup"</li>
     * </ol>
     * 
     * <h4>🌐 Impacto na Resolução de Grupos:</h4>
     * <p>Os beans registrados aqui são posteriormente utilizados pelo OpenApiGroupResolver
     * para detectar qual grupo corresponde a uma requisição específica.</p>
     *
     * @param basePath o path base do controller (ex: "/api/human-resources/funcionarios")
     * @param groupName o nome personalizado do grupo (ex: "human-resources")
     */
    private void registerGroupBeanWithCustomName(String basePath, String groupName) {
        // 🎯 Cria padrão de matching: "/api/human-resources/funcionarios" → "/api/human-resources/funcionarios/**"
        String pathsToMatch = basePath + "/**";

        // 🏗️ Constrói o bean GroupedOpenApi
        GroupedOpenApi groupedOpenApi = GroupedOpenApi.builder()
                .group(groupName)
                .pathsToMatch(pathsToMatch)
                .build();

        // 📝 Registra o bean no factory com nome único para evitar conflitos
        String beanName = groupName.replace("-", "_") + "_ApiGroup";
        beanFactory.registerSingleton(beanName, groupedOpenApi);
        
        logger.info("Bean GroupedOpenApi registrado: bean={}, group={}, paths={}",
            beanName, groupName, pathsToMatch);
    }
}
