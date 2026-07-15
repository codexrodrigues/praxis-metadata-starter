# Changelog - praxis-metadata-starter

All notable changes to this module will be documented in this file.

## Unreleased

### Fixed
- `/schemas/domain` agora associa option sources registry-wide ao `resourceKey`
  canonico ja materializado por surfaces/actions para o mesmo `resourcePath`,
  preservando a descoberta mesmo quando a URL nao codifica a identidade
  semantica completa do recurso.
- `/schemas/filtered` publica `x-ui.resource.identity` somente para schemas de
  resposta; schemas de request representam filtros e comandos, nao registros
  materializados.
- `ApiResourceIdentityResolver` agora injeta explicitamente o
  `requestMappingHandlerMapping` canonico do Spring MVC, evitando falha de
  auto-configuracao em hosts que tambem publicam mappings auxiliares, como
  `controllerEndpointHandlerMapping`.

### Added
- `@AnalyticsPolicyReference` e
  `x-ui.analytics.projections[].governance.policyRefs[]` para publicar identidade
  e versao de policies de dominio, papel, campo de resultado e atestacao
  opcional sem expor thresholds, expressoes ou dados de runtime.
- `x-ui-field.schema.json` agora publica o contrato de atalhos de periodo para
  `dateRange` e `inlineDateRange`, incluindo `shortcuts[]`,
  `inlineQuickPresets`, `inlineOverlay`, fixtures valid/invalid e cobertura para
  impedir callbacks como `calculateRange` em metadata JSON.
- Base publica `AbstractCollectionCommandResourceController` para recursos que possuem somente
  actions no escopo da colecao: publica `/actions` e `/capabilities`, integra a execucao ao
  boundary governado e aos schemas filtrados da operacao real, sem expor CRUD, filtros ou
  persistencia ficticios.
- Guia canonico `docs/guides/ENTERPRISE-AVAILABILITY-ADOPTION.md`,
  checklist de readiness e fixture E2E non-Ergon para orientar availability
  enterprise entre `ResourceOperationAvailabilityProvider`,
  `ActionAvailabilityRule`, `SurfaceAvailabilityRule`,
  `ResourceStateSnapshotProvider`, `_links`, `/capabilities`, `/actions` e
  `/surfaces` sem expor politicas privadas do host.
- API Java inicial de **Governed Resource Command Execution** em
  `org.praxisplatform.uischema.command`, com executor host-neutral, provider,
  request/result, response policies, outcomes publicos, error categories e
  sanitizacao de evidence privada, alem de adapter opt-in para converter
  outcomes governados em `RestApiResponse`/`CustomProblemDetail` canonicos e
  helpers protegidos em `AbstractResourceQueryController` para actions reais
  executarem comandos governados preservando `X-Data-Version` e links de
  schema sem criar dispatcher generico nem alterar endpoints/discovery
  existentes; o executor tambem converte `ResponseStatusException` publica de
  providers Spring em outcomes governados para reduzir adaptacao local nos
  hosts.
- Engine canonico `ExcelCollectionExportEngine` para exportacao XLSX real em
  `POST /{resource}/export`, registrado por auto-configuracao junto aos engines
  CSV/JSON e governado pela mesma allowlist de campos, `applyFormatting`,
  `localization`, headers, ordem tabular e protecao contra formula injection.
- `GET /{resource}/capabilities` agora publica `stats.fields` como discovery publico
  derivado de `StatsFieldRegistry`, incluindo campo, `propertyPath`, label sugerido,
  metricas e modos elegiveis para dashboards e cockpits metadata-driven.
