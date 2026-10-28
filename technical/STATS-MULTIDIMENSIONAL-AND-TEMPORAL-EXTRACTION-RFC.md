# RFC - Agregacao Multidimensional (Cross-Tab 2D/ND) e Extracoes Canonicas de Dimensao em `praxis.stats`

## Status

- estado: `proposed`
- issue: #301
- dependencias: #98 (`STATS-BUCKET-IDENTITY-RFC`), #99 (`STATS-COMPARISON-RFC`)
- classe: `contrato-publico + arquitetural + transversal`
- aderencia: `lacuna-real-de-contrato`

## Objetivo

Evoluir a superficie canonica `POST /{resource}/stats/group-by`, `GET /{resource}/capabilities` (`stats`) e `x-ui.analytics` no `praxis-metadata-starter` para suportar tres primitivas ortogonais de plataforma:

1. **Extracao/Transformacao Canonica de Dimensao (`StatsDimensionExtraction`) com `timezone` IANA**: permitir agrupar um campo elegivel nao apenas pelo seu valor bruto, mas por extracoes temporais ciclicas (`HOUR_OF_DAY`, `DAY_OF_WEEK`, `DAY_OF_MONTH`, `MONTH_OF_YEAR`, `QUARTER_OF_YEAR`) ou truncamentos cronologicos (`HOUR`, `DAY`, `WEEK`, `MONTH`, `QUARTER`, `YEAR`), tanto em **1D** quanto em **2D/ND**.
2. **Agrupamento Multidimensional / Cross-Tab Governado (`dimensions[]`)**: permitir agrupar por ate `praxis.stats.max-dimensions` (padrao `2`) dimensoes coordenadas em uma unica operacao, cobrindo cruzamentos **Categorico x Categorico**, **Categorico x Temporal** e **Temporal x Temporal** (como matrizes `dia da semana x hora` 7x24), sem introduzir endpoint paralelo ad-hoc.
3. **Densificacao de Dominio Finito e Medidas sobre Ocorrencias do Ciclo (`AVG_PER_CYCLE_OCCURRENCE`)**: distinguir formalmente contagem zero (`0`), valor metrico `null` (inclusive por ausencia de ocorrencia do ciclo em janelas parciais ou transicoes de DST) e bucket omitido, alem de suportar media normalizada pelo numero real de ocorrencias do bucket temporal dentro da janela `[from, to]`.

## Diagnostico do Contrato Atual (Por que nao criar endpoint separado)

Antes desta RFC:
- `GroupByStatsRequest` aceitava apenas `String field` (1D escalar) e `GroupByBucket` publicava apenas `Object key` e `String label`.
- `TimeSeriesStatsRequest` e `TimeSeriesGranularity` (`DAY`, `WEEK`, `MONTH`) modelavam apenas progressao cronologica linear contínua. Adicionar `HOUR` em `timeseries` produziria uma sequencia linear (`720` pontos em 30 dias), incapaz de consolidar os mesmos horarios ou dias da semana ao longo de varias semanas.
- `UiAnalyticsAnnotationMapper` e `x-ui-analytics.schema.json` publicavam apenas `bindings.primaryDimension` singular, enquanto o runtime `@praxisui/charts` ja previa `dimensions[]`.

Criar um endpoint dedicado apenas a `weekday x hour` violaria a Diretriz de Solucao de Plataforma: qualquer matriz `departamento x cargo`, `severidade x status`, `equipe x dia da semana` ou `ano x mes` exigiria outro contrato ad-hoc. A evolucao canonica correta consiste em estender `group-by`, `StatsCapability` e `x-ui.analytics` com `dimensions[]` e `StatsDimensionExtraction`, preservando total retrocompatibilidade com requests 1D (`field`).

## 1. Primitiva de Extracao de Dimensao (`StatsDimensionExtraction`)

