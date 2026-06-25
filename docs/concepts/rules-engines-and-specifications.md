# Rules Engines and Specifications

## Definicao curta

Usar regras canônicas e materializações de runtime para expressar
comportamento, visibilidade, validacao e efeitos visuais sem espalhar logica
imperativa.

## Onde aparece no Praxis

- Frontend: contratos Json Logic em `@praxisui/core`
- Frontend: `@praxisui/table`
- Frontend: `@praxisui/visual-builder`
- Backend: specifications no ecossistema Spring Data

## Referencias publicas

- repositório público: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes: `@praxisui/core`, `@praxisui/table`, `@praxisui/visual-builder`, `@praxisui/table-rule-builder`

## Pacotes aposentados

`@praxisui/specification` e `@praxisui/specification-core` foram removidos do
inventario canônico do workspace Angular. Novas integrações devem usar Json
Logic serializável e os runtimes/editores ativos.
