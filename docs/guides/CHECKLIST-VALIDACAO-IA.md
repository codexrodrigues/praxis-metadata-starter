# Checklist de Validacao - Codigo Gerado por IA

Use esta lista apos o agente gerar codigo para uma aplicacao nova, um recurso
metadata-driven no baseline atual ou um frontend Angular.

## 1. Build e execucao

- `mvn clean package` executa sem erros no backend
- aplicacao Spring Boot sobe na porta esperada
- `npm run build` executa sem erros no frontend, quando aplicavel
- host Angular sobe sem erro de bootstrap, quando aplicavel

## 2. OpenAPI e grupos

- `GET /v3/api-docs` responde
- grupos OpenAPI esperados existem (`@ApiGroup`)
- Swagger UI esta disponivel

## 3. Endpoints CRUD e Discovery

- `GET /{id}`, `GET /all`, `POST /filter`, `POST /filter/cursor`,
  `POST /locate`, `GET /by-ids`
- `POST /`, `PUT /{id}`, `DELETE /{id}`, `DELETE /batch`
  ou `405` quando read-only (`AbstractReadOnlyResourceController`)
- `GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities` respondem com snapshot agregado
- `GET /schemas/catalog`, `GET /schemas/domain` e `GET /schemas/surfaces` incluem o recurso

## 4. Options e Relacionamentos

- Entidade possui `@OptionLabel` e `@DefaultSortColumn`
- `POST /options/filter` retorna `OptionDTO` paginado quando houver select remoto
- `GET /options/by-ids` reidrata labels preservando ordem
- campos com `endpoint=".../options/filter"` usam `valueField=id` e
  `displayField=label`
- no `ResponseDTO`, relacionamentos expoem o par `...Id` (`@ResourceReference` + `tableHidden = true`) e `...Nome` (visivel na tabela)

## 5. Filtros e paginacao

- `FilterDTO` implementa `GenericFilterDTO` e usa `@Filterable` com matriz rica de operadores (`LIKE`, `EQUAL`, `IN`, `BETWEEN`, `IN_LAST_DAYS` quando aplicavel)
- campos com sufixo ou relacionamento declaram `relation = "..."` explicito (ex.: `statusIn` -> `relation = "status"`, `departamentoId` -> `relation = "departamento.id"`)
- `/filter` e `/filter/cursor` respeitam sort e limites
- `/locate` retorna resposta coerente

## 6. Estatisticas Analiticas (`/stats/*`)

- `Service` declara `StatsFieldRegistry` e sobrescreve `getGroupByStatsSupportMode()`, `getTimeSeriesStatsSupportMode()`, `getDistributionStatsSupportMode()` e `getComparisonStatsSupportMode()` retornando `StatsSupportMode.AUTO`
- `POST /{resource}/stats/group-by`, `/stats/timeseries`, `/stats/distribution` e `/stats/comparison` respondem `200` com `RestApiResponse`
- `GET /{resource}/capabilities` publica suporte a `stats`

## 7. Schemas, `x-ui` e Governanca Semantica

- `GET /schemas/filtered` responde com `x-ui` completo
- `ETag` e `X-Schema-Hash` estao presentes (`If-None-Match` retorna `304` quando o schema nao muda)
- `x-ui.resource.idField` esta resolvido corretamente (`@ResourceIdentity` no Controller e `ResponseDTO`, `@QuickFilter` no Controller)
- em request schemas com campos relacionais `...Id`, o `idField` nao e sequestrado pelo id da relacao
- todos os campos dos DTOs possuem a triade completa: `@Schema(description = ...)` de negocio em PT-BR, `@UISchema` (`UISchemaPreset`, `FieldControlType`, `group`, `order`, `icon`, `NumericFormat`) e `@DomainGovernance` + `@AiUsagePolicy`

## 8. DTOs, mapeamentos e service

- 4 DTOs separados por recurso mutavel (`ResponseDTO`, `CreateDTO`, `UpdateDTO`, `FilterDTO`)
- `CorporateMapperConfig` e usado no `ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>`
- `applyUpdate(@MappingTarget E entity, UpdateDTO dto)` recebe `entity` no 1º parametro e `dto` no 2º

## 9. Infra e organizacao

- `ApiPaths` centraliza os paths
- propriedades SpringDoc estao ativas
- `app.openapi.internal-base-url` esta configurado quando necessario
- pacotes seguem a organizacao por modulo

## 10. Frontend Angular

- existe um host `praxis-crud` para CRUD completo (sem servicos HTTP manuais nem colunas hardcoded)
- `resource.path` (sem prefixo `/api`) e `idField` estao corretos
- `praxis-dynamic-form` usa `layoutPolicy.source = 'schema'` (`groupedCommand` / `compactPresentation`) sem `FormConfig.sections` manuais redundantes
- `API_URL` esta coerente
- o recurso remoto carrega schema sem ajuste local de contrato
- `POST /api/.../filter?page=0&size=10` responde `200` no mesmo origin do host

## 11. Exportacao de colecoes

- `GET /{resource}/capabilities` anuncia `export` apenas quando o service suporta exportacao
- detalhes de `export` publicam formatos, escopos, limites e async coerentes com o recurso
- `POST /{resource}/export` respeita filtros, selecao, pagina atual e allowlist de campos
- limite efetivo do servidor e aplicado mesmo quando o cliente pede `maxRows` maior
- resultado truncado publica `X-Export-Truncated`, `X-Export-Max-Rows`,
  `X-Export-Candidate-Row-Count` e `X-Export-Warnings`
- campos solicitados e nao suportados retornam `400` quando nenhum campo valido sobrar
- CSV protege contra formula injection

## Referencias publicas

- guias principais: `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`, `GUIA-02-AI-BACKEND-CRUD-METADATA.md`, `GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md`
- views / somente leitura: `READ-ONLY-VIEWS.md`
- exportacao de colecoes: `COLLECTION-EXPORT.md`
- repositorio Git do runtime Angular: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes npm relevantes: `@praxisui/core`, `@praxisui/table`, `@praxisui/dynamic-form`, `@praxisui/crud`, `@praxisui/list`
