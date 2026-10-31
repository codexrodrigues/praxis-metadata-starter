# Guia 00 — Blueprint Completo de Recurso e Host Praxis (20 Pilares e 3 Fases)

> **Leitura Obrigatória para Humanos e Agentes de IA (Codex, Claude, Antigravity, Copilot)**
> Este guia consolida **todas as 20 capacidades canônicas** do `praxis-metadata-starter`, `praxis-rules-engine` e `praxis-config-starter` que devem ser contempladas ao criar ou migrar um recurso corporativo na plataforma Praxis.
> Uma cópia idêntica deste blueprint também é empacotada dentro do próprio `.jar` publicado no Maven Central em `META-INF/praxis/AI-RESOURCE-BLUEPRINT.md`.

---

## 1. Por que existe este Blueprint? (Diretriz Anti-Minimalismo)

Quando uma LLM gera um recurso Spring Boot sem um guia prescritivo completo, ela tende a produzir apenas um CRUD básico: um `ResponseDTO` com meia dúzia de campos, um `FilterDTO` com 2 ou 3 filtros simples e um controller vazio. Isso **desperdiça 80% do valor da plataforma Praxis**.

Na plataforma Praxis, o backend é a fonte canônica de metadados (`x-ui`, `/schemas/filtered`, `/schemas/surfaces`, `/schemas/actions`, `/schemas/domain`, `/capabilities`, `/option-sources/*`, `/stats/*`, `/export`, `/reactive-determinations/evaluate`). Quando o recurso backend publica os **20 Pilares** abaixo, o runtime Angular (`@praxisui/*`) e os assistentes de IA materializam automaticamente:
- Tabelas corporativas com micro-visualizações in-cell (`@MicroVisualization`), badges, máscaras, Quick Filters, paginação por cursor e exportação rica (`CSV`/`JSON`/`EXCEL`);
- Inspeção **Quick View 360°** ao passar o mouse ou clicar em células de chave estrangeira ou no título do registro (`@ResourceIdentity` + `@ResourceReference` + `@UiSurface`);
- Formulários dinâmicos com **Lookups Ricos (`EntityLookupDescriptor`)**, **Manutenção Parcial por Subbloco (`@ResourceIntent`)** e **Determinações Reativas em Tempo de Formulário (`ReactiveDetermination`)**;
- Transições de ciclo de vida governadas por **`@WorkflowAction`** com ETag/`If-Match` e **Operações em Lote (`Bulking` nos 3 modos)**;
- Gráficos e KPIs prontos para consumo via **`StatsFieldRegistry` + `@UiAnalytics` / `@AnalyticsProjection`**.

---

## 2. Estratégia de Execução em 3 Fases de Profundidade 100%

Para evitar entregas superficiais ("um pouquinho de cada coisa"), todo módulo ou vitrine com múltiplos recursos deve ser construído e validado em **3 Fases Verticais com 100% de profundidade**:

1. **Fase 1 — Dados, Contabilidade 100% Entity-to-DTO, `@UISchema`, `@MicroVisualization`, `@DomainGovernance`, Quick View 360°, Lookups Ricos e Matriz `FilterDTO`** (Pilares 1 a 9).
2. **Fase 2 — Superfícies Semânticas (`@UiSurface`), Manutenção Parcial (`@ResourceIntent`), Ciclo de Vida (`@WorkflowAction`), Operações em Lote (`Bulking`), Fatos (`@ResourceFact`), Analytics (`@UiAnalytics`) e Exportação (`CollectionExport`)** (Pilares 10 a 16).
3. **Fase 3 — Regras de Negócio Transacionais (`409`/`422`), Determinações Reativas (`ReactiveDetermination`), Motor de Regras (`praxis-rules-engine` / `praxis-config-starter`), Cockpit (`/praxis/cockpit`) e Gates Automatizados** (Pilares 17 a 20).

---

## 3. Os 20 Pilares Canônicos (Checklist Completo)

