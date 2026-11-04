# Praxis Platform — AI Resource & Host Completeness Blueprint (20 Pillars)

Este documento é embarcado em `META-INF/praxis/AI-RESOURCE-BLUEPRINT.md` dentro do artefato Maven `io.github.codexrodrigues:praxis-metadata-starter` para servir como fonte canônica autocontida a qualquer desenvolvedor ou assistente de IA (Codex, Claude, Antigravity, Copilot) que crie ou evolua um serviço Spring Boot sobre a plataforma Praxis — mesmo fora do monorepo oficial.

---

## 1. Princípio Anti-Minimalismo e Execução em 3 Fases de Profundidade 100%

Um recurso Praxis **não é** um controlador REST tradicional com DTOs esqueléticos. A **Praxis Platform** é uma **plataforma semanticamente orientada para aplicações corporativas AI-native**, em que recursos, regras, ações, dados e políticas são descritos uma vez e podem ser operados por **interfaces tradicionais, interfaces generativas, agentes de IA e integrações** — construindo uma aplicação que, por definição, já seja legível e operável por IA (em vez de construir IA ao lado da aplicação).

O valor da plataforma Praxis reside em publicar um **contrato semântico completo** que habilita automaticamente no `@praxisui/*` (Angular), nas superfícies generativas/compostas, nos agentes de IA e nas integrações:
- Tabelas corporativas com micro-visualizações in-cell, filtros avançados, Quick Filters, paginação por cursor, exportação (`CSV`/`JSON`/`EXCEL`) e analíticos (`/stats/*`);
- Cartões **Quick View 360°** e gavetas (*drawers*) de inspeção master-detail sem código manual de frontend;
- Formulários dinâmicos com **Lookups Ricos (`RESOURCE_ENTITY`)**, **Manutenção Parcial (`@ResourceIntent`)** e **Determinações Reativas (`ReactiveDetermination`)**;
- Ciclo de vida governado por **`@WorkflowAction`** com controle de concorrência otimista (`ETag` / `If-Match`) e **Operações em Lote (`Bulking` nos 3 modos)**;
- Governança LGPD/AI (`@DomainGovernance`, `x-ui.fieldAccess`, `ResourceFilterAccessScope`) e Fatos de Domínio (`@ResourceFact`).

> **Regra de Execução em 3 Fases (Proibido "Um Pouquinho de Cada Coisa"):**
> Quando construir ou migrar um módulo com múltiplos recursos, **jamais execute todas as camadas em um único passo monolítico superficial**. Divida o trabalho em **3 Fases Verticais com 100% de profundidade e validação própria**:
> - **Fase 1:** Dados, Contabilidade 100% Entity-to-DTO, Qualidade Editorial `@UISchema`, `@MicroVisualization`, `@DomainGovernance`/`fieldAccess`, `@ResourceIdentity` Rico, Quick View 360° (`@ResourceReference`), `OptionSourceDescriptor` + `EntityLookupDescriptor` Rico e Matriz `FilterDTO` + `@QuickFilter` + `ResourceFilterAccessScope`.
> - **Fase 2:** Superfícies Semânticas (`@UiSurface` `READ_PROJECTION` / `PARTIAL_FORM` / `VIEW` e coleções filhas), `@ResourceIntent` (`PATCH`), `@WorkflowAction` com `If-Match`/`Idempotency-Key`, **Bulking Governado** (`@BulkEditable`, `@BulkResourceOperations`, `@BulkOperation` para `UNIFORM_UPDATE`, `PER_ITEM_UPDATE` e `DOMAIN_COMMAND`), `@ResourceFact`, Analytics (`StatsFieldRegistry` + `@UiAnalytics`/`@AnalyticsProjection`) e `CollectionExport` (`CSV`/`JSON`/`EXCEL` com `CollectionExportFieldPresentation`).
> - **Fase 3:** Regras de Negócio Transacionais (erros estruturados `409`/`422`), Determinações Reativas (`ReactiveDeterminationDefinitionProvider`), Motor de Regras (`praxis-rules-engine` / decisões governadas `praxis-config-starter`), Cockpit (`/praxis/cockpit`), Orçamento N+1 e Gates Automatizados.

---

## 2. Matriz Canônica dos 20 Pilares de um Recurso Praxis

### FASE 1 — Dados, DTOs, Semântica de Campos, Lookups Ricos, Filtros e Quick View 360°

