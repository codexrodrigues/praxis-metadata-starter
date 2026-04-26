# Estudo de mercado e roadmap competitivo do Praxis Metadata Starter

Data: 2026-04-25

Classificacao da mudanca: `docs-apenas`.

## Objetivo

Este estudo compara os recursos atuais do `praxis-metadata-starter` com plataformas
enterprise de referencia, especialmente SAP, para orientar novos recursos que
posicionem a Praxis como plataforma metadata-driven e AI-operable, nao apenas
como biblioteca de CRUD dinamico.

A tese central e simples: SAP, Salesforce, ServiceNow, Mendix e OutSystems ja
educaram o mercado para aceitar modelos, metadados e automacao como fonte de
produtividade. A oportunidade da Praxis e ir alem: publicar semantica de dominio,
governanca, actions, capabilities e evidencias para que IA possa authorar,
simular, explicar e materializar decisoes canonicas com menor drift entre backend,
runtime, configuracao e documentacao.

## Leitura executiva

O `praxis-metadata-starter` ja tem uma base competitiva forte:

- contrato estrutural canonico em `/schemas/filtered`;
- OpenAPI enriquecido com `x-ui`;
- catalogo documental em `/schemas/catalog`;
- catalogo semantico AI-operable em `/schemas/domain`;
- discovery de experiencias e comandos por `/schemas/surfaces` e `/schemas/actions`;
- snapshots agregados por `/{resource}/capabilities`;
- HATEOAS efetivo via `_links`;
- filtros, option sources, stats e exportacao de colecao;
- governanca de dominio por `@DomainGovernance` e `x-domain-governance`;
- baseline resource-oriented com `@ApiResource`, services e mappers canonicos.

Comparado ao mercado, a Praxis deve ser analisada como solucao integrada entre
`praxis-metadata-starter` e `praxis-config-starter`. Muitos pontos que seriam
gaps se o metadata starter fosse visto isoladamente ja aparecem cobertos, ou em
rota canonica clara, pelo config starter:

1. **Governanca de decisoes semanticas**: ja existe como direcao e superficie
   publica em `/api/praxis/config/domain-rules/**`, com intake, simulacao,
   publicacao, materializacao, diagnostics e lifecycle.
2. **Modelo operacional de lifecycle**: ja aparece em releases imutaveis de
   catalogo, `domain_knowledge_change_set`, status direcionais de regras e
   materializacoes, `sourceHash` e diagnostics de publicacao/materializacao.
3. **Materializacao derivada**: ja esta modelada por `domain_rule_definition` e
   `domain_rule_materialization`, separando decisao canonica de payloads
   concretos como `option_source`, `backend_validation` e `form_config`.
4. **Authoring agentico**: ja existe em `/api/praxis/config/ai/**`, manifests
   executaveis em `ai_registry`, stream SSE, threads/turns/actions e gates reais
   com quickstart e Angular.

Portanto, os gaps restantes nao sao "criar tudo isso". Sao: consolidar a
narrativa de plataforma integrada, conectar melhor as provas comerciais, expor
capability/readiness como produto e reduzir a distancia entre os contratos ja
existentes e a comunicacao publica.

## Estado atual do starter

### Recursos canonicos ja existentes

| Eixo | Recurso atual | Valor para o mercado |
| --- | --- | --- |
| Contrato estrutural | `/schemas/filtered`, `ETag`, `X-Schema-Hash`, `schemaId` | Permite UI/runtime dinamico com cache, variacao por contexto e contrato testavel |
| Vocabulario UI | `x-ui`, `@UISchema`, Bean Validation, validacoes, tipos e controles | Reduz cola de frontend e permite formularios/tabelas metadata-driven |
| Recurso canonico | `@ApiResource(resourceKey)`, controllers/services resource-oriented | Padroniza recursos como unidades semanticas estaveis |
| Discovery documental | `/schemas/catalog` | Gera base para docs, playground, RAG e exploracao por ferramentas |
| Discovery semantico | `/schemas/surfaces`, `/schemas/actions` | Separa experiencia de UI, comando de negocio e contrato estrutural |
| Capabilities | `/{resource}/capabilities`, `/{resource}/{id}/capabilities` | Explica o que pode ser feito agora por colecao ou item |
| Governanca AI | `/schemas/domain`, `@DomainGovernance`, `AiUsageMode` | Fundamenta uso de IA com classificacao, politica e evidencias |
| Options | `x-ui.optionSource`, registries e endpoints de option sources | Cria selects, lookups e cascatas governadas sem endpoints ad hoc |
| Analytics | `x-ui.analytics`, `x-ui.chart`, `/stats/*` | Abre caminho para dashboards metadata-driven e indicadores governados |
| Exportacao | `POST /{resource}/export`, `CollectionExportCapability`, engines CSV/JSON | Resolve demanda enterprise de exportacao com filtros, selecao e limites |
| HATEOAS | envelope `_links` | Liga contrato publicado a affordances HTTP reais |

