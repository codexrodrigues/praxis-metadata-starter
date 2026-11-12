# Semantic Metadata Authoring

Use este guia quando um host precisa acelerar metadata de DTOs sem transformar a IA em geradora de
texto de dominio plausivel.

## Fronteiras

- `@Schema(description = ...)` descreve o significado de negocio do campo.
- `@UISchema` descreve apresentacao, controles, layout e comportamento de UI.
- `@DomainGovernance` descreve classificacao (`preset`, `kind`, `classification`, `dataCategory`, `complianceTags`, `reason`, `confidence`), elegibilidade de exportacao (`exportable`) e uso por IA (`@AiUsagePolicy`), sendo publicado em `x-domain-governance` e no `/schemas/domain`. Suporta `@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})`, permitindo heranca monotônica da classe/interface para os campos. (Nota: metadados organizacionais como `owner`, `steward` e `retentionPolicy` pertencem a `GovernanceSummary` de `/schemas/domain` e a `DomainRuleDefinition` no `praxis-config-starter`, nao sendo atributos da annotation `@DomainGovernance`).
- `@DomainLineage` descreve a linhagem semantica deterministica (`attributeId`, `sourceClass`, `sourcePaths`, `sourceResourceKey`, `transformation`) de um atributo projetado em DTO a partir de uma Entidade JPA ou classe ancestral, publicada em `x-domain-governance.lineage` e validada para impedir que o DTO enfraqueca a governanca da origem (`lineage-governance-weakened`). Em DTOs, `@DomainLineage` complementa (e nao substitui) a declaracao de `@DomainGovernance`.
- `LegalTreatmentPurpose` (`legalPurpose` e `legalBasisRef` em `@UiSurface` e `@WorkflowAction`) declara a finalidade e a base legal LGPD de cada superficie de visualizacao ou comando de negocio.
- `@UISchema(preset = ...)` reduz repeticao visual, mas nao escreve descricao de dominio.

## Presets

Presets canonicos disponiveis:

- `ENTERPRISE_ID`
- `ENTERPRISE_CODE`
- `ENTERPRISE_NAME`
- `ENTERPRISE_STATUS`
- `DATE_RANGE`
- `MONETARY_AMOUNT`
- `BOOLEAN_FLAG`
- `LEGAL_DOCUMENT_REFERENCE`
- `TENANT_LABEL`
- `AUDIT_TIMESTAMP`
- `CPF_CNPJ`
- `EMAIL_CONTACT`
- `PHONE_CONTACT`
- `INTEGER_COUNTER`
- `PERCENTAGE_RATE`

### Presets de Governança (`DomainGovernancePreset`) e Meta-Anotações Compostas

Para evitar repetição de atributos LGPD/segurança e políticas de IA em cada campo, `@DomainGovernance` aceita `preset = DomainGovernancePreset.*` (qualquer atributo explícito sobrescreve o preset):

- `LGPD_PERSONAL_ID` (`PRIVACY`, `CONFIDENTIAL`, `PERSONAL`, tags `{"LGPD", "PII"}`, `visibility = MASK`)
- `LGPD_PERSONAL_CONTACT` (`PRIVACY`, `CONFIDENTIAL`, `PERSONAL`, tags `{"LGPD", "PII"}`, `visibility = MASK`)
- `LGPD_SENSITIVE_DATA` (`PRIVACY`, `RESTRICTED`, `SENSITIVE_PERSONAL`, tags `{"LGPD", "SENSITIVE_PII"}`, `exportable = false`, `visibility = MASK`, `reasoningUse = REVIEW_REQUIRED`)
- `CONFIDENTIAL_FINANCIAL` (`PRIVACY`, `CONFIDENTIAL`, `FINANCIAL`, tags `{"LGPD", "FINANCIAL_CONFIDENTIAL"}`, `visibility = MASK`)
- `SECURITY_CREDENTIAL` (`SECURITY`, `RESTRICTED`, `CREDENTIAL`, tags `{"SECURITY", "CREDENTIAL"}`, `exportable = false`, `visibility = DENY`, `ruleAuthoring = DENY`, `reasoningUse = DENY`)
- `INTERNAL_OPERATIONAL` (`SECURITY`, `INTERNAL`, `OPERATIONAL`)
- `LEGAL_COMPLIANCE` (`COMPLIANCE`, `INTERNAL`, `LEGAL`, tags `{"COMPLIANCE"}`)

Além disso, `@UISchema`, `@DomainGovernance` e `@MicroVisualization` suportam `@Target(ElementType.ANNOTATION_TYPE)`, permitindo compor meta-anotações reutilizáveis resolvidas recursivamente por `ResolverUtils`.

Exemplo:

```java
@Schema(description = "Codigo operacional reconhecido pelo backend legado de folha para classificar eventos e rubricas.")
@UISchema(label = "Codigo", preset = UISchemaPreset.ENTERPRISE_CODE)
private String code;
```

O schema publicado inclui `x-ui.presentationPreset = "enterprise-code"` e a apresentacao
semantica de leitura/lista em `x-ui.presentation`, mas a descricao continua vindo do texto humano
em `@Schema`.

## Review

Use `SemanticMetadataReviewer` para produzir relatorios de autoria e auditoria de governanca:

```java
SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

// Revisao padrao do DTO
SemanticMetadataReviewReport report = reviewer.review(MeuDTO.class);

// Revisao com gate P50 (cruzando campos RESTRICTED/SENSITIVE_PERSONAL/CREDENTIAL contra OptionSources e StatsFieldRegistry)
SemanticMetadataReviewReport fullReport = reviewer.review(MeuDTO.class, optionSourceDescriptors, statsFieldRegistry);

// Revisao estrita (exige @DomainGovernance explicito em 100% dos campos expostos)
SemanticMetadataReviewReport strictReport = reviewer.reviewStrictGovernance(MeuDTO.class);

// Revisao de fatos (@ResourceFact) e finalidade legal LGPD (@UiSurface / @WorkflowAction)
SemanticMetadataReviewReport factsAndLegalReport = reviewer.reviewResourceFacts(MeuControllerOuProvider.class);
```

