# Praxis Platform — AI Resource & Host Completeness Blueprint (20 Pillars)

Este documento é embarcado em `META-INF/praxis/AI-RESOURCE-BLUEPRINT.md` dentro do artefato Maven `io.github.codexrodrigues:praxis-metadata-starter` para servir como fonte canônica autocontida a qualquer desenvolvedor ou assistente de IA (Codex, Claude, Antigravity, Copilot) que crie ou evolua um serviço Spring Boot sobre a plataforma Praxis — mesmo fora do monorepo oficial.

---

## 1. Princípio Anti-Minimalismo e Execução em 3 Fases de Profundidade 100%

Um recurso Praxis **não é** um controlador REST tradicional com DTOs esqueléticos. O valor da plataforma Praxis reside em publicar um **contrato semântico completo** que habilita automaticamente no `@praxisui/*` (Angular) e nas superfícies de IA:
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
| **1** | **Hierarquia Resource-Oriented & `@DefaultSortColumn`** | Recursos mutáveis estendem `AbstractResourceController<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>` e `AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>` com `ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>` e `BaseCrudRepository<E, ID>`. Recursos somente leitura estendem `AbstractReadOnlyResourceController<ResponseDTO, ID, FilterDTO>` e `AbstractReadOnlyResourceService<E, ResponseDTO, ID, FilterDTO>`. A entidade declara `@OptionLabel` e `@DefaultSortColumn` determinístico (necessário para `/filter/cursor` e `/locate`). Chaves compostas imutáveis implementam `CompositeResourceId`. |
| **2** | **Contabilidade 100% Entity-to-DTO (`Entity-to-DTO Coverage Gate`)** | **100% das colunas de negócio da entidade JPA** devem estar presentes no `ResponseDTO`. Colunas secundárias usam `@UISchema(group = "...", tableHidden = true)` para manter a tabela principal escaneável (5 a 10 colunas visíveis por padrão) e o formulário/ficha 100% completo. Em recursos mutáveis, toda coluna de negócio deve ser editável em `CreateDTO`/`UpdateDTO` **ou** `readOnly = true` no `ResponseDTO` e mutada por `@WorkflowAction` ou `@ResourceIntent` (`PATCH`). |
| **3** | **Qualidade Editorial de `@UISchema`, `UISchemaPreset` & `valuePresentation`** | `label` curto (1 a 4 palavras, máx. ~35 caracteres, sem ponto final e **nunca** cópia de `@Schema.description`); `helpText` orientativo; `@Schema(description = ...)` com semântica real de negócio; `FieldDataType` e `FieldControlType` explícitos (usando os 65+ controles especializados como `AVATAR`, `CPF_CNPJ_INPUT`, `PHONE`, `CURRENCY_INPUT`, `DATE_PICKER`, `DATE_RANGE`, `ENTITY_LOOKUP`, `SELECT`, `CHECKBOX`, `PERCENT_SLIDER`, `FILE_UPLOAD`, `CRON_BUILDER`); `icon` Material; `group` e `order`; `UISchemaPreset` e `extraProperties` de apresentação (`labelTrue`/`labelFalse`, `toneTrue`/`toneFalse`, `presentation.presenter`). |
| **4** | **Micro-Visualizações In-Cell (`@MicroVisualization`)** | Campos de progresso, ocupação, meta, variação ou distribuição em `ResponseDTO` declaram `@MicroVisualization(kind = MicroVisualizationKind.BULLET \| RADIAL \| HARVEY_BALL \| STACKED_BAR \| COMPARISON \| DELTA \| PROCESS_FLOW, valueExpr = "...", targetExpr = "...", totalExpr = "...", thresholds = {@Threshold(...)})`, validados estaticamente por `PraxisExpressionStaticValidator`. |
| **5** | **Governança de Domínio, LGPD & `fieldAccess`** | `@DomainGovernance(domain, subdomain, dataCategory, classification, sensitiveData, maskPattern, exportable, filterable, analyticsEligible, aiPolicy = @AiUsagePolicy(...))` em todos os campos sensíveis, pessoais, financeiros ou de auditoria, combinado com `@UISchema(visibleForAuthorities = {...}, editableForAuthorities = {...}, fieldAccessReason = "...")` quando houver restrição por perfil. |
| **6** | **Identidade Rica (`@ResourceIdentity`)** | Declarada tanto em `@ApiResource(identity = @ResourceIdentity(...))` no Controller quanto no `ResponseDTO`, preenchendo obrigatoriamente `keyField`, `titleField`, `subtitleField` (e/ou `codeField`/`statusField`) e `metadataFields = {...}`. |
| **7** | **Quick View & Navegação 360° (`@ResourceReference`)** | Curar **4 a 8 campos executivos** no `ResponseDTO` com `@UISchema(quickViewVisible = true, quickViewOrder = ...)` (sem repetir `titleField`/`subtitleField`). Anotar o campo `titleField` do próprio recurso **e** todos os campos de exibição de FKs (`departmentName`, `customerName`, `positionName`, `costCenterName`, `managerName`, etc.) com `@ResourceReference(resourceKey = "...", valueField = "...", surfaceId = "detail", previewSurfaceId = "...", detailPresentation = "drawer")`. |
| **8** | **Lookups Ricos (`OptionSourceDescriptor` + `EntityLookupDescriptor`)** | Registrar no `OptionSourceRegistry` fontes `RESOURCE_ENTITY` e `DISTINCT_DIMENSION` completas. Todo `RESOURCE_ENTITY` deve preencher `EntityLookupDescriptor` com: (a) `codePropertyPath`, `descriptionPropertyPaths`, `statusPropertyPath`; (b) `LookupSelectionPolicy` (`selectablePropertyPath`, `allowedStatuses`/`blockedStatuses`, `allowRetainInvalidExistingValue = true`, `disabledReasonTemplate`, `validationMessageTemplate`); (c) `LookupDetailDescriptor` (`surfaceId = "detail"`, `previewSurfaceId = "..."`, `presentation = "drawer"`); (d) `LookupDisplayDescriptor` (`preset = "directory"`, `fields`); (e) `LookupFilteringDescriptor` (`sortOptions`, `defaultSort`, `searchStrategies`); e (f) `dependsOn` + `dependencyFilterMap` para filtros em cascata (ex.: `companyId` / `organizationId`). |
| **9** | **Matriz Exaustiva de `FilterDTO`, Busca Ampla (`relations`), `@QuickFilter` & `ResourceFilterAccessScope`** | `FilterDTO implements GenericFilterDTO` com no mínimo 5 a 15+ campos cobrindo: (a) **Busca Ampla / Omnibox num único campo** via `@Filterable(operation = FilterOperation.LIKE, relations = {"fullName", "socialName", "motherName", "fatherName", "dependents.fullName", "taxId"})` para pesquisar simultaneamente em múltiplos atributos da entidade e de seus relacionamentos (`OR`); (b) combinação multi-operador sobre o mesmo atributo (`LIKE`/`STARTS_WITH`/`EQUAL`/`IN`, intervalos `BETWEEN` com `DATE_RANGE`/`PRICE_RANGE`, janelas relativas `IN_LAST_DAYS`/`IN_NEXT_DAYS`/`ON_DATE`, auditoria de nulidade `IS_NULL`/`IS_NOT_NULL`, cardinalidade de coleções `SIZE_GT`/`SIZE_EQ` e booleanos `IS_TRUE`); (c) navegação relacional cross-entity (`relation = "department.costCenter.code"`, `relation = "dependents.relationshipType"`) e `@CustomFields`; (d) no mínimo 2 a 6 `@QuickFilter` no `@ApiResource`; e (e) `ResourceFilterAccessScope` no `Service` quando houver isolamento multi-empresa/tenant. |

