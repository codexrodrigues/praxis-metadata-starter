# Filtered Stats Plan

## Contexto

O `praxis-metadata-starter` já centraliza a superfície canônica de leitura para
recursos genéricos:

- `POST /filter`
- `POST /filter/cursor`
- `POST /locate`
- `POST /options/filter`

Essas operações resolvem listagem, paginação, locate e projeções leves, mas a
plataforma ainda não oferece uma forma canônica de consultar KPIs e agregações
sobre o mesmo conjunto filtrado.

Na prática, isso empurra apps consumidores para uma das alternativas erradas:

- criar endpoints ad hoc por recurso
- duplicar a tradução de `FilterDTO -> Specification`
- expor contratos arbitrários e inconsistentes de analytics
- misturar necessidades simples de dashboard com consultas específicas demais

## Direção de Plataforma

A solução correta de plataforma é incorporar uma família canônica de
estatísticas filtradas no `praxis-metadata-starter`, reaproveitando a mesma
semântica de filtro já usada em `/filter`.

O objetivo não é criar uma DSL analítica universal. O objetivo é suportar o
caso canônico de produto:

- KPIs por agrupamento
- séries temporais simples
- distribuições simples

com:

- contrato HTTP estável
- capability explícita por recurso
- governança de campos e métricas
- reutilização da `Specification<E>` já gerada pelo starter

## Estado Atual do V1

O plano abaixo já está parcialmente implementado no starter.

Status atual da superfície:

- `POST /stats/group-by`
  - suportado
  - métricas: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
- `POST /stats/timeseries`
  - suportado
  - granularidades: `DAY`, `WEEK`, `MONTH`
  - métricas: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
- `POST /stats/distribution`
  - suportado
  - `mode=TERMS`: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
  - `mode=HISTOGRAM`: `COUNT`

Governança atual:

- capability explícita por recurso
- `StatsFieldRegistry` e `StatsFieldDescriptor` com elegibilidade por papel
- `metric.field` como fonte canônica para métricas numéricas

Restrições deliberadas ainda vigentes:

- `HISTOGRAM` continua restrito a `COUNT`
- não há suporte a múltiplas dimensões
- não há percentis, pivôs ou fórmulas arbitrárias

## Superfície Canônica Proposta

### Endpoints

- `POST /stats/group-by`
- `POST /stats/timeseries`
- `POST /stats/distribution`

Todos devem operar sobre o mesmo universo filtrado pelo `FD extends
GenericFilterDTO`.

### Regra de Design

O frontend não deve mandar uma consulta analítica arbitrária no estilo SQL ou
Elasticsearch. O starter deve aceitar apenas requests tipados, pequenos e
governados.

## Escopo Seguro do V1

### Incluído

- reutilização do `FD` já existente por recurso
- `count` como métrica obrigatória
- `sum`, `avg`, `min`, `max` como métricas opcionais para campos elegíveis
- `group-by` unidimensional
- `timeseries` simples por campo temporal elegível
- `distribution` em modo `terms` e `histogram` básico
- capability explícita por recurso
- governança de campos agregáveis

### Excluído

- múltiplas dimensões no mesmo request
- pivôs
- percentis
- fórmulas arbitrárias
- joins complexos para agregação
- métricas derivadas compostas
- nested aggregations
- contrato analítico universal para qualquer recurso/campo

## Contratos Canônicos

Além do contrato HTTP dos endpoints, o starter agora também consegue refletir
exemplos operacionais no catálogo derivado de `/schemas/filtered`:

- `x-ui.operationExamples.request`
- `x-ui.operationExamples.response`

Esse bloco é derivado dos `examples`/`example` publicados no OpenAPI da
operação e serve para catálogos, playgrounds e frontends metadata-driven que
precisem exibir exemplos prontos de uso sem manter documentação paralela.

Observações de contrato:

- o catálogo publica apenas o lado coerente com o `schemaType` solicitado
- esse bloco é documental e não participa do hash estrutural de
  `/schemas/filtered`

### 1. Group By

Endpoint:

- `POST /stats/group-by`

Request canônico:

- `filter`
- `field`
- `metric`
- `limit`
- `orderBy`

Semântica mínima:

- agrupa por um campo elegível
- calcula uma métrica por bucket
- permite limitar buckets e ordenar por chave ou valor

Exemplo conceitual:

```json
{
  "filter": {
    "status": "ATIVO"
  },
  "field": "departamentoId",
  "metric": {
    "operation": "count"
  },
  "limit": 10,
  "orderBy": "value_desc"
}
```

