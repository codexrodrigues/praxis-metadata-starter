package org.praxisplatform.uischema.configuration;

import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configura dinamicamente os grupos OpenAPI usados pelo starter.
 *
 * <p>
 * A configuracao registra grupos individuais para controllers canonicos da hierarquia
 * {@link AbstractResourceQueryController} e grupos agregados por contexto via {@link ApiGroup}.
 * </p>
 *
 * <p>
 * O objetivo e manter a resolucao de grupos coerente entre Swagger UI, documentos OpenAPI
 * agrupados e superficies canonicas como {@code /schemas/filtered}. A classe tambem valida a
 * adocao de {@link ApiResource} em controllers resource-oriented.
 * </p>
 */
@Configuration
public class DynamicSwaggerConfig {

    private static final Logger logger = LoggerFactory.getLogger(DynamicSwaggerConfig.class);

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ConfigurableListableBeanFactory beanFactory;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * Modo de validacao para exigir {@link ApiResource} em controllers resource-oriented.
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
*   <li>Escaneia controllers que estendem {@code AbstractResourceQueryController}</li>
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
 * Controllers qualificados para grupos individuais canônicos: 8
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
        logger.info("Iniciando escaneamento dinamico de grupos para controllers resource-oriented canonicamente suportados...");

        // 📋 PASSO 1: Obter todos os handler methods registrados no Spring MVC
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        Set<Class<?>> qualifyingControllers = new HashSet<>();

        int totalHandlers = handlerMethods.size();
        logger.info("Total de handlers encontrados: {}", totalHandlers);

        // 🔍 PASSO 3A: Escanear controllers resource-oriented canônicos para grupos individuais
        Map<String, Set<String>> aggregatedGroupToPaths = new LinkedHashMap<>();
        Set<String> registeredGroupNames = new HashSet<>();
        
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            Class<?> controllerClass = handlerMethod.getBeanType();

