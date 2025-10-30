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
- Itens médios/grandes → `multiSelect` e dica de filtro `filterControlType = multiColumnComboBox`

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
- `roles` como `array<string enum[...]>` com 50 valores → `multiSelect` e `filterControlType = multiColumnComboBox`

## Referências

- Javadoc: [`OpenApiUiUtils`](../apidocs/org/praxisplatform/uischema/util/OpenApiUiUtils.html)
- Javadoc: [`CustomOpenApiResolver`](../apidocs/org/praxisplatform/uischema/extension/CustomOpenApiResolver.html)