---

### FASE 2 — Superfícies Semânticas, Projeções 360°, Intents, Workflow Actions, Bulking, Facts, Analytics e Exportação

| # | Pilar | Contrato Canônico Obrigatório |
| :-: | :--- | :--- |
| **10** | **Superfícies Semânticas (`@UiSurface`), Master-Detail & Novos Endpoints Cross-Entity** | Publicar `@UiSurface(id = "...", kind = SurfaceKind.READ_PROJECTION \| SurfaceKind.VIEW, scope = SurfaceScope.ITEM)` para atender aos `previewSurfaceId` de `@ResourceReference`, além de coleções filhas master-detail (`relatedChildResourceKey`, `relatedChildResourcePath`, `relatedChildParentField`). Quando o relacionamento entre entidades revelar visões consolidadas não-óbvias (ex.: matriz de ocupação e vagas, radar de vencimentos, auditoria de pendências), criar **novos endpoints read-only dedicados** (`AbstractReadOnlyResourceController`). Os DTOs de projeção read-only devem manter de 5 a 10 colunas com `tableHidden = false` para renderização escaneável em tabelas/drawers. |
| **11** | **Manutenção Parcial Governada (`@ResourceIntent`)** | Em recursos extensos ou com subblocos cadastrais (ex.: alocação organizacional, dados bancários/faturamento, endereço/contato, parâmetros contratuais), expor endpoints `PATCH /{id}/{intent}` anotados com `@ResourceIntent(id = ..., title = ..., description = ...)` e `@UiSurface(id = ..., kind = SurfaceKind.PARTIAL_FORM, scope = SurfaceScope.ITEM, intent = ...)`. |
| **12** | **Ciclo de Vida (`@WorkflowAction`) & Concorrência (`If-Match` / `Idempotency-Key`)** | Modelar todas as transições reais de negócio da entidade como `@WorkflowAction` com DTO de comando próprio, `ResourceStateSnapshotProvider` para disponibilidade contextual em `/capabilities`, controle de versão otimista via `VersionedCreateUpdateResourceService` (`ETag` + `If-Match` retornando `428`/`412`/`409`), `Idempotency-Key` (`GovernedResourceCommandExecutor`) e `DuplicateDraftLegacyBackedResourceService` quando aplicável. |
| **13** | **Operações em Lote Governadas (`Bulking` — 3 Modos Além do Óbvio)** | Em recursos mutáveis corporativos, auditar **100% dos atributos e transições** para bulking: (1) diferenciar `@BulkEditable` em `BulkMode.UNIFORM_UPDATE` (padronização coletiva: departamento, centro de custo, jornada/turno, regime/plano, banco/agência/forma de pagamento, flags operacionais, gestor) e `BulkMode.PER_ITEM_UPDATE` (planilha/grade de revisão individualizada em lote: telefones, e-mails, endereços, contas bancárias, valores/limites individuais, códigos externos, datas específicas por item); se `allowClear = true`, declarar `@Schema(nullable = true)`; (2) anotar o Controller com `@BulkResourceOperations(protectedUpdateFields = {...})`; (3) expor endpoints de confirmação com `@BulkOperation(mode = BulkMode.UNIFORM_UPDATE \| BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "...")`; e (4) descobrir **atos coletivos de negócio** no ciclo de vida e nos relacionamentos (`@WorkflowAction(scope = ActionScope.COLLECTION)` + `@BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "...")`, ex.: realocação coletiva entre unidades, homologação/aprovação em lote, suspensão/reativação coletiva, reenquadramento paramétrico) seguindo o protocolo de 2 fases (`evaluation -> proposal -> confirm -> execution/results`). |
| **14** | **Fatos Determinísticos de Domínio (`@ResourceFact`)** | Declarar `@ResourceFact` (ou `ResourceFactCatalogProvider`) para publicar indicadores derivados e fatos determinísticos em `/schemas/domain` com `label` curto (≤ 35 caracteres, sem ponto final). |
| **15** | **Analytics (`StatsFieldRegistry` + `@UiAnalytics` / `@AnalyticsProjection`)** | Sobrescrever `getStatsFieldRegistry()` e `StatsSupportMode.AUTO` no `Service` (habilitando `/stats/group-by`, `/stats/timeseries`, `/stats/distribution` e `/stats/comparison`) e anotar o Controller com `@UiAnalytics(projections = {@AnalyticsProjection(..., recordOpen = @AnalyticsRecordOpen(..., target = @AnalyticsSurfaceTarget(...)))})`. |
| **16** | **Exportação Governada (`CollectionExport` + `CollectionExportFieldPresentation`)** | Configurar `CollectionExport` suportando os 3 formatos (`CSV`, `JSON`, `EXCEL`) e os 5 escopos (`AUTO`, `SELECTED`, `FILTERED`, `CURRENT_PAGE`, `ALL`), respeitando `exportable = false` de `@DomainGovernance` e enriquecendo colunas monetárias, datas e booleanos com `CollectionExportFieldPresentation`. |

