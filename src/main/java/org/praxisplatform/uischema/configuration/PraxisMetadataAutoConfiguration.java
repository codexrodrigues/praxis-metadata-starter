package org.praxisplatform.uischema.configuration;

import io.swagger.v3.oas.models.media.NumberSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.math.BigDecimal;

/**
 * <h2>⚙️ Auto-Configuração Principal do Praxis Metadata Starter</h2>
 * 
 * <p>Esta classe é responsável por configurar os aspectos <strong>estruturais e de integração</strong> 
 * do sistema Praxis Metadata, incluindo escaneamento de componentes, customizações OpenAPI 
 * e grupos de documentação de infraestrutura.</p>
 *
 * <h3>🎯 Objetivos Principais</h3>
 * <ol>
 *   <li><strong>Component Scanning:</strong> Detecta e registra automaticamente todos os componentes do framework</li>
 *   <li><strong>OpenAPI Integration:</strong> Configura customizações específicas para melhor documentação</li>
 *   <li><strong>Infrastructure Groups:</strong> Cria grupos básicos de documentação para infraestrutura</li>
 *   <li><strong>Type Mapping:</strong> Configura mapeamento correto de tipos Java para schemas OpenAPI</li>
 * </ol>
 * 
 * <h3>🔄 Diferença das Outras Auto-Configurações</h3>
 * <table>
 *   <tr><th>Configuração</th><th>Responsabilidade</th><th>Foco</th></tr>
 *   <tr>
 *     <td>PraxisMetadataAutoConfiguration</td>
 *     <td>Estrutura base + Component Scan</td>
 *     <td>🏗️ Infraestrutura</td>
 *   </tr>
 *   <tr>
 *     <td>OpenApiUiSchemaAutoConfiguration</td>
 *     <td>Beans específicos do UI Schema</td>
 *     <td>🎨 Funcionalidade</td>
 *   </tr>
 *   <tr>
 *     <td>DynamicSwaggerConfig</td>
 *     <td>Grupos dinâmicos + Validação</td>
 *     <td>🤖 Automação</td>
 *   </tr>
 * </table>
 * 
 * <h3>📦 Component Scanning Strategy</h3>
 * <p>Esta classe usa {@code @ComponentScan} para detectar automaticamente:</p>
 * <ul>
 *   <li><strong>org.praxisplatform.uischema.controller.docs:</strong> Controllers de documentação</li>
 *   <li><strong>org.praxisplatform.uischema.service:</strong> Serviços base e metadados</li>
 *   <li><strong>org.praxisplatform.uischema.filter:</strong> Filtros e specifications</li>
 *   <li><strong>org.praxisplatform.uischema.configuration:</strong> Configurações adicionais</li>
 * </ul>
 * 
 * <h3>🔧 Fluxo de Inicialização</h3>
 * <pre>
 * Spring Boot Auto-Configuration Detection
 *              ↓
 * PraxisMetadataAutoConfiguration
 *              ↓
 * {@code @ComponentScan} escaneia packages
 *              ↓
 * Registra: Controllers, Services, Filters, Configs
 *              ↓  
 * OpenAPI Customizers aplicados
 *              ↓
 * Grupos de infraestrutura criados
 *              ↓
 * Sistema pronto para DynamicSwaggerConfig
 * </pre>
 * 
 * <h3>🎯 Integração Sistêmica</h3>
 * <p>Esta auto-configuração é a <strong>"fundação"</strong> que permite que outras funcionalidades funcionem:</p>
 * <ul>
 *   <li><strong>DynamicSwaggerConfig:</strong> Depende do component scan para ser detectado</li>
 *   <li><strong>ApiDocsController:</strong> Registrado via component scan</li>
 *   <li><strong>Services e Utilidades:</strong> Serviços base detectados automaticamente</li>
 *   <li><strong>AbstractCrudController:</strong> Classes base disponibilizadas para herança</li>
 * </ul>
 * 
 * <h3>⚡ Ordem de Execução</h3>
 * <ol>
 *   <li><strong>1º:</strong> PraxisMetadataAutoConfiguration (fundação + component scan)</li>
 *   <li><strong>2º:</strong> OpenApiUiSchemaAutoConfiguration (beans específicos)</li>
 *   <li><strong>3º:</strong> DynamicSwaggerConfig (detectado via component scan, roda via @PostConstruct)</li>
 *   <li><strong>4º:</strong> Validação (executada via @EventListener após startup completo)</li>
 * </ol>
 * 
 * @since 1.0.0
 * @see org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration
 * @see org.praxisplatform.uischema.configuration.DynamicSwaggerConfig
 * @see org.praxisplatform.uischema.service.base.BaseCrudService
 */
