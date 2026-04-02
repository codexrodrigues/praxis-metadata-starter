# Guia 01 - IA Backend - Aplicacao Nova com Core Canonico Atual

## Objetivo

Este guia orienta a criacao de uma nova aplicacao Spring Boot sobre o estado atual do
`praxis-metadata-starter`.

O baseline canonico nao e mais o legado `AbstractCrudController`.
Uma aplicacao nova deve nascer sobre:

- `AbstractResourceController`
- `AbstractReadOnlyResourceController`
- `AbstractBaseResourceService`
- `AbstractReadOnlyResourceService`
- `ResourceMapper`
- `@ApiResource(value = ..., resourceKey = ...)`
- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

## Resultado esperado

Uma aplicacao nova deve sair com:

- contrato metadata-driven em OpenAPI + `x-ui`
- um primeiro recurso canonico resource-oriented
- DTOs separados de `response`, `create`, `update` e `filter`
- discovery estrutural e discovery semantico alinhados ao starter

## Dependencias minimas

Exemplo base:

```xml
<dependency>
  <groupId>io.github.codexrodrigues</groupId>
  <artifactId>praxis-metadata-starter</artifactId>
  <version>5.0.0-rc.2</version>
</dependency>

<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

Observacoes:

- o starter ja publica `spring-boot-starter-validation`, mas o host pode manter a dependencia explicita sem problema
- o host ainda precisa declarar driver real de banco, Flyway e plugins de build quando aplicavel

## Estrutura recomendada

```text
src/main/java/{base-package}/
|-- {App}Application.java
|-- constants/
|   `-- ApiPaths.java
`-- {modulo}/
    |-- controller/
    |-- dto/
    |   `-- filter/
    |-- entity/
    |-- mapper/
    |-- repository/
    `-- service/
```

## Como pensar `resourcePath` vs `resourceKey`

Antes de criar o primeiro controller, trate os dois campos como responsabilidades diferentes:

- `resourcePath`: URL operacional real do recurso, por exemplo `/api/human-resources/employees`
- `resourceKey`: identidade semantica estavel do recurso, por exemplo `human-resources.employees`

Na plataforma Praxis, `resourceKey` e a chave usada por discovery e capabilities.
Ele alimenta consultas como:

- `GET /schemas/surfaces?resource={resourceKey}`
- `GET /schemas/actions?resource={resourceKey}`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

Regra pratica:

- se a URL mudar, tente preservar o mesmo `resourceKey`
- so mude o `resourceKey` quando a semantica canonica do recurso realmente mudou

## Primeiro recurso canonico

O primeiro recurso de uma aplicacao nova deve nascer com:

- `ResponseDTO`
- `CreateDTO`
- `UpdateDTO`
- `FilterDTO`
- `ResourceMapper`
- `Repository`
- `Service`
- `Controller`

Se houver um caso real de escrita parcial por intencao:

- `@ResourceIntent`
- endpoint `PATCH` tipado real
- `@UiSurface` apenas se a UX precisar de discovery semantico

Se houver um caso real de workflow:

- `@WorkflowAction`
- endpoint tipado real, por exemplo `POST /{id}/actions/approve`

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

O exemplo acima deixa explicita a separacao correta:

- `ApiPaths.HumanResources.EMPLOYEES` define o endereco HTTP do recurso
- `"human-resources.employees"` define a identidade semantica que o starter usa em discovery

## Read-only canonico

```java
@RestController
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

## Resource mapper canonico

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

## Discovery que a aplicacao nova deve publicar

No baseline atual, um host novo deve expor pelo menos:

- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

Os catalogos nao compartilham a mesma semantica de ausencia:

- `/schemas/surfaces?resource=...` continua publicando surfaces automaticas de `create`, `list`,
  `detail` e `edit` para controllers canonicos.
- `/schemas/actions?resource=...` so existe quando houver pelo menos uma `@WorkflowAction`
  explicita; sem workflow anotado, o retorno esperado e `404`.
- `GET /{resource}/capabilities` agrega o que existir e normaliza ausencia de `surfaces` ou `actions` para listas
  vazias.
- em catalogos globais, entradas `ITEM` de `surfaces` e `actions` sao discovery-only e tendem a
  sair com `availability.allowed=false` ate que exista `resourceId` real.

## O que nao usar em aplicacao nova

- `AbstractCrudController`
- `AbstractBaseCrudService`
- DTO unico para leitura e escrita
- dispatcher generico de workflow
- schema inline em `surfaces`, `actions` ou `capabilities`

## Prompt recomendado para IA

```text
Voce esta criando uma nova aplicacao Spring Boot sobre o baseline atual do praxis-metadata-starter.

Use o core canonico atual:
- AbstractResourceController / AbstractReadOnlyResourceController
- AbstractBaseResourceService / AbstractReadOnlyResourceService
- ResourceMapper
- @ApiResource(value=..., resourceKey=...)
- DTOs separados de response, create, update e filter

Gere:
- pom.xml coerente
- classe principal Spring Boot
- ApiPaths local
- modulo inicial resource-oriented completo

Nao gere AbstractCrudController nem BaseCrudService.
Nao gere payload inline em catalogos semanticos.
```

## Checklist minimo

Antes de concluir a aplicacao nova:

- `mvn test`
- `GET /v3/api-docs/{grupo}`
- `GET /schemas/filtered`
- `GET /schemas/catalog`
- `GET /schemas/surfaces?resource={resourceKey}` com surfaces automaticas coerentes para o tipo de controller
- `GET /schemas/actions?resource={resourceKey}` se houver `@WorkflowAction`; sem workflow explicito, validar `404`
- `GET /{resource}/{id}/surfaces` e `GET /{resource}/{id}/actions` quando houver discovery `ITEM`
- `GET /{resource}/capabilities`
- validacao `@Valid` funcionando com `400 Validation error`

## Referencias

- `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `docs/guides/GUIA-03-MIGRACAO-CONSUMIDOR-PILOTO.md`
- `docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
- `docs/technical/RESOURCE-SURFACE-ACTION-ARCHITECTURE-PLAN.md`
