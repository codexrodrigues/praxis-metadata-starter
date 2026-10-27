# Guia 02 - IA Backend - Recurso Metadata-Driven no Core Atual

## Objetivo

Este guia orienta a implementacao de um recurso backend metadata-driven alinhado
ao core atual do `praxis-metadata-starter`.

Ele foca no caso mais comum: a aplicacao ja existe, e a LLM precisa adicionar um
novo recurso no baseline atual sem gerar codigo incompleto.

## Entrada minima para a LLM

No minimo:

1. entidade JPA ou estrutura equivalente
2. tipo do ID
3. `resourcePath`
4. `resourceKey`
5. grupo OpenAPI
6. pacote base
7. se o recurso e mutavel ou read-only

Exemplo:

```text
Gere um recurso metadata-driven canonico.

Entrada:
- Entidade: src/main/java/com/example/hr/entity/Employee.java
- ID: Long
- Resource path: /api/human-resources/employees
- Resource key: human-resources.employees
- Api group: human-resources
- Pacote base: com.example.hr.employee
- Recurso mutavel: sim
```

## Protocolo de geracao

Peca explicitamente para a LLM gerar nesta ordem:

1. DTOs (`ResponseDTO`, `CreateDTO`, `UpdateDTO`, `FilterDTO`)
2. repository (`extends BaseCrudRepository<E, ID>`)
3. mapper (`implements ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>`)
4. service (`extends AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>`)
5. controller (`extends AbstractResourceController<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>`)
6. apenas depois intents, surfaces e workflow actions

Isso evita dois erros frequentes:

- controller dependente de tipos ainda inexistentes
- service sem mapper correto ou com ordem trocada de parametros genericos

## Semantica obrigatoria de `resourceKey`

Nao trate `resourceKey` como espelho cosmetico do path.

Use esta regra:

- `resourcePath` identifica a URL do recurso
- `resourceKey` identifica a semantica canonica do recurso

Exemplo:

- `resourcePath = /api/human-resources/employees`
- `resourceKey = human-resources.employees`

O starter usa `resourceKey` para:

- encontrar surfaces do recurso em `/schemas/surfaces`
- encontrar workflow actions do recurso em `/schemas/actions`
- compor snapshots de `capabilities`

Mesmo quando o host publica `/schemas/catalog`, o contrato estrutural continua
ancorado em `/schemas/filtered`.

Se o recurso continua semanticamente o mesmo, prefira manter o mesmo
`resourceKey` mesmo quando a URL operacional mudar.

## Arquivos minimos do recurso

```text
src/main/java/{base-package}/
|-- dto/
|   |-- {Resource}ResponseDTO.java
|   |-- Create{Resource}DTO.java
|   |-- Update{Resource}DTO.java
|   `-- filter/
|       `-- {Resource}FilterDTO.java
|-- mapper/
|   `-- {Resource}Mapper.java
|-- repository/
|   `-- {Resource}Repository.java
|-- service/
|   `-- {Resource}Service.java
`-- controller/
    `-- {Resource}Controller.java
