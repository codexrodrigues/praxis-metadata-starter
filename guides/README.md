# Guias de Implementacao

Esta pasta reune os guias operacionais do `praxis-metadata-starter` para uso
por pessoas e por LLMs.

## Trilha principal para LLM

Use os guias nesta ordem:

0. [Guia 00 - Blueprint Completo de um Recurso Praxis (20 Pilares e 3 Fases)](GUIA-00-BLUEPRINT-COMPLETO-RECURSO-PRAXIS.md)
1. [Guia 01 - Backend - Aplicacao Nova](GUIA-01-AI-BACKEND-APLICACAO-NOVA.md)
2. [Guia 02 - Backend - Recurso Semântico Metadata-Driven](GUIA-02-AI-BACKEND-CRUD-METADATA.md)
3. [Guia 04 - Quando usar Resource, Surface, Action e Capability](GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md)
4. [Guia 05 - Do Cadastro Básico ao Contrato Semântico](GUIA-05-DO-CRUD-AO-CONTRATO-SEMANTICO.md)
5. [Guia 06 - Redacao Semantica de Annotations para IA](GUIA-06-REDACAO-SEMANTICA-DE-ANNOTATIONS-PARA-IA.md)
6. [Enterprise Availability Adoption](ENTERPRISE-AVAILABILITY-ADOPTION.md)
7. [Guia 03 - Frontend - Runtime Angular de Recursos Completo](GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md)
8. [Checklist de Validacao](CHECKLIST-VALIDACAO-IA.md)

Essa trilha foi organizada para que a LLM consiga percorrer os passos sem
depender de app externo nem de conhecimento oral do time.

Se o objetivo for prova operacional repetivel, siga depois para
`ai-proof/README.md`.

## Quando usar cada guia

### Guia 01

Use para criar uma nova aplicacao Spring Boot minima e correta sobre o starter.

### Guia 02

Use para gerar um recurso metadata-driven no baseline canonico atual, com
modelo resource-oriented, DTOs separados e semantica correta de contrato.

### Guia 04

Use para decidir quando a modelagem deve ficar em `resource`, quando deve subir
para `@UiSurface`, quando deve virar `@WorkflowAction` e quando o cliente deve
consumir `capabilities`.

### Guia 05

Use quando for preciso explicar visualmente a diferenca entre uma leitura de cadastro
simplificada e o contrato semantico canonico, e como `ResourceIntent`, `UiSurface`,
`WorkflowAction` e `capabilities` se encaixam no mesmo contrato.

### Guia 06

Use quando a tarefa for preencher ou revisar textos publicos de annotations,
como `@Operation`, `@ResourceIntent`, `@UiSurface`, `@WorkflowAction`,
`@Schema`, `@UISchema`, `@UiAnalytics`, tags e descricoes consumidas por OpenAPI,
`/schemas/catalog`, `/schemas/domain`, capabilities, RAG e assistentes.

### Enterprise Availability Adoption

Use quando um recurso precisar de availability contextual corporativa em
operacoes canonicas, actions ou surfaces. O guia define quando plugar
`ResourceOperationAvailabilityProvider`, `ActionAvailabilityRule`,
`SurfaceAvailabilityRule` e `ResourceStateSnapshotProvider`, alem do shape
seguro de metadata publica para Cockpit e IA.

### Guia 03

Use apenas quando o backend ja publicar corretamente o contrato metadata-driven
e a tarefa for gerar o host Angular canonico.

### Checklist

Use depois da geracao para validar build, endpoints, schemas e consumo.

## Guias complementares

- [Filtros e Paginacao](FILTROS-E-PAGINACAO.md)
- [Ordenacao Padrao](ORDEM-PADRAO.md)
- [Options (id/label)](OPTIONS-ENDPOINT.md)
- [Implementando OptionSourceProvider externo](OPTION-SOURCE-PROVIDER-SPI.md)
- [Exportacao de Colecoes](COLLECTION-EXPORT.md)
- [Enterprise Availability Adoption](ENTERPRISE-AVAILABILITY-ADOPTION.md)
- [Views / Somente Leitura](READ-ONLY-VIEWS.md)
- [Receitas Analíticas Verificadas e Inteligência Estatística](VERIFIED-ANALYTICAL-RECIPES-AND-STATISTICAL-INTELLIGENCE.md)
- [Erros e Respostas](ERROS-E-RESPOSTAS.md)
- [Do Cadastro Básico ao Contrato Semântico](GUIA-05-DO-CRUD-AO-CONTRATO-SEMANTICO.md)
- [Redacao Semantica de Annotations para IA](GUIA-06-REDACAO-SEMANTICA-DE-ANNOTATIONS-PARA-IA.md)
- [Semantic Metadata Authoring](SEMANTIC-METADATA-AUTHORING.md)
- [Quick View 360° e Curadoria Executiva Inteligente](QUICK-VIEW-360-E-CURADORIA-EXECUTIVA.md)
- [Core Resource Service](CORE-RESOURCE-SERVICE.md)
- [ApiPaths e Option Sources](API-PATHS-E-OPTION-SOURCES.md)
- [Admissão de Mutações de Recurso](RESOURCE-MUTATION-ADMISSION.md)
- [Adoção de Duplicate-Draft](DUPLICATE-DRAFT-ADOPTION.md)
- [Motor Declarativo de Operações em Lote](BULK-DECLARATIVE-RESOURCE-ENGINE.md)
- [Ponte de Regras Governadas (`decisionKind = BUSINESS`)](GOVERNED-RULE-BRIDGE.md)
- [Ontologia e Catálogo Semântico de Domínio](DOMAIN-CATALOG-ONTOLOGY.md)
- [Guia 03 - Adocao do Consumidor Piloto](GUIA-03-ADOCAO-CONSUMIDOR-PILOTO.md)
- [Prova Operacional dos Guias de IA](ai-proof/README.md)

## Papel desta pasta

- `docs/README.md` e o hub geral da documentacao
- este arquivo organiza a trilha de leitura dos guias
- `ai-proof/README.md` organiza apenas a trilha de prova
- o Javadoc publico do starter e referencia tecnica complementar:
  `https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/`

## Criterio de qualidade

Os guias desta pasta devem permanecer aderentes a:

- codigo canonico do `praxis-metadata-starter`
- contrato realmente consumido pelo runtime Angular oficial da plataforma
- protocolo de prova documentado em `ai-proof/`

Quando houver divergencia entre guia, codigo canonicamente publicado e prova
operacional, o guia deve ser corrigido.