O reviewer aponta:

- **Qualidade Editorial de `@Schema` e `@UISchema`:**
  - `schema-description-missing`
  - `schema-description-copies-ui-label`
  - `schema-description-derived-from-field-name`
  - `preset-without-domain-description`
- **Governança, Sensibilidade, Linhagem (`@DomainLineage`) e Modo Estrito:**
  - `public-private-context-field-without-governance`
  - `sensitive-field-without-governance`
  - `sensitive-field-public-classification`
  - `sensitive-field-allows-ai-training`
  - `missing-explicit-domain-governance` (em `reviewStrictGovernance`)
  - `lineage-governance-weakened` (quando a governança do DTO é menos restritiva que a origem declarada em `@DomainLineage`)
  - `invalid-domain-lineage` (quando `sourceClass`/`sourcePaths` de `@DomainLineage` não podem ser resolvidos)
- **Proteção de Superfícies Analíticas e Lookups (`P50`):**
  - `restricted-field-in-analytics-or-option-source` (quando um campo `RESTRICTED`, `CREDENTIAL` ou `SENSITIVE_PERSONAL` é exposto em `StatsFieldRegistry` ou `OptionSourceDescriptor`)
- **Finalidade Legal LGPD (`@UiSurface` / `@WorkflowAction`) e Fatos Determinísticos (`@ResourceFact`):**
  - `legal-basis-without-legal-purpose` (`legalBasisRef` preenchido com `legalPurpose = UNSPECIFIED`)
  - `legal-purpose-without-legal-basis-ref` (`legalPurpose` regulado como `LEGAL_OBLIGATION` ou `PUBLIC_POLICY` sem `legalBasisRef`)
  - `resource-fact-client-overridable-not-allowed`, `resource-fact-invalid-authority`, `resource-fact-invalid-evaluation-phase`, `resource-fact-invalid-missing-value-policy`, `resource-fact-invalid-scope`

O reviewer nao gera descricoes. Ele apenas mostra onde a autoria humana ainda precisa decidir
significado, limites, relacoes, impacto e governanca.

## MicroVisualization e precedência efetiva

`@MicroVisualization` publica a base de `x-ui.presentation` após os presets de
`@UISchema`. Overrides explícitos em `@UISchema.extraProperties` são aplicados por
último. Prefira paths de folhas, como `presentation.visualization.tone`, para
substituir somente uma propriedade e preservar `kind`, `target` e demais dados.
Um override do objeto completo substitui esse objeto; não representa um merge de
folhas. Metadados de controle, label, validação e options continuam independentes.

Em campos anotados com `@MicroVisualization`, quando o presenter efetivo é
`microVisualization`,
`presentation.visualization.fallbackText` deve ser uma string não vazia, com texto
do domínio legível para acessibilidade e indisponibilidade do gráfico. Pode vir
de `@MicroVisualization(fallbackText = "...")` ou de `extraProperties`; a validação
ocorre após os overrides. Valor ausente, vazio ou de outro tipo impede publicar
esse schema. Um presenter alternativo explícito, como `chip`, pode substituir a
microvisualização e não exige seu fallback. O starter não inventa texto de negócio.

```java
@MicroVisualization(kind = MicroVisualizationKind.BULLET, target = 90,
        valueExpr = "row.percentualAtendimento",
        fallbackText = "Meta de atendimento: 90%")
@UISchema(extraProperties = @ExtensionProperty(
        name = "presentation.visualization.tone", value = "warning"))
private Double percentualAtendimento;
```

Neste exemplo, `row.percentualAtendimento` e `target = 90` usam a mesma escala
percentual de 0 a 100. `valueExpr` fornece o valor da linha; sem valor estático ou
expressão, o renderer não recebe o valor do bullet e usa o fallback.

### Escala comparativa (`scaleScope`), limites (`min`/`max`), sufixo (`valueSuffix`) e linhas de referência

Em tabelas analíticas e KPIs corporativos, `@MicroVisualization` e `presentation.visualization` suportam diretamente (ou por override em `extraProperties`):

- `scaleScope`: `'row'` (padrão, normaliza a série localmente por linha) ou `'page'` (sincroniza o domínio `min`/`max` entre todas as linhas visíveis da página atual na tabela para comparação justa de magnitude).
- `min` / `minExpr` e `max` / `maxExpr`: limites explícitos ou dinâmicos por linha; quando definidos na linha, têm precedência sobre os limites sincronizados de página.
- `valueSuffix` / `valueSuffixExpr`: unidade ou sufixo contextual (ex.: `"%"`, `" ms"`, `" R$"`) exibido nos HUDs e pílulas de inspeção sem alterar o valor numérico bruto.
- `target` / `targetExpr`, `baseline` / `baselineExpr` e `pointsExpr`: além de `bullet`, `target` e `baseline` também projetam linhas horizontais tracejadas de meta/referência nas famílias canônicas de séries (`line`, `area`, `column`).
- `segmentsExpr`, `itemsExpr`, `thresholdsExpr`, `ariaLabel`, `ariaLabelExpr` e `fallbackTextExpr`: permitem vincular segmentos de `stackedBar`, etapas de `processFlow`, faixas de `thresholds` e textos acessíveis diretamente a propriedades ou expressões da linha.

O Angular consome esse contrato efetivo; não deve corrigir schemas inválidos com
texto ou regras locais. A prova do resolver não substitui validação HTTP do host
ou implantação do artefato público.

