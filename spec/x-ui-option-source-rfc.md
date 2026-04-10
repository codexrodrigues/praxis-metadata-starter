# RFC - `x-ui.optionSource` para Opcoes Derivadas em Filtros Metadata-Driven

## Status

- estado: `draft`
- versao proposta: `0.1.0`
- classe: `arquitetural`

## Objetivo

Definir a direcao canonica para fontes de opcoes derivadas em filtros metadata-driven na
plataforma Praxis, cobrindo cenarios em que um campo precisa de selecao assistida, mas
nao possui um recurso CRUD proprio para `POST /options/filter`.

Esta RFC fecha quatro pontos:

- a fronteira entre filtros, options e stats
- a publicacao canonica em `/schemas/filtered`
- o papel de `optionSource` no contrato `x-ui`
- a integracao com starter, host de referencia e runtime Angular

## Fonte Canonica

`praxis-metadata-starter` e a fonte canonica do vocabulario `x-ui`, da semantica de
`/schemas/filtered` e das superficies metadata-driven publicas da plataforma.

Consequencia:

- o contrato canonico deve nascer no starter
- hosts consumidores nao devem inventar shapes locais para opcoes derivadas
- runtimes devem preferir a semantica canonica publicada pelo backend

## Motivacao

Hoje a plataforma ja publica campos com opcoes locais e tambem suporta fluxos em que
o frontend consome endpoints de options derivados de recursos. O problema e que isso
ainda aparece de forma heterogenea:

- parte dos campos expõe `endpoint`, `valueField` e `displayField`
- parte dos consumidores ja trabalha mais perto de `resourcePath` e semantica de fonte
- a relacao com `stats` e dimensoes derivadas ainda precisa de uma linguagem publica unica

O objetivo desta RFC nao e preservar multiplas trilhas como recomendacao. O objetivo e
fechar uma forma canonica de publicacao e manter shapes compativeis apenas enquanto
existirem consumidores que ainda dependam deles.

## Diagnostico do Estado Atual

### O que ja existe

- `OptionDTO` como payload publico para opcoes remotas
- endpoints operacionais de filtro e reidratacao por IDs
- runtime Angular com capacidade de consumir `optionSource`
- starter com suporte base para `DISTINCT_DIMENSION` e `CATEGORICAL_BUCKET`

### O que falta

- um bloco canonico unico em `x-ui`
- regras publicas para os tipos de fonte
- diretriz clara sobre quando usar `stats`, lookup leve ou entidade de recurso
- narrativa publica limpa para rollout e conformidade

## Principios

### 1. Opcao publica nao deve depender de endpoint ad hoc

A forma canonica de publicacao deve descrever a fonte sem exigir que o consumidor
interprete um endpoint arbitrario como contrato principal.

### 2. `stats` nao deve virar API publica de options

`stats` continua sendo superficie analitica. Quando uma fonte de opcoes aproveitar
infraestrutura analitica internamente, isso nao muda o contrato publico publicado ao consumidor.

### 3. `OptionDTO` continua sendo o payload publico

O shape de resposta de opcoes deve continuar convergindo para `OptionDTO`, evitando
contratos alternativos por tipo de fonte.

### 4. Recurso CRUD proprio nao e obrigatorio

Nem toda fonte de opcoes precisa nascer como recurso CRUD. A plataforma precisa suportar
fontes derivadas, lookup leve e buckets categoricos sem forcar supermodelagem.

### 5. A solucao deve ser governada por campo e por fonte

O contrato precisa descrever claramente:

- de onde vem a opcao
- como filtrar
- como reidratar selecionados
- quais dependencias afetam a cascata

## Modelo Conceitual Proposto

Cada campo pode publicar `x-ui.optionSource` quando a opcao remota for parte do contrato
canonico do backend. Esse bloco descreve a identidade da fonte e sua semantica operacional.

Elementos esperados:

- `key`
- `type`
- `resourcePath`
- `filterField` quando necessario
- `dependsOn` quando houver cascata
- hints de `policy`, `search` e `pagination` quando aplicavel

O runtime consumidor deve tratar `optionSource` como referencia principal quando o bloco
for publicado.

## Tipos Canonicos Iniciais

### `RESOURCE_ENTITY`

Fonte cuja opcao deriva de um recurso identificavel e navegavel, normalmente com
semantica de entidade reutilizavel entre superficies.

Use quando:

- ha identidade estavel
- existe reuso entre filtros, formularios ou outras telas
- a opcao representa algo mais proximo de um recurso do dominio

### `DISTINCT_DIMENSION`

