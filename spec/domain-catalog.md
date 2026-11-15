# Domain Catalog

`GET /schemas/domain` publica o catalogo semantico AI-operable do backend.

Ele e uma superficie derivada: agrega significado de dominio a partir de
`@WorkflowAction`, `@UiSurface`, option sources, OpenAPI schemas e extensoes
canonicas como `x-domain-governance`. Ele nao substitui `/schemas/filtered`,
`/schemas/catalog`, `/schemas/actions` ou `/schemas/surfaces`.

## Papel Canonico

- `/schemas/filtered`: contrato estrutural de request/response
- `/schemas/catalog`: catalogo documental e operacional
- `/schemas/actions`: discovery semantico de comandos de negocio
- `/schemas/surfaces`: discovery semantico de experiencias de UI
- `/schemas/domain`: vocabulario, aliases, bindings, evidencias e governanca de dominio

## Versao

O schema atual publicado pelo starter e `praxis.domain-catalog/v0.2`.

## Identidade De Release

`release.sourceHash` e o SHA-256 canonico do catalogo semantico, sem metadados
volateis como `generatedAt`. `release.releaseKey` e derivado de `serviceKey`,
escopo semantico e prefixo do hash, mantendo a mesma identidade para semanticas
iguais em chamadas repetidas ou depois de restart do host.

Quando a consulta usa `resourceKey`, o envelope tambem publica esse valor no
campo top-level `resourceKey`. Consumidores que persistem releases devem usar
esse campo estrutural para escopo e indexacao; `release.releaseKey` continua
sendo a identidade imutavel da release, e nao deve ser analisada para recuperar
o recurso. Em catalogos solicitados por `group`, `resourceKey` e nulo.

O catalogo gerado por `resourceKey` ou `group` e memoizado no runtime do starter
para evitar recomputar schemas, aliases, evidencias e governanca em leituras
repetidas de `/schemas/domain`.

## Governanca Explicita

Campos podem declarar governanca com `@DomainGovernance`.

O resolver OpenAPI materializa a anotacao em `x-domain-governance`:

```json
{
  "annotationType": "privacy",
  "classification": "confidential",
  "dataCategory": "financial",
  "complianceTags": ["LGPD", "INTERNAL_POLICY"],
  "exportable": false,
  "aiUsage": {
    "visibility": "mask",
    "trainingUse": "deny",
    "ruleAuthoring": "review_required",
    "reasoningUse": "allow"
  },
  "reason": "Campo financeiro explicitamente marcado.",
  "source": "java.annotation",
  "confidence": 1.0
}
```

`/schemas/domain` prioriza essa governanca explicita antes de aplicar heuristicas
por nome, titulo, descricao ou formato do campo.

Quando consultado com `GET /schemas/domain?strict=true` (ou via `SemanticDomainCatalogService.findByResourceKeyStrict` / `findByGroupStrict`), o starter opera em **modo estrito fail-closed**: se qualquer campo do recurso não possuir `@DomainGovernance` explícito (dependendo de fallback heurístico), a geração do catálogo falha com `IllegalStateException` listando os campos sem governança declarada.

### Escopo de Enforcement de `aiUsage` e Descoberta Estrutural

No payload de governança publicado em `/schemas/domain`, `aiUsage` inclui os metadados canônicos:

- `valueEnforcementScope = "runtime_payload_only"`
- `catalogMetadataDiscoverable = true`

Isso explicita que `aiUsage.visibility = "deny"` ou `"mask"` governa o **valor em payloads de runtime** (prompts, respostas e ferramentas de dados), enquanto o **nome estrutural e a existência do campo** permanecem visíveis no catálogo de metadados para permitir auditoria, compilação de políticas (`praxis-authz-*`) e validação de referências.

### Herança em Nível de Tipo (`ElementType.TYPE`) e Linhagem Semântica (`@DomainLineage`)

- **`@DomainGovernance` em classes/interfaces (`ElementType.TYPE`):** permite declarar um piso de governança na classe ou interface do DTO/Entidade. Propriedades sem `@DomainGovernance` próprio herdam a anotação do tipo; propriedades com `@DomainGovernance` próprio passam por fusão monotônica (`DomainLineageResolver.mergeRestrictive`), prevalecendo sempre a classificação mais restritiva.
- **`@DomainLineage` (`org.praxisplatform.uischema.annotation.DomainLineage`):** vincula uma propriedade de DTO ou projeção analítica ao atributo canônico de origem (`attributeId`, `sourceClass`, `sourcePaths`, `sourceResourceKey`, `transformation = DIRECT | MASKED | AGGREGATED | DERIVED`). O catálogo `/schemas/domain` projeta essa linhagem em:
  - `node.metadata.lineage` no nó `field` e `conceptNode.metadata.sharedRepresentations` no nó `concept`;
  - arestas `same_as` (`transformation = DIRECT`), `materializes` (`AGGREGATED`) ou `depends_on` (`DERIVED` / `MASKED`) com `metadata.provenance = "domain-lineage"`.

