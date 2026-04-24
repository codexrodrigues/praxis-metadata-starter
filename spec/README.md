# Especificacao de Metadados (x-ui) - v1.0.0

## Objetivo

- Padronizar, de forma agnostica de tecnologia, o contrato de metadados que a UI Praxis consome.
- Permitir que qualquer backend produza JSON alinhado ao contrato e valide localmente contra os JSON Schemas desta pasta.

## Escopo de validacao (JSON)

- x-ui de campo (por propriedade OpenAPI): `x-ui-field.schema.json`
- x-ui em nivel de operacao (por path+operation do OpenAPI): `x-ui-operation.schema.json`
- x-ui.resource no payload final consumido pela UI (`/schemas/filtered`): `x-ui-resource.schema.json`
- x-ui.analytics como draft validavel: `x-ui-analytics.schema.json`
- x-ui.chart como draft validavel: `x-ui-chart.schema.json`
- checklist de publicacao do draft: `x-ui-chart-publication-checklist.md`
- catalogo semantico de dominio: `domain-catalog.md`

## Drafts canonicos em andamento

- Charts metadata-driven: `x-ui-chart-rfc.md`
  - define a direcao canonica de `x-ui.chart` como extensao governada da plataforma
  - complementado por `x-ui-chart.schema.json` como draft validavel inicial
  - publicacao assistida por `x-ui-chart-publication-checklist.md`, com gates minimos de contrato, host operacional e consumo pelo runtime oficial
- Analytics semantics metadata-driven: `x-ui-analytics-rfc.md`
  - define a direcao canonica de `x-ui.analytics` como projecao semantica analitica de operacao
  - usa `projections[]` para manter a neutralidade de `praxis.stats`
  - convive com `x-ui.chart`, que permanece como especializacao opcional
- Option sources metadata-driven: `x-ui-option-source-rfc.md`
  - define a direcao canonica para fontes de opcoes derivadas em filtros metadata-driven
  - cobre a lacuna entre recursos CRUD com `/options/filter`, dimensoes categoricas derivadas e buckets governados
  - prepara a publicacao futura de `x-ui.optionSource` em `/schemas/filtered`
  - permanece em estado `draft`; a RFC nao implica implementacao automatica no starter ou no runtime Angular
- Domain catalog AI-operable: `domain-catalog.md`
  - define `/schemas/domain` como vocabulario semantico derivado de actions, surfaces, option sources e schemas
  - documenta `x-domain-governance` como materializacao OpenAPI de `@DomainGovernance`
  - separa governanca de dominio do contrato estrutural de `/schemas/filtered`

## Vocabulario (resumo)

- Campo (x-ui por propriedade)
  - Identificacao/Apresentacao: `name`, `label`, `description`, `group`, `order`, `width`
  - Tipo/Componente: `type` (enum), `controlType` (enum), `placeholder`, `defaultValue`
  - Estado/Validacao: `disabled`, `readOnly`, `editable`, `unique`, `mask`, `sortable`, `filterable`
  - Visibilidade: `hidden`, `tableHidden`, `formHidden`
  - Dependencias/Condicionais: `conditionalDisplay`, `dependentField`, `resetOnDependentChange`
    - `dependentField` e legado/condicional; cascata de option-source deve usar `optionSource.dependsOn`.
  - Layout/Icone: `hint`, `helpText`, `tooltipOnHover`, `icon*`
  - Selecao/Opcaoes: `options[]`, `endpoint`, `valueField`, `displayField`, `multiple`, `emptyOptionText`
    - No runtime Angular, a UI Praxis normaliza esses campos para `resourcePath`, `optionValueKey` e `optionLabelKey`.
    - Quando publicado, `optionSource` e a forma canonica de descrever fontes derivadas de options; `endpoint` permanece como contrato de options remotas diretas.
    - `optionSource.dependsOn` e a origem canonica de cascata metadata-driven; `optionSource.dependencyFilterMap` explicita o mapeamento dependencia -> chave de filtro quando necessario.
  - Numerico: `numericFormat` (enum), `numericStep`, `numericMin`, `numericMax`, `numericMaxLength`
  - Apresentacao de valor: `valuePresentation{ type, style?, format?, currency?, number? }` como contrato canonico de display/read-only para valores escalares
  - Validacao (top-level): `required`, `minLength`, `maxLength`, `min`, `max`, `pattern`, `range`, mensagens (`*Message`), alem de `email`, `url`, `matchField`, `uniqueValidator`, `customValidator`, `asyncValidator`, `minWords`, `validationTrigger(s)`, `validationDebounce`, `showInlineErrors`, `errorPosition`
  - Validacao agrupada: bloco `validation{}` com chaves basicas quando o produtor optar por agrupar regras no mesmo namespace
