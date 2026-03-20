# English Public Surface Backlog

Objective: close the public surface of `praxis-metadata-starter` in English, starting with runtime HTTP contracts and moving later to documentation and internal comments.

## Scope

Public surface for this backlog means:

- HTTP response `message`, `title`, `detail`, `reason`
- exception messages that can escape through public APIs or shared helpers
- public contract constants and enum/parser messages
- canonical starter documentation consumed across teams

Out of scope for the first wave:

- internal comments
- test-only strings
- purely local developer notes

## Definition Of Done

The starter is considered English-complete for public surface when:

1. runtime HTTP messages and error titles are in English
2. `ResponseStatusException` reasons exposed by base controllers are in English
3. public enum/parser/helper exceptions are in English
4. canonical docs linked from starter entry points are in English, or explicitly marked as legacy/internal
5. automated checks prevent regressions in `src/main/java` and public `docs/`

## Priority Plan

### P0: Runtime HTTP contract

These items should be fixed first because they cross service boundaries immediately.

- `GlobalExceptionHandler`
  - replace Portuguese top-level `RestApiResponse.message(...)` values with English
  - review remaining top-level response summaries for consistency across validation, business, not-found, request and server errors
  - file: `src/main/java/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.java`
  - current hotspots:
    - `Erro de validação`
    - `Erro de regra de negócio`
    - `Recurso não encontrado`

- `RestApiResponse`
  - replace default success message with English
  - file: `src/main/java/org/praxisplatform/uischema/rest/response/RestApiResponse.java`
  - current hotspot:
    - `Requisição realizada com sucesso`

- `AbstractReadOnlyController`
  - replace `ResponseStatusException` reasons with English
  - file: `src/main/java/org/praxisplatform/uischema/controller/base/AbstractReadOnlyController.java`
  - current hotspot:
    - `recurso somente leitura`

- `AbstractCrudController`
  - replace `ResponseStatusException` reasons returned by cursor/locate/pagination guards with English
  - file: `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java`
  - current hotspots:
    - `Limite máximo de registros por página excedido: ...`
    - `não implementado`

### P1: Public helper and parser exceptions

These items are not always rendered as HTTP responses, but they are part of shared library behavior and leak into integrations and tests.

- enum/parser helpers
  - file: `src/main/java/org/praxisplatform/uischema/FieldDataType.java`
  - file: `src/main/java/org/praxisplatform/uischema/FieldControlType.java`
  - file: `src/main/java/org/praxisplatform/uischema/numeric/NumberFormatStyle.java`
  - current hotspots:
    - `Tipo de dado desconhecido: ...`
    - `Tipo de controle desconhecido: ...`
    - `Estilo de formato numérico desconhecido: ...`

- `AbstractCrudController` helper exceptions
  - file: `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java`
  - current hotspots:
    - `O parâmetro 'methodPath' não pode ser nulo ou vazio.`
    - `O parâmetro 'operation' não pode ser nulo ou vazio.`
    - `Não foi possível construir o link para a documentação filtrada.`

- `ApiDocsController`
  - file: `src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java`
  - current hotspots:
    - `schemaType deve ser 'response' ou 'request'`
    - `Não foi possível obter documento OpenAPI para o grupo: ...`
    - `O caminho ou operação especificado não foi encontrado na documentação.`
    - `O schema solicitado não foi encontrado...`
    - `O esquema de componentes especificado não foi encontrado...`
    - `Documento OpenAPI não encontrado na URL: ...`

### P2: Canonical docs

These items should be translated after runtime and helper layers are stable.

- starter entry-point docs
  - `docs/README.md`
  - `docs/packages-overview.md`
  - `docs/overview/VISAO-GERAL.md`

- canonical usage guides
  - `docs/guides/FILTROS-E-PAGINACAO.md`
  - `docs/guides/READ-ONLY-VIEWS.md`
  - `docs/technical/VALIDACAO-API-RESOURCE.md`

Recommendation:

- keep legacy Portuguese docs temporarily only if they are clearly marked as legacy/internal
- prefer one canonical English version instead of maintaining bilingual drift

### P3: Internal comments, Javadocs and tests

These items improve consistency but should not block closure of the public contract.

- package-info files
- internal Javadocs
- test descriptions and assertions
- dummy/sample fixtures

## Recommended Execution Order

1. close `GlobalExceptionHandler` and `RestApiResponse`
2. close base controller `ResponseStatusException` reasons
3. close public helper/parser exceptions
4. add or update contract tests for English runtime messages
5. translate canonical docs
6. add CI guardrails

## Contract Test Backlog

Add focused tests for:

- `GlobalExceptionHandlerTest`
  - assert English top-level messages for validation, business and not-found flows

- base controller tests
  - assert English `reason` for read-only and not-implemented flows

- parser/helper tests
  - assert English exceptions in enum `fromValue(...)` helpers

## CI Guardrails

Recommended guardrails:

- a small regression check over `src/main/java` and public `docs/`
- fail on Portuguese strings in public surface, with a short allowlist for accepted legacy cases
- keep internal comments/tests out of the first guardrail to avoid noisy adoption

## Notes

- `inlineRelativePeriod` is no longer the blocker in this area; the remaining work is mostly legacy public surface outside that capability
- do not solve this with local app translations; the correct platform fix is to normalize the starter public surface itself
