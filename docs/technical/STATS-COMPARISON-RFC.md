# RFC - `POST /{resource}/stats/comparison`

## Status

- estado: `proposed`
- issue: #99
- dependencia: #98
- classe: `arquitetural + transversal + contrato-publico`
- aderencia: `lacuna-real-de-contrato`

## Objetivo

Publicar uma unica operacao governada que compara as mesmas metricas agrupadas
pela mesma dimensao em dois periodos relacionados. A operacao pertence ao
`praxis-metadata-starter`; hosts provam dados e regras de dominio, e runtimes
somente materializam o envelope retornado.

```http
POST /{resource}/stats/comparison
```

## Request canonico

```json
{
  "filter": {},
  "field": "departamento",
  "metrics": [
    { "operation": "DISTINCT_COUNT", "field": "funcionarioId", "alias": "funcionariosAfastados" },
    { "operation": "SUM", "field": "diasAfastado", "alias": "diasAfastado" }
  ],
  "period": {
    "field": "competencia",
    "preset": "THIS_MONTH",
    "mode": "PREVIOUS_CALENDAR_PERIOD",
    "timezone": "America/Sao_Paulo"
  },
  "limit": 20,
  "orderBy": "CURRENT_VALUE_DESC"
}
```

`metrics[]` e obrigatorio, nao vazio e tem aliases efetivos unicos. O primeiro
alias e a metrica primaria para ordenacao. O primeiro corte aceita somente
`COUNT`, `DISTINCT_COUNT` e `SUM`; `AVG`, `MIN` e `MAX` respondem `400` ate que
exista semantica publica para ausencia de observacao.

O periodo aceita exatamente um de `preset` ou `from` + `to`; enviar ambos e
erro `400`. Presets aceitos: `TODAY`, `YESTERDAY`, `LAST_7_DAYS`,
`LAST_30_DAYS`, `THIS_MONTH`, `LAST_MONTH`, `THIS_QUARTER` e `THIS_YEAR`.
Preset exige timezone IANA valida. `Clock` deve ser
injetavel. O response devolve as duas janelas resolvidas como datas inclusivas.

Para campos timestamp (`Instant`, `OffsetDateTime`, `LocalDateTime` ou
`ZonedDateTime`), a execucao converte a janela resolvida para
`[inicioDaData, inicioDoDiaSeguinte)` na timezone declarada. Para `LocalDate`,
usa limite inclusivo. `PREVIOUS_ALIGNED` desloca a janela atual pelo mesmo numero
de dias; `PREVIOUS_CALENDAR_PERIOD` usa a unidade de calendario do preset. Custom
ranges usam somente `PREVIOUS_ALIGNED` no primeiro corte; combinar custom range
com `PREVIOUS_CALENDAR_PERIOD` retorna `400`.

## Response canonico

```json
{
  "field": "departamento",
  "periodField": "competencia",
  "metrics": [{ "operation": "SUM", "field": "diasAfastado", "alias": "diasAfastado" }],
  "currentPeriod": { "from": "2026-07-01", "to": "2026-07-31", "timezone": "America/Sao_Paulo" },
  "previousPeriod": { "from": "2026-06-01", "to": "2026-06-30", "timezone": "America/Sao_Paulo" },
  "buckets": [{
    "key": "D-ENG",
    "label": "Engenharia",
    "values": {
      "diasAfastado": {
        "current": 18,
        "previous": 9,
        "delta": 9,
        "deltaPercent": 100.0,
        "baselineMissing": false
      }
    }
  }]
}
```

Para cada alias: buckets das duas janelas sao unidos por key, valores ausentes
sao zero, `delta = current - previous`, e `deltaPercent = delta / previous *
100`. Se `previous` for zero, `deltaPercent` e `null` e `baselineMissing` e
`true`. Nunca sao emitidos `NaN`, `Infinity` ou numeros serializados como texto.
Empates de ordenacao usam key ascendente.

## Execucao, limites e seguranca

As duas agregacoes usam o mesmo `Specification`, acesso a campos, row scope e
transacao/dataset version quando essa evidencia existir. A uniao, zero-fill,
calculo e ordenacao acontecem no starter; o Angular nao realiza merge ou delta.

`limit` e aplicado somente apos a ordenacao global. Para evitar um top-N falso,
o executor nunca limita cada janela pelo `limit` solicitado. Cada agregacao le no
maximo `praxis.stats.max-comparison-candidates + 1` grupos; exceder esse teto
responde erro de politica/limite em vez de truncar silenciosamente. A janela
atual tambem nao pode exceder `praxis.stats.max-comparison-period-days`. Os
padroes sao, respectivamente, `1000` candidatos e `366` dias; o host pode
reduzi-los conforme capacidade e politica de retencao.

Comparison nao amplia o que a colecao pode revelar. Um bucket removido pelo row
scope nao reaparece por zero-fill. Erros, schemas e logs nao expõem SQL ou paths
internos; observabilidade registra apenas resource key, operacao, schema hash,
janelas resolvidas, cardinalidade e duracao.

## Discovery e schema

O recurso e opt-in por `getComparisonStatsSupportMode()`, inicialmente
`DISABLED`. A capability `statsComparison` so e verdadeira quando endpoint,
support mode e registry permitem dimensao, periodo e metricas. A capability pode
anunciar os papeis publicos `COMPARISON_DIMENSION` e `COMPARISON_PERIOD`, sem
redefinir payloads que pertencem a `/schemas/filtered`.

O endpoint deve publicar `RestApiResponse`, links de filter/request schema/response
schema, OpenAPI, catalogo, ETag e `X-Schema-Hash` coerentes. `AnalyticsOperation`
ganha `COMPARISON`; `x-ui.analytics` recebe bindings explicitos de periodo,
preset e estrategia, sem fixar componente ou engine de chart.

## Provas obrigatorias e rollout

1. H2 no starter: periodo, eligibility, key/label, uniao, zero-fill, baseline,
   ordenacao e top-N divergente.
2. Quickstart: folha por departamento e afastamentos multi-metrica com lotacao
   historica, ambos por HTTP real.
3. Angular: uma chamada comparison; label para display; key para cross-filter;
   estados de baseline, 400, 403 e 501.
4. Config: planejamento por capability e campos elegiveis, sem keyword routing.
5. Corpus e Landing: somente apos a versao publicada estar disponivel.

A ordem de release e #98, depois #99 no starter, Quickstart, Angular, Config,
corpus HTTP e Landing. Cada consumidor deve registrar a coordenada publicada que
consome; nenhum exemplo ou playground antecipa um endpoint ainda indisponivel.
