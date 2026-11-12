# Enterprise Availability Adoption

## Objetivo

Este guia transforma a SPI de availability do `praxis-metadata-starter` em
um trilho canonico de adocao corporativa. Ele nao cria um novo contrato:
organiza como hosts devem usar `ResourceOperationAvailabilityProvider`,
`ActionAvailabilityRule`, `SurfaceAvailabilityRule` e
`ResourceStateSnapshotProvider` sem espalhar regras por controllers, services ou
clientes.

Classificacao de aderencia: `ja-suportado-so-ux` para hosts que ainda nao materializam
a SPI e `lacuna-real-de-contrato` encerrada no starter para operacoes query/stats.

O starter ja sabe publicar availability em operacoes, surfaces, actions,
`/capabilities` e `_links`. A lacuna nao e uma nova SPI; e a disciplina de uso,
evidencia e explicacao para que cada host pluge suas regras privadas atras da
fronteira publica correta.

## Regra canonica

Use cada eixo para o tipo de decisao que ele governa:

| Necessidade | Fonte canonica | Exemplo |
| --- | --- | --- |
| Disponibilidade de operacao canonica | `ResourceOperationAvailabilityProvider` | `create`, `edit`, `delete`, `all`, `filter`, `cursor`, `options`, `optionSources`, `stats*`, `export` |
| Disponibilidade de comando de negocio | `ActionAvailabilityRule` | `approve`, `reject`, `bulk-approve` |
| Disponibilidade de experiencia UI discoverable | `SurfaceAvailabilityRule` | `profile`, `detail`, `wizard`, `related-list` |
| Estado compartilhado do item | `ResourceStateSnapshotProvider` | `ACTIVE`, `INACTIVE`, `LOCKED`, `CLOSED` |
| Snapshot agregado para runtime/IA | `GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities` | operacoes, actions, surfaces e stats atuais |

Nao use services locais como segunda fonte de permissao publica. Se o service
precisa chamar um guard privado, ele deve chama-lo atras da SPI correta e
publicar apenas uma decisao segura.

## Operacoes de recurso

Use `ResourceOperationAvailabilityProvider` quando a pergunta for:

- o recurso pode criar agora?
- este item pode ser editado agora?
- este item pode ser excluido agora?
- este item pode gerar um rascunho editavel?
- a colecao pode exportar ou calcular stats agora?
- o principal pode consultar dados nominais por `filter/cursor` sem receber implicitamente acesso agregado?

O provider recebe `ResourceOperationAvailabilityContext` com:

- `resourceKey`
- `resourcePath`
- `operationId`
- `scope`
- `resourceId`, quando item-level
- `resourceState`, quando houver snapshot
- `metadata` publica do contexto, incluindo `preferredMethod`, `preferredRel` e
  `supported` quando a operacao vier do snapshot canonico

Essas chaves estruturais permitem que o host alinhe availability a classes de
operacao, como exigir autenticacao para `POST`, `PUT`, `PATCH` e `DELETE`, sem
inferir comportamento por nome de operacao ou duplicar um catalogo local. Elas
descrevem a operacao; nao substituem o enforcement do host.

O provider deve encapsular policy privada, motor legado, autorizacao, agenda,
tenant, sessao ou regra regulatoria. Ele nao deve publicar nomes internos de
package, SQL, HADES, ROWID, usuario tecnico, locators ou detalhes de banco.

IDs estaveis recebidos pelo provider incluem `all`, `byId`, `filter`, `cursor`,
`options`, `optionSources`, `statsGroupBy`, `statsTimeSeries`,
`statsDistribution`, `statsComparison` e `export`, alem das affordances CRUD
`create`, `view`, `edit`, `delete` e `duplicate-draft`. O `scope` pertence a
operacao: mesmo em um snapshot de item, uma operacao query/stats continua
`COLLECTION` e nao recebe `resourceId` ou `resourceState`.

`canonicalOperations` responde somente se a operacao faz parte do contrato
estrutural. A decisao atual esta em `operations.{operationId}.availability`.
Nenhum desses blocos substitui o enforcement do `SecurityFilterChain`.

## Workflow actions

Use `ActionAvailabilityRule` quando a decisao pertencer a uma action publicada
por `@WorkflowAction`.

Exemplos:

- `POST /employees/{id}/actions/approve`
- `POST /employees/actions/bulk-approve`
- `POST /orders/{id}/actions/cancel`

