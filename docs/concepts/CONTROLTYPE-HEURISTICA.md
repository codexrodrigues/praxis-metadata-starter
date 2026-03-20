# Heurística de ControlType (INPUT vs TEXTAREA)

Esta página documenta a heurística que determina `controlType` para campos `string`, combinando tamanho (maxLength) e nome do campo.

## Regras Atualizadas

- Threshold para `textarea`:
  - Antes: `maxLength > 100`
  - Agora: `maxLength > 300`
- Detecção por nome (maior precedência que detecção por tamanho):
  - Força `INPUT` para: `nome`, `name`, `titulo`, `title`, `assunto`, `subject`
  - Força `TEXTAREA` para: `descricao`, `observacao`, `description`, `comment`

### Enums (string)

- Cardinalidade controla o tipo de UI:
  - Pequeno (≤5) → `radio`
  - Médio (6–25) → `select`
  - Grande (>25) → `autoComplete`

### Booleanos

- Padrão → `checkbox` (ou `toggle`).
- Enum textual binária (ex.: "Sim/Não", "Yes/No") → `radio`.

### Arrays de enums

- Itens enum pequenos (≤5) → `chipInput`
- Itens médios/grandes → `multiSelect`

### Família `INLINE_*`

- A heurística automática não promove variantes `INLINE_*` por conta própria.
- Quando a superfície canônica exigir um controle compacto, declare explicitamente `@UISchema(controlType = FieldControlType.INLINE_...)`.
- As variantes base `SEARCHABLE_SELECT` e `ASYNC_SELECT` também devem ser declaradas explicitamente quando o backend precisar publicar esses componentes dedicados para o Angular.
- `BUTTON_TOGGLE` e `SELECTION_LIST` devem ser declarados explicitamente; a heurística automática não deve inferi-los por convenção implícita.
- Quando esses controles consumirem catálogos remotos, o backend continua publicando `endpoint`, `displayField` e `valueField`; a UI Praxis normaliza esses campos para `resourcePath`, `optionLabelKey` e `optionValueKey`.
- Exemplos frequentes:
  - `SEARCHABLE_SELECT`
  - `ASYNC_SELECT`
  - `BUTTON_TOGGLE`
  - `SELECTION_LIST`
  - `COLOR_INPUT`
  - `INLINE_SELECT`
  - `INLINE_SEARCHABLE_SELECT`
  - `INLINE_MULTISELECT`
  - `INLINE_DATE`
  - `INLINE_DATE_RANGE`
  - `INLINE_RATING`
  - `INLINE_RELATIVE_PERIOD`
- O contrato canônico do starter publica apenas `controlType` com paridade dinâmica no Angular; novas superfícies compactas devem usar o vocabulário `INLINE_*`.
- `COLOR_INPUT` deve ser usado para captura direta de uma cor única; a heurística automática do starter também o infere para `format=color` e nomes contendo `cor/color`.
- `COLOR_PICKER` permanece a opção rica quando o contrato exigir paleta, presets ou picker expandido, e deve ser declarado explicitamente nesses casos.
- `SELECTION_LIST` já existe no runtime Angular, mas ainda tem maturidade parcial de superfície; use-o quando a lista visível for realmente a UX desejada e não depender de cobertura completa de `searchable`/`selectAll`.
- `INLINE_RELATIVE_PERIOD` é normalizado semanticamente no starter antes da desserialização do DTO. O timezone padrão é `UTC`, com override via `praxis.filter.relative-period.zone-id`.

### Percent/Numéricos

- `format="percent"` → `numericStep=0.01`, `placeholder="0–100%"`, `numericMin=0`, `numericMax=100` (se ausentes)
- `@Digits(fraction=n)` → `numericStep` = passo a partir das casas decimais (ex.: 2 → `0.01`, se ausente)

## Precedência Geral

1) Valor explícito em `@UISchema` (vence tudo)
2) Heurística por nome (INPUT/TEXTAREA acima de detecção por tamanho)
3) Detecção por schema (type/format/enum)
4) Defaults

> Observação: formatos especiais e enum (ex.: `email`, `password`, `url`, `date/time`, `enum → select`) continuam tendo precedência e não são sobrescritos pela heurística de nome.

## Exemplos

- `nomeCompleto` com `@Size(max=200)` → `controlType = input`
- `descricao` com `@Size(max=1000)` → `controlType = textarea`
- `email` com `format=email` → `controlType = email-input` (não alterado)
- `status` com 3 valores → `radio`
- `pais` com 10 valores → `select`
- `cidade` com 100 valores → `autoComplete`
- `habilitado` boolean (sem enum) → `checkbox`
- `roles` como `array<string enum[...]>` com 50 valores → `multiSelect`

## Referências

- Javadoc: [`OpenApiUiUtils`](../apidocs/org/praxisplatform/uischema/util/OpenApiUiUtils.html)
- Javadoc: [`CustomOpenApiResolver`](../apidocs/org/praxisplatform/uischema/extension/CustomOpenApiResolver.html)