### Fase 1: Dados, DTOs, Semântica, Lookups Ricos, Filtros e Quick View 360°

1. **Hierarquia Resource-Oriented & `@DefaultSortColumn`:**
   - Mutável: `BaseCrudRepository<E, ID>`, `ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>`, `AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>` e `AbstractResourceController<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>`.
   - Somente leitura: `AbstractReadOnlyResourceService<E, ResponseDTO, ID, FilterDTO>` e `AbstractReadOnlyResourceController<ResponseDTO, ID, FilterDTO>`.
   - Entidade com `@OptionLabel` e `@DefaultSortColumn` determinístico (indispensável para `/filter/cursor` e `/locate`). Chaves compostas imutáveis usam `@Embeddable` `record` implementando `CompositeResourceId`.
2. **Contabilidade de 100% das Colunas da Entidade (`Entity-to-DTO Coverage Gate`):**
   - 100% das colunas de negócio da entidade JPA devem ser expostas no `ResponseDTO`.
   - Use `@UISchema(group = "...", tableHidden = true)` para atributos secundários de formulário/ficha, deixando entre **5 e 10 colunas** visíveis por padrão na grade principal (`tableHidden = false`) — inclusive em DTOs read-only de projeção.
   - Em recursos mutáveis, toda coluna de negócio deve ser editável em `CreateDTO`/`UpdateDTO` **ou** declarada `readOnly = true` no `ResponseDTO` e controlada por um `@WorkflowAction` ou `@ResourceIntent` (`PATCH`).
3. **Qualidade Editorial de `@UISchema`, `UISchemaPreset` e `valuePresentation`:**
   - `label` curto (1 a 4 palavras, ≤ 35 caracteres, sem ponto final e **jamais** copiando `@Schema.description`);
   - `helpText` explicando preenchimento/interpretação; `@Schema(description = ...)` documentando semântica real de produto;
   - `FieldDataType`, `FieldControlType` explícitos (incluindo controles especializados como `AVATAR`, `CPF_CNPJ_INPUT`, `PHONE`, `CURRENCY_INPUT`, `DATE_PICKER`, `DATE_RANGE`, `ENTITY_LOOKUP`, `SELECT`, `CHECKBOX`, `PERCENT_SLIDER`, `FILE_UPLOAD`, `CRON_BUILDER`), `icon` Material, `group`, `order`, `UISchemaPreset` e `extraProperties` (`labelTrue`/`labelFalse`, `toneTrue`/`toneFalse`, `presentation.presenter`).
4. **Micro-Visualizações In-Cell (`@MicroVisualization` + `@Threshold`):**
   - Campos de ocupação, saldo, progresso, meta ou comparação no `ResponseDTO` declaram `@MicroVisualization(kind = MicroVisualizationKind.BULLET | RADIAL | HARVEY_BALL | STACKED_BAR | COMPARISON | DELTA | PROCESS_FLOW, valueExpr = "...", targetExpr = "...", totalExpr = "...", thresholds = {@Threshold(...)})`, validados por `PraxisExpressionStaticValidator`.
5. **Governança de Domínio, LGPD & `fieldAccess`:**
   - `@DomainGovernance(domain, subdomain, dataCategory, classification, sensitiveData, maskPattern, exportable, filterable, analyticsEligible, aiPolicy = @AiUsagePolicy(...))` + `@UISchema(visibleForAuthorities = {...}, editableForAuthorities = {...}, fieldAccessReason = "...")`.
6. **Identidade Rica (`@ResourceIdentity`):**
   - No `@ApiResource` do Controller e no `ResponseDTO`: `keyField`, `titleField`, `subtitleField` (e/ou `codeField`/`statusField`) e `metadataFields = {...}`.
