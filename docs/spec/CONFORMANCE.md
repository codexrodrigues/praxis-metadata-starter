# Guia de Conformidade - Especificacao Praxis x-ui v1.0.0

## Objetivo

- Descrever como validar JSONs gerados por qualquer backend contra os JSON Schemas desta pasta.
- Definir criterios minimos (Core) e opcionais (Extended) de conformidade.
- Mapear, de forma resumida, quais chaves a UI consome e onde.

## Como validar localmente (AJV CLI)

- Pre-requisitos: Node.js 18+
- Instalacao local do ajv-cli (projeto host):
  - `npm i -D ajv ajv-formats ajv-cli`
- Exemplos de execucao:
  - Validar x-ui de campo:
    - `npx ajv -s ./x-ui-field.schema.json -d ./examples/x-ui-field.valid.json -c ajv-formats`
    - `npx ajv -s ./x-ui-field.schema.json -d ./examples/x-ui-field.invalid.json -c ajv-formats || echo "(esperado: invalido)"`
  - Validar x-ui de operacao:
    - `npx ajv -s ./x-ui-operation.schema.json -d ./examples/x-ui-operation.valid.json`
  - Validar x-ui.resource:
    - `npx ajv -s ./x-ui-resource.schema.json -d ./examples/x-ui-resource.valid.json`
  - Validar x-ui.chart:
    - `npx ajv -s ./x-ui-chart.schema.json -d ./examples/x-ui-chart.valid.json`
    - `npx ajv -s ./x-ui-chart.schema.json -d ./examples/x-ui-chart.invalid.json || echo "(esperado: invalido)"`
  - Antes de publicar uma versao nova do starter com charts:
    - revisar `./x-ui-chart-publication-checklist.md`

## Criterios de Conformidade

- Core (minimo obrigatorio)
  - x-ui por campo segue o schema e utiliza chaves canonicas para tipo, controle e validacoes basicas
  - x-ui.resource presente no payload de `/schemas/filtered` contendo `idField`, `idFieldValid`, `readOnly`, `capabilities`
  - ETag forte no `/schemas/filtered` e hash deterministico em `X-Schema-Hash`
  - `schemaId` estavel conforme composicao `path|operation|schemaType|internal|tenant|locale`
  - quando `x-ui.chart.source.kind = "praxis.stats"`, o contrato publicado exige `source.resource` e `source.operation`
- Extended (recomendado)
  - preencher `displayColumns`/`displayFields` no x-ui de operacao
  - publicar `x-ui.chart` com `version`, `kind`, `source`, semantica analitica e eventos declarativos, mantendo o contrato agnostico de engine
  - quando `source.kind = "praxis.stats"`, manter coerencia com os produtores reais da familia `/stats/*`, em especial `group-by`, `timeseries` e `distribution`
  - preencher `x-ui.operationExamples.<schemaType>` quando o OpenAPI publicar exemplos uteis
  - popular mensagens de validacao (`*Message`) para melhor UX
  - incluir `capabilities.options|byId|all|filter|cursor` quando aplicavel
  - adotar `custom.*` para extensoes privadas do host

## Matriz - Chave da Spec -> Consumo na UI

- x-ui por campo
  - `type`, `controlType` -> `SchemaNormalizerService` / `FieldDefinition`
  - `label` -> rotulagem de inputs e colunas
  - `placeholder`, `hint`, `helpText` -> renderizacao de formulario
  - `readOnly`, `disabled` -> `field-state.util.ts` e componentes
  - validacoes (`required`, `minLength`, `maxLength`, `pattern`, `min`, `max`, `range`, `*Message`) -> helpers de validacao
  - selecao (`options`, `endpoint`, `valueField`, `displayField`, `multiple`, `emptyOptionText`) -> componentes de selecao
  - numerico (`numericFormat`, `numeric*`) -> componentes numericos
- x-ui por operacao
  - `displayColumns` -> padrao de colunas iniciais
  - `operationExamples.<schemaType>` -> exemplos operacionais para catalogo, playgrounds e documentacao contextual
- x-ui.chart
  - `version`, `kind`, `source` -> identidade minima do contrato analitico metadata-driven
  - `dimensions`, `metrics`, `aggregations`, `filters`, `sort` -> semantica analitica canonica
  - `state`, `events` -> estados e interacoes declarativas de plataforma
- x-ui.resource
  - `idField` -> chave primaria no fluxo de CRUD/UI
  - `idFieldValid`/`idFieldMessage` -> diagnostico e alertas
  - `readOnly` -> bloqueio de edicao
  - `capabilities` -> habilitacao de acoes e utilitarios

## Compatibilidade de consumidor - `@praxisui/charts`

O draft canonico pode ser mais amplo que o runtime Angular atual.

Suportado hoje no runtime oficial:

- `source.kind = "praxis.stats"`
- `kind`: `bar`, `line`, `pie`, `donut`, `area`, `stacked-bar`
- uma metrica por chart quando a origem e `praxis.stats`
- `pointClick` e `drillDown` no fluxo atual

Ainda nao suportado no runtime Angular atual:

- `source.kind = "derived"`
- `aggregation = "distinct-count"`
- `events.selectionChange`
- `events.crossFilter`
- `theme.variant`
- `theme.palette` como token string
- pie/donut com multiplas metricas

Publicacoes do starter devem deixar essa assimetria explicita ate que os consumidores sejam endurecidos no mesmo nivel.

## Boas praticas e notas

- `custom.*`: prefixo reservado para extensoes de fornecedores/hosts
- o backend deve canonicalizar o payload antes de calcular o hash do schema
- o backend deve separar payload estrutural e payload documental antes de calcular o hash
- locale/tenant variam `schemaId` e os headers de cache

## Compatibilidade (anotacoes temporarias)

- `filterOptions`: a spec define `array`, porem o starter atualmente serializa como `string` em alguns caminhos legados. Mantido assim por compatibilidade com consumidores atuais.

## Suite de fixtures

- Esta pasta traz exemplos validos e invalidos para facilitar a automacao no CI.
- Os arquivos `*.valid.json` e `*.invalid.json` devem ser tratados como fixtures de validacao, nao como catalogo exaustivo de exemplos publicos.
- `canonical-payload.json` e `x-ui-chart.valid.json` tambem cumprem papel documental e podem ser referenciados em guias, desde que permanecam coerentes com os schemas publicados.
- Use estes arquivos como base para gerar casos especificos do seu dominio.
