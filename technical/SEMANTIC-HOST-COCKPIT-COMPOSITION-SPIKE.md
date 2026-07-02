# Semantic Host Cockpit Composition Spike

## Status

Spike arquitetural para a issue #40.

Este documento nao altera contrato publico. Ele define como montar uma visao
resource-centric do host usando superficies existentes antes de propor qualquer
endpoint agregado.

Classificacao: `arquitetural`.

## Pergunta do spike

O cockpit consegue montar uma experiencia util e coerente usando apenas os
contratos atuais?

Resposta curta: sim, para um primeiro corte. O modelo atual tem pivots
suficientes em `resourceKey`, `resourcePath`, `path`, `method`, `operationId` e
`schemaUrl`. A composicao inicial deve continuar derivada e empacotada pelo
starter. Um endpoint agregado de dados como `/schemas/host-cockpit` ainda nao
esta justificado como `lacuna-real-de-contrato`.

## Fontes existentes

### Health e operacao do host

Fonte:

- `/actuator/health`
- `/actuator/info`, quando exposto pelo host

Uso no cockpit:

- status do host;
- baseline operacional;
- contexto de ambiente quando publicado.

Classificacao: `ja-suportado-so-ux`.

### OpenAPI e catalogo documental

Fontes:

- `/v3/api-docs`
- `/v3/api-docs/{group}`
- `/schemas/catalog`

Evidencia no starter:

- `DomainCatalogController.CatalogResponse`
- `DomainCatalogController.EndpointSummary`
- `EndpointSummary.path`
- `EndpointSummary.method`
- `EndpointSummary.operationId`
- `EndpointSummary.requestSchema`
- `EndpointSummary.responseSchema`
- `EndpointSummary.schemaLinks`

Uso no cockpit:

- descobrir endpoints;
- listar operacoes por path/metodo;
- localizar schemas request/response;
- exibir summaries/descriptions/examples;
- compor links para `/schemas/filtered`.

Classificacao: `ja-suportado-so-ux`.

Observacao: apesar do nome historico `DomainCatalogController`, o endpoint
`/schemas/catalog` e catalogo documental. O dominio AI-operable fica em
`/schemas/domain`.

### Contrato estrutural

Fonte:

- `/schemas/filtered?path={path}&operation={method}&schemaType=request|response`

Uso no cockpit:

- schema estrutural canonico;
- `properties.*.x-ui`;
- `x-ui.resource.idField`;
- `x-ui.resource.readOnly`;
- `x-ui.resource.capabilities`;
- `x-ui.optionSource`;
- `x-ui.analytics`;
- `fieldAccess`;
- `x-domain-governance`;
- ETag e `X-Schema-Hash`.

Classificacao: `ja-suportado-so-ux`.

Risco:

- a consulta e path/operation/schemaType centric. O cockpit precisa derivar os
  links corretos a partir do catalogo, surfaces, actions ou capabilities.

### Dominio AI-operable

Fonte:

- `/schemas/domain`
- `/schemas/domain?resourceKey={resourceKey}`, quando usado pelo host

Uso no cockpit:

- vocabulario;
- aliases;
- evidencias;
- governanca de campos;
- sinais para AI grounding readiness.

Classificacao: `ja-suportado-so-ux` para exibicao basica e
`suportado-parcialmente` para readiness score.

### Surfaces

Fonte:

- `/schemas/surfaces`
- `/schemas/surfaces?resource={resourceKey}`
- `GET /{resource}/{id}/surfaces`, quando houver contexto item-level

Evidencia no starter:

- `SurfaceCatalogResponse.resourceKey`
- `SurfaceCatalogResponse.resourcePath`
- `SurfaceCatalogResponse.group`
- `SurfaceCatalogItem.path`
- `SurfaceCatalogItem.method`
- `SurfaceCatalogItem.operationId`
- `SurfaceCatalogItem.schemaUrl`
- `SurfaceCatalogItem.relatedResource`
- `SurfaceCatalogItem.availability`

