# Plano de Reescrita do Core Resource/Surface/Action

## Contexto

Nao ha compromisso com compatibilidade retroativa. A estrategia correta e substituir o nucleo conceitualmente errado, e nao empilhar uma camada "V2" sobre o legado.

O ponto que permanece canonico no repo e:

- `@ApiResource`
- grupos OpenAPI dinamicos
- `GET /schemas/filtered` como resolvedor estrutural canonico
- `GET /schemas/catalog` como catalogo resumido de operacoes
- infraestrutura atual de filtros, `option-sources`, `stats` e capabilities derivadas do OpenAPI

O ponto que deve ser substituido e o core baseado em:

- `AbstractCrudController`
- `BaseCrudService`
- `AbstractBaseCrudService`
- `AbstractReadOnlyController`

## Tese Arquitetural

O starter deve passar a ter tres eixos distintos:

1. `resource-oriented`
   Contrato estrutural canonico, query, create, update, partial update por intencao e schemas canonicamente resolvidos.
2. `surface-oriented`
   Discovery semantico de formularios, views parciais, projecoes e UX contextual. Nao define payload; apenas referencia operacao real, `schemaId` e URL de `/schemas/filtered`.
3. `action-oriented`
   Workflows e comandos de negocio explicitos, com endpoints tipados e DTOs proprios. Tambem nao define payload inline no catalogo.

## Regras de Ouro

- `resource` continua sendo a fonte canonica de contrato.
- `surface` nunca define campos, validacoes ou payloads inline.
- `action` nunca substitui endpoint real tipado.
- Todo item de discovery deve apontar para operacao OpenAPI real.
- Todo item de discovery deve retornar `operationId`, `schemaId` e `schemaUrl`.
- `schemaId` e `schemaUrl` devem ser sempre derivados de `/schemas/filtered`.
- `surface` organiza experiencia contextual; nao substitui `resource intent`.
- `PATCH /{id}` so deve existir como update base se `UpdateDTO` for parcial. Se o contrato quiser substituicao completa, manter `PUT`.
- `resourcePath` nao deve ser a identidade canonica do recurso. O modelo interno deve usar um identificador estavel, como `resourceKey`, e tratar a URL como dado derivado.
- Nao introduzir roteadores genericos de execucao como `PATCH /{resource}/{id}/intents/{intentId}` ou `POST /{resource}/{id}/actions/{actionId}` sem controllers tipados reais por operacao.

## Problema Atual

Hoje o starter ainda concentra leitura e escrita no mesmo DTO central `D`, via `AbstractCrudController<E, D, ID, FD>` e `BaseCrudService<E, D, ID, FD>`.

Isso aperta o modelo para:

- `ResponseDTO`, `CreateDTO` e `UpdateDTO` diferentes
- multiplos formularios da mesma entidade
- DTOs parciais por intencao
- workflows de negocio como `approve`, `reject`, `resubmit`
- read-only real sem heranca de surface de escrita

## Estado-Alvo

### Resource-oriented

- `GET /{id}` retorna `ResponseDTO`
- `POST /` recebe `CreateDTO`
- `PUT /{id}` recebe `UpdateDTO` se a semantica for substituicao completa
- `PATCH /{id}` recebe `UpdateDTO` se a semantica base da plataforma for update parcial
- `PATCH /{id}/profile`, `PATCH /{id}/bank-details` etc. recebem DTOs parciais nomeados por intencao

### Surface-oriented

- cataloga experiencias possiveis por recurso ou instancia
- referencia operacao real + schema canonicamente resolvido
- informa disponibilidade contextual

### Action-oriented

- cataloga workflows possiveis por recurso ou instancia
- executa por endpoint real, tipado e documentado
- usa DTO semantico proprio

## Sequencia Recomendada de Implementacao

## Fase 1 - Extrair a resolucao canonica de operacao e schema

### Objetivo

Criar a fundacao reutilizavel para:

- `ApiDocsController`
- `DomainCatalogController`
- catalogo de surfaces
- catalogo de actions
- snapshot de capabilities

### Pacotes

```text
org.praxisplatform.uischema.openapi
org.praxisplatform.uischema.schema
```

### Classes novas