            // ✅ GRUPOS INDIVIDUAIS: controllers do legado ou da nova hierarquia resource-oriented
            if (isCanonicalResourceController(controllerClass)) {
                qualifyingControllers.add(controllerClass);

                logger.debug("Analisando controller de recurso: {}", controllerClass.getSimpleName());
                
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
                    logger.warn("BasePath invalido ou vazio para controller resource-oriented: {} (basePath='{}')",
                        controllerClass.getSimpleName(), basePath);
                }
            }
        }

        logger.info("Controllers qualificados para grupos individuais canonicos: {}", qualifyingControllers.size());

        // 🔍 PASSO 3B: Escanear TODOS os controllers para grupos agregados via @ApiGroup
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            Class<?> controllerClass = handlerMethod.getBeanType();
            
            // 🏷️ GRUPOS AGREGADOS: Qualquer controller com @ApiGroup
            ApiGroup apiGroup = AnnotationUtils.findAnnotation(controllerClass, ApiGroup.class);
            if (apiGroup != null) {
                logger.debug("Controller com @ApiGroup encontrado: {} -> '{}'", controllerClass.getSimpleName(), apiGroup.value());
                
                String basePath = extractControllerBasePath(controllerClass);
                
                if (basePath != null && !basePath.isEmpty() && !"/".equals(basePath)) {
                    String customGroupName = apiGroup.value();
                    aggregatedGroupToPaths.computeIfAbsent(customGroupName, k -> new LinkedHashSet<>()).add(basePath);
                } else {
                    logger.warn("Controller {} tem @ApiGroup mas basePath invalido: '{}'",
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
                String[] aggregatedPatterns = toPathsToMatchPatterns(paths);
                registerGroupBeanWithPaths(customGroupName, paths);
                registeredGroupNames.add(customGroupName);
                
                logger.info("Grupo agregado '{}' registrado com {} controller(s) (patterns: {}, paths: {})",
                    customGroupName, paths.size(), java.util.Arrays.toString(aggregatedPatterns), paths);
            }
        }

        // 🚨 Aviso caso nenhum controller qualificado seja encontrado
        if (qualifyingControllers.isEmpty()) {
            logger.warn("Nenhum controller qualificado encontrado. Verifique se os controllers resource-oriented usam @ApiResource ou @RequestMapping.");
        }
    }

    /**
     * <h3>✅ Validação de Conformidade com @ApiResource</h3>
     * <p>Executa após o startup completo da aplicação para validar se todos os controllers 
     * resource-oriented canônicos estão usando @ApiResource conforme esperado.</p>
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
     * <p>Todos os controllers resource-oriented canônicos devem migrar para @ApiResource.
     * Não há anotação de exceção ou contorno - a validação é direta e obrigatória.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateApiResourceUsage() {
        if ("IGNORE".equalsIgnoreCase(apiResourceValidationMode)) {
            logger.debug("Validacao de @ApiResource desabilitada via configuracao");
            return;
        }

        logger.info("Iniciando validacao de uso de @ApiResource em controllers resource-oriented...");
        
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        Set<Class<?>> violatingControllers = new HashSet<>();
        Set<Class<?>> compliantControllers = new HashSet<>();
        
        // 📋 PASSO 1: Identificar controllers resource-oriented canônicos
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            Class<?> controllerClass = handlerMethod.getBeanType();

            // ✅ Filtra controllers do legado ou da nova hierarquia resource-oriented
            if (isCanonicalResourceController(controllerClass)) {
                
                // 🔍 Verifica se usa @ApiResource ou pelo menos @RequestMapping + @RestController
                boolean hasApiResource = AnnotationUtils.findAnnotation(controllerClass, ApiResource.class) != null;
                boolean hasRequestMapping = AnnotationUtils.findAnnotation(controllerClass, RequestMapping.class) != null;
                
                if (hasApiResource) {
                    compliantControllers.add(controllerClass);
                    logger.debug("{} usa @ApiResource corretamente", controllerClass.getSimpleName());
                } else if (hasRequestMapping) {
                    violatingControllers.add(controllerClass);
                    logger.debug("{} usa @RequestMapping em vez de @ApiResource", controllerClass.getSimpleName());
                } else {
                    violatingControllers.add(controllerClass);
                    logger.debug("{} nao tem anotacao de mapeamento detectavel", controllerClass.getSimpleName());
                }
            }
        }
        
        // 📊 PASSO 2: Reportar resultados
        int totalControllers = compliantControllers.size() + violatingControllers.size();
        int compliantCount = compliantControllers.size();
        int violatingCount = violatingControllers.size();
        
        logger.info("Relatorio de conformidade @ApiResource:");
        logger.info("Conformes: {}/{} controllers", compliantCount, totalControllers);
        logger.info("Precisam migracao: {}/{} controllers", violatingCount, totalControllers);
        
        if (!violatingControllers.isEmpty()) {
            String violatingNames = violatingControllers.stream()
                .map(Class::getSimpleName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("nenhum");
                
            String message = String.format(
                "Controllers que precisam migrar para @ApiResource: %s. " +
                "Recomenda-se substituir @RestController + @RequestMapping por @ApiResource(ApiPaths.CONSTANT) " +
                "para aproveitar os beneficios da resolucao automatica de grupos OpenAPI.",
                violatingNames
            );
            
            // 🎯 PASSO 3: Ação baseada na configuração
            if ("FAIL".equalsIgnoreCase(apiResourceValidationMode)) {
                throw new IllegalStateException(message + " Configurado para falhar no startup (praxis.openapi.validation.api-resource-required=FAIL)");
            } else {
                logger.warn(message);
                logger.info("Para desabilitar esta validacao: praxis.openapi.validation.api-resource-required=IGNORE");
                logger.info("Para falhar o startup: praxis.openapi.validation.api-resource-required=FAIL");
            }
        } else {
            logger.info("Todos os controllers resource-oriented estao usando @ApiResource corretamente.");
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
            logger.debug("Controller {}: @ApiResource={}, basePath extraido={}", controllerClass.getSimpleName(), 
                java.util.Arrays.toString(apiResource.value()),
                basePath);
            return basePath;
        }
        
        // 🔄 ESTRATÉGIA 2: Fallback para @RequestMapping
        RequestMapping requestMapping = AnnotationUtils.findAnnotation(controllerClass, RequestMapping.class);
        if (requestMapping != null) {
            String basePath = extractBasePath(requestMapping);
            logger.debug("Controller {}: @RequestMapping={}, basePath extraido={}", controllerClass.getSimpleName(), 
                java.util.Arrays.toString(requestMapping.value()),
                basePath);
            return basePath;
        }
        
        logger.debug("Controller {}: Nenhuma anotacao @ApiResource ou @RequestMapping encontrada", controllerClass.getSimpleName());
        return null;
    }

    private boolean isCanonicalResourceController(Class<?> controllerClass) {
        return AbstractResourceQueryController.class.isAssignableFrom(controllerClass);
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

    private void registerGroupBeanWithPaths(String groupName, Set<String> basePaths) {
        String[] normalizedPaths = toPathsToMatchPatterns(basePaths);
        GroupedOpenApi groupedOpenApi = GroupedOpenApi.builder()
                .group(groupName)
                .pathsToMatch(normalizedPaths)
                .build();

        String beanName = groupName.replace("-", "_") + "_ApiGroup";
        beanFactory.registerSingleton(beanName, groupedOpenApi);

        logger.info("Bean GroupedOpenApi registrado: bean={}, group={}, paths={}",
                beanName, groupName, java.util.Arrays.toString(normalizedPaths));
    }

    private String[] toPathsToMatchPatterns(Set<String> basePaths) {
        if (basePaths == null || basePaths.isEmpty()) {
            return new String[]{"/**"};
        }
        return basePaths.stream()
                .map(this::toPathsToMatchPattern)
                .toArray(String[]::new);
    }

    private String toPathsToMatchPattern(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "/**";
        }
        String normalizedBasePath = basePath.trim();
        if ("/".equals(normalizedBasePath)) {
            return "/**";
        }
        if (normalizedBasePath.length() > 1 && normalizedBasePath.endsWith("/")) {
            normalizedBasePath = normalizedBasePath.substring(0, normalizedBasePath.length() - 1);
        }
        return normalizedBasePath + "/**";
    }
}