- Base canonica `AbstractCreateUpdateResourceController` e portas `BaseCreateUpdateResourceService` / `BaseCreateUpdateResourceCommandService` para recursos `read + create/update` que nao publicam `delete`.
- Base canonica `AbstractUnitDeleteResourceController` e portas `BaseUnitDeleteResourceService` / `BaseUnitDeleteResourceCommandService` para recursos `read + create/update + delete unitario` que nao publicam `DELETE /batch`.
- SPI publica `ResourceOperationAvailabilityProvider` para availability host-neutral de operacoes canonicas de recurso, integrada a `/capabilities` e `_links`.
- Tipos publicos `ResourceOperationAvailabilityContext` e `NoOpResourceOperationAvailabilityProvider` para hosts corporativos plugar guards legados sem expor detalhes privados no contrato.
- Base canonica `AbstractLegacyBackedResourceController` e portas `LegacyBackedResourceService` / `LegacyBackedResourceCommandService` para recursos mutaveis resource-oriented com escrita delegada ao host legado.
- Portas opcionais `DuplicateDraftLegacyBackedResourceService` / `DuplicateDraftLegacyBackedResourceCommandService` para `duplicate-draft` nao mutante, retornando DTO de rascunho editavel separado do DTO de resposta persistida.
- Operacao canonica opcional `duplicate-draft` em `capabilities.operations` quando o recurso publica `POST /{resource}/{id}/duplicate-draft`.
- Builder publico `GovernedOptionSourceCatalog` para declarar lookups provider-backed com endpoints canonicos, dependency mapping, selected-value reload e politica de sort sem boilerplate por service.
- Contrato publico `OptionSourceRuntimeContract`, `OptionSourceSelectedReloadPolicy` e `OptionSourceInvalidSortPolicy` para projetar `filterEndpoint`, `byIdsEndpoint`, `selectedReloadPolicy` e `invalidSortPolicy` em `x-ui.optionSource`.
- Contrato publico `RelatedResourceSurface` e `RelatedResourceChildOperation` para que `@UiSurface` descreva colecoes filhas relacionadas, binding do item pai, selecao e affordances da colecao filha em `/schemas/surfaces`.
- `@UISchema.preset()` e `UISchemaPreset` para acelerar metadados repetitivos de apresentacao sem gerar texto de dominio; o resolver publica `x-ui.presentationPreset`.
- API publica `SemanticMetadataReviewer` para gerar relatorio de qualidade de autoria, apontando descricoes ausentes, copiadas de labels, derivadas de nomes de campos e vazamento de contexto privado sem governanca.
- Endpoint `POST /{resource}/option-sources/{sourceKey}/options/by-ids` com `OptionSourceByIdsRequest` para selected-value reload contextual sem quebrar o `GET .../by-ids` canonico.
- API publica `OptionSourceByIdsRequest` para extensoes de service provider-backed que precisam tratar selected-value reload por IDs sem criar contrato local no host.
- Propriedade `@UISchema.dependsOn()` como atalho canonico para publicar dependencias de LOV/options em `x-ui.optionSource.dependsOn`.
- Contrato canonico de filtro rico para `RESOURCE_ENTITY` em `x-ui.optionSource.filtering`, com `availableFilters`, `defaultFilters`, `sortOptions`, `defaultSort`, `quickFilterFields` e `searchPlaceholder`.
- Tipos publicos `LookupFilterDefinition`, `LookupFilteringDescriptor` e `LookupSortOption` para publicar o contrato de filtro rico no starter sem convencoes locais de frontend.
- Execucao JPA compartilhada para `LIGHT_LOOKUP`, com projeção leve `OptionDTO{id,label}`,
  busca textual e reidratacao por IDs quando o descriptor publica `propertyPath` ou
  `valuePropertyPath`/`labelPropertyPath`.

### Changed
- `@UISchema.options` agora pode enriquecer labels/metadados de opcoes derivadas de `enum` em `x-ui.options`, preservando os valores canonicos do schema OpenAPI e ignorando valores extras que nao pertencem ao enum.
- `_links` de create/edit/delete/export passam a respeitar a availability canonica avaliada pelo `CapabilityService`, evitando divergencia entre HATEOAS e `/capabilities`.
- `/schemas/domain` agora preenche a descricao do no conceitual de recurso a partir da
  descricao OpenAPI do schema raiz, priorizando schemas de resposta para o Cockpit
  materializar contexto de negocio sem convencoes locais no host.