### 2. Timeseries

Endpoint:

- `POST /stats/timeseries`

Request canônico:

- `filter`
- `field`
- `granularity`
- `metric`
- `from`
- `to`
- `fillGaps`

Semântica mínima:

- agrega por um campo temporal elegível
- suporta granularidade controlada (`day`, `week`, `month`)
- pode preencher lacunas quando aplicável

### 3. Distribution

Endpoint:

- `POST /stats/distribution`

Request canônico:

- `filter`
- `field`
- `mode`
- `metric`
- `bucketSize`
- `bucketCount`
- `limit`

Semântica mínima:

- `terms` para enum/texto canônico elegível
- `histogram` para número/data em cenários simples

## Respostas Canônicas

O v1 deve ter DTOs explícitos para evitar payloads ambíguos.

### GroupByResponse

- `metric`
- `field`
- `buckets`

Cada bucket:

- `key`
- `label`
- `value`
- `count`

### TimeSeriesResponse

- `metric`
- `field`
- `granularity`
- `points`

Cada ponto:

- `start`
- `end`
- `label`
- `value`
- `count`

Contrato JSON estabilizado:

- `start` e `end` são serializados como string ISO `yyyy-MM-dd`
- `label` é a representação textual estável do bucket
- `value` representa a métrica solicitada
- `count` permanece presente mesmo quando `value` vem de métrica numérica

Exemplo:

```json
{
  "field": "createdOn",
  "granularity": "DAY",
  "metric": {
    "operation": "SUM",
    "field": "salary"
  },
  "points": [
    {
      "start": "2026-03-01",
      "end": "2026-03-01",
      "label": "2026-03-01",
      "value": 25.0,
      "count": 2
    }
  ]
}
```

### DistributionResponse

- `metric`
- `field`
- `mode`
- `buckets`

Cada bucket:

- `from`
- `to`
- `key`
- `label`
- `value`
- `count`

## Governança de Capability

Stats não devem ser implicitamente suportadas por todo recurso.

O starter já possui `ResourceCapabilities`. O caminho correto é estender essa
família com capacidades específicas para stats, por exemplo:

- `statsGroupBy`
- `statsTimeSeries`
- `statsDistribution`

No nível do service, a capability precisa distinguir pelo menos:

- recurso globalmente desabilitado
- recurso sem suporte a stats
- recurso com suporte parcial
- recurso com suporte operacional

## Governança de Campos e Métricas

O ponto mais importante do desenho é impedir agregação arbitrária.

O v1 precisa de uma camada canônica para declarar:

- quais campos podem ser usados em `group-by`
- quais campos temporais podem ser usados em `timeseries`
- quais campos podem ser usados em `distribution`
- quais métricas cada campo suporta

Isso pode ser modelado com um registry explícito por recurso, por exemplo:

- `StatsFieldRegistry`
- `StatsFieldDescriptor`
- `StatsMetric`
- `StatsOperationType`

Exemplos de regras:

- `departamentoId` suporta `group-by` + `count`
- `dataAdmissao` suporta `timeseries` + `count`
- `salario` suporta `distribution(histogram)` + `avg` + `min` + `max`

## Reuso da Infraestrutura Atual

O starter já tem a peça mais valiosa: a tradução de `FD -> Specification<E>`.

O fluxo recomendado do v1 é:

1. receber request tipado de stats
2. gerar `Specification<E>` a partir do `filter`
3. resolver o campo canônico para property path JPA governado
4. montar a agregação com Criteria API
5. adaptar o resultado para DTO canônico

Isso evita:

- duplicar lógica de filtro
- drift entre `/filter` e `/stats/*`
- controllers específicos por app consumidor

## Papel de Cada Camada

### DTOs

- request/response estáveis para cada operação
- enums de métrica, granularidade e modo

### Service Base

- aplica capability
- valida elegibilidade do campo/métrica
- constrói a `Specification`
- delega execução ao executor analítico

### Executor Analítico

- traduz requests tipados em Criteria API
- resolve `group by`, truncamento temporal e histogramas simples
- devolve linhas agregadas neutras para adaptação

### Controller Base

- publica `/stats/group-by`, `/stats/timeseries`, `/stats/distribution`
- aplica limites
- converte `UnsupportedOperationException` em `501` quando necessário

### Documentação / Catálogo

- expõe capabilities novas no `ApiDocsController`
- documenta a superfície de stats por recurso

