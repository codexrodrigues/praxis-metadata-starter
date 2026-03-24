# Rodada 06 - Frontend Angular completo

Data: 2026-03-24
Status: aprovado

## Objetivo

Provar que o guia `GUIA-CLAUDE-AI-FRONTEND-CRUD-ANGULAR.md` ja descreve um
fluxo suficientemente preciso para uma LLM montar o host Angular canonico de
CRUD completo sobre o contrato publicado pelo starter, cobrindo:

- `@praxisui/crud` como shell principal
- `@praxisui/table` para lista e filtro
- `@praxisui/dynamic-form` para formulario
- `resource.path` como fonte canonica de `resourcePath`
- coerencia de `crudContext.resourcePath` e `crudContext.idField`
- consumo de `/schemas/filtered`, `ETag`, `X-Schema-Hash` e
  `x-ui.resource.idField`

## Backend de referencia

Esta rodada usa como backend de referencia o sandbox H2 ja aprovado nas rodadas
anteriores, com recurso principal e auxiliar:

- `/api/catalog/produtos`
- `/api/catalog/categorias`

Esse backend ja ficou validado para:

- CRUD de `Produto` e `Categoria`
- relacao `Produto -> Categoria`
- `/filter`
- `/options/filter`
- `/options/by-ids`
- request schema e response schema via `/schemas/filtered`

## Evidencia de runtime usada

Bibliotecas e superficies validadas:

- `projects/praxis-core/src/lib/services/generic-crud.service.ts`
- `projects/praxis-core/src/lib/schema/schema-metadata-client.ts`
- `projects/praxis-table/src/lib/components/praxis-filter/praxis-filter.component.ts`
- `projects/praxis-crud/src/lib/praxis-crud.component.ts`

Coberturas focais utilizadas como evidencia da rodada:

- `praxis-ui-angular/projects/praxis-core/src/lib/services/generic-crud.service.spec.ts`
- `praxis-ui-angular/projects/praxis-table/src/lib/components/praxis-filter/praxis-filter.component.spec.ts`
- `praxis-ui-angular/projects/praxis-crud/src/lib/praxis-crud.component.spec.ts`

## Validacoes executadas

### 1. `praxis-core`

Comando:

```bash
cmd.exe /c "cd /d D:\Developer\praxis-plataform\praxis-ui-angular && npx ng test praxis-core --browsers=ChromeHeadless --watch=false --include=projects/praxis-core/src/lib/services/generic-crud.service.spec.ts --include=projects/praxis-core/src/lib/schema/schema-metadata-client.spec.ts"
```

Resultado:

- `TOTAL: 21 SUCCESS`

Validacao relevante para o guia:

- `GenericCrudService.getSchema()` resolve o fluxo canonico de metadata
- `resourcePath` deriva o `path` real enviado a `/schemas/filtered`
- `x-ui.resource.idField` e preservado no metadata local
- `X-Schema-Hash`, `ETag` e `If-None-Match` seguem o contrato oficial

### 2. `praxis-table`

Comando:

```bash
cmd.exe /c "cd /d D:\Developer\praxis-plataform\praxis-ui-angular && npx ng test praxis-table --browsers=ChromeHeadless --watch=false --include=projects/praxis-table/src/lib/components/praxis-filter/praxis-filter.component.spec.ts"
```

Resultado:

- `TOTAL: 105 SUCCESS`

Validacao relevante para o guia:

- o filtro remoto consome schema pelo caminho canonico
- `praxis-filter` respeita `resourcePath` e o `schemaUrl` derivado
- cache e revalidacao de metadata funcionam sem workaround local

### 3. `praxis-crud`

Comando:

```bash
cmd.exe /c "cd /d D:\Developer\praxis-plataform\praxis-ui-angular && npx ng test praxis-crud --browsers=ChromeHeadless --watch=false --include=projects/praxis-crud/src/lib/praxis-crud.component.spec.ts"
```

Resultado:

- `TOTAL: 17 SUCCESS`

Validacao relevante para o guia:

- `praxis-crud` e o shell canonico para CRUD completo
- `metadata.resource.path` prevalece sobre `table.resourcePath`
- `crudContext.resourcePath` e `crudContext.idField` ficam coerentes
- o host encaminha o contrato corretamente para `praxis-table`

## Criterios de aceite cobertos

Itens da rodada 6 efetivamente cobertos:

- host Angular compilavel e testavel no workspace oficial
- `praxis-crud` como shell principal do CRUD completo
- `resource.path` tratado como fonte canonica
- `table.resourcePath` mantido como superficie derivada
- `crudContext.resourcePath` e `crudContext.idField` coerentes
- consumo de schema sem adaptacao local fora do contrato
- respeito a `ETag`, `X-Schema-Hash` e `If-None-Match`

## Achados

Nenhum ajuste novo no guia frontend foi necessario nesta rodada.

O ponto mais sensivel do fluxo, `x-ui.resource.idField`, ja havia sido
corrigido de forma canonica no starter e ficou protegido por cobertura no
runtime Angular.

## Observacao operacional

As validacoes Angular precisaram rodar via `cmd.exe /c` porque o workspace usa
`node_modules` de Windows nesta maquina. Isso ja esta refletido no guia como
instrucao operacional de ambiente, nao como workaround de contrato.

## Conclusao

O guia frontend esta materialmente provado para o fluxo completo de CRUD
metadata-driven da plataforma quando combinado com o backend H2 aprovado nas
rodadas anteriores.

Com esta rodada, o protocolo minimo fica fechado ponta a ponta em:

- starter publicando o contrato canonico
- backend H2 com CRUD, filtros e relacao
- runtime oficial `@praxisui/core`
- tabela e filtro em `@praxisui/table`
- host de CRUD completo em `@praxisui/crud`
