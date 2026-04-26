# Estudo complementar: Praxis UI Angular como runtime metadata-driven

Data: 2026-04-25

Classificacao da mudanca: `docs-apenas`.

## Objetivo

Este documento complementa o estudo de mercado do `praxis-metadata-starter`
incluindo a proxima peca da plataforma: o projeto `praxis-ui-angular`, que fecha
as pontas da solucao ao materializar contratos, configuracoes e decisoes
governadas em componentes Angular orientados a metadados.

A leitura correta e:

- `praxis-metadata-starter` publica grounding estrutural e semantico;
- `praxis-config-starter` governa persistencia, authoring, simulacao,
  publicacao e materializacao;
- `praxis-ui-angular` consome essas fontes canonicas como runtime/cockpit, sem
  virar fonte primaria de regra de negocio.

## Leitura executiva

O `praxis-ui-angular` nao deve ser vendido apenas como "suite de componentes
Angular". Ele e o runtime oficial da plataforma Praxis para:

- renderizar interfaces a partir de `/schemas/filtered`, `x-ui` e discovery
  semantico;
- consumir `_links`, `surfaces`, `actions` e `capabilities` sem reconstruir
  contratos localmente;
- persistir configuracoes em `/api/praxis/config/ui`;
- abrir editores, drawers, modais, builders e assistentes de IA como cockpit de
  decisoes materializadas;
- expor catalogos de componentes, capabilities e manifests para authoring
  agentico;
- manter a fronteira do host visivel, sem impor uma suite monolitica.

Essa peca fecha a narrativa competitiva contra SAP/Fiori, ServiceNow,
Salesforce e low-code: Praxis nao para no backend metadata-driven nem em um
editor visual. Ela tem runtime, cockpit, configuracao remota, authoring,
manifests, AI registry e provas E2E.

## Papel canonico na plataforma

| Camada | Dono canonico | Papel |
| --- | --- | --- |
| Contrato estrutural | `praxis-metadata-starter` | `/schemas/filtered`, `x-ui`, schema hash, ETag |
| Discovery semantico | `praxis-metadata-starter` | `/schemas/domain`, `/schemas/surfaces`, `/schemas/actions`, `/capabilities` |
| Authoring/governanca | `praxis-config-starter` | `/api/praxis/config/**`, `ai_registry`, `domain-rules`, materializacoes |
| Runtime/cockpit | `praxis-ui-angular` | componentes, editors, page builder, assistant, persistence bridge |
| Prova operacional | `praxis-api-quickstart` | host HTTP real, smokes, exemplos verticais |
| Publicacao comercial | `praxis-ui-landing-page` | docs, demos, playground, narrativa publica |

O Angular deve continuar sendo consumidor e cockpit, nao fonte primaria de
semantica de negocio. Quando o runtime precisa decidir algo, a ordem correta e:

1. contrato explicito recebido do backend;
2. capabilities/surfaces/actions publicados;
3. links operacionais `_links`;
4. configuracao governada em `/api/praxis/config/**`;
5. defaults locais apenas como fallback tecnico.

## Recursos atuais que fecham as pontas

### Core: contrato, schema flow e discovery

`@praxisui/core` e a base compartilhada do workspace. Ele concentra:

- clientes de schema com `ETag`, `If-None-Match`, `X-Schema-Hash` e `schemaId`;
- normalizacao e reconciliacao de schemas para form/filter;
- `ResourceDiscoveryService` para seguir `_links`, `surfaces`, `actions` e
  `capabilities`;
- `CrudOperationResolutionService` com precedencia
  `explicit > capability + surface > capability + link > convention`;
- adapters de open para surfaces e workflow actions;
- storage/config remota, global config, i18n, logging, widgets e contratos base.

Isso e uma vantagem competitiva: o frontend nao precisa adivinhar schema, action
ou path. Ele segue discovery canonico.

### CRUD: fluxo operacional resource-oriented

`@praxisui/crud` fecha o primeiro caminho comercial de alto valor:

- combina `praxis-table`, `praxis-dynamic-form` e modos de abertura governados;
- preserva a separacao entre `resourcePath` operacional e `resourceKey`
  semantico;
- usa discovery por `_links`, `surfaces`, `actions` e `capabilities`;
- estabiliza `crudContext` para evitar regressao de change detection;
- delega abertura para `CrudLauncherService`, respeitando route/modal/drawer;
- refaz fetch apos save/delete quando o fluxo conclui.