Uso no cockpit:

- discovery semantico de experiencias reais;
- preview de surfaces de item/collection;
- child collections por `relatedResource`;
- matriz surface -> schema -> runtime.

Classificacao: `ja-suportado-so-ux`.

### Actions

Fonte:

- `/schemas/actions`
- `/schemas/actions?resource={resourceKey}`
- `GET /{resource}/{id}/actions`, quando houver contexto item-level

Evidencia no starter:

- `ActionCatalogResponse.resourceKey`
- `ActionCatalogResponse.resourcePath`
- `ActionCatalogItem.path`
- `ActionCatalogItem.method`
- `ActionCatalogItem.operationId`
- `ActionCatalogItem.requestSchemaUrl`
- `ActionCatalogItem.responseSchemaUrl`
- `ActionCatalogItem.availability`

Uso no cockpit:

- workflow affordances;
- command preview;
- diferenciar CRUD canonico de comando de negocio;
- explicar pre-condicoes e availability.

Classificacao: `ja-suportado-so-ux`.

### Capabilities

Fontes:

- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

Evidencia no starter:

- `CapabilitySnapshot.resourceKey`
- `CapabilitySnapshot.resourcePath`
- `CapabilitySnapshot.canonicalOperations`
- `CapabilitySnapshot.operations`
- `CapabilitySnapshot.surfaces`
- `CapabilitySnapshot.actions`
- `CapabilityOperation.supported`
- `CapabilityOperation.scope`
- `CapabilityOperation.preferredMethod`
- `CapabilityOperation.preferredRel`
- `CapabilityOperation.availability`
- `CapabilityOperation.formats`
- `CapabilityOperation.scopes`
- `CapabilityOperation.maxRows`
- `CapabilityOperation.async`

Uso no cockpit:

- matriz de operacoes;
- comparacao collection vs item;
- export readiness;
- coerencia com `_links`;
- disponibilidade contextual.

Classificacao: `ja-suportado-so-ux` para exibir e
`suportado-parcialmente` para diagnosticar coerencia.

### Option sources

Fontes:

- `x-ui.optionSource` em `/schemas/filtered`
- `POST /{resource}/option-sources/{sourceKey}/options/filter`
- `GET /{resource}/option-sources/{sourceKey}/options/by-ids`
- `POST /{resource}/option-sources/{sourceKey}/options/by-ids`

Uso no cockpit:

- indicar campos com lookup remoto;
- mostrar endpoints de hidratacao;
- diferenciar select simples de `RESOURCE_ENTITY`;
- explicar dependency mapping, reload policy e selection policy.

Classificacao: `ja-suportado-so-ux` para campos e endpoints publicados;
`suportado-parcialmente` para inventario global por host.

### Stats e analytics

Fontes:

- endpoints `/stats/group-by`
- endpoints `/stats/timeseries`
- endpoints `/stats/distribution`
- `x-ui.analytics` e `source.kind = "praxis.stats"` quando publicados
- exemplos operacionais no corpus HTTP

Uso no cockpit:

- readiness de dashboards;
- preview de graficos;
- exemplos de materializacao analitica.

Classificacao: `suportado-parcialmente`, porque a UX precisa compor evidencias
de schema, endpoint e exemplos operacionais.

### Corpus operacional e LLM surface

Fontes:

- `praxisui-http-examples/examples.manifest.json`
- `praxisui-http-examples/LLM_SURFACE.md`

Uso no cockpit:

- exemplos recomendados por resource;
- lane publica/auth-light/protected;
- prova operacional de stats, options, actions e capabilities.

Classificacao: `ja-suportado-so-ux` como referencia documental externa ao
starter, mas nao fonte primaria de contrato.

## Modelo de composicao resource-centric

### `HostCockpitView`