```java
public enum StatsDimensionExtraction {
    NONE,
    HOUR_OF_DAY,      // 0..23 (dominio finito de 24 chaves inteiras)
    DAY_OF_WEEK,      // ISO-8601: 1 (Monday) .. 7 (Sunday) (dominio finito de 7 chaves inteiras)
    DAY_OF_MONTH,     // 1..31 (dominio finito de 31 chaves inteiras)
    MONTH_OF_YEAR,    // 1..12 (dominio finito de 12 chaves inteiras)
    QUARTER_OF_YEAR,  // 1..4 (dominio finito de 4 chaves inteiras)
    HOUR,             // truncamento cronologico linear: YYYY-MM-DDTHH:00
    DAY,              // truncamento cronologico linear: YYYY-MM-DD
    WEEK,             // truncamento cronologico linear: YYYY-MM-DD (segunda-feira ISO)
    MONTH,            // truncamento cronologico linear: YYYY-MM
    QUARTER,          // truncamento cronologico linear: YYYY-Qn
    YEAR              // truncamento cronologico linear: YYYY
}
```

Regras semanticas:
- `NONE` (default quando omitido): usa a identidade e o label governados pelo `StatsFieldDescriptor` (incluindo `keyPropertyPath` e `labelPropertyPath` distintos da #98). Exige `descriptor.groupByEligible() == true`.
- Extracoes temporais (`!= NONE`): exigem `descriptor.timeSeriesEligible() == true`.
- **Identidade estavel vs. Label**:
  - Para extracoes ciclicas, `key` e sempre um `Integer` canonico (`0..23` para `HOUR_OF_DAY`, `1..7` ISO para `DAY_OF_WEEK`, `1..31` para `DAY_OF_MONTH`, `1..12` para `MONTH_OF_YEAR`, `1..4` para `QUARTER_OF_YEAR`).
  - `label` publica uma representacao canonica neutra (`"00:00"`..`"23:00"`, `"MON"`..`"SUN"`, `"01"`..`"31"`, `"JAN"`..`"DEC"`, `"Q1"`..`"Q4"`). Runtimes consumidores usam `key` para ordenacao, identidade de celula e filtros, podendo localizar o texto exibido sem nunca inferir semantica a partir do `label`.
- **Governanca de `timezone` IANA**:
  - Quando qualquer dimensao utiliza extracao temporal sobre campos de instante/timestamp (`Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime`) ou quando uma janela temporal (`periodPreset` ou `from`/`to`) e solicitada, `timezone` deve ser um identificador IANA valido (`ZoneId.of(timezone)`). Se ausente em extracao temporal ou preset, responde `400 Bad Request`.
  - Para `LocalDate` puro (sem componente horario), extracoes `HOUR_OF_DAY` e `HOUR` sao rejeitadas com `400 Bad Request`.

## 2. Primitiva de Agrupamento Multidimensional (`dimensions[]`)

### Request canonico (`POST /{resource}/stats/group-by`)

O contrato aceita o modo 1D classico (`field`) ou a lista ordenada `dimensions[]` (1D com extracao ou 2D/ND Cross-Tab):

```json
{
  "filter": {},
  "dimensions": [
    { "field": "occurredAt", "extraction": "DAY_OF_WEEK", "alias": "dayOfWeek" },
    { "field": "occurredAt", "extraction": "HOUR_OF_DAY", "alias": "hourOfDay" }
  ],
  "timezone": "America/Sao_Paulo",
  "periodPreset": "LAST_30_DAYS",
  "fillGaps": true,
  "metrics": [
    { "operation": "COUNT", "alias": "totalEventos" },
    { "operation": "AVG_PER_CYCLE_OCCURRENCE", "alias": "mediaPorOcorrencia" }
  ],
  "orderBy": "KEY_ASC"
}
```

Regras de validacao do request:
- Deve informar `field` ou `dimensions[]` nao vazio. Se ambos forem enviados e divergirem, `dimensions[]` prevalece apenas quando `field` for omitido ou igual a primeira dimensao; enviar `field` contraditorio com `dimensions[]` responde `400`.
- `dimensions.size()` nao pode exceder `praxis.stats.max-dimensions` (default `2`).
- Cada `StatsDimensionRequest` possui `effectiveAlias()`: usa `alias` quando declarado; caso contrario, usa `field` quando `extraction == NONE` ou `field + "_" + extraction.name().toLowerCase()` quando `extraction != NONE`. Aliases efetivos de dimensao devem ser unicos no request (permitindo extrair duas partes distintas do mesmo campo temporal, como `occurredAt` -> `dayOfWeek` e `hourOfDay`).
- Janela temporal opcional no `group-by`: aceita `periodPreset` (`ComparisonPeriodPreset`) OU par `from` + `to` (`LocalDate`), nunca ambos simultaneamente (`400`). A janela filtra o campo temporal elegivel no `timezone` informado como `[from.atStartOfDay(zone), to.plusDays(1).atStartOfDay(zone))`, limitado a `praxis.stats.max-comparison-period-days` (default `366` dias).

### Response canonico (`GroupByStatsResponse` e `GroupByBucket`)

```json
{
  "field": "occurredAt",
  "dimensions": [
    { "field": "occurredAt", "extraction": "DAY_OF_WEEK", "alias": "dayOfWeek" },
    { "field": "occurredAt", "extraction": "HOUR_OF_DAY", "alias": "hourOfDay" }
  ],
  "window": {
    "from": "2026-09-25",
    "to": "2026-10-24",
    "timezone": "America/Sao_Paulo"
  },
  "metric": { "operation": "COUNT", "alias": "totalEventos" },
  "metrics": [
    { "operation": "COUNT", "alias": "totalEventos" },
    { "operation": "AVG_PER_CYCLE_OCCURRENCE", "alias": "mediaPorOcorrencia" }
  ],
  "buckets": [
    {
      "key": "1|0",
      "label": "MON | 00:00",
      "keys": [1, 0],
      "labels": ["MON", "00:00"],
      "coordinates": {
        "dayOfWeek": 1,
        "hourOfDay": 0
      },
      "value": 12,
      "count": 12,
      "values": {
        "totalEventos": 12,
        "mediaPorOcorrencia": 3.0
      }
    }
  ]
}
```

Compatibilidade e invariantes:
- Em consultas 1D simples (`field` sem `dimensions[]`), `buckets[].key` e `buckets[].label` mantem exatamente o comportamento anterior; `keys`, `labels`, `coordinates`, `dimensions` e `window` permanecem `null` (ou omitidos quando nao aplicaveis) para nao quebrar snapshots 1D existentes, enquanto consultas via `dimensions[]` populam `keys`, `labels`, `coordinates` e `dimensions`.

## 3. Semantica de Dominio Finito (Densificacao), DST e Medidas sobre Ciclos

### Densificacao (`fillGaps = true`)
- Quando `fillGaps = true` e todas as dimensoes solicitadas possuem dominio finito conhecido no contexto da consulta (ex.: `DAY_OF_WEEK` [7], `HOUR_OF_DAY` [24], `MONTH_OF_YEAR` [12], `QUARTER_OF_YEAR` [4], `DAY_OF_MONTH` [31], ou truncamentos lineares `DAY`/`WEEK`/`MONTH`/`HOUR` dentro de uma janela `[from, to]` fechada):
  - O produto cartesiano das chaves do dominio e validado contra `maxCells` (`praxis.stats.max-buckets`, ou `maxSeriesPoints` / `maxCells` governado, default `500` para matrizes densificadas como `7 x 24 = 168` ou `12 x 31 = 372`).
  - Celulas sem registros recebem `count = 0`, `COUNT = 0`, `DISTINCT_COUNT = 0`, `SUM = 0`, e `MIN = null`, `MAX = null`, `AVG = null`.
- Quando `fillGaps = false` (ou omitido), apenas os buckets observados nos dados filtrados sao retornados, respeitando `limit` e `orderBy`.

### Medidas sobre Ocorrencias do Ciclo (`AVG_PER_CYCLE_OCCURRENCE`)
- Alem de `COUNT`, `DISTINCT_COUNT`, `SUM`, `AVG` (media simples por registro: `sum / count`), `MIN` e `MAX`, `StatsMetric` passa a incluir `AVG_PER_CYCLE_OCCURRENCE`:
  - Requer janela temporal resolvida (`[from, to]` + `timezone`) e pelo menos uma dimensao temporal ciclica (`HOUR_OF_DAY`, `DAY_OF_WEEK`, `DAY_OF_MONTH`, `MONTH_OF_YEAR`, `QUARTER_OF_YEAR`) ou truncamento diario/horario.
  - O denominador `occurrences(cell, window, timezone)` e o numero exato de vezes que aquela coordenada temporal existiu no calendario da `timezone` dentro de `[from, to]`:
    - Exemplo 1 (janela parcial de 30 dias): em 30 dias corridos, dois dias da semana ocorrem `5` vezes e cinco dias da semana ocorrem `4` vezes. Para `dayOfWeek × hourOfDay`, uma celula nos dois primeiros dias divide a soma/contagem por `5`; nas demais, divide por `4`.
    - Exemplo 2 (janela curta de 3 dias, Segunda a Quarta, com `fillGaps = true`): Quinta a Domingo tiveram `0` ocorrencias na janela. Para Quinta a Domingo, `COUNT = 0`, mas `AVG_PER_CYCLE_OCCURRENCE = null` (coordenada nao observada na janela, nunca confundida com media `0.0`).
    - Exemplo 3 (DST / Horario de Verao):
      - No dia de avanco de relogio (*spring-forward*, ex.: `America/New_York` em `2026-03-08`, onde a hora `02:00` pula para `03:00`), a hora `02` nao ocorre naquele domingo (`0` ocorrencias nesse dia).
      - No dia de atraso de relogio (*fall-back*, ex.: `America/New_York` em `2026-11-01`, onde a hora `01:00` ocorre duas vezes, `-04:00` e `-05:00`), a politica declarada conta cada hora civil distinta no calendario ou cada passagem horaria real conforme documentado: adotamos **passagens horarias reais de 1h no eixo UTC convertido para a ZoneId** para `HOUR_OF_DAY` (ou dias civis para `DAY_OF_WEEK`), garantindo que no *spring-forward* de 1 dia único a hora inexistente tenha `0` ocorrencias (`AVG_PER_CYCLE_OCCURRENCE = null`) e no *fall-back* as duas passagens da hora `01` contem `2` ocorrencias horarias.
  - Se `metric.field` for omitido em `AVG_PER_CYCLE_OCCURRENCE`, o numerador e a contagem de eventos (`count`) da celula (`count / occurrences`). Se `metric.field` for um campo numerico elegivel, o numerador e a soma (`sum(field) / occurrences`).

## 4. Seguranca, Row Scope, Discovery (`StatsCapability`) e `x-ui.analytics`

- **Autorizacao e Row Scope obrigatorios**:
  - Todas as operacoes estatisticas (`group-by`, `timeseries`, `distribution`, `comparison`) em `AbstractBaseQueryResourceService` devem construir a `Specification` efetiva passando por `resolveResourceFilterQuery` (intersecao obrigatoria com `resolveResourceFilterAccessScope()`), garantindo que `stats` nunca revele contagens ou categorias fora do escopo autorizado do principal/tenant.
- **Discovery em `StatsCapability` e `StatsFieldCapability`**:
  - `StatsCapability` passa a informar `maxDimensions` (ex.: `2`) e `maxCells` (ex.: `500`).
  - `StatsFieldCapability` publica `supportedExtractions` (lista dos `StatsDimensionExtraction` suportados pelo campo: `["NONE"]` para categoricos; `["HOUR_OF_DAY", "DAY_OF_WEEK", "DAY_OF_MONTH", "MONTH_OF_YEAR", "QUARTER_OF_YEAR", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR"]` para campos `timeSeriesEligible`, acrescentando `"NONE"` se tambem for `groupByEligible`).
- **Projecao semantica em `x-ui.analytics` (`@AnalyticsProjection`)**:
  - `@AnalyticsDimensionBinding` ganha `extraction` (default `""`), `alias` (default `""`) e `timezone` opcional.
  - `@AnalyticsProjection` ganha `secondaryDimension()` e `dimensions()` (`AnalyticsDimensionBinding[]`) alem de `primaryDimension()`, e `defaults` pode declarar `timezone`, `periodPreset` e `fillGaps`.
  - Quando `interactions.crossFilter = true` em uma projection multidimensional, todas as dimensoes com filtro publico declaram seu `keyFilterField` (ou `primaryDimension` + `dimensions[]`), permitindo ao runtime aplicar cross-filter coordenado por celula.
