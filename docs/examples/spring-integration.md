# Integração Spring Boot com o Praxis Metadata Starter

Este guia demonstra uma aplicação Spring Boot mínima consumindo o starter e expondo metadados x-ui completos.

## Dependência Maven

```xml
<dependency>
    <groupId>io.github.codexrodrigues</groupId>
    <artifactId>praxis-metadata-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuração de Pacotes

```java
@SpringBootApplication(scanBasePackages = {
    "com.example.hr",
    "org.praxisplatform.uischema"
})
public class HrApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrApplication.class, args);
    }
}
```

* O `scanBasePackages` garante que os beans auto-configurados pelo starter sejam descobertos.
* Alternativamente, importe explicitamente `OpenApiUiSchemaAutoConfiguration` em testes slice.

## Controller CRUD com Metadados

```java
@RestController
@RequestMapping("/api/human-resources/funcionarios")
@ApiResource("human-resources/funcionarios")
public class EmployeeController extends AbstractCrudController<
        Employee, EmployeeDTO, Long, EmployeeFilterDTO> {

    public EmployeeController(EmployeeService service) {
        super(service);
    }
}
```

* `@ApiResource` registra o controller para a estratégia dupla de grupos OpenAPI.
* `AbstractCrudController` expõe endpoints prontos (`/all`, `/page`, `/options`).

## DTO com `@UISchema`

```java
public class EmployeeDTO {

    @UISchema(label = "Nome completo", order = 10, required = true)
    @NotBlank @Size(max = 120)
    private String fullName;

    @UISchema(label = "CPF", mask = "999.999.999-99", order = 20)
    @Pattern(regexp = "^(\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}|\\d{11})$")
    private String cpf;

    @UISchema(label = "Departamento", controlType = "autoComplete", order = 30,
              dataEndpoint = "/api/human-resources/departamentos/options")
    private Long departmentId;

    // getters e setters
}
```

* O `CustomOpenApiResolver` converte `@NotBlank`, `@Size` e `@Pattern` em mensagens padrão (`x-ui.validation`).
* Campos com `dataEndpoint` usam o `OptionMapper` padrão do `BaseCrudService`.

## Consultando o Schema Enriquecido

```
GET /schemas/filtered?path=/api/human-resources/funcionarios/all
Accept: application/json
```

Resposta (trecho simplificado):

```json
{
  "schemaId": "EmployeeDTO",
  "etag": "\"8b872aee\"",
  "properties": {
    "fullName": {
      "type": "string",
      "maxLength": 120,
      "x-ui": {
        "label": "Nome completo",
        "order": 10,
        "validation": {
          "required": true,
          "requiredMessage": "Campo obrigatório",
          "maxLength": 120,
          "maxLengthMessage": "Máximo de 120 caracteres"
        }
      }
    }
  }
}
```

## Próximos passos

* Cadastre filtros adicionais seguindo o [exemplo de Filter DTO](filter-dto.md).
* Utilize `If-None-Match` e `ETag` para caching eficiente no frontend.
* Explore as auto-configurações disponíveis em [`docs/architecture-overview.md`](../architecture-overview.md).