```text
HostCockpitView
  host
  readiness
  resources[]
  diagnostics[]
  corpusReferences[]
```

Campos:

- `host`: status operacional e metadados de ambiente;
- `readiness`: score derivado e experimental;
- `resources`: lista de `ResourceCockpitView`;
- `diagnostics`: diagnosticos agregados;
- `corpusReferences`: exemplos HTTP/LLM quando disponiveis.

### `ResourceCockpitView`

```text
ResourceCockpitView
  identity
  structural
  domain
  surfaces
  actions
  capabilities
  options
  stats
  export
  runtimePreview
  diagnostics
  rawSources
```

#### `identity`

Campos:

- `resourceKey`
- `resourcePath`
- `group`
- `domainSegment`
- `displayName`
- `readOnly`
- `sourceConfidence`

Fontes:

- `SurfaceCatalogResponse.resourceKey/resourcePath/group`
- `ActionCatalogResponse.resourceKey/resourcePath/group`
- `CapabilitySnapshot.resourceKey/resourcePath/group`
- `@ApiResource` materializado indiretamente nos catalogos;
- `/schemas/catalog` por path/metodo;
- `/schemas/filtered` em `x-ui.resource`.

Classificacao:

- `resourceKey`: `ja-suportado-so-ux`;
- `displayName`: `suportado-parcialmente`, pois pode precisar de label melhor
  a partir de schema/catalog/domain;
- `sourceConfidence`: `suportado-parcialmente`.

#### `structural`

Campos:

- `operations[]`
- `requestSchemaUrl`
- `responseSchemaUrl`
- `idField`
- `xuiCoverage`
- `etag`
- `schemaHash`

Fontes:

- `/schemas/catalog`;
- `/schemas/filtered`;
- `schemaLinks`;
- headers de schema.

Classificacao:

- URLs e schema refs: `ja-suportado-so-ux`;
- `xuiCoverage`: `suportado-parcialmente`, calculado pela UX;
- schema headers em overview agregado: `suportado-parcialmente`.

#### `domain`

Campos:

- `vocabulary`
- `aliases`
- `evidence`
- `governanceFields`
- `aiUsageModes`
- `domainCoverage`

Fontes:

- `/schemas/domain`;
- `x-domain-governance` em schema filtrado.

Classificacao:

- dados brutos: `ja-suportado-so-ux`;
- coverage/readiness: `suportado-parcialmente`.

#### `surfaces`

Campos:

- `items[]`
- `relatedResources[]`
- `availabilitySummary`
- `schemaResolutionStatus`

Fontes:

- `/schemas/surfaces?resource={resourceKey}`;
- item-level surfaces;
- `/schemas/filtered` via `schemaUrl`.

Classificacao:

- itens: `ja-suportado-so-ux`;
- schema resolution status: `suportado-parcialmente`.

#### `actions`

Campos:

- `items[]`
- `workflowCount`
- `availabilitySummary`
- `schemaResolutionStatus`

Fontes:

- `/schemas/actions?resource={resourceKey}`;
- item-level actions;
- request/response schema URLs nos action items.

Classificacao:

- itens: `ja-suportado-so-ux`;
- workflow/runtime readiness: `suportado-parcialmente`.

#### `capabilities`

Campos:

- `canonicalOperations`
- `operations`
- `collectionSnapshot`
- `itemSnapshotSample`
- `linkConsistency`

Fontes:

- `/{resource}/capabilities`;
- `/{resource}/{id}/capabilities`;
- `_links` em respostas reais quando o cockpit fizer smokes opcionais.

Classificacao:

- snapshot: `ja-suportado-so-ux`;
- item sample: `suportado-parcialmente`, pois exige id real;
- link consistency: `suportado-parcialmente`.

#### `options`

Campos:

- `fields[]`
- `sourceKey`
- `type`
- `filterEndpoint`
- `byIdsEndpoint`
- `selectedReloadPolicy`
- `invalidSortPolicy`
- `dependencyFilterMap`
- `selectionPolicy`

