# Metadata‑driven backend‑agnostic

## Definição curta
A UI é guiada por metadados publicados pelo backend (OpenAPI + `x-ui`), permitindo que diferentes backends funcionem desde que publiquem o mesmo contrato compatível. O front interpreta em runtime, sem geração de código específica de backend.

## Onde aparece no Praxis
- Backend: `backend-libs/praxis-metadata-starter/README.md:66` — conversão de validações e metadados para `x-ui` consumível.
- Backend: `backend-libs/praxis-metadata-starter/README.md:615` — `ApiDocsController`/`OpenApiGroupResolver` para documentos por grupo.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/generic-crud.service.ts:1182` — base do `/schemas/filtered` respeitando `ApiUrlConfig` (origens distintas).
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-table/README.md:88` — `resourcePath` desacopla tela do endpoint concreto.

## Como aplicar (passo a passo)
1) Padronize o contrato (`/schemas/filtered` + `x-ui`) em todos os serviços.
2) No front, centralize o consumo via `GenericCrudService` e `ApiUrlConfig` para alternar origens.
3) Garanta consistência de `x-ui.resource.idField` e semântica de paginação/ordenacão/filters.

## Exemplos mínimos
- Alternar origem (ApiUrlConfig):
  - `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/tokens/api-url.token.ts`
- Consumo de schema com base cruzada:
  - `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/generic-crud.service.ts:1182`

Snippet (config múltiplos endpoints):
```ts
providers: [
  { provide: API_URL, useValue: { default: '/api', admin: '/admin-api' } },
]
crud.configure('employees'); // default
crud.configure('employees', ApiEndpoint.Admin); // admin
```

## Anti‑padrões
- Engessar o front a um único backend/URL sem `ApiEndpoint`/`ApiUrlConfig`.
- Divergir do contrato `x-ui`, quebrando compatibilidade.

## Referências internas
- backend-libs/praxis-metadata-starter/README.md:681
- backend-libs/praxis-metadata-starter/src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:714
- frontend-libs/praxis-ui-workspace/README.md:103
- frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/generic-crud.service.ts:143

## Veja também
- [Self‑describing APIs](./self-describing-apis.md)
- [Schema‑driven UI](./schema-driven-ui.md)
- [Configuration‑driven development](./configuration-driven-development.md)