@AutoConfiguration
@ComponentScan(basePackages = {
    "org.praxisplatform.uischema.controller.docs",
    "org.praxisplatform.uischema.service",
    "org.praxisplatform.uischema.filter",
    "org.praxisplatform.uischema.configuration"
})
public class PraxisMetadataAutoConfiguration {

    /**
     * <h3>🔢 Bean OpenApiCustomizer para BigDecimal</h3>
     * 
     * <p>Configura o mapeamento correto de {@code BigDecimal} para schemas OpenAPI, 
     * garantindo que valores decimais sejam documentados adequadamente na API.</p>
     * 
     * <h4>🎯 Problema Resolvido:</h4>
     * <p>Por padrão, SpringDoc pode mapear {@code BigDecimal} de forma inconsistente. 
     * Este customizer força o mapeamento correto para o tipo "number" com formato "decimal".</p>
     * 
     * <h4>📋 Mapeamento Aplicado:</h4>
     * <pre>
     * // Antes (inconsistente):
     * BigDecimal → pode ser "string", "number", ou indefinido
     * 
     * // Depois (consistente):
     * BigDecimal → "number" com format "decimal"
     * </pre>
     * 
     * <h4>🔄 Exemplo de Schema Gerado:</h4>
     * <pre>{@code
     * // Campo Java:
     * @Schema(description = "Valor do salário")
     * private BigDecimal salario;
     * 
     * // Schema OpenAPI resultante:
     * {
     *   "salario": {
     *     "type": "number",
     *     "format": "decimal",
     *     "description": "Valor do salário"
     *   }
     * }
     * }</pre>
     * 
     * <h4>🎯 Benefícios:</h4>
     * <ul>
     *   <li><strong>Consistência:</strong> Todos os BigDecimal mapeados igualmente</li>
     *   <li><strong>Precisão:</strong> Frontend sabe como tratar valores decimais</li>
     *   <li><strong>Validação:</strong> Schemas corretos para validação de entrada</li>
     *   <li><strong>Documentação:</strong> API docs mostram tipos precisos</li>
     * </ul>
     * 
     * <h4>🔗 Uso em DTOs:</h4>
     * <pre>{@code
     * public class FuncionarioDTO {
     *     // Automaticamente mapeado como "number"/"decimal":
     *     private BigDecimal salario;
     *     private BigDecimal bonus; 
     *     private BigDecimal desconto;
     * }
     * 
     * // Frontend JavaScript pode tratar adequadamente:
     * const salario = parseFloat(response.salario); // Não precisa converter de string
     * }</pre>
     * 
     * <h4>⚙️ Configuração Técnica:</h4>
     * <p>Utiliza {@code SpringDocUtils.getConfig().replaceWithSchema()} para aplicar 
     * o mapeamento globalmente a todas as ocorrências de {@code BigDecimal} na aplicação.</p>
     * 
     * @return OpenApiCustomizer que configura mapeamento de BigDecimal
     */
    @Bean
    public OpenApiCustomizer bigDecimalOpenApiCustomizer() {
        return openApi -> SpringDocUtils.getConfig()
            .replaceWithSchema(BigDecimal.class, new NumberSchema().type("number").format("decimal"));
    }