---

### FASE 3 — Regras de Negócio, Determinações Reativas, Motor de Regras e Gates Automatizados

| # | Pilar | Contrato Canônico Obrigatório |
| :-: | :--- | :--- |
| **17** | **Invariantes Transacionais & Erros Estruturados (`409` / `422`)** | Implementar todas as invariantes de domínio no `Service` (create, update, intents, workflow actions e bulk), retornando erros canônicos estruturados (`409 Conflict` / `422 Unprocessable Entity` com `fieldErrors`) para materialização automática nos formulários `@praxisui/dynamic-form`. |
| **18** | **Determinações Reativas (`ReactiveDeterminationDefinitionProvider` — Além do Óbvio)** | Registrar beans `ReactiveDeterminationDefinitionProvider` vinculando operações de formulário (`ReactiveDeterminationScope(schemaOperationId, ReactiveDeterminationFormMode.CREATE \| EDIT)` — exigindo `@Operation(operationId = "create...")` no `@PostMapping` e `@Operation(operationId = "update...")` no `@PutMapping("/{id}")` do Controller) a endpoints `POST` não-persistentes anotados com `@Operation(operationId = "determine...")`. Usar `ReactiveDeterminationTriggerMode.ON_CHANGE`, `sourcePaths` (JSON Pointers iniciados por `"/"`, todos presentes em `inputs`), `ReactiveDeterminationInputBinding(fieldPath, requestPath)` (podendo incluir `"/id"` em modo `EDIT` sem colocá-lo em `sourcePaths`), `ReactiveDeterminationOutputBinding(responsePath, fieldPath)` (todos os campos de saída devem existir em `CreateDTO`/`UpdateDTO` — com `@UISchema(readOnly = true)` quando forem prévias calculadas — e jamais sobrepor `inputs`) e `ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST \| PLATFORM, sourceId, version)`. |
| **19** | **Motor de Regras (`praxis-rules-engine`) & Decisões Governadas (`praxis-config-starter`)** | Avaliar regras declarativas determinísticas via `RuleSet` (JSON Logic) do `praxis-rules-engine`. Quando o host integrar `praxis-config-starter`, consumir apenas snapshots aprovados de `/api/praxis/config/domain-rules/**` (`GOVERNED_DECISION`), sem inventar snapshots fictícios quando a fronteira de config não estiver ativa. |
| **20** | **Observabilidade, `/praxis/cockpit`, Orçamento N+1 & Gates Automatizados** | Expor `/praxis/cockpit/**` no `SecurityConfig` (em perfis de dev/inspeção), provar ausência de N+1 com assertions de contagem de queries em `POST /{resource}/filter` frio (orçamento fixo independentemente do tamanho da página) e executar o **Gate JUnit Nativo** abaixo (e `python scripts/workspace/audit-java-resource-completeness.py <host> --strict` quando no monorepo). |

