# Guia 02 - IA Backend - Recurso Metadata-Driven no Core Atual

## Objetivo

Este guia orienta a implementacao de um recurso backend metadata-driven alinhado ao core atual do
`praxis-metadata-starter`.

O baseline correto hoje e `resource-oriented`.
Este guia nao deve gerar:

- `AbstractCrudController`
- `AbstractBaseCrudService`
- DTO unico de leitura/escrita

## Entrada minima para a LLM

No minimo:

1. entidade JPA ou sua estrutura
2. `resourcePath`
3. `resourceKey`
4. grupo OpenAPI
5. pacote base

Exemplo:

```text
Gere um recurso metadata-driven canonico.

Entrada:
- Entidade: src/main/java/com/example/hr/entity/Employee.java
- Resource path: /api/human-resources/employees
- Resource key: human-resources.employees
- Api group: human-resources
- Pacote base: com.example.hr
```

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

## DTOs canonicos

Use sempre:

- `{Resource}ResponseDTO` para leitura
- `Create{Resource}DTO` para `POST`
- `Update{Resource}DTO` para `PUT` base
- `{Resource}FilterDTO` para query/filter

No baseline atual do starter, `PATCH /{id}` nao faz parte do core HTTP canonico. Use `PATCH`
apenas em operacoes explicitas por intencao com `@ResourceIntent`.

Se existir escrita parcial por intencao:

- `Update{Resource}{Intent}DTO`
- endpoint `PATCH /{id}/{intent}`
- `@ResourceIntent`

## Controller canonico

```java
@RestController
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

## Read-only canonico

Quando o recurso for apenas leitura:

- controller: `AbstractReadOnlyResourceController`
- service: `AbstractReadOnlyResourceService`
- sem endpoints de escrita publicados

## Quando adicionar ResourceIntent, UiSurface e WorkflowAction

### `@ResourceIntent`

Use quando a operacao ainda e manutencao do recurso, mas com DTO parcial e semantica propria.

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

Exemplo:

```java
@PatchMapping("/{id}/profile")
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

Exemplo:

```java
@PostMapping("/{id}/actions/approve")
@WorkflowAction(id = "approve", title = "Aprovar", scope = ActionScope.ITEM)
```

## O que o recurso deve expor no runtime

Para um recurso mutavel no core atual, o baseline esperado inclui:

- `GET /{resource}/all`
- `GET /{resource}/{id}`
- `POST /{resource}`
- `PUT /{resource}/{id}`
- `POST /{resource}/filter`
- `POST /{resource}/filter/cursor`
- `POST /{resource}/locate`
- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

## O que nao deve ser gerado

- schema inline em catalogos
- action router generico
- payload generico por string
- duplicacao `V1/V2`
- contrato paralelo para read-only com `405` herdado

## Checklist do recurso

Antes de concluir:

- o controller usa `@ApiResource(value = ..., resourceKey = ...)`
- o recurso nao usa DTO unico
- `@Valid` funciona de verdade
- `/schemas/filtered` resolve request e response
- `/schemas/surfaces` e `/schemas/actions` so expoem referencias canonicas
- `GET /{resource}/capabilities` agrega sem redefinir contrato

## Referencias

- `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
- `docs/technical/RESOURCE-ORIENTED-PILOT-IN-SRC-TEST.md`

