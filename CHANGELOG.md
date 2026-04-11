# Changelog - praxis-metadata-starter

All notable changes to this module will be documented in this file.

## [Unreleased]

### Removed
- Superficie CRUD anterior removida do starter: `AbstractCrudController`, `AbstractReadOnlyController`, `BaseCrudService`, `AbstractBaseCrudService` e `AbstractReadOnlyService`.
- Suite de testes e fixtures de coexistencia do core anterior removidas para consolidar o baseline `resource-oriented`.

### Changed
- `DynamicSwaggerConfig` passa a reconhecer apenas controllers da hierarquia `AbstractResourceQueryController`.
- Guias publicos deixam de apontar onboarding ativo para o core anterior.
- `README.md`, `docs/index.md` e `docs/spec/CONFORMANCE.md` passam a tratar `option-sources` como superficie publica canonicamente suportada quando o recurso publica `OptionSourceRegistry`.

### Added
- Rollout do baseline semantico `resource + surface + action + capability`, com `@UiSurface`, `@WorkflowAction`, `GET /schemas/surfaces`, `GET /schemas/actions` e snapshots agregados em `/capabilities`.
- Auto-configuracao canonica de `OptionSourceQueryExecutor`, `OptionSourceEligibility` e `OptionSourceRegistry` agregado para discovery e enrich de `/schemas/filtered`.

### Fixed
- Corrigida a lacuna que impedia `option-sources` reais de funcionar apenas com o starter: recursos que expoem `OptionSourceRegistry` agora publicam `x-ui.optionSource` em `/schemas/filtered` e executam `POST /{resource}/option-sources/{sourceKey}/options/filter` e `GET /{resource}/option-sources/{sourceKey}/options/by-ids` via auto-configuracao padrao.

### Documentation
- `README.md` e `docs/spec/CONFORMANCE.md` passam a citar explicitamente `/schemas/surfaces` e `/schemas/actions` como superficies publicas canonicas de discovery.
- `README.md`, `docs/index.md` e o guia `docs/guides/OPTIONS-ENDPOINT.md` passam a integrar a checklist minima de validacao de `option-sources` e o posicionamento canonico dessa superficie no starter.

## [2.0.0-rc.7] - 2026-03-21

## [5.0.0-rc.2] - 2026-03-24

### Fixed

- Corrige a resolucao de `x-ui.resource.idField` em `/schemas/filtered` para que request schemas com campos relacionais `...Id` nao publiquem uma FK como identificador canonico do recurso quando chamados diretamente.
- Endurece a cobertura de regressao no starter e no quickstart para o cenario de request schema com relacoes.

### Added
- Novo endpoint `GET /schemas/catalog` como superficie canonica de discovery, exemplos operacionais e navegacao para `request`/`response` schema.
- Arquivo fisico `LICENSE` (Apache 2.0) adicionado ao root do modulo para alinhar repositorio, artefato e distribuicao publica.

### Changed
- `ApiDocsController` agora separa payload estrutural de payload documental no calculo de `ETag` e `X-Schema-Hash`, evitando invalidar cache por mudancas apenas em exemplos/documentacao.
- `x-ui.operationExamples` passou a respeitar `schemaType=request|response` no payload de `/schemas/filtered`.
- Exemplos derivados do OpenAPI podem ser complementados ou sobrescritos por `x-ui.operationExamples` explicito na propria operacao.
- Extracao de examples passou a preservar `externalValue` alem de `summary`, `description` e `value`.
- `DomainCatalogController` agora publica `schemaLinks.request` e `schemaLinks.response` apontando diretamente para `/schemas/filtered`.

### Fixed
- Corrigido o acoplamento indevido entre metadados documentais e hash estrutural do contrato retornado por `/schemas/filtered`.
- Melhorada a codificacao de links do catalogo para paths com `/`, espaco e outros caracteres reservados.
- Tornado mais robusto o merge de exemplos operacionais entre OpenAPI derivado e overrides explicitos por recurso.

### Documentation
- `SCHEMA-INTEGRATION-PLAN.md` atualizado para refletir a separacao formal entre contrato estrutural (`/schemas/filtered`) e catalogo/documentacao (`/schemas/catalog`).
- `CONFORMANCE.md` e a spec `x-ui-operation.schema.json` atualizadas para documentar `operationExamples`, incluindo `externalValue`.
- `README.md` e `docs/overview/VISAO-GERAL.md` alinhados para a nova RC.

## [1.0.0-rc.6] - 2025-11-06