### Finalidade Legal LGPD em Surfaces e Actions (`LegalTreatmentPurpose`)

`@UiSurface` e `@WorkflowAction` aceitam `legalPurpose = LegalTreatmentPurpose.*` (`UNSPECIFIED`, `HUMAN_RESOURCES_ADMINISTRATION`, `PAYROLL_AND_TAX_COMPLIANCE`, `OFFICIAL_HEALTH_EXPERTISE`, `SOCIAL_SECURITY_AND_BENEFITS`, `ACTIVE_PUBLIC_TRANSPARENCY_LAI`, `EXTERNAL_AUDIT_CONTROL`, `EMPLOYEE_SELF_SERVICE`, `STATUTORY_LEGAL_OBLIGATION`) e `legalBasisRef = "..."` (ex.: `"LGPD:ART_7_III"`, `"LGPD:ART_11_II_A"`, `"Lei 8.112/1990 art. 202"`). Quando declarados, são publicados nos catálogos `/schemas/surfaces`, `/schemas/actions` e nos nós `surface` e `action` de `/schemas/domain` (validados por `SemanticMetadataReviewer` via `legal-basis-without-legal-purpose` e `legal-purpose-without-legal-basis-ref`).

## Vocabulario Canonico

- `annotationType`: `privacy`, `security`, `compliance`
- `classification`: `public`, `internal`, `confidential`, `restricted`
- `dataCategory`: `credential`, `sensitive_personal`, `personal`, `financial`, `operational`, `legal`
- `aiUsage.*`: `allow`, `deny`, `mask`, `review_required`, `summarize_only`

No Java, esses tokens sao fixados por:

- `DomainGovernanceKind`
- `DomainClassification`
- `DomainDataCategory`
- `AiVisibilityMode`
- `AiTrainingUseMode`
- `AiControlledUseMode`

## Regra De Uso

Hosts self-describing devem preferir `@DomainGovernance` para campos sensiveis,
regulatorios ou relevantes para authoring governado por IA. Heuristicas continuam
existindo como fallback, mas nao devem ser a fonte primaria quando a semantica e
conhecida pelo dominio.

Consumidores devem tratar itens de governanca como sinais para explicacao,
mascaramento, indexacao, RAG e authoring assistido. A decisao operacional final
continua pertencendo ao runtime ou a fronteira governada que consome o catalogo.

`x-domain-governance.exportable` é booleano (default Java `true`). Valor `false` ou a tag
`NON_EXPORTABLE` exclui o campo da allowlist de exportação na base de serviços, inclusive
quando o host sobrescreve `exportFields()`. A lista do cliente não pode relaxar essa política.

Os itens de `governance` do catálogo também preservam `exportable`; quando não há declaração
nem tag de restrição, o valor é ausente/null. `NON_EXPORTABLE` sempre resulta em `false`.

## Knowledge Graph, Glossario De Negocio E Data Products (`v0.2`)

O `SemanticDomainCatalogService` sintetiza automaticamente o Knowledge Graph corporativo e os Data Products do dominio a partir das anotacoes canonicas do starter (`@ApiResource`, `@ResourceReference`, `@UiSurface`, `@ResourceFact`, `@UiAnalytics`, `StatsFieldRegistry` e `OptionSourceRegistry`), sem exigir YAML manual no host:

