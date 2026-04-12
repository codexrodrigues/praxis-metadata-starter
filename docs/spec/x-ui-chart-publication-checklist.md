# Checklist de Conformidade - `x-ui.chart` draft `0.1.0`

## Objetivo

Consolidar os gates minimos para manter o draft canonico de `x-ui.chart` coerente entre:

- schema canonico no starter
- host operacional `praxis-api-quickstart`
- runtime oficial `@praxisui/charts`

## Gates Minimos

### 1. Integridade do contrato canonico

- `docs/spec/x-ui-chart.schema.json` valida sintaxe JSON
- `docs/spec/examples/x-ui-chart.valid.json` valida sintaxe JSON
- o exemplo valido continua aceito pelo schema
- o schema exige `source.resource` e `source.operation` quando `source.kind = "praxis.stats"`
- `timeseries` exige `source.options.granularity`
- `refresh.strategy = "interval"` exige `intervalMs`

### 2. Alinhamento operacional com stats

- o contrato aponta explicitamente para a familia institucional `praxis.stats`
- as operacoes cobertas sao:
  - `group-by`
  - `timeseries`
  - `distribution`
- o host operacional `praxis-api-quickstart` aceita payloads nesse shape sem adaptadores ad hoc
- `/schemas/filtered` resolve request/response schema para essas operacoes

### 3. Cobertura no runtime Angular oficial

O contrato canonico e a cobertura do runtime oficial devem estar documentados de forma objetiva.

Cobertura executavel em `@praxisui/charts`:

- `source.kind = "praxis.stats"`
- `kind`: `bar`, `line`, `pie`, `donut`, `area`, `stacked-bar`
- uma metrica por chart quando a origem e `praxis.stats`
- `pointClick` e `drillDown` no fluxo executavel

Sem cobertura executavel no runtime Angular oficial:

- `source.kind = "derived"`
- `aggregation = "distinct-count"`
- `events.selectionChange`
- `events.crossFilter`
- `theme.variant`
- `theme.palette` como token semantico string
- pie/donut com multiplas metricas

Regra de publicacao:

- o starter so deve publicar exemplos operacionais para capacidades com cobertura executavel documentada;
- capacidades presentes apenas no schema devem permanecer descritas como vocabulario de contrato, sem promessa de execucao pelo consumidor oficial.

### 4. Validacao de host operacional

Recomenda-se validar no `praxis-api-quickstart`:

- schema request/response para `group-by`, `timeseries` e `distribution`
- smoke HTTP de requests no shape que o Angular materializa

Comando focal recomendado:

```bash
PRAXIS_EXTERNAL_SMOKE_TESTS=true ./mvnw -Dtest=StatsSchemaSmokeHttpTest,VwStatsSmokeHttpTest test
```

## Riscos Conhecidos

### 1. Contrato sem cobertura executavel correspondente

Toda semantica publicada pelo starter deve declarar se existe cobertura executavel no consumidor oficial.

### 2. Projecao para `/schemas/filtered`

A documentacao de `x-ui.chart` deve separar o vocabulario de contrato da projecao automatica para payloads finais em `/schemas/filtered`.

### 3. O contrato continua em estado `draft`

Mesmo com schema validavel, o bloco segue como draft `0.1.0`. A comunicacao deve deixar claro que a estabilidade e de draft, sem equivalencia ao core `x-ui` v1.

## Texto Recomendado para Notas Publicas

```md
- adiciona o draft canonico `x-ui.chart` (`0.1.0`) em `docs/spec`
- alinha a origem analitica inicial ao namespace institucional `praxis.stats`
- publica exemplo valido e guia de conformidade para charts metadata-driven
- documenta a cobertura executavel do runtime Angular oficial `@praxisui/charts`
```

## Validacoes Operacionais Relacionadas

- ligar o `praxis-api-quickstart` ao starter em validacao
- publicar o host operacional no ambiente remoto quando houver gate de release
- apontar o `praxis-ui-angular` para a API publicada e validar `/charts-showcase` contra backend real