- `duplicate-draft` agora e opt-in via `AbstractDuplicateDraftLegacyBackedResourceController`; `AbstractLegacyBackedResourceController` publica apenas o baseline CRUD legado-backed, e o endpoint de rascunho retorna `200 OK` sem criar item persistido.
- `deleteBatch` passa a validar availability de colecao e de cada item antes de delegar exclusao em lote.
- `SemanticMetadataReviewer` passa a revisar campos herdados de DTOs, evitando que contexto privado em superclasses escape sem governanca.
- `OpenApiGroupResolver` agora respeita fronteira de segmento ao resolver grupos, evitando falso match entre recursos com prefixos comuns, como `/vinculos` e `/vinculos-funcionais`.
- A documentacao do starter agora explicita a forma canonica de publicar controllers customizados de recursos relacionados com `@ApiGroup` e `@RequestMapping` de classe.
- `EntityLookupDescriptor` agora pode publicar o bloco `filtering` como parte da semantica canonica de `entityLookup`.
- `x-ui-field.schema.json`, fixtures de exemplo e a RFC de `optionSource` passam a documentar o contrato de filtro rico para buscas corporativas.
- Requests com `sort` contendo direcao diferente de `asc` ou `desc` agora retornam erro de cliente em vez de serem normalizadas silenciosamente para `ASC`.
- `OptionSourceEligibility` preserva `OptionSourceExecutionMode.PROVIDER_REQUIRED` ao enriquecer descriptors derivados por stats, evitando fallback JPA indevido para fontes externas.
- A documentacao de `@UISchema` foi alinhada ao contrato atual de `type`, `controlType`, `numericFormat` e semantica textual para codigos, documentos e identificadores numericos de legado.
- `@UiSurface` agora pode publicar `relatedResource` para surfaces `ITEM` que projetam colecoes relacionadas; o schema continua resolvido por `/schemas/filtered` da operacao real e metadados parciais de colecao filha sao rejeitados.
- `x-ui-field.schema.json` passa a documentar `presentationPreset` como acelerador visual, explicitamente separado da descricao OpenAPI de dominio.
- `conditionalDisplay`, `conditionalRequired` e `conditionalValidation[].condition` agora sao validados no backend como Json Logic canonico contra a matriz de operadores do runtime Angular antes de serem publicados em `x-ui`.
- Mensagens de erro para condicionais Json Logic malformados agora distinguem JSON invalido de contrato Json Logic invalido, e a validacao bloqueia literais com shape basico incompatível com o runtime Angular.

### Fixed
- Links HATEOAS absolutos agora respeitam headers `Forwarded` e
  `X-Forwarded-*`, preservando host, porta e protocolo de proxies e dev servers
  ao publicar affordances como `capabilities`, `actions` e discovery contextual.
- `_links` operacionais resource-local (`self`, `all`, `filter`,
  `filter-cursor`, `create`, `update`, `delete`, `export` e
  `duplicate-draft`) agora publicam paths relativos com `contextPath`, mantendo
  o mesmo origin do consumidor atras de proxy Angular ou headers forwarded.
- Resolucao interna de documentos OpenAPI agora usa a origem local do backend
  ao rodar atras de proxy/forwarded headers, evitando que `/schemas/*`,
  `/capabilities`, actions e surfaces tentem consumir `/v3/api-docs` pela
  origem publica sem porta do proxy local.
- `/schemas/filtered` agora resolve paths OpenAPI template-equivalentes apenas
  quando a operacao HTTP solicitada tambem existe no candidato estrutural, mantendo
  match exato como prioridade e rejeitando ambiguidades em vez de escolher um schema
  incorreto para recursos relacionados nested.
- `POST /{resource}/option-sources/{sourceKey}/options/filter` agora aceita
  dependencias publicas declaradas em `dependsOn`/`dependencyFilterMap` para
  fontes `PROVIDER_REQUIRED` mesmo quando esses campos nao existem no
  `FilterDTO` do recurso host, preservando o payload publico governado antes da
  conversao do filtro estrutural e sem interpretar campos legados chamados
  `search`, `sort`, `filters` ou `includeIds` como envelope quando eles existem
  no `FilterDTO`.
- O mesmo endpoint provider-backed volta a publicar `OptionSourceFilterRequest`
  como schema OpenAPI de request, mantendo a documentacao publica estavel mesmo
  com parsing interno por JSON bruto para preservar dependencias governadas.
- `/schemas/filtered` agora preserva descricoes `@Schema` de campos `BigDecimal`
  ao manter o formato `decimal`, evitando que metricas monetarias ou agregadas
  percam semantica de negocio no Cockpit e em consumidores AI.
- O cockpit agora calcula formularios esperados e workflows acionaveis como
  cobertura contextual, evitando tratar recursos read-only ou analiticos como
  lacunas operacionais por nao publicarem formulario ou action.
- O cockpit agora verifica `/capabilities` apenas para recursos com `resourceKey`
  canonico publicado, evitando 404 falso-positivo em endpoints tecnicos isolados
  descobertos pelo catalogo OpenAPI.
- O cockpit agora materializa o mapa de dominio apenas a partir de endpoints com
  `resourceKey` canonico, mantendo endpoints tecnicos ou custom sem `@ApiResource`
  fora da contagem de recursos de negocio.
- O cockpit agora usa timeout maior ao ler catalogos por grupo, evitando falso
  "sem dominio materializavel" em hosts grandes durante inicializacao fria do OpenAPI.
- O cockpit e o catalogo de actions agora reconhecem `POST /{resource}/{id}/duplicate-draft`
  como workflow action canonica opt-in do starter, sem exigir aliases locais em `/actions/...`.
