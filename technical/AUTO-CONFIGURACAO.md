# Auto-Configuracao do Praxis Metadata Starter

## Status do documento

Este documento descreve a auto-configuracao viva do starter no baseline atual.

Para narrativa publica e onboarding, consulte primeiro:

- `README.md`
- `docs/index.md`
- `docs/architecture-overview.md`

## Visao geral

O starter sobe duas auto-configuracoes complementares:

- `PraxisMetadataAutoConfiguration`
- `OpenApiUiSchemaAutoConfiguration`

Juntas, elas registram:

- infra base do starter
- beans de resolucao OpenAPI enriquecida
- endpoints de docs e discovery
- suporte a grupos OpenAPI e schema filtering

## Objetivo pratico

Ao adicionar a dependencia Maven, a aplicacao passa a ter:

- OpenAPI enriquecido com `x-ui`
- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- resolucao de grupos OpenAPI por path

Sem boilerplate manual para os beans principais.

## Propriedades relevantes

| Propriedade | Padrao | Descricao |
|-------------|--------|-----------|
| `praxis.query.by-ids.max` | `200` | Limite de IDs aceitos por `GET /{resource}/by-ids`. |
| `praxis.pagination.max-size` | `200` | Tamanho maximo de pagina nos endpoints paginados. |
| `app.openapi.internal-base-url` | vazio | Origem interna explicita para consultas server-side ao SpringDoc. |

## O que cada auto-configuracao faz

### `PraxisMetadataAutoConfiguration`

- registra infraestrutura base
- publica grupos `praxis-metadata-infra` e `application`
- participa do bootstrap do scanning e do agrupamento OpenAPI

### `OpenApiUiSchemaAutoConfiguration`

- registra `RestTemplate` interno
- registra `ObjectMapper` dedicado
- registra `CustomOpenApiResolver`
- registra `GenericSpecificationsBuilder`
- registra `OpenApiGroupResolver`

## Fluxo de inicializacao

1. Spring Boot detecta as auto-configuracoes.
2. `PraxisMetadataAutoConfiguration` registra a base.
3. `OpenApiUiSchemaAutoConfiguration` registra os beans funcionais.
4. `DynamicSwaggerConfig` monta os grupos OpenAPI.
5. `ApiDocsController` e os catalogos ficam disponiveis.

## Exemplo de uso

```java
@ApiResource(value = ApiPaths.HR_EVENTOS_FOLHA, resourceKey = "hr.eventos-folha")
@ApiGroup("human-resources")
public class EventosFolhaController
    extends AbstractResourceController<
        EventoFolha,
        EventoFolhaResponseDTO,
        Long,
        EventoFolhaFilterDTO,
        CreateEventoFolhaDTO,
        UpdateEventoFolhaDTO> {

    public EventosFolhaController(EventosFolhaService service) {
        super(service);
    }
}
```

## Base interna do OpenAPI

Os endpoints internos que consultam o SpringDoc resolvem a base nesta ordem:

1. `app.openapi.internal-base-url`
2. contexto HTTP atual

Use configuracao explicita quando a aplicacao estiver atras de proxy, gateway ou
quando a origem interna diferir da origem publica.

Exemplo:

```properties
app.openapi.internal-base-url=http://localhost:4003
```

## Regras de leitura

- o baseline canonico do starter e resource-oriented
- a auto-configuracao nao deve ser explicada como "CRUD generico com metadata"
- docs e discovery sao parte do contrato publico, nao um extra opcional

## Referencias

- [PraxisMetadataAutoConfiguration.java](../../src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java)
- [OpenApiUiSchemaAutoConfiguration.java](../../src/main/java/org/praxisplatform/uischema/configuration/OpenApiUiSchemaAutoConfiguration.java)
- [DynamicSwaggerConfig.java](../../src/main/java/org/praxisplatform/uischema/configuration/DynamicSwaggerConfig.java)
- [README](../../README.md)
