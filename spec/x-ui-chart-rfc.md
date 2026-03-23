# RFC — `x-ui.chart` como Extensao Canonica da Plataforma Praxis

## Status

- estado: `draft`
- versao proposta: `0.1.0`
- classe: `contrato-publico`

## Objetivo

Definir a direcao canônica para charts metadata-driven na plataforma Praxis sem acoplar a especificacao publica a uma engine de renderizacao especifica.

Esta RFC nao congela ainda um JSON Schema final. O objetivo desta rodada e fechar:

- a semantica de plataforma de `x-ui.chart`
- a fronteira entre contrato canonico e runtime Angular
- a relacao entre charts, `/schemas/filtered`, stats e payloads analiticos
- os pontos que precisam de versionamento antes de endurecer o contrato

## Fonte Canonica

`praxis-metadata-starter` e a fonte canonica do vocabulario `x-ui` e da semantica consumida pelo runtime Praxis via `/schemas/filtered`.

Consequencia:

- `x-ui.chart` deve nascer aqui como extensao governada
- `praxis-ui-angular` implementa o runtime oficial e os mappers
- adapters como ECharts continuam internos ao runtime e nao redefinem a semantica publica

## Motivacao

Hoje a fundacao de `@praxisui/charts` no workspace Angular ja resolve:

- contrato tipado de charts
- adapter interno para ECharts
- showcase com payloads mockados
- composicao com widgets dinamicos

Isso e suficiente para validar o runtime, mas ainda nao e a forma canonica de plataforma. Se o contrato ficar apenas no Angular:

- recipes e builders passam a depender de convencoes de frontend
- backends nao sabem qual envelope analitico devem produzir
- a troca futura de engine ou a introducao de um editor declarativo ficam mais arriscadas

## Escopo da Extensao

`x-ui.chart` deve representar semantica analitica e editorial, nao detalhes de uma engine.

### O que deve ser canonico

- tipo analitico do chart
- dimensoes
- metricas
- agregacoes
- ordenacao
- datasource
- estados
- interacoes
- presets
- politicas e limites

### O que nao deve ser canonico

- shape cru de `EChartsOption`
- nomes de propriedades especificas de uma engine
- callbacks imperativos do runtime Angular
- detalhes de instancia, lifecycle ou listeners do renderer

## Localizacao Proposta no Contrato

`x-ui.chart` deve viver em nivel de operacao ou de payload derivado, nao em `x-ui` de campo.

Direcao inicial:

- `paths.{path}.{operation}.x-ui.chart`
  - descreve a intencao analitica de uma operacao ou widget orientado a dados
- `/schemas/filtered`
  - pode copiar `x-ui.chart` para o payload final do mesmo modo que hoje copia outras chaves de operacao

Isto preserva o papel atual de:

- `x-ui` de campo
  - semantica de propriedades individuais
- `x-ui` de operacao
  - preferencias de renderizacao e composicao por endpoint
- `x-ui.resource`
  - metadados do recurso consumido pela UI

## Modelo Conceitual Inicial

Um contrato `x-ui.chart` precisa cobrir pelo menos os seguintes blocos.

### 1. Identidade e tipo

- `version`
- `kind`
  - exemplo: `bar`, `combo`, `horizontal-bar`, `line`, `pie`, `donut`, `area`, `stacked-bar`, `stacked-area`, `scatter`
- `preset`
  - exemplo: `kpi-trend`, `comparison`, `distribution`, `ranking`, `composition`
- `orientation`
  - `vertical`, `horizontal`
  - util para charts cartesianos quando a plataforma quiser separar orientacao sem depender apenas do nome do tipo

### 2. Datasource

Direcao preferencial desta fase:

- `source.kind`
  - `praxis.stats`, `derived`
- `source.resource`
  - recurso analitico base, por exemplo `/api/human-resources/vw-indicadores-incidentes`
- `source.operation`
  - `group-by`, `timeseries`, `distribution`
- `source.options`
  - opcoes especificas da familia `stats`, como `granularity`, `mode`, `bucketSize`, `bucketCount`, `orderBy`, `limit`
- `source.refresh`
  - freshness, cache hints, update policy

Observacao:

- nesta direcao canonica, `x-ui.chart` deixa de depender de um `source` remoto generico e passa a apontar explicitamente para a familia analitica institucional ja publicada pelo starter

### 3. Semantica analitica

- `dimensions[]`
- `metrics[]`
- `aggregations[]`
- `groupBy[]`
- `sort[]`
- `filters[]`
- `limit`

Observacoes para a primeira onda de expansao:

- `horizontal-bar`
  - continua semanticamente proximo de `bar`, mas exige orientacao explicita no contrato
- `stacked-area`
  - exige semantica de composicao empilhada no nivel canonico, nao apenas um detalhe do renderer
- `scatter`
  - exige leitura bidimensional minima
  - nesta fase inicial pode ser modelado como uma primeira dimensao para eixo `x` e uma primeira metrica para eixo `y`, desde que o contrato deixe essa regra explicita

Observacoes para a segunda onda:

- `combo`
  - exige serie heterogenea por metrica
  - o contrato deve permitir declarar por metrica pelo menos:
    - `seriesKind`
    - `axis`
    - `color`
  - isso preserva a semantica no nivel canonico e evita codificar combinacoes apenas no adapter ECharts

