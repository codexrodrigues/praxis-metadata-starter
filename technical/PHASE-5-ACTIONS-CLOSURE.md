# Fechamento da Fase 5 - Catalogo de Workflow Actions

## Status

A Fase 5 de `WorkflowAction` esta concluida no `praxis-metadata-starter`.

O eixo `action-oriented` ja esta implementado como camada semantica derivada sobre operacoes HTTP
reais, sem payload inline, sem dispatcher generico e sem redefinir o contrato estrutural canonico.

## O que a fase entregou

- `@WorkflowAction` como anotacao canonica para comandos de negocio explicitos
- catalogo global em:
  - `GET /schemas/actions?resource={resourceKey}`
  - `GET /schemas/actions?group={openApiGroup}`
- discovery contextual em:
  - `GET /{resource}/{id}/actions`
  - `GET /{resource}/actions`
- `ActionScope.ITEM` e `ActionScope.COLLECTION` publicados no mesmo modelo canonico
- catalogo retornando apenas referencias canonicas:
  - `operationId`
  - `path`
  - `method`
  - `requestSchemaId`
  - `requestSchemaUrl`
  - `responseSchemaId`
  - `responseSchemaUrl`
- availability contextual por composicao, com:
  - `ActionAvailabilityRule`
  - `ActionAvailabilityContextResolver`
  - `DefaultActionAvailabilityEvaluator`
  - short-circuit no primeiro deny sensivel
  - compartilhamento de contexto/snapshot por catalogo para evitar custo N+1 por action
  - `ResourceStateSnapshot` e `ResourceStateSnapshotProvider` promovidos para o pacote neutro `capability`
- conflito semantico `@UiSurface` + `@WorkflowAction` governado por
  `praxis.metadata.validation.surface-workflow-conflict=FAIL|WARN|IGNORE`
- shape canonico de `@WorkflowAction` governado por
  `praxis.metadata.validation.workflow-action-shape=FAIL|WARN|IGNORE`
- fixture E2E real para os dois escopos:
  - `POST /employees/{id}/actions/approve`
  - `POST /employees/actions/bulk-approve`

## Regras canonicamente fixadas

- `action` nao define fields, schema ou payload inline
- `action` sempre aponta para endpoint real tipado e documentado
- a identidade canonica da action e `(resourceKey, actionId)`
- `delete`, `filter`, `stats`, `options` e `PATCH` resource-oriented por intencao nao entram no catalogo de actions
- `@WorkflowAction` nao entra no catalogo de `surfaces`
- `@WorkflowAction` deve apontar para mapping canonico de comando, com verbo `POST` ou `PATCH` e path workflow-like (`/actions/...` ou alias `:action`)
- `ITEM` actions em catalogo global sao discovery semantico e nao affordance executavel sem `resourceId`
- `COLLECTION` actions podem ser avaliadas contextual e canonicamente sem `resourceId`

## Validacao que fecha a fase

Cobertura focal validada no starter:

- `ActionCatalogE2ETest`
- `ActionCatalogServiceTest`
- `AnnotationDrivenActionDefinitionRegistryTest`
- `DefaultActionAvailabilityEvaluatorTest`
- `DefaultActionAvailabilityContextResolverTest`
- `OpenApiUiSchemaAutoConfigurationActionAvailabilityTest`
- `SurfaceCatalogE2ETest`
- `AnnotationDrivenSurfaceDefinitionRegistryTest`
- `AbstractResourceControllerJpaWriteIntegrationTest`

Rodada de hardening da availability:

- `BUILD SUCCESS`
- `50 testes`
- ampliou a blindagem de:
  - `resource-context-required`
  - `missing-authority`
  - `resource-state-blocked`
  - `resource-state-unavailable`
  - short-circuit sem vazamento de metadata apos deny por RBAC
  - wiring plugavel da auto-configuracao com prova real de ordering/override do container

Rodada de fechamento de `ActionScope.COLLECTION`:

- `BUILD SUCCESS`
- `54 testes`
- ampliou a blindagem de:
  - discovery contextual `GET /{resource}/actions`
  - `404` explicito para recurso sem actions de colecao
  - fixture real com `POST /employees/actions/bulk-approve`
  - cobertura E2E dos escopos `ITEM` e `COLLECTION` sem regressao em `surfaces`
- QA independente sem findings bloqueantes

## O que ficou fora por escopo

- capabilities unificadas agregando operacoes canonicas + surfaces + actions
- anuncio HATEOAS do novo endpoint `GET /actions` nas colecoes
- cobertura negativa do comando de colecao para payload invalido e IDs ausentes
- snapshot agregado de estado para futuras actions de colecao dependentes do estado do conjunto
- perfil `e2e-pg` com PostgreSQL/Testcontainers

Esses pontos nao bloqueiam o encerramento da Fase 5. Eles pertencem a hardening adicional ou a
fases posteriores do plano.

## Saida formal da fase

Com a Fase 5 encerrada:

- o starter ja possui discovery semantico de workflow actions sobre operacoes reais
- o catalogo de actions ja esta endurecido para uso corporativo basico nos escopos `ITEM` e `COLLECTION`
- a proxima fase canonicamente correta e a Fase 6 de capabilities unificadas