| # | Pilar | Contrato Canônico Obrigatório |
| :-: | :--- | :--- |
| **1** | **Hierarquia Resource-Oriented & `@DefaultSortColumn`** | Recursos mutáveis estendem `AbstractResourceController<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>` e `AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>` com `ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>` e `BaseCrudRepository<E, ID>`. Recursos somente leitura estendem `AbstractReadOnlyResourceController<ResponseDTO, ID, FilterDTO>` e `AbstractReadOnlyResourceService<E, ResponseDTO, ID, FilterDTO>`. A entidade declara `@OptionLabel` e `@DefaultSortColumn` determinístico, e o `Service` habilita `supportsCursorPagination() = true` para publicar `/filter/cursor` (keyset pagination) e `/locate` nativos sem boilerplate manual, respeitando `ResourceFilterAccessScope`. Chaves compostas imutáveis implementam `CompositeResourceId`. |
| **2** | **Contabilidade 100% Entity-to-DTO (`Entity-to-DTO Coverage Gate`)** | **100% das colunas de negócio da entidade JPA** devem estar presentes no `ResponseDTO`. Colunas secundárias usam `@UISchema(group = "...", tableHidden = true)` para manter a tabela principal escaneável (5 a 10 colunas visíveis por padrão) e o formulário/ficha 100% completo. Em recursos mutáveis, toda coluna de negócio deve ser editável em `CreateDTO`/`UpdateDTO` **ou** `readOnly = true` no `ResponseDTO` e mutada por `@WorkflowAction` ou `@ResourceIntent` (`PATCH`). |
| **3** | **Qualidade Editorial de `@UISchema`, `UISchemaPreset` & `valuePresentation`** | `label` curto (1 a 4 palavras, máx. ~35 caracteres, sem ponto final e **nunca** cópia de `@Schema.description`); `helpText` orientativo; `@Schema(description = ...)` com semântica real de negócio; `FieldDataType` e `FieldControlType` explícitos (usando os 65+ controles especializados como `AVATAR`, `CPF_CNPJ_INPUT`, `PHONE`, `CURRENCY_INPUT`, `DATE_PICKER`, `DATE_RANGE`, `ENTITY_LOOKUP`, `SELECT`, `CHECKBOX`, `PERCENT_SLIDER`, `FILE_UPLOAD`, `CRON_BUILDER`); `icon` Material; `group` e `order`; `UISchemaPreset` e `extraProperties` de apresentação (`labelTrue`/`labelFalse`, `toneTrue`/`toneFalse`, `presentation.presenter`). |
| **4** | **Micro-Visualizações In-Cell (`@MicroVisualization`)** | Campos de progresso, ocupação, meta, variação ou distribuição em `ResponseDTO` declaram `@MicroVisualization(kind = MicroVisualizationKind.BULLET \| RADIAL \| HARVEY_BALL \| STACKED_BAR \| COMPARISON \| DELTA \| PROCESS_FLOW, valueExpr = "...", targetExpr = "...", totalExpr = "...", thresholds = {@Threshold(...)})`, validados estaticamente por `PraxisExpressionStaticValidator`. |
| **5** | **Governança de Domínio, LGPD & `fieldAccess`** | `@DomainGovernance(kind = DomainGovernanceKind.PRIVACY \| SECURITY \| COMPLIANCE, classification = DomainClassification.PUBLIC \| INTERNAL \| CONFIDENTIAL \| RESTRICTED, dataCategory = DomainDataCategory.PERSONAL \| SENSITIVE_PERSONAL \| FINANCIAL \| CREDENTIAL \| OPERATIONAL \| LEGAL, complianceTags = {"LGPD", "PII"}, aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK \| SUMMARIZE_ONLY \| ALLOW \| DENY, trainingUse = AiTrainingUseMode.DENY, ruleAuthoring = AiControlledUseMode.REVIEW_REQUIRED, reasoningUse = AiControlledUseMode.ALLOW), reason = "...")` em todos os campos sensíveis, pessoais, financeiros ou de contexto de segurança, combinado com `@UISchema(visibleForAuthorities = {...}, editableForAuthorities = {...}, fieldAccessReason = "...")` quando houver restrição declarativa por perfil (consumida pelo catálogo semântico `/schemas/domain` e pelo futuro Sidecar de Políticas de Segurança OPA/Rego). |
| **6** | **Identidade Rica (`@ResourceIdentity`)** | Declarada tanto em `@ApiResource(identity = @ResourceIdentity(...))` no Controller quanto no `ResponseDTO`, preenchendo obrigatoriamente `keyField`, `titleField`, `subtitleField` (e/ou `codeField`/`statusField`) e `metadataFields = {...}`. |
| **7** | **Quick View & Navegação 360° (`@ResourceReference`)** | Curar **4 a 8 campos executivos** no `ResponseDTO` com `@UISchema(quickViewVisible = true, quickViewOrder = ...)` (sem repetir `titleField`/`subtitleField`). Anotar o campo `titleField` do próprio recurso **e** todos os campos de exibição de FKs (`departmentName`, `customerName`, `positionName`, `costCenterName`, `managerName`, etc.) com `@ResourceReference(resourceKey = "...", valueField = "...", surfaceId = "detail", previewSurfaceId = "...", detailPresentation = "drawer")`. |
| **8** | **Lookups Ricos (`OptionSourceDescriptor` + `EntityLookupDescriptor`)** | Registrar no `OptionSourceRegistry` fontes `RESOURCE_ENTITY` e `DISTINCT_DIMENSION` completas. Todo `RESOURCE_ENTITY` deve preencher `EntityLookupDescriptor` com: (a) `codePropertyPath`, `descriptionPropertyPaths`, `statusPropertyPath`; (b) `LookupSelectionPolicy` (`selectablePropertyPath`, `allowedStatuses`/`blockedStatuses`, `allowRetainInvalidExistingValue = true`, `disabledReasonTemplate`, `validationMessageTemplate`); (c) `LookupDetailDescriptor` (`surfaceId = "detail"`, `previewSurfaceId = "..."`, `presentation = "drawer"`); (d) `LookupDisplayDescriptor` (`preset = "directory"`, `fields`); (e) `LookupFilteringDescriptor` (`sortOptions`, `defaultSort`, `searchStrategies`); e (f) `dependsOn` + `dependencyFilterMap` para filtros em cascata (ex.: `companyId` / `organizationId`). |
| **9** | **Matriz Exaustiva de `FilterDTO`, Busca Ampla (`relations`), `@QuickFilter` & `ResourceFilterAccessScope`** | `FilterDTO implements GenericFilterDTO` com no mínimo 5 a 15+ campos cobrindo: (a) **Busca Ampla / Omnibox num único campo** via `@Filterable(operation = FilterOperation.LIKE, relations = {"fullName", "socialName", "motherName", "fatherName", "dependents.fullName", "taxId", "department.name", "position.name"})` para pesquisar simultaneamente em múltiplos atributos da entidade e nomes legíveis de seus relacionamentos (`OR`); (b) combinação multi-operador sobre o mesmo atributo (`LIKE`/`STARTS_WITH`/`EQUAL`/`IN`, intervalos `BETWEEN` com `DATE_RANGE`/`PRICE_RANGE`, janelas relativas `IN_LAST_DAYS`/`IN_NEXT_DAYS`/`ON_DATE`, **janelas de ausência/estagnação e auditoria de nulidade** `IS_NULL`/`IS_NOT_NULL`/`IS_FALSE`, cardinalidade de coleções `SIZE_GT`/`SIZE_EQ` e booleanos `IS_TRUE`); (c) **navegação relacional em vocabulário humano** (`relation = "department.name"`, `relation = "department.unitType"`, `relation = "employment.category"`, `relation = "department.costCenter.code"`, `relation = "dependents.relationshipType"` — proibido expor apenas códigos/IDs brutos de FK) e `@CustomFields`; (d) no mínimo 2 a 6 `@QuickFilter` no `@ApiResource`; e (e) `ResourceFilterAccessScope` no `Service` quando houver isolamento multi-empresa/tenant. |

---

### FASE 2 — Superfícies Semânticas, Projeções 360°, Intents, Workflow Actions, Bulking, Facts, Analytics e Exportação

