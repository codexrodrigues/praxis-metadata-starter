# Especificacao de Metadados (x-ui) - v1.0.0

## Objetivo

- Padronizar, de forma agnostica de tecnologia, o contrato de metadados que a UI Praxis consome.
- Permitir que qualquer backend produza JSON compativel e valide localmente contra os JSON Schemas desta pasta.

## Escopo de validacao (JSON)

- x-ui de campo (por propriedade OpenAPI): `x-ui-field.schema.json`
- x-ui em nivel de operacao (por path+operation do OpenAPI): `x-ui-operation.schema.json`
- x-ui.resource no payload final consumido pela UI (`/schemas/filtered`): `x-ui-resource.schema.json`
- x-ui.chart como draft validavel: `x-ui-chart.schema.json`
- checklist de publicacao do draft: `x-ui-chart-publication-checklist.md`

## Drafts canonicos em andamento

- Charts metadata-driven: `x-ui-chart-rfc.md`
  - define a direcao canonica de `x-ui.chart` como extensao governada da plataforma
  - complementado por `x-ui-chart.schema.json` como draft validavel inicial
  - publicacao assistida por `x-ui-chart-publication-checklist.md`, com gates minimos de contrato, host operacional e compatibilidade de consumidor

## Vocabulario (resumo)

- Campo (x-ui por propriedade)
  - Identificacao/Apresentacao: `name`, `label`, `description`, `group`, `order`, `width`
  - Tipo/Componente: `type` (enum), `controlType` (enum), `placeholder`, `defaultValue`
  - Estado/Validacao: `disabled`, `readOnly`, `editable`, `unique`, `mask`, `sortable`, `filterable`
  - Visibilidade: `hidden`, `tableHidden`, `formHidden`
  - Dependencias/Condicionais: `conditionalDisplay`, `dependentField`, `resetOnDependentChange`
  - Layout/Icone: `hint`, `helpText`, `tooltipOnHover`, `icon*`
  - Selecao/Opcaoes: `options[]`, `endpoint`, `valueField`, `displayField`, `multiple`, `emptyOptionText`
    - No runtime Angular, a UI Praxis normaliza esses campos para `resourcePath`, `optionValueKey` e `optionLabelKey`.
  - Numerico: `numericFormat` (enum), `numericStep`, `numericMin`, `numericMax`, `numericMaxLength`
  - Validacao (top-level): `required`, `minLength`, `maxLength`, `min`, `max`, `pattern`, `range`, mensagens (`*Message`), alem de `email`, `url`, `matchField`, `uniqueValidator`, `customValidator`, `asyncValidator`, `minWords`, `validationTrigger(s)`, `validationDebounce`, `showInlineErrors`, `errorPosition`
  - Legado (opcional): bloco `validation{}` com chaves basicas
- Operacao (x-ui por operacao)
  - `displayColumns` (string[]), `displayFields` (string[]), `filterFields` (string[]), `responseSchema` (string), `relatedEntitiesEndpoints` (string[])
- Recurso (x-ui.resource)
  - `idField` (string), `idFieldValid` (boolean), `idFieldMessage?` (string, opcional)
  - `readOnly` (boolean)
  - `capabilities` (mapa boolean com chaves conhecidas: `create`, `update`, `delete`, `options`, `byId`, `all`, `filter`, `cursor`; valores adicionais MAY existir e DEVEM ser boolean)
- Chart (x-ui.chart)
  - `version`, `kind`, `preset`, `source`
  - `dimensions`, `metrics`, `aggregations`, `filters`, `sort`, `limit`
  - `state`, `events`, `legend`, `labels`, `tooltip`, `theme`

## Tipos e Enums

- `type` (FieldDataType): `text | number | email | date | password | file | url | boolean | json`
- `controlType` (FieldControlType): ver enum completo no JSON Schema
- `numericFormat` (NumericFormat): `integer | decimal | currency | scientific | time | date | date-time | duration | number | fraction | percent`
- `x-ui.chart.kind`: `bar | line | pie | donut | area | stacked-bar`
- `x-ui.chart.source.kind`: `praxis.stats | derived`