- Operacao (x-ui por operacao)
  - `displayColumns` (string[]), `displayFields` (string[]), `filterFields` (string[]), `responseSchema` (string), `relatedEntitiesEndpoints` (string[]), `analytics`
- Analytics (x-ui.analytics)
  - `projections[]`
  - cada projection define `id`, `intent`, `source`, `bindings`, `defaults`, `presentationHints` e `interactions`
  - a escolha de apresentacao continua no runtime consumidor
- Recurso (x-ui.resource)
  - `idField` (string), `idFieldValid` (boolean), `idFieldMessage?` (string, opcional)
  - `readOnly` (boolean)
  - `capabilities` (mapa boolean com chaves conhecidas: `create`, `update`, `delete`, `options`, `byId`, `all`, `filter`, `cursor`; valores adicionais MAY existir e DEVEM ser boolean)
- Chart (x-ui.chart)
  - `version`, `kind`, `preset`, `source`, `orientation`
  - `dimensions`, `metrics`, `aggregations`, `filters`, `sort`, `limit`
  - `metrics[].seriesKind`, `metrics[].axis`, `metrics[].color`
  - `state`, `events`, `legend`, `labels`, `tooltip`, `theme`
- Domain governance (`x-domain-governance` por propriedade OpenAPI)
  - `annotationType`, `classification`, `dataCategory`, `complianceTags`
  - `aiUsage.visibility`, `aiUsage.trainingUse`, `aiUsage.ruleAuthoring`, `aiUsage.reasoningUse`
  - `reason`, `source`, `confidence`

## Tipos e Enums

- `type` (FieldDataType): `text | number | email | date | password | file | url | boolean | json`
- `controlType` (FieldControlType): ver enum completo no JSON Schema
- `numericFormat` (NumericFormat): `integer | decimal | currency | scientific | time | date | date-time | duration | number | fraction | percent`
- `x-ui.chart.kind`: `bar | combo | horizontal-bar | line | pie | donut | area | stacked-bar | stacked-area | scatter`
- `x-ui.chart.source.kind`: `praxis.stats | derived`
- `x-ui.analytics.intent`: `ranking | trend | distribution | composition | comparison | correlation`
- `x-ui.analytics.source.operation`: `group-by | timeseries | distribution`
- `x-ui.analytics.presentationHints.preferredFamilies`: `chart | analytic-table | kpi | summary-list`
- `x-ui.optionSource.type`: `RESOURCE_ENTITY | DISTINCT_DIMENSION | CATEGORICAL_BUCKET | LIGHT_LOOKUP | STATIC_CANONICAL`
- `x-domain-governance.annotationType`: `privacy | security | compliance`
- `x-domain-governance.classification`: `public | internal | confidential | restricted`
- `x-domain-governance.dataCategory`: `credential | sensitive_personal | personal | financial | operational | legal`
- `x-domain-governance.aiUsage.*`: `allow | deny | mask | review_required | summarize_only`

## Obrigatoriedade e Defaults

- Campo (x-ui): nao ha campos obrigatorios universais. A UI consegue inferir `type/controlType` por heuristica, mas recomenda-se prover ao menos `type` e `label`.
- Operacao (x-ui): todos opcionais; sugerem preferencias de renderizacao.
- Analytics (x-ui.analytics):
  - MUST conter `projections[]`
  - cada projection MUST conter `id`, `intent`, `source` e `bindings.primaryMetrics`
  - MUST NOT fixar renderer, engine ou layout
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

- Para display/read-only, `valuePresentation` define a intencao canonica de exibicao; `format` permanece como override explicito quando presente no consumidor.