## Arquitetura Recomendada do V1

### 1. Começar por `count`

O primeiro ganho de produto vem de:

- `group-by + count`
- `timeseries + count`
- `distribution + count`

Métricas numéricas adicionais entram apenas se o campo estiver explicitamente
governado.

Status:

- concluído para `group-by`
- concluído para `timeseries`
- concluído para `distribution` em `TERMS`
- ainda restrito a `COUNT` em `distribution/HISTOGRAM`

### 2. Reutilizar Criteria API

Não abrir uma camada de query arbitrária. Para o v1, o executor pode operar com:

- `CriteriaBuilder`
- `CriteriaQuery<Tuple>`
- `groupBy`
- funções temporais simples por dialeto, quando necessário

### 3. Restringir o problema temporal

`timeseries` só deve aceitar campos temporais explicitamente elegíveis e um
conjunto pequeno de granularidades.

### 4. Fazer `distribution` em dois modos

- `terms`: enum/texto controlado
- `histogram`: campo numérico elegível com buckets simples

## Backlog Arquivo por Arquivo

### 1. Contrato e tipos canônicos

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/StatsMetric.java`

Objetivo:

- definir o conjunto canônico de métricas do v1

Tarefas:

- criar enum inicial com:
  - `COUNT`
  - `SUM`
  - `AVG`
  - `MIN`
  - `MAX`

Critério de aceite:

- as métricas suportadas deixam de ser strings soltas no contrato HTTP

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/TimeSeriesGranularity.java`

Objetivo:

- definir granularidade canônica do v1

Tarefas:

- criar enum inicial com:
  - `DAY`
  - `WEEK`
  - `MONTH`

Critério de aceite:

- `timeseries` opera com granularidade explícita e governada

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/DistributionMode.java`

Objetivo:

- definir os modos de distribuição do v1

Tarefas:

- criar enum inicial com:
  - `TERMS`
  - `HISTOGRAM`

Critério de aceite:

- o endpoint de distribuição tem semântica controlada e estável

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/dto/StatsMetricRequest.java`

Objetivo:

- modelar a métrica solicitada pelo cliente

Tarefas:

- suportar pelo menos:
  - `operation`
  - `field` opcional para métricas numéricas

Critério de aceite:

- o contrato de métrica fica reutilizável entre `group-by`, `timeseries` e
  `distribution`

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/dto/GroupByStatsRequest.java`

Objetivo:

- definir request canônico de `/stats/group-by`

Tarefas:

- incluir:
  - `filter`
  - `field`
  - `metric`
  - `limit`
  - `orderBy`

Critério de aceite:

- `group-by` possui request estável e tipado

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/dto/TimeSeriesStatsRequest.java`

Objetivo:

- definir request canônico de `/stats/timeseries`

Tarefas:

- incluir:
  - `filter`
  - `field`
  - `granularity`
  - `metric`
  - `from`
  - `to`
  - `fillGaps`

Critério de aceite:

- `timeseries` possui request estável e tipado

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/dto/DistributionStatsRequest.java`

Objetivo:

- definir request canônico de `/stats/distribution`

Tarefas:

- incluir:
  - `filter`
  - `field`
  - `mode`
  - `metric`
  - `bucketSize`
  - `bucketCount`
  - `limit`

Critério de aceite:

- `distribution` possui request estável e tipado

#### Novos arquivos em `src/main/java/org/praxisplatform/uischema/stats/dto/response/...`

Objetivo:

- definir responses canônicas do v1

Tarefas:

- criar:
  - `GroupByStatsResponse`
  - `TimeSeriesStatsResponse`
  - `DistributionStatsResponse`
  - DTOs auxiliares de bucket/ponto

Critério de aceite:

- cada endpoint retorna payload estável e semanticamente claro

### 2. Capability e governança

#### `src/main/java/org/praxisplatform/uischema/annotation/ResourceCapabilities.java`

Objetivo:

- expor suporte a stats no catálogo da plataforma

Tarefas:

- adicionar:
  - `statsGroupBy`
  - `statsTimeSeries`
  - `statsDistribution`

Critério de aceite:

- a plataforma consegue documentar suporte de stats por recurso

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/StatsSupportMode.java`

Objetivo:

- definir capability explícita de stats

Tarefas:

- criar enum inicial com:
  - `AUTO`
  - `DISABLED`

Critério de aceite:

- o service possui contrato claro para expor suporte a stats

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/StatsFieldDescriptor.java`

Objetivo:

- descrever um campo elegível para stats

Tarefas:

- modelar:
  - nome canônico do campo
  - property path JPA
  - operações permitidas
  - métricas permitidas
  - tipo semântico do campo

Critério de aceite:

- governança de stats fica centralizada e explícita

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/StatsFieldRegistry.java`

Objetivo:

- expor o conjunto de campos elegíveis por recurso

Tarefas:

- definir API de leitura/registro dos campos governados

Critério de aceite:

- `group-by`, `timeseries` e `distribution` não dependem de campos arbitrários

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/StatsEligibility.java`

Objetivo:

- validar se request, campo e métrica são compatíveis com o v1

Tarefas:

- validar:
  - operação permitida
  - métrica permitida
  - tipo de campo compatível
  - restrições de `histogram` e `timeseries`

Critério de aceite:

- requests inválidos são rejeitados antes da execução JPA

### 3. Execução analítica

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/service/StatsQueryExecutor.java`

Objetivo:

- definir contrato interno de execução analítica

Tarefas:

- declarar operações para:
  - `groupBy`
  - `timeSeries`
  - `distribution`

Critério de aceite:

- o service base delega a execução a uma infraestrutura explícita

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/service/jpa/JpaStatsQueryExecutor.java`

Objetivo:

- implementar o executor analítico com JPA Criteria

Tarefas:

- receber:
  - `Specification<E>`
  - descritor do campo
  - request tipado
- implementar:
  - agregação por bucket
  - agrupamento temporal simples
  - distribuição `terms`
  - distribuição `histogram`

Critério de aceite:

- o starter consegue executar stats filtrados reais sem SQL arbitrário

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/service/jpa/StatsCriteriaSupport.java`

Objetivo:

- encapsular helpers de Criteria API

Tarefas:

- resolver path JPA governado
- aplicar função agregadora
- encapsular montagem de tuplas

Critério de aceite:

- a execução JPA fica reaproveitável e legível

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/service/jpa/TimeSeriesBucketExpressionFactory.java`

Objetivo:

- isolar a criação da expressão temporal por granularidade

Tarefas:

- suportar ao menos:
  - dia
  - semana
  - mês
- documentar limites de portabilidade entre dialetos

Critério de aceite:

- a complexidade de `timeseries` não vaza para o restante da infraestrutura

### 4. Integração no service base

#### `src/main/java/org/praxisplatform/uischema/service/base/BaseCrudService.java`

Objetivo:

- adicionar APIs canônicas de stats ao service base

Tarefas:

- adicionar defaults para:
  - `groupByStats(...)`
  - `timeSeriesStats(...)`
  - `distributionStats(...)`
- adicionar consultas de capability quando necessário

Critério de aceite:

- stats passam a ser capacidade formal do service base

#### `src/main/java/org/praxisplatform/uischema/service/base/AbstractBaseCrudService.java`

Objetivo:

- fornecer implementação padrão reutilizável

Tarefas:

- reaproveitar `GenericSpecificationsBuilder`
- validar capability e elegibilidade
- delegar ao `StatsQueryExecutor`
- adaptar resultado para DTO canônico

Critério de aceite:

- recursos elegíveis suportam stats sem código duplicado por app

#### `src/main/java/org/praxisplatform/uischema/service/base/AbstractReadOnlyService.java`

Objetivo:

- herdar a implementação padrão de stats

Tarefas:

- validar que recursos read-only elegíveis entram no fluxo sem duplicação

Critério de aceite:

- read-only e CRUD compartilham a mesma infraestrutura de stats

### 5. Controller e exposição REST

#### `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java`

Objetivo:

- publicar a superfície canônica de stats

Tarefas:

- adicionar:
  - `POST /stats/group-by`
  - `POST /stats/timeseries`
  - `POST /stats/distribution`
- aplicar limites e validações de request
- converter `UnsupportedOperationException` em `501`

Critério de aceite:

- a plataforma expõe endpoints de stats consistentes entre recursos

#### `src/main/java/org/praxisplatform/uischema/controller/base/AbstractReadOnlyController.java`

Objetivo:

- garantir semântica idêntica em recursos read-only

Tarefas:

- validar herança e documentação dos endpoints de stats

Critério de aceite:

- recursos read-only elegíveis compartilham a mesma surface REST

### 6. Auto-configuração

#### `src/main/java/org/praxisplatform/uischema/configuration/OpenApiUiSchemaAutoConfiguration.java`

Objetivo:

- registrar a infraestrutura de stats no starter

Tarefas:

- registrar:
  - `StatsQueryExecutor`
  - serviços auxiliares
  - propriedades quando aplicável
- usar `@ConditionalOnMissingBean` quando fizer sentido

Critério de aceite:

- apps consumidores recebem suporte a stats sem wiring manual

#### Novo arquivo: `src/main/java/org/praxisplatform/uischema/stats/StatsProperties.java`

Objetivo:

- centralizar governança configurável do v1

Tarefas:

- modelar propriedades como:
  - `enabled`
  - `maxBuckets`
  - `maxSeriesPoints`
  - `defaultMode`

Critério de aceite:

- limites operacionais de stats ficam governados por configuração

### 7. Catálogo e documentação automática

#### `src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java`

Objetivo:

- refletir os novos endpoints e capabilities no catálogo

Tarefas:

- incluir suffixes:
  - `/stats/group-by`
  - `/stats/timeseries`
  - `/stats/distribution`
- computar capabilities de stats

Critério de aceite:

- o schema/catalog consegue informar suporte a stats por recurso

#### `src/main/java/org/praxisplatform/uischema/controller/base/doc-files/endpoints-overview.html`

Objetivo:

- documentar a nova família de endpoints

Tarefas:

- incluir exemplos e limites de `group-by`, `timeseries` e `distribution`

Critério de aceite:

- a documentação pública da classe base cobre stats filtrados

#### `docs/technical/README.md`

Objetivo:

- indexar o novo documento técnico

Critério de aceite:

- a navegação técnica do starter aponta para o plano de stats

### 8. Testes no starter

#### Novo arquivo: `src/test/java/org/praxisplatform/uischema/stats/StatsEligibilityTest.java`

Objetivo:

- cobrir governança de campos, operações e métricas

#### Novo arquivo: `src/test/java/org/praxisplatform/uischema/stats/service/jpa/JpaStatsQueryExecutorTest.java`

Objetivo:

- validar agregações reais com JPA/H2

Cenários mínimos:

- `group-by + count`
- `timeseries + count`
- `distribution terms + count`
- `distribution histogram + count`
- filtro combinado via `Specification`

#### `src/test/java/org/praxisplatform/uischema/controller/base/...`

Objetivo:

- validar `501` para recurso sem suporte
- validar `200` para recurso elegível real

### 9. Validação consumidora

#### `praxis-api-quickstart/src/test/java/...`

Objetivo:

- provar integração real sem mocks

Recursos candidatos iniciais:

- um CRUD simples com campo categórico claro para `group-by`
- um CRUD ou read-only com campo temporal claro para `timeseries`
- um recurso com campo numérico simples para `distribution`

Critério de aceite:

- pelo menos um fluxo real de cada endpoint passa no consumidor

## Ordem Recomendada de Execução

1. enums e DTOs canônicos
2. capability e registry de campos
3. eligibility de stats
4. executor JPA de `group-by + count`
5. integração no service base
6. endpoints no controller base
7. catálogo/documentação automática
8. `timeseries + count`
9. `distribution terms + count`
10. `distribution histogram` para campos numéricos elegíveis
11. testes consumidores no quickstart
12. documentação operacional final

## Definition of Done do V1

O v1 só deve ser considerado pronto quando:

- `group-by`, `timeseries` e `distribution` existirem como endpoints canônicos
- todos os endpoints reutilizarem o mesmo `FD` do recurso
- houver capability explícita por recurso
- o starter impedir agregações arbitrárias por campo/métrica
- pelo menos um recurso CRUD e um read-only validarem stats reais
- houver testes cobrindo a execução JPA e a governança do contrato
- a documentação pública explicar claramente as restrições do v1

## Riscos a Monitorar

- transformar stats em DSL analítica genérica cedo demais
- permitir qualquer campo do DTO sem governança
- drift entre `/filter` e `/stats/*`
- alta dependência de dialeto em `timeseries`
- histogramas frágeis para tipos não numéricos
- payloads de resposta sem semântica estável

## Próxima Ação Recomendada

Começar pelo menor corte de plataforma com valor real:

- `GroupByStatsRequest`
- `GroupByStatsResponse`
- `StatsMetric`
- `StatsFieldRegistry`
- `StatsEligibility`
- `JpaStatsQueryExecutor` com `group-by + count`

Depois disso:

- integrar em `AbstractBaseCrudService`
- expor `POST /stats/group-by`
- validar no quickstart

Só então ampliar para `timeseries` e `distribution`.
