# Semantic Host Cockpit Plan

## Status

Plano de produto e arquitetura para um dashboard didatico e futurista do host
Praxis metadata-driven.

Este documento nao altera contrato publico. Ele orienta a evolucao de uma
superficie derivada dos contratos atuais do `praxis-metadata-starter`.

Classificacao da iniciativa: `arquitetural`.

Classificacao de uma implementacao inicial autoexposta pelo starter:
`contrato-publico`, porque a URL `/praxis/cockpit` passa a ser superficie
publica automaticamente disponivel em hosts que instalam o starter.

Classificacao de qualquer endpoint agregado novo: `contrato-publico`.

## Principio do produto

O cockpit deve responder rapidamente:

- o que este host Praxis sabe semanticamente;
- o que ele consegue materializar em runtime;
- o que a IA consegue entender com seguranca;
- onde ha incoerencia, baixa aderencia ou lacuna real;
- qual correcao canonica melhora o host.

O dashboard nao deve ser uma copia de Swagger, Spring Boot Admin ou admin CRUD.
Ele deve mostrar a cadeia Praxis:

```text
resource -> schema -> domain -> surface -> action -> capability -> HATEOAS -> runtime -> AI grounding
```

## Premissas canonicas

- `/schemas/filtered` permanece a fonte estrutural canonica.
- `/schemas/catalog` permanece catalogo documental e de discovery.
- `/schemas/domain` publica vocabulario, aliases, evidencias e governanca
  AI-operable.
- `/schemas/surfaces` e `/schemas/actions` sao discovery semantico; nao
  substituem schema inline.
- `GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities` sao
  snapshots agregados; nao redefinem schema.
- `_links` fazem parte do contrato operacional quando HATEOAS esta habilitado.
- `POST /{resource}/export` so deve aparecer como capacidade real quando houver
  suporte publicado pelo service.
- UI, forms, tables, dashboards, agents e docs devem materializar a semantica
  publicada, nao redefinir regra de negocio localmente.

## Inspiracoes externas

As referencias abaixo inspiram padroes de apresentacao, nao contratos Praxis.

- Spring Boot Admin: health, status operacional e management endpoints.
- Swagger UI: explorer interativo de contrato HTTP.
- Backstage Software Catalog: catalogo de entidades, ownership e descoberta.
- Hasura Console: metadata como ativo operacional, com reload, export/import e
  inconsistencias.
- Strapi Content-type Builder: modelagem visual de tipos, campos, relacoes e
  status de mudanca.
- Directus Data Studio e Insights: modelo de dados, permissoes, flows e paineis.
- Retool Resources: recursos operacionais como conexoes reutilizaveis por apps e
  workflows.

Uso correto dessas inspiracoes no Praxis:

- copiar a clareza do catalogo, nao a fonte de verdade;
- copiar a navegabilidade, nao a semantica;
- copiar a didatica visual, nao um editor local de schema;
- preservar o starter como publicador de semantica e o config-starter como
  fronteira de authoring governado.

## Inventario de aderencia

### `ja-suportado-so-ux`

Dados que a plataforma ja publica e que podem ser exibidos por uma UX derivada:

- health do host;
- OpenAPI por grupo;
- `/schemas/filtered`;
- `/schemas/catalog`;
- `/schemas/domain`;
- `/schemas/surfaces`;
- `/schemas/actions`;
- collection e item capabilities;
- `_links` no envelope `RestApiResponse`;
- option sources;
- stats;
- export capabilities;
- ETag e `X-Schema-Hash`;
- exemplos operacionais do corpus HTTP e LLM surface.

### `ja-suportado-mal-nomeado-ou-mal-materializado`

Conceitos que existem, mas precisam de melhor explicacao visual:

- diferenca entre schema estrutural, catalogo documental e discovery semantico;
- diferenca entre `resourceKey` e path operacional;
- diferenca entre surface, workflow action e capability;
- diferenca entre capability atual e schema da operacao;
- relacao entre field-level `x-ui`, `fieldAccess` e governanca de dominio;
- relacao entre option source, endpoint de options e runtime form/table;
- coerencia esperada entre capability e `_links`.

### `suportado-parcialmente`

Experiencias que podem ser montadas com dados atuais, mas precisam de camada de
composicao, scoring ou diagnostico:

- host readiness score;
- AI grounding readiness;
- runtime materialization preview;
- semantic resource graph;
- capability/action/surface consistency matrix;
- diagnostics acionaveis por resource;
- resumo de maturidade por dominio;
- seletor de exemplos do corpus operacional mais representativos por resource.

