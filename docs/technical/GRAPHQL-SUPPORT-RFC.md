# RFC - GraphQL Support

## Status

Exploratorio. Nao implementar como contrato padrao do `praxis-metadata-starter`
sem uma fase posterior de adapter opcional e provas de consumidor.

## Contexto

A issue original pergunta se os metadados de UI e as abstracoes de service
poderiam auto-gerar esquemas GraphQL como alternativa as APIs REST.

Isso e viavel em tese, mas nao deve inverter a arquitetura da plataforma. No
Praxis, a fonte canonica continua sendo:

- resource-oriented services e controllers
- DTOs separados de response, create, update e filter
- OpenAPI enriquecido com `x-ui`
- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- `/capabilities`
- HATEOAS

GraphQL deve ser uma materializacao derivada, nao uma segunda fonte primaria de
schema, regra de negocio, autorizacao ou metadata.

## Inventario De Aderencia

| Necessidade | Aderencia atual |
| --- | --- |
| Descobrir recursos e operacoes | suportado-parcialmente |
| Descobrir campos, validacoes e apresentacao | ja-suportado-mal-nomeado-ou-mal-materializado |
| Resolver filtros/paginacao/sort | suportado-parcialmente |
| Publicar queries GraphQL derivadas | lacuna-real-de-contrato |
| Publicar mutations GraphQL derivadas | lacuna-real-de-contrato |
| Governar selecao de campos por capabilities | suportado-parcialmente |
| Explicar GraphQL para IA sem desviar de REST | lacuna-real-de-contrato |

## Direcao Recomendada

Criar futuramente um adapter opcional, por exemplo `praxis-graphql-starter`, em
vez de acoplar GraphQL diretamente ao starter metadata base.

Esse adapter deve consumir contratos existentes:

- `ApiResource` e catalogo de recursos para nomes canonicos
- `AbstractBaseResourceService` / `AbstractReadOnlyResourceService` como porta
  operacional
- DTOs canonicos para tipos GraphQL de input/output
- filtros e pageable canonicos para argumentos de query
- capabilities para decidir quais queries e mutations sao publicaveis
- surfaces/actions apenas como discovery semantico complementar, nao como
  geradores implicitos de mutation

## Fronteiras

O adapter GraphQL nao deve:

- rotear intencao por nomes de campo, palavras-chave ou regex
- inferir mutations de negocio a partir de paths parecidos
- duplicar regras de autorizacao fora de capabilities/availability
- substituir `/schemas/filtered` como contrato estrutural do runtime oficial
- tratar `x-ui` como fonte primaria de regra de negocio
- gerar GraphQL para endpoints sem `resourceKey` canonico

O adapter GraphQL pode:

- publicar queries read-only para recursos elegiveis
- publicar mutations CRUD apenas quando a operation availability canonica permitir
- mapear filtros declarados para argumentos estruturados
- usar DTOs como shape inicial dos tipos
- expor diagnostics de quais campos/operacoes foram omitidos e por que

## Shape Inicial Proposto

Uma primeira fase deveria ser read-only:

- `resource(id: ID!): Resource`
- `resources(filter: ResourceFilter, page: PageInput, sort: [SortInput!]): ResourcePage`
- tipos derivados de `ResponseDTO`
- filtros derivados de `FilterDTO`
- nenhuma mutation

Uma segunda fase poderia publicar mutations CRUD:

- `createResource(input: ResourceCreateInput!): Resource`
- `updateResource(id: ID!, input: ResourceUpdateInput!): Resource`
- `deleteResource(id: ID!): DeletePayload`

Mutations customizadas de workflow devem depender de `@WorkflowAction` e
contratos explicitos de input/output, nunca de inferencia por path.

## Validacao Minima De Uma Implementacao Futura

- teste unitario do schema registry GraphQL gerado para recurso read-only
- teste unitario de recurso mutavel com create/update/delete publicados apenas
  quando a availability permitir
- teste de filtro/paginacao/sort preservando semantica do `FilterDTO`
- teste de omissao de recurso sem `resourceKey`
- teste de diagnostics para campos ou operacoes nao publicaveis
- smoke no `praxis-api-quickstart` com HTTP real

## Decisao

A issue procede como exploracao, mas a implementacao direta dentro do
`praxis-metadata-starter` nao e o proximo passo correto. O caminho de plataforma
e especificar um adapter GraphQL opcional e derivado, mantendo REST, OpenAPI,
schemas, capabilities e HATEOAS como fonte canonica atual.