```java
public record CanonicalOperationRef(
    String group,
    String operationId,
    String path,
    String method
) {}
```

```java
public record CanonicalSchemaRef(
    String schemaId,
    String schemaType,
    String url
) {}
```

```java
public interface CanonicalOperationResolver {
    CanonicalOperationRef resolve(HandlerMethod handlerMethod, RequestMappingInfo mappingInfo);
    CanonicalOperationRef resolve(String path, String method);
    Optional<CanonicalOperationRef> resolveByOperationId(String operationId);
}
```

```java
public interface SchemaReferenceResolver {
    CanonicalSchemaRef resolve(
        String path,
        String method,
        String schemaType,
        boolean includeInternalSchemas,
        String tenant,
        Locale locale,
        String idField,
        Boolean readOnly
    );
}
```

```java
public interface OpenApiDocumentService {
    String resolveGroupFromPath(String path);
    JsonNode getDocumentForGroup(String group);
    String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier);
    void clearCaches();
}
```

### Refatoracoes

- `ApiDocsController` passa a usar `OpenApiDocumentService`, `CanonicalOperationResolver` e `SchemaReferenceResolver`
- `DomainCatalogController` passa a usar os mesmos servicos
- `DynamicSwaggerConfig` permanece como base para scanning e resolucao de grupos

### Estado implementado na Lane 1

- `ApiDocsController` calcula `schemaId` e `ETag` pela variante real do payload, incluindo `includeInternalSchemas`, `resolvedIdField` e `computedReadOnly`
- `DomainCatalogController` monta links de `/schemas/filtered` a partir do resolver canonico
- `OpenApiDocumentService` passou a ser o dono de resolucao de grupo, fetch/cache de OpenAPI e cache de hash estrutural
- `SchemaReferenceResolver` passou a devolver uma `schemaUrl` que reproduz a mesma variante canonica do schema

### Resultado esperado

O repo ganha uma API interna unica para resolver operacao e schema canonicos, sem acoplamento ao controller documental.

### Estado implementado no corte A da Fase 2

- `ResourceMapper`
- `BaseResourceQueryService`
- `BaseResourceCommandService`
- `BaseResourceService`
- `AbstractBaseQueryResourceService`
- `AbstractBaseResourceService`
- `AbstractReadOnlyResourceService`

Este corte troca o boundary de service e mapeamento, separando `ResponseDTO`, `CreateDTO` e
`UpdateDTO`, garante `findAll()` e preservacao de ordem em `findAllById()`, e move read-only para
uma hierarquia query-only real. Os controllers legados ainda nao foram removidos nesta rodada.

### Estado implementado no corte B da Fase 2

- `AbstractResourceQueryController`
- `AbstractResourceController`
- `AbstractReadOnlyResourceController`

Este corte sobe o core HTTP novo sobre o boundary resource-oriented, preserva a superficie canonica
de query/options/stats/schema, remove a semantica de escrita herdada da variante read-only e adapta
o scanning de grupos OpenAPI para reconhecer a nova hierarquia. O legado `AbstractCrudController` /
`AbstractReadOnlyController` permanece apenas como superficie transitoria enquanto consumidores como
o `praxis-api-quickstart` ainda nao foram migrados.

O proximo corte da Fase 2 deve:

- migrar consumidores piloto para os controllers novos
- remover progressivamente o legado `AbstractReadOnlyController`
- reduzir a coexistencia com `AbstractCrudController`

## Fase 2 - Reescrever o core resource-oriented

### Objetivo

Trocar o boundary legado baseado em DTO unico por um core com separacao explicita entre leitura, criacao e atualizacao.

### Pacotes

```text
org.praxisplatform.uischema.mapper.base
org.praxisplatform.uischema.service.base
org.praxisplatform.uischema.controller.base
```

### Classes novas

```java
public interface ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID> {
    ResponseDTO toResponse(E entity);
    E newEntity(CreateDTO dto);
    void applyUpdate(E entity, UpdateDTO dto);
    ID extractId(E entity);
}
```