Comparacao com SAP/Fiori: SAP empacota floorplans fortes; Praxis entrega shell
CRUD composavel, menos monolitico, mas ainda dirigido por contrato.

### Dynamic Form e Dynamic Fields

`@praxisui/dynamic-form` e a superficie canonica de formularios schema-driven e
edicao de configuracao. Ele materializa:

- `FormConfig`;
- layout runtime;
- reconciliacao de metadata remota;
- hot metadata updates;
- editores de configuracao;
- campos locais/transientes com politica de submit;
- integracao com `dynamic-fields`, `settings-panel`, `metadata-editor` e
  visual builders.

`@praxisui/dynamic-fields` fornece o catalogo de campos e a cadeia de descoberta
editorial. O ponto importante e que suporte runtime de campo nao basta: o campo
precisa permanecer descobrivel, nomeado corretamente, editavel e coberto pelo
tooling.

### Table, filters e analytics

`@praxisui/table` e a superficie canonica de tabela enterprise:

- filtros metadata-driven;
- schema especifico de `POST /filter`;
- dinamica local/remota com precedencia documentada;
- regras condicionais e JSON Logic;
- adapters de drawer;
- authoring visual e editores especializados.

Junto de `x-ui.analytics`, `x-ui.chart` e libs de charts, a tabela deixa de ser
apenas grid e passa a ser materializacao de leitura operacional, filtros,
exports, stats e dashboards.

### Remote Config Storage

O Angular ja possui a ponte para `praxis-config-starter`:

- `ApiConfigStorage`;
- `RemoteConfigStorage`;
- `ASYNC_CONFIG_STORAGE` e `CONFIG_STORAGE`;
- `providePraxisGlobalConfigBootstrap(...)`;
- persistencia em `/api/praxis/config/ui`;
- suporte a tenant/user/env;
- fluxo com ETag, `If-Match`, `412`, reload e retry.

Isso fecha a parte de configuracao governada: as libs nao precisam salvar estado
em convencoes locais quando o host habilita storage remoto.

### Page Builder e dynamic page

`@praxisui/page-builder` fecha a frente de composicao visual:

- edicao de paginas dinamicas baseadas em `WidgetPageDefinition`;
- palette, page settings, widget shell e toolbar;
- `composition.links` como wiring canonico;
- `nestedPath` para component ports internas;
- bridge com `SettingsPanel`;
- catalogos de capacidades de IA por widget;
- agentic authoring via backend `praxis-config-starter`;
- `PRAXIS_PAGE_BUILDER_AUTHORING_MANIFEST` como contrato executavel.

O ponto arquitetural mais importante: o Page Builder nao deve redefinir inputs
de widgets filhos nem inventar semantica local. Ele delega para manifests,
`ComponentDocMeta.configEditor` e contratos canonicos.

### AI e authoring agentico

`@praxisui/ai` fecha a experiencia de assistente:

- nao chama providers externos diretamente;
- usa backend `/api/praxis/config/ai/**`;
- fornece shell conversacional compartilhado;
- preserva quick replies ricas e `contextHints`;
- suporta attachments somente como metadados serializaveis;
- usa stream SSE de authoring quando disponivel;
- mantem historico leve com tenant/env/user;
- aplica politicas de risco e confirmacao.

Essa fronteira e essencial para venda enterprise: IA nao vira sidecar local do
frontend; ela e orquestrada pelo backend canonico.

### AI Registry e Component Metadata

O workspace tem duas pecas complementares:

- `ComponentMetadataRegistry` e `ComponentDocMeta`, que descrevem componentes,
  inputs, outputs, icones, libs e editores;
- `tools/ai-registry`, que gera, valida e sincroniza catalogos AI-ready e
  artefatos de ingestao para o backend.

Isso permite que componentes sejam descobertos por builders, settings panels,
assistentes e RAG sem depender de leitura manual do codigo.

## Comparacao com mercado

| Mercado | Como vende | Leitura Praxis Angular |
| --- | --- | --- |
| SAP Fiori Elements | floorplans gerados por OData annotations | Praxis tem runtime composavel por OpenAPI/x-ui, surfaces, actions e capabilities |
| Salesforce | metadata e AI conectados a configuracoes de negocio | Praxis conecta runtime Angular a metadata, config, AI registry e decisions |
| ServiceNow | workflow UI + data fabric + AI Platform | Praxis usa Angular como cockpit de decisions e materializacoes, nao como rule engine |
| Mendix/OutSystems | low-code visual/model-driven | Praxis preserva contratos e codigo, mas permite authoring visual e agentico governado |
| Component suites tradicionais | widgets e temas | Praxis entrega componentes que entendem schema, config remota, authoring, AI e governance |