| # | Pilar | Contrato Canônico Obrigatório |
| :-: | :--- | :--- |
| **10** | **Superfícies Semânticas (`@UiSurface`), Master-Detail & Novos Endpoints Cross-Entity (`Radar*`)** | Publicar `@UiSurface(id = "...", kind = SurfaceKind.READ_PROJECTION \| SurfaceKind.VIEW, scope = SurfaceScope.ITEM)` para atender aos `previewSurfaceId` de `@ResourceReference`, além de coleções filhas master-detail (`relatedChildResourceKey`, `relatedChildResourcePath`, `relatedChildParentField`). Quando o relacionamento entre entidades revelar visões consolidadas ou perguntas investigativas multi-domínio não-óbvias (ex.: matriz de ocupação e vagas, radar de vencimentos, auditoria de anomalias remuneratórias vs. movimentação funcional, cobertura de chefias), criar **novos endpoints read-only dedicados** (`AbstractReadOnlyResourceController`) com `@ResourceFact` e `@UiAnalytics` completos. Os DTOs de projeção read-only devem manter de 5 a 10 colunas com `tableHidden = false` para renderização escaneável em tabelas/drawers. |
| **11** | **Manutenção Parcial Governada (`@ResourceIntent`)** | Em recursos extensos ou com subblocos cadastrais (ex.: alocação organizacional, dados bancários/faturamento, endereço/contato, parâmetros contratuais), expor endpoints `PATCH /{id}/{intent}` anotados com `@ResourceIntent(id = ..., title = ..., description = ...)` e `@UiSurface(id = ..., kind = SurfaceKind.PARTIAL_FORM, scope = SurfaceScope.ITEM, intent = ...)`. |
| **12** | **Ciclo de Vida (`@WorkflowAction`), Concorrência (`If-Match` / `Idempotency-Key`) & Duplicação (`duplicate-draft` vs. Deep Clone)** | Modelar todas as transições reais de negócio da entidade como `@WorkflowAction` com DTO de comando próprio, `ResourceStateSnapshotProvider` para disponibilidade contextual em `/capabilities`, controle de versão otimista via `VersionedCreateUpdateResourceService` (`ETag` + `If-Match` retornando `428`/`412`/`409`) e `Idempotency-Key` (`GovernedResourceCommandExecutor`). Para **duplicação assistida (não-persistente)** de cadastros/catálogos/templates, estender `AbstractDuplicateDraftResourceController<R, ID, F, C, U, D>` + `DuplicateDraftResourceService<R, ID, F, C, U, D>` (`DuplicateDraftResourceCommandService<ID, D>`), expondo `POST /{resource}/{id}/duplicate-draft` com `DuplicateDraftDTO` explícito (limpando chaves únicas/pessoais e declarando `@Schema(nullable = true)` nos campos zerados que exigem decisão explícita no `CreateDTO`) e `ResourceOperationAvailabilityEvaluator` (operação `"duplicate-draft"`; suporte estrutural pelo componente `boolean duplicateDraft` de `ResourceStructuralCapabilities`, lido pelo accessor `duplicateDraft()` e combinado com o mapping POST). A política de cópia pertence ao backend e limpa identidade, versão e valores protegidos; o DTO é compatível com create e só o POST de criação após confirmação persiste. Suporte exige opt-in e mapping POST; disponibilidade exige create COLLECTION, view e duplicate-draft ITEM afirmativos, falhando fechado. Clonagem profunda transacional imediata (`1:N`) é uma `@WorkflowAction` distinta. A base mantém `@WorkflowAction(id="duplicate-draft")` para discovery, sem persistência no preparo. Ver o guia `docs/guides/DUPLICATE-DRAFT-ADOPTION.md`. |
| **13** | **Operações em Lote Governadas (`Bulking` — 3 Modos Além do Óbvio)** | Em recursos mutáveis corporativos, auditar **100% dos atributos e transições** para bulking: (1) diferenciar `@BulkEditable` em `BulkMode.UNIFORM_UPDATE` (padronização coletiva: departamento, centro de custo, jornada/turno, regime/plano, banco/agência/forma de pagamento, flags operacionais, gestor) e `BulkMode.PER_ITEM_UPDATE` (planilha/grade de revisão individualizada em lote: telefones, e-mails, endereços, contas bancárias, valores/limites individuais, códigos externos, datas específicas por item); se `allowClear = true`, declarar `@Schema(nullable = true)`; (2) anotar o Controller com `@BulkResourceOperations(protectedUpdateFields = {...})`; (3) expor endpoints de confirmação com `@BulkOperation(mode = BulkMode.UNIFORM_UPDATE \| BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "...")`; e (4) descobrir **atos coletivos de negócio** no ciclo de vida e nos relacionamentos (`@WorkflowAction(scope = ActionScope.COLLECTION)` + `@BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "...")`, ex.: realocação coletiva entre unidades, homologação/aprovação em lote, suspensão/reativação coletiva, reenquadramento paramétrico) seguindo o protocolo de 2 fases (`evaluation -> proposal -> confirm -> execution/results`). |
| **14** | **Fatos Determinísticos de Domínio (`@ResourceFact`)** | Declarar `@ResourceFact` (ou `ResourceFactCatalogProvider`) para publicar indicadores derivados e fatos determinísticos em `/schemas/domain` com `label` curto (≤ 35 caracteres, sem ponto final) e metadados visuais executivos (`icon`, `tone`, `valuePresentation`, `thresholds = {@Threshold(...)}`). |
| **15** | **Analytics (`StatsFieldRegistry` + `@UiAnalytics` / `@AnalyticsProjection`)** | Sobrescrever `getStatsFieldRegistry()` e `StatsSupportMode.AUTO` no `Service` (habilitando `/stats/group-by`, `/stats/timeseries`, `/stats/distribution` e `/stats/comparison`, incluindo métricas `COUNT`, `DISTINCT_COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `MEDIAN`, `PERCENTILE_90`, `PERCENTILE_95` e `AVG_PER_CYCLE_OCCURRENCE` em `numericMeasureField`/`numericHistogramMeasureField`) e anotar o Controller com `@UiAnalytics(projections = {@AnalyticsProjection(..., recordOpen = @AnalyticsRecordOpen(..., target = @AnalyticsSurfaceTarget(...)))})`. |
| **16** | **Exportação Governada (`CollectionExport` + `CollectionExportFieldPresentation`)** | Configurar `CollectionExport` suportando os 3 formatos (`CSV`, `JSON`, `EXCEL`) e os 5 escopos (`AUTO`, `SELECTED`, `FILTERED`, `CURRENT_PAGE`, `ALL`), respeitando as políticas de `@DomainGovernance` / `visibleForAuthorities` e allowlists de exportação do servidor, além de enriquecer colunas monetárias, datas e booleanos com `CollectionExportFieldPresentation`. |

---

### FASE 3 — Regras de Negócio, Determinações Reativas, Motor de Regras e Gates Automatizados

| # | Pilar | Contrato Canônico Obrigatório |
| :-: | :--- | :--- |
| **17** | **Invariantes Transacionais, Ciclo de Vida Completo (`CREATE`/`UPDATE`/`DELETE`) & Erros Estruturados (`409` / `422`)** | Implementar todas as invariantes de domínio no `Service` (create, update, delete, intents, workflow actions e bulk), retornando erros canônicos estruturados (`409 Conflict` / `422 Unprocessable Entity` com `fieldErrors`) para materialização automática nos formulários `@praxisui/dynamic-form`. Garantir ordem transacional estrita: (a) `hydrate*` antes do `RuleEngine` (sem `@NotNull` bloqueante no DTO para atributos hidratados no servidor); (b) `beforeDelete`/`deleteById` com isolamento de escopo (`checkOrg`) e guardas de estado/dependência em todo `Service` mutável com regras de domínio; e (c) `afterCreate`/`afterUpdate`/`afterDelete` para sincronizações pós-persistência (`MAIN_POS`). |
| **18** | **Determinações Reativas (`ReactiveDeterminationDefinitionProvider` — Além do Óbvio)** | Registrar beans `ReactiveDeterminationDefinitionProvider` vinculando operações de formulário (`ReactiveDeterminationScope(schemaOperationId, ReactiveDeterminationFormMode.CREATE \| EDIT)` — exigindo `@Operation(operationId = "create...")` no `@PostMapping` e `@Operation(operationId = "update...")` no `@PutMapping("/{id}")` do Controller) a endpoints `POST` não-persistentes anotados com `@Operation(operationId = "determine...")`. Usar `ReactiveDeterminationTriggerMode.ON_CHANGE`, `ON_INIT` (`sourcePaths` opcionais para defaults iniciais) ou `ON_INIT_AND_CHANGE` (hidratação inicial + reação a mudanças), `sourcePaths` (JSON Pointers iniciados por `"/"`, todos presentes em `inputs`), `ReactiveDeterminationInputBinding(fieldPath, requestPath)` (podendo incluir `"/id"` em modo `EDIT` sem colocá-lo em `sourcePaths`), `ReactiveDeterminationOutputBinding(responsePath, fieldPath)` (todos os campos de saída devem existir em `CreateDTO`/`UpdateDTO` — com `@UISchema(readOnly = true)` quando forem prévias calculadas — e jamais sobrepor `inputs`) e `ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST \| PLATFORM, sourceId, version)`. |
| **19** | **Motor de Regras (`praxis-rules-engine`), Grafo Ontológico de Fatos & Decisões Governadas (`praxis-config-starter`)** | Avaliar regras declarativas determinísticas via `RuleSet` (JSON Logic) do `praxis-rules-engine`. Quando o host integrar `praxis-config-starter`, consumir apenas snapshots aprovados de `/api/praxis/config/domain-rules/**` (`GOVERNED_DECISION`), sem inventar snapshots fictícios quando a fronteira de config não estiver ativa. Manter: (1) rastreabilidade `1:1 KEEP_SEPARATE` direto no código Java (`INSERE_ERRO`/`TRATA_ERRO` -> `*RuleErrorCatalog` -> `DecisionBinding` -> `*Test.java`, sem aglutinar códigos e sem arquivos intermediários de cerimônia); (2) proibição de *Split-Brain* (`PraxisRulesEngine` como autoridade única das regras de `RuleSet`, reservando `Invariants` a guardas estruturais HTTP como `403`, `404`, `If-Match`, locks e recursos sem `RuleSet`); (3) **Grafo Ontológico de Fatos nas 5 raízes canônicas** (`draft`, `current`, `<conceito>`, `neighborhood`, `context`) via `PraxisFactTreeBuilder` (`putDraft`, `putCurrent`, `putConcept`, `putNeighborhood`, `putContext`), proibindo sacos escalares achatados (`facts.*`) e booleanos/contagens SQL de fachada quando a restrição pertencer ao JSON Logic; e (4) derivação automática de `availableRoots` e `requiredFactPaths` da AST via `PraxisFactTreeBuilder.extractReferencedRoots` / `extractReferencedFactPaths`. |
| **20** | **Observabilidade, `/praxis/cockpit`, Orçamento N+1 & Gates Automatizados** | Expor `/praxis/cockpit/**` no `SecurityConfig` (em perfis de dev/inspeção), provar ausência de N+1 com assertions de contagem de queries em `POST /{resource}/filter` frio (orçamento fixo independentemente do tamanho da página) e executar o **Gate JUnit Nativo** abaixo (e `python scripts/workspace/audit-java-resource-completeness.py <host> --strict` quando no monorepo, cobrindo inclusive os Checks 13 a 16). |

---

### 2.1. APIs canônicas das Ondas 1–3 por pilar

Estas APIs fazem parte da hierarquia acima. O host fornece domínio, segurança,
infraestrutura e políticas; os helpers preservam a semântica compartilhada.

| Pilar | API canônica | Adoção e limite de responsabilidade |
| :--- | :--- | :--- |
| **1, 5, 9** | `assertEntityAccessAllowed` | Sobrescrever no service para validar o tenant/escopo do registro. Combinar com `resolveResourceFilterAccessScope()` para restringir coleções antes de paginação, contagem, stats e exportação. O starter aplica o guarda também a locate, itens fixados por `includeIds` e exportação. A carga por ID distingue inexistência (`404`) de acesso negado (`403`); mutações validam acesso antes do lock e novamente após o refresh sob `PESSIMISTIC_WRITE`. |
| **8** | `EntityLookupDescriptor.directoryBuilder` | Construir o lookup rico a partir de `entityKey`, código e descrições reais. Personalizar status, seleção, dependências em cascata e surfaces de detalhe; o builder materializa os subcontratos de seleção, detalhe, exibição e filtro sem passar `null` a cada construtor. O registro da fonte e os endpoints continuam explícitos em `OptionSourceRegistry` e `ApiPaths` do host. |
| **8** | `LookupCapabilities.standardDirectory` | Perfil de diretório com busca, resolução por IDs, detalhe, navegação e multisseleção. Não anuncia criação nem edição; permissões reais continuam no backend. `directoryBuilder` já usa esse perfil como default. |
| **10** | `filterRelatedChildResource` | Implementar o endpoint real da coleção filha com o helper do controller base: autoriza o pai, vincula seu ID ao filtro da filha e preserva paginação/envelope/links. Declarar `relatedChildResourceKey`, `relatedChildResourcePath` e `relatedChildParentField` na surface. O child service mantém seu próprio escopo obrigatório. |
| **11** | `executeVersionedItemPatch` | Executar o `@ResourceIntent` ITEM com `If-Match` e retornar a representação com ETag da revisão persistida. O endpoint PATCH e a surface `PARTIAL_FORM` continuam explícitos; o callback usa o service transacional e o domínio autorizado. |
| **12** | `executeVersionedItemCommand` | Executar a `@WorkflowAction` ITEM pelo executor governado, com precondição de versão, availability, resposta e ETag canônicos. Fornecer `Idempotency-Key`, payload e store quando o comando exigir reexecução idempotente. Não duplicar a montagem HTTP no host. |
| **12** | `JdbcResourceCommandReceiptStore` | Store opt-in PostgreSQL de recibos de comandos unitários, com o mesmo datasource/transaction manager da mutação. Instalar explicitamente `db/praxis-command-receipts.sql`; o starter não executa DDL nem migrações de recibos automaticamente. O recibo e o efeito de domínio compartilham a transação. |
| **13** | `AbstractBulkGovernedResourceController` | Publicar as nove rotas canônicas de avaliação, confirmação, propostas, execuções, resultados e cancelamento para `SYNC`/`PER_ITEM`. Declarar `@BulkResourceOperations`, `UPDATE_SOURCE` e campos protegidos; não copiar o protocolo no host. Com infraestrutura explícita, `BulkOperationStatusLookup` usa `BulkOperationIdentityDiscovery` para resolver os IDs retidos no namespace/recurso correto; a leitura continua sujeita à autorização atual. |
| **13** | `JpaResourceBulkEngine` | Compor com `bulkDomainAdapter`, codecs, campos editáveis, infraestrutura e política do host. Avaliar candidatos destacados em `REPEATABLE_READ`, descartar alterações acidentais da avaliação e confirmar por item com lock, versão e recibo durável. Falhas funcionais surgidas na gravação revertem todo o item antes de registrar `CONFLICT`/`INVALID`/`DENIED`, permitindo os itens seguintes. Falhas técnicas e resultado de commit incerto mantêm o tratamento conservador do kernel. Migração, publicação READY, ASYNC/ATOMIC e workers são composições explícitas. |
| **18** | `ReactiveDeterminationDefinition.forCrudResource` | Definir uma determinação com os `operationId` reais de CREATE, UPDATE e cálculo; o builder publica scopes `CREATE` e `EDIT`. Declarar sources/inputs/outputs por JSON Pointer e provenance. Os campos devem existir nos DTOs; inputs/outputs não se sobrepõem. A prévia não persiste nem substitui regras transacionais. |
| **19** | `GovernedRuleEngineTemplate` | Compilar/cachear o plano por escopo autenticado e executar o engine determinístico com fatos capturados, instante e timezone explícitos. `evaluate` preserva os cinco estados; `enforce` autoriza somente `ALLOW`. O host continua dono da seleção do snapshot governado, hidratação, transação e efeitos. |
| **19** | `RuleErrorCatalog` | Registrar cada erro atômico com código, campo público, mensagem de domínio, lineage e categoria seguros. `PraxisRuleViolationException` conserva apenas as causas da decisão consolidada e usa a primeira mensagem resolvida. `category="CONFLICT"` em todas as violações sugere HTTP `409`; categorias mistas, ausentes ou outras sugerem `422`, com default `BUSINESS_LOGIC`. As propriedades de diagnóstico incluem a categoria por violação, sem fatos nem causas privadas. |
| **19** | `PraxisFactTreeBuilder` | Capturar o **Grafo Ontológico de Fatos** destacado nas 5 raízes canônicas (`putDraft`, `putCurrent`, `putConcept(conceptKey, ...)`, `putNeighborhood`, `putContext`), sem comparações de regra no provider. `put` valida apenas o novo valor; `build` valida o limite agregado da árvore. Para objetos persistentes já carregados, preferir `putScalarBean`/`toScalarObjectNode`: escalares, enum, datas, UUID, coleções escalares e `@Embedded` simples, ignorando propriedades de associações JPA antes de invocar seus getters. Usar `extractReferencedRoots(expression)` e `extractReferencedFactPaths(expression)` para derivar `availableRoots` e `requiredFactPaths` diretamente da AST JSON Logic (ignorando variáveis locais de `map`/`filter`/`all`/`some`/`none`/`reduce`). |

Exemplo de diretório rico, sem anúncios de escrita implícitos:

```java
var directory = EntityLookupDescriptor.directoryBuilder("departments", "code", "name")
        .status("status")
        .dependencies(Map.of("companyId", "companyId"))
        .detailSurface("detail", "preview")
        .build();
```

O host define `LookupSelectionPolicy` para os estados realmente selecionáveis,
mantendo `allowRetainInvalidExistingValue` quando necessário. `sortOptions` e
`defaultSort` devem usar a mesma dimensão publicada pelo lookup, sem misturar
nomes de propriedades da entidade com aliases da apresentação.

Em custom fields, CREATE rejeita qualquer atribuição read-only. PUT e bulk
aceitam o snapshot read-only idêntico ao persistido, mas rejeitam alteração ou
limpeza explícita; valores omitidos permanecem protegidos pelo lifecycle.
O draft de UPDATE aplica o DTO a uma cópia destacada do registro autorizado e
projeta a resposta, combinando novos valores com estado hidratado. Regras locais
de campos opcionais vazios não são aplicáveis; regras cross-field ainda avaliam
com `value=null` para preservar obrigatoriedade condicional. Incompatibilidade
de tipo nessa avaliação vazia não vira HTTP `500`. Uma expressão que depende
de `entity` exige estado confiável no update e não pode ser validada standalone;
`{"var":""}` dentro do predicado de um iterador representa seu item local.

Cursores de recursos vinculam a tupla à ordenação efetiva (propriedade, direção,
tratamento de null e case sensitivity). Trocar a ordenação exige novo cursor;
um cursor incompatível é payload inválido, sem reinterpretar sua posição.

## 3. Gate JUnit Nativo de Completude (Para Todo Projeto Host — Interno ou Externo)

Todo projeto Java que utiliza `praxis-metadata-starter` deve incluir um teste JUnit executando os **3 validadores nativos embarcados no próprio JAR do starter**:
1. `org.praxisplatform.uischema.authoring.SemanticMetadataReviewer`
2. `org.praxisplatform.uischema.validation.PraxisExpressionStaticValidator`
3. `org.praxisplatform.uischema.options.diagnostics.OptionSourcePublicationInventory` + `OptionSourcePublicationDiagnostics`

```java
package com.example.host.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.authoring.SemanticMetadataIssueSeverity;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewReport;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewer;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.diagnostics.OptionSourcePublicationCandidate;
import org.praxisplatform.uischema.options.diagnostics.OptionSourcePublicationDiagnostics;
import org.praxisplatform.uischema.options.diagnostics.OptionSourcePublicationInventory;
import org.praxisplatform.uischema.validation.PraxisExpressionStaticValidator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PraxisMetadataCompletenessTest {

    private final SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();
    private final PraxisExpressionStaticValidator expressionValidator = new PraxisExpressionStaticValidator();

    @Test
    void responseAndFilterDtosPassSemanticMetadataReview() {
        List<Class<?>> dtoClasses = List.of(
                // Liste aqui todos os ResponseDTO, CreateDTO, UpdateDTO, FilterDTO, DuplicateDraftDTO e CommandDTO do host
        );
        for (Class<?> dtoClass : dtoClasses) {
            SemanticMetadataReviewReport report = reviewer.review(dtoClass);
            assertThat(report.issues())
                    .as("Semantic metadata errors in %s", dtoClass.getSimpleName())
                    .noneMatch(issue -> issue.severity() == SemanticMetadataIssueSeverity.ERROR);
        }
    }

    @Test
    void responseDtosPassStaticExpressionValidation() {
        List<Class<?>> responseDtoClasses = List.of(
                // Liste aqui todos os ResponseDTO do host (que declaram @MicroVisualization / formulas)
        );
        assertThat(expressionValidator.validateDtoClasses(responseDtoClasses, false))
                .as("Static expression issues in ResponseDTOs")
                .isEmpty();
    }

    @Test
    void registeredOptionSourcesArePublishedWithoutMismatch() {
        OptionSourceRegistry optionSourceRegistry = OptionSourceRegistry.empty(); // substitua pelo bean OptionSourceRegistry do host
        OptionSourcePublicationInventory inventory = OptionSourcePublicationInventory.of(
                // Ex.: new OptionSourcePublicationCandidate("department", ApiPaths.HumanResources.DEPARTMENTS, "hr")
        );
        OptionSourcePublicationDiagnostics diagnostics =
                new OptionSourcePublicationDiagnostics(optionSourceRegistry, List.of(inventory));
        assertThat(diagnostics.unpublishedOrMismatched()).isEmpty();
    }
}
```

---

## 4. Protocolo de Descoberta Profunda de Domínio e Síntese Relacional ("Além do Óbvio")

Ao construir ou evoluir um host Praxis, **é proibido atuar como um gerador mecânico `1 tabela = 1 CRUD óbvio`**. O agente de IA deve analisar **o domínio de negócio em si e o grafo de relacionamentos entre as entidades (`@ManyToOne`, `@OneToMany`, `@Embedded`)** para descobrir e implementar capacidades corporativas não-óbvias nas 4 dimensões abaixo:

### 4.1. Filtragem Profunda, Multi-Operador, Cross-Entity e Busca Ampla (`Omnibox`)
1. **Busca Unificada num Único Campo (`@Filterable(relations = {...})`):**
   - Em sistemas corporativos reais, o usuário não quer selecionar 6 campos diferentes para descobrir se um nome pesquisado é o nome civil, nome social, sobrenome, nome da mãe/pai, nome de um dependente/familiar, razão social, unidade/setor, cargo ou documento.
   - Todo `FilterDTO` cadastral/operacional deve incluir pelo menos um campo de **busca ampla semântica** (`searchTerm` / `buscaAmpla` / `identificacaoOuFamiliar`) usando `@Filterable(operation = FilterOperation.LIKE, relations = {"fullName", "socialName", "motherName", "fatherName", "dependents.fullName", "taxId", "department.name", "position.name"})`, avaliando todos os caminhos em disjunção (`OR`) com `DISTINCT` automático.
2. **Combinação Multi-Operador sobre o Mesmo Atributo:**
   - **Textos e Códigos:** `LIKE` (contém), `STARTS_WITH` (prefixo hierárquico/código), `EQUAL` (exato) e `IN` (lista de códigos em lote);
   - **Datas e Vigências:** combinar `BETWEEN` (`DATE_RANGE`), `ON_DATE`, `GREATER_OR_EQUAL`/`LESS_OR_EQUAL`, **`IN_LAST_DAYS`** (eventos recentes: admitidos/criados nos últimos N dias) e **`IN_NEXT_DAYS`** (radar preventivo: contratos/avaliações/férias vencendo nos próximos N dias);
   - **Valores e Quantidades:** `BETWEEN` (`PRICE_RANGE`), `GREATER_OR_EQUAL` (piso) e `LESS_OR_EQUAL` (teto);
   - **Auditoria de Completude Cadastral e Predicados de Ausência/Estagnação (`IS_NULL` / `IS_NOT_NULL` / `IS_FALSE` / Anti-Event Windows):** criar filtros explícitos para localizar cadastros com pendências, omissões ou ausência de eventos numa janela temporal (ex.: *"sem conta bancária"*, *"sem gestor/substituto alocado"*, *"sem documento comprobatório"*, *"sem movimentação funcional / sem homologação de estágio"*, *"sem parcela programada"*);
   - **Cardinalidade de Coleções Filhas (`SIZE_GT`, `SIZE_EQ`, `SIZE_LT`):** filtrar a entidade pai pelo volume de itens relacionados (ex.: *"com mais de N dependentes/alocações"*).
3. **Navegação Relacional em Múltiplos Níveis e Vocabulário Humano (`relation = "a.b.c"`):**
   - **Proibido expor apenas IDs ou códigos técnicos de FK (`setor`, `empCodigo`, `cargo`) no `FilterDTO`:** todo relacionamento organizacional ou classificatório deve publicar filtros navegáveis pelo vocabulário humano que o usuário e a LLM utilizam (ex.: nome da unidade `relation = "lotacao.nome"`, tipo da unidade `relation = "lotacao.tipoSetor"`, categoria funcional `relation = "vinculo.categoria"`, nome do cargo `relation = "cargoEntidade.nome"`), além de entidades avô e coleções filhas (`relation = "department.costCenter.region"`, `relation = "dependents.relationshipType"`, `relation = "assignments.status"`).

### 4.2. Descoberta Exaustiva de Operações em Lote (Bulking Além do Óbvio — 3 Modos)
O agente nunca deve escolher apenas 2 ou 3 campos óbvios para `@BulkEditable`. Ele deve auditar **100% dos atributos mutáveis e transições do domínio**:
1. **`BulkMode.UNIFORM_UPDATE` (Padronização Coletiva por Seleção ou `QUERY`):**
   - Habilitar em **todos** os campos organizacionais, classificatórios, contratuais, financeiros e operacionais que podem ser padronizados para um grupo de registros (ex.: departamento/unidade, local de atuação, centro de custo, jornada/turno, regime/plano, banco/agência/forma de pagamento, gestor, categoria, flags de elegibilidade e datas de referência coletiva).
2. **`BulkMode.PER_ITEM_UPDATE` (Grade/Planilha de Revisão Individualizada em Lote):**
   - Muitos atributos que **não** fazem sentido receber o mesmo valor uniforme para 50 registros (ex.: telefone, e-mail, endereço/CEP, conta bancária individual, salário/limite individual, código externo, observação individual, datas específicas por pessoa) **são candidatos ideais para `PER_ITEM_UPDATE`**, onde o operador abre os 50 registros numa grade em lote e edita os valores individuais de cada linha numa única proposta governada (`evaluation -> proposal -> confirm`). Exclua apenas chaves primárias/identidade imutável (`id`, `version`, documentos civis imutáveis) e campos exclusivos de máquina de estado.
3. **`BulkMode.DOMAIN_COMMAND` (`@WorkflowAction(scope = ActionScope.COLLECTION)` + `@BulkOperation(mode = BulkMode.DOMAIN_COMMAND)`):**
   - Analisar o negócio e os relacionamentos entre entidades para descobrir **atos coletivos reais** que exigem parâmetros próprios (ato/justificativa, data de vigência, unidade de destino, regra de transição):
     - *Reestruturação/Movimentação Coletiva:* realocar equipe entre departamentos/unidades, transferir carteira entre centros de custo ou migrar vínculos de um cargo/plano para outro;
     - *Ciclo de Vida e Homologação Coletiva:* aprovar/homologar período de experiência ou avaliação em lote, suspender/reativar/encerrar registros em lote com justificativa;
     - *Revisão Paramétrica Coletiva:* aplicar nova jornada, tabela ou política de benefícios a um recorte filtrado.

#### Composição declarativa HTTP/JPA

Para `SYNC`/`PER_ITEM`, herdar `AbstractBulkGovernedResourceController<R, ID, F, C, U>`:
a base publica as nove rotas de avaliação, confirmação, propostas, execuções,
resultados e cancelamento no envelope `RestApiResponse.data`/`_links`. Declarar
`@BulkResourceOperations` e o PUT unitário `UPDATE_SOURCE` com `operationId`
explícito, preservando campos protegidos. Compor `JpaResourceBulkEngine` com
`AbstractBaseResourceService.bulkDomainAdapter`, infraestrutura e política de
autorização do host. A avaliação usa candidato destacado em `REPEATABLE_READ`;
a confirmação usa o update versionado sob lock e o kernel de recibos duráveis.

O starter auto-configura `PraxisBulkProtocolHttpMessageConverter`, resolve o codec
pelo ID real e normaliza os filtros aninhados. `BulkOperationIdentityDiscovery`
elimina listas manuais de operações na instalação. O documento OpenAPI sintetiza
os três schemas tipados de avaliação; não criar envelopes de schema por host.
Para `DOMAIN_COMMAND`, declarar a action de coleção e seus endpoints reais,
DTO de parâmetros e `BulkDomainCommandAdapter`, usando os helpers da base.
Migração, publicação READY e workers continuam explícitos. ASYNC e ATOMIC usam
as composições específicas existentes do SDK. Guia de adoção no repositório:
`docs/guides/BULK-DECLARATIVE-RESOURCE-ENGINE.md`.

### 4.3. Criação de Novos Endpoints, Projeções Analíticas Cross-Entity (`Radar*`), Knowledge Graph e Data Products
Ao analisar os relacionamentos entre entidades (`Entidade A <-> Entidade B <-> Entidade C`), o agente deve ir além dos endpoints CRUD óbvios:
1. **Novos Endpoints Read-Only de Visão Consolidada e Investigação Multi-Domínio (`AbstractReadOnlyResourceController` — Analytical Data Products):** criar recursos analíticos/operacionais dedicados (`Radar*`) que cruzem entidades (via `@Subselect` ou `VIEW`) para responder perguntas executivas e investigativas de alta complexidade sem exigir joins ad-hoc no cliente (ex.: matriz de ocupação e vagas por cargo/unidade, radar de prescrição de férias e licença especial, radar de cobertura de chefias e substituições, radar de variação remuneratória vs. movimentação funcional). Todo endpoint `Radar*` **deve** publicar `@ResourceFact`s, `StatsFieldRegistry` (`StatsSupportMode.AUTO`) e `@UiAnalytics` (`COMPOSITION`, `RANKING` com `recordOpen`, `TIMESERIES`, `DISTRIBUTION`, `COMPARISON`).
2. **Superfícies Master-Detail 360° (`@UiSurface` + `relatedChildResourceKey`):** expor todas as coleções filhas `1:N` relevantes como superfícies navegáveis no Quick View 360° e nas fichas de detalhe.
3. **Intents Especializadas por Persona (`@ResourceIntent` + `SurfaceKind.PARTIAL_FORM`):** decompor entidades extensas em múltiplos endpoints `PATCH` orientados aos processos reais de cada área operacional (alocação, pagamento/bancário, endereço/contato, parâmetros contratuais).
4. **Síntese Automática de Knowledge Graph e Data Products pela Plataforma (`SemanticDomainCatalogService` + `DomainCatalogPromptContextService`):** o `praxis-metadata-starter` sintetiza automaticamente em `/schemas/domain` (sem configuração adicional no host):
   - arestas `references` entre conceitos e campos a partir de `@ResourceReference` (`x-ui.resourceReference`) e de `@UiSurface` (`relatedChildResourceKey`);
   - nós `stats` (`statsKind = "analytics_projection"`) e arestas `has_stats` / `references` a partir de `@UiAnalytics` (`x-ui.analytics.projections`);
   - classificação automática de recursos read-only analíticos com referências cross-resource como `semanticRole = "analytical_data_product"` (`dataProduct = true`, `analyzesConcepts = [...]`, arestas `materializes`), enriquecendo também os conceitos operacionais analisados com `analyzedByDataProducts = [...]` e projetando essa topologia no `DOMAIN_RESOURCE_IDENTITY_CATALOG` do assistente de IA.

### 4.4. Descoberta Profunda de Determinações Reativas em Tempo de Formulário (`ReactiveDetermination` Além do Óbvio)
Formulários corporativos não devem ser fichas passivas que só validam ou calculam dados depois que o usuário clica em "Salvar". Ao analisar os atributos de `CreateDTO` e `UpdateDTO` e os relacionamentos da entidade, o agente deve descobrir e implementar **Determinações Reativas (`ReactiveDeterminationDefinitionProvider`)** nas 4 famílias abaixo:
1. **Autopreenchimento e Projeção Cross-Entity ao Selecionar FK ou Código (`Lookup & Directory Projection`):**
   - Ao digitar um CEP (`"/postalCode"`), preencher automaticamente logradouro, bairro, cidade e UF (`"/street"`, `"/neighborhood"`, `"/city"`, `"/state"`);
   - Ao selecionar um cargo/plano (`"/positionId"`), departamento (`"/departmentId"`) ou contrato, projetar imediatamente no formulário o piso salarial, jornada padrão, vagas disponíveis, centro de custo padrão ou gestor responsável.
2. **Cálculos Determinísticos Multi-Campo (`Financial, Tax & Date Formulas`):**
   - Ao alterar salário bruto, descontos ou alíquotas (`"/grossAmount"`, `"/baseDeductions"`), calcular e exibir em tempo real o total de descontos e o valor líquido (`"/totalDeductions"`, `"/netAmount"`);
   - Ao informar data de início e prazo/regime (`"/startDate"`, `"/probationDays"`), calcular automaticamente a data prevista de término ou revisão (`"/expectedEndDate"`).
3. **Determinações Reativas Encadeadas (`Chained Determinations A -> B -> C`):**
   - O runtime Angular (`ReactiveDeterminationRuntimeService`) suporta encadeamento automático: quando a Determinação 1 atualiza um campo de saída (ex.: `"/netAmount"`), uma Determinação 2 que declare `"/netAmount"` em seus `sourcePaths` e `inputs` é disparada em sequência para derivar o próximo atributo dependente (ex.: `"/expectedPaymentDate"` ou faixa de aprovação exigida).
4. **Simulação Preventiva de Elegibilidade e Capacidade (`Draft Policy & Quota Preview`):**
   - Ao selecionar colaborador + cargo + unidade em um formulário de alocação/movimentação, avaliar preventivamente se há saldo de vagas, compatibilidade de escolaridade/requisitos ou conflito de acúmulo e preencher campos de prévia (`"/availableVacanciesPreview"`, `"/eligibilityStatusPreview"`, `"/policyDiagnosticMessage"`) no formulário antes do submit.

#### Regras Estritas do Compilador (`ReactiveDeterminationMetadataCompiler` — Fail-Closed)
Para que o starter publique `x-ui.reactiveDeterminations` em `/schemas/filtered` sem falhar:
- Sobrescreva `create` (`@PostMapping`) e `update` (`@PutMapping("/{id}")`) no Controller com `@Operation(operationId = "create...", ...)` e `@Operation(operationId = "update...", ...)` estáveis e únicos;
- O endpoint de cálculo deve ser um `@PostMapping` **sem variáveis de rota (`{id}`)**, anotado com `@Operation(operationId = "determine...", ...)`, recebendo `@RequestBody` JSON e retornando `200 OK` JSON sem persistir estado;
- Todos os `sourcePaths`, `input.fieldPath`, `input.requestPath`, `output.responsePath` e `output.fieldPath` devem ser **JSON Pointers válidos começando com `"/"`** (ex.: `"/positionId"`, `"/netAmount"`);
- Todo item de `sourcePaths` **deve estar presente** em `inputs` (`input.fieldPath`); em `ReactiveDeterminationFormMode.EDIT`, você pode enviar `new ReactiveDeterminationInputBinding("/id", "/recordId")` em `inputs` sem incluí-lo em `sourcePaths`;
- Todo `input.fieldPath` e `output.fieldPath` **deve existir como propriedade no `CreateDTO` / `UpdateDTO`** da operação (campos de prévia/cálculo preenchidos pela determinação devem constar no `CreateDTO`/`UpdateDTO` com `@UISchema(readOnly = true)`), e `inputs` e `outputs` **nunca podem se sobrepor**.

### 4.5. Arquitetura Ontológica de Fatos e Regras Declarativas (Padrão SAP BDC Ontology & SHACL-Style JSON Logic)
Inspirada na arquitetura de **Ontology & Knowledge Graph + SHACL Shapes** do SAP Business Data Cloud (BDC), a Fase 3 da plataforma Praxis separa estritamente a **Topologia Semântica do Domínio (Grafo Ontológico de Fatos)** das **Restrições Declarativas de Negócio (`RuleSet` JSON Logic)**:

1. **As 5 Raízes Ontológicas Canônicas (`PraxisFactTreeBuilder`):**
   É proibido empacotar variáveis de múltiplas entidades num saco escalar achatado (`facts.*`). Todo provedor de fatos (`*Facts.java`) deve construir a árvore JSON via `PraxisFactTreeBuilder` organizando os nós nas **5 raízes ontológicas canônicas**:
   - **`draft` (`PraxisFactTreeBuilder.ROOT_DRAFT` / `putDraft`)**: o estado candidato da mutação (`NEW` em triggers/packages legados), capturado **após** a hidratação server-side (`hydrate*`);
   - **`current` (`PraxisFactTreeBuilder.ROOT_CURRENT` / `putCurrent`)**: o estado persistido anterior em operações de `UPDATE`/`DELETE` (`OLD` no legado, ou `null` em `CREATE`), permitindo expressar guardas de imutabilidade (`draft.campo == current.campo`) e transições de estado diretamente na árvore JSON Logic;
   - **`<conceito>` (`putConcept("vaga", ...)` / `putConcept("funcao", ...)` / `putConcept("designacao", ...)` / `putConcept("substituto", ...)` / `putConcept("impedimento", ...)`)**: nós de entidades de domínio relacionadas em **1-hop** via chave estrangeira;
   - **`neighborhood` (`PraxisFactTreeBuilder.ROOT_NEIGHBORHOOD` / `putNeighborhood`)**: fatos agregados ou coleções de **N-hop** sobre registros irmãos no mesmo escopo/tenant (ex.: contagem de sobreposições temporais `hasDateRangeOverlap`, quantidade de titulares ativos, ocupação acumulada da vaga);
   - **`context` (`PraxisFactTreeBuilder.ROOT_CONTEXT` / `putContext`)**: metadados de execução, tenant (`empCodigo`), operação e janela temporal congelada.

2. **Fronteira Estrita entre Travessia de Aresta (SQL 1-Hop) e Restrição de Negócio (JSON Logic / SHACL Shape):**
   - **Proibido esconder regras de negócio no `WHERE` de consultas 1-hop:** consultas de conceitos `1-hop` (`<conceito>`) devem usar no SQL **apenas predicados de travessia de aresta/identidade** (`emp_codigo`, `id`, `numfunc`, `numvinc`). Jamais filtre status (`situacao IN ('DESIGNADO', ...)`), cobertura de vigência (`dtini <= :ini AND dtfim >= :fim`) ou flags de negócio dentro do SQL para retornar um `COUNT(*)` ou `boolean` de fachada ao motor de regras;
   - **Projeção Primitiva + Avaliação JSON Logic:** projete os atributos primitivos reais do nó relacionado (ex.: `impedimento.tipo`, `impedimento.situacao`, `impedimento.inicio`, `impedimento.fim`, `impedimento.interrupcao`) e escreva a restrição completa na árvore JSON Logic do `*RuleSet.java`. Assim, a regra permanece 100% transparente no `/api/praxis/config/domain-rules/**` e no `Policy Studio`, e customizações governadas (`REPLACE_EXACT` / `PARAMETRIC_TEMPLATE`) podem evoluir os critérios sem alterar SQL;
   - **Consultas `neighborhood` (N-Hop):** agregações SQL (`COUNT`) são legítimas **exclusivamente** na raiz `neighborhood.*` para quantificar colisões entre registros irmãos na mesma coleção (ex.: `neighborhood.sobreposicoesTitular`, `neighborhood.designacoesAtivasNaVaga`), onde o próprio fato ontológico é a cardinalidade da vizinhança.

3. **Introspecção Automática de AST (`extractReferencedRoots` / `extractReferencedFactPaths`) e DSL Declarativa Enxuta (`*RuleSetSupport`):**
   - Nunca declare `List.of("facts")` manualmente em `RuleSetDefinition.availableRoots` ou em `DecisionBinding.requiredFactPaths`. Use `PraxisFactTreeBuilder.extractReferencedRoots(expression)` e `PraxisFactTreeBuilder.extractReferencedFactPaths(expression)` (inclusive ao construir `RuleParameterTemplate`), que inspecionam a AST JSON Logic respeitando o escopo local de iteradores (`map`, `filter`, `all`, `some`, `none`, `reduce`);
   - Mantenha um helper declarativo compacto por módulo (`*RuleSetSupport`, ~100–130 LOC) que conecte automaticamente o código do `*RuleErrorCatalog` ao `DecisionSlot` e ao `DecisionBinding`, permitindo que cada `*RuleSet.java` tenha entre **50 e 140 linhas** e funcione como uma especificação ontológica limpa e auditável.



## Caminhos reutilizados e fontes de opções

Antes de criar DTOs e FilterDTOs, defina `final ApiPaths` no host (construtor
privado, constantes `public static final String` calculáveis em compilação).
Componha base, caminho do recurso, chave da fonte e endpoint nomeado de opções
uma única vez. Controllers, DTOs de todas as operações, registro de fontes,
superfícies e links devem reutilizar essas constantes. Não concatene caminhos
completos em cada DTO, não use interfaces de constantes ou chamadas de método
para valores de annotations, e não inclua origem/porta do ambiente.
`OptionSourceRegistry`/`OptionSourceDescriptor` continuam definindo a semântica;
o runtime consome `x-ui.optionSource.filterEndpoint` e `byIdsEndpoint` publicados
pelo starter. Selects com enum/opções estáticas não precisam de endpoint.
Valide os endpoints das annotations contra o runtime do descritor e o mapping
do controller, preservando fixtures literais independentes e recompilando o host.
A classe de caminhos é do host; bibliotecas universais não recebem rotas de produtos.
