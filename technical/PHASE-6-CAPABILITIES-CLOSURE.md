# Fechamento da Fase 6 - Capabilities Unificadas

## Status

A Fase 6 de capabilities unificadas esta concluida no `praxis-metadata-starter`.

O eixo de `capabilities` ja esta implementado como camada agregadora sobre contratos canonicos
ja publicados, sem redefinir payload, sem schema inline e sem reabrir o problema de shadow API.

## O que a fase entregou

- `CapabilitySnapshot` como snapshot unificado de:
  - `canonicalOperations`
  - `surfaces`
  - `actions`
- `CanonicalCapabilityResolver` para resolver o mapa canonico de operacoes publicadas a partir do
  OpenAPI agrupado
- `CapabilityService` / `DefaultCapabilityService` separando:
  - capabilities de colecao
  - capabilities de item
- discovery contextual no core HTTP em:
  - `GET /{resource}/capabilities`
  - `GET /{resource}/{id}/capabilities`
- agregacao canonicamente disciplinada:
  - colecao -> apenas `surfaces` de `COLLECTION` e `actions` de `COLLECTION`
  - item -> apenas `surfaces` de `ITEM` e `actions` de `ITEM`
- integracao do mesmo resolvedor canonico em `ApiDocsController` para manter
  `x-ui.resource.capabilities` alinhado ao agregado unificado
- comportamento defensivo para catalogos ausentes:
  - recurso valido sem `surfaces` publicadas -> lista vazia
  - recurso valido sem `actions` publicadas -> lista vazia
  - sem inventar semantica paralela
- hardening final do calculo de `update`:
  - workflow-like `PATCH` em `/{id}/actions/...`
  - aliases `/{id}:action`
  - nao contaminam mais a capability resource-oriented de `update`

## Regras canonicamente fixadas

- `capabilities` nao define schema, fields ou payload inline
- `capabilities` apenas agrega:
  - operacoes canonicas presentes
  - `surfaces` disponiveis
  - `actions` disponiveis
- `CapabilitySnapshot.group` representa o grupo OpenAPI canonico resolvido por `resourcePath`
- `CapabilitySnapshot.group` nao representa o agrupamento documental agregado de `@ApiGroup`
- `update` no eixo de capabilities continua sendo semantica resource-oriented
- workflow actions nao podem contaminar `update` so por usarem `PATCH`

## Validacao que fecha a fase

Cobertura focal principal validada no starter:

- `OpenApiCanonicalCapabilityResolverTest`
- `CapabilityServiceTest`
- `CapabilityE2ETest`
- `AbstractResourceControllerJpaWriteIntegrationTest`
- `SurfaceCatalogE2ETest`
- `ActionCatalogE2ETest`
- `ApiDocsControllerTest`

Primeiro corte da Fase 6:

- `BUILD SUCCESS`
- `61 testes`
- validou:
  - resolver canonico
  - agregador de capabilities
  - fixture E2E real
  - piloto JPA interno
  - regressao lateral em `surfaces`, `actions` e `ApiDocsController`

Hardening curto da reabertura da fase:

- `BUILD SUCCESS`
- `22 testes`
- endureceu explicitamente:
  - exclusao de workflow-like `PATCH` do calculo de `update`
  - semantica publica de `CapabilitySnapshot.group`
  - regressao em `ApiDocsController` para `x-ui.resource.capabilities`

QA independente:

- rodada principal sem blockers
- rodada de hardening sem blockers

## O que ficou fora por escopo

- campo separado para distinguir grupo individual do recurso e agrupamento documental agregado
- suite integral do modulo
- perfil `e2e-pg` com PostgreSQL/Testcontainers
- migracao do primeiro consumidor externo

Esses pontos nao bloqueiam o encerramento da Fase 6. Eles pertencem a governanca futura, hardening
transversal ou ao proximo ciclo de migracao.

## Saida formal da fase

Com a Fase 6 encerrada:

- o starter ja possui snapshot unificado de capabilities sobre operacoes canonicas, `surfaces` e
  `actions`
- o calculo canonicamente publicado em `/capabilities` e em `x-ui.resource.capabilities` ficou
  alinhado
- a base 1-6 do plano arquitetural esta completa dentro do `praxis-metadata-starter`
- o proximo passo canonicamente correto deixa de ser evolucao interna do starter e passa a ser a
  migracao do primeiro consumidor externo