- **Nos do grafo (`nodes`)**:
  - `concept`: conceito central do recurso, enriquecido com `businessGlossary` (`preferredTerm`, `synonyms`, `domain`, `description`, `stewardRole`) e `metadata`:
    - `conceptRole`: `operational_concept` (recursos transacionais/cadastrais) ou `analytical_data_product` (projecoes analiticas read-only `Radar*` / `@UiAnalytics` sem operacoes de escrita).
    - `relatedConcepts`, `parentConcepts`, `childConcepts`: vizinhanca semantica derivada de `@ResourceReference` e `relatedResource` em `@UiSurface`.
    - `analyzesConcepts` e `analyzedByDataProducts`: conexao bidirecional deterministica entre conceitos operacionais e os Data Products analiticos que os monitoram.
    - `ontologicalFactRoots` e `conceptFactRootMap`: raizes ontologicas da arvore de fatos (`PraxisFactTreeBuilder` — `draft`, `current`, raizes `<conceito>` em 1-hop derivadas de FKs/lookups, `neighborhood` e `context`) e o mapa `conceptFactRootMap` (`<conceito> -> referencedResourceKey`), conectando a macro-ontologia do Knowledge Graph a micro-ontologia do motor de regras (`praxis-rules-engine`).
    - `governedRuleSets`, `governedOperations` e `governedReasonCodes`: resumo dos `RuleSetDefinition`s (SHACL NodeShapes) que governam o conceito.
    - `dataProduct`: manifesto do produto de dados analitico (`kind`, `domain`, `resourceKey`, `resourcePath`, `readOnly`, `supportedStatsOperations`, `metricsCount`, `factsCount`, `projectionsCount`, `recommendedCharts`, `analyzesConcepts`).
  - `field`: campos do contrato, enriquecidos com `businessGlossary` (incluindo sinonimos humanos de busca ampla quando houver `@Filterable(relations = ...)`), `metadata.resourceReference` e `metadata.optionSource` (`key`, `type`, `resourcePath`, `dependsOn`, `dependencyFilterMap`, `searchStrategies`, `selectionPolicy`).
  - `fact`: fatos deterministicos declarados via `@ResourceFact` (`factId`, `valueType`, `unit`, `category`).
  - `surface`: experiencias de UI declaradas via `@UiSurface`.
  - `action`: transicoes e comandos declarados via `@WorkflowAction` / `@ResourceIntent`.
  - `quick_filter`: recortes operacionais canonicos declarados via `@QuickFilter` (`criteria = @FilterCriterion(...)`) ou `defineQuickFilters()`, publicados em `{resourceKey}.quickFilter.{id}` com `filter`, `filterFields`, `tone`, `isDefault`, `description` e arestas `has_quick_filter` / `filters_field`.
  - `stats`: capacidades analiticas (`group-by`, `timeseries`, `distribution`, `comparison`) e `analyticsProjections` (`@UiAnalytics`).
  - `capability`: operacoes estruturais publicadas para o recurso.
  - `policy_hint`: dicas de governanca, politicas de selecao (`option-source`) e `RuleSetDefinition`s governados (`source = "rule-set"`, `metadata.policyKind = "domain_rule_set"` via `DomainRuleSetCatalogProvider` / `DomainRuleSetDescriptor`, com `availableRoots`, `referencedFactPaths`, `slots`, `rules`, `reasonCodes` e `targetFields`).
    - Para executores Java, o host declara os resultados possíveis em `DomainRuleSetDescriptor.reasonDescriptors`, associados ao slot. O índice `reasonCodes` e os `targetFields` incluem todos os descritores dos slots Java habilitados, inclusive quando há vários resultados possíveis. Slots Java desabilitados não acrescentam resultados ao índice ativo. Não preencher `DecisionBinding.falseReasonCode` para simular um resultado JSON Logic nem inferir códigos pelo nome da implementação.
- **Arestas do grafo (`edges`)**:
  - Estruturais do recurso: `has_field`, `has_fact`, `has_surface`, `has_action`, `has_stats`, `has_capability`, `has_policy`, `governed_by` (do `concept` e dos `field`s de `targetFields` para o no `policy_hint` do `RuleSet`, com binding `rule_definition`) e `materializes` (`analytical_data_product -> operational_resource`).
  - Relacionais cross-resource (Knowledge Graph):
    - `references` (`concept -> targetConcept`) e `part_of` (`field -> targetConcept`): derivados de `@ResourceReference` e lookups `RESOURCE_ENTITY`.
    - `contains` (`parentConcept -> childConcept`): derivado de `@UiSurface(relatedChildResourceKey = ...)`.
    - `depends_on` (`field -> dependencyField`): derivado de `dependsOn` / `dependencyFilterMap` em `OptionSourceDescriptor`.
    - `related_to` (`analytical_data_product -> operational_concept`): derivado do cruzamento de FKs, `@ResourceReference` e prefixo de dominio em Data Products analiticos.

## Sincronizacao Zero-Config E Visao 360° De Dominio (`praxis-config-starter`)

Quando o host inclui `praxis-metadata-starter` e `praxis-config-starter` no mesmo processo:

- `DomainCatalogLocalSourceProvider` expoe os catalogos escopados de `SemanticDomainCatalogService` diretamente em memoria (sem round-trip HTTP).
- `DomainCatalogAutoSyncService` ingere automaticamente os catalogos locais na inicializacao (`ApplicationReadyEvent`), controlado por `praxis.domain-catalog.auto-sync.enabled=true` (habilitado por padrao quando ambos os starters estao presentes).
- `GET /api/praxis/config/domain-360` projeta a visao consolidada 360° de um conceito (`?resourceKey=...`) ou de todo o dominio (`?group=...`), reunindo glossario, vizinhanca do Knowledge Graph (`relatedConcepts`, `parentConcepts`, `childConcepts`), Data Products (`analyzesConcepts`, `analyzedByDataProducts`), campos, fatos, surfaces, actions, analytics e governanca.
- O grounding de IA (`DomainCatalogPromptContextService`, `AgenticAuthoringDomainCatalogCandidateEnhancer` e `AgenticAuthoringConsultativeApiCatalogProjectionService`) consome esses sinais para expandir automaticamente candidatos com Data Products analiticos relacionados e rotular recursos `analytical_data_product` como `role = "analytical"`.