7. **Quick View & Navegação 360° (`@ResourceReference`):**
   - Curar de **4 a 8 campos executivos** no `ResponseDTO` com `@UISchema(quickViewVisible = true, quickViewOrder = ...)` (sem duplicar `titleField`/`subtitleField`).
   - Anotar o campo de título do próprio recurso **e** todos os campos de exibição de chaves estrangeiras (`departmentName`, `customerName`, `positionName`, `costCenterName`, `managerName`, etc.) com `@ResourceReference(resourceKey = "...", valueField = "...", surfaceId = "detail", previewSurfaceId = "...", detailPresentation = "drawer")`.
8. **Lookups Ricos (`OptionSourceDescriptor` + `EntityLookupDescriptor`):**
   - Registrar `RESOURCE_ENTITY` e `DISTINCT_DIMENSION` no `OptionSourceRegistry`.
   - Nunca passar `null` nos subcontratos de `EntityLookupDescriptor`: configurar `codePropertyPath`, `descriptionPropertyPaths`, `statusPropertyPath`, `LookupSelectionPolicy` (`allowedStatuses`/`blockedStatuses`, `allowRetainInvalidExistingValue = true`, `disabledReasonTemplate`, `validationMessageTemplate`), `LookupDetailDescriptor` (conectado à surface 360°), `LookupDisplayDescriptor` (`preset = "directory"`, `fields`), `LookupFilteringDescriptor` (`sortOptions`, `defaultSort`, `searchStrategies`) e `dependsOn` + `dependencyFilterMap` para dependências cruzadas entre campos.
9. **Matriz Exaustiva de `FilterDTO`, Busca Ampla (`relations`), `@QuickFilter` & `ResourceFilterAccessScope`:**
   - `FilterDTO implements GenericFilterDTO` cobrindo todas as dimensões operacionais: busca ampla unificada num único campo (`@Filterable(operation = FilterOperation.LIKE, relations = {"fullName", "socialName", "motherName", "fatherName", "dependents.fullName", "taxId"})`), combinação multi-operador (`LIKE`/`STARTS_WITH`/`EQUAL`/`IN`, intervalos `BETWEEN`, janelas relativas `IN_LAST_DAYS`/`IN_NEXT_DAYS`/`ON_DATE`, auditoria de nulidade `IS_NULL`/`IS_NOT_NULL`, cardinalidade de coleções `SIZE_GT`/`SIZE_EQ`, booleanos `IS_TRUE`), navegação relacional cross-entity (`relation = "entidade.subentidade.campo"`) e `@CustomFields`, `@QuickFilter` no `@ApiResource` para recortes frequentes e `ResourceFilterAccessScope` no `Service` quando houver escopo de linha por tenant/empresa.

### Fase 2: Superfícies, Projeções 360°, Intents, Workflow Actions, Bulking, Facts, Analytics e Exportação

10. **Superfícies Semânticas (`@UiSurface`), Master-Detail & Novos Endpoints Cross-Entity:**
    - `@UiSurface(id = "...", kind = SurfaceKind.READ_PROJECTION | SurfaceKind.VIEW, scope = SurfaceScope.ITEM)` para respaldar todos os `previewSurfaceId` e `surfaceId` declarados em `@ResourceReference`, além de coleções filhas via `relatedChildResourceKey`, `relatedChildResourcePath`, `relatedChildParentField`. Quando o relacionamento entre entidades revelar visões consolidadas não-óbvias, criar também novos endpoints read-only dedicados (`AbstractReadOnlyResourceController`).
11. **Manutenção Parcial Governada (`@ResourceIntent`):**
    - Endpoints `PATCH /{id}/{intent}` com `@ResourceIntent` + `@UiSurface(kind = SurfaceKind.PARTIAL_FORM)` e DTO dedicado por subbloco cadastral.