## Obrigatoriedade e Defaults

- Campo (x-ui): nao ha campos obrigatorios universais. A UI consegue inferir `type/controlType` por heuristica, mas recomenda-se prover ao menos `type` e `label`.
- Operacao (x-ui): todos opcionais; sugerem preferencias de renderizacao.
- Recurso (x-ui.resource): MUST conter `idField`, `idFieldValid`, `readOnly`, `capabilities`; `idFieldMessage` e opcional e presente quando `idFieldValid=false`.
- Chart (x-ui.chart):
  - MUST conter `version`, `kind` e `source`
  - quando `source.kind = "praxis.stats"`, MUST conter `source.resource` e `source.operation`
  - quando `source.operation = "timeseries"`, MUST conter `source.options.granularity`

## Precedencia (normativa)

1. Defaults de `@UISchema` (ou equivalente no backend)
2. Deteccao automatica (OpenAPI type/format/enum + heuristicas por nome)
3. Valores explicitos de `@UISchema`
4. Bean Validation (ex.: NotBlank/Size/Pattern -> chaves de validacao no `x-ui`)
5. `extraProperties`/`custom.*` (precedencia maxima)

## Normas (MUST/SHOULD/MAY)

- MUST: chaves canonicas devem seguir tipos/semantica dos JSON Schemas.
- SHOULD: extensoes privadas usarem o prefixo `custom.`.
- MUST: `capabilities` conter apenas valores boolean; chaves adicionais sao permitidas.
- SHOULD: `/schemas/filtered` enviar `ETag` forte e `X-Schema-Hash` e expor via `Access-Control-Expose-Headers`.
- SHOULD: publicacoes de `x-ui.chart` explicitar quando o contrato canonico ainda e mais amplo que o runtime consumidor atual.
- MAY: incluir `specVersion` em um envelope/meta do payload para auditoria.

## Versionamento da especificacao

- `specVersion`: `1.0.0`. SemVer:
  - `MAJOR`: alteracoes incompativeis
  - `MINOR`: adicoes retrocompativeis
  - `PATCH`: correcoes editoriais e ajustes de descricao

## Arquivos (machine-readable)

- `x-ui-field.schema.json` - valida x-ui de campo
- `x-ui-operation.schema.json` - valida x-ui por operacao
- `x-ui-resource.schema.json` - valida x-ui.resource no payload final
- `x-ui-chart.schema.json` - valida o draft inicial de `x-ui.chart`

## Drafts e RFCs

- `x-ui-chart-rfc.md` - proposta canonica para `x-ui.chart`, separando semantica de plataforma de runtime Angular e detalhes de engine
- `x-ui-chart-publication-checklist.md` - gates minimos para publicar o draft `0.1.0` sem drift imediato entre starter, quickstart e `@praxisui/charts`

## Exemplos e Fixtures

- Em `examples/` convivem dois tipos de artefato:
  - fixtures de validacao de schema, usados como base para CI e smoke local
  - exemplos canonicos de documentacao, usados para explicar o contrato esperado
- Fixtures de validacao:
  - `examples/x-ui-field.valid.json`
  - `examples/x-ui-field.invalid.json`
  - `examples/x-ui-operation.valid.json`
  - `examples/x-ui-operation.invalid.json`
  - `examples/x-ui-resource.valid.json`
  - `examples/x-ui-resource.invalid.json`
  - `examples/x-ui-chart.valid.json`
  - `examples/x-ui-chart.invalid.json`
- Exemplos canonicos de documentacao:
  - `examples/canonical-payload.json` - payload ilustrativo combinando campo, operacao e recurso
  - `examples/x-ui-chart.valid.json` - exemplo valido do draft inicial de `x-ui.chart`

## Observacoes

- `schemaId`, `ETag` e `X-Schema-Hash` sao praticas recomendadas para cache/304 e compoem a identidade de schema, mas nao fazem parte da validacao JSON desta pasta.
- O draft `x-ui.chart` esta pronto para publicacao controlada, mas continua em estado `draft` e deve ser tratado como contrato em evolucao.
