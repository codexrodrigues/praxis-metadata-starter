# Guia 02 - IA Backend - Recurso Metadata-Driven no Core Atual

## Objetivo

Este guia orienta a implementacao de um recurso backend metadata-driven alinhado
ao core atual do `praxis-metadata-starter`.

Ele foca no caso mais comum: a aplicacao ja existe, e a LLM precisa adicionar um
novo recurso sem cair no core legado removido nem gerar codigo incompleto.

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

Peça explicitamente para a LLM gerar nesta ordem:

1. DTOs
2. repository
3. mapper
4. service
5. controller
6. apenas depois intents, surfaces e workflow actions

Isso evita dois erros frequentes:

- controller dependente de tipos ainda inexistentes
- service sem mapper correto

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

- `{Resource}ResponseDTO` para leitura
- `Create{Resource}DTO` para `POST`
- `Update{Resource}DTO` para `PUT`
- `{Resource}FilterDTO` para query/filter

Regras que a LLM deve obedecer:

- `FilterDTO` deve implementar `GenericFilterDTO`
- DTOs de escrita devem carregar `@Valid` e constraints reais quando houver
- `ResponseDTO` deve expor o ID retornado pelo controller

## Repository minimo

```java
public interface EmployeeRepository extends BaseCrudRepository<Employee, Long> {
}
```

Sem isso, o service nao encaixa no core atual.

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
    public void applyUpdate(Employee entity, UpdateEmployeeDTO dto) { ... }

    @Override
    public Long extractId(Employee entity) {
        return entity.getId();
    }
}
```

Metodos obrigatorios:

- `toResponse`
- `newEntity`
- `applyUpdate`
- `extractId`

## Service canonico

```java
@Service
public class EmployeeService extends AbstractBaseResourceService<
        Employee,
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    private final EmployeeMapper mapper;

    public EmployeeService(EmployeeRepository repository, EmployeeMapper mapper) {
        super(repository, Employee.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<Employee, EmployeeResponseDTO, CreateEmployeeDTO, UpdateEmployeeDTO, Long> getResourceMapper() {
        return mapper;
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

## Controller canonico

```java
@ApiResource(value = ApiPaths.HumanResources.EMPLOYEES, resourceKey = "human-resources.employees")
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

Para um recurso mutavel no core atual, espere:

- `GET /{resource}/all`
- `GET /{resource}/{id}`
- `POST /{resource}`
- `PUT /{resource}/{id}`
- `POST /{resource}/filter`
- `POST /{resource}/filter/cursor`
- `POST /{resource}/locate`
- `POST /{resource}/options/filter`
- `GET /{resource}/options/by-ids`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

Para recurso read-only, nao espere:

- `POST /{resource}`
- `PUT /{resource}/{id}`
- `DELETE /{resource}/{id}`

## O que nao deve ser gerado

- schema inline em catalogos
- action router generico
- payload generico por string
- duplicacao `v1/v2`
- DTO unico para leitura e escrita
- classes do core legado removido

## Prompt recomendado para adicionar um recurso novo

```text
Adicione um novo recurso metadata-driven ao projeto Spring Boot existente.

Use obrigatoriamente:
- @ApiResource(value = ..., resourceKey = ...)
- @ApiGroup
- BaseCrudRepository
- ResourceMapper
- AbstractBaseResourceService ou AbstractReadOnlyResourceService
- AbstractResourceController ou AbstractReadOnlyResourceController

Gere, nesta ordem:
1. DTOs
2. repository
3. mapper
4. service
5. controller

Regras:
- nao use @RestController junto com @ApiResource
- FilterDTO deve implementar GenericFilterDTO
- mapper deve implementar toResponse, newEntity, applyUpdate, extractId
- service deve sobrescrever getResourceMapper()
- controller deve sobrescrever getService() e getResponseId()
- nao gere classes do core legado removido
- entregue codigo compilavel, sem stubs
```

## Checklist do recurso

Antes de concluir:

- o controller usa `@ApiResource(value = ..., resourceKey = ...)`
- `resourceKey` representa a semantica do recurso
- o recurso nao usa DTO unico
- `FilterDTO implements GenericFilterDTO`
- repository estende `BaseCrudRepository`
- service sobrescreve `getResourceMapper()`
- controller sobrescreve `getService()` e `getResponseId()`
- `/schemas/filtered` resolve request e response
- `/schemas/surfaces` e `/schemas/actions` so expoem referencias canonicas
- `GET /{resource}/capabilities` agrega sem redefinir contrato

## Referencias

- `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
- `docs/architecture-overview.md`