- `OptionSourceRuntimeContract.canonical(...)` rejeita `sourceKey` nao URL-safe antes de publicar endpoints de runtime.
- `CustomOpenApiResolver` agora preserva `x-ui.type=text` e nao publica `valuePresentation` numerico automatico quando um campo com transporte OpenAPI numerico e declarado como texto, controle textual ou mascara textual.
- `/schemas/filtered` agora pode derivar `x-ui.resource.idField` de um identificador natural escalar obrigatorio quando o DTO de resposta nao possui `id` ou `*Id`, cobrindo recursos como `EmpresaDTO.empresa`.
- O cockpit empacotado em `/praxis/cockpit` agora mescla o catalogo default com catalogos por grupo, evitando leitura parcial quando um grupo demora ou falha, e reorganiza endpoints em uma coluna no mobile para impedir linhas largas no painel do recurso.
- O cockpit agora prioriza o prefixo semantico de `resourceKey` antes de grupos tecnicos genericos como `application`, e reduz rotulos secundarios no modo limpo do grafo para melhorar a leitura da constelacao de relacoes.
- O cockpit agora inclui uma leitura rapida acionavel na lista de recursos e compacta o grafo semantico no mobile, reduzindo rolagem cega antes de escolher recursos, charts, formularios e workflows.
- O cockpit agora exibe um marcador de publicacao no topo, combinando `release`/`published`/`qa` da URL com `build.version` e `build.time` do host para reduzir ambiguidade durante validacao publica.
- O cockpit agora calcula workflows acionaveis a partir do cache canonico de
  `/schemas/actions`, evitando ressalva falsa quando a verificacao assíncrona ja
  carregou a action mas o objeto de recurso renderizado ainda esta desatualizado.

## [8.0.0-rc.14] - 2026-04-24

### Added
- Anotacoes publicas `@DomainGovernance` e `@AiUsagePolicy` para declarar
  classificacao semantica e politicas de uso por IA diretamente no codigo-fonte
  dos campos publicados pelo starter.
- Enums publicos `DomainGovernanceKind`, `DomainClassification`,
  `DomainDataCategory` e `AiUsageMode` para fixar os tokens canonicos emitidos em
  `x-domain-governance` e republicados por `/schemas/domain`.

### Changed
- `SemanticDomainCatalogService` agora prioriza governanca explicita publicada em
  `x-domain-governance` antes do fallback heuristico por nome ou descricao de
  campo.
- A anotacao `@DomainGovernance` usa vocabulario tipado no codigo Java e continua
  materializando os mesmos valores wire compativeis com o contrato semantico.

## [8.0.0-rc.80] - 2026-07-08

### Added
- Hooks protegidos de lifecycle em `AbstractBaseResourceService` para customizar
  create, update, delete individual e delete em lote sem sobrescrever o fluxo
  canonico de mapper, save, refresh e response.
- Helpers protegidos em `AbstractBaseResourceService` para resolver referencias
  JPA de entidades relacionadas por ID e substituir colecoes relacionais mutaveis,
  evitando boilerplate de `EntityManager#getReference` em updates de aggregates.
- RFC publica de suporte GraphQL, posicionando GraphQL como adapter derivado e
  nao como segunda fonte primaria da semantica metadata-driven.

## [8.0.0-rc.13] - 2026-04-22

### Changed
- `GET /schemas/domain` now emits `praxis.domain-catalog/v0.2` with explicit
  semantic ownership, lifecycle, business glossary, resolution metadata and
  source evidence keys on generated context/node items.
- Domain catalog aliases are now materialized from generated labels and stable
  runtime identifiers such as field names, workflow action IDs and UI surface
  IDs.
- Domain field governance now recognizes operational risk and regulatory
  compliance vocabulary, so AI context can classify mission, incident,
  jurisdiction, approval and blocking fields beyond privacy/financial signals.

### Fixed
- Domain catalog governance now emits config-compatible enum values for
  `annotationType`, `dataCategory` and `aiUsage.visibility`, including
  `security`, `operational`, `legal` and `summarize_only`.

### Validated
- `praxis-api-quickstart` consumes this release with `praxis-config-starter`
  `0.1.0-rc.6` and validates critical `/schemas/domain` payloads against the
  config-starter schema contract.

## [8.0.0-rc.7] - 2026-04-21

### Added
- `GET /schemas/domain` as the runtime semantic domain catalog surface.
- Domain catalog nodes for contexts, concepts, actions, surfaces, states, policy hints and DTO fields.
- Domain catalog edges, bindings and evidence derived from runtime annotations, option sources and OpenAPI schemas.
- Field extraction for canonical `/schemas/filtered` references, including wrapper and `$ref` resolution.

