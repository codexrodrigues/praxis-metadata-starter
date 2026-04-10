package org.praxisplatform.uischema.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.praxisplatform.uischema.rest.response.RestApiResource;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.hateoas.EntityModel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuracao principal do `praxis-metadata-starter`.
 *
 * <p>
 * Esta classe registra a infraestrutura base do starter: component scan, customizacoes OpenAPI,
 * mapeamentos de tipos e grupos documentais de infraestrutura. Ela funciona como a fundacao sobre
 * a qual as demais configuracoes especificas do metadata-driven sao montadas.
 * </p>
 */
@AutoConfiguration
@ComponentScan(basePackages = {
    "org.praxisplatform.uischema.controller.docs",
    "org.praxisplatform.uischema.rest.exceptionhandler",
    "org.praxisplatform.uischema.service",
    "org.praxisplatform.uischema.filter",
    "org.praxisplatform.uischema.configuration"
})
public class PraxisMetadataAutoConfiguration {

    static {
        // Alinha o schema bruto ao JSON real dos itens de colecao antes da geracao do OpenAPI.
        SpringDocUtils.getConfig().replaceWithClass(EntityModel.class, RestApiResource.class);
    }

    /**
     * Ajusta o mapeamento global de {@link BigDecimal} para schema OpenAPI decimal.
     */
    @Bean
    public OpenApiCustomizer bigDecimalOpenApiCustomizer() {
        return openApi -> SpringDocUtils.getConfig()
            .replaceWithSchema(BigDecimal.class, new NumberSchema().type("number").format("decimal"));
    }