---

## 3. Gate JUnit Nativo de Completude (Para Todo Projeto Host — Interno ou Externo)

Todo projeto Java que utiliza `praxis-metadata-starter` deve incluir um teste JUnit executando os **3 validadores nativos embarcados no próprio JAR do starter**:
1. `org.praxisplatform.uischema.authoring.SemanticMetadataReviewer`
2. `org.praxisplatform.uischema.validation.PraxisExpressionStaticValidator`
3. `org.praxisplatform.uischema.options.diagnostics.OptionSourcePublicationInventory` + `OptionSourcePublicationDiagnostics`

```java
package com.example.host.architecture;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.authoring.SemanticMetadataIssueSeverity;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewReport;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewer;
import org.praxisplatform.uischema.options.diagnostics.OptionSourcePublicationCandidate;
import org.praxisplatform.uischema.options.diagnostics.OptionSourcePublicationDiagnostics;
import org.praxisplatform.uischema.options.diagnostics.OptionSourcePublicationInventory;
import org.praxisplatform.uischema.options.service.OptionSourceRegistry;
import org.praxisplatform.uischema.validation.PraxisExpressionStaticValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PraxisMetadataCompletenessTest {

    @Autowired
    private OptionSourceRegistry optionSourceRegistry;

    private final SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();
    private final PraxisExpressionStaticValidator expressionValidator = new PraxisExpressionStaticValidator();

    @Test
    void responseAndFilterDtosPassSemanticMetadataReview() {
        List<Class<?>> dtoClasses = List.of(
                // Liste aqui todos os ResponseDTO, CreateDTO, UpdateDTO e FilterDTO do host
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
        for (Class<?> dtoClass : responseDtoClasses) {
            Map<String, Schema> resolved = ModelConverters.getInstance().readAll(dtoClass);
            Schema<?> rootSchema = resolved.get(dtoClass.getSimpleName());
            assertThat(expressionValidator.validateSchemaExpressions(dtoClass.getSimpleName(), rootSchema, false))
                    .as("Static expression issues in %s", dtoClass.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    void registeredOptionSourcesArePublishedWithoutMismatch() {
        List<OptionSourcePublicationCandidate> candidates = List.of(
                // Ex.: new OptionSourcePublicationCandidate("department", "human-resources.employees", "POST", "/api/human-resources/employees/filter", "request", EmployeeFilterDTO.class)
        );
        OptionSourcePublicationDiagnostics diagnostics =
                new OptionSourcePublicationInventory(optionSourceRegistry, candidates).diagnose();
        assertThat(diagnostics.unpublishedOrMismatched()).isEmpty();
    }
}
```