Fontes:

- `/schemas/filtered` em `properties.*.x-ui.optionSource`.

Classificacao:

- por schema: `ja-suportado-so-ux`;
- inventario global por resource: `suportado-parcialmente`.

#### `stats`

Campos:

- `groupBy`
- `timeseries`
- `distribution`
- `analyticsHints`
- `corpusExamples`

Fontes:

- capabilities;
- `/schemas/catalog`;
- `/schemas/filtered`;
- corpus HTTP.

Classificacao:

- existencia de endpoints: `ja-suportado-so-ux`;
- preview visual: `suportado-parcialmente`.

#### `export`

Campos:

- `supported`
- `formats`
- `scopes`
- `maxRows`
- `async`
- `serviceBacked`

Fontes:

- `CapabilityOperation export`;
- `POST /{resource}/export`, quando validado em smoke.

Classificacao:

- capability: `ja-suportado-so-ux`;
- serviceBacked proof: `suportado-parcialmente`.

## Ordem de chamadas recomendada

### Host overview

1. `GET /actuator/health`.
2. `GET /schemas/catalog`.
3. `GET /schemas/domain`.
4. `GET /schemas/surfaces`.
5. `GET /schemas/actions`.

Resultado:

- lista inicial de resources;
- contagens;
- dominios;
- coverage superficial;
- primeiros diagnostics experimentais.

Observacao: evitar chamar `/schemas/filtered` para todos os endpoints no
primeiro viewport. Isso deve acontecer sob demanda ou com cache.

### Resource detail

1. Resolver `resourceKey` e `resourcePath` a partir do item selecionado.
2. `GET /schemas/surfaces?resource={resourceKey}`.
3. `GET /schemas/actions?resource={resourceKey}`.
4. `GET {resourcePath}/capabilities`.
5. Para operacoes principais, chamar `/schemas/filtered` via `schemaLinks`,
   `schemaUrl`, `requestSchemaUrl` ou `responseSchemaUrl`.
6. Opcionalmente carregar exemplos do corpus HTTP relacionados.
7. Se houver id amostral aprovado, chamar item capabilities/surfaces/actions.

### Raw JSON

Guardar `rawSources` por aba:

- catalog;
- domain;
- surfaces;
- actions;
- capabilities;
- filtered schemas;
- corpus reference.

Isso protege transparencia sem tornar JSON a UX primaria.

## Join keys e regras de reconciliacao

### Ordem de confianca

1. `resourceKey`, quando publicado por surfaces/actions/capabilities/domain.
2. `resourcePath`, quando publicado junto com `resourceKey`.
3. `path + method + operationId`, para endpoint/schema.
4. `schemaUrl`, para resolver `/schemas/filtered`.
5. Tags/grupo OpenAPI apenas como apoio visual, nao como identidade canonica.

### Regras

- Nunca tratar tag OpenAPI como resource identity primaria.
- Nunca inferir resource por palavra-chave quando `resourceKey` existir.
- Quando so houver path, marcar `sourceConfidence = partial`.
- Quando path e `resourceKey` divergirem, mostrar diagnostico em vez de escolher
  silenciosamente.
- Quando `schemaUrl` existir, usar o link publicado em vez de reconstruir query
  localmente.
- Quando action/surface publica schema URL, ela continua referencia; o schema
  estrutural vem de `/schemas/filtered`.

## Diagnosticos derivados do spike

### `resource-key-missing-from-structural-only-view`

Quando um endpoint aparece no catalogo, mas nao ha resourceKey associado em
surfaces/actions/capabilities/domain.

Classificacao: `suportado-parcialmente`.

Acao recomendada: confirmar se o endpoint deve ser um recurso Praxis ou apenas
operacao auxiliar.

### `schema-url-unresolved`

Quando `schemaUrl`, `requestSchemaUrl` ou `responseSchemaUrl` existe, mas
`/schemas/filtered` falha.

