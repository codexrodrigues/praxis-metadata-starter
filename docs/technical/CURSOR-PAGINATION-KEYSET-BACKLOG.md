# Cursor Pagination / Keyset Backlog

## Objetivo

Transformar o plano de keyset pagination do starter em um backlog técnico
executável, arquivo por arquivo, para o v1 canônico da plataforma.

Este backlog assume as decisões do documento
[CURSOR-PAGINATION-KEYSET-PLAN.md](CURSOR-PAGINATION-KEYSET-PLAN.md):

- usar primitives do Spring Data Commons para scrolling/keyset
- centralizar a infraestrutura no `praxis-metadata-starter`
- restringir o v1 ao caso canônico
- manter `CursorPage` como DTO externo
- explicitar elegibilidade/capability por recurso

## Escopo do V1

### Incluído

- `after` e `before`
- sort estável
- ID simples como tie-breaker
- recursos CRUD e read-only elegíveis
- integração com `Specification`
- adaptação para `CursorPage`

### Excluído

- joins complexos como chave de cursor
- sort arbitrário sem governança
- campos calculados
- coleções/nested sorts
- assinatura/HMAC do cursor
- contrato universal para recursos inelegíveis

## Estratégia de Entrega

### Lote 1

Infraestrutura mínima no starter:

- tipos internos de contrato
- codec de cursor
- governança de sort estável
- capability explícita

### Lote 2

Implementação JPA real no starter:

- execução keyset real
- adaptação `Window -> CursorPage`
- integração com service base
- uso preferencial das primitives já expostas por `JpaSpecificationExecutor`

### Lote 3

Controller/documentação/testes consumidores:

- endpoint com capability governada e documentação honesta por recurso
- testes integrados no starter
- validação no `praxis-api-quickstart`

## Backlog Arquivo por Arquivo

### 1. Contrato e tipos internos

#### `src/main/java/org/praxisplatform/uischema/dto/CursorPage.java`

Objetivo:
- revisar o DTO externo para garantir compatibilidade com `Window<T>`

Tarefas:
- confirmar se o shape atual é suficiente para `next`, `prev` e `size`
- decidir se precisa de metadados adicionais no v1
- preservar backward compatibility do contrato HTTP

Critério de aceite:
- `CursorPage` continua sendo o DTO externo único do starter para cursor

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/service/cursor/CursorSupportMode.java`

Objetivo:
- definir capability explícita por recurso

Tarefas:
- criar enum inicial com pelo menos:
  - `AUTO`
  - `DISABLED`

Critério de aceite:
- existe um contrato claro para dizer se o recurso suporta cursor

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/service/cursor/CursorEligibility.java`

Objetivo:
- encapsular a avaliação de elegibilidade do v1

Tarefas:
- modelar regras mínimas:
  - ID simples
  - sort estável baseado em campos escalares
  - sem coleções/nested sorts
  - sem depender de joins complexos como chave do cursor
- expor utilitário/serviço reutilizável

Critério de aceite:
- o starter consegue decidir, de forma centralizada, se um recurso é elegível

### 2. Codec e governança de cursor

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/service/cursor/CursorTokenCodec.java`

Objetivo:
- serializar/deserializar o cursor opaco

Tarefas:
- modelar payload interno do cursor
- usar JSON + base64url
- suportar direção (`after`/`before`)

Critério de aceite:
- cursor opaco gerado e lido sem expor diretamente a estrutura ao frontend

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/service/cursor/CursorSortNormalizer.java`

Objetivo:
- transformar `Sort` em sort cursor-safe

Tarefas:
- definir a fonte canônica do sort do v1:
  - `sort` explícito somente quando cursor-safe
  - fallback para `getDefaultSort()`
  - resolução consistente de aliases/campos de `FilterDTO` para property path JPA
- anexar ID como tie-breaker
- rejeitar sort inelegível

Critério de aceite:
- todo fluxo de cursor passa por um sort estável e determinístico

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/service/cursor/CursorProperties.java`

Objetivo:
- centralizar configuração do v1

Tarefas:
- modelar propriedades como:
  - `enabled`
  - `maxSize`
  - `defaultMode`
  - `publishEndpointStrategy` ou semântica equivalente, se necessário
- decidir explicitamente se o binding será via `@ConfigurationProperties` ou se o
  v1 ficará alinhado ao padrão atual do starter com `@Value`

Critério de aceite:
- configurações de cursor ficam governadas via Spring Boot properties

### 3. Execução JPA keyset

#### `src/main/java/org/praxisplatform/uischema/repository/base/BaseCrudRepository.java`

Objetivo:
- confirmar se o contrato atual do repositório base já é suficiente para o v1

Tarefas:
- validar o uso de `JpaSpecificationExecutor#findBy(..., q -> q.scroll(...))`
  com `Specification`
