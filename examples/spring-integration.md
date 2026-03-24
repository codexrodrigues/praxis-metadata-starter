# Integração Spring Boot com o Praxis Metadata Starter

Este guia mostra uma integração mínima e atual do `praxis-metadata-starter` em uma aplicação Spring Boot.

O foco aqui é:

- dependência mínima correta
- controller compatível com `AbstractCrudController`
- DTO com `@UISchema` coerente com `/schemas/filtered`
- selects remotos compatíveis com `OptionDTO{id,label}`

## Dependência Maven

```xml
<dependency>
    <groupId>io.github.codexrodrigues</groupId>
    <artifactId>praxis-metadata-starter</artifactId>
    <version>2.0.0-rc.7</version>
</dependency>
```

Complementos comuns:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

## Classe principal

Na maior parte dos casos, a auto-configuração padrão do Spring Boot é suficiente:

```java
@SpringBootApplication
public class HrApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrApplication.class, args);
    }
}
```

Nao use `scanBasePackages` para forçar descoberta de `org.praxisplatform.uischema` sem necessidade comprovada.

## Controller CRUD com metadados

Exemplo alinhado ao contrato atual:

```java
@RestController
@ApiResource(ApiPaths.HumanResources.FUNCIONARIOS)
@ApiGroup("human-resources")
public class EmployeeController extends AbstractCrudController<
        Employee, EmployeeDTO, Long, EmployeeFilterDTO> {

    private final EmployeeService service;
    private final EmployeeMapper mapper;

    public EmployeeController(EmployeeService service, EmployeeMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    protected BaseCrudService<Employee, EmployeeDTO, Long, EmployeeFilterDTO> getService() {
        return service;
    }

    @Override
    protected EmployeeDTO toDto(Employee entity) {
        return mapper.toDto(entity);
    }

    @Override
    protected Employee toEntity(EmployeeDTO dto) {
        return mapper.toEntity(dto);
    }

    @Override
    protected Long getEntityId(Employee entity) {
        return entity.getId();
    }

    @Override
    protected Long getDtoId(EmployeeDTO dto) {
        return dto.getId();
    }
}
```

Observações:

- use `@ApiResource` com o path completo do recurso
- `AbstractCrudController` expõe endpoints como `/all`, `/filter`, `/filter/cursor`, `/locate`, `/options/filter`, `/options/by-ids`, CRUD e `/schemas`
- o host pode sobrescrever métodos do controller para enriquecer a documentação OpenAPI, como faz o quickstart

## DTO com `@UISchema`

Exemplo compatível com o quickstart e com o consumo do Angular:

```java
public class EmployeeDTO {

    private Long id;

    @NotBlank
    @Size(max = 120)
    @UISchema(label = "Nome completo", required = true, maxLength = 120, order = 10)
    private String fullName;

    @Pattern(regexp = "^(\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}|\\d{11})$")
    @UISchema(
        label = "CPF",
        controlType = FieldControlType.CPF_CNPJ_INPUT,
        mask = "000.000.000-00",
        order = 20
    )
    private String cpf;

    @UISchema(
        label = "Departamento",
        controlType = FieldControlType.SELECT,
        endpoint = ApiPaths.HumanResources.DEPARTAMENTOS + "/options/filter",
        valueField = "id",
        displayField = "label",
        tableHidden = true,
        order = 30
    )
    private Long departmentId;
}
```

Pontos importantes:

- use `endpoint`, nao `dataEndpoint`
- para `.../options/filter`, use `displayField = "label"`
- o backend publicará esse contrato via `x-ui` em `/schemas/filtered`

## Consultando o schema enriquecido

Grid/response:

```text
GET /schemas/filtered?path=/api/human-resources/funcionarios/all&operation=get&schemaType=response
```

Filtro/request:

```text
GET /schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=request
```

Resposta típica:

```json
{
  "type": "object",
  "properties": {
    "departmentId": {
      "type": "integer",
      "x-ui": {
        "controlType": "select",
        "endpoint": "/api/human-resources/departamentos/options/filter",
        "valueField": "id",
        "displayField": "label"
      }
    }
  },
  "x-ui": {
    "resource": {
      "idField": "id"
    }
  }
}
```

## Integração com o Angular

O `GenericCrudService` do `praxis-ui-angular` consome esse contrato assim:

- `getSchema()` deriva `.../all + get + response`
- `getFilteredSchema()` deriva `.../filter + post + request`
- usa `If-None-Match` para revalidar
- lê `ETag`, `X-Schema-Hash` e `x-ui.resource.idField`

Isso significa que o backend deve publicar:

- `/schemas/filtered`
- `ETag` e `X-Schema-Hash`
- `displayField = "label"` quando o select usa `OptionDTO`

## Próximos passos

- Cadastre filtros adicionais seguindo o [exemplo de Filter DTO](filter-dto.md).
- Use `CorporateMapperConfig` se o projeto adotar MapStruct.
- Consulte o `praxis-api-quickstart` como referência operacional ponta a ponta.
