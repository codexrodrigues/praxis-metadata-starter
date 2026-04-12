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
        controlType = FieldControlType.SEARCHABLE_SELECT,
        endpoint = "/api/human-resources/departamentos/options/filter",
        valueField = "id",
        displayField = "label",
        order = 20
    )
    private Long departmentId;

    @Filterable(operation = Filterable.FilterOperation.IN, relation = "communicationChannels.id")
    @UISchema(
        label = "Canais",
        controlType = FieldControlType.SELECTION_LIST,
        endpoint = "/api/human-resources/communication-channels/options/filter",
        valueField = "id",
        displayField = "label",
        multiple = true,
        order = 25
    )
    private java.util.List<Long> channelIds;

    @UISchema(
        label = "Visualização",
        controlType = FieldControlType.BUTTON_TOGGLE,
        options = "[{\"label\":\"Ativos\",\"value\":\"ACTIVE\"},{\"label\":\"Todos\",\"value\":\"ALL\"}]",
        order = 27
    )
    private String viewMode;

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

    @UISchema(
        label = "Cor da etiqueta",
        controlType = FieldControlType.COLOR_INPUT,
        order = 45
    )
    private String tagColor;

    // getters e setters omitidos
}
```

## Como isso aparece no OpenAPI filtrado

Quando o `CustomOpenApiResolver` processa esse DTO:

* cada campo gera `components.schemas.EmployeeFilterDTO.properties.<campo>.x-ui` com as configurações acima;
* as validações (por exemplo, `@NotNull`) seriam convertidas automaticamente em `x-ui.validation`;
* o endpoint `GET /schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=request` retornará o schema de request do filtro;
* o endpoint `GET /schemas/filtered?path=/api/human-resources/funcionarios/all&operation=get&schemaType=response` retornará o schema de response da listagem.

No Angular, o `GenericCrudService` normaliza esse contrato para a forma canônica de runtime:

* `endpoint` -> `resourcePath`
* `displayField` -> `optionLabelKey`
* `valueField` -> `optionValueKey`
* `filter` -> `filterCriteria`

Ou seja: no backend Java o contrato anotado continua sendo `endpoint`/`displayField`/`valueField`, mas a UI Praxis passa a operar internamente com `resourcePath` e chaves `option*Key`, revalidando o schema com `If-None-Match` e consumindo `ETag` e `X-Schema-Hash`.

Observações de maturidade do cenário atual:

* `SELECTION_LIST` já tem runtime Angular utilizável, mas ainda não cobre totalmente toda a superfície declarada de metadata; para filtros enterprise que dependam fortemente de `searchable` ou `selectAll`, prefira validar o comportamento final antes de padronizar esse controle em larga escala.
* `COLOR_INPUT` é a escolha padrão para cor simples: a heurística automática do starter já o infere para `format=color` e nomes contendo `cor/color`. Reserve `COLOR_PICKER` para contratos que precisem de paleta/presets ou picker rico.

## Boas práticas

* Ordene os campos (`order`) para que o frontend mantenha a consistência visual.
* Use `endpoint` para publicar combos dinâmicos no `x-ui`, preferencialmente apontando para `/{resource}/options/filter`; no Angular esse campo será normalizado para `resourcePath`.
* Declare `valueField` e `displayField` explicitamente para catálogos remotos; a UI Praxis os normaliza para `optionValueKey` e `optionLabelKey`.
* Combine `Filterable.FilterOperation` com regras de domínio quando precisar de comportamentos além dos operadores padrão.
* Quando o filtro precisar ocupar a superfície principal de forma compacta, prefira declarar `controlType` com a família canônica `INLINE_*` em vez de depender de convenções implícitas de filtro.

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
    * shape normalizado: `[min,null]` e convertido para `[min]` no backend;
    * `BETWEEN_EXCLUSIVE`: exige exatamente dois limites não nulos (`[min,max]`).
  * objeto canônico (`{ minPrice, maxPrice, currency? }` para monetário ou `{ startDate, endDate }` para datas), com regra OpenAPI para obrigar ao menos um limite (ou ambos no `BETWEEN_EXCLUSIVE`).

Regras recomendadas:

* `minPrice` e `maxPrice` opcionais (`null` permitido) para operações não exclusivas.
* modo default de filtro: aceitar parcial (`>= min` ou `<= max`).
* quando houver somente limite superior, serialize como `[null, maxPrice]` no payload canônico para preservar semântica.
* payload vazio é inválido no contrato: não envie `[null]`, `[null,null]` ou objeto sem nenhum limite efetivo.
* payload com mais de dois limites é inválido: não envie listas como `[a,b,c]`.
* payload escalar é inválido no modo estrito: não envie `1500` diretamente, use lista ou objeto canônico.
* bounds enviados explicitamente como `null` sem contrapartida válida também são inválidos e devem retornar `400`.
* `BETWEEN_EXCLUSIVE` é estrito: exige os dois limites preenchidos.
* quando `minPrice > maxPrice`, normalizar (swap) e registrar aviso de validação.
* para ranges percentuais (`NumericFormat.PERCENT` ou `format: percent`), o metadata publica `rangeSlider` com `mode=range` e defaults `min=0`, `max=100`, `step=0.01`.
* `currency` pode ser enviado como contexto de UX, mas o backend considera apenas os limites para construir o predicado.
* use sempre lista ou objeto canonico para ranges; payload escalar e invalido.
* no frontend enterprise, prefira normalização metadata-driven: só campos com `controlType` de range no schema devem ser colapsados para formato canônico.

Contrato canonico de payload:

* payload escalar de range e invalido e retorna `400`.
* nao ha flag para aceitar escalar em runtime.
* payload inválido de filtro retorna `400` com `errors[].properties.code = FILTER_PAYLOAD_INVALID`.