Fonte derivada de valores distintos sobre uma dimensao real do conjunto consultado.

Use quando:

- a opcao vem de um campo real do dataset
- o objetivo principal e discovery de valores distintos
- nao faz sentido promover a fonte a recurso proprio

### `CATEGORICAL_BUCKET`

Fonte derivada de buckets ou faixas categoricas governadas.

Use quando:

- a opcao nao e um valor literal armazenado como entidade
- a fonte depende de regra de classificacao
- a apresentacao canonica depende mais da categoria do que do valor bruto

### `LIGHT_LOOKUP`

Fonte leve para selecao assistida sem peso de recurso completo.

Use quando:

- ha busca e reidratacao por IDs
- o caso nao exige CRUD proprio
- a opcao continua sendo parte operacional de outro recurso

### `STATIC_CANONICAL`

Fonte governada por catalogo estavel publicado como parte do contrato da plataforma.

Use quando:

- o conjunto e pequeno e relativamente estavel
- o valor deve ser governado como vocabulario controlado

## Fronteira Entre Tipos

### `DISTINCT_DIMENSION` versus `CATEGORICAL_BUCKET`

`DISTINCT_DIMENSION` representa valores observados no dataset.

`CATEGORICAL_BUCKET` representa classificacoes governadas sobre os dados.

Regra pratica:

- se o valor publicado corresponde ao valor observado, use `DISTINCT_DIMENSION`
- se o valor publicado corresponde a uma classificacao derivada, use `CATEGORICAL_BUCKET`

## Contrato Canonico no Metadata

`/schemas/filtered` deve ser a superficie estrutural que publica o bloco `x-ui.optionSource`.

Diretriz minima:

- o bloco deve ser aditivo
- o contrato deve continuar apontando para `OptionDTO` como resposta operacional
- a publicacao deve explicitar a fonte sem exigir heuristica local do runtime

Exemplo ilustrativo:

```json
{
  "x-ui": {
    "controlType": "select",
    "optionSource": {
      "key": "universo",
      "type": "DISTINCT_DIMENSION",
      "resourcePath": "/api/folha/eventos",
      "filterField": "universo",
      "dependsOn": ["empresaId"]
    }
  }
}
```

## Compatibilidade Transitoria

Durante a adequacao dos consumidores, o backend ainda pode publicar o shape compativel:

- `endpoint`
- `valueField`
- `displayField`
- `POST /options/filter`
- `GET /options/by-ids`

Direcao de convivio:

- campos existentes podem continuar publicando apenas `endpoint/valueField/displayField`
- campos no baseline atual devem preferir `x-ui.optionSource`
- um mesmo campo pode publicar ambos os blocos quando isso reduzir risco operacional
- o runtime oficial deve preferir `x-ui.optionSource` quando disponivel

Essa compatibilidade nao muda a recomendacao principal da plataforma.

## Nao Objetivos Desta Fase

Esta RFC nao tenta resolver agora:

- remocao imediata do shape compativel `endpoint/valueField/displayField`
- suporte completo a todos os tipos no executor JPA padrao
- conversao automatica de qualquer campo textual em fonte de options
- exposicao de `stats/*` como API publica de options

## Endpoints Canonicos Propostos

### Filtro de opcoes por source

- `POST /{resource}/option-sources/{sourceKey}/options/filter`

### Reidratacao de selecionados por source

- `GET /{resource}/option-sources/{sourceKey}/options/by-ids`

Regras:

- o payload de resposta converge para `OptionDTO`
- o contrato da fonte vive em `x-ui.optionSource`
- o endpoint operacional nao vira a fonte primaria da semantica

## Request e Cascata

Quando a source depender de outros campos:

- `dependsOn` deve listar as dependencias relevantes
- `filterField` deve explicitar o campo real quando a `key` da source nao coincidir com ele
- o runtime nao deve inferir cascata por naming heuristic

## Relacao com `StatsFieldRegistry`

O starter pode reaproveitar infraestrutura de stats para executar algumas fontes, em especial
`DISTINCT_DIMENSION`, mas isso nao altera o contrato publico.

Regra:

- `StatsFieldRegistry` e detalhe operacional
- `optionSource` e a superficie canonica publicada ao consumidor

## `OptionDTO`

`OptionDTO` continua sendo o payload publico minimo esperado para opcoes remotas.

Campos minimos esperados:

- `id`
- `label`

Campos adicionais podem existir quando houver valor claro de plataforma, mas sem fragmentar
o contrato por tipo de fonte.

## Publicacao em `/schemas/filtered`

