# Guia 04 - Quando usar Resource, Surface, Action e Capability

## Objetivo

Este guia evita o erro mais comum na adocao do baseline atual: misturar os papeis de
`resource`, `surface`, `action` e `capability`.

## Resource

Use `resource-oriented` quando a operacao e parte do contrato canonico do recurso.

Entram aqui:

- `GET /{id}`
- `GET /all`
- `POST /`
- `PUT /{id}`
- `DELETE /{id}`
- `POST /filter`
- `PATCH /{id}/profile` quando isso ainda for manutencao do recurso

Regra:

- `resource` define o contrato real
- `resource` e a fonte da verdade de payload e schema
- `/schemas/filtered` e a superficie estrutural canonica desse contrato

## ResourceIntent

Use `@ResourceIntent` quando um `PATCH` continua sendo resource-oriented, mas com semantica propria.

Exemplo:

- `PATCH /employees/{id}/profile`

Regra:

- continua sendo `resource`
- o `intent` nomeia a manutencao parcial
- nao vira workflow so porque tem nome proprio

## UiSurface

Use `@UiSurface` quando a UI precisa descobrir semanticamente uma experiencia sobre uma operacao real.

Entram aqui:

- formulario parcial
- view detalhada
- projecao de leitura

Regra:

- `surface` nao define payload
- `surface` aponta para operacao real + schema canonico
- o schema continua vindo de `/schemas/filtered`, nao de `/schemas/surfaces`
- para colecoes relacionadas, `surface.relatedResource` descreve o filho, o binding do pai,
  selecao e operacoes da colecao filha; ainda assim nao cria schema paralelo
- `surface.relatedResource` deve informar o trio `childResourceKey`, `childResourcePath` e
  `childParentField`; `childOperations` deve listar somente operacoes realmente publicadas por HTTP
- `responseCardinality` descreve a cardinalidade da resposta da operacao (`OBJECT`,
  `COLLECTION`, `PAGE`, `VOID` ou `UNKNOWN`), sem criar um segundo schema
- `ITEM` em `/schemas/surfaces` e discovery-only sem `resourceId`; a availability real vem de
  `GET /{resource}/{id}/surfaces`

Exemplo importante:

- `GET /employees/{id}/hero-profile` pode ser uma surface `ITEM` com
  `responseCardinality = OBJECT`
- `GET /employees/{id}/payroll-history` tambem pode ser uma surface `ITEM`, mas com
  `responseCardinality = COLLECTION`, porque projeta uma colecao relacionada ao funcionario
- `GET /employees/{id}/certifications` pode publicar `relatedResource.childResourceKey`,
  `childParentField = employeeId`, `selectable = true` e `childOperations = [LIST]`
  para que o runtime saiba renderizar a lista filha; inclua `CREATE` ou `DELETE` somente quando
  houver endpoints HTTP reais publicados para esses comandos da colecao relacionada

Essa distincao evita que runtimes precisem inferir se devem renderizar um form de leitura,
uma tabela, uma lista ou uma pagina a partir apenas de `kind` e `scope`.

## WorkflowAction

Use `@WorkflowAction` quando a operacao e um comando de negocio explicito.

Entram aqui:

- `approve`
- `reject`
- `resubmit`
- `bulk-approve`

Regra:

- `action` nao e CRUD
- `action` nao e patch de manutencao do recurso
- execucao sempre por endpoint tipado real
- `/schemas/actions` faz discovery semantico; nao publica um segundo contrato de payload
- `ITEM` em `/schemas/actions` e discovery-only sem `resourceId`; a availability real vem de
  `GET /{resource}/{id}/actions`

## Capability

Use `GET /{resource}/capabilities` ou `GET /{resource}/{id}/capabilities` quando o cliente precisa de um snapshot agregado do que pode ser feito agora.

O snapshot agrega:

- operacoes canonicas publicadas
- `surfaces`
- `actions`
- detalhes governados de `export`, quando o service publica suporte real a exportacao de colecao

Regra:

- `capability` nao substitui catalogos dedicados
- `capability` nao define schema inline
- `capability` agrega ausencia de `surfaces` e `actions` como listas vazias, mas os catalogos
  dedicados mantem sua propria semantica (`surfaces` automaticas; `actions` `404` sem workflow)
- `capability` pode anunciar formatos, escopos, limites e async de exportacao, mas a execucao,
  seguranca, filtros e allowlist de campos continuam no service do recurso

## Regra pratica de decisao

Pergunta 1: a operacao altera ou le o recurso como parte do contrato principal?

- sim -> `resource`

Pergunta 2: a operacao ainda e manutencao do recurso, mas parcial ou com UX propria?

- sim -> `resource` + opcionalmente `@ResourceIntent` + `@UiSurface`

Pergunta 3: a operacao e um comando de negocio explicito?

- sim -> `@WorkflowAction`

Pergunta 4: a UI so precisa saber o que existe ou o que esta disponivel agora?

- catalogo semantico especifico -> `surfaces` ou `actions`
- visao agregada -> `capabilities`

## Exemplos rapidos

- `PATCH /employees/{id}/profile` -> `resource` + `@ResourceIntent` + opcionalmente `@UiSurface`
- `POST /employees/{id}/actions/approve` -> `@WorkflowAction`
- `GET /employees/{id}/surfaces` -> discovery semantico contextual
- `GET /employees/{id}/actions` -> discovery de workflow contextual
- `GET /employees/{id}/capabilities` -> snapshot agregado contextual
- `POST /employees/export` -> operacao canonica de colecao, com contrato detalhado em
  [Exportacao de Colecoes](COLLECTION-EXPORT.md)

## Disponibilidade Contextual

O baseline distingue **descoberta** (o que existe) de **disponibilidade** (o que esta permitido agora).

- `allowedStates` e `requiredAuthorities` (nas anotacoes `@UiSurface` e `@WorkflowAction`) declaram restricoes estaticas.
- Quando a disponibilidade depende de regras dinamicas, datas, motor de regras ou multiplos campos, o host deve fornecer:
  - Um `ResourceStateSnapshotProvider` (para informar o estado atual do recurso)
  - Ou `ActionAvailabilityRule` / `SurfaceAvailabilityRule` customizadas

A UI obtem a disponibilidade real via `GET /{resource}/{id}/capabilities` (campo `available` / `availability.decision`) e ajusta a interface (ocultar, desabilitar, tooltip).

Detalhes de implementacao e exemplos estao no `QuickstartResourceStateSnapshotProvider` do `praxis-api-quickstart` e nos Javadocs das classes de availability do starter.
