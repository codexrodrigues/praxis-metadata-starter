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
  - `/schemas/surfaces` e `/schemas/actions` publicam apenas discovery semantico e links para schemas canonicos resolviveis via `/schemas/filtered`
  - ETag forte no `/schemas/filtered` e hash deterministico em `X-Schema-Hash`
  - `schemaId` estavel conforme composicao `path|operation|schemaType|internal|tenant|locale`
  - quando `x-ui.chart.source.kind = "praxis.stats"`, o contrato publicado exige `source.resource` e `source.operation`
  - quando um campo publicar `x-ui.optionSource`, o bloco deve obedecer o schema draft e manter convivio aditivo com o legado
- Extended (recomendado)
  - preencher `displayColumns`/`displayFields` no x-ui de operacao
  - publicar `x-ui.chart` com `version`, `kind`, `source`, semantica analitica e eventos declarativos, mantendo o contrato agnostico de engine
  - quando `source.kind = "praxis.stats"`, manter coerencia com os produtores reais da familia `/stats/*`, em especial `group-by`, `timeseries` e `distribution`
  - preencher `x-ui.operationExamples.<schemaType>` quando o OpenAPI publicar exemplos uteis
  - popular mensagens de validacao (`*Message`) para melhor UX
  - incluir `capabilities.options|byId|all|filter|cursor` quando aplicavel
  - manter `/schemas/surfaces` e `/schemas/actions` sincronizados com a semantica publicada por `@UiSurface` e `@WorkflowAction`
  - adotar `custom.*` para extensoes privadas do host
  - publicar `x-ui.optionSource` para fontes derivadas governadas, evitando promover `INPUT` em campos com semantica corporativa clara

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
- `metrics[].seriesKind`, `metrics[].axis` -> serie heterogenea e eixo primario/secundario para charts combinados
- `state`, `events` -> estados e interacoes declarativas de plataforma
- x-ui.optionSource
  - `key`, `type`, `resourcePath` -> identidade minima da fonte derivada
  - `dependsOn`, `excludeSelfField` -> cascata e remocao do proprio predicado
  - `searchMode`, `pageSize`, `includeIds`, `cachePolicy` -> politica publica minima de consumo
- x-ui.resource
  - `idField` -> chave primaria no fluxo de CRUD/UI
  - `idFieldValid`/`idFieldMessage` -> diagnostico e alertas
  - `readOnly` -> bloqueio de edicao
  - `capabilities` -> habilitacao de acoes e utilitarios
- discovery semantico
  - `/schemas/surfaces` -> catalogo semantico de `@UiSurface` e surfaces automaticas do recurso
  - `/schemas/actions` -> catalogo semantico de `@WorkflowAction`

## Compatibilidade de consumidor - `@praxisui/charts`

O draft canonico pode ser mais amplo que o runtime Angular atual.

Suportado hoje no runtime oficial:

- `source.kind = "praxis.stats"`
- `kind`: `bar`, `horizontal-bar`, `line`, `pie`, `donut`, `area`, `stacked-bar`, `stacked-area`, `scatter`
- `kind`: `combo` com dados locais/derivados e series heterogeneas por metrica
- uma metrica por chart quando a origem e `praxis.stats`
- `pointClick` e `drillDown` no fluxo atual
- `orientation = "horizontal"` para `horizontal-bar`
- `scatter` com leitura bidimensional minima: primeira dimensao no eixo `x` e primeira metrica no eixo `y`

Ainda nao suportado no runtime Angular atual:

- `source.kind = "derived"`
- `kind = "combo"` sobre `source.kind = "praxis.stats"` com multiplas metricas publicadas pelo backend
- `aggregation = "distinct-count"`
- `events.selectionChange`
- `events.crossFilter`
- `theme.variant`
- `theme.palette` como token string
- pie/donut com multiplas metricas

Publicacoes do starter devem deixar essa assimetria explicita ate que os consumidores sejam endurecidos no mesmo nivel.

## Compatibilidade de consumidor - `x-ui.optionSource`

O draft canonico de `x-ui.optionSource` ainda pode ser mais amplo que o rollout implementado em cada consumidor.

Estado esperado por fase:

- documentado na RFC: `x-ui-option-source-rfc.md`
- validado no schema de campo: `x-ui-field.schema.json`
- ainda nao obrigatoriamente publicado/executado em todos os hosts
- ainda nao obrigatoriamente consumido pelo runtime Angular oficial

Diretriz de rollout:

- `x-ui.optionSource` deve entrar de forma aditiva
- `endpoint`, `valueField` e `displayField` permanecem validos durante a transicao
- um host pode publicar ambos os modelos para o mesmo campo quando isso reduzir risco de migracao
- consumidores nao devem inferir `optionSource` a partir de heuristicas locais se o backend ainda nao o publicar

## Boas praticas e notas

- `custom.*`: prefixo reservado para extensoes de fornecedores/hosts
- o backend deve canonicalizar o payload antes de calcular o hash do schema
- o backend deve separar payload estrutural e payload documental antes de calcular o hash
- locale/tenant variam `schemaId` e os headers de cache

## Compatibilidade (anotacoes temporarias)

- `filterOptions`: a spec define `array`, porem o starter atualmente serializa como `string` em alguns caminhos legados. Mantido assim por compatibilidade com consumidores atuais.
- `optionSource`: o schema draft ja valida o bloco, mas a publicacao em `/schemas/filtered` e o consumo runtime oficial dependem das PRs de rollout de backend/host/UI.

## Suite de fixtures

- Esta pasta traz exemplos validos e invalidos para facilitar a automacao no CI.
- Os arquivos `*.valid.json` e `*.invalid.json` devem ser tratados como fixtures de validacao, nao como catalogo exaustivo de exemplos publicos.
- `canonical-payload.json` e `x-ui-chart.valid.json` tambem cumprem papel documental e podem ser referenciados em guias, desde que permanecam coerentes com os schemas publicados.
- os fixtures `x-ui-field-option-source-*.json` cobrem apenas o draft contratual inicial e nao implicam rollout completo da feature.
- Use estes arquivos como base para gerar casos especificos do seu dominio.
