# Fechamento da Fase 4 - Catalogo de Surfaces

## Status

A Fase 4 do catalogo de surfaces esta concluida no `praxis-metadata-starter`.

O eixo `surface-oriented` ja esta implementado como camada semantica derivada sobre o contrato canonico `resource-oriented`, sem redefinir payload, validacao ou schema inline.

## O que a fase entregou

- `resourceKey` obrigatorio em `@ApiResource` como identidade semantica estavel do recurso
- `@UiSurface` para surfaces explicitas sobre operacoes HTTP reais
- surfaces automaticas `create`, `list`, `detail` e `edit`
- discovery global em:
  - `GET /schemas/surfaces?resource={resourceKey}`
  - `GET /schemas/surfaces?group={openApiGroup}`
- discovery contextual item-level em:
  - `GET /{resource}/{id}/surfaces`
- catalogo retornando apenas referencias canonicas:
  - `operationId`
  - `path`
  - `method`
  - `schemaId`
  - `schemaUrl`
- availability contextual por composicao, com:
  - `SurfaceAvailabilityRule`
  - `SurfaceAvailabilityContextResolver`
  - `ResourceStateSnapshotProvider`
  - short-circuit no primeiro deny sensivel
  - compartilhamento de contexto/snapshot por catalogo para evitar custo N+1 por surface
- hardening final do catalogo:
  - `404` para `resourceKey` ou `group` desconhecidos
  - cache lazy no registry annotation-driven
  - exclusao de mappings workflow-like (`/actions/` e `:approve`)

## Regras canonicamente fixadas

- `surface` nao define campos, schema ou validacao inline
- `surface` sempre referencia operacao real descoberta no OpenAPI
- `FORM` e `PARTIAL_FORM` apontam para schema `request`
- `VIEW` e `READ_PROJECTION` apontam para schema `response`
- `ITEM` surfaces em catalogo global sao discovery semantico e nao affordance executavel sem `resourceId`
- workflow actions continuam fora do catalogo de surfaces

## Validacao que fecha a fase

Cobertura focal validada no starter:

- `SurfaceCatalogE2ETest`
- `AnnotationDrivenSurfaceDefinitionRegistryTest`
- `DefaultSurfaceAvailabilityContextResolverTest`
- `DefaultSurfaceAvailabilityEvaluatorTest`
- `SurfaceCatalogServiceTest`
- `OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest`
- `AbstractResourceControllerJpaWriteIntegrationTest`

Rodada de fechamento formal:

- `BUILD SUCCESS`
- `32 testes`
- QA independente sem findings bloqueantes

Rodada de hardening de testes em `src/test`:

- `BUILD SUCCESS`
- `39 testes`
- ampliou a blindagem de:
  - short-circuit sem vazamento de metadata apos deny por RBAC
  - `resource-state-unavailable`
  - ordenacao e conflitos canônicos em `SurfaceCatalogService`
  - `not found` de `resourceKey` e `group`
  - cache do registry e publicacao automatica mutavel vs. read-only
- QA independente sem blockers

## O que ficou fora por escopo

- catalogo de workflow actions (`@WorkflowAction`) e endpoints `/schemas/actions`
- capabilities unificadas agregando surfaces + actions
- perfil `e2e-pg` com PostgreSQL/Testcontainers
- teste de bootstrap completo de `ApplicationContext` para ordering/override de `SurfaceAvailabilityRule` beans

Esses pontos nao bloqueiam o encerramento da Fase 4. Eles pertencem a fases posteriores ou a hardening adicional.

## Saida formal da fase

Com a Fase 4 encerrada:

- o starter ja possui discovery semantico de surfaces sobre operacoes reais
- o catalogo de surfaces ja esta endurecido para uso corporativo basico
- a proxima fase canonicamente correta e a Fase 5 de `WorkflowAction`