### Forca diferencial atual

O starter ja e mais proximo de uma camada semantica de plataforma do que de um
gerador de UI. A separacao entre `resource`, `surface`, `action` e `capability`
e uma vantagem porque evita o erro comum de transformar a tela em fonte primaria
da regra de negocio.

Na narrativa comercial, isso deve ser vendido como:

- "contratos vivos para produtos internos";
- "metadata que a IA consegue entender e auditar";
- "UI dinamica com semantica de negocio, nao apenas schema";
- "governanca antes da materializacao";
- "menos lock-in que suites monoliticas, com padrao aberto OpenAPI + extensoes".

## Praxis integrada: metadata starter + config starter

O estudo competitivo nao deve tratar o `praxis-metadata-starter` como produto
isolado. Ele e o publicador canonico de grounding estrutural e semantico; o
`praxis-config-starter` e a fronteira canonica de persistencia, authoring,
governanca, simulacao, publicacao e materializacao das decisoes.

| Concern | Dono canonico na Praxis | Cobertura atual observada |
| --- | --- | --- |
| Grounding estrutural | `praxis-metadata-starter` | `/schemas/filtered`, OpenAPI enriquecido, `x-ui`, hashes e headers |
| Grounding semantico | `praxis-metadata-starter` | `/schemas/domain`, `/schemas/surfaces`, `/schemas/actions`, `/capabilities` |
| Persistencia de catalogo | `praxis-config-starter` | `domain_catalog_release`, `domain_catalog_item`, raw payload, tenant/env |
| Knowledge layer governada | `praxis-config-starter` | `domain_knowledge_*`, aliases, bindings, relationships, evidence, change sets |
| Authoring AI | `praxis-config-starter` | `/api/praxis/config/ai/**`, manifests em `ai_registry`, patch/preview/apply, SSE |
| Decisao semantica compartilhada | `praxis-config-starter` | `domain_rule_definition`, intake, simulation, publication, diagnostics |
| Materializacao derivada | `praxis-config-starter` | `domain_rule_materialization`, `option_source`, `backend_validation`, `form_config` |
| Runtime oficial | `praxis-ui-angular` | CRUD, forms, table, page builder, cockpit/handoff de regras |
| Prova operacional | `praxis-api-quickstart` | smokes HTTP/SSE, Domain Catalog v2, Page Builder E2E com LLM real |

Essa divisao e competitivamente importante: a Praxis nao precisa colocar
authoring e rule lifecycle dentro do metadata starter. O metadata starter deve
continuar limpo como produtor de semantica confiavel; o config starter deve
absorver o ciclo de decisao governada.

### Recursos do config starter que cobrem gaps do estudo inicial

O `praxis-config-starter` ja cobre, ou documenta como fronteira canonica, varios
itens que pareciam lacunas:

- **Domain Catalog persistido**: ingestao e armazenamento de releases
  imutaveis publicados por `/schemas/domain`.
- **Domain Knowledge Layer**: conceitos, aliases, bindings, relationships,
  evidence, lifecycle, stewardship, AI visibility e change sets.
- **Shared Rule Definition**: `domain_rule_definition` captura intencao de regra
  reutilizavel, ownership, evidencias, governanca, status, aprovacao e versao.
- **Rule Materialization**: `domain_rule_materialization` separa a decisao
  canonica das projecoes concretas para `form_config`, `option_source`,
  `backend_validation`, workflow, policy engine e outros targets.