Aqui a preocupacao nao e a forma final exata do JSON, e sim garantir que o contrato expresse negocio e analise, nao apenas apresentacao.

### 4. Apresentacao e estado

- `title`
- `subtitle`
- `legend`
- `labels`
- `tooltip`
- `height`
- `theme`
- `emptyState`
- `loadingState`
- `errorState`

Esses blocos devem continuar alinhados a i18n/config da plataforma, sem incentivar texto hardcoded no runtime.

### 5. Interacoes

- `events.pointClick`
- `events.selectionChange`
- `events.drillDown`
- `events.crossFilter`

Cada evento deve ser declarativo e orientado a acao de plataforma, por exemplo:

- filtrar outro widget
- abrir detalhe
- navegar
- atualizar contexto global
- disparar acao canônica

## Payload de Evento

O payload de evento de charts deve ser um contrato de plataforma, nao o evento bruto da engine.

Campos recomendados:

- `eventType`
- `chartId`
- `dimension`
- `metric`
- `value`
- `label`
- `seriesKey`
- `rawPoint`
- `correlationId`
- `context`

`rawPoint` deve ser opcional e restrito a um shape neutro o suficiente para nao institucionalizar a engine.

## Relacao com Stats e Endpoints Analiticos

O `praxis-metadata-starter` ja possui primitives analiticas relevantes:

- `GroupByStatsRequest` / `GroupByStatsResponse`
- `TimeSeriesStatsRequest` / `TimeSeriesStatsResponse`
- `DistributionStatsRequest` / `DistributionStatsResponse`
- `StatsProperties`

Esses DTOs e capacidades sao bons candidatos a produtores do datasource de `x-ui.chart`, mas nao devem ser confundidos com o contrato editorial do chart.

Separacao recomendada:

- stats DTOs
  - representam consulta e resposta analitica do backend
- `x-ui.chart`
  - representa como uma capacidade analitica deve ser composta, apresentada e integrada no ecossistema UI

Compatibilidade da primeira onda com `praxis.stats`:

- `horizontal-bar`
  - deve nascer sobre `group-by`
- `stacked-area`
  - deve nascer preferencialmente sobre `timeseries`
- `scatter`
  - pode nascer inicialmente sobre `group-by` quando o recurso permitir usar uma dimensao numerica ou temporal no eixo `x` e uma metrica agregada no eixo `y`
  - isso nao elimina uma evolucao futura para `source.kind = "derived"` ou envelopes analiticos mais ricos

Compatibilidade inicial da segunda onda:

- `combo`
  - deve nascer primeiro com `source.kind = "derived"` ou dados locais fornecidos pelo host/widget
  - o suporte remoto canonico sobre `praxis.stats` depende de evolucao posterior do envelope analitico para mais de uma metrica por consulta

## Versionamento

Antes de publicar schema definitivo, `x-ui.chart` deve nascer com versionamento explicito.

Recomendacao:

- `version` no proprio bloco `x-ui.chart`
- SemVer para a especificacao
- compatibilidade por mapper no runtime Angular quando necessario

Sem isso, editor, builder, payload remoto e recipes correm risco de quebrar em cadeia.

## Limites Entre Camadas

### Contrato canonico

Define:

- semantica
- versionamento
- vocabulário
- eventos
- estados
- presets

Na primeira onda, o contrato canonico passa a cobrir:

- orientacao cartesiana
- composicao temporal empilhada
- dispersao bidimensional minima

### Runtime Angular

Define:

- `PraxisChartConfig`
- mapeamento de `x-ui.chart` para widgets dinamicos
- carregamento por registry
- i18n, estados e adaptacao ao host

### Engine adapter

Define:

- traducao para `EChartsOption` ou equivalente
- listeners e ciclo de renderizacao
- detalhes internos de option, resize, update e destroy

## Mapa de Impacto

- subprojeto canonico afetado:
  - `praxis-metadata-starter`
- consumidores impactados:
  - `praxis-ui-angular`
  - `praxis-ui-landing-page`
  - recipes e showcases locais
  - futuros editors e builders
  - hosts backend que publicarem payloads analiticos
- docs publicas potencialmente afetadas:
  - docs da spec `x-ui`
  - guias de backend compativel
  - docs publicas de charts
- testes ou validacoes minimas necessarias:
  - fixtures de contrato
  - compatibilidade com payloads mockados atuais
  - testes de copia de `x-ui.chart` por `/schemas/filtered`
  - testes de mapper no runtime Angular
- risco de breaking change:
  - medio, mitigado por versionamento e rollout em fases

## Decisoes Recomendadas para a Proxima Rodada

1. Fechar a semantica minima de `source`, `dimensions`, `metrics`, `aggregations`, `state` e `events`.
2. Decidir se o schema JSON definitivo sera:
   - um novo arquivo `x-ui-chart.schema.json`; ou
   - uma extensao versionada de `x-ui-operation.schema.json`.
3. Alinhar a especificacao com os DTOs de stats do starter.
4. Depois disso, atualizar `@praxisui/charts` para mapear do contrato canonico, e nao do shape Angular-first atual.

## Fora de Escopo Nesta RFC

- congelar o shape final do JSON Schema
- definir a API final do editor visual
- definir a engine de renderizacao futura alem do adapter atual
- fechar persistencia de preferencia por usuario/equipe
