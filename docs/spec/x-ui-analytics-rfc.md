# RFC - `x-ui.analytics` como Projecao Semantica Analitica Canonica

## Status

- estado: `draft`
- versao proposta: `0.3.0`
- classe: `contrato-publico`

## Objetivo

Definir uma projecao semantica analitica canonicamente publicada pelo backend para operacoes
analiticas reais, preservando a neutralidade da capacidade `praxis.stats` e mantendo a escolha de
apresentacao no runtime consumidor.

## Problema

Os endpoints `stats/group-by`, `stats/timeseries`, `stats/distribution` e `stats/comparison` ja publicam capacidade
analitica reutilizavel, mas o schema estrutural sozinho nao informa com seguranca:

- qual e a leitura analitica principal da operacao;
- quais bindings de dimensao/metrica sao a interpretacao canonica;
- se a UX deveria preferir chart, tabela analitica ou outro renderer.

## Direcao Canonica

- `x-ui.analytics` nasce no `praxis-metadata-starter`
- o backend publica semantica analitica; o runtime escolhe a apresentacao
- `x-ui.chart` permanece como especializacao opcional para renderers de chart
- a capacidade `praxis.stats` continua neutra e reutilizavel

## Localizacao

`paths.{path}.{operation}.x-ui.analytics`

Na fase inicial, o contrato publicado deve usar `projections[]` para evitar acoplar uma operacao a
uma unica leitura editorial.

## Shape Inicial

```json
{
  "x-ui": {
    "analytics": {
      "projections": [
        {
          "id": "ranking-table",
          "intent": "ranking",
          "source": {
            "kind": "praxis.stats",
            "resource": "/api/human-resources/vw-analytics-folha-pagamento",
            "operation": "group-by"
          },
          "bindings": {
            "primaryDimension": {
              "field": "departamento",
              "role": "category",
              "label": "Departamento"
            },
            "primaryMetrics": [
              {
                "field": "valorLiquido",
                "aggregation": "sum",
                "label": "Valor liquido"
              }
            ]
          },
          "defaults": {
            "sort": [
              { "field": "valorLiquido", "direction": "desc" }
            ],
            "limit": 10,
            "granularity": "month"
          },
          "presentationHints": {
            "preferredFamilies": ["analytic-table", "chart"]
          },
          "interactions": {
            "drillDown": true,
            "pointSelection": false,
            "crossFilter": false
          }
        }
      ]
    }
  }
}
```

## Projection de comparison

Uma projection de comparison acrescenta o binding temporal que o runtime precisa
para montar o request sem deduzir campo, fuso ou estrategia localmente.

```json
{
  "id": "monthly-department-comparison",
  "intent": "comparison",
  "source": {
    "kind": "praxis.stats",
    "resource": "/api/human-resources/funcionarios",
    "operation": "comparison"
  },
  "bindings": {
    "primaryDimension": { "field": "departamento", "role": "category" },
    "primaryMetrics": [{ "field": "funcionarioId", "aggregation": "count", "label": "Funcionarios" }],
    "comparisonPeriod": {
      "field": "dataAdmissao",
      "timezone": "America/Sao_Paulo",
      "preset": "LAST_30_DAYS",
      "mode": "PREVIOUS_ALIGNED"
    }
  }
}
```

`comparisonPeriod` representa defaults de execucao da projection. A elegibilidade
de campos e metricas continua no `StatsFieldRegistry` e em `capabilities.stats`;
esse bloco nao cria uma segunda fonte de verdade nem autoriza o runtime a trocar
o periodo publicado por heuristica.

## Referencias de politicas governadas

Quando uma projection depende de uma classificacao, elegibilidade, retencao ou
outra politica executada pelo dominio, `governance.policyRefs[]` publica somente
a identidade versionada e os campos que atestam o resultado materializado:

```json
{
  "governance": {
    "policyRefs": [
      {
        "policyId": "classification-policy",
        "policyVersion": "2026-07",
        "role": "criticality",
        "resultField": "criticalityLevel",
        "attestation": {
          "policyIdField": "criticalityPolicyId",
          "policyVersionField": "criticalityPolicyVersion"
        }
      }
    ]
  }
}
```

O dominio continua dono da execucao, dos thresholds e da autorizacao. A
referencia permite que authoring, auditoria e runtimes preservem provenance sem
ler linhas para descobrir a policy e sem copiar sua logica para configuracao.

## Regras

- `MUST`: publicar `projections[]`
- `MUST`: cada projection conter `id`, `intent`, `source`, `bindings.primaryMetrics`
- `SHOULD`: publicar `defaults` e `preferredFamilies` quando a leitura canonica estiver clara
- `SHOULD`: projections `timeseries` publicar `defaults.granularity` quando o runtime depender da granularidade para executar a consulta sem heuristica local
- `MUST`: `defaults.granularity`, quando publicado para `praxis.stats`, usar `day`, `week` ou `month`
- `MUST`: projection com `source.operation = "comparison"` publicar `bindings.comparisonPeriod`
- `MUST`: `comparisonPeriod` declarar `field`, `timezone`, `preset` e `mode`, usando os mesmos enums
  do request HTTP de comparison; o runtime pode oferecer intervalo customizado, mas nao deve trocar
  silenciosamente os defaults publicados
- `MAY`: metricas publicar `aggregation = "distinct-count"` quando a fonte `praxis.stats` governar o campo de metrica
- `MUST`: cada item de `governance.policyRefs[]` declarar `policyId`, `policyVersion`, `role` e `resultField`
- `MUST`: `attestation`, quando publicado, declarar em conjunto `policyIdField` e `policyVersionField`
- `MUST NOT`: policy references publicar thresholds, expressoes, scripts ou payloads de runtime
- `MUST NOT`: a presenca de uma policy reference ser interpretada como autorizacao para leitura nominal ou abertura de surface
- `MUST NOT`: fixar componente Angular, engine, layout ou detalhes visuais de chart

## Convivencia com `x-ui.chart`

- `x-ui.analytics` define a intencao analitica canonicamente publicada
- `x-ui.chart` continua como especializacao opcional de renderer
- consumidores nao devem exigir ambos ao mesmo tempo
- quando ambos existirem, `x-ui.analytics` governa a orquestracao semantica e `x-ui.chart`
  governa a especializacao do renderer chart

## Fora do Escopo da v0.1

- `theme`, `motion`, `state`
- `preferredChartKinds`
- eventos especificos de chart
- alteracoes em `capabilities`
- catalogos proprios de analytics fora de `/schemas/filtered`