- só introduzir fragmento custom se houver limitação real da stack atual
  para o caso canônico do v1

Critério de aceite:
- o backlog do v1 não cria infraestrutura redundante no repositório base

#### Opcional: `src/main/java/org/praxisplatform/uischema/repository/base/CursorScrollableRepository.java`

Objetivo:
- existir apenas como fallback arquitetural, não como premissa obrigatória

Tarefas:
- criar somente se a prova de conceito com `JpaSpecificationExecutor` mostrar
  limitação concreta
- se existir, declarar método de scroll keyset com:
  - `Specification<E>`
  - `Sort`
  - `ScrollPosition`/`KeysetScrollPosition`
  - `limit`

Critério de aceite:
- o fragmento só entra quando simplificar de fato a plataforma

#### Opcional: `src/main/java/org/praxisplatform/uischema/repository/base/CursorScrollableRepositoryImpl.java`

Objetivo:
- implementar execução keyset real apenas se a via nativa do Spring Data não
  bastar para o v1

Tarefas:
- encapsular a limitação encontrada na prova de conceito
- manter o mesmo contrato de elegibilidade e sort normalizado do v1
- suportar `after` e `before`

Critério de aceite:
- a implementação adicional reduz complexidade total; não a aumenta sem ganho

### 4. Integração no service base

#### `src/main/java/org/praxisplatform/uischema/service/base/BaseCrudService.java`

Objetivo:
- substituir o default puramente não implementado por capability governada

Tarefas:
- adicionar API canônica de suporte a cursor
- decidir comportamento de `supportsCursorPagination()`
- integrar `CursorSupportMode`
- distinguir claramente:
  - recurso globalmente desabilitado
  - recurso habilitado mas inelegível no v1
  - recurso elegível e operacional

Critério de aceite:
- a ausência de suporte deixa de ser implícita e passa a ser governada

#### `src/main/java/org/praxisplatform/uischema/service/base/AbstractBaseCrudService.java`

Objetivo:
- fornecer implementação padrão reutilizável de `filterByCursor(...)`

Tarefas:
- implementar a primeira versão usando preferencialmente
  `JpaSpecificationExecutor#findBy(..., q -> q.sortBy(...).scroll(...))`
- reaproveitar `GenericSpecificationsBuilder` para produzir `Specification`
  e o mapping de sort já existente, sem duplicar semântica em outra camada
- adaptar `Window<E>` para `CursorPage<E>`
- aplicar `CursorSortNormalizer`

Critério de aceite:
- recursos elegíveis passam a suportar cursor sem override manual por app

#### `src/main/java/org/praxisplatform/uischema/service/base/AbstractReadOnlyService.java`

Objetivo:
- herdar a implementação padrão sem duplicação

Tarefas:
- validar que recursos read-only elegíveis também entram no fluxo

Critério de aceite:
- read-only e CRUD simples compartilham a mesma infraestrutura cursorável

### 5. Controller e exposição do endpoint

#### `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java`

Objetivo:
- alinhar a publicação de `/filter/cursor` com suporte real

Tarefas:
- revisar comportamento de `501`
- no v1, manter o endpoint publicado e governar suporte real por capability e
  documentação, evitando tentar despublicação dinâmica por recurso nesta fase
- manter validação de `size`

Critério de aceite:
- a superfície publicada não promete mais suporte inexistente sem governança

#### `src/main/java/org/praxisplatform/uischema/controller/base/AbstractReadOnlyController.java`

Objetivo:
- garantir consistência com o controller base read-only

Tarefas:
- revisar herança e documentação do endpoint de cursor

Critério de aceite:
- mesma semântica entre controllers CRUD e read-only

### 6. Auto-configuração

#### `src/main/java/org/praxisplatform/uischema/configuration/OpenApiUiSchemaAutoConfiguration.java`

Objetivo:
- registrar beans necessários do v1

Tarefas:
- registrar `CursorProperties`
- registrar codec/normalizer/serviços auxiliares
- expor beans com `@ConditionalOnMissingBean` quando fizer sentido
- evitar mexer no agregador sem necessidade; preferir encaixar o v1 na
  auto-configuração já existente do starter

Critério de aceite:
- apps consumidores recebem a infraestrutura cursorável automaticamente

#### `src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java`

Objetivo:
- revisar se o módulo agregador precisa importar algo explicitamente

Tarefas:
- confirmar apenas que a auto-configuração cursorável continua no caminho de
  inicialização; não abrir refactor do agregador sem necessidade concreta

