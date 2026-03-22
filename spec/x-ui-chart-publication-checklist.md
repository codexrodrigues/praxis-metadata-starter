# Checklist de Publicacao - `x-ui.chart` draft `0.1.0`

## Objetivo

Consolidar os gates minimos para publicar uma nova versao do `praxis-metadata-starter` contendo o draft canonico de `x-ui.chart` sem gerar drift imediato entre:

- schema canonico no starter
- host operacional `praxis-api-quickstart`
- runtime oficial `@praxisui/charts`

## Gates Minimos Antes da Publicacao

### 1. Integridade do contrato canonico

- `docs/spec/x-ui-chart.schema.json` valida sintaxe JSON
- `docs/spec/examples/x-ui-chart.valid.json` valida sintaxe JSON
- o exemplo valido continua aceito pelo schema
- o schema exige `source.resource` e `source.operation` quando `source.kind = "praxis.stats"`
- `timeseries` exige `source.options.granularity`
- `refresh.strategy = "interval"` exige `intervalMs`

### 2. Compatibilidade operacional com stats

- o contrato aponta explicitamente para a familia institucional `praxis.stats`
- as operacoes cobertas nesta fase sao:
  - `group-by`
  - `timeseries`
  - `distribution`
- o host operacional `praxis-api-quickstart` aceita payloads nesse shape sem adaptadores ad hoc
- `/schemas/filtered` resolve request/response schema para essas operacoes

### 3. Compatibilidade com o runtime Angular oficial

O draft canonico pode ser mais amplo que o runtime atual, mas isso precisa estar documentado explicitamente.

Suportado hoje em `@praxisui/charts`:

- `source.kind = "praxis.stats"`
- `kind`: `bar`, `line`, `pie`, `donut`, `area`, `stacked-bar`
- uma metrica por chart quando a origem e `praxis.stats`
- `pointClick` e `drillDown` no fluxo atual

Ainda nao suportado no runtime Angular atual:

- `source.kind = "derived"`
- `aggregation = "distinct-count"`
- `events.selectionChange`
- `events.crossFilter`
- `theme.variant`
- `theme.palette` como token semantico string
- pie/donut com multiplas metricas

Consequencia:

- a publicacao do starter pode incluir essas capacidades no draft canonico
- mas a release deve deixar claro que parte delas ainda esta fora da superficie executavel atual do Angular

### 4. Validacao de host operacional

Antes da publicacao, recomenda-se validar no `praxis-api-quickstart`:

- schema request/response para `group-by`, `timeseries` e `distribution`
- smoke HTTP de requests no shape que o Angular materializa

Comando focal recomendado:

```bash
PRAXIS_EXTERNAL_SMOKE_TESTS=true ./mvnw -Dtest=StatsSchemaSmokeHttpTest,VwStatsSmokeHttpTest test
```

## Riscos Conhecidos na Publicacao desta Fase

### 1. Contrato mais amplo que o runtime

O starter pode publicar semantica que o Angular oficial ainda nao executa completamente. Isso e aceitavel apenas se a limitacao estiver documentada como compatibilidade parcial de consumidor.

### 2. Nao ha ainda integracao canonica automatica em `/schemas/filtered`

Nesta fase, o schema e a documentacao canonica existem no starter, mas a projecao automatica de `x-ui.chart` para payloads finais continua dependendo do rollout dos produtores/hosts.

### 3. O contrato continua em estado `draft`

Mesmo com schema validavel, o bloco segue como draft `0.1.0`. A release nao deve comunicar estabilidade de longo prazo equivalente ao core `x-ui` v1.

## Texto Recomendado para Release Notes

```md
- adiciona o draft canonico `x-ui.chart` (`0.1.0`) em `docs/spec`
- alinha a origem analitica inicial ao namespace institucional `praxis.stats`
- publica exemplo valido e guia de conformidade para charts metadata-driven
- documenta limites de compatibilidade atuais do runtime Angular oficial `@praxisui/charts`
```

## Onde Retomar Depois da Publicacao

- ligar o `praxis-api-quickstart` na versao publicada nova do starter
- publicar o host operacional no ambiente remoto
- apontar o `praxis-ui-angular` para a API publicada e validar `/charts-showcase` contra backend real
