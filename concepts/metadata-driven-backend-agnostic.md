# Metadata-driven & Backend-agnostic

## Definição

Uma arquitetura **Metadata-driven & Backend-agnostic** desacopla a implementação interna do servidor (persistência, framework web, linguagem) dos consumidores de interface e automação por meio de um contrato declarativo intermediário baseado em padrões abertos (OpenAPI + JSON Schema + extensões semânticas).

## Como o `praxis-metadata-starter` implementa

- **Contrato neutro em JSON**: toda a inteligência das anotações Java (`@ApiResource`, `@ResourceIntent`, `@UISchema`, `@Filterable`, `@UiSurface`, `@WorkflowAction`, `@DomainGovernance`) é compilada em documentos HTTP padronizados (`/v3/api-docs`, `/schemas/filtered`, `/schemas/catalog`, `/schemas/domain`, `/schemas/surfaces`, `/schemas/actions` e `/{resource}/capabilities`).
- **Independência de consumidor**:
  - O runtime Angular oficial (`@praxisui/*`) consome esses endpoints sem acoplamento a classes Java.
  - O **`/praxis/cockpit`** consome exatamente os mesmos endpoints usando JavaScript puro no navegador.
  - Agentes de IA e o `praxis-config-starter` ingerem `/schemas/domain` e `/schemas/catalog` para grounding sem precisar ler o código-fonte do host.
- **Operações padronizadas**: convenções canônicas de paginação, ordenação, `POST .../filter`, `/options/filter`, `/options/by-ids`, `/stats/*` e `/actions/{actionKey}` permitem que clientes genéricos operem qualquer recurso novo imediatamente.

## Referências internas

- [Visão geral da arquitetura](../architecture-overview.md)
- [Guia de AI Backend — Aplicação Nova](../guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md)

## Referências públicas

- [OpenAPI Initiative](https://www.openapis.org/)
- [Martin Fowler — Richardson Maturity Model](https://martinfowler.com/articles/richardsonMaturityModel.html)