- **Simulacao/publicacao**: `/api/praxis/config/domain-rules/**` inclui intake,
  simulations, publications, materializations e status transitions.
- **Diagnostics de decisao**: responses incluem `decisionDiagnostics`,
  `publicationDiagnostics`, `sourceHash`, outcomes e explicabilidade para o host
  renderizar sem reconstruir heuristicas localmente.
- **Authoring agentico**: `ai_registry`, authoring manifests, patch orchestration,
  stream SSE, threads, turns, replay, probe e cancelamento.
- **Prova real de plataforma**: release decision do config starter registra
  smokes HTTP/SSE, Domain Catalog v2, materializacoes de procurement e Page
  Builder full E2E com provider real.

Em outras palavras: a Praxis ja esta mais perto de "semantic decision platform"
do que o estudo inicial sugeria. O trabalho agora e vender a composicao correta.

## Referencias de mercado

### SAP Fiori Elements, OData, CDS e RAP/CAP

SAP Fiori Elements usa OData, metadata e annotations para gerar experiencias
como list reports e object pages em runtime, com menos codigo JavaScript. A
documentacao SAP afirma que Fiori Elements gera apps usando floorplan especifico,
service metadata e annotations em runtime. SAP CAP tambem modela dominio e
services em CDS e serve APIs OData/OpenAPI, incluindo actions/functions,
autorizacao, privacidade, extensibilidade e multitenancy.

Leitura para Praxis:

- SAP prova que metadata-driven e vendavel em ambiente enterprise.
- OData annotations sao o paralelo comercial mais facil para explicar `x-ui`.
- A lacuna da SAP para a Praxis explorar e composabilidade mais aberta,
  explicabilidade AI-operable e menor dependencia de um stack monolitico.
- SAP e forte em floorplans e vocabularios maduros; Praxis deve ser forte em
  contrato semantico aberto, runtime host-aware e governanca de decisoes.

### Salesforce Einstein 1

Salesforce posiciona o Einstein Copilot como assistente unificado, grounded em
dados da empresa e conectado ao metadata da plataforma, entendido como os dados
que descrevem configuracoes de negocio do cliente.

Leitura para Praxis:

- A mensagem "AI grounded in trusted data and metadata" ja esta validada no
  mercado.
- Praxis pode ser mais explicita: nao apenas "metadata da configuracao", mas
  "catalogo semantico, evidencias, governanca, actions e capabilities".
- O starter deve continuar enriquecendo `/schemas/domain` como base de RAG,
  explicacao, mascaramento e authoring governado.

### ServiceNow AI Platform

ServiceNow trata metadata como arquivos de aplicacao organizados por categorias
como AI, automation, data, integrations e UI. Em 2025, reforcou a narrativa de
Workflow Data Fabric e Workflow Data Network para conectar dados, contexto,
governanca e AI agents em workflows.

Leitura para Praxis:

- ServiceNow vende "workflow + data + AI" como tecido operacional.
- Praxis deve responder com "semantic decision fabric": recursos, actions,
  capabilities, surfaces, policies e materializacoes versionadas.
- A oportunidade esta em tornar cada endpoint e cada decisao authorada pela IA
  auditavel, simulavel e publicavel como artefato de plataforma.

### Mendix e OutSystems

Mendix vende model-driven development como fundamento de low-code: modelos
abstraem logica, dados e UI em um IDE visual. OutSystems se posiciona como
plataforma AI-powered low-code para apps e agents enterprise.

Leitura para Praxis:

- Low-code venceu a narrativa de produtividade, mas frequentemente concentra
  regra em builders visuais dificeis de auditar por codigo.
- Praxis pode se diferenciar mantendo fonte canonica em contratos e codigo de
  plataforma, enquanto oferece materializacao visual e AI-assisted authoring.
- O valor comercial nao deve ser "arrastar componentes"; deve ser "authorar
  decisoes semanticas governadas e materializar para varios runtimes".

## Comparacao competitiva

