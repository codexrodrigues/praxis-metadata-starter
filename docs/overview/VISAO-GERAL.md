# Visao Geral - Praxis Metadata Starter

O `praxis-metadata-starter` publica o contrato canonico metadata-driven do
backend Praxis.

Hoje esse contrato nao deve mais ser entendido apenas como "DTO anotado + CRUD".
O baseline atual da plataforma combina:

- OpenAPI enriquecido com `x-ui`
- `/schemas/filtered` como contrato estrutural
- `/schemas/catalog` como superficie documental
- `/schemas/surfaces` e `/schemas/actions` como discovery semantico
- `GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities`
- `RestApiResponse` com HATEOAS efetivo

## Problema que resolvemos

- duplicacao de semantica entre back e front
- contratos rigidos e dificeis de evoluir
- descoberta pobre do que o recurso pode fazer agora
- excesso de convencoes implícitas no onboarding

## Abordagem

- self-describing APIs: o backend publica o contrato com extensoes `x-ui`
- resource-oriented backend: o recurso define payload e schema canonicos
- semantic discovery: surfaces, actions e capabilities ficam explicitos
- schema-driven UI: o runtime consome o contrato estrutural sem DSL paralelo

## Como funciona

1. o backend publica o recurso canonico com `@ApiResource(value = ..., resourceKey = ...)`
2. o starter enriquece o OpenAPI e resolve grupos por `path`
3. o runtime consome `/schemas/filtered`
4. clientes semanticos podem consumir `/schemas/surfaces`, `/schemas/actions` e `/{resource}/capabilities`

## Beneficios praticos

- menos boilerplate estrutural
- fronteira mais clara entre contrato estrutural e discovery semantico
- evolucao de schema com `ETag` e `X-Schema-Hash`
- semantica de recurso, acao e UX publicada de forma governada

## Passos rapidos

1. siga `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
2. modele o primeiro recurso no baseline resource-oriented
3. valide `/schemas/filtered`, `/schemas/catalog`, `/schemas/surfaces`, `/schemas/actions` e capabilities resource-scoped
4. integre o host Angular oficial com o contrato publicado

## Leitura recomendada

- `../architecture-overview.md`
- `../guides/README.md`
- `../guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `../guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
