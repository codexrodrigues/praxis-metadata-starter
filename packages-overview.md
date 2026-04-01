# Visao dos Pacotes do Praxis Metadata Starter

Esta referencia resume os pacotes Java disponibilizados pelo starter e como
eles se conectam ao baseline atual da plataforma.

> Importante: esta pagina mistura o baseline canonico atual com classes
> legadas ainda presentes no codigo. Para aplicacoes novas, a fonte de verdade
> continua sendo `resource + surfaces + actions + capabilities + HATEOAS`,
> conforme `architecture-overview.md` e os guias principais.

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
- `controller.base` contem tanto o core atual resource-oriented quanto classes legadas ainda mantidas por compatibilidade

Para aplicacoes novas, priorize os controllers resource-oriented. Leia
`AbstractCrudController` apenas como referencia de legado ou migracao.

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

- o pacote contem servicos resource-oriented atuais
- tambem contem servicos CRUD legados ainda presentes no codigo

Para aplicacoes novas, trate `BaseCrudService` como referencia historica do
core legado, nao como baseline recomendado.

## `util`

Utilitarios de suporte, incluindo:

- `OpenApiGroupResolver`
- `OpenApiUiUtils`
- localizacao e helpers gerais

## Como usar esta visao

1. identifique o pacote relevante
2. confira `architecture-overview.md` para entender o papel dele no baseline atual
3. use os guias principais para onboarding
4. use o Javadoc quando precisar aprofundar detalhes de API ou classes legadas
