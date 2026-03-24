# Schema-driven UI

## Definicao curta

Gerar a interface a partir do schema e de `x-ui`, em vez de codificar campos,
colunas e filtros manualmente.

## Onde aparece no Praxis

- Frontend: `@praxisui/table`
- Frontend: `@praxisui/core`
- Backend: `praxis-metadata-starter`

## Como aplicar

1. publique `@UISchema` no backend
2. consuma schema via `GenericCrudService`
3. informe `resourcePath` e deixe o runtime compor tela e dados

## Referencias publicas

- repositório público: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes: `@praxisui/core`, `@praxisui/table`