`@WorkflowAction.allowedStates` e `requiredAuthorities` cobrem restricoes
declarativas simples.

### Atalho Declarativo Zero-Boilerplate via `toResourceStateSnapshot(entity)` (`PLAT-EVO-01`)

Antes de criar classes `@Configuration` ou beans `ActionAvailabilityRule` /
`SurfaceAvailabilityRule` dedicados por recurso (`*ContextualAvailabilityConfig`),
prefira sobrescrever `toResourceStateSnapshot(E entity)` diretamente no `Service`
do recurso e popular as chaves declarativas nativas em `ResourceStateSnapshot.metadata()`:

- Actions (`@WorkflowAction`): `action.<id>.allowed` (`Boolean`), `action.<id>.reason`,
  `action.<id>.legalBasis`, `action.<id>.legalPurpose`, `action.<id>.customTitle`,
  `action.<id>.blockedByRule`;
- Surfaces (`@UiSurface`): `surface.<id>.allowed`, `surface.<id>.reason`,
  `surface.<id>.legalBasis`, `surface.<id>.legalPurpose`, `surface.<id>.customTitle`,
  `surface.<id>.blockedByRule`;
- Operacoes CRUD contextuais de item (`edit`, `delete`, `duplicate-draft`):
  `operation.<id>.allowed`, `operation.<id>.reason`, `operation.<id>.blockedByRule`;
- Defaults do recurso: `resource.legalBasis` e `resource.legalPurpose` (aplicados com
  precedencia deterministica `action.<id>.legalBasis` -> `@WorkflowAction.legalBasisRef`
  -> `resource.legalBasis`).

`DefaultResourceAvailabilityService` avalia essas chaves automaticamente tanto em
`GET /{resource}/{id}/capabilities` quanto nos catalogos contextuais e `_links`,
mantendo fail-closed (`SNAPSHOT_UNAVAILABLE`) quando `allowedStates` for declarado
sem estado resolvido. Reserve `ActionAvailabilityRule`, `SurfaceAvailabilityRule`
ou `ContextualResourceActionService` apenas para politicas transversais ou que
dependam de sistemas externos ao service do recurso.

Para descoberta estrutural de pontos de decisao no PAP / Rules Studio (`PLAT-EVO-07`),
`ResourceDecisionPointCatalogService` (`deriveForController` e `deriveForResource`,
apoiado por `DtoMetadataFactCatalogEnricher`) introspeciona automaticamente
`@ApiResource`, handlers MVC, `@WorkflowAction`, `@UiSurface`, `@BulkOperation`,
`@DomainGovernance` e `@DomainLineage`, publicando `ResourceDecisionPointDescriptor`
(`DecisionPointKind`: `RESOURCE_QUERY`, `RESOURCE_CREATE`, `RESOURCE_UPDATE`,
`RESOURCE_DELETE`, `COLLECTION_EXPORT`, `AI_TOOL_EXECUTION`, `WORKFLOW_ACTION`,
`SURFACE_AVAILABILITY`, `BULK_OPERATION`, `FIELD_PROJECTION`, `FIELD_WRITE`) sem
exigir registro manual por recurso.

Nao transforme action em CRUD por causa do metodo HTTP. O eixo canonico e a
intencao de negocio, nao o verbo.

## Surfaces

Use `SurfaceAvailabilityRule` quando a decisao pertencer a uma experiencia UI
publicada por `@UiSurface`.

Exemplos:

- form parcial `profile`
- detalhe contextual `detail`
- wizard de item
- related-list ou surface analitica

`@UiSurface` descreve a experiencia sobre uma operacao real. Ela nao cria
payload, schema ou permissao paralela. O schema continua em `/schemas/filtered`;
a disponibilidade contextual vem de `/surfaces` e `/capabilities`.

## Collection vs item

Collection capabilities respondem "o que existe ou esta disponivel para a
colecao agora". Item capabilities respondem "o que este registro pode fazer
agora".

Regras:

- `GET /{resource}/capabilities` nao substitui
  `GET /{resource}/{id}/capabilities` quando a decisao depende de estado do item.
- catalogos globais de `/schemas/actions` e `/schemas/surfaces` sao discovery.
  Para decisao real de item, use endpoints item-level.
- quando uma action ou surface `ITEM` aparece como
  `resource-context-required`, isso significa que ela existe, mas precisa de
  `resourceId` para decidir.
