# Visao dos Pacotes do Praxis Metadata Starter

Esta referencia resume os pacotes Java disponibilizados pelo starter e como
eles se conectam ao baseline canonico atual da plataforma.

> Importante: esta pagina descreve apenas a superficie viva do starter.
> Para onboarding, a fonte de verdade continua sendo
> `resource + surfaces + actions + capabilities + HATEOAS`, conforme
> `architecture-overview.md` e os guias principais.

## `annotation`

Anotacoes que expressam semantica do backend publicado.

- `@ApiResource`
- `@ApiGroup`
- `@ResourceIntent`
- `@UiSurface`
- `@WorkflowAction`

`@UISchema` continua existindo para metadados de campo, mas nao descreve sozinha
o baseline arquitetural atual.

## `configuration`

Auto-configuracoes do starter.

- `PraxisMetadataAutoConfiguration`
- `OpenApiUiSchemaAutoConfiguration`
- `DynamicSwaggerConfig`

Essas pecas ligam resolvers, grouped OpenAPI e os controllers de docs/discovery.

## `controller`

Camada HTTP do starter.

- `controller.docs` publica `/schemas/filtered`, `/schemas/catalog`, `/schemas/surfaces` e `/schemas/actions`
- `controller.base` concentra o core resource-oriented

Os controllers-base canonicos sao:

- `AbstractResourceController`
- `AbstractReadOnlyResourceController`
- `AbstractResourceQueryController`

## `dto`

Objetos de transporte usados por controllers e services.

No baseline atual, espere separacao entre:

- response
- create
- update
- filter

## `extension`

Corpo do enriquecimento OpenAPI.

- `CustomOpenApiResolver`
- utilitarios de resolucao e anotacoes

Aqui DTOs, validacoes e metadata de recurso viram `x-ui` e metadata de operacao.

## `filter`

Infraestrutura de filtros dinamicos baseada em Specification.

- `filter.annotation`
- `filter.dto`
- `filter.specification`

## `http` e `rest`

Infraestrutura de resposta e semantica HTTP.

- `RestApiResponse`
- builders de link
- tratamento de excecao

Essas pecas sustentam a superficie publica com HATEOAS e envelopes consistentes.

## `mapper`

Configuracoes compartilhadas para mapeamento e integracao com MapStruct.

## `repository`

Infraestrutura base de acesso a dados.

## `service`

Camada de servicos base.

- `AbstractBaseQueryResourceService`
- `AbstractBaseResourceService`
- `AbstractReadOnlyResourceService`
- `BaseResourceQueryService`
- `BaseResourceCommandService`
- `BaseResourceService`

Essas interfaces e classes sustentam o baseline query/command atual do starter.

## `util`

Utilitarios de suporte, incluindo:

- `OpenApiGroupResolver`
- `OpenApiUiUtils`
- localizacao e helpers gerais

## Como usar esta visao

1. identifique o pacote relevante
2. confira `architecture-overview.md` para entender o papel dele no baseline atual
3. use os guias principais para onboarding
4. use o Javadoc publicado quando precisar aprofundar detalhes de API Java
