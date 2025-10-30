# Checklist Geral de Auditoria — Praxis Metadata Starter

Visão consolidada das fases. Cada fase possui um arquivo próprio com checklist detalhado, verificações, correções comuns, referências e um prompt pronto.

- Fase 1 — Build e Dependências
  - JDK 21 ativo, Maven Wrapper funcional, dependência do starter no(s) POM(s) e build `./mvnw -B -DskipTests package` sem erros
  - Ref.: README.md:1, docs/technical/AUTO-CONFIGURACAO.md:1

- Fase 2 — Controllers e Grupos OpenAPI
  - `@ApiResource` nos controllers base (`AbstractCrudController`), política de validação `praxis.openapi.validation.api-resource-required`, resolução automática em `/schemas/filtered`
  - Ref.: src/main/java/org/praxisplatform/uischema/annotation/ApiResource.java:1, src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:242, docs/technical/VALIDACAO-API-RESOURCE.md:1, docs/technical/ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md:1

- Fase 3 — DTOs de Entrada, Bean Validation e @UISchema
  - DTOs anotados com Jakarta Validation, `@Valid` em `create`/`update`, `@UISchema` conforme necessário; evitar `FieldDataType.STRING` (use `TEXT`)
  - Ref.: README.md:58, src/main/java/org/praxisplatform/uischema/FieldDataType.java:1, src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:682

- Fase 4 — Services, Repositórios, OptionDTO e Ordenação Padrão
  - Services implementam `BaseCrudService`/`AbstractBaseCrudService`; `OptionDTO` funciona (via `getId()`/`getOptionMapper()`), `@OptionLabel`, `@DefaultSortColumn`, `getDatasetVersion()` quando aplicável
  - Ref.: src/main/java/org/praxisplatform/uischema/service/base/AbstractBaseCrudService.java:1, src/main/java/org/praxisplatform/uischema/service/base/BaseCrudService.java:61, src/main/java/org/praxisplatform/uischema/annotation/OptionLabel.java:1

- Fase 5 — Filtros, Paginação e Opções
  - Filter DTOs com `@Filterable` (operation e relation) e `@UISchema` por campo para UX adequada em filtros:
    - Texto: LIKE + `@UISchema`
    - Numéricos (intervalo): BETWEEN + `@UISchema(type=NUMBER, controlType=RANGE_SLIDER, numericFormat/step quando moeda)`
    - Datas (intervalo): BETWEEN + `@UISchema(type=DATE, controlType=DATE_RANGE)`
    - Booleanos: EQUAL + `@UISchema(type=BOOLEAN, controlType=CHECKBOX)`
    - Relações: EQUAL + `relation="entidade.id"` + `@UISchema(type=NUMBER, controlType=SELECT, endpoint=ApiPaths.<Modulo>.<Recurso> + "/filter")`
  - Limites de segurança (`praxis.pagination.max-size`, `praxis.query.by-ids.max`), endpoints de opções
  - Seleção vs Listagem — qual endpoint usar:
    - `POST /{resource}/options/filter` para SELECT/MULTI_SELECT/AUTOCOMPLETE (OptionDTO paginado)
    - `GET /{resource}/options/by-ids` para reidratar opções preservando a ordem
    - `POST /{resource}/filter` para listagens ricas (DTO completo)
    - Guia detalhado: docs/audit/fases/FASE-05-FILTROS-PAGINACAO-OPCOES.md:1
  - Ref.: src/main/java/org/praxisplatform/uischema/filter/annotation/Filterable.java:71, src/main/java/org/praxisplatform/uischema/filter/specification/GenericSpecificationsBuilder.java:21, src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:188

- Fase 6 — Erros, HATEOAS, OpenAPI e Cache de Schema
  - `GlobalExceptionHandler` padroniza erros; HATEOAS habilitado; `X-Data-Version` quando disponível; `/schemas/filtered` com ETag/If‑None‑Match
  - Ref.: src/main/java/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.java:1, src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:1, docs/SCHEMA-HASH-PLAN.md:337

- Fase 7 — MapStruct (Fail‑Fast)
  - Mappers usam `CorporateMapperConfig` com `unmappedTargetPolicy=ERROR`; corrigir unmapped
  - Ref.: src/main/java/org/praxisplatform/uischema/mapper/config/CorporateMapperConfig.java:1, README.md:42

- Fase 8 — Auto‑config, Grupos de Infra/Fallback e BigDecimal
  - Auto‑configs ativas; grupos `praxis-metadata-infra` e `application` presentes; BigDecimal → number/decimal
  - Ref.: src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java:1

Siga as fases na ordem. Cada arquivo em docs/audit/fases inclui o prompt para execução por outro agente.
