# Stats Multi-Metric Examples

## Scope

This document records the additive contract extension introduced for `praxis.stats`
multi-metric requests in `group-by` and `timeseries`.

In this phase:

- `metric` remains valid and fully supported
- `metrics[]` is now part of the public contract for `group-by` and `timeseries`
- `DISTINCT_COUNT` is part of the public metric operation set and requires a governed `field`
- `value` remains the compatibility field for the primary metric
- `values` is the compatibility-safe container for per-alias values
- `group-by` and `timeseries` already execute this shape in the starter JPA runtime
- `distribution` remains mono-metric until the executor is expanded

## Group-By Request

```json
{
  "filter": {
    "status": "ATIVO"
  },
  "field": "departamentoId",
  "metrics": [
    {
      "operation": "COUNT",
      "alias": "total"
    },
    {
      "operation": "SUM",
      "field": "massaLiquida",
      "alias": "massaLiquida"
    },
    {
      "operation": "DISTINCT_COUNT",
      "field": "perfilFolha",
      "alias": "perfis"
    }
  ],
  "limit": 10,
  "orderBy": "VALUE_DESC"
}
```

## Group-By Response

```json
{
  "field": "departamentoId",
  "metric": {
    "operation": "COUNT",
    "alias": "total"
  },
  "metrics": [
    {
      "operation": "COUNT",
      "alias": "total"
    },
    {
      "operation": "SUM",
      "field": "massaLiquida",
      "alias": "massaLiquida"
    },
    {
      "operation": "DISTINCT_COUNT",
      "field": "perfilFolha",
      "alias": "perfis"
    }
  ],
  "buckets": [
    {
      "key": "TI",
      "label": "TI",
      "value": 12,
      "count": 12,
      "values": {
        "total": 12,
        "massaLiquida": 84000,
        "perfis": 3
      }
    }
  ]
}
```

## Time-Series Request

```json
{
  "filter": {},
  "field": "competencia",
  "granularity": "MONTH",
  "metrics": [
    {
      "operation": "COUNT",
      "alias": "total"
    },
    {
      "operation": "SUM",
      "field": "massaLiquida",
      "alias": "massaLiquida"
    },
    {
      "operation": "DISTINCT_COUNT",
      "field": "perfilFolha",
      "alias": "perfis"
    }
  ],
  "from": "2026-01-01",
  "to": "2026-03-31",
  "fillGaps": true
}
```

## Time-Series Response

```json
{
  "field": "competencia",
  "granularity": "MONTH",
  "metric": {
    "operation": "COUNT",
    "alias": "total"
  },
  "metrics": [
    {
      "operation": "COUNT",
      "alias": "total"
    },
    {
      "operation": "SUM",
      "field": "massaLiquida",
      "alias": "massaLiquida"
    },
    {
      "operation": "DISTINCT_COUNT",
      "field": "perfilFolha",
      "alias": "perfis"
    }
  ],
  "points": [
    {
      "start": "2026-01-01",
      "end": "2026-01-31",
      "label": "2026-01",
      "value": 12,
      "count": 12,
      "values": {
        "total": 12,
        "massaLiquida": 84000,
        "perfis": 3
      }
    }
  ]
}
```