| Capacidade | SAP | Salesforce | ServiceNow | Mendix/OutSystems | Praxis hoje | Onde Praxis pode liderar |
| --- | --- | --- | --- | --- | --- | --- |
| UI metadata-driven | Muito forte com Fiori Elements | Forte no CRM e builders | Forte em workspaces/forms | Forte visual low-code | Forte em `x-ui`, forms, tables, CRUD | Runtime aberto + host-aware sem suite monolitica |
| Modelo de dominio | CDS/RAP/CAP maduro | Metadata de objetos/config | Common data/workflow model | Modelos visuais | `resourceKey`, `/schemas/domain`, governanca | Grafo semantico AI-operable versionado |
| Actions/workflows | Actions/functions e RAP | Flow/Actions | Flow/Playbooks | Process flows | `/schemas/actions`, capabilities, domain rules | Simulacao, publicacao e materializacao governada |
| Governanca AI | Crescente com Joule | Trust Layer e metadata grounding | Data fabric + governance | AI governance emergente | `@DomainGovernance`, `AiUsageMode`, `ai_registry`, `domain-rules` | Decision authoring com aprovacao, evidencias, diagnostics e rollback |
| Interoperabilidade | Forte, mas SAP-centric | Forte dentro Salesforce | Forte dentro ServiceNow | Plataforma proprietaria | OpenAPI + x-* + Spring | Contrato aberto multi-backend e suite de conformidade |
| Prova operacional | Muito madura | Muito madura | Muito madura | Muito madura | Quickstart + landing + docs | Demos verticais com audit trail e IA explicavel |

## Gaps reais da solucao integrada

### 1. Decisao semantica ja existe, mas a narrativa esta fragmentada

No metadata starter isolado, a entidade explicita de "decisao semantica" nao
aparece. Mas na solucao integrada ela ja esta modelada no config starter como
`domain_rule_definition`, `domain_rule_materialization`, intake, simulation,
publication, status transitions e diagnostics.

O gap real e de comunicacao e empacotamento:

- mostrar o fluxo completo metadata -> domain catalog -> knowledge layer ->
  shared rule -> simulation -> publication -> materialization -> runtime;
- documentar claramente que metadata starter publica grounding e config starter
  governa a decisao;
- expor exemplos comerciais que atravessem os dois starters.

### 2. Lifecycle semantico existe, mas precisa virar experiencia de produto

O config starter ja registra releases, change sets, status direcionais,
approved/active/applied timestamps, `sourceHash`, diagnostics e smokes. O que
ainda precisa amadurecer e a experiencia de produto sobre esse lifecycle:

- comparar duas versoes semanticas;
- explicar breaking changes;
- gerar changelog de contrato para consumidores;
- simular impacto em runtime Angular, examples e quickstart;
- assinar/promover releases de metadata e decisoes com linguagem de negocio.

### 3. Catalogo e knowledge layer precisam de uma camada comercial

`/schemas/catalog`, `/schemas/domain`, `/schemas/surfaces`, `/schemas/actions`,
`domain_catalog_release`, `domain_knowledge_*` e `domain-rules` sao poderosos,
mas a maior parte da venda ainda depende de entender nomes tecnicos.

A camada comercial deve traduzir isso para:

- catalogo de capabilities por dominio;
- matriz "quem pode fazer o que, quando e por que";
- exemplos de jornadas reais;
- score de maturidade metadata-driven por recurso;
- explicacao natural language gerada a partir do catalogo e da regra governada.

### 4. Falta uma narrativa de extensibilidade enterprise

SAP tem vocabulos e floorplans. ServiceNow tem app files e workflows. Salesforce
tem metadata de negocio. Praxis precisa nomear e estabilizar seus pontos de
extensao como produto:

- vocabulary packs (`x-ui`, `x-ui.chart`, `x-ui.analytics`, `x-domain-governance`);
- capability providers;
- action availability rules;
- surface packs;
- option source executors;
- export engines;
- domain evidence providers;
- conformance suites por backend.

## Roadmap recomendado

### Horizonte 1: fortalecer venda e confianca tecnica

Prioridade alta para curto prazo.

1. **Metadata Capability Score**
   - Endpoint ou relatorio derivado que pontua cada recurso: schema, idField,
     surfaces, actions, capabilities, domain governance, options, stats, export,
     examples e links.
   - Valor comercial: mostrar maturidade e gaps de adocao para clientes.
   - Fonte canonica: metadata starter calcula maturidade de grounding; config
     starter acrescenta maturidade de ingestao, knowledge, regras e
     materializacoes.