12. **Ciclo de Vida (`@WorkflowAction`), Concorrência (`If-Match` / `Idempotency-Key`) & Duplicação (`duplicate-draft` vs. Deep Clone):**
    - Todas as transições de estado reais modeladas com `@WorkflowAction`, DTO de comando, `ResourceStateSnapshotProvider`, `VersionedCreateUpdateResourceService` (`ETag` + `If-Match`) e `Idempotency-Key` (`GovernedResourceCommandExecutor`).
    - **Regra de Decisão (`duplicate-draft` vs. `@WorkflowAction` Deep Clone):** Para duplicação assistida em que o operador revisa e complementa um novo registro antes de salvar (catálogos, cargos, tabelas paramétricas, pré-preenchimento organizacional), usar `AbstractDuplicateDraftResourceController<R, ID, F, C, U, D>` + `DuplicateDraftResourceService<R, ID, F, C, U, D>` (`DuplicateDraftResourceCommandService<ID, D>`), expondo `POST /{resource}/{id}/duplicate-draft` sem efeitos colaterais de persistência, com `DuplicateDraftDTO` explícito (zerando chaves únicas/pessoais e usando `@Schema(nullable = true)` nos campos zerados cujo preenchimento é obrigatório em `CreateDTO`) e `ResourceOperationAvailabilityEvaluator` (`ResourceStructuralCapabilities.DUPLICATE_DRAFT_OPERATION`). Usar `@WorkflowAction` somente quando o domínio exigir clonagem profunda transacional imediata no servidor (ex.: clonar cabeçalho + coleções filhas `1:N` em uma única transação).
13. **Operações em Lote Governadas (`Bulking` — 3 Modos Além do Óbvio):**
    - Auditar 100% dos atributos mutáveis diferenciando `BulkMode.UNIFORM_UPDATE` (padronização coletiva: departamento, centro de custo, jornada/turno, regime/plano, banco/agência/forma de pagamento, flags operacionais, gestor) e `BulkMode.PER_ITEM_UPDATE` (planilha/grade de revisão individualizada em lote: telefones, e-mails, endereços, contas bancárias, valores/limites individuais, códigos externos, datas específicas por item) com `@Schema(nullable = true)` quando `allowClear = true`;
    - `@BulkResourceOperations(protectedUpdateFields = {...})` no Controller;
    - `@BulkOperation(mode = BulkMode.UNIFORM_UPDATE | BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "...")` para atualização estrutural em lote;
    - `@WorkflowAction(scope = ActionScope.COLLECTION)` + `@BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "...")` para atos coletivos de negócio descobertos a partir do ciclo de vida e dos relacionamentos entre entidades (ex.: realocação coletiva entre unidades, homologação/aprovação em lote, suspensão/reativação coletiva, reenquadramento paramétrico) no protocolo de 2 fases (`evaluation -> proposal -> confirm -> execution/results`).
14. **Fatos Determinísticos (`@ResourceFact`):**
    - Indicadores calculados publicados em `/schemas/domain` com `label` curto (≤ 35 caracteres, sem ponto final).
15. **Analytics (`StatsFieldRegistry` + `@UiAnalytics` / `@AnalyticsProjection`):**
    - `StatsFieldRegistry` e `StatsSupportMode.AUTO` no `Service` (`/stats/group-by`, `/stats/timeseries`, `/stats/distribution`, `/stats/comparison`) + `@UiAnalytics(projections = {@AnalyticsProjection(...)})` no Controller com `@AnalyticsRecordOpen` apontando para a surface 360°.
16. **Exportação Governada (`CollectionExport` + `CollectionExportFieldPresentation`):**
    - Formatos `CSV`, `JSON` e `EXCEL`, escopos `AUTO`, `SELECTED`, `FILTERED`, `CURRENT_PAGE`, `ALL`, respeitando `exportable = false` de `@DomainGovernance` e aplicando `CollectionExportFieldPresentation` (moeda, data, booleanos).

### Fase 3: Regras de Negócio, Determinações Reativas, Motor de Regras e Gates Automatizados

17. **Invariantes Transacionais & Contratos de Erro Estruturados (`409` / `422`):**
    - Validações transacionais no `Service` retornando `409 Conflict` / `422 Unprocessable Entity` com `fieldErrors` estruturados.
