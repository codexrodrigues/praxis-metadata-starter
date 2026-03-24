# Self-describing APIs

## Definicao curta

APIs que publicam, junto aos dados, um contrato rico e interpretavel em
runtime, como OpenAPI com extensoes `x-ui`.

## Onde aparece no Praxis

- Backend: `praxis-metadata-starter` com `/schemas/filtered`
- Frontend: `@praxisui/core` com `GenericCrudService`
- Frontend: `@praxisui/table` e formularios dinamicos baseados no contrato

## Como aplicar

1. anote DTOs com `@UISchema`
2. publique `/schemas/filtered`
3. consuma schema e revalide com `ETag` e `If-None-Match`
4. deixe os componentes gerarem estrutura a partir do contrato

## Referencias publicas

- repositório público: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes: `@praxisui/core`, `@praxisui/table`, `@praxisui/dynamic-form`