    @Bean
    public GlobalOpenApiCustomizer restApiResourceComponentCustomizer() {
        return this::customizeRestApiResourceSchemas;
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
     *   <li><strong>/schemas/catalog:</strong> Catálogo resumido de domínios e superfícies publicadas</li>
     *   <li><strong>Outros endpoints futuros em /schemas/*:</strong> somente quando realmente implementados pelo starter</li>
     * </ul>
     * 
     * <h4>🔄 Padrão de Matching:</h4>
     * <pre>
     * pathsToMatch("/schemas/**")
     * 
     * ✅ Incluídos:
     * /schemas/filtered?path=/api/funcionarios
     * /schemas/catalog
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
     * Cenário 2 - Controller com mapeamento HTTP fora da publicação canônica:
     * {@code @RestController @RequestMapping("/api/funcionarios")}
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
     *   <li><strong>Fallback Seguro:</strong> Endpoints sem grupo específico continuam documentados</li>
     *   <li><strong>Debugging:</strong> Facilita identificar endpoints não organizados</li>
     *   <li><strong>Governança:</strong> Evidencia rapidamente o que ainda não foi publicado na trilha canônica</li>
     * </ul>
     * 
     * <h4>⚠️ Indicador de Adoção Canônica:</h4>
     * <p>Se endpoints de negócio aparecem neste grupo em vez de grupos específicos,
     * isso indica que o controller ainda não foi publicado com {@link org.praxisplatform.uischema.annotation.ApiResource}:</p>
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
     *     └── /api/custom/** (controllers fora do agrupamento específico)
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

    private void customizeRestApiResourceSchemas(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null || components.getSchemas().isEmpty()) {
            return;
        }

        Map<String, Schema> schemas = components.getSchemas();
        customizeRestApiLinksSchema(schemas);
        for (String schemaName : new ArrayList<>(schemas.keySet())) {
            if (schemaName.startsWith("PageEntityModel")) {
                rewriteArrayItemRef(schemas, schemaName, "content", schemaName.substring("PageEntityModel".length()));
                continue;
            }
            if (schemaName.startsWith("CursorPageEntityModel")) {
                rewriteArrayItemRef(schemas, schemaName, "content", schemaName.substring("CursorPageEntityModel".length()));
                continue;
            }
            if (schemaName.startsWith("RestApiResponseListEntityModel")) {
                rewriteArrayItemRef(schemas, schemaName, "data", schemaName.substring("RestApiResponseListEntityModel".length()));
            }
        }
    }

    private void customizeRestApiLinksSchema(Map<String, Schema> schemas) {
        ObjectSchema linkObject = new ObjectSchema();
        linkObject.setDescription("Objeto de link canonico publicado dentro de `_links`.");
        linkObject.addProperty("href", new StringSchema().description("URL absoluta ou relativa do affordance."));
        linkObject.addProperty("templated", new BooleanSchema().description("Indica se o href e URI template."));
        linkObject.addProperty("type", new StringSchema().description("Media type associado ao link."));
        linkObject.addProperty("deprecation", new StringSchema().description("URL de deprecacao quando aplicavel."));
        linkObject.addProperty("profile", new StringSchema().description("Profile URI associado ao link."));
        linkObject.addProperty("name", new StringSchema().description("Nome opcional do link."));
        linkObject.addProperty("title", new StringSchema().description("Titulo humano opcional."));
        linkObject.addProperty("hreflang", new StringSchema().description("Idioma associado ao link."));
        schemas.put("RestApiLinkObject", linkObject);

        ArraySchema repeatedLinks = new ArraySchema();
        repeatedLinks.setItems(refSchema("RestApiLinkObject"));

        Schema<?> linkValue = new Schema<>().oneOf(List.of(refSchema("RestApiLinkObject"), repeatedLinks));

        ObjectSchema linksSchema = new ObjectSchema();
        linksSchema.setDescription("Mapa canonico de `_links` por rel. Cada rel aponta para um objeto unico ou para uma lista quando houver multiplas ocorrencias.");
        linksSchema.setAdditionalProperties(linkValue);
        schemas.put("RestApiLinks", linksSchema);
    }

    private void rewriteArrayItemRef(Map<String, Schema> schemas, String containerSchemaName, String arrayProperty, String dtoSchemaName) {
        Schema<?> containerSchema = schemas.get(containerSchemaName);
        if (containerSchema == null || containerSchema.getProperties() == null) {
            return;
        }

        Schema<?> arrayCandidate = containerSchema.getProperties().get(arrayProperty);
        if (arrayCandidate == null) {
            return;
        }

        Schema<?> resolvedArray = dereferenceSchema(schemas, arrayCandidate);
        if (!(resolvedArray instanceof ArraySchema arraySchema) || arraySchema.getItems() == null) {
            return;
        }

        Schema<?> resolvedItems = dereferenceSchema(schemas, arraySchema.getItems());
        if (!isGenericRestApiResourceRef(arraySchema.getItems(), resolvedItems)) {
            return;
        }

        String resourceSchemaName = ensureConcreteRestApiResourceSchema(schemas, dtoSchemaName);
        arraySchema.setItems(refSchema(resourceSchemaName));
    }

    private Schema<?> dereferenceSchema(Map<String, Schema> schemas, Schema<?> schema) {
        if (schema == null || schema.get$ref() == null) {
            return schema;
        }
        String ref = schema.get$ref();
        String prefix = "#/components/schemas/";
        if (!ref.startsWith(prefix)) {
            return schema;
        }
        return schemas.getOrDefault(ref.substring(prefix.length()), schema);
    }

    private boolean isGenericRestApiResourceRef(Schema<?> rawItems, Schema<?> resolvedItems) {
        if (rawItems != null && "#/components/schemas/RestApiResource".equals(rawItems.get$ref())) {
            return true;
        }
        return resolvedItems != null
                && resolvedItems.getProperties() != null
                && resolvedItems.getProperties().containsKey("content")
                && resolvedItems.getProperties().containsKey("_links");
    }

    private String ensureConcreteRestApiResourceSchema(Map<String, Schema> schemas, String dtoSchemaName) {
        String resourceSchemaName = "RestApiResource" + dtoSchemaName;
        if (schemas.containsKey(resourceSchemaName)) {
            return resourceSchemaName;
        }

        if (!schemas.containsKey(dtoSchemaName)) {
            return "RestApiResource";
        }

        ComposedSchema resourceSchema = new ComposedSchema();
        resourceSchema.addAllOfItem(refSchema(dtoSchemaName));

        ObjectSchema linksSchema = new ObjectSchema();
        linksSchema.addProperty("_links", refSchema("RestApiLinks"));
        resourceSchema.addAllOfItem(linksSchema);

        schemas.put(resourceSchemaName, resourceSchema);
        return resourceSchemaName;
    }

    private Schema<?> refSchema(String schemaName) {
        return new Schema<>().$ref("#/components/schemas/" + schemaName);
    }
}