    /**
     * <h3>🏗️ Bean GroupedOpenApi para Infraestrutura do Praxis</h3>
     * 
     * <p>Cria um grupo OpenAPI específico para endpoints de infraestrutura do sistema 
     * Praxis Metadata, separando a documentação interna dos endpoints de aplicação.</p>
     * 
     * <h4>🎯 Objetivo:</h4>
     * <p>Organizar a documentação OpenAPI separando endpoints internos do framework 
     * (infraestrutura) dos endpoints específicos da aplicação do usuário.</p>
     * 
     * <h4>📡 Endpoints Incluídos:</h4>
     * <ul>
     *   <li><strong>/schemas/filtered:</strong> Resolução automática de schemas por path</li>
     *   <li><strong>/schemas/{groupName}:</strong> Documentação completa de grupos específicos</li>
     *   <li><strong>/schemas/metadata:</strong> Metadados de UI Schema (se implementado)</li>
     *   <li><strong>Futuros endpoints /schemas/*:</strong> Expansões da funcionalidade</li>
     * </ul>
     * 
     * <h4>🔄 Padrão de Matching:</h4>
     * <pre>
     * pathsToMatch("/schemas/**")
     * 
     * ✅ Incluídos:
     * /schemas/filtered?path=/api/funcionarios
     * /schemas/funcionarios  
     * /schemas/metadata/forms
     * /schemas/ui-config
     * 
     * ❌ Excluídos:
     * /api/funcionarios (aplicação)
     * /api/departamentos (aplicação)  
     * /health (outros sistemas)
     * </pre>
     * 
     * <h4>🎪 Benefícios da Separação:</h4>
     * <ul>
     *   <li><strong>Organização:</strong> Documentação interna separada da aplicação</li>
     *   <li><strong>Clareza:</strong> Desenvolvedores veem APIs do framework vs. negócio</li>
     *   <li><strong>Manutenção:</strong> Mudanças internas não poluem docs da aplicação</li>
     *   <li><strong>Versionamento:</strong> Infraestrutura pode ter ciclo diferente da aplicação</li>
     * </ul>
     * 
     * <h4>📋 Uso no Swagger UI:</h4>
     * <pre>
     * Swagger UI Dropdown:
     * ├── praxis-metadata-infra (este grupo)
     * │   └── Endpoints: /schemas/filtered, /schemas/{group}
     * ├── application (próximo bean)  
     * │   └── Endpoints: /api/funcionarios, /api/departamentos
     * └── api-human-resources-funcionarios (grupos dinâmicos)
     *     └── Endpoints: /api/human-resources/funcionarios/**
     * </pre>
     * 
     * <h4>🔗 Integração:</h4>
     * <p>Este grupo é consumido pelo {@code OpenApiGroupResolver} junto com outros grupos, 
     * mas tem prioridade diferente por ser específico de infraestrutura.</p>
     * 
     * <h4>⚡ Performance:</h4>
     * <p>Documento pequeno (~2-5KB) focado apenas nos endpoints de infraestrutura, 
     * carregamento rápido para desenvolvedores que querem entender a API interna do framework.</p>
     * 
     * @return GroupedOpenApi configurado para endpoints de infraestrutura Praxis
     */
    @Bean
    public GroupedOpenApi praxisMetadataInfraOpenApi() {
        return GroupedOpenApi.builder()
            .group("praxis-metadata-infra")
            .pathsToMatch("/schemas/**")
            .build();
    }