### `lacuna-real-de-contrato`

So devem virar contrato publico se a implementacao provar que chamar os
endpoints atuais gera custo, duplicacao ou inconsistencia relevante:

- endpoint agregado de host cockpit;
- diagnosticos canonicos emitidos pelo starter em vez de calculados apenas na
  UX;
- readiness score canonico versionado;
- indice oficial resource-centric que conecte, em um unico payload, schema,
  domain, surfaces, actions, capabilities, links, option sources, stats e export.

## Experiencia alvo

### 1. Host Overview

Objetivo: mostrar em poucos segundos se o host esta vivo, rico e coerente.

Exibir:

- app name, version, profile e environment;
- base URL publica e OpenAPI internal base URL, quando disponivel;
- status de health;
- versao do starter;
- contagem de resources, schemas, domain items, surfaces, actions, option
  sources, stats e exports;
- HATEOAS enabled/disabled;
- ultima leitura de ETag/hash quando disponivel;
- readiness resumida: structural, semantic, runtime, AI, governance.

Como exibir:

- scorecards compactos;
- trilha de readiness por eixo;
- alertas de divergencia de alto impacto;
- link direto para docs, Swagger e JSON tecnico.

### 2. Semantic Resource Map

Objetivo: transformar o host em um mapa mental navegavel.

Exibir por resource:

- `resourceKey`;
- path operacional;
- dominio/grupo OpenAPI;
- controller baseline detectavel;
- read-only/mutable;
- schemas request/response principais;
- surfaces;
- actions;
- capabilities collection/item;
- option sources;
- stats;
- export;
- governanca de dominio;
- links contextuais.

Como exibir:

- grafo por dominio;
- lista densa filtravel;
- cores por maturidade;
- badges `structural`, `semantic`, `runtime`, `ai`, `governance`;
- drill-down para resource detail.

### 3. Resource Detail

Objetivo: explicar um resource como unidade canonica da plataforma.

Abas recomendadas:

- Overview;
- Contract Chain;
- Schemas;
- Domain;
- Surfaces;
- Actions;
- Capabilities;
- Options;
- Stats;
- Runtime Preview;
- Diagnostics;
- Raw JSON.

Regra de UX: JSON cru e detalhe tecnico devem estar disponiveis, mas nao podem
ser a experiencia primaria.

### 4. Contract Chain

Objetivo: mostrar como uma anotacao e um DTO viram runtime e grounding.

Exibir fluxo:

```text
DTO + annotations + validation
  -> OpenAPI + x-ui
  -> /schemas/filtered
  -> /schemas/catalog
  -> /schemas/domain
  -> /schemas/surfaces + /schemas/actions
  -> /capabilities + _links
  -> praxis-ui runtime
  -> AI grounding and governed authoring
```

Cada etapa deve indicar:

- fonte canonica;
- payload/endpoint;
- consumidor;
- riscos comuns;
- evidencia real no host.

### 5. Schema Explorer Praxis

Objetivo: substituir leitura bruta de JSON por entendimento de contrato.

Exibir:

- propriedades e tipos;
- `properties.*.x-ui`;
- `x-ui.resource.idField`;
- `readOnly`;
- validacoes;
- `controlType`;
- `presentationPreset`;
- `fieldAccess`;
- `optionSource`;
- `x-domain-governance`;
- `schemaUrl`;
- ETag e hash.

Como exibir:

- arvore de campos;
- painel de significado runtime;
- painel de governanca;
- preview de form/table quando possivel;
- link para schema filtrado bruto.

### 6. AI Grounding Readiness

Objetivo: avaliar se a IA consegue entender e agir com seguranca.

Exibir:

- recursos com `/schemas/domain`;
- vocabulario, aliases e evidencias;
- campos sem descricao semantica suficiente;
- campos sensiveis sem `@DomainGovernance` explicita;
- `AiUsageMode` por campo;
- actions que exigem confirmacao;
- exemplos seguros do LLM surface;
- riscos de roteamento por texto local em vez de intencao semantica.

Como exibir:

- score por resource;
- lista de gaps acionaveis;
- explicacao do impacto em authoring, RAG e runtime.

### 7. Runtime Materialization Preview

Objetivo: mostrar o que o `praxis-ui-angular` conseguiria montar.

Exibir:

- tabela inferida;
- form create/edit/view;
- filtros;
- options remotas;
- row actions;
- toolbar actions;
- child collections via `relatedResource`;
- charts ou analytics derivados de stats;
- export;
- estados vazios e warning states.