2. **Semantic Diff**
   - Comparar dois payloads de `/schemas/domain`, `/schemas/filtered` ou
     `/schemas/catalog` e classificar impacto: non-breaking, additive, warning,
     breaking.
   - Valor comercial: "governanca de mudanca" para plataformas internas.
   - Melhor lugar: metadata starter pode definir diff de contrato publicado;
     config starter armazena releases, change sets, publicacoes e impacto de
     decisoes.

3. **AI Explain Contract**
   - Gerar uma explicacao estruturada por recurso: "o que e", "quais dados
     governa", "quais actions existem", "quais riscos AI", "quais links executar".
   - Valor comercial: RAG, onboarding e venda para lideranca.
   - Deve combinar `/schemas/domain`, `/schemas/actions`, `/schemas/surfaces`,
     `/capabilities`, `/schemas/catalog`, domain knowledge e domain rules.

4. **Conformance Report por recurso**
   - Hoje existe guia de conformidade. O proximo passo e um output machine-readable
     por recurso e por backend.
   - Valor comercial: certificacao "Praxis-compatible backend".
   - A certificacao deve cobrir os dois lados: produtor de metadata e consumidor
     de config/authoring quando a solucao vender decisao governada.

### Horizonte 2: avancar alem de SAP/Fiori em semantica operacional

Prioridade alta para diferenciar.

1. **Decision Readiness Manifest**
   - Manifesto que informa se um recurso tem grounding suficiente para IA authorar
     decisoes com seguranca.
   - Campos: required evidence, missing governance, risky fields, allowed AI modes,
     approval policy, executable actions, simulation endpoints.
   - Deve ser agregado pela solucao: metadata starter informa grounding; config
     starter informa knowledge/rules/materialization readiness.

2. **Action Simulation Contract**
   - Para `@WorkflowAction`, publicar metadados de simulacao: dry-run supported,
     input schema, side effects, required approvals, expected state transition,
     rollback hints.
   - Importante: nao transformar o metadata starter em rule engine. Ele publica
     contrato e discovery; a simulacao governada ja pertence ao config starter
     quando o assunto e decisao semantica compartilhada.

3. **State Transition Semantics**
   - Formalizar transicoes de estado associadas a actions e availability.
   - SAP/CAP tem status flows e actions; Praxis pode ter fluxo mais aberto e
     AI-operable, ligado a `ResourceStateSnapshot`.
   - Para regras e materializacoes, o config starter ja tem status transitions
     direcionais; o proximo passo e alinhar essa narrativa com actions do
     metadata starter e estados de recursos.

4. **Policy-Aware Capabilities**
   - Expandir availability com motivos estruturados, politica aplicada, evidencia
     e proxima acao recomendada.
   - Valor: UI explica por que uma action esta bloqueada; IA nao inventa execucao.
   - Deve consumir diagnostics do config starter quando o bloqueio vier de regra
     governada, e manter capabilities como snapshot agregado, nao segunda fonte
     estrutural de schema.

### Horizonte 3: plataforma de decisoes e ecossistema

Prioridade media, mas estrategica.

1. **Semantic Decision Registry como produto**
   - O registro tecnico ja existe no config starter por domain rules,
     materializacoes, diagnostics, AI turns e registry.
   - O roadmap deve transforma-lo em produto: busca, comparacao, explicacao,
     aprovacao, release notes, impacto e auditoria por dominio.
   - O metadata starter fornece grounding e links de verificacao.

2. **Marketplace interno de resource packs**
   - Pacotes por dominio: HR, financeiro, compras, eventos, contratos, compliance.
   - Cada pack traz recursos, actions, surfaces, governance, examples, prompts de
     IA e testes de conformidade.

3. **Multi-backend certification**
   - Java starter como referencia; .NET/Node/outros backends certificados por
     JSON Schema, HTTP smoke e semantic conformance.
   - Valor comercial: competir com suites proprietarias sem exigir replatforming.