---

## 4. Protocolo de Descoberta Profunda de Domínio e Síntese Relacional ("Além do Óbvio")

Ao construir ou evoluir um host Praxis, **é proibido atuar como um gerador mecânico `1 tabela = 1 CRUD óbvio`**. O agente de IA deve analisar **o domínio de negócio em si e o grafo de relacionamentos entre as entidades (`@ManyToOne`, `@OneToMany`, `@Embedded`)** para descobrir e implementar capacidades corporativas não-óbvias nas 4 dimensões abaixo:

### 4.1. Filtragem Profunda, Multi-Operador, Cross-Entity e Busca Ampla (`Omnibox`)
1. **Busca Unificada num Único Campo (`@Filterable(relations = {...})`):**
   - Em sistemas corporativos reais, o usuário não quer selecionar 6 campos diferentes para descobrir se um nome pesquisado é o nome civil, nome social, sobrenome, nome da mãe/pai, nome de um dependente/familiar, razão social ou documento.
   - Todo `FilterDTO` cadastral/operacional deve incluir pelo menos um campo de **busca ampla semântica** (`searchTerm` / `buscaAmpla` / `identificacaoOuFamiliar`) usando `@Filterable(operation = FilterOperation.LIKE, relations = {"fullName", "socialName", "motherName", "fatherName", "dependents.fullName", "taxId"})`, avaliando todos os caminhos em disjunção (`OR`) com `DISTINCT` automático.
2. **Combinação Multi-Operador sobre o Mesmo Atributo:**
   - **Textos e Códigos:** `LIKE` (contém), `STARTS_WITH` (prefixo hierárquico/código), `EQUAL` (exato) e `IN` (lista de códigos em lote);
   - **Datas e Vigências:** combinar `BETWEEN` (`DATE_RANGE`), `ON_DATE`, `GREATER_OR_EQUAL`/`LESS_OR_EQUAL`, **`IN_LAST_DAYS`** (eventos recentes: admitidos/criados nos últimos N dias) e **`IN_NEXT_DAYS`** (radar preventivo: contratos/avaliações/férias vencendo nos próximos N dias);
   - **Valores e Quantidades:** `BETWEEN` (`PRICE_RANGE`), `GREATER_OR_EQUAL` (piso) e `LESS_OR_EQUAL` (teto);
   - **Auditoria de Completude Cadastral (`IS_NULL` / `IS_NOT_NULL`):** criar filtros explícitos para localizar cadastros com pendências ou exceções (ex.: *"sem conta bancária"*, *"sem gestor alocado"*, *"sem documento comprobatório"*, *"em aberto / sem data de encerramento"*);
   - **Cardinalidade de Coleções Filhas (`SIZE_GT`, `SIZE_EQ`, `SIZE_LT`):** filtrar a entidade pai pelo volume de itens relacionados (ex.: *"com mais de N dependentes/alocações"*).
3. **Navegação Relacional em Múltiplos Níveis (`relation = "a.b.c"`):**
   - Permitir filtrar a entidade raiz por atributos de entidades pai, avô e coleções filhas (`relation = "department.costCenter.region"`, `relation = "dependents.relationshipType"`, `relation = "assignments.status"`).

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

### 4.3. Criação de Novos Endpoints, Projeções Analíticas Cross-Entity e Subdomínios
Ao analisar os relacionamentos entre entidades (`Entidade A <-> Entidade B <-> Entidade C`), o agente deve ir além dos endpoints CRUD óbvios:
1. **Novos Endpoints Read-Only de Visão Consolidada (`AbstractReadOnlyResourceController`):** criar recursos analíticos/operacionais dedicados que cruzem entidades para responder perguntas executivas (ex.: matriz de ocupação e vagas por cargo/unidade, radar de vencimentos e afastamentos, painel de inconsistências cadastrais).
2. **Superfícies Master-Detail 360° (`@UiSurface` + `relatedChildResourceKey`):** expor todas as coleções filhas `1:N` relevantes como superfícies navegáveis no Quick View 360° e nas fichas de detalhe.
3. **Intents Especializadas por Persona (`@ResourceIntent` + `SurfaceKind.PARTIAL_FORM`):** decompor entidades extensas em múltiplos endpoints `PATCH` orientados aos processos reais de cada área operacional (alocação, pagamento/bancário, endereço/contato, parâmetros contratuais).

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