## Normas (MUST/SHOULD/MAY)

- MUST: chaves canonicas devem seguir tipos/semantica dos JSON Schemas.
- SHOULD: extensoes privadas usarem o prefixo `custom.`.
- MUST: `capabilities` conter apenas valores boolean; chaves adicionais sao permitidas.
- SHOULD: publicar `x-ui.valuePresentation` para campos escalares de exibicao quando a intencao semantica estiver clara.
- MUST NOT: publicar `x-ui.valuePresentation` automatico para ranges, selecoes, arrays, objects ou IDs semanticos sem override explicito.
- SHOULD: `/schemas/filtered` enviar `ETag` forte e `X-Schema-Hash` e expor via `Access-Control-Expose-Headers`.
- SHOULD: publicacoes de `x-ui.chart` explicitar restricoes executaveis do runtime oficial sem criar contrato paralelo.
- SHOULD: publicacoes de `x-ui.analytics` manterem a semantica estritamente analitica, sem reempacotar detalhes especificos de chart.
- SHOULD: hosts self-describing declararem governanca sensivel com `@DomainGovernance` em vez de depender apenas de heuristicas por nome de campo.
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
- `x-ui-analytics.schema.json` - valida o draft inicial de `x-ui.analytics`
- `x-ui-chart.schema.json` - valida o draft inicial de `x-ui.chart`

## Drafts e RFCs

- `x-ui-analytics-rfc.md` - proposta canonica para `x-ui.analytics`, separando capacidade analitica, projecao semantica e especializacao opcional de chart
- `x-ui-chart-rfc.md` - proposta canonica para `x-ui.chart`, separando semantica de plataforma de runtime Angular e detalhes de engine
- `x-ui-chart-publication-checklist.md` - gates minimos para publicar o draft `0.1.0` sem drift imediato entre starter, quickstart e `@praxisui/charts`
- `x-ui-option-source-rfc.md` - proposta canonica para `x-ui.optionSource`, separando recursos CRUD, fontes derivadas de options e backend interno de stats/distinct values
- `x-ui-field.schema.json` - ja aceita o bloco draft `optionSource` para validacao documental inicial, sem pressupor rollout completo no runtime
- `domain-catalog.md` - contrato narrativo de `/schemas/domain`, incluindo governanca explicita por `x-domain-governance`

## Exemplos e Fixtures

- Em `examples/` convivem dois tipos de artefato:
  - fixtures de validacao de schema, usados como base para CI e smoke local
  - exemplos canonicos de documentacao, usados para explicar o contrato esperado
- Fixtures de validacao:
  - `examples/x-ui-field.valid.json`
  - `examples/x-ui-field.invalid.json`
  - `examples/x-ui-field-option-source-resource.valid.json`
  - `examples/x-ui-field-option-source-distinct.valid.json`
  - `examples/x-ui-field-option-source.invalid.json`
  - `examples/x-ui-operation.valid.json`
  - `examples/x-ui-operation.invalid.json`
  - `examples/x-ui-resource.valid.json`
  - `examples/x-ui-resource.invalid.json`
  - `examples/x-ui-analytics.valid.json`
  - `examples/x-ui-analytics.invalid.json`
  - `examples/x-ui-chart.valid.json`
  - `examples/x-ui-chart.invalid.json`
- Exemplos canonicos de documentacao:
  - `examples/canonical-payload.json` - payload ilustrativo combinando campo, operacao e recurso
  - `examples/x-ui-chart.valid.json` - exemplo valido do draft inicial de `x-ui.chart`

## Observacoes

- `schemaId`, `ETag` e `X-Schema-Hash` sao praticas recomendadas para cache/304 e compoem a identidade de schema, mas nao fazem parte da validacao JSON desta pasta.
- O draft `x-ui.chart` esta pronto para publicacao controlada, mas continua em estado `draft` e deve ser tratado como contrato em evolucao.
- A primeira onda do draft amplia o contrato para `horizontal-bar`, `stacked-area` e `scatter`; a segunda onda introduz `combo` com semantica de serie por metrica.
- O runtime Angular oficial deve explicitar honestamente quando alguma parte do contrato ainda for mais ampla que a implementacao atual.
