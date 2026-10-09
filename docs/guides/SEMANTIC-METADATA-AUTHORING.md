# Semantic Metadata Authoring

Use este guia quando um host precisa acelerar metadata de DTOs sem transformar a IA em geradora de
texto de dominio plausivel.

## Fronteiras

- `@Schema(description = ...)` descreve o significado de negocio do campo.
- `@UISchema` descreve apresentacao, controles, layout e comportamento de UI.
- `@DomainGovernance` descreve classificacao, privacidade, compliance e uso por IA.
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

Use `SemanticMetadataReviewer` para produzir relatorios de autoria:

```java
SemanticMetadataReviewReport report = new SemanticMetadataReviewer().review(MeuDTO.class);
```

O reviewer aponta:

- `schema-description-missing`
- `schema-description-copies-ui-label`
- `schema-description-derived-from-field-name`
- `preset-without-domain-description`
- `public-private-context-field-without-governance`

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

O Angular consome esse contrato efetivo; não deve corrigir schemas inválidos com
texto ou regras locais. A prova do resolver não substitui validação HTTP do host
ou implantação do artefato público.