Regra: preview deve declarar a evidencia usada. Se a UX inferir algo, deve dizer
qual contrato sustentou a inferencia.

### 8. Capabilities And Action Matrix

Objetivo: tornar visivel a coerencia operacional.

Linhas:

- create;
- view;
- edit;
- delete;
- duplicate-draft;
- export;
- filter;
- cursor;
- options;
- optionSources;
- stats group-by;
- stats timeseries;
- stats distribution;
- workflow actions.

Colunas:

- supported;
- scope;
- preferredMethod;
- preferredRel;
- availability;
- schema resolvivel;
- `_links` coerentes;
- surface associada;
- action associada;
- consumidor esperado.

### 9. Diagnostics And Conformance

Objetivo: fazer o dashboard ensinar e orientar correcao canonica.

Diagnosticos iniciais:

- resource sem `resourceKey` estavel;
- path operacional sem schema filtrado resolvivel;
- `idField` ausente no schema esperado;
- schema publicado sem `x-ui` util;
- surface sem operacao real;
- action sem schema request/response resolvivel;
- capability suportada sem `_links` coerentes;
- `_links` publicados sem capability correspondente;
- option source sem endpoints de filter/by-ids;
- `RESOURCE_ENTITY` sem politica de selecao adequada;
- field sensivel sem governanca explicita;
- export anunciado sem engine real;
- stats publicados sem contrato visual ou sem exemplos operacionais;
- endpoint legacy aparecendo como baseline canonico.

Cada diagnostico deve conter:

- severidade;
- resource;
- fonte canonica;
- evidencia;
- impacto;
- correcao recomendada;
- validacao minima.

### 10. LLM Operational Surface

Objetivo: conectar o dashboard ao uso real por IA.

Exibir:

- caminho seguro de descoberta;
- exemplos publicos;
- exemplos auth-light;
- headers recomendados;
- exemplos protegidos/reference-only;
- status de smoke quando disponivel;
- relacao entre exemplo e resource/surface/action/capability.

Isso deve usar o corpus operacional como materializacao derivada, nao como fonte
primaria do contrato.

## Arquitetura de dados recomendada

### Corte 1: cockpit autoexposto, sem contrato de dados novo

O primeiro corte deve ser empacotado pelo `praxis-metadata-starter` e compor a
experiencia a partir de endpoints existentes:

- `/actuator/health`;
- `/v3/api-docs`;
- `/schemas/catalog`;
- `/schemas/domain`;
- `/schemas/surfaces`;
- `/schemas/actions`;
- `/schemas/filtered`;
- `/{resource}/capabilities`;
- `/{resource}/{id}/capabilities`, quando houver amostra contextual;
- endpoints de options/stats/export quando descobertos;
- corpus HTTP/LLM surface como referencia operacional opcional.

Vantagens:

- nao cria contrato de dados prematuro;
- nao exige que cada host copie HTML, CSS ou JavaScript;
- prova o que ja existe;
- revela lacunas reais;
- respeita a hierarquia canonica.

### Corte 2: composition service local

Se a UX ficar pesada, criar uma camada de composicao no host de referencia ou no
runtime, ainda sem promover contrato publico do starter.

Responsabilidade:

- cachear leituras;
- normalizar view models;
- calcular diagnostics experimentais;
- preservar links para fontes canonicas.

### Corte 3: contrato agregado, se comprovado

So depois de evidencia real considerar endpoint como:

```text
GET /schemas/host-cockpit
GET /schemas/resources/summary
GET /schemas/diagnostics
```

Qualquer endpoint desse tipo deve declarar:

- fonte canonica de cada campo;
- consumidores;
- versao do contrato;
- headers/cache;
- relacao com ETag/schema hash;
- testes focais;
- docs e exemplos derivados.

## Proposta de implementacao por fases

### Fase 0. Inventario canonico

Entregaveis:

- matriz de aderencia;
- lista de endpoints usados;
- lista de resources ricos para prova;
- lista de resources simples para contraste;
- decisao explicita sobre ausencia de contrato novo.

Resources candidatos:

- `operations.missoes` para actions, surfaces e item capabilities;
- `human-resources.funcionarios` para CRUD e forms/tables;
- `procurement.suppliers` para option source governada;
- views analiticas para stats/dashboard preview.

### Fase 1. Product spec e design system

Entregaveis:

- wireframes;
- vocabulary visual;
- estados vazios;
- estados de erro;
- criterios de qualidade visual;
- criterio de primeiro viewport.

### Fase 2. Prototype navegavel

Local recomendado:

- `praxis-metadata-starter`, em assets estaticos empacotados no jar e URL
  automatica `/praxis/cockpit`;
- `praxis-api-quickstart` apenas como host consumidor para provar com HTTP real;
- extracao futura para `praxis-ui-angular` somente quando os componentes
  estabilizarem como runtime oficial.

### Fase 3. Diagnostics experimentais

Entregaveis:

- regras de diagnostico documentadas;
- severidades;
- links de correcao;
- smokes de resources ricos e simples.

### Fase 4. Integracao com documentacao publica

Entregaveis:

- guia de uso do cockpit;
- exemplos no quickstart;
- referencias na landing page;
- exemplos HTTP atualizados quando houver nova superficie.

### Fase 5. Avaliacao de contrato agregado

So iniciar se a fase 2/3 provar uma lacuna real.

## Mapa de impacto

Subprojeto canonico afetado:

- `praxis-metadata-starter`, como fonte semantica.

Consumidores impactados:

- `praxis-api-quickstart`, como host de prova que recebe o cockpit automaticamente;
- `praxis-ui-angular`, como runtime oficial;
- `praxis-ui-landing-page`, como docs/playground publico;
- `praxisui-http-examples`, como corpus operacional e LLM surface.

Docs publicas potencialmente afetadas:

- README do starter;
- architecture overview;
- guides de consumo Angular;
- conformance;
- quickstart README;
- landing page oficial.

Examples/playgrounds potencialmente afetados:

- cockpit automatico publicado pelo starter;
- quickstart como host consumidor;
- corpus HTTP metadata;
- LLM surface;
- demos Angular de metadata/runtime.

Validacao minima para o primeiro corte:

- inspecao dos endpoints metadata usados;
- build/test focal do subprojeto onde a UI for implementada;
- smoke visual em desktop e mobile;
- smoke com um resource rico e um resource simples;
- validacao de estados de erro com endpoint ausente ou host parcial.

Risco de breaking change:

- baixo para contratos de dados se a primeira entrega for apenas UX derivada;
- medio para superficie publica porque `/praxis/cockpit` passa a existir em todo host;
- medio se houver composition service local;
- alto se houver endpoint publico agregado sem inventario previo.

## Criterios de qualidade surpreendente

- O primeiro viewport deve explicar o valor do Praxis sem texto de marketing.
- A pessoa deve conseguir responder "o que este host sabe?" em menos de 10
  segundos.
- A pessoa deve conseguir localizar uma incoerencia de contrato em menos de 30
  segundos.
- A IA deve poder usar a pagina como mapa de grounding, nao como lista solta de
  endpoints.
- A UI deve ensinar a diferenca entre structural contract, semantic discovery,
  capability snapshot e runtime materialization.
- Cada diagnostico deve apontar a fonte canonica correta, nao sugerir remendo no
  consumidor.
- O design deve ser futurista, mas denso, legivel e operacional.

## Antiobjetivos

- Nao criar editor local de schema dentro do metadata starter.
- Nao transformar o dashboard em fonte primaria de regra de negocio.
- Nao rotear intencao por keywords ou regexes no cockpit.
- Nao duplicar semantica de `/api/praxis/config/**`.
- Nao institucionalizar aliases ou contratos paralelos por conveniencia visual.
- Nao esconder JSON tecnico; apenas deixa-lo como detalhe, nao como UX primaria.

## Proximas issues recomendadas

1. Criar prototipo navegavel do Semantic Host Cockpit empacotado no starter,
   usando contratos existentes.
2. Definir diagnostics experimentais para readiness e conformance do host.
3. Criar spike de composition model derivado para conectar resource, schemas,
   domain, surfaces, actions, capabilities e exemplos operacionais.
4. Avaliar, apos o spike, se existe lacuna real para endpoint agregado publico no
   starter.

## Spike concluido

O spike resource-centric esta documentado em
[Semantic Host Cockpit Composition Spike](SEMANTIC-HOST-COCKPIT-COMPOSITION-SPIKE.md).

Conclusao atual:

- ha pivots suficientes em `resourceKey`, `resourcePath`, `path`, `method`,
  `operationId` e `schemaUrl` para um primeiro cockpit derivado;
- o prototipo deve seguir sem endpoint agregado de dados novo;
- a URL automatica do cockpit deve ser `/praxis/cockpit`;
- `HostCockpitView`, diagnostics canonicos e readiness score versionado ainda
  nao estao justificados como `lacuna-real-de-contrato`;
- o quickstart deve provar o consumo por HTTP real, sem copiar assets locais.
