# UI Schema (UISchema) vs Data Schema

## Definição curta
O Data Schema representa a estrutura e as regras de dados (OpenAPI). O UI Schema estende o Data Schema com metadados de apresentação e interação (extensões `x-ui` geradas por `@UISchema`), guiando a renderização de tabelas, formulários e filtros.

## Onde aparece no Praxis
- Backend: `backend-libs/praxis-metadata-starter/README.md:164` — ordem de precedência entre padrões, detecção e valores explícitos do `@UISchema`.
- Backend: `backend-libs/praxis-metadata-starter/README.md:189` — propriedades do `@UISchema` e exemplos.
- Backend: `backend-libs/praxis-metadata-starter/src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:280` — anotação de `x-ui.resource.idField` no payload do `/schemas/filtered`.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/schema-normalizer.service.ts` — normalização do contrato para consumo interno.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/models/material-field-metadata.interface.ts:1032` — metadados de campos, inclusive `resourcePath`/tipos de controle.

## Como aplicar (passo a passo)
1) Modele DTOs com Bean Validation (Data Schema) e aplique `@UISchema` para enriquecer com `x-ui` (UI Schema).
2) Use `/schemas/filtered` para obter o Data+UI Schema filtrado por operação (request/response).
3) No front, alimente Tabela/Form com o resultado normalizado do schema; sobrescreva apenas o necessário via config.

## Exemplos mínimos
- DTO anotado:
  - `examples/praxis-backend-libs-sample-app/src/main/java/.../ValorParametroInstituicaoItemDTO.java:12`
- Consumo front (metadados de campo):
  - `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/models/material-field-metadata.interface.ts:980`

## Anti‑padrões
- Duplicar dados de apresentação no DTO (hardcode visual) em vez de `@UISchema`.
- Ignorar validações do Data Schema no front (divergência de regra).

## Referências internas
- backend-libs/praxis-metadata-starter/README.md:193
- backend-libs/praxis-metadata-starter/src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:572
- frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/generic-crud.service.ts:375
- frontend-libs/praxis-ui-workspace/projects/praxis-table/README.md:151
## Ordem de precedência (lista numerada)
1. Padrões do `@UISchema` (defaults)
2. Detecção automática pelo tipo/format do OpenAPI
3. Valores explícitos do `@UISchema` (sobrescrevem detecção)
4. Bean Validation (constraints tornam-se `x-ui.validation`)
5. Propriedades extras (`extraProperties`)

Exemplo: `@UISchema(controlType = FieldControlType.TEXTAREA)` força TEXTAREA mesmo que a detecção escolha INPUT por `string` curta.

```java
@UISchema(controlType = FieldControlType.TEXTAREA, maxLength = 1000)
private String observacoes;
```

## Evolução de Schema
- Non‑breaking: adicionar `description`, `helpText`, `placeholder`, novas `extraProperties` backward‑compatible.
- Breaking: renomear campo, alterar tipo/required. Estratégias: versão lógica (header), período de transição com alias, comunicar no CHANGELOG do contrato.
- Use `ETag`/`If-None-Match` para revalidação; combine com `X-Data-Version` quando a semântica dos dados mudar.

## Veja também
- [Self‑describing APIs](./self-describing-apis.md)
- [Schema‑driven UI](./schema-driven-ui.md)
- [Declarative UI](./declarative-ui.md)

