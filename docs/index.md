# Praxis Metadata Starter — Documentação Oficial

Bem-vindo à documentação pública do **`praxis-metadata-starter`**, a biblioteca Spring Boot canônica da plataforma Praxis para publicação de **contratos autodescritivos (*self-describing APIs*)**, enriquecimento OpenAPI com `x-ui`, descoberta semântica (`domain`, `surfaces`, `actions`, `capabilities`) e governança de metadados para UIs dinâmicas e agentes de IA.

- **Demonstração ao vivo (Zero Install):** [Abrir o Praxis Cockpit](https://praxis-api-quickstart.onrender.com/praxis/cockpit)
- **Vídeo em Alta Definição (1080p):** [CRUD Studio Zero-Deploy (MP4)](https://praxisui.dev/media/crud-studio-zero-deploy-demo.mp4) · [Dynamic Page Studio (MP4)](https://praxisui.dev/media/dynamic-page-studio-real-use.mp4)
- **Repositório no GitHub:** [codexrodrigues/praxis-metadata-starter](https://github.com/codexrodrigues/praxis-metadata-starter)
- **Javadoc Público:** [Referência de API Java](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/)

<video controls preload="metadata" poster="https://praxisui.dev/media/crud-studio-zero-deploy-demo.gif" style="width:100%;max-width:960px;border-radius:12px;margin:1rem 0;border:1px solid rgba(128,128,128,0.25);">
  <source src="https://praxisui.dev/media/crud-studio-zero-deploy-demo.mp4" type="video/mp4" />
  <a href="https://praxisui.dev/media/crud-studio-zero-deploy-demo.mp4">Assistir ao vídeo de demonstração (MP4)</a>
</video>

---

## O que este starter publica

O baseline canônico atual da plataforma organiza cada recurso sobre cinco pilares (**`resource` + `surface` + `action` + `capability` + `HATEOAS`**):

- **`/schemas/filtered`** — contrato estrutural canônico consumido pelos runtimes para renderização de formulários, filtros e tabelas, com `properties.*.x-ui`, `idField`, `ETag` e `X-Schema-Hash`
- **`/schemas/catalog`** — catálogo documental com resumos, identidade visual, exemplos e links canônicos para os schemas estruturais
- **`/schemas/domain`** — catálogo semântico *AI-operable* com vocabulário de domínio, aliases, evidências, governança (`@DomainGovernance`) e `optionSources` registry-wide
- **`/schemas/surfaces` e `/schemas/actions`** — descoberta semântica de experiências de UI (`@UiSurface`) e comandos explícitos de negócio (`@WorkflowAction`)
- **`/{resource}/capabilities` e `/{resource}/{id}/capabilities`** — snapshot agregado contextual que combina suporte estrutural (`canonicalOperations`) com disponibilidade real (`operations`, `surfaces`, `actions` e `stats.fields`)
- **`POST /{resource}/export`** — operação canônica de exportação de coleção (CSV, JSON, XLSX inline com headers `X-Export-*` ou assíncrono `202 Accepted` com `status=deferred`, `downloadUrl` e `jobId`)
- **`GET /praxis/cockpit`** — cockpit automático empacotado pelo próprio starter para inspeção visual do domínio e auditoria dos contratos publicados

---

## Comece por Objetivo

### 1. Quero entender a arquitetura e os conceitos da plataforma

- [Visão Geral Funcional](overview/VISAO-GERAL.html)
- [Visão Arquitetural](architecture-overview.html)
- [Mapa de Pacotes Java](packages-overview.html)
- [Conceito de `@UISchema` e `x-ui`](concepts/ui-schema.html)
- [Hub de Conceitos](concepts/index.html)
- [Conformidade da Especificação (`CONFORMANCE`)](spec/CONFORMANCE.html)

### 2. Quero criar uma aplicação nova ou modelar recursos no backend

1. [Guia 01 — Backend: Aplicação Nova com Core Canônico](guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.html)
2. [Guia 02 — Backend: Recurso Metadata-Driven](guides/GUIA-02-AI-BACKEND-CRUD-METADATA.html)
3. [Guia 04 — Quando usar Resource, Surface, Action e Capability](guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.html)
4. [Guia 05 — Do CRUD ao Contrato Semântico](guides/GUIA-05-DO-CRUD-AO-CONTRATO-SEMANTICO.html)
5. [Guia 06 — Redação Semântica de Annotations para IA](guides/GUIA-06-REDACAO-SEMANTICA-DE-ANNOTATIONS-PARA-IA.html)
6. [Authoring Semântico de Metadata (`@UISchema` Presets e `@MicroVisualization`)](guides/SEMANTIC-METADATA-AUTHORING.html)
7. [Hub Completo de Guias](guides/index.html)

### 3. Quero adotar capacidades avançadas (Lookups, Exportação, Disponibilidade, Reatividade)

- [Options e Option-Sources Governadas](guides/OPTIONS-ENDPOINT.html) · [RFC `x-ui.optionSource`](spec/x-ui-option-source-rfc.html)
- [Exportação Canônica de Coleções](guides/COLLECTION-EXPORT.html)
- [Disponibilidade Contextual Corporativa (Enterprise Availability)](guides/ENTERPRISE-AVAILABILITY-ADOPTION.html)
- [Determinações Reativas de Formulário](spec/reactive-determinations.html)
- [Filtros e Paginação](guides/FILTROS-E-PAGINACAO.html) · [Views Somente Leitura](guides/READ-ONLY-VIEWS.html) · [Erros e Respostas](guides/ERROS-E-RESPOSTAS.html)
- [RFC — GraphQL Support](technical/GRAPHQL-SUPPORT-RFC.html)

### 4. Quero integrar um runtime Angular

- [Guia 03 — Frontend: Angular CRUD Completo](guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.html)
- [Checklist de Validação Ponta a Ponta](guides/CHECKLIST-VALIDACAO-IA.html)

### 5. Quero referência técnica Java e especificações do SDK de Operações em Lote (Bulk)

- [Javadoc Público](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/) · [Índice Humano do Javadoc](api/index.html)
- [Hub de Documentação Técnica](technical/index.html)
- [Status Consolidado do SDK Bulk e Candidatos de Release](technical/BULK-AND-RELEASE-CANDIDATES-STATUS.html)
- [Guia de Composição de Lote (`BULK-COMPOSITION-GUIDE`)](technical/BULK-COMPOSITION-GUIDE.html)

#### Especificações Técnicas do Protocolo de Lote (Bulk SDK) e Lifecycle Governado

- **Protocolo, Binding e Contratos CRUD Bulk**:
  - [Entrada do protocolo de operações em lote](spec/BULK-PROTOCOL-INPUT.html)
  - [Propostas, resultados e fingerprint de intenção](spec/BULK-PROTOCOL-RESULTS.html)
  - [Binding canônico de operações](spec/CANONICAL-OPERATION-BINDING.html)
  - [Leitura estrita de request](spec/CANONICAL-REQUEST-SCHEMA.html)
  - [Campos editáveis em lote (`@BulkEditable`)](spec/BULK-EDITABLE-FIELDS.html)
  - [Composição estrutural CRUD](spec/BULK-CRUD-STRUCTURE.html)
  - [Composição operacional CRUD (`UNIFORM_UPDATE` / `PER_ITEM_UPDATE`)](spec/BULK-CRUD-OPERATIONS.html)
  - [Projeção P1 de action bulk (`execution.bulk`)](spec/BULK-ACTION-PROJECTION.html)
- **Persistência, Execução Durável, Read Model e Lifecycle OpenAPI**:
  - [Infraestrutura transacional de lote](spec/BULK-EXECUTION-INFRASTRUCTURE.html)
  - [Persistência protegida de propostas (`EXPLICIT` e `QUERY`)](spec/BULK-PROPOSAL-STORAGE.html)
  - [Evidência protegida da avaliação](spec/BULK-EVALUATION-EVIDENCE.html)
  - [Núcleo durável de execução](spec/BULK-DURABLE-EXECUTION.html)
  - [Decisão H1b de leitura (`RS1–RS4`, cursores AEAD)](spec/BULK-H1B-READ-MODEL.html)
  - [Lifecycle governado de publicação OpenAPI](spec/BULK-OPERATION-LIFECYCLE.html)
- **Capacidade, Administração Local, Workers e Owner Gerenciado**:
  - [Autoridade de capacidade candidata B5b.1a](spec/BULK-CAPACITY-AUTHORITY.html)
  - [Instalação local candidata B5b.1b.A](spec/BULK-CAPACITY-INSTALLATION.html)
  - [Ocupação local protegida B5b.1b.B e caracterização de clone C1a](spec/BULK-CAPACITY-OCCUPANCY.html)
  - [Administração local de capacidade (candidato C0-04)](spec/BULK-CAPACITY-LOCAL-ADMINISTRATION.html)
  - [Estado do worker durável B5b.2](technical/BULK-DURABLE-WORKER-B5B2-STATUS.html) · [Migração owner e cutover B5b.2](technical/BULK-DURABLE-WORKER-OWNER-UPGRADE.html)
  - [Composição explícita do worker — candidato B5b.3](technical/BULK-WORKER-EXPLICIT-COMPOSITION.html)
  - [Adoção de operações em lote por owner gerenciado (PostgreSQL 17+)](spec/BULK-MANAGED-OWNER-ADOPTION-PLAN.html)

---

## Regra de Leitura

Quando houver dúvida sobre a superfície publicada:
1. Priorize os guias principais desta home (`Guia 01` a `Guia 06`) e a [Visão Arquitetural](architecture-overview.html).
2. Trate `/schemas/filtered` como a única fonte estrutural de request/response e `/capabilities` como o snapshot agregado de disponibilidade.
3. Consulte o [Status Consolidado do SDK Bulk e Candidatos de Release](technical/BULK-AND-RELEASE-CANDIDATES-STATUS.html) para distinguir capacidades já publicadas no Maven Central de candidatos internos em validação.
