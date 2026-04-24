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

## Vocabulario Canonico

- `annotationType`: `privacy`, `security`, `compliance`
- `classification`: `public`, `internal`, `confidential`, `restricted`
- `dataCategory`: `credential`, `sensitive_personal`, `personal`, `financial`, `operational`, `legal`
- `aiUsage.*`: `allow`, `deny`, `mask`, `review_required`, `summarize_only`

No Java, esses tokens sao fixados por:

- `DomainGovernanceKind`
- `DomainClassification`
- `DomainDataCategory`
- `AiUsageMode`

## Regra De Uso

Hosts self-describing devem preferir `@DomainGovernance` para campos sensiveis,
regulatorios ou relevantes para authoring governado por IA. Heuristicas continuam
existindo como fallback, mas nao devem ser a fonte primaria quando a semantica e
conhecida pelo dominio.

Consumidores devem tratar itens de governanca como sinais para explicacao,
mascaramento, indexacao, RAG e authoring assistido. A decisao operacional final
continua pertencendo ao runtime ou a fronteira governada que consome o catalogo.
