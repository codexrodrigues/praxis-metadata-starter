# Visão dos Pacotes do Praxis Metadata Starter

Esta referência resume os pacotes Java disponibilizados pelo starter, destacando **responsabilidades**, **principais tipos** e **como cada peça se conecta aos fluxos descritos na [visão arquitetural](architecture-overview.md)**.

> 💡 Use esta página como mapa mental antes de navegar pelo Javadoc. Cada seção cita classes chave com links para facilitar a exploração.

## Pacote Raiz: `org.praxisplatform.uischema`

Abriga enums e tipos compartilhados entre as camadas.

| Tipo | Função | Notas |
|------|--------|-------|
| [`FieldConfigProperties`](../src/main/java/org/praxisplatform/uischema/FieldConfigProperties.java) | Lista oficial de chaves `x-ui` para propriedades de campos | Usada por `CustomOpenApiResolver` e pela UI |
| [`ValidationProperties`](../src/main/java/org/praxisplatform/uischema/ValidationProperties.java) | Mapeia restrições de validação e mensagens padrão | Preenchida automaticamente a partir de Bean Validation |
| `FieldControlType`, `FieldDataType`, `FieldAlignment` | Enumerações de suporte visual | Podem ser referenciadas diretamente em `@UISchema` |

## `annotation`

Fornece anotações que descrevem recursos API e comportamento de UI.

* `@ApiResource` / `@ApiGroup` — categorizam controllers REST para geração de grupos OpenAPI.
* `@UISchema` (exposta em `extension.annotation`) — descreve propriedades visuais; ver [conceito detalhado](concepts/ui-schema.md).
* `@Filterable` — identifica campos que participam de filtros dinâmicos.

## `configuration`

Auto-configurações Spring Boot que ligam todos os componentes automaticamente.

* `OpenApiUiSchemaAutoConfiguration` habilita resolvers, controllers e utilitários.
* `DynamicSwaggerConfig` registra grupos adicionais e integra com `OpenApiGroupResolver`.
* `RepositoryConfiguration` expõe beans com `JpaSpecificationExecutor` para filtros dinâmicos.

## `controller`

Camada HTTP responsável por expor operações prontas.

* `controller.base` — controllers genéricos (`AbstractCrudController`, `BaseFilterController`) com operações CRUD, paginação e filtros.
* `controller.docs` — endpoints de documentação (`ApiDocsController`) com cache e ETag. Faz a ponte entre `/v3/api-docs` e o fluxo de enriquecimento descrito em [architecture-overview.md](architecture-overview.md).

## `dto`

Objetos de transporte utilizados por controllers e services.

* `OptionDTO` — payload leve para combos e autocompletes.
* `PagedResponseDTO` — abstrai paginação padrão.
* Exemplos de uso: [Filter DTO real](examples/filter-dto.md).

## `extension`

Corpo do processo de enriquecimento OpenAPI.

* `CustomOpenApiResolver` — converte metadados de anotações e validação em `x-ui`.
* `ResolverUtils` — utilitários para leitura de anotações e classes.
* `extension.annotation` — contém `@UISchema`, organizada separadamente para minimizar dependências.

## `filter`

Infraestrutura de filtros dinâmicos baseada em Specification.

* `filter.annotation` — `@Filterable` e metadados adicionais.
* `filter.dto` — contratos de DTOs e adaptadores (por exemplo, `FilterDefinitionDTO`). Veja [exemplo detalhado](examples/filter-dto.md).
* `filter.specification` — builders de Specifications Spring Data que transformam DTOs em consultas.

## `hash`

Geração e comparação de hashes de schema.

* Utilizado por `ApiDocsController` para calcular ETag do payload filtrado.
* Extensível para persistência de versões via banco ou cache distribuído.

## `http`

Classes auxiliares para construção de respostas padronizadas.

* `RestApiResponse` — wrapper com categoria, payload e erros.
* `LinkBuilder` — utilitário para montagem de links HATEOAS baseados em rota.

## `id`

Estruturas relacionadas a identificação única e extração de IDs.

* `Identifiable` — contrato usado pelos serviços base para extrair chave primária.
* `IdExtractor` — reflexão segura para identificar getters de ID.

## `mapper`

Configurações compartilhadas para MapStruct e mapeamento de entidades.

* `mapper.base` — mapeadores genéricos (`BaseMapper`, `OptionMapper`).
* `mapper.config` — configurações corporativas (`CorporateMapperConfig`).

## `numeric`

Conversores e utilitários para campos numéricos.

* `NumberFormatStyle` — enum com estilos (percentual, moeda etc.).
* Integrado diretamente pelo `CustomOpenApiResolver`.

## `repository`

Infraestrutura de acesso a dados.

* `repository.base` — interfaces base (`BaseRepository`, `BaseReadOnlyRepository`).
* Extensível por aplicações consumidoras que desejam padronizar operações CRUD.

## `rest`

Exceções e respostas específicas da camada REST.

* `rest.exceptionhandler` — `GlobalExceptionHandler` padroniza respostas de erro.
* `rest.response` — builders auxiliares para `RestApiResponse`.

## `service`

Serviços base orientados a domínio.

* `service.base` — implementações padrão para CRUD (`BaseCrudService`) e leitura (`BaseReadOnlyService`).
* `service.base.annotation` — metadados de serviço utilizados por anotações.

## `util`

Utilitários diversos.

* `OpenApiUiUtils` — converte validações em mensagens amigáveis.
* `OpenApiGroupResolver` — estratégia para detectar grupos (`ApiDocsController` depende dele).
* `LocaleUtils` — suporte a localização para mensagens e labels.

---

### Como usar esta visão

1. Identifique o pacote relevante via tabela acima.
2. Abra o `package-info.java` correspondente para obter detalhes extras e links cruzados.
3. Consulte o Javadoc da classe desejada para obter exemplos de uso e contratos detalhados.
4. Navegue pelos [exemplos](examples/) para ver o pacote em ação.
