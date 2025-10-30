# Exemplo de Filter DTO com Metadados x-ui

Este exemplo mostra como declarar um DTO de filtro que:

1. Usa `@Filterable` para mapear campos para Specifications Spring Data.
2. Expõe metadados `@UISchema` para que o frontend renderize os componentes corretos.
3. Demonstra a integração automática com o endpoint `/schemas/filtered`.

```java
package com.example.hr.employee.filter;

import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.annotation.FilterOperator;
import org.praxisplatform.uischema.filter.dto.FilterDefinitionDTO;
import org.praxisplatform.uischema.numeric.NumberFormatStyle;

import java.math.BigDecimal;
import java.time.LocalDate;

public class EmployeeFilterDTO implements FilterDefinitionDTO {

    @Filterable(path = "person.name", operator = FilterOperator.LIKE_IGNORE_CASE)
    @UISchema(
        label = "Nome",
        placeholder = "Busque por nome ou parte",
        controlType = "text",
        order = 10
    )
    private String name;

    @Filterable(path = "department.id")
    @UISchema(
        label = "Departamento",
        controlType = "autoComplete",
        dataEndpoint = "/api/human-resources/departamentos/options",
        optionLabelPath = "label",
        order = 20
    )
    private Long departmentId;

    @Filterable(path = "admissionDate", operator = FilterOperator.GREATER_THAN_OR_EQUAL)
    @UISchema(
        label = "Admitido a partir de",
        controlType = "date-picker",
        order = 30
    )
    private LocalDate admissionDateFrom;

    @Filterable(path = "salary", operator = FilterOperator.GREATER_THAN_OR_EQUAL)
    @UISchema(
        label = "Salário mínimo",
        controlType = "currency-input",
        numericFormat = NumberFormatStyle.CURRENCY,
        numericStep = "0.01",
        order = 40
    )
    private BigDecimal salaryMin;

    // getters e setters omitidos
}
```

## Como isso aparece no OpenAPI filtrado

Quando o `CustomOpenApiResolver` processa esse DTO:

* cada campo gera `components.schemas.EmployeeFilterDTO.properties.<campo>.x-ui` com as configurações acima;
* as validações (por exemplo, `@NotNull`) seriam convertidas automaticamente em `x-ui.validation`;
* o endpoint `/schemas/filtered?path=/api/human-resources/funcionarios/all` retornará apenas os campos relevantes.

## Boas práticas

* Ordene os campos (`order`) para que o frontend mantenha a consistência visual.
* Use `dataEndpoint` para combos dinâmicos e exponha o endpoint correspondente via `AbstractCrudController`.
* Combine `FilterOperator` com enums personalizados caso precise de comportamentos além dos operadores padrão.