## Gaps reais do Angular como fechamento da plataforma

### 1. Narrativa publica ainda pode parecer "component library"

O README ainda abre com "suite completa de componentes UI Angular". Isso e
verdade, mas fraco para a plataforma. A narrativa comercial deveria abrir com:

- runtime metadata-driven;
- cockpit de decisoes materializadas;
- composicao governada;
- AI-assisted authoring sobre backend canonico;
- componentes enterprise como materializadores, nao como widgets isolados.

### 2. Readiness por componente precisa virar produto

Cada componente importante deveria ter um score ou matriz:

- schema-driven support;
- remote config support;
- config editor support;
- AI capabilities;
- authoring manifest;
- domain-rule diagnostics projection;
- E2E coverage;
- public docs synced;
- registry ingestion ready.

Isso facilita vender maturidade e priorizar roadmap.

### 3. Decision cockpit precisa ficar mais visivel

O config starter ja governa domain rules, materializacoes e diagnostics. O
Angular precisa expor isso como cockpit comercial:

- decisao proposta;
- grounding usado;
- simulacao;
- materializacoes previstas;
- publicacao;
- materializacoes aplicadas/bloqueadas;
- source hash;
- impacto em components/runtime.

Se essa experiencia ficar escondida em docs tecnicos, o mercado nao percebe o
diferencial.

### 4. O round-trip editor/runtime e o principal risco de produto

O workspace ja tem regras fortes: alterou config, precisa revisar editor visual.
Esse risco deve virar criterio comercial:

- um componente nao esta "enterprise-ready" se suporta JSON mas nao editor;
- nao esta "AI-ready" se tem editor mas nao manifesto/capabilities;
- nao esta "governance-ready" se materializa rule sem diagnostics/source hash;
- nao esta "platform-ready" se quebra storage remoto, ETag ou round-trip.

### 5. Documentacao derivada precisa convergir com a historia integrada

Ha docs excelentes, mas espalhados por libs. A plataforma precisa de uma trilha
unificada:

1. recurso backend publica schema/domain/actions/capabilities;
2. config starter ingere e governa decisions;
3. Angular materializa em CRUD/form/table/page;
4. Page Builder e AI assistant authoram mudancas;
5. settings/storage persistem;
6. landing mostra demo vertical por dominio.

## Roadmap recomendado para fechar as pontas

### Horizonte 1: empacotar a narrativa integrada

1. **Platform Runtime Overview**
   - Documento curto em `praxis-ui-angular` explicando o workspace como runtime
     de metadata, config e decisions.
   - Deve apontar para metadata starter, config starter e quickstart.

2. **Component Readiness Matrix**
   - Matriz por lib: core, table, dynamic-form, dynamic-fields, crud, page-builder,
     ai, settings-panel, list, charts, rich-content, tabs, expansion.
   - Colunas: schema, remote config, editor, AI catalog, authoring manifest,
     domain-rule projection, E2E, public docs.

3. **Demo vertical integrada**
   - Um caso de dominio no quickstart:
     metadata -> domain catalog -> domain rule -> materialization -> Angular
     CRUD/form/table/page builder.
   - Deve ter narrativa de venda: "o que a IA decidiu, por que, como simulou e
     onde materializou".

### Horizonte 2: cockpit de decision materialization

1. **Decision Handoff UI**
   - UI padrao para mostrar `decisionDiagnostics`, `publicationDiagnostics`,
     materialization outcomes e `sourceHash`.
   - Consumida por Page Builder, Dynamic Form, Table e CRUD quando aplicavel.

2. **Materialization Inspector**
   - Painel para abrir uma config de componente e ver de quais decisions ela
     veio.
   - Deve diferenciar config authorada pelo usuario, default do produto,
     materializacao derivada e patch local.

3. **Round-trip Gate**
   - Validacao focada: abrir editor -> alterar -> apply/save -> runtime refletir
     -> reabrir sem perda -> persistir remoto quando habilitado.
   - Deve virar criterio de readiness por componente.

### Horizonte 3: marketplace interno de materializadores

1. **Materializer Catalog**
   - Catalogo de quais componentes conseguem materializar quais tipos de
     decisions: validation, visibility, selection eligibility, workflow, chart,
     dashboard, rich content, page shell.

