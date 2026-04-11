# Rollback e Observabilidade do Piloto

## Objetivo

Definir o minimo operacional antes de migrar o primeiro consumidor externo.

## Estrategia de rollback

Regra principal:

- nao criar contratos paralelos permanentes como estrategia de seguranca

Se o piloto falhar:

- antes do merge: revert da branch
- depois do merge ou deploy: revert da entrega do host

O rollback nao deve significar "manter modelo anterior e atual coexistindo indefinidamente".

## O que observar

Endpoints e superficies principais:

- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- `/capabilities`

Headers e sinais importantes:

- `ETag`
- `X-Schema-Hash`
- `X-Data-Version`
- `Location` em `POST`

## Sintomas de regressao

- `schemaUrl` nao resolvendo
- `resourceKey` incoerente
- `surface` ou `action` aparecendo no catalogo errado
- `capabilities` divergindo dos catalogos dedicados
- `@Valid` deixando de responder `400`
- `update` canonico sendo contaminado por workflow-like mapping

## Resposta minima a incidente

1. confirmar se a regressao esta no starter ou no host
2. isolar o recurso piloto
3. reverter a entrega se houver impacto de contrato
4. registrar o desvio antes de reabrir a migracao