```java
public interface BaseResourceQueryService<ResponseDTO, ID, FilterDTO extends GenericFilterDTO> {
    ResponseDTO findById(ID id);
    Page<ResponseDTO> filter(FilterDTO filter, Pageable pageable, Collection<ID> includeIds);
    CursorPage<ResponseDTO> filterByCursor(FilterDTO filter, Sort sort, String after, String before, int size);
    OptionalLong locate(FilterDTO filter, Sort sort, ID id);
    Page<OptionDTO<ID>> filterOptions(FilterDTO filter, Pageable pageable);
    List<OptionDTO<ID>> byIdsOptions(Collection<ID> ids);
    GroupByStatsResponse groupByStats(GroupByStatsRequest<FilterDTO> request);
    TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<FilterDTO> request);
    DistributionStatsResponse distributionStats(DistributionStatsRequest<FilterDTO> request);
}
```

```java
public interface BaseResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {
    SavedResult<ID, ResponseDTO> create(CreateDTO dto);
    ResponseDTO update(ID id, UpdateDTO dto);
    void deleteById(ID id);
    void deleteAllById(Collection<ID> ids);
}
```

```java
public interface BaseResourceService<
    ResponseDTO,
    ID,
    FilterDTO extends GenericFilterDTO,
    CreateDTO,
    UpdateDTO
> extends BaseResourceQueryService<ResponseDTO, ID, FilterDTO>,
        BaseResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {
}
```

### Controllers novos

- `AbstractResourceQueryController`
- `AbstractResourceController`
- `AbstractReadOnlyResourceController`

### Decisoes

- `AbstractReadOnlyResourceController` herda apenas da base de query
- o modelo atual de read-only herdando CRUD e devolvendo `405` deve ser removido
- `findAll()` permanece obrigatorio enquanto a superficie canonica do starter continuar expondo `GET /all`

### Resultado esperado

O starter passa a suportar `ResponseDTO`, `CreateDTO`, `UpdateDTO` e `FilterDTO` como fronteiras distintas.

## Fase 3 - Escrita parcial por intencao no eixo resource-oriented

### Objetivo

Modelar multiplos formularios da mesma entidade sem transformar `surface` em contrato de escrita.

### Pacotes

```text
org.praxisplatform.uischema.annotation
org.praxisplatform.uischema.resource.intent
```

