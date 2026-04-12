# Rodada 05 - Consumo Angular real (`praxis-core` + `praxis-table`)

Data: 2026-03-24
Status: aprovado

## Objetivo

Provar que o fluxo gerado nas rodadas H2 anteriores e o contrato publicado por
`praxis-metadata-starter 5.0.0-rc.2` sao consumidos corretamente pelo runtime
oficial Angular, cobrindo:

- resolucao de `resourcePath`
- bootstrap via `/schemas/filtered`
- propagacao de `x-ui.resource.idField`
- cache com `ETag` / `X-Schema-Hash`
- reuso via `If-None-Match`
- consumo do schema de filtro por `praxis-filter`

## Evidencia de codigo validada

- `projects/praxis-core/src/lib/services/generic-crud.service.ts`
- `projects/praxis-core/src/lib/schema/schema-metadata-client.ts`
- `projects/praxis-table/src/lib/components/praxis-filter/praxis-filter.component.ts`

## Ajuste de cobertura realizado

Foi adicionada uma spec focal em:

- `praxis-ui-angular/projects/praxis-core/src/lib/services/generic-crud.service.spec.ts`

Essa spec valida explicitamente que:

- `getSchema()` deriva `path=/api/catalog/products/all`
- o schema recebido com `x-ui.resource.idField = id` atualiza o metadata local
- `X-Schema-Hash` prevalece sobre `ETag`
- a segunda chamada envia `If-None-Match`
- o caminho `304` reaproveita o cache sem perder `idField`

## Validacoes executadas

### 1. `praxis-core` focal

Comando:

```bash
cmd.exe /c "cd /d D:\Developer\praxis-plataform\praxis-ui-angular && npx ng test praxis-core --browsers=ChromeHeadless --watch=false --include=projects/praxis-core/src/lib/services/generic-crud.service.spec.ts --include=projects/praxis-core/src/lib/schema/schema-metadata-client.spec.ts"
```

Resultado:

- `TOTAL: 21 SUCCESS`

Cobertura relevante confirmada:

- `GenericCrudService.getSchema()` com `idField` canonico + cache/304
- `SchemaMetadataClient` com `X-Schema-Hash`, `ETag`, `304` e base URL relativa

### 2. `praxis-table` focal

Comando:

```bash
cmd.exe /c "cd /d D:\Developer\praxis-plataform\praxis-ui-angular && npx ng test praxis-table --browsers=ChromeHeadless --watch=false --include=projects/praxis-table/src/lib/components/praxis-filter/praxis-filter.component.spec.ts"
```

Resultado:

- `TOTAL: 105 SUCCESS`

Cobertura relevante confirmada:

- `praxis-filter` deriva `path=/.../filter` a partir de `resourcePath`/`schemaUrl`
- fluxo primario por `SchemaMetadataClient`
- fallback por `crud.getFilteredSchema()`
- emissao de `metaChanged` com `schemaId` e `serverHash`
- escopo de cache por contexto e por `resourcePath`

### 3. `praxis-crud` focal

Comando:

```bash
cmd.exe /c "cd /d D:\Developer\praxis-plataform\praxis-ui-angular && npx ng test praxis-crud --browsers=ChromeHeadless --watch=false --include=projects/praxis-crud/src/lib/praxis-crud.component.spec.ts"
```

Resultado:

- `TOTAL: 17 SUCCESS`

Cobertura relevante confirmada:

- o host `praxis-crud` encaminha `resourcePath` para `praxis-table`
- `metadata.resource.path` prevalece como fonte canonica sobre `table.resourcePath`
- `crudContext.resourcePath` e `crudContext.idField` permanecem coerentes com o contrato canonico
- a trilha host `praxis-crud -> praxis-table` ficou protegida por spec dedicada

## Achados

Nenhum desvio funcional novo foi encontrado nesta rodada.

O ajuste canônico anterior no starter para `x-ui.resource.idField` ficou
alinhado ao runtime Angular oficial.

Houve um bloqueio operacional inicial na suite de `praxis-crud` (`NG0908` sem
`Zone.js`). Esse bloqueio foi corrigido no workspace pelo ajuste canônico da
target de teste da lib, e a validacao foi repetida com sucesso.

## Observacao operacional importante

O workspace `praxis-ui-angular` desta maquina possui `node_modules` de Windows
(`@esbuild/win32-x64`). Por isso, as validacoes Angular precisaram rodar via
`cmd.exe /c`, e nao via WSL/Linux direto.

Isso nao indica falha do guia nem da plataforma; e apenas a regra correta de
execucao para este ambiente.

## Conclusao

Com as rodadas H2 anteriores e esta validacao Angular, o fluxo minimo proposto
pelos guias esta provado ponta a ponta para:

- host Spring com banco local H2
- contrato metadata-driven publicado pelo starter
- consumo oficial por `@praxisui/core` e `@praxisui/table`
- hospedagem oficial por `@praxisui/crud`

Neste ponto, nao houve necessidade de novo ajuste nos guias.
