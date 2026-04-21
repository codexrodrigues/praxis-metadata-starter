# Praxis Metadata Starter

`praxis-metadata-starter` e a fonte canonica da semantica metadata-driven do backend Praxis.

Ele publica:

- OpenAPI enriquecido com `x-ui`
- `/schemas/filtered` como contrato estrutural consumido pelos runtimes
- `/schemas/catalog` como catalogo documental e de discovery
- `/schemas/surfaces` e `/schemas/actions` como discovery semantico
- `GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities` como snapshot agregado
- `POST /{resource}/export` como operacao canonica de exportacao de colecao
- envelopes `RestApiResponse` com suporte efetivo a Spring HATEOAS

Nao e apenas um gerador de CRUD. O baseline atual da plataforma e:

- `resource`
- `surface`
- `action`
- `capability`
- HATEOAS

## Public Documentation

Use estes entry points primeiro:

- Home: [docs/index.md](docs/index.md)
- Guides hub: [docs/guides/index.md](docs/guides/index.md)
- Architecture overview: [docs/architecture-overview.md](docs/architecture-overview.md)
- Conformance: [docs/spec/CONFORMANCE.md](docs/spec/CONFORMANCE.md)
- Guide 01 - Application setup: [docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md](docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md)
- Guide 02 - Canonical resource backend: [docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md](docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md)
- Options and option-sources: [docs/guides/OPTIONS-ENDPOINT.md](docs/guides/OPTIONS-ENDPOINT.md)
- Guide 04 - When to use resource, surface, action, capability: [docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md](docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md)
- GitHub Pages site: [https://codexrodrigues.github.io/praxis-metadata-starter/](https://codexrodrigues.github.io/praxis-metadata-starter/)
- Public Javadoc: [https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/)

## What The Starter Actually Publishes

### Structural contract

`/schemas/filtered` continua sendo a superficie estrutural canonica.

Ela entrega:

- propriedades com `properties.*.x-ui`
- `x-ui.resource.idField`
- `x-ui.resource.readOnly`
- `x-ui.resource.capabilities`
- metadata de operacao
- `ETag` e `X-Schema-Hash`

### Documentary contract

`/schemas/catalog` nao substitui o contrato estrutural. Ele publica:

- resumo de operacoes
- exemplos request/response
- links canonicos para schemas estruturais
- material para docs, tooling e RAG

### Semantic discovery

O baseline atual adiciona discovery orientado a recurso:

- `GET /schemas/surfaces`
- `GET /schemas/actions`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`
- `POST /{resource}/export`

Regras importantes:

- `surfaces` e `actions` sao catalogos semanticos; nao redefinem schema inline
- `capabilities` agrega o que existe agora sem virar uma segunda fonte de verdade do contrato
- ausencia em `actions` e `capabilities` nao tem a mesma semantica
- exportacao e uma operacao de colecao; o request preserva escopo, selecao, filtros, ordenacao, campos e limites

### `capabilities.operations` como semantica minima canonica

Para o baseline novo de CRUD inferido no frontend oficial, `capabilities` precisa expor um bloco operacional minimo por acao canonica.

Shape esperado por operacao:

- `supported`
- `scope`
- `preferredMethod`
- `preferredRel`
- `availability`

Operacoes canonicas esperadas:

- `create`
- `view`
- `edit`
- `delete`

Papel de cada camada:

- `capabilities.operations` governa se a operacao existe agora e como ela deve ser tratada semanticamente
- `/schemas/filtered` continua sendo a fonte estrutural de request/response schema
- `surfaces` e `actions` continuam sendo discovery semantico rico quando publicados
- `_links` entram como camada operacional/contextual para escolher o target real de execucao

Em outras palavras:

- `capabilities` nao substitui schema
- `capabilities` nao deve carregar schema inline
- `capabilities` governa operacao; schema continua vindo do contrato estrutural e dos catalogos semanticos

### Regras de delete canonico

`delete` canÃ´nico do recurso precisa permanecer semanticamente separado de outros usos de HTTP `DELETE`.

Regras de plataforma:

- `operations.delete` representa delete canÃ´nico item-level do recurso
- `DELETE /batch` nao deve promover `operations.delete` item-level
- workflow actions de negocio com verbo HTTP `DELETE` nao devem contaminar o delete canÃ´nico
- `delete` canÃ´nico normalmente nao exige schema; ele exige suporte, escopo e target operacional validos

Isso evita que clientes metadata-driven confundam:

- exclusao de um item do recurso
- comando destrutivo de negocio
- operacao em lote

### Exportacao canonica de colecao

`POST /{resource}/export` e a superficie backend canonica consumida por componentes como Table e List.

O request publica o estado de colecao necessario para uma exportacao correta:

- `format`: `csv`, `json`, `excel`, `pdf` ou `print`
- `scope`: `auto`, `selected`, `filtered`, `currentPage` ou `all`
- `selection`: chaves selecionadas, chave identificadora, selecao de todos os resultados e exclusoes
- `filters`, `sort`, `pagination` e `query`
- `fields`, `includeHeaders`, `applyFormatting`, `maxRows` e `fileName`

A base `AbstractResourceQueryController` publica o endpoint e delega para
`BaseResourceQueryService.exportCollection(...)`. A implementacao padrao retorna `501 Not Implemented`;
recursos que suportam exportacao devem sobrescrever o metodo no servico e retornar `CollectionExportResult`
com `status`, `format`, `scope` e o artefato produzido. Para exportacoes pequenas, `status=completed`
retorna bytes inline com nome de arquivo, content type e, opcionalmente, `rowCount`. Para exportacoes
corporativas grandes, `status=deferred` retorna `202 Accepted` em JSON com `downloadUrl`, `jobId`,
`warnings` e `metadata` para filas, retencao e governanca do host.

Limites publicados em `CollectionExportCapability` devem ser tratados como politica do servidor, nao
como preferencia do cliente. Quando uma exportacao inline for limitada ou truncada, o controller pode
propagar metadados operacionais em headers como `X-Export-Truncated`, `X-Export-Max-Rows`,
`X-Export-Candidate-Row-Count` e `X-Export-Warnings`. Campos solicitados que nao pertencem ao allowlist
exportavel do recurso devem falhar com erro claro, em vez de cair silenciosamente para todos os campos
padrao.

Quando um recurso suportar exportacao, ele pode publicar `CollectionExportCapability` no servico. O snapshot
`GET /{resource}/capabilities` entao preenche `operations.export` com os formatos, escopos, limites e modo
sincrono/assincrono realmente disponiveis:

```json
{
  "operations": {
    "export": {
      "supported": true,
      "scope": "COLLECTION",
      "preferredMethod": "POST",
      "preferredRel": "export",
      "formats": ["csv", "json"],
      "scopes": ["auto", "selected", "filtered", "currentPage", "all"],
      "maxRows": {
        "csv": 500,
        "json": 500
      },
      "async": false
    }
  }
}
```

O starter tambem publica uma SPI reutilizavel para recursos que querem manter a responsabilidade
local apenas sobre consulta, permissao e campos:

- `CollectionExportExecutor`: resolve o engine pelo `format` solicitado e monta o `CollectionExportResult`
- `CollectionExportEngine`: contrato de serializacao por formato
- `CsvCollectionExportEngine`: engine padrao para CSV com headers opcionais e protecao contra formula injection
- `JsonCollectionExportEngine`: engine padrao para JSON tabular preservando a ordem dos campos
- `CollectionExportValueResolver`: funcao do recurso para mapear linha + campo em valor exportavel

Os formatos `excel`, `pdf` e `print` permanecem no contrato publico para que consumidores possam expressar
a intencao, mas exigem engine registrado pelo host ou por versao futura do starter. Na ausencia de engine,
o executor rejeita o formato com `400 Bad Request` pela mesma trilha de validacao do endpoint base.

## Canonical Backend Baseline

O baseline canonico para recursos metadata-driven e:

- `AbstractResourceController`
- `AbstractReadOnlyResourceController`
- `AbstractBaseResourceService`
- `AbstractReadOnlyResourceService`
- `ResourceMapper`
- `@ApiResource(value = ..., resourceKey = ...)`

Adote DTOs separados:

- `ResponseDTO`
- `CreateDTO`
- `UpdateDTO`
- `FilterDTO`

Use `@UiSurface` quando a UX precisa descobrir semanticamente uma experiencia real.

Use `@WorkflowAction` quando a operacao for um comando de negocio explicito.

## What `resourceKey` Actually Means

`resourceKey` e a identidade semantica estavel do recurso na plataforma.

Diferenca pratica:

- `path` ou `resourcePath` diz onde o endpoint vive, por exemplo `/api/human-resources/employees`
- `resourceKey` diz o que o recurso e para discovery e agregacao, por exemplo `human-resources.employees`

Regra de plataforma:

- o `path` pode mudar por reorganizacao de URL, proxy, versionamento ou host
- o `resourceKey` nao deve mudar apenas porque a URL mudou

No starter, `resourceKey` e usado para:

- resolver `GET /schemas/surfaces?resource={resourceKey}`
- resolver `GET /schemas/actions?resource={resourceKey}`
- agregar `GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities`
- indexar availability contextual de surfaces e actions

Por isso, `@ApiResource(value = ..., resourceKey = ...)` nao e so decoracao. Ele define:

- a URL operacional do recurso
- a identidade semantica que o restante da plataforma usa para discovery

## Spring HATEOAS Is Part Of The Contract

O starter usa HATEOAS de forma efetiva, nao ornamental.

Isso aparece em:

- `_links` no envelope `RestApiResponse`
- links para self e operacoes canonicas nos controllers base
- discovery contextual em recursos item-level
- integracao entre semantica de recurso e navegacao HTTP

Quando `praxis.hateoas.enabled=false`, a semantica de links e suprimida do envelope, mas o contrato estrutural de schema continua existindo.

## Quick Mental Model

```mermaid
flowchart LR
  dto["DTOs + validation + resource annotations"] --> openapi["OpenAPI enriched with x-ui"]
  openapi --> filtered["/schemas/filtered"]
  openapi --> catalog["/schemas/catalog"]
  openapi --> surfaces["/schemas/surfaces"]
  openapi --> actions["/schemas/actions"]
  surfaces --> caps["/{resource}/capabilities"]
  actions --> caps
  filtered --> runtime["praxis-ui-angular runtime"]
  catalog --> docs["docs, examples, RAG"]
  caps --> clients["semantic clients and assistants"]
```

## First Steps

1. Adicione a dependencia Maven.
2. Siga [docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md](docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md).
3. Modele o primeiro recurso com `@ApiResource(value = ..., resourceKey = ...)`.
4. Valide `/schemas/filtered`, `/schemas/catalog`, `/schemas/surfaces`, `/schemas/actions`, `GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities`.
5. Se o recurso publicar `OptionSourceRegistry`, valide tambem `POST /{resource}/option-sources/{sourceKey}/options/filter` e `GET /{resource}/option-sources/{sourceKey}/options/by-ids`.
6. Integre o host oficial com `praxis-ui-angular`.

Dependencia minima:

```xml
<dependency>
  <groupId>io.github.codexrodrigues</groupId>
  <artifactId>praxis-metadata-starter</artifactId>
  <version>8.0.0-rc.7</version>
</dependency>
```

## Public Endpoints To Validate

Para um recurso mutavel no baseline atual, valide no minimo:

- `GET /v3/api-docs/{group}`
- `GET /schemas/filtered`
- `GET /schemas/catalog`
- `GET /schemas/surfaces?resource={resourceKey}`
- `GET /schemas/actions?resource={resourceKey}` quando houver `@WorkflowAction`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`
- `POST /{resource}/export` quando o recurso suportar exportacao

Quando o frontend oficial for consumir CRUD inferido, valide tambem no payload de `capabilities`:

- `operations.create.supported`
- `operations.create.scope`
- `operations.create.preferredMethod`
- `operations.create.preferredRel`
- `operations.view.supported`
- `operations.edit.supported`
- `operations.delete.supported`
- `operations.delete.scope`
- `operations.delete.preferredMethod`

Para `delete`, confira explicitamente:

- item-level real publica `operations.delete.scope = ITEM`
- um endpoint `DELETE /batch` isolado nao promove `operations.delete`
- workflow `DELETE` continua em `actions`, nao redefine `operations.delete`

Quando houver discovery `ITEM`, valide tambem:

- `GET /{resource}/{id}/surfaces`
- `GET /{resource}/{id}/actions`

Quando o recurso publicar `OptionSourceRegistry`, valide tambem:

- `POST /{resource}/option-sources/{sourceKey}/options/filter`
- `GET /{resource}/option-sources/{sourceKey}/options/by-ids`
- `x-ui.optionSource` em `/schemas/filtered` para os campos governados por essa source

## Internal OpenAPI Base Resolution

Os endpoints internos que consultam o SpringDoc, como `/schemas/filtered`, `/schemas/catalog`, `/schemas/surfaces` e `/schemas/actions`, resolvem a base do OpenAPI nesta ordem:

1. `app.openapi.internal-base-url`
2. contexto HTTP atual

Use isso quando a aplicacao estiver atras de proxy ou numa plataforma em que a URL publica nao coincide com a origem interna do processo Java.

Exemplo:

```properties
app.openapi.internal-base-url=http://localhost:4003
```

## Read-only Resources

Read-only continua sendo um caso de primeira classe:

- controller: `AbstractReadOnlyResourceController`
- service: `AbstractReadOnlyResourceService`
- `x-ui.resource.readOnly=true`
- HATEOAS e discovery coerentes com o contrato publicado

Nao crie um contrato paralelo de read-only para contornar o baseline canonico.

## Documentation Positioning

Este README descreve apenas a superficie publica atual do starter.

Para onboarding, modelagem e validacao, siga os entry points documentados neste arquivo e em `docs/`.

## References

- Architecture overview: [docs/architecture-overview.md](docs/architecture-overview.md)
- Package map: [docs/packages-overview.md](docs/packages-overview.md)
- Guides hub: [docs/guides/index.md](docs/guides/index.md)
- Conformance: [docs/spec/CONFORMANCE.md](docs/spec/CONFORMANCE.md)