`/schemas/filtered` deve publicar a capacidade de fonte de opcao por campo.

Direcao minima:

- manter o shape compativel para `endpoint/valueField/displayField` enquanto necessario
- adicionar `x-ui.optionSource`
- explicitar a capability de options derivadas no nivel certo de recurso, operacao ou campo

Com isso, o runtime consumidor pode:

- continuar funcionando com o shape compativel
- preferir a semantica canonica quando disponivel

## Compatibilidade com Angular

O runtime Angular ja esta mais proximo de `resourcePath` base do que de endpoint arbitrario final.

Implicacao:

- `optionSource` reduz heuristica local
- `resourcePath` alinha melhor backend e runtime
- o consumidor oficial deve ser tratado como prova operacional do contrato

## Alternativas Consideradas

### 1. Exigir sempre recurso CRUD proprio

Rejeitada porque supermodela casos simples de selecao assistida e empurra a plataforma para
mais entidades do que o dominio realmente precisa.

### 2. Criar endpoint ad hoc por recurso analitico

Rejeitada porque institucionaliza contratos locais e enfraquece a governanca canonicamente
publicada em `x-ui`.

### 3. Expor `stats/group-by` como API publica de options

Rejeitada porque mistura superficie analitica com superficie operacional de selecao.

### 4. Manter apenas `endpoint/valueField/displayField`

Rejeitada como direcao principal porque obriga o runtime a operar por shape operacional em vez
de semantica canonica de fonte.

## Casos Prioritarios

Casos que devem guiar a primeira implementacao:

- dimensao distinta de universo
- bucket categorico como faixa salarial
- lookup leve com busca e reidratacao por IDs
- cascata governada por `dependsOn`

## Seguranca e Governanca

O contrato de options derivadas deve respeitar as mesmas guardrails de contrato publico do starter:

- sem endpoint improvisado como fonte de verdade
- sem duplicacao de semantica em consumidor
- sem heuristica local para inferir algo que o backend pode publicar explicitamente

## Versionamento

Recomendacao:

- `version` no bloco `x-ui.optionSource`
- rollout aditivo
- sem remocao do modelo compativel `endpoint/valueField/displayField` na primeira fase

## Plano de Evolucao

### Fase 1 - Contrato

- fechar o shape canonico de `x-ui.optionSource`
- documentar tipos iniciais e regras de uso

### Fase 2 - Starter

- publicar o bloco em `/schemas/filtered`
- suportar filtro e reidratacao para os tipos iniciais priorizados

### Fase 3 - Host de referencia

- provar a superficie em `praxis-api-quickstart`
- validar cascata, filtro e reidratacao em fluxo real

### Fase 4 - Runtime Angular

- consumir `optionSource` como referencia principal
- manter shape compativel apenas como fallback operacional

### Fase 5 - Exemplos e Conformidade

- atualizar exemplos oficiais
- alinhar docs de conformance e guias

## Introducao Didatica para Iniciantes

Se um campo precisa mostrar opcoes dinamicas, a plataforma precisa responder tres perguntas:

1. de onde vem a opcao
2. como filtrar opcoes validas para o contexto atual
3. como reidratar opcoes ja selecionadas

`x-ui.optionSource` existe para responder essas perguntas de forma canonica, sem depender de
contratos ad hoc espalhados entre backend e frontend.

## Exemplos Praticos Expandidos

### Exemplo 1: `DISTINCT_DIMENSION` para `universo`

Use quando o campo precisa listar valores distintos realmente observados no conjunto filtrado.

### Exemplo 2: `CATEGORICAL_BUCKET` para faixa salarial

Use quando o usuario escolhe categorias governadas, e nao valores brutos diretamente observados.

### Exemplo 3: Dependencias em cascata

Se a lista de opcoes depende de empresa, filial ou outro campo, publique `dependsOn` e
`filterField` quando necessario.

## Decisoes em Aberto

Itens ainda dependentes de implementacao completa:

- cobertura de todos os tipos pelo executor JPA padrao
- formato final de hints adicionais de policy e cache

## Pitfalls Comuns

- tratar `endpoint` compativel como se ainda fosse a direcao canonica
- inferir fonte por heuristica local no runtime
- misturar semantica de stats com semantica publica de options
- publicar cascata sem `dependsOn` explicito

## Conclusao

`x-ui.optionSource` deve se consolidar como a forma canonica de publicar opcoes derivadas no
backend Praxis. Shapes compativeis ainda aceitos existem apenas para reduzir risco operacional
durante a adequacao dos consumidores, sem competir com a direcao oficial da plataforma.
