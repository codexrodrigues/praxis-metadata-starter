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
  - quando um campo publicar `x-ui.optionSource`, o bloco deve obedecer o schema draft e representar a fonte de opcoes como contrato canonico
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

## Cobertura do consumidor oficial - `@praxisui/charts`

O draft canonico e a cobertura executavel do runtime Angular oficial sao documentados separadamente.

Cobertura executavel no runtime oficial:

- `source.kind = "praxis.stats"`
- `kind`: `bar`, `horizontal-bar`, `line`, `pie`, `donut`, `area`, `stacked-bar`, `stacked-area`, `scatter`
- `kind`: `combo` com dados locais/derivados e series heterogeneas por metrica
- uma metrica por chart quando a origem e `praxis.stats`
- `pointClick` e `drillDown` no fluxo executavel
- `orientation = "horizontal"` para `horizontal-bar`
- `scatter` com leitura bidimensional minima: primeira dimensao no eixo `x` e primeira metrica no eixo `y`

Sem cobertura executavel no runtime Angular oficial:

- `source.kind = "derived"`
- `kind = "combo"` sobre `source.kind = "praxis.stats"` com multiplas metricas publicadas pelo backend
- `aggregation = "distinct-count"`
- `events.selectionChange`
- `events.crossFilter`
- `theme.variant`
- `theme.palette` como token string
- pie/donut com multiplas metricas

Publicacoes do starter devem declarar essa diferenca como cobertura de execucao, sem sugerir trilhas paralelas de contrato.

## Cobertura do consumidor oficial - `x-ui.optionSource`

O draft canonico de `x-ui.optionSource` e contrato publico oficial do starter. Consumidores devem ler esse bloco como a fonte semantica da opcao remota.

Estado canonicamente suportado no starter:

- documentado na RFC: `x-ui-option-source-rfc.md`
- validado no schema de campo: `x-ui-field.schema.json`
- publicado em `/schemas/filtered` quando o recurso expoe `OptionSourceRegistry`
- executado pelos controllers base para `DISTINCT_DIMENSION` e `CATEGORICAL_BUCKET`
- exposto nas capacidades agregadas e nos endpoints `/{resource}/option-sources/{sourceKey}/options/*`

Limites de cobertura fora do starter:

- consumidores especificos podem declarar subconjuntos de cobertura do draft;
- tipos como `RESOURCE_ENTITY`, `LIGHT_LOOKUP` e `STATIC_CANONICAL` exigem executor correspondente no host.

Diretriz canonica:

- campos com fonte remota devem publicar `x-ui.optionSource`;
- `endpoint`, `valueField` e `displayField` nao devem redefinir a semantica de fonte quando `x-ui.optionSource` estiver presente;
- consumidores nao devem inferir `optionSource` a partir de heuristicas locais.

## Boas praticas e notas

- `custom.*`: prefixo reservado para extensoes de fornecedores/hosts
- o backend deve canonicalizar o payload antes de calcular o hash do schema
- o backend deve separar payload estrutural e payload documental antes de calcular o hash
- locale/tenant variam `schemaId` e os headers de cache

## Cobertura atual das anotacoes

- `filterOptions`: a spec define `array`; a serializacao deve seguir esse formato canonico.
- `optionSource`: o starter publica e executa a superficie base para `DISTINCT_DIMENSION` e `CATEGORICAL_BUCKET`; demais tipos exigem executor correspondente no host.

## Suite de fixtures

- Esta pasta traz exemplos validos e invalidos para facilitar a automacao no CI.
- Os arquivos `*.valid.json` e `*.invalid.json` devem ser tratados como fixtures de validacao, nao como catalogo exaustivo de exemplos publicos.
- `canonical-payload.json` e `x-ui-chart.valid.json` tambem cumprem papel documental e podem ser referenciados em guias, desde que permanecam coerentes com os schemas publicados.
- os fixtures `x-ui-field-option-source-*.json` documentam o draft contratual de `x-ui.optionSource`.
- Use estes arquivos como base para gerar casos especificos do seu dominio.
