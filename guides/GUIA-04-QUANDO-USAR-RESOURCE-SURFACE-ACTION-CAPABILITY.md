# Guia 04 - Quando usar Resource, Surface, Action e Capability

## Objetivo

Este guia evita o erro mais comum na migracao do primeiro consumidor: misturar os papeis de
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
- `ITEM` em `/schemas/surfaces` e discovery-only sem `resourceId`; a availability real vem de
  `GET /{resource}/{id}/surfaces`

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

Regra:

- `capability` nao substitui catalogos dedicados
- `capability` nao define schema inline
- `capability` agrega ausencia de `surfaces` e `actions` como listas vazias, mas os catalogos
  dedicados mantem sua propria semantica (`surfaces` automaticas; `actions` `404` sem workflow)

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