Critério de aceite:
- o starter sobe com a capability sem wiring manual no app consumidor

### 7. Documentação pública

#### `README.md`

Objetivo:
- ajustar narrativa pública sobre paginação por cursor

Tarefas:
- deixar explícito que keyset é suportado apenas em recursos elegíveis no v1

Critério de aceite:
- o README não promete cursor universal se o v1 for governado por elegibilidade

#### `docs/technical/CURSOR-PAGINATION-KEYSET-PLAN.md`

Objetivo:
- manter o plano estratégico alinhado ao que foi implementado

Tarefas:
- atualizar status do rollout conforme cada lote avançar

Critério de aceite:
- o documento continua sendo o norte técnico de arquitetura

#### Novo arquivo: `docs/technical/CURSOR-PAGINATION-KEYSET-V1.md`

Objetivo:
- documentar a implementação efetiva do v1 depois que sair do papel

Tarefas:
- descrever:
  - capability
  - restrições
  - propriedades
  - exemplos de uso

Critério de aceite:
- existe uma doc operacional do v1, não só um plano

### 8. Testes no starter

#### Novo arquivo: `src/test/java/org/praxisplatform/uischema/service/cursor/CursorTokenCodecTest.java`

Objetivo:
- cobrir encode/decode do cursor opaco

#### Novo arquivo: `src/test/java/org/praxisplatform/uischema/service/cursor/CursorSortNormalizerTest.java`

Objetivo:
- cobrir sort estável, tie-break por ID e rejeição de sort inelegível

#### Novo arquivo: `src/test/java/org/praxisplatform/uischema/service/cursor/CursorJpaKeysetIntegrationTest.java`

Objetivo:
- validar keyset real com JPA/H2 pela via escolhida no v1

Observação:
- se o v1 ficar na via nativa de `JpaSpecificationExecutor`, o teste cobre essa
  integração
- se um fragmento custom for introduzido depois, ele ganha teste dedicado

Cenários mínimos:
- asc
- desc
- empate por ID
- `after`
- `before`
- filtro + sort

#### `src/test/java/org/praxisplatform/uischema/controller/base/...`

Objetivo:
- validar `501` apenas para recurso explicitamente inelegível
- validar `200` em recurso elegível real

### 9. Validação consumidora

#### `praxis-api-quickstart/src/test/java/...`

Objetivo:
- provar integração real sem mock do service

Recursos candidatos iniciais:
- um CRUD simples com sort canônico já estável, por exemplo `Cargo`,
  `Departamento` ou `Equipe`
- um read-only simples com sort escalar e baixo acoplamento relacional
- deixar `MencoesMidia`, `VwRankingReputacao` e `Missao` para rodada posterior,
  se continuarem elegíveis após a prova do v1

Critério de aceite:
- pelo menos um recurso CRUD e um recurso read-only passam em `/filter/cursor`
  no app consumidor real

## Ordem Recomendada de Execução

1. tipos internos + propriedades
2. codec de cursor
3. normalização de sort
4. prova de conceito com `JpaSpecificationExecutor#findBy(...).scroll(...)`
5. integração no service base
6. ajuste do controller base
7. testes JPA do starter
8. teste consumidor no quickstart
9. introduzir fragmento custom apenas se a prova de conceito falhar
10. atualização final de docs

## Definition of Done do V1

O v1 só deve ser considerado pronto quando:

- pelo menos um recurso CRUD simples suportar `/filter/cursor` real
- pelo menos um recurso read-only simples suportar `/filter/cursor` real
- o sort for sempre estável e com tie-break por ID
- recursos inelegíveis não prometerem suporte de forma enganosa
- houver testes cobrindo `after`, `before`, asc, desc e empate por ID
- a documentação pública descrever claramente as restrições do v1

## Riscos a Monitorar

- generalização prematura para sorts arbitrários
- suporte implícito publicado sem capability real
- drift entre starter e quickstart
- cursores frágeis por dependerem de colunas não determinísticas
- comportamento incorreto com `nulls`

## Próxima Ação Recomendada

Começar pelo lote de infraestrutura mínima:

- `CursorSupportMode`
- `CursorEligibility`
- `CursorTokenCodec`
- `CursorSortNormalizer`
- `CursorProperties`

Na sequência imediata, provar o caminho mais curto com a stack atual:

- `JpaSpecificationExecutor#findBy(..., q -> q.sortBy(...).scroll(...))`
- adaptação `Window -> CursorPage`
- capability governada no service

Só depois disso decidir se existe motivo real para abrir um fragmento custom de
repositório no v1.