18. **Determinações Reativas (`ReactiveDeterminationDefinitionProvider` — Além do Óbvio):**
    - Registrar beans `ReactiveDeterminationDefinitionProvider` vinculando operações de formulário (`ReactiveDeterminationScope(schemaOperationId, ReactiveDeterminationFormMode.CREATE | EDIT)` — exigindo `@Operation(operationId = "create...")` no `@PostMapping` e `@Operation(operationId = "update...")` no `@PutMapping("/{id}")` do Controller) a endpoints `POST` não-persistentes anotados com `@Operation(operationId = "determine...")`.
    - Configurar `ReactiveDeterminationTriggerMode.ON_CHANGE`, `sourcePaths` (JSON Pointers iniciados por `"/"`, todos contidos em `inputs`), `ReactiveDeterminationInputBinding(fieldPath, requestPath)` (podendo incluir `"/id"` em modo `EDIT` sem listá-lo em `sourcePaths`), `ReactiveDeterminationOutputBinding(responsePath, fieldPath)` (todos os campos de saída devem existir em `CreateDTO`/`UpdateDTO` — com `@UISchema(readOnly = true)` quando forem prévias calculadas — sem sobrepor `inputs`) e `ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST | PLATFORM, sourceId, version)`.
19. **Motor de Regras (`praxis-rules-engine`) & Decisões Governadas (`praxis-config-starter`):**
    - `RuleSet` determinístico JSON Logic e, quando `praxis-config-starter` estiver ativo no host, consumo de snapshots aprovados em `/api/praxis/config/domain-rules/**` (`ReactiveDeterminationProvenanceKind.PLATFORM` / decisões governadas).
20. **Observabilidade, `/praxis/cockpit`, Orçamento N+1 & Gates Automatizados:**
    - Liberar `/praxis/cockpit/**` no `SecurityConfig` de dev/showcase, testar contagem de queries SQL em `/filter` frio (`QueryCountHolder`) e executar o **Gate JUnit Nativo** (`SemanticMetadataReviewer` + `PraxisExpressionStaticValidator` + `OptionSourcePublicationInventory`) e o auditor `scripts/workspace/audit-java-resource-completeness.py`.

---

## 4. Protocolo de Descoberta Profunda de Domínio e Síntese Relacional ("Além do Óbvio")

Ao construir ou evoluir um host Praxis, **é proibido atuar como um gerador mecânico `1 tabela = 1 CRUD óbvio`**. O agente de IA deve analisar **o domínio de negócio em si e o grafo de relacionamentos entre as entidades (`@ManyToOne`, `@OneToMany`, `@Embedded`)** para descobrir e implementar capacidades corporativas não-óbvias:

1. **Filtragem Profunda, Multi-Operador, Cross-Entity e Busca Ampla (`Omnibox`):**
   - **Busca Unificada num Único Campo (`@Filterable(relations = {...})`):** incluir pelo menos um campo de busca ampla (`searchTerm` / `buscaAmpla` / `identificacaoOuFamiliar`) com `@Filterable(operation = FilterOperation.LIKE, relations = {"fullName", "socialName", "motherName", "fatherName", "dependents.fullName", "taxId"})` para que o usuário pesquise por nome, sobrenome, filiação/parente ou documento no mesmo campo sem escolher filtros separados;
   - **Multi-Operador sobre o Mesmo Atributo:** combinar `LIKE` + `STARTS_WITH` + `EQUAL` + `IN` em textos/códigos; `BETWEEN` + `ON_DATE` + `IN_LAST_DAYS` (eventos recentes) + `IN_NEXT_DAYS` (radar de vencimentos) em datas; `IS_NULL` / `IS_NOT_NULL` para auditoria de cadastros incompletos; e `SIZE_GT` / `SIZE_EQ` em coleções filhas;
   - **Navegação Cross-Entity:** atravessar 1 a 3 níveis de relacionamento (`relation = "department.costCenter.region"`, `relation = "dependents.relationshipType"`).
