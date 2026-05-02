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
    - `npx ajv --spec=draft2020 -s ./x-ui-field.schema.json -d ./examples/x-ui-field.valid.json -c ajv-formats`
    - `npx ajv --spec=draft2020 -s ./x-ui-field.schema.json -d ./examples/x-ui-field.invalid.json -c ajv-formats || echo "(esperado: invalido)"`
  - Validar x-ui de operacao:
    - `npx ajv --spec=draft2020 -s ./x-ui-operation.schema.json -d ./examples/x-ui-operation.valid.json`
  - Validar x-ui.resource:
    - `npx ajv --spec=draft2020 -s ./x-ui-resource.schema.json -d ./examples/x-ui-resource.valid.json`
  - Validar x-ui.chart:
    - `npx ajv --spec=draft2020 -s ./x-ui-chart.schema.json -d ./examples/x-ui-chart.valid.json`
    - `npx ajv --spec=draft2020 -s ./x-ui-chart.schema.json -d ./examples/x-ui-chart.invalid.json || echo "(esperado: invalido)"`
  - Antes de publicar uma versao nova do starter com charts:
    - revisar `./x-ui-chart-publication-checklist.md`

## Criterios de Conformidade

- Core (minimo obrigatorio)
  - x-ui por campo segue o schema e utiliza chaves canonicas para tipo, controle e validacoes basicas
  - x-ui.resource presente no payload de `/schemas/filtered` contendo `idField`, `idFieldValid`, `readOnly`, `capabilities`
  - `/schemas/domain` publica vocabulario, bindings, aliases, evidencias e governanca como superficie semantica derivada, sem substituir `/schemas/filtered`
  - `/schemas/surfaces` e `/schemas/actions` publicam apenas discovery semantico e links para schemas canonicos resolviveis via `/schemas/filtered`
  - ETag forte no `/schemas/filtered` e hash deterministico em `X-Schema-Hash`
  - `schemaId` estavel conforme composicao `path|operation|schemaType|internal|tenant|locale`
  - quando `x-ui.chart.source.kind = "praxis.stats"`, o contrato publicado exige `source.resource` e `source.operation`
  - quando um campo publicar `x-ui.optionSource`, o bloco deve obedecer o schema draft e representar a fonte de opcoes como contrato canonico
  - quando um recurso publicar exportacao de colecao, `POST /{resource}/export` deve aplicar filtros, selecao, allowlist de campos e limite efetivo do servidor
- Extended (recomendado)
  - preencher `displayColumns`/`displayFields` no x-ui de operacao
  - publicar `x-ui.chart` com `version`, `kind`, `source`, semantica analitica e eventos declarativos, mantendo o contrato agnostico de engine
  - quando `source.kind = "praxis.stats"`, manter coerencia com os produtores reais da familia `/stats/*`, em especial `group-by`, `timeseries` e `distribution`
  - preencher `x-ui.operationExamples.<schemaType>` quando o OpenAPI publicar exemplos uteis
  - popular mensagens de validacao (`*Message`) para melhor UX
  - incluir `capabilities.options|byId|all|filter|cursor` quando aplicavel
  - manter `/schemas/surfaces` e `/schemas/actions` sincronizados com a semantica publicada por `@UiSurface` e `@WorkflowAction`
  - declarar campos sensiveis com `@DomainGovernance` e politicas de IA tipadas por `AiUsageMode`
  - adotar `custom.*` para extensoes privadas do host
  - publicar `x-ui.optionSource` para fontes derivadas governadas, evitando promover `INPUT` em campos com semantica corporativa clara
  - publicar detalhes de `export` em `/capabilities` com formatos, escopos, limites e async quando o service declarar suporte real a exportacao de colecao

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
  - `dependsOn`, `dependencyFilterMap`, `excludeSelfField` -> cascata, mapeamento dependencia -> filtro e remocao do proprio predicado
  - `searchMode`, `pageSize`, `includeIds`, `cachePolicy` -> politica publica minima de consumo
  - `filtering.availableFilters`, `filtering.defaultFilters`, `filtering.sortOptions`, `filtering.defaultSort` -> contrato publico de filtro rico para `entityLookup`