Classificacao: `suportado-parcialmente`.

Acao recomendada: corrigir schema resolution, operationId ou path/metodo na
fonte canonica.

### `capability-without-runtime-target`

Quando uma capability suportada nao encontra link, schema, action ou endpoint
coerente para materializacao.

Classificacao: `suportado-parcialmente`.

Acao recomendada: corrigir capability/link/catalogo no starter ou no host.

### `action-without-structural-schema`

Quando uma action de negocio tem payload esperado, mas nao publica schema
request/response resolvivel.

Classificacao: `suportado-parcialmente`.

Acao recomendada: corrigir anotacao/DTO/OpenAPI da operacao.

### `option-source-incomplete-runtime-contract`

Quando `x-ui.optionSource` existe, mas faltam endpoints, sourceKey ou policy
publica necessaria.

Classificacao: `suportado-parcialmente`.

Acao recomendada: corrigir `OptionSourceRegistry` ou publicacao do descriptor.

## Candidatos de teste

### Resource rico: `operations.missoes`

Por que usar:

- surfaces;
- actions;
- item capabilities;
- exemplos no LLM surface;
- workflow affordances.

Provas esperadas:

- `/schemas/surfaces?resource=operations.missoes`;
- `/schemas/actions?resource=operations.missoes`;
- `/api/operations/missoes/{id}/capabilities`;
- schema filtrado de action quando publicado.

### Resource CRUD comum: `human-resources.funcionarios`

Por que usar:

- CRUD;
- filtered schemas request/response;
- options;
- export;
- forms/tables runtime.

Provas esperadas:

- create/edit/view/delete capabilities quando suportadas;
- `x-ui.resource.idField`;
- option sources em campos;
- export capability.

### Resource lookup governado: `procurement.suppliers`

Por que usar:

- entity lookup;
- option source com selection policy;
- prova de materializacao governada por config/domain rules no quickstart.

Provas esperadas:

- `RESOURCE_ENTITY` option source;
- filter/by-ids;
- dependency/selection policy quando publicada.

### View analitica: `human-resources.vw-analytics-folha-pagamento`

Por que usar:

- read-only;
- stats group-by/timeseries;
- dashboard preview.

Provas esperadas:

- read-only capabilities;
- stats endpoints no catalogo;
- exemplos operacionais no corpus.

## Resultado do spike

### O que ja da para fazer

- Host overview derivado.
- Resource map por `resourceKey`.
- Resource detail com raw sources.
- Contract chain didatico.
- Schema explorer sob demanda.
- Capability/action/surface matrix.
- AI grounding readiness experimental.
- Runtime preview experimental.
- Diagnostics experimentais.

### O que ainda nao justifica contrato novo

- `HostCockpitView` agregado canonico.
- readiness score canonico versionado.
- diagnostics canonicos publicados pelo starter.
- indice global de option sources por host.

Esses itens podem se tornar `lacuna-real-de-contrato` depois do prototipo se
houver evidencia de custo, duplicacao ou inconsistencia real.

### Recomendacao

Seguir para o prototipo #38 com composicao derivada empacotada no
`praxis-metadata-starter` e servida por `/praxis/cockpit`.

Nao implementar endpoint agregado de dados no starter neste momento.

Nao implementar cockpit como HTML local do quickstart. O quickstart deve apenas
receber a URL automaticamente ao instalar o starter.

Durante o prototipo, medir:

- numero de chamadas para overview;
- numero de chamadas para resource detail;
- custo de resolver schemas sob demanda;
- ambiguidades entre `resourceKey`, path e operationId;
- falsos positivos dos diagnostics;
- campos que a UX precisa mas nenhuma fonte canonica publica hoje.

Se os dados mostrarem lacuna real, abrir uma issue `contrato-publico` especifica
para um endpoint agregado, com mapa de impacto e validacao focal.