2. **Descoberta Exaustiva de Bulking nos 3 Modos:**
   - **`UNIFORM_UPDATE`:** habilitar em todos os atributos organizacionais, classificatórios, contratuais, bancários e operacionais padronizáveis para um lote;
   - **`PER_ITEM_UPDATE`:** habilitar amplamente em atributos de revisão individualizada em planilha/grade (telefones, e-mails, endereços, contas, saldos/valores individuais, códigos externos, datas específicas), excluindo apenas chaves imutáveis e campos exclusivos de workflow;
   - **`DOMAIN_COMMAND`:** analisar o ciclo de vida e os relacionamentos para criar atos coletivos reais (`@WorkflowAction(scope = ActionScope.COLLECTION)` + `@BulkOperation(mode = BulkMode.DOMAIN_COMMAND)`), como realocação coletiva entre unidades, aprovação/homologação em lote, suspensão/reativação coletiva ou revisão paramétrica.
3. **Novos Endpoints Cross-Entity e Subdomínios:**
   - Criar novos endpoints read-only consolidados (`AbstractReadOnlyResourceController`) quando o cruzamento de entidades responder a perguntas operacionais/analíticas (ocupação e vagas, vencimentos, custos por unidade);
   - Expor todas as coleções filhas `1:N` como superfícies 360° (`@UiSurface` + `relatedChildResourceKey`) e decompor entidades extensas em `@ResourceIntent` (`PATCH`) por subbloco operacional.
4. **Descoberta Profunda de Determinações Reativas em Tempo de Formulário (`ReactiveDetermination` Além do Óbvio):**
   - Analisar `CreateDTO` e `UpdateDTO` para transformar formulários passivos em superfícies assistidas em tempo real nas 4 famílias:
     1. *Autopreenchimento e Projeção Cross-Entity (`Lookup & Directory Projection`):* ao informar CEP (`"/postalCode"`) preencher logradouro/bairro/cidade/UF; ao selecionar cargo/departamento/plano (`"/positionId"`, `"/departmentId"`), projetar piso salarial, jornada padrão, vagas disponíveis ou centro de custo padrão;
     2. *Cálculos Determinísticos Multi-Campo (`Financial, Tax & Date Formulas`):* ao alterar valor bruto/descontos (`"/grossAmount"`, `"/baseDeductions"`), calcular total de descontos e valor líquido (`"/totalDeductions"`, `"/netAmount"`); ao informar data inicial e prazo (`"/startDate"`, `"/probationDays"`), derivar data prevista de término (`"/expectedEndDate"`);
     3. *Determinações Encadeadas (`Chained Determinations A -> B -> C`):* quando a Determinação 1 escreve `"/netAmount"`, uma Determinação 2 que escute `"/netAmount"` em `sourcePaths` e `inputs` dispara automaticamente para calcular `"/expectedPaymentDate"`;
     4. *Simulação Preventiva de Elegibilidade e Capacidade (`Draft Policy & Quota Preview`):* avaliar saldo de vagas, compatibilidade de requisitos ou conflitos em tempo de digitação e preencher campos de prévia (`@UISchema(readOnly = true)`) no `CreateDTO`/`UpdateDTO`.
   - **Regras Estritas do `ReactiveDeterminationMetadataCompiler`:**
     - `@PostMapping` (`create`) e `@PutMapping("/{id}")` (`update`) no Controller devem declarar `@Operation(operationId = "create...", ...)` e `@Operation(operationId = "update...", ...)` explícitos;
     - O endpoint da determinação deve ser um `@PostMapping` sem variáveis de caminho, com `@Operation(operationId = "determine...", ...)`, corpo JSON e resposta `200 OK` JSON não-persistente;
     - Todos os caminhos (`sourcePaths`, `fieldPath`, `requestPath`, `responsePath`) devem ser JSON Pointers iniciados por `"/"`; todo `sourcePath` deve constar em `inputs`; todos os campos `input.fieldPath` e `output.fieldPath` devem existir no `CreateDTO`/`UpdateDTO`; e `inputs` e `outputs` nunca podem se sobrepor.

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