- restricoes independentes do item sao avaliadas antes do contexto: uma surface
  `ITEM` sem `resourceId` retorna `missing-authority` para um principal inelegivel
  e `resource-context-required` somente depois que as authorities exigidas passam.

## Metadata publica recomendada

Use `AvailabilityDecision.metadata` para explicar a decisao sem vazar a policy
privada.

Chaves recomendadas:

- `policy`: nome publico e estavel da politica, como `employee-operation-policy`
- `publicReason`: texto seguro para UI, cockpit ou auditoria
- `blockedOperation`: operacao canonica bloqueada
- `contextual`: `true` quando a decisao depende de contexto ou item
- `resourceState`: estado publico do recurso, quando seguro
- `requiredAuthorities`: authorities publicas quando ja forem parte do contrato
- `missingAuthorities`: authorities ausentes quando puderem ser exibidas
- `allowedStates`: estados publicos aceitos quando declarados na annotation

Evite:

- SQL, package, stack trace ou procedure
- HADES, Oracle, ROWID, Archon, locators internos ou nomes de guards privados
- identificadores de tenant, usuario, sessao ou empresa que nao sejam parte do
  contrato publico
- mensagem interna de exception

## Explicacao para Cockpit e IA

Clientes semanticos devem distinguir:

| Estado | Como interpretar |
| --- | --- |
| `unsupported` | a operacao/action/surface nao existe para o recurso |
| `supported-but-denied` | existe, mas `availability.allowed=false` no contexto atual |
| `collection-scope` | decisao feita sem `resourceId` |
| `item-scope` | decisao feita para um item concreto |
| `requires-resource-context` | existe, mas a consulta global nao tem item suficiente |

O Cockpit e assistentes devem explicar primeiro a decisao publica e, depois,
apontar a superficie canonica para investigacao:

- operacao de recurso: `/capabilities.operations`
- workflow: `/actions` ou `/capabilities.actions`
- experiencia UI: `/surfaces` ou `/capabilities.surfaces`
- schema estrutural: `/schemas/filtered`

## Evitando N+1

Quando availability de action, surface e operation depender do mesmo estado do
item, publique um `ResourceStateSnapshotProvider` por recurso. O starter usa o
snapshot compartilhado no fluxo contextual para evitar que cada rule recarregue
o mesmo registro.

Boas praticas:

- o snapshot deve carregar somente estado publico e metadata minima
- rules devem ler o snapshot em vez de chamar repository diretamente
- providers privados podem consultar sistemas externos, mas devem cachear ou
  agrupar no host quando a consulta puder ser repetida por item
- testes de host devem cobrir uma colecao com varios itens para detectar N+1
  evitavel

## Exemplo non-Ergon

A fixture E2E do starter demonstra o padrao sem depender de Ergon:

- `EmployeeSurfaceStateSnapshotProvider` publica estado publico de employee
- `EmployeeResourceOperationAvailabilityProvider` bloqueia `delete` quando o
  employee esta em `LEAVE`
- `@WorkflowAction(id = "approve")` usa estado `INACTIVE`
- `@UiSurface(id = "profile")` usa estado `ACTIVE`
- `CapabilityConsistencyE2ETest` prova que `/capabilities` e `_links`
  concordam para a operacao negada
- `ActionCatalogE2ETest`, `SurfaceCatalogE2ETest` e
  `HypermediaDiscoveryE2ETest` provam que actions e surfaces mantem a mesma
  disponibilidade publica nos endpoints dedicados e no snapshot agregado

Esse exemplo e propositalmente host-neutral. Em um host corporativo, HADES,
Oracle, Archon, IAM ou motor regulatorio ficam atras da SPI. Nenhum desses nomes
deve virar semantica publica do Praxis.

## Checklist de aceite para hosts

Antes de declarar um recurso pronto para migracao assistida por IA:

- `_links` e `/capabilities.operations` concordam para create/edit/delete,
  all/filter/cursor e export
- query e stats protegidos possuem ao menos um teste allow e um deny por principal
- `canonicalOperations=true` nunca e interpretado como autorizacao
- action catalog e `capabilities.actions` concordam no mesmo contexto
- surface catalog e `capabilities.surfaces` concordam no mesmo contexto
- item-level availability foi testada com pelo menos um allow e um deny
- denial usa metadata publica segura
- provider falhando resulta em fail-closed para operacoes protegidas
- collection capabilities nao sao usadas como substituto de item capabilities
- testes focais cobrem o host sem depender de regra privada especifica no contrato
