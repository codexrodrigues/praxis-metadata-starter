# Exemplo de Filter DTO com Metadados x-ui

Este exemplo mostra como declarar um DTO de filtro que:

1. Usa `@Filterable` para mapear campos para Specifications Spring Data.
2. Expõe metadados `@UISchema` para que o frontend renderize os componentes corretos.
3. Demonstra a integração automática com o endpoint `/schemas/filtered`.

```java
package com.example.hr.employee.filter;

import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.math.BigDecimal;
import java.time.LocalDate;

public class EmployeeFilterDTO implements GenericFilterDTO {

    @Filterable(operation = Filterable.FilterOperation.LIKE, relation = "person.name")
    @UISchema(
        label = "Nome",
        placeholder = "Busque por nome ou parte",
        controlType = FieldControlType.INPUT,
        order = 10
    )
    private String name;

    @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "department.id")
    @UISchema(
        label = "Departamento",
        controlType = FieldControlType.AUTO_COMPLETE,
        endpoint = "/api/human-resources/departamentos/options",
        displayField = "label",
        order = 20
    )
    private Long departmentId;

    @Filterable(operation = Filterable.FilterOperation.GREATER_OR_EQUAL, relation = "admissionDate")
    @UISchema(
        label = "Admitido a partir de",
        controlType = FieldControlType.DATE_PICKER,
        order = 30
    )
    private LocalDate admissionDateFrom;

    @Filterable(operation = Filterable.FilterOperation.GREATER_OR_EQUAL, relation = "salary")
    @UISchema(
        label = "Salário mínimo",
        controlType = FieldControlType.CURRENCY_INPUT,
        numericFormat = NumericFormat.CURRENCY,
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
* Use `endpoint` para combos dinâmicos e exponha o endpoint correspondente via `AbstractCrudController`.
* Combine `Filterable.FilterOperation` com regras de domínio quando precisar de comportamentos além dos operadores padrão.

## Padrão recomendado para faixa monetária (enterprise)

Para filtros monetários, prefira um objeto com nomes explícitos em vez de lista posicional:

```json
{
  "salaryRange": {
    "minPrice": 6500,
    "maxPrice": 15000,
    "currency": "BRL"
  }
}
```

Contrato OpenAPI publicado para ranges:

* o schema de campos `@Filterable` com operação de range publica `oneOf` com duas variantes aceitas:
  * array canônico:
    * `BETWEEN`/`NOT_BETWEEN`/`OUTSIDE_RANGE`: `[min]`, `[min,max]` ou `[null,max]`;
    * compatibilidade legado: `[min,null]` é aceito e normalizado para `[min]` no backend;
    * `BETWEEN_EXCLUSIVE`: exige exatamente dois limites não nulos (`[min,max]`).
  * objeto canônico (`{ minPrice, maxPrice, currency? }` para monetário ou `{ startDate, endDate }` para datas), com regra OpenAPI para obrigar ao menos um limite (ou ambos no `BETWEEN_EXCLUSIVE`).

Regras recomendadas:

* `minPrice` e `maxPrice` opcionais (`null` permitido) para operações não exclusivas.
* modo default de filtro: aceitar parcial (`>= min` ou `<= max`).
* quando houver somente limite superior, serialize como `[null, maxPrice]` no payload canônico para preservar semântica.
* payload vazio é inválido no contrato: não envie `[null]`, `[null,null]` ou objeto sem nenhum limite efetivo.
* payload com mais de dois limites é inválido: não envie listas como `[a,b,c]`.
* bounds enviados explicitamente como `null` sem contrapartida válida também são inválidos e devem retornar `400`.
* `BETWEEN_EXCLUSIVE` é estrito: exige os dois limites preenchidos.
* quando `minPrice > maxPrice`, normalizar (swap) e registrar aviso de validação.
* `currency` pode ser enviado como contexto de UX, mas o backend considera apenas os limites para construir o predicado.
* manter `between[]` apenas como compatibilidade com legado.
* no frontend enterprise, prefira normalização metadata-driven: só campos com `controlType` de range no schema devem ser colapsados para formato canônico.
