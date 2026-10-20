# Self-describing APIs

## Definição

Uma **Self-describing API** (API autodescritiva) publica não apenas dados de negócio, mas também a estrutura, a semântica operacional, as regras de validação, as superfícies disponíveis e as ações permitidas para cada recurso e instância em tempo de execução.

## Como o `praxis-metadata-starter` implementa

No ecossistema Praxis, uma API autodescritiva combina **cinco camadas complementares** geradas automaticamente a partir do controller (`@ApiResource`), do serviço e dos DTOs:

1. **Superfície estrutural (`/schemas/filtered`)**: resolve o schema de `request` ou `response` de qualquer operação OpenAPI enriquecido com `x-ui`, validações Jakarta e `schemaId`/`ETag`.
2. **Catálogo semântico e de discovery (`/schemas/catalog`, `/schemas/domain`)**: publica o inventário de recursos, operações, relacionamentos navegáveis, resumos de `request`/`response` e governança de domínio (`@DomainGovernance`) para descoberta humana e grounding de agentes de IA.
3. **Descoberta de experiências e comandos (`/schemas/surfaces`, `/schemas/actions`)**: expõe projeções de leitura/edição (`@UiSurface`) e comandos de negócio (`@WorkflowAction`) sem acoplar o cliente a rotas fixas.
4. **Snapshot operacional agregado (`GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities`)**: consolida operações canônicas, surfaces, actions e disponibilidade contextual para a coleção ou para o registro específico.
5. **Hipermídia e concorrência (`HATEOAS` + `ETag` + `X-Data-Version`)**: entrega links acionáveis (`_links`) e cabeçalhos de revalidação para clientes HTTP e runtimes dinâmicos.

## Exemplo de inspeção rápida

Além dos endpoints JSON, o starter embute o **`/praxis/cockpit`**, que lê as próprias superfícies autodescritivas da aplicação e materializa tabelas, filtros, formulários, gráficos e comandos sem exigir frontend dedicado.

## Referências internas

- [Visão geral da arquitetura](../architecture-overview.md)
- [Quando usar Resource, Surface, Action e Capability](../guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md)
- [Do CRUD ao contrato semântico](../guides/GUIA-05-DO-CRUD-AO-CONTRATO-SEMANTICO.md)

## Referências públicas

- [OpenAPI Specification](https://spec.openapis.org/oas/latest.html)
- [JSON Hyper-Schema](https://json-schema.org/draft/2019-09/json-schema-hypermedia.html)
- [Hydra Core Vocabulary](https://www.hydra-cg.com/spec/latest/core/)
