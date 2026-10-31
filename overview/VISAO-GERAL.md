# Visão Geral — Praxis Metadata Starter

A **Praxis** é uma **plataforma semanticamente orientada para aplicações corporativas *AI-native***, em que **recursos, regras, ações, dados e políticas são descritos uma vez** e podem ser operados por **interfaces tradicionais, interfaces generativas, agentes e integrações** — construindo aplicações que, por definição, já nascem legíveis e operáveis por IA em vez de acoplar IA ao lado de código opaco.

Dentro dessa arquitetura, o `praxis-metadata-starter` publica o contrato canônico de recursos, esquemas, superfícies, ações, capacidades e governança de domínio do backend Praxis para aplicações Spring Boot.

Hoje esse contrato vai muito além de "DTO anotado + CRUD". O baseline atual da plataforma combina cinco pilares (`resource + surface + action + capability + HATEOAS`) e expõe:

- **OpenAPI enriquecido com `x-ui`** resolvido por grupo e path
- **`/schemas/filtered`** como contrato estrutural canônico consumido pelo runtime de UI
- **`/schemas/catalog`** como superfície documental, de exemplos e discovery
- **`/schemas/domain`** como catálogo semântico *AI-operable* (vocabulário, conceitos, evidências e `@DomainGovernance`)
- **`/schemas/surfaces` e `/schemas/actions`** como discovery semântico de experiências (`@UiSurface`) e comandos de negócio (`@WorkflowAction`)
- **`GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities`** como snapshot contextual agregado (incluindo `operations`, `stats.fields` e `export`)
- **`POST /{resource}/export`** como operação canônica de exportação de coleções (CSV, JSON, XLSX e modo assíncrono)
- **`GET /praxis/cockpit`** como cockpit visual automático empacotado pelo próprio starter
- **`RestApiResponse`** com suporte efetivo a Spring HATEOAS (`_links`)

## Problema que resolvemos

- Duplicação constante de regras, tipos, máscaras, validações e filtros entre backend e frontend
- Contratos rígidos que exigem retrabalho manual na UI a cada evolução do modelo
- Descoberta pobre do que o recurso suporta estruturalmente vs. o que está permitido *agora* para o usuário ou registro
- Dificuldade para agentes de IA compreenderem o domínio e operarem APIs corporativas com segurança sem ler código-fonte

## Abordagem

- **Self-describing APIs**: o backend publica o contrato estrutural com extensões `x-ui` e validações sincronizadas
- **Resource-oriented backend**: o recurso define payload e schema canônicos com DTOs separados (`ResponseDTO`, `CreateDTO`, `UpdateDTO`, `FilterDTO`)
- **Semantic discovery**: `surfaces`, `actions`, `domain` e `capabilities` ficam explícitos e auditáveis
- **Schema-driven UI & AI grounding**: o runtime Angular (`@praxisui/*`), o Praxis Cockpit e agentes de IA consomem os contratos publicados sem DSL paralelo

## Como funciona na prática

1. O backend modela DTOs separados com `@Schema`, `@UISchema`, `@Filterable`, `@DomainGovernance` e Bean Validation.
2. O controller expõe o recurso canônico com `@ApiResource(value = ..., resourceKey = ...)` e `@ApiGroup`.
3. O starter enriquece o OpenAPI e publica `/schemas/filtered`, `/schemas/catalog` e `/schemas/domain`.
4. O runtime Angular consome `/schemas/filtered` (com `ETag` e `X-Schema-Hash`) e `/{resource}/capabilities` para renderizar tabelas, filtros, formulários, gráficos e ações.
5. Desenvolvedores e agentes inspecionam tudo visualmente em `/praxis/cockpit`.

## Passos rápidos

1. Siga o [Guia 01 — Aplicação Nova](../guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md).
2. Modele o primeiro recurso seguindo o [Guia 02 — Recurso Metadata-Driven](../guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md).
3. Valide `/schemas/filtered`, `/schemas/catalog`, `/schemas/domain`, `/schemas/surfaces`, `/schemas/actions`, `/{resource}/capabilities` e `/praxis/cockpit`.
4. Integre o frontend Angular oficial seguindo o [Guia 03 — Angular CRUD Completo](../guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md).

## Leitura recomendada

- [Visão Arquitetural](../architecture-overview.md)
- [Mapa de Pacotes Java](../packages-overview.md)
- [Hub dos Guias](../guides/README.md)
- [Guia 04 — Quando usar Resource, Surface, Action e Capability](../guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md)
- [Guia 05 — Do CRUD ao Contrato Semântico](../guides/GUIA-05-DO-CRUD-AO-CONTRATO-SEMANTICO.md)
