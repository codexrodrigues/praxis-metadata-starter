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
