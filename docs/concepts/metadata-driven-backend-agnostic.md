# Metadata-driven backend-agnostic

## Definicao curta

A UI e guiada por metadados publicados pelo backend, permitindo que diferentes
backends funcionem desde que publiquem o mesmo contrato.

## Onde aparece no Praxis

- Backend: `praxis-metadata-starter`
- Frontend: `@praxisui/core` com `GenericCrudService`
- Frontend: `@praxisui/table` usando `resourcePath`

## Como aplicar

1. padronize `/schemas/filtered` e `x-ui`
2. centralize consumo via `GenericCrudService`
3. mantenha `idField`, paginacao e filtros coerentes

## Referencias publicas

- repositório público: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes: `@praxisui/core`, `@praxisui/table`