### Added/Changed
- Resolver: serializacao completa de x-ui por propriedade, incluindo `tableHidden` e `formHidden` (visibilidade por contexto).
- Suporte a propriedades avancadas de `@UISchema` em x-ui (layout/icones, condicionais, triggers, numericos, validacoes e mensagens, arquivos).
- Fallbacks do schema OpenAPI: `name`, `label` derivado, `placeholder`, `helpText`, validacoes basicas, `enum -> options`.
- Decisao temporaria: `filterOptions` permanece `string`; registrada em `docs/spec/CONFORMANCE.md`. Follow-up detalhado em `docs/follow-ups/filter-options-array.md` para migrar para `array` conforme a spec.
- Operacao (`x-ui.operation`): chaves `displayColumns`/`displayFields` mantidas apenas na spec/exemplos (backend nao gera/consome).
- Testes: adicionados `VisibilityFlagsTest` e `ExplicitAdvancedPropsTest` cobrindo novas chaves/flags.

## [1.0.0-rc.1] - 2025-10-31

### Changed
- Migracao para repositorio standalone `praxis-metadata-starter` com metadados SCM corrigidos.
- Adicionado workflow de release para Maven Central com extracao de versao via tag `v*` e fallback de GPG key id.
- Adicionado workflow de documentacao (Javadoc + Markdown -> HTML) publicado em `gh-pages`.
- Heuristica de `controlType` (strings): threshold de `textarea` ajustado de `>100` para `>300` e deteccao por nome com maior precedencia para campos single-line (e.g., `nome`, `titulo`, `assunto` -> `input`).
- Enums: inferencia por cardinalidade (`<=5` -> `radio`, `6-25` -> `select`, `>25` -> `autoComplete`).
- Booleanos: padrao `checkbox` (ou `toggle`); `radio` quando enum textual binaria.
- Arrays de enums: pequeno -> `chipInput`; maiores -> `multiSelect` e dica `filterControlType = multiColumnComboBox`.
- Percent: aplica `numericStep=0.01`, `placeholder="0-100%"`, `numericMin=0`, `numericMax=100` (quando ausentes).
- Filtros: novas operacoes adicionadas - `NOT_EQUAL`, `GREATER_OR_EQUAL`, `LESS_OR_EQUAL`, `NOT_LIKE`, `STARTS_WITH`, `ENDS_WITH`, `NOT_IN`, `IS_NULL`, `IS_NOT_NULL`.
- Filtros (Lote 1 - Core): `BETWEEN_EXCLUSIVE`, `NOT_BETWEEN`, `OUTSIDE_RANGE`, `ON_DATE`, `IN_LAST_DAYS`, `IN_NEXT_DAYS`, `SIZE_EQ`, `SIZE_GT`, `SIZE_LT`, `IS_TRUE`, `IS_FALSE`.

### Documentation
- Endpoints Overview enriquecido (`doc-files/endpoints-overview.html`):
- Problemas que resolve, funcionamento interno, parametros/retornos, erros/limites.
- Notas de integracao frontend por endpoint (debounce, multi-sort, includeIds, infinite scroll, reset de cursores, jump-to-row, reidratacao de options, uso do `X-Data-Version`).
- Anti-patterns e exemplos praticos: cursor pagination (JS), jump-to-row (`/locate`) e reidratacao (React/Angular).
- Ancoras por endpoint e links cruzados a partir da pagina overview do Javadoc.
- Javadoc ampliado:
- `AbstractCrudController`: detalhes de `/filter`, `/filter/cursor`, `/locate`, `/options` (inclui blocos "Uso em DTOs (@UISchema)").
- `@UISchema`: secao "Referenciando endpoints de Options em DTOs" (OptionDTO vs DTO completo; combos dependentes com interpolacao; reidratacao).
- `OptionDTO`: exemplo de referencia em DTOs com `@UISchema`.

### Behavior
- Respostas de `GET /{id}`, `POST /` e `PUT /{id}` agora anexam o cabecalho `X-Data-Version` quando o service expoe `getDatasetVersion()` (padronizacao com os demais endpoints).

### Migration Notes
- Campos `string` que eram inferidos como `textarea` apenas por `maxLength` entre `101` e `300` agora serao `input` por padrao. Para manter `textarea`, use `@UISchema(controlType=TEXTAREA)` ou utilize nomes semanticos como `descricao`/`observacao`.

### Notes
- Este RC prepara a publicacao `1.0.0` final; sem mudancas de API em relacao ao beta.1.

## [1.0.0-beta.1] - YYYY-MM-DD

### Added
- New annotation `@OptionLabel` to declare the label source for OptionDTO on entity field or getter (supports inheritance).
- Default `OptionMapper` fallback in `BaseCrudService`: if `getOptionMapper()` is not overridden, entities are projected to `OptionDTO` using `extractId()` and `computeOptionLabel()`.

### Compatibility
- No breaking changes. Existing services and custom mappers continue to work unchanged.