### Anotacao

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceIntent {
    String id();
    String title();
    String description() default "";
    int order() default 0;
}
```

### Regra

Endpoints reais e tipados, por exemplo:

```java
@PatchMapping("/{id}/profile")
@ResourceIntent(id = "employee-profile", title = "Editar perfil")
public ResponseEntity<RestApiResponse<EmployeeResponseDTO>> updateProfile(
    @PathVariable Long id,
    @Valid @RequestBody UpdateEmployeeProfileDTO dto
) { ... }
```

### Resultado esperado

Formularios parciais viram operacoes canonicamente resource-oriented, com DTO nomeado por intencao.

### Estado atual no starter

- a anotacao `org.praxisplatform.uischema.annotation.ResourceIntent` foi introduzida como vocabulario canonico minimo da fase
- o piloto em `src/test/java/org/praxisplatform/uischema/controller/base/AbstractResourceControllerJpaWriteIntegrationTest.java` agora prova um `PATCH /integration-employees/{id}/profile`
- o piloto valida o endpoint tipado, o DTO parcial `UpdateEmployeeProfileDto`, o OpenAPI do grupo individual e a resolucao canonica de `/schemas/filtered` para o `PATCH`
- discovery/catalogo de intents ainda nao existe; nesta fase o objetivo continua sendo operacao real e tipada, nao dispatcher generico nem catalogo semantico

## Fase 4 - Catalogo de surfaces

### Objetivo

Adicionar discovery semantico de formularios, views e projecoes, sempre por referencia a operacao canonica.

### Estado atual da implementacao

- `@ApiResource` agora exige `resourceKey` como identidade semantica estavel do recurso
- `@UiSurface` foi introduzida para surfaces explicitas sobre operacoes HTTP reais
- o pacote `surface/*` agora publica `SurfaceDefinition`, `SurfaceCatalogItem`, `SurfaceCatalogResponse`,
  `SurfaceDefinitionRegistry`, `SurfaceCatalogService`, `SurfaceAvailabilityEvaluator` e `AnnotationDrivenSurfaceDefinitionRegistry`
- `GET /schemas/surfaces?resource={resourceKey}` e `GET /schemas/surfaces?group={openApiGroup}` ja estao publicados
- o primeiro corte cobre surfaces automaticas `create`, `list`, `detail`, `edit` e surfaces explicitas anotadas, como `profile`
- o segundo corte acoplou `GET /{resource}/{id}/surfaces` ao `AbstractResourceQueryController`, com `resourceId` real no payload
- o endpoint contextual devolve apenas `SurfaceScope.ITEM` e usa `SurfaceAvailabilityContext` com `resourceKey`, `resourcePath`, `resourceId`, `locale` e `principal`
- o terceiro corte extraiu `SurfaceAvailabilityContextResolver`, passou a usar `X-Tenant` como sinal contextual canonico e tornou surfaces `ITEM` globalmente indisponiveis sem `resourceId`, com `reason=resource-context-required`

### Pacotes

```text
org.praxisplatform.uischema.annotation
org.praxisplatform.uischema.surface
org.praxisplatform.uischema.controller.docs
```

### Anotacao

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UiSurface {
    String id();
    SurfaceKind kind();
    String title();
    String description() default "";
    String intent() default "";
    SurfaceScope scope() default SurfaceScope.ITEM;
    int order() default 0;
    String[] tags() default {};
}
```

### Modelo

- `SurfaceDefinition`
- `SurfaceCatalogItem`
- `SurfaceCatalogResponse`
- `SurfaceDefinitionRegistry`
- `SurfaceCatalogService`
- `SurfaceAvailabilityEvaluator`
- `SurfaceAvailabilityContext`
- `AnnotationDrivenSurfaceDefinitionRegistry`

### Endpoints

- `GET /schemas/surfaces?resource={resourceKey}`
- `GET /schemas/surfaces?group={openApiGroup}`
- `GET /{resource}/{id}/surfaces`

### Regras

- o catalogo retorna `operationId`, `path`, `method`, `schemaId`, `schemaUrl`
- o catalogo nao retorna fields, schema ou validacao inline
- `FORM` e `PARTIAL_FORM` apontam para schema `request`
- `VIEW` e `READ_PROJECTION` apontam para schema `response`
- a Fase 4 nao surfaceia `delete`, `filter`, `cursor`, `locate`, `options`, `stats` nem workflow actions
- no catalogo global, surfaces `ITEM` sao discovery semantico e devem refletir ausencia de contexto concreto em `availability`

## Fase 5 - Catalogo de actions de workflow

### Objetivo

Catalogar e avaliar workflows de negocio explicitos.

### Pacotes

```text
org.praxisplatform.uischema.annotation
org.praxisplatform.uischema.action
org.praxisplatform.uischema.controller.docs
```

### Anotacao

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkflowAction {
    String id();
    String title();
    String description() default "";
    ActionScope scope() default ActionScope.ITEM;
    int order() default 0;
    String successMessage() default "";
}
```

### Modelo

- `ActionDefinition`
- `ActionCatalogItem`
- `ActionCatalogResponse`
- `ActionDefinitionRegistry`
- `ActionAvailabilityEvaluator`
- `ActionAvailabilityContext`
- `AnnotationDrivenActionDefinitionRegistry`

### Endpoints

- `GET /schemas/actions?resource={resourceKey}`
- `GET /schemas/actions?group={openApiGroup}`
- `GET /{resource}/{id}/actions`

### Execucao

Sempre por endpoint tipado real, por exemplo:

```java
@PostMapping("/{id}/actions/approve")
@WorkflowAction(id = "approve", title = "Aprovar")
public ResponseEntity<RestApiResponse<EmployeeResponseDTO>> approve(
    @PathVariable Long id,
    @Valid @RequestBody ApproveEmployeeDTO dto
) { ... }
```

## Fase 6 - Capabilities unificadas

### Objetivo

Expor um snapshot unico do que pode ser feito agora em uma colecao ou instancia.

### Pacotes

```text
org.praxisplatform.uischema.capability
org.praxisplatform.uischema.controller.docs
```

### Modelo

- `AvailabilityDecision`
- `CapabilitySnapshot`
- `CanonicalCapabilityResolver`
- `CapabilityService`

### Endpoints

- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

### Regra

Capabilities agregam:

- operacoes canonicas presentes
- surfaces disponiveis
- actions disponiveis

Sem redefinir payload ou contrato.

## Estrutura de Pacotes Alvo

```text
org.praxisplatform.uischema
|-- annotation
|-- mapper.base
|-- service.base
|-- controller.base
|-- openapi
|-- schema
|-- resource.intent
|-- surface
|-- action
|-- capability
|-- controller.docs
`-- configuration
```

## Corte Recomendado para o Primeiro PR

### Escopo

- criar `OpenApiDocumentService`
- criar `CanonicalOperationResolver`
- criar `SchemaReferenceResolver`
- refatorar `ApiDocsController`
- refatorar `DomainCatalogController`

### Motivo

Esse corte tem o menor risco arquitetural e estabelece a fundacao canonica para:

- troca do core resource-oriented
- catalogo de surfaces
- catalogo de actions
- snapshot de capabilities

## Corte Recomendado para o Segundo PR

### Escopo

- criar `ResourceMapper`
- criar `BaseResourceQueryService`
- criar `BaseResourceCommandService`
- criar `BaseResourceService`
- criar `AbstractResourceQueryController`
- criar `AbstractResourceController`
- criar `AbstractReadOnlyResourceController`
- migrar um recurso piloto
- remover `AbstractReadOnlyController`

### Motivo

Esse corte remove a principal limitacao estrutural do starter: o DTO central compartilhado entre leitura e escrita.

## Divisao Operacional das Tarefas

## Regra de Execucao

- a thread principal fica com a integracao, decisoes canonicas e revisao final do write-set
- subagentes de implementacao recebem sempre write-sets disjuntos
- nenhum subagente cria contrato paralelo, endpoint generico ou payload inline em catalogo
- todo subagente deve ser registrado em `docs/agent-status/YYYY-MM-DD.md` e encerrado ao final da lane
- toda rodada termina com um agente de QA independente

## Lanes Recomendadas

### Lane 1 - Fundacao canonica de OpenAPI/schema

Escopo:

- `openapi/*`
- `schema/*`
- refatoracao controlada de `ApiDocsController`
- refatoracao controlada de `DomainCatalogController`

Responsabilidade:

- extrair `OpenApiDocumentService`
- extrair `CanonicalOperationResolver`
- extrair `SchemaReferenceResolver`
- preservar o comportamento canonico atual de `/schemas/filtered` e `/schemas/catalog`

### Lane 2 - Novo core resource-oriented

Escopo:

- `mapper.base/*`
- `service.base/*`
- `controller.base/*`

Responsabilidade:

- criar `ResourceMapper`
- criar `BaseResourceQueryService`
- criar `BaseResourceCommandService`
- criar `BaseResourceService`
- criar `AbstractResourceQueryController`
- criar `AbstractResourceController`
- criar `AbstractReadOnlyResourceController`

### Lane 3 - Migracao piloto de recurso

Escopo:

- recurso piloto escolhido
- DTOs de resposta, criacao, update e patch por intencao
- testes focais do recurso piloto

Responsabilidade:

- provar o novo core em um fluxo real
- eliminar o uso do DTO unico no recurso piloto
- consolidar um primeiro consumidor piloto dentro do proprio starter antes de abrir a frente de patch por intencao

Estado atual no starter:

- o piloto inicial foi consolidado em `src/test/java/org/praxisplatform/uischema/controller/base/AbstractResourceControllerJpaWriteIntegrationTest.java`
- ele valida create, update, leitura individual, leitura de colecao, `by-ids`, exclusao, `datasetVersion`, OpenAPI do grupo individual e resolucao canonica de `/schemas/filtered`
- a extensao inicial de patch por intencao ja foi provada no mesmo piloto com `PATCH /integration-employees/{id}/profile` e `UpdateEmployeeProfileDto`
- antes de migrar consumidores externos, esse piloto deve permanecer verde e servir como guarda minima do core novo, incluindo o patch tipado por intencao

### Lane 4 - Discovery de surfaces

Escopo:

- `annotation/UiSurface`
- `surface/*`
- `controller.docs/SurfaceCatalogController`

Responsabilidade:

- catalogar surfaces por referencia a operacoes reais
- nunca publicar payload inline
- sempre retornar `operationId`, `schemaId` e `schemaUrl`

### Lane 5 - Discovery de actions

Escopo:

- `annotation/WorkflowAction`
- `action/*`
- `controller.docs/ActionCatalogController`

Responsabilidade:

- catalogar workflows por referencia a operacoes reais
- manter execucao por controllers tipados
- nunca introduzir action router generico

### Lane 6 - Capabilities unificadas

Escopo:

- `capability/*`
- enriquecimento controlado de `controller.docs/*`

Responsabilidade:

- agregar operacoes canonicas, surfaces e actions
- responder o que pode ser feito agora sem redefinir contrato

## Ordem Recomendada de Execucao

1. Lane 1
2. Lane 2
3. Lane 3
4. Lane 4
5. Lane 5
6. Lane 6

As lanes 4, 5 e 6 so devem comecar depois que as lanes 1 e 2 estabilizarem a fundacao canonica.

## Protocolo de Subagentes

### Agentes de implementacao

Cada agente de implementacao deve receber:

- objetivo fechado
- write-set explicito
- lista do que nao pode alterar
- validacao minima esperada
- regra de nao reverter mudancas alheias

Template operacional minimo:

```text
Responsabilidade desta lane:
- arquivos permitidos: ...
- arquivos proibidos: ...
- objetivo: ...
- validacao minima: ...
- nao criar contratos paralelos
- nao redefinir schema fora de /schemas/filtered
- nao reverter mudancas de outras lanes
```

### Agente de QA obrigatorio ao fim de cada rodada

Toda rodada relevante deve terminar com um agente de QA separado da implementacao.

Perfil esperado:

- agir como Staff Engineer especialista em Spring Boot
- revisar integridade de codigo
- revisar integridade da logica de negocio
- revisar aderencia entre planejado e executado
- revisar consistencia documental
- revisar robustez para uso corporativo

Checklist obrigatorio do agente de QA:

- verificar se o contrato canonico continua centrado em `resource`
- verificar se `surface` e `action` nao introduziram shadow API
- verificar se `schemaId` e `schemaUrl` sao derivados canonicamente
- verificar se nao surgiu endpoint generico de execucao
- verificar regressao em `/schemas/filtered`
- verificar regressao em `/schemas/catalog`
- verificar coerencia entre controller, service, DTO e documentacao
- verificar se a validacao executada foi suficiente para o risco do patch
- apontar gaps de testes, gaps de modelagem e debt documental

Prompt minimo recomendado para o agente de QA:

```text
Revise esta rodada como Staff Engineer especialista em Spring Boot.
Priorize:
1. integridade do codigo
2. integridade da logica de negocio
3. inconsistencias entre o plano e a implementacao
4. qualidade e sufiencia da documentacao
5. aderencia a uso corporativo

Procure principalmente:
- shadow API
- regressao de contrato canonico
- duplicacao estrutural
- endpoint generico onde deveria haver operacao tipada
- schema nao derivado de /schemas/filtered
- leitura/escrita ainda acopladas no mesmo DTO sem necessidade
- lacunas de teste e de migracao
```

## Gate de Pronto por Rodada

Uma rodada so pode ser dada como pronta quando:

- a lane implementada passou pela validacao minima prevista
- o agente de QA revisou o patch
- os findings do QA foram resolvidos ou registrados explicitamente
- a documentacao tocada pela rodada foi atualizada
- todos os subagentes da rodada foram encerrados

## Remocoes Planejadas

Ao final da migracao do core:

- remover `AbstractCrudController`
- remover `BaseCrudService`
- remover `AbstractBaseCrudService`
- remover `AbstractReadOnlyController`

## Resultado Esperado

Ao final do plano:

- `resource` define a verdade estrutural
- `surface` organiza discovery semantico contextual
- `action` explicita workflows de negocio
- `/schemas/filtered` continua sendo a unica fonte estrutural canonica
- nenhum catalogo define payload inline

## Frase-Guia

`resource` define o contrato; `surface` organiza a experiencia; `action` expressa a intencao; `/schemas/filtered` continua sendo a verdade estrutural.
