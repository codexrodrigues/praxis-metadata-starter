# Guia 03 - Adocao do Consumidor Piloto

## Objetivo

Este guia descreve como adotar o baseline canonico do `praxis-metadata-starter`
no primeiro consumidor externo real.

O objetivo e publicar um recurso pequeno, observavel e representativo usando:

- `resource-oriented`
- `surfaces`
- `actions`
- `capabilities`

## Pre-requisitos minimos

Antes de tocar o consumidor:

- piloto interno em `src/test` do starter verde
- fixture E2E H2 do starter verde
- QA independente recente concluido
- guias e checklist do piloto publicados
- se o host ficar atras de proxy/gateway, `app.openapi.internal-base-url` decidido e validado

Referencias obrigatorias:

- `docs/architecture-overview.md`
- `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `docs/technical/PILOT-READINESS-CHECKLIST.md`
- `docs/technical/ROLLBACK-E-OBSERVABILIDADE-DO-PILOTO.md`

## Como escolher o recurso piloto

Escolha um recurso que seja:

- pequeno e isolado
- importante o suficiente para provar o baseline
- com no maximo um patch por intencao e um workflow real
- sem dependencia de integracao pesada para o primeiro corte

Evite no primeiro piloto:

- modulo com muitas relacoes acopladas
- recurso com regras de workflow altamente excepcionais
- recurso que exija contratos paralelos

## Modelagem canonica do recurso

Use DTOs separados:

- `ResponseDTO`
- `CreateDTO`
- `UpdateDTO`
- `FilterDTO`

Use a hierarquia resource-oriented:

- `AbstractResourceController` para recurso mutavel
- `AbstractReadOnlyResourceController` para recurso somente leitura
- `AbstractBaseResourceService` ou `AbstractReadOnlyResourceService` no service
- `ResourceMapper` para separar response, create e update

Modele intencoes explicitamente:

- continua sendo manutencao do recurso -> `@ResourceIntent` e opcionalmente `@UiSurface`
- vira comando de negocio -> `@WorkflowAction`

## Sequencia recomendada de adocao

1. Congelar o escopo do recurso piloto.
2. Mapear os endpoints reais que pertencem ao recurso.
3. Separar DTOs e criar `ResourceMapper`.
4. Implementar service e controller na hierarquia resource-oriented.
5. Adicionar `@ResourceIntent`, `@UiSurface` e `@WorkflowAction` so onde houver operacao real correspondente.
6. Validar discovery:
   - `/schemas/filtered`
   - `/schemas/catalog`
   - `/schemas/surfaces`
   - `/schemas/actions`
   - `GET /{resource}/capabilities`
   - `GET /{resource}/{id}/capabilities`
7. Rodar a suite focal do host e o checklist de prontidao.

## Semantica contextual de availability

Ao publicar o primeiro consumidor, nao trate os catalogos semanticos como se todos tivessem a mesma semantica:

- em `/schemas/surfaces` e `/schemas/actions`, entradas `ITEM` sao discovery-only sem `resourceId`
  concreto e podem sair com `availability.allowed=false`
- authorities, tenant e estado do recurso so fazem sentido nos endpoints contextuais como
  `GET /{resource}/{id}/surfaces`, `GET /{resource}/{id}/actions` e `GET /{resource}/{id}/capabilities`
- ausencia de workflow explicito nao gera lista vazia em `/schemas/actions`; o comportamento esperado
  e `404`
- `capabilities` agrega ausencia de `surfaces` ou `actions` como listas vazias, sem redefinir o
  contrato dos catalogos dedicados

## O que deve existir no host ao final

Para o recurso piloto escolhido:

- contrato canonico do recurso
- read-only correto, se aplicavel
- patch por intencao, se existir
- workflow tipado real, se existir
- discovery estrutural
- discovery semantico
- snapshot de capabilities

## O que nao fazer

- nao criar `v2`
- nao manter DTO unico por conveniencia
- nao criar endpoints espelho so para a UI
- nao criar dispatcher generico para workflows
- nao usar o consumidor como fonte canonica da semantica
- nao criar contratos paralelos permanentes

## Validacao minima antes do merge do piloto

- suite focal do host verde
- verificacao manual dos endpoints de discovery
- QA independente
- rollback definido
- observabilidade minima definida

## Estrategia de rollback

Se o piloto falhar:

- antes do merge: reverta a branch
- depois do merge/desdobramento: reverta a entrega do host

O rollback deve ser reversao operacional da mudanca, sem contratos paralelos permanentes.