- x-ui.resource
  - `idField` -> chave primaria no fluxo de CRUD/UI
  - `idFieldValid`/`idFieldMessage` -> diagnostico e alertas
  - `readOnly` -> bloqueio de edicao
  - `capabilities` -> habilitacao de acoes e utilitarios
- discovery semantico
  - `/schemas/domain` -> vocabulario semantico AI-operable, evidencias e governanca de dominio
  - `/schemas/surfaces` -> catalogo semantico de `@UiSurface` e surfaces automaticas do recurso
  - `/schemas/actions` -> catalogo semantico de `@WorkflowAction`
- x-domain-governance
  - `annotationType`, `classification`, `dataCategory` -> classificacao canonica do campo
  - `aiUsage.*` -> politica de visibilidade, treino, authoring e raciocinio assistido por IA
  - `source`, `confidence`, `reason` -> explicabilidade e auditoria da classificacao
- exportacao de colecao
  - `/capabilities.operations.export` -> discovery agregado de suporte, formatos, escopos, limites e async
  - `POST /{resource}/export` -> execucao governada pelo service do recurso
  - `X-Export-*` -> metadados de resultado inline, limite efetivo, truncamento e warnings

## Cobertura do consumidor oficial - `@praxisui/charts`

O draft canonico e a cobertura executavel do runtime Angular oficial sao documentados separadamente.

Cobertura executavel no runtime oficial:

- `source.kind = "praxis.stats"`
- `source.kind = "derived"`
- `kind`: `bar`, `horizontal-bar`, `line`, `pie`, `donut`, `area`, `stacked-bar`, `stacked-area`, `combo`, `scatter`
- `combo` com dados locais/derivados e series heterogeneas por metrica
- `combo` sobre `source.kind = "praxis.stats"` quando a operacao e `group-by` ou `timeseries`
- `aggregation = "distinct-count"`
- `pointClick`, `selectionChange`, `drillDown` e `crossFilter` no fluxo executavel
- `orientation = "horizontal"` para `horizontal-bar`
- `scatter` com leitura bidimensional minima: primeira dimensao no eixo `x` e primeira metrica no eixo `y`
- `theme.variant`
- `theme.palette` como token string

Restricoes executaveis no runtime Angular oficial:

- `timeseries` aceita `source.options.granularity` em `day`, `week` ou `month`
- `distribution` aceita uma metrica
- `combo` exige pelo menos duas metricas
- `axis = "secondary"` e exclusivo de `combo`
- pie/donut com multiplas metricas

Publicacoes do starter devem declarar essas restricoes como cobertura de execucao, sem sugerir trilhas paralelas de contrato.

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
- quando `RESOURCE_ENTITY` publicar `filtering`, o backend continua como dono canonico dos operadores e da serializacao de filtro; o frontend nao deve inferir combinacoes suportadas.

Diretriz canonica:

- campos com fonte remota devem publicar `x-ui.optionSource`;
- `endpoint`, `valueField` e `displayField` nao devem redefinir a semantica de fonte quando `x-ui.optionSource` estiver presente;
- consumidores nao devem inferir `optionSource` a partir de heuristicas locais.

## Cobertura do consumidor oficial - exportacao de colecao

O contrato canonico de exportacao de colecao e uma superficie resource-oriented,
nao uma extensao de `x-ui` por campo.

Estado canonicamente suportado no starter:

- request canonico `CollectionExportRequest`, preservando formato, escopo, selecao,
  filtros, sort, paginacao, campos, limite e metadados;
- executor reutilizavel para reconciliar campos e delegar para engine por formato;
- engines tabulares CSV e JSON;
- discovery agregado por `/capabilities.operations.export`;
- headers `X-Export-Row-Count`, `X-Export-Truncated`, `X-Export-Max-Rows`,
  `X-Export-Candidate-Row-Count` e `X-Export-Warnings` quando o resultado inline
  trouxer esses metadados.

Diretriz canonica:

- o service do recurso decide consulta, seguranca, limite efetivo e allowlist de campos;
- `maxRows` do cliente e sugestao limitada pela politica do servidor;
- campos desconhecidos nao devem abrir fallback silencioso para dados default quando
  nenhum campo solicitado for suportado;
- CSV deve aplicar protecao contra formula injection.

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