4. **AI Governance Workbench**
   - Cockpit para revisar diffs, aprovar decisoes, publicar releases semanticos e
     acompanhar impacto em runtimes.
   - Donos: config-starter + ui-angular/landing; metadata starter continua como
     fonte de grounding.

## Narrativa comercial recomendada

### Para clientes SAP/Fiori

"Praxis entrega um modelo familiar a quem conhece Fiori Elements: o backend
publica metadados semanticos e o runtime materializa experiencias. A diferenca e
que Praxis usa OpenAPI + extensoes abertas, mantem a fronteira do host visivel e
leva a semantica alem da tela: actions, capabilities, dominio, governanca e IA."

### Para clientes Salesforce/ServiceNow

"Praxis traz o principio de metadata como sistema nervoso da plataforma, mas sem
prender a empresa a um CRM ou workflow suite. Cada recurso publica o que e, o que
pode fazer, quais dados governa, quais actions existem e como a IA pode raciocinar
com seguranca."

### Para clientes low-code

"Praxis nao troca codigo por telas opacas. Ela preserva contratos, testes e APIs
reais, enquanto permite que IA e runtime materializem experiencias dinamicas a
partir de decisoes governadas."

## Plano de execucao sugerido

### Fase 1: estudo em prova vendavel

- Criar um exemplo vertical no quickstart com 2 ou 3 recursos ricos.
- Para cada recurso, publicar `/schemas/filtered`, `/schemas/domain`,
  `/schemas/surfaces`, `/schemas/actions`, `/capabilities`, stats e export.
- Ingerir o catalogo no config starter e demonstrar domain knowledge,
  domain rules, simulation, publication e materialization.
- Na landing, mostrar comparacao com mental model SAP/Fiori e diferenca Praxis.
- Entregar um "AI contract + decision explanation" em linguagem natural por
  recurso e por decisao publicada.

### Fase 2: produto de governanca

- Implementar ou expor Semantic Diff e Conformance Report como artefatos de
  produto, reaproveitando releases/change sets/diagnostics ja existentes no
  config starter.
- Criar manifest de readiness para IA agregando metadata readiness e decision
  readiness.
- Publicar guia "de metadata-driven CRUD para semantic decision platform".

### Fase 3: authoring governado

- Consolidar o config starter como registry de decisoes na narrativa publica.
- Expandir cockpit de aprovacao/publicacao sobre `/domain-rules/**` e
  diagnostics ja existentes.
- Materializar decisions no runtime Angular como projections derivadas, mantendo
  `domain_rule_definition` como fonte canonica da decisao.

## Mapa de impacto para futuras implementacoes

Subprojeto canonico afetado:

- `praxis-metadata-starter` para grounding, discovery, conformance, diff e
  readiness derivados.
- `praxis-config-starter` para authoring, persistencia, aprovacao e publicacao
  de decisoes.

Consumidores impactados:

- `praxis-ui-angular`, especialmente runtime de CRUD, dynamic-form, table, charts
  e clientes de schema/capability.
- `praxis-api-quickstart` como prova operacional por HTTP real.
- `praxis-ui-landing-page` como narrativa publica, examples e playground.

Docs publicas potencialmente afetadas:

- guias de `x-ui`, CRUD, resource/surface/action/capability;
- `LLM_SURFACE` e materiais publicos de RAG quando houver novos endpoints;
- docs de conformidade e exemplos oficiais.

Exemplos/playgrounds/recipes potencialmente afetados:

- examples de dynamic-page;
- AI recipes;
- corpus HTTP;
- quickstart pilots.

Validacoes minimas futuras:

- para mudancas em contrato publico do starter, rodar suite focal de docs/schema,
  capabilities/actions/surfaces conforme `AGENTS.md`;
- para mudancas em `/api/praxis/config/**`, domain rules, authoring ou SSE,
  rodar validacoes focais do config starter e smoke downstream conforme seu
  `AGENTS.md`;
- validar quickstart quando houver impacto em consumidor real;
- validar runtime Angular quando `capabilities`, `x-ui`, `schemaUrl`,
  `requestSchemaUrl`, `responseSchemaUrl`, `ETag` ou `X-Schema-Hash` mudarem.

Risco de breaking change:

- baixo para relatorios derivados novos;
- medio para novas chaves em capabilities/actions/surfaces se consumidores
  assumirem shape fechado;
- medio/alto para alteracoes em `/api/praxis/config/domain-rules/**`,
  diagnostics, materialization targets e authoring manifests, pois ja sao
  contrato publico de decisao governada;
- alto se `x-ui`, `/schemas/filtered`, headers ou semantics de `resourceKey`
  forem alterados sem compatibilidade.

## Recomendacao final

O proximo salto da solucao Praxis nao deve ser "mais controles de UI". Isso e
necessario, mas nao diferencia o produto.

O salto competitivo e vender a composicao correta:

- `praxis-metadata-starter` como **grounding semantico oficial**;
- `praxis-config-starter` como **fronteira de authoring, governanca, publicacao
  e materializacao de decisoes**;
- `praxis-ui-angular` como **cockpit/runtime de materializacoes derivadas**;
- `praxis-api-quickstart` como **prova operacional por HTTP real**.

Nessa composicao, o metadata starter diz:

- ele diz quais recursos existem;
- quais dados e politicas importam;
- quais surfaces e actions sao reais;
- quais capabilities estao disponiveis agora;
- quais evidencias sustentam o grounding;
- quais contratos devem ser usados por consumidores e authoring.

E o config starter governa:

- quais decisoes foram propostas;
- quais evidencias e diagnostics sustentam a decisao;
- quais simulacoes e aprovacoes ocorreram;
- quais materializacoes foram criadas, reutilizadas, bloqueadas ou aplicadas;
- qual `sourceHash` conecta a decisao canonica ao runtime derivado.

Com isso, Praxis fica comparavel a SAP no conforto enterprise do metadata-driven,
mas com uma historia mais moderna: aberta, AI-operable, governada, composavel e
centrada em decisoes semanticas.

## Fontes externas consultadas

- SAP Learning: Fiori Elements usa OData, service metadata e annotations em
  runtime para gerar apps com menos codigo JavaScript:
  https://learning.sap.com/courses/getting-started-with-creating-an-sap-fiori-elements-app-based-on-an-odata-v4-rap-service/getting-started-with-sap-fiori-elements-understanding-odata-and-annotations
- SAP CAP: documentacao de OData APIs, domain modeling, services, actions,
  authorization, privacy, extensibility e multitenancy:
  https://cap.cloud.sap/docs/advanced/odata.html
- Salesforce: Einstein Copilot grounded em dados da empresa e conectado ao
  Salesforce metadata:
  https://www.salesforce.com/news/press-releases/2024/04/25/einstein-copilot-general-availability/
- ServiceNow docs: Studio trabalha com metadata de automations, integrations,
  UI e AI Platform:
  https://www.servicenow.com/docs/r/application-development/servicenow-studio/sn-studio-working-with-metadata.html
- ServiceNow Newsroom: Workflow Data Fabric/Data Network para AI agents,
  workflows, governance e dados conectados:
  https://newsroom.servicenow.com/press-releases/details/2025/ServiceNow-Enhances-Its-Workflow-Data-Fabric-With-New-Ecosystem-to-Power-AI-agents-and-Workflows-With-Real-Time-Intelligence/default.aspx
- Mendix: model-driven development como fundamento de low-code:
  https://www.mendix.com/platform/model-driven-development/
- OutSystems: plataforma AI-powered low-code para apps e agents:
  https://www.outsystems.com/low-code-platform/

## Fontes internas consultadas

- `praxis-metadata-starter/README.md`
- `praxis-metadata-starter/docs/architecture-overview.md`
- `praxis-metadata-starter/docs/spec/CONFORMANCE.md`
- `praxis-config-starter/README.md`
- `praxis-config-starter/AGENTS.md`
- `praxis-config-starter/docs/domain-catalog/governed-semantic-layer-plan.md`
- `praxis-config-starter/docs/domain-catalog/domain-knowledge-layer-v1.md`
- `praxis-config-starter/docs/ai/agentic-authoring-streaming.md`
- `praxis-config-starter/docs/ai-context-runtime-state-decision.md`
- `praxis-config-starter/docs/ai/release-decision-2026-04-25-domain-rules.md`