```

Para recurso read-only, remova:

- `Create{Resource}DTO`
- `Update{Resource}DTO`

## DTOs canonicos

Use sempre:

- `{Resource}ResponseDTO` para leitura (com `@Schema`, `@ResourceIdentity`, `@UISchema` com grupos/icones/presets e `@DomainGovernance`)
- `Create{Resource}DTO` para `POST` (`@JsonIgnoreProperties({"id"})`)
- `Update{Resource}DTO` para `PUT` (`@JsonIgnoreProperties({"id"})`)
- `{Resource}FilterDTO` para query/filter (`implements GenericFilterDTO` com `@Filterable` + `@UISchema` + `@Schema`)

Regras que a LLM deve obedecer:

- `FilterDTO` deve implementar `GenericFilterDTO` e declarar predicados reais com `@Filterable` (`LIKE`, `EQUAL`, `IN`, `BETWEEN`, `IN_LAST_DAYS`)
- DTOs de escrita devem carregar `@Valid` e constraints reais quando houver
- `ResponseDTO` deve expor o ID retornado pelo controller

A entidade JPA pode orientar nomes, tipos e relacionamentos, mas nao deve ser
tratada como gerador mecanico do contrato publico. Antes de preencher os DTOs,
resolva a semantica do recurso: quais campos pertencem a leitura, criacao,
atualizacao, filtros e discovery. Depois disso, use mapper ou MapStruct para
materializar a fronteira entre persistencia e contrato.

## Repository minimo

```java
public interface EmployeeRepository extends BaseCrudRepository<Employee, Long> {
}
```

Sem isso, o service nao encaixa no core atual.

### Chave primaria simples (`Long`/`UUID`/`String`) vs. Chave composta (`@EmbeddedId` / `CompositeResourceId`)

O `praxis-metadata-starter` suporta nativamente tanto chaves simples (`Long`, `UUID`, `String`) quanto chaves compostas JPA (`@EmbeddedId` com `@Embeddable` `record` ou classe implementando `CompositeResourceId`):

- **Quando usar `@EmbeddedId` (`CompositeResourceId`):** quando a chave composta de dominio for **imutavel** apos a criacao do registro (ex.: `VinculoId(Long numfunc, Integer numvinc)`, `DependenteId(Long numfunc, Integer numdep)`, `ItemTabelaId(String tab, String item)`). No wire HTTP/JSON/OpenAPI, o starter converte automaticamente o `@Embeddable` para string canonica separada por `:` (`"1001:1"`) em `@PathVariable`, `includeIds`, `options/by-ids`, `OptionDTO<ID>`, `RESOURCE_ENTITY` option sources, `@Filterable(relation = "id.numfunc")` / `@Filterable(relation = "vinculo.id")`, ordenacao (`sort=id,desc` expandido para os componentes), exportacao tabular (CSV/Excel/JSON), HATEOAS e `/schemas/filtered` (`type: "string"`, `format: "composite-id"`, `x-ui.compositeIdComponents: ["numfunc", "numvinc"]`, `x-ui.compositeIdSeparator: ":"`).
- **Distincao entre `@EmbeddedId` e Value Objects `@Embedded`:** apenas classes que implementam `CompositeResourceId` (ou sao referenciadas por `@EmbeddedId` / seguem a convencao de chave composta) sao serializadas como `format: "composite-id"`. Classes `@Embeddable` comuns usadas como `@Embedded` Value Objects (ex.: `EnderecoEmbeddable`, `LotacaoEmbeddable`) permanecem objetos JSON estruturados nos DTOs e OpenAPI, mas tambem contam com navegacao segura via `.get(part)` no Criteria JPA (sem `LEFT JOIN` indevido).
- **Contrato UX em Tabelas e Formularios (`ResponseDTO`, `CreateDTO`, `UpdateDTO`):**
  - `ResponseDTO`: exponha `VinculoId id` (identidade canonica para selecao de linha, roteamento, edicao, exclusao e HATEOAS) **e** mantenha as colunas de negocio individuais (`Long numfunc`, `Integer numvinc`) visiveis e formatadas para leitura/ordenacao humana na tabela.
  - `CreateDTO`: exponha os campos de negocio separados e editaveis (`@NotNull Long numfunc`, `@NotNull Integer numvinc`), permitindo que o formulario de criacao use controles ricos (ex.: lookup `RESOURCE_ENTITY` para `numfunc` e input numerico para `numvinc`), e instancie `new VinculoId(dto.numfunc(), dto.numvinc())` no `ResourceMapper.newEntity(dto)`.
  - `UpdateDTO`: omita os campos da chave primaria composta (ou marque-os como `readOnly = true` apenas para exibicao contextual) e **nunca** altere `entity.setId(...)` em `applyUpdate`, pois chaves primarias sao imutaveis apos a criacao.
- **Quando usar chave substituta (`Long id`) + `UNIQUE`:** quando as colunas que identificam o evento no legado forem **editaveis pelo usuario** em operacoes de `PUT /{id}` (ex.: lancamentos de frequencia/afastamento em que `dtini` ou `codfreq` podem ser corrigidos na tela), pois o JPA/Hibernate proibe alterar atributos de um `@EmbeddedId` em uma entidade gerenciada.

## Mapper canonico

```java
@Component
public class EmployeeMapper implements ResourceMapper<
        Employee,
        EmployeeResponseDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO,
        Long> {

    @Override
    public EmployeeResponseDTO toResponse(Employee entity) { ... }

    @Override
    public Employee newEntity(CreateEmployeeDTO dto) { ... }

    @Override
    public void applyUpdate(Employee entity, UpdateEmployeeDTO dto) {
        // Nunca sobrescreva entity.setId(...) nem chaves técnicas pertencentes à rota!
        ...
    }

    @Override
    public Long extractId(Employee entity) {
        return entity.getId();
    }
}
```

Metodos obrigatorios:

- `toResponse`
- `newEntity`
- `applyUpdate(E entity, UpdateDTO dto)` (`entity` e o 1º parametro!)
- `extractId`

## Service canonico (com Analytics / `StatsFieldRegistry` e `getDatasetVersion()`)

```java
@Service
public class EmployeeService extends AbstractBaseResourceService<
        Employee,
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    private static final StatsFieldRegistry STATS_FIELDS = StatsFieldRegistry.builder()
            .categoricalGroupByBucket("active", "active")
            .temporalTimeSeriesField("hireDate", "hireDate")
            .numericHistogramMeasureField("salary", "salary")
            .build();

    private final EmployeeMapper mapper;

    public EmployeeService(EmployeeRepository repository, EmployeeMapper mapper) {
        super(repository, Employee.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<Employee, EmployeeResponseDTO, CreateEmployeeDTO, UpdateEmployeeDTO, Long> getResourceMapper() {
        return mapper;
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.of("Employee:" + getRepository().count());
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return STATS_FIELDS;
    }

    @Override
    public StatsSupportMode getGroupByStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getTimeSeriesStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getDistributionStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getComparisonStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }
}
```

Para read-only:

```java
@Service
public class PayrollViewService extends AbstractReadOnlyResourceService<
        PayrollView,
        PayrollViewResponseDTO,
        Long,
        PayrollViewFilterDTO> {

    private final PayrollViewMapper mapper;

    public PayrollViewService(PayrollViewRepository repository, PayrollViewMapper mapper) {
        super(repository, PayrollView.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<PayrollView, PayrollViewResponseDTO, ?, ?, Long> getResourceMapper() {
        return mapper;
    }
}
```

## Controller canonico (com `@ResourceIdentity` e `@QuickFilter`)

```java
@ApiResource(
    value = ApiPaths.HumanResources.EMPLOYEES,
    resourceKey = "human-resources.employees",
    title = "Colaboradores",
    description = "Gestão cadastral, remuneração base e situação operacional de colaboradores.",
    icon = "groups",
    visualTone = "human-resources",
    identity = @ResourceIdentity(
        keyField = "id",
        titleField = "name",
        subtitleField = "email",
        metadataFields = {"hireDate"},
        displayLabelField = "name"
    ),
    quickFilters = {
        @QuickFilter(id = "active", label = "Ativos", filter = "active=true", icon = "check_circle"),
        @QuickFilter(id = "inactive", label = "Inativos", filter = "active=false", icon = "remove_circle_outline")
    }
)
@ApiGroup("human-resources")
public class EmployeeController extends AbstractResourceController<
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @Override
    protected EmployeeService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(EmployeeResponseDTO dto) {
        return dto.getId();
    }
}
```

Regras obrigatorias:

- use `@ApiResource(value = ..., resourceKey = ...)`
- nao use `@RestController` junto com `@ApiResource`
- sobrescreva `getService()`
- sobrescreva `getResponseId()`

## Read-only canonico

```java
@ApiResource(value = ApiPaths.HumanResources.PAYROLL_VIEW, resourceKey = "human-resources.payroll-view")
@ApiGroup("human-resources")
public class PayrollViewController extends AbstractReadOnlyResourceController<
        PayrollViewResponseDTO,
        Long,
        PayrollViewFilterDTO> {

    private final PayrollViewService service;

    public PayrollViewController(PayrollViewService service) {
        this.service = service;
    }

    @Override
    protected PayrollViewService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(PayrollViewResponseDTO dto) {
        return dto.getId();
    }
}
```

## Quando adicionar `@ResourceIntent`, `@UiSurface` e `@WorkflowAction`

### `@ResourceIntent`

Use quando a operacao ainda e manutencao do recurso, mas com DTO parcial e
semantica propria.

Exemplo:

```java
@PatchMapping("/{id}/profile")
@ResourceIntent(id = "employee-profile", title = "Editar perfil")
public ResponseEntity<RestApiResponse<EmployeeResponseDTO>> updateProfile(
        @PathVariable Long id,
        @Valid @RequestBody UpdateEmployeeProfileDTO dto) {
    ...
}
```

### `@UiSurface`

Use quando a UX precisar descobrir semanticamente uma operacao real.

```java
@UiSurface(
        id = "profile",
        kind = SurfaceKind.PARTIAL_FORM,
        scope = SurfaceScope.ITEM,
        title = "Editar perfil",
        intent = "profile"
)
```

### `@WorkflowAction`

Use apenas para comando de negocio explicito.

```java
@PostMapping("/{id}/actions/approve")
@WorkflowAction(id = "approve", title = "Aprovar", scope = ActionScope.ITEM)
```

## O que o recurso deve expor

Para um recurso mutavel no core atual (com `StatsFieldRegistry` configurado), espere:

- `GET /{resource}/all`
- `GET /{resource}/{id}`
- `POST /{resource}`
- `PUT /{resource}/{id}`
- `DELETE /{resource}/{id}`
- `DELETE /{resource}/batch`
- `POST /{resource}/filter`
- `POST /{resource}/filter/cursor`
- `POST /{resource}/locate`
- `POST /{resource}/options/filter`
- `GET /{resource}/options/by-ids`
- `POST /{resource}/stats/group-by`
- `POST /{resource}/stats/timeseries`
- `POST /{resource}/stats/distribution`
- `POST /{resource}/stats/comparison`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

Para recurso read-only, nao espere:

- `POST /{resource}`
- `PUT /{resource}/{id}`
- `DELETE /{resource}/{id}`
- `DELETE /{resource}/batch`

## O que nao deve ser gerado

- schema inline em catalogos
- action router generico
- payload generico por string
- duplicacao `v1/v2`
- DTO unico para leitura e escrita
- DTO publico derivado mecanicamente da entidade JPA sem decisao semantica
- classes fora do baseline resource-oriented atual

## Prompt recomendado para adicionar um recurso novo

```text
Adicione um novo recurso metadata-driven completo e rico em metadados de negocio ao projeto Spring Boot existente.

Use obrigatoriamente:
- @ApiResource(value = ..., resourceKey = ..., title = ..., description = ..., icon = ..., identity = @ResourceIdentity(...), quickFilters = {@QuickFilter(...)})
- @ApiGroup
- Entidade com @OptionLabel e @DefaultSortColumn
- BaseCrudRepository<E, ID>
- ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>
- AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO> ou AbstractReadOnlyResourceService<E, ResponseDTO, ID, FilterDTO>
- AbstractResourceController<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO> ou AbstractReadOnlyResourceController<ResponseDTO, ID, FilterDTO>

Gere, nesta ordem:
1. DTOs (ResponseDTO, CreateDTO, UpdateDTO, FilterDTO com @Schema de negocio em PT-BR, @UISchema com grupos/icones/presets, @DomainGovernance e @Filterable rico)
2. repository (extends BaseCrudRepository<E, ID>)
3. mapper (implements ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>)
4. service (com getResourceMapper(), getDatasetVersion(), getStatsFieldRegistry() e modos StatsSupportMode.AUTO)
5. controller (com getService() e getResponseId())

Regras:
- nao use @RestController junto com @ApiResource
- nao derive DTO publico mecanicamente da entidade JPA; use a entidade apenas como grounding
- FilterDTO deve implementar GenericFilterDTO e usar @Filterable (LIKE, EQUAL, IN, BETWEEN, IN_LAST_DAYS) + @UISchema
- mapper deve implementar toResponse, newEntity, applyUpdate(E entity, UpdateDTO dto) (sem sobrescrever id) e extractId
- service deve sobrescrever getResourceMapper(), getDatasetVersion(), getStatsFieldRegistry() e StatsSupportMode.AUTO
- controller deve sobrescrever getService() e getResponseId()
- nao gere classes fora do baseline resource-oriented atual
- entregue codigo compilavel na primeira tentativa, sem stubs
```

## Checklist do recurso

Antes de concluir:

- o controller usa `@ApiResource(value = ..., resourceKey = ...)`
- `resourceKey` representa a semantica do recurso
- o recurso nao usa DTO unico
- `FilterDTO implements GenericFilterDTO` com `@Filterable` e `@UISchema`
- entidade declara `@OptionLabel` para alimentar `/options/filter` e `/options/by-ids`
- repository estende `BaseCrudRepository`
- service sobrescreve `getResourceMapper()`, `getDatasetVersion()` e `getStatsFieldRegistry()` (quando houver dimensoes/metricas analiticas)
- controller sobrescreve `getService()` e `getResponseId()`
- `/schemas/filtered` resolve request e response
- `/schemas/catalog` continua sendo discovery documental, nao fonte estrutural
- `/schemas/surfaces` e `/schemas/actions` so expoem referencias canonicas
- `GET /{resource}/capabilities` agrega sem redefinir contrato

## Referencias

- `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
- `docs/architecture-overview.md`