2. **Authoring Manifest Federation**
   - Manifestos por componente federados no `ai_registry`, com busca por
     componente, target, operation e risco.

3. **Open Runtime Certification**
   - Certificar hosts Angular ou outros runtimes que consigam consumir a mesma
     cadeia de metadata/config/decision sem usar atalhos locais.

## Mapa de impacto para futuras implementacoes

Subprojeto canonico afetado:

- `praxis-ui-angular` para runtime, editors, AI shell, page builder, storage e
  manifests.

Consumidores impactados:

- `praxis-ui-landing-page` para narrativa publica e demos;
- `praxis-api-quickstart` para prova operacional;
- `praxis-config-starter` para contratos AI/config/domain-rules consumidos pelo
  Angular;
- `praxis-metadata-starter` quando o runtime exigir novo grounding semantico.

Docs publicas potencialmente afetadas:

- docs de CRUD, Dynamic Form, Table, Page Builder, AI e Settings Panel;
- docs de landing e guias "what is x-ui", resource/surface/action/capability;
- docs de authoring e AI registry.

Exemplos/playgrounds/recipes potencialmente afetados:

- `quickstart-remote-metadata-lab`;
- `remote-config-storage-persistence-lab`;
- Page Builder agentic validation;
- dynamic-page examples;
- AI recipes e registry ingestion.

Validacoes minimas futuras:

- para docs-only, leitura final e `git diff --check`;
- para public API ou contratos entre libs, build focal da lib alterada e de um
  consumidor direto;
- para config/editor, round-trip focal de editor/runtime;
- para AI/page-builder, gate focal ou completo de agentic authoring conforme
  risco;
- para registry/capabilities, `npm run generate:registry:ingestion` e
  `npm run validate:catalog` quando a superficie funcional mudar.

Risco de breaking change:

- alto em `@praxisui/core`, `public-api`, schema flow, storage e shared models;
- alto em `@praxisui/ai` e page-builder quando endpoints/manifests mudarem;
- medio/alto em form/table/crud quando config/editor/runtime divergirem;
- medio em docs/registry se a mudanca alterar descoberta por IA ou landing.

## Recomendacao final

O `praxis-ui-angular` deve ser apresentado como o **runtime/cockpit oficial da
plataforma de decisoes semanticas Praxis**.

Ele fecha as pontas porque transforma:

- contrato estrutural em UI;
- discovery semantico em acoes e surfaces;
- config persistida em comportamento runtime;
- domain rules em materializacoes visiveis;
- authoring AI em preview, apply, save e auditoria;
- componentes isolados em materializadores governados.

Assim, a historia completa fica mais forte que "metadata-driven UI":

> Praxis publica semantica, governa decisoes, materializa em componentes
> enterprise e prova tudo por HTTP real.

Essa e a frase que aproxima o conforto enterprise da SAP, a governanca de dados
da ServiceNow/Salesforce e a produtividade do low-code, mas com contratos
abertos, runtime composavel e fronteiras canonicas claras.

## Fontes internas consultadas

- `praxis-ui-angular/AGENTS.md`
- `praxis-ui-angular/README.md`
- `praxis-ui-angular/docs/README.md`
- `praxis-ui-angular/COMPONENT_METADATA.md`
- `praxis-ui-angular/projects/praxis-core/AGENTS.md`
- `praxis-ui-angular/projects/praxis-core/docs/schema-flow.md`
- `praxis-ui-angular/projects/praxis-crud/AGENTS.md`
- `praxis-ui-angular/projects/praxis-crud/README.md`
- `praxis-ui-angular/projects/praxis-dynamic-form/AGENTS.md`
- `praxis-ui-angular/projects/praxis-table/AGENTS.md`
- `praxis-ui-angular/projects/praxis-page-builder/AGENTS.md`
- `praxis-ui-angular/projects/praxis-page-builder/README.md`
- `praxis-ui-angular/projects/praxis-ai/AGENTS.md`
- `praxis-ui-angular/projects/praxis-ai/README.md`
- `praxis-ui-angular/tools/ai-registry/AGENTS.md`
- `praxis-ui-angular/docs/CRUD-REMOTE-BASELINE.md`
- `praxis-ui-angular/docs/REMOTE-CONFIG-STORAGE-E2E.md`
- `praxis-ui-angular/docs/metadata-editors-architecture.md`
- `praxis-ui-angular/docs/GUIDE-AUTHORING-STANDARD.md`