### Changed
- `OptionSourceRegistry` now contributes semantic option-source signals to the domain catalog.
- Auto-configuration registers the semantic domain catalog service and controller.

## [8.0.0-rc.6] - 2026-04-20

### Removed
- Superficies paralelas de CRUD removidas para consolidar o baseline `resource-oriented`.
- Suite de testes e fixtures ajustadas para manter uma unica hierarquia canonica.
- Fallback configuravel de payload escalar para filtros de range removido; ranges aceitam apenas lista ou objeto canonico.

### Changed
- `DynamicSwaggerConfig` passa a reconhecer controllers da hierarquia `AbstractResourceQueryController`.
- Guias publicos passam a apontar onboarding ativo apenas para o baseline resource-oriented.
- `README.md`, `docs/index.md` e `docs/spec/CONFORMANCE.md` passam a tratar `option-sources` como superficie publica canonicamente suportada quando o recurso publica `OptionSourceRegistry`.
- `OptionSourceDescriptor` passa a carregar e publicar `dependencyFilterMap` diretamente para qualquer tipo de option-source, preservando a cascata canonica em `x-ui.optionSource` quando o campo dependente difere da chave de filtro.
- Guia do consumidor piloto passa a ser guia de adocao canonica, sem narrativa de migracao entre modelos.
- `/capabilities` passa a publicar detalhes governados da operacao `export` apenas quando o service declara suporte real a exportacao de colecao.
- `POST /{resource}/export` passa a expor headers de limite, truncamento, linhas candidatas e warnings quando o resultado inline trouxer esses metadados.

### Added
- Rollout do baseline semantico `resource + surface + action + capability`, com `@UiSurface`, `@WorkflowAction`, `GET /schemas/surfaces`, `GET /schemas/actions` e snapshots agregados em `/capabilities`.
- Auto-configuracao canonica de `OptionSourceQueryExecutor`, `OptionSourceEligibility` e `OptionSourceRegistry` agregado para discovery e enrich de `/schemas/filtered`.
- Contrato rico de Entity Lookup para `x-ui.optionSource` com `RESOURCE_ENTITY`, incluindo `entityKey`, paths de display/status/busca, `dependencyFilterMap`, `selectionPolicy`, `capabilities` e `detail`.
- Execucao JPA de `RESOURCE_ENTITY` rico, com busca multi-campo, reidratacao por IDs e `OptionDTO.extra` governado para Entity Lookup.
- Superficie canonica `POST /{resource}/export` para exportacao de colecao, com request preservando escopo, selecao, filtros, ordenacao, campos e limites.
- Camada reutilizavel de exportacao de colecoes com executor canonico e engines CSV/JSON tabulares.
- Guia canonico `docs/guides/COLLECTION-EXPORT.md` para contrato, responsabilidades do recurso, capabilities, headers, limites e checklist de publicacao.

### Fixed
- Corrigida a lacuna que impedia `option-sources` reais de funcionar apenas com o starter: recursos que expoem `OptionSourceRegistry` agora publicam `x-ui.optionSource` em `/schemas/filtered` e executam `POST /{resource}/option-sources/{sourceKey}/options/filter` e `GET /{resource}/option-sources/{sourceKey}/options/by-ids` via auto-configuracao padrao.
- Exportacao CSV passa a proteger contra formula injection mesmo quando o valor perigoso vem depois de whitespace inicial.
- Requests de exportacao com campos informados, mas nenhum campo suportado pelo recurso, passam a falhar em vez de cair silenciosamente para os campos default.

### Documentation
- `README.md` e `docs/spec/CONFORMANCE.md` passam a citar explicitamente `/schemas/surfaces` e `/schemas/actions` como superficies publicas canonicas de discovery.
- `README.md`, `docs/index.md` e o guia `docs/guides/OPTIONS-ENDPOINT.md` passam a integrar a checklist minima de validacao de `option-sources` e o posicionamento canonico dessa superficie no starter.
- `README.md`, `docs/index.md`, `docs/spec/CONFORMANCE.md`, guias e checklist tecnica passam a documentar a politica de exportacao de colecoes, incluindo limites corporativos, truncamento, headers e allowlist de campos.

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
- Controllers resource-oriented: detalhes de `/filter`, `/filter/cursor`, `/locate`, `/options` (inclui blocos "Uso em DTOs (@UISchema)").
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
- Default `OptionMapper` fallback in resource services: if `getOptionMapper()` is not overridden, entities are projected to `OptionDTO` using `extractId()` and `computeOptionLabel()`.

### Compatibility
- No breaking changes. Existing services and custom mappers continue to work unchanged.