    /**
     * <h3>🌐 Bean GroupedOpenApi para Aplicação Geral</h3>
     * 
     * <p>Cria um grupo OpenAPI "fallback" que captura todos os endpoints da aplicação 
     * que não são específicos de infraestrutura nem foram capturados por grupos dinâmicos 
     * mais específicos.</p>
     * 
     * <h4>🎯 Objetivo:</h4>
     * <p>Fornecer um grupo de documentação "catch-all" para endpoints que não se encaixam 
     * em grupos mais específicos, garantindo que nenhuma API fique sem documentação.</p>
     * 
     * <h4>📡 Endpoints Incluídos:</h4>
     * <pre>
     * pathsToMatch("/**")           // Inclui TODOS os paths
     * pathsToExclude("/schemas/**") // EXCETO infraestrutura
     * 
     * ✅ Resultado - Incluídos:
     * /api/funcionarios/**     (se não tiver grupo dinâmico mais específico)
     * /api/departamentos/**    (se não tiver grupo dinâmico mais específico)
     * /health                  (endpoints de sistema)
     * /actuator/**             (se habilitado)
     * /api/custom/**           (endpoints customizados)
     * 
     * ❌ Excluídos:
     * /schemas/**              (grupo praxis-metadata-infra)
     * </pre>
     * 
     * <h4>🔄 Hierarquia de Grupos (Ordem de Prioridade):</h4>
     * <ol>
     *   <li><strong>Grupos Dinâmicos:</strong> Criados pelo DynamicSwaggerConfig (mais específicos)</li>
     *   <li><strong>Infraestrutura:</strong> praxis-metadata-infra (/schemas/**)</li>
     *   <li><strong>Application:</strong> Este grupo (fallback geral)</li>
     * </ol>
     * 
     * <h4>📋 Cenários de Uso:</h4>
     * <pre>
     * Cenário 1 - Controller com {@code @ApiResource}:
     * {@code @ApiResource("/api/funcionarios")} 
     * → DynamicSwaggerConfig cria grupo específico
     * → NÃO aparece no grupo "application"
     * 
     * Cenário 2 - Controller sem {@code @ApiResource}:
     * {@code @RestController @RequestMapping("/api/legacy")}
     * → NÃO tem grupo específico criado
     * → APARECE no grupo "application" 
     * 
     * Cenário 3 - Endpoints de sistema:
     * /health, /actuator/info
     * → APARECE no grupo "application"
     * </pre>
     * 
     * <h4>🎪 Benefícios:</h4>
     * <ul>
     *   <li><strong>Cobertura Total:</strong> Nenhum endpoint fica sem documentação</li>
     *   <li><strong>Fallback Seguro:</strong> Endpoints legados ainda são documentados</li>
     *   <li><strong>Debugging:</strong> Facilita identificar endpoints não organizados</li>
     *   <li><strong>Migração Gradual:</strong> Permite transição suave para @ApiResource</li>
     * </ul>
     * 
     * <h4>⚠️ Indicador de Migração:</h4>
     * <p>Se endpoints de negócio aparecem neste grupo em vez de grupos específicos, 
     * é um indicador de que o controller precisa migrar para usar @ApiResource:</p>
     * <pre>{@code
     * // ❌ Aparece no grupo "application":
     * @RestController
     * @RequestMapping("/api/funcionarios") 
     * 
     * // ✅ Tem grupo próprio "api-funcionarios":
     * @ApiResource("/api/funcionarios")
     * }</pre>
     * 
     * <h4>📊 Exemplo no Swagger UI:</h4>
     * <pre>
     * Dropdown Swagger UI:
     * ├── api-human-resources-funcionarios (específico)
     * ├── api-human-resources-departamentos (específico)  
     * ├── praxis-metadata-infra (infraestrutura)
     * └── application (este grupo - fallback)
     *     ├── /health
     *     ├── /actuator/info  
     *     └── /api/legacy/** (controllers não migrados)
     * </pre>
     * 
     * <h4>🔗 Integração com Resolução Automática:</h4>
     * <p>O OpenApiGroupResolver também considera este grupo, mas com menor prioridade 
     * que grupos mais específicos, garantindo que a resolução automática funcione corretamente.</p>
     * 
     * @return GroupedOpenApi configurado como fallback para todos os endpoints da aplicação
     */
    @Bean
    public GroupedOpenApi praxisMetadataApplicationOpenApi() {
        return GroupedOpenApi.builder()
            .group("application")
            .pathsToMatch("/**")
            .pathsToExclude("/schemas/**")
            .build();
    }
}

