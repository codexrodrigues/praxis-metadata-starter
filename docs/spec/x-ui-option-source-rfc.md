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

A plataforma publica opcoes locais e fontes remotas de opcoes como parte do contrato
metadata-driven. `x-ui.optionSource` define a linguagem publica unica para campos que
dependem de uma fonte derivada, governada ou reidratavel.

O objetivo desta RFC e declarar essa forma canonica de publicacao, mantendo endpoints
operacionais como detalhe de execucao e impedindo que consumidores reconstruam semantica
por heuristicas locais.

## Diagnostico do Contrato

### Superficies existentes

- `OptionDTO` como payload publico para opcoes remotas
- endpoints operacionais de filtro e reidratacao por IDs
- runtime Angular com capacidade de consumir `optionSource`
- starter com suporte base para `DISTINCT_DIMENSION`, `CATEGORICAL_BUCKET` e `LIGHT_LOOKUP`
  leve, alem de `RESOURCE_ENTITY` rico

### Decisoes canonicas

- um bloco canonico unico em `x-ui`
- regras publicas para os tipos de fonte
- diretriz clara sobre quando usar `stats`, lookup leve ou entidade de recurso
- narrativa publica limpa para conformidade

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
- `dependencyFilterMap` quando a chave do campo dependente nao coincidir com a chave de filtro esperada pelo executor
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

Quando usado por `controlType: "entityLookup"`, `RESOURCE_ENTITY` publica tambem
semantica governada de entidade no proprio `x-ui.optionSource`:

- `entityKey`
- `valuePropertyPath`
- `labelPropertyPath`
- `codePropertyPath`
- `descriptionPropertyPaths`
- `statusPropertyPath`
- `disabledPropertyPath`
- `disabledReasonPropertyPath`
- `searchPropertyPaths`
- `dependencyFilterMap`
- `selectionPolicy`
- `capabilities`
- `detail`
- `display`
- `filtering`

O bloco `display` descreve a intencao de UX da referencia de entidade. Ele nao
substitui surface, formulario ou tabela; ele informa ao runtime qual leitura
semantica aplicar quando a mesma option-source aparecer em formulario, filtro,
celula editavel, dashboard ou revisao. Presets canonicos iniciais:

- `directory`: pessoas, equipes e cadastros com avatar/iniciais, titulo e subtitulo.
- `reference`: codigo + descricao, recomendado para centros de custo, contratos e produtos.
- `status`: entidades em que o estado operacional precisa aparecer como badge.
- `hierarchical`: entidades com caminho/parent.
- `rich`: linha rica generica.
- `compact`: leitura densa para filtros inline e celulas.

`display.fields[]` publica subinformacoes ricas da entidade sem transformar o
frontend em fonte de regra visual. Cada item declara `propertyPath`, `label`,
`icon`, `presentation` (`text`, `chip`, `badge`, `date`, `currency`, `metric`),
`tone` e `format`. O executor de option-source pode materializar esses campos em
`OptionDTO.extra.richFields[]`, permitindo que runtimes mostrem ícones, chips e
datas formatadas tanto na lista quanto no item selecionado.

O bloco `filtering` governa busca corporativa rica sem empurrar convencoes de query
para o frontend. Ele descreve:

- `availableFilters`
- `defaultFilters`
- `sortOptions`
- `defaultSort`
- `quickFilterFields`
- `searchPlaceholder`
- `searchStrategies`

`searchStrategies` declara as interpretacoes publicas de um termo de busca,
como `business-code`, `descriptive-text` e `normalized-document`. O provider
continua dono de normalizacao, bindings e execucao; SQL, argumentos internos e
documento bruto nao fazem parte de `x-ui.optionSource` nem de `OptionDTO.extra`.

Esse bloco permite que runtime, formularios, tabelas e editores tratem o lookup como
selecao de entidade real, com identidade, status, permissao, reidratacao e navegacao,
sem promover aliases locais no frontend.

Exemplo:

```json
{
  "controlType": "entityLookup",
  "optionSource": {
    "key": "company",
    "type": "RESOURCE_ENTITY",
    "resourcePath": "/api/companies",
    "filterEndpoint": "/api/companies/option-sources/company/options/filter",
    "byIdsEndpoint": "/api/companies/option-sources/company/options/by-ids",
    "selectedReloadPolicy": "required",
    "invalidSortPolicy": "reject",
    "entityKey": "company",
    "valuePropertyPath": "id",
    "labelPropertyPath": "legalName",
    "codePropertyPath": "code",
    "descriptionPropertyPaths": ["documentNumber", "city", "state"],
    "statusPropertyPath": "status",
    "searchPropertyPaths": ["code", "legalName", "documentNumber"],
    "selectionPolicy": {
      "selectablePropertyPath": "selectable",
      "statusPropertyPath": "status",
      "allowedStatuses": ["ACTIVE"],
      "allowRetainInvalidExistingValue": true
    },
    "capabilities": {
      "filter": true,
      "byIds": true,
      "detail": true,
      "create": false
    },
    "detail": {
      "kind": "surface",
      "surfaceId": "view",
      "presentation": "drawer",
      "preferredWidget": "praxis-dynamic-form",
      "mode": "view"
    },
    "display": {
      "preset": "directory",
      "usage": "form",
      "density": "comfortable",
      "selectedLayout": "compact",
      "resultLayout": "list",
      "primaryPropertyPath": "legalName",
      "fields": [
        { "key": "document", "propertyPath": "documentNumber", "label": "Documento", "icon": "badge", "presentation": "chip", "tone": "neutral" },
        { "key": "city", "propertyPath": "city", "label": "Cidade", "icon": "location_on", "presentation": "text", "tone": "info" }
      ],
      "secondaryPropertyPaths": ["documentNumber", "city", "state"],
      "badgePropertyPaths": ["status"],
      "showAvatar": true,
      "showCode": true,
      "showDescription": true,
      "showStatus": true,
      "showBadges": true,
      "showResultCount": true
    },
    "filtering": {
      "availableFilters": [
        {
          "field": "status",
          "label": "Status",
          "type": "enum",
          "operators": ["equals", "in"],
          "defaultOperator": "in",
          "optionsSource": "company-status"
        },
        {
          "field": "documentNumber",
          "label": "Documento",
          "type": "text",
          "operators": ["contains", "equals"],
          "defaultOperator": "contains"
        }
      ],
      "defaultFilters": {
        "status": ["ACTIVE"]
      },
      "sortOptions": [
        {
          "key": "legalNameAsc",
          "field": "legalName",
          "direction": "asc",
          "label": "Nome A-Z"
        }
      ],
      "defaultSort": "legalNameAsc",
      "quickFilterFields": ["code", "legalName"],
      "searchPlaceholder": "Buscar empresa por codigo, nome ou documento"
    }
  }
}
```

Request canonico esperado para filtro rico:

```json
{
  "filters": [
    { "field": "status", "operator": "in", "value": ["ACTIVE"] },
    { "field": "documentNumber", "operator": "contains", "value": "123" }
  ],
  "sort": "legalNameAsc"
}
```

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

No executor JPA compartilhado, `LIGHT_LOOKUP` materializa apenas `OptionDTO{id,label}`.
O descriptor deve informar `propertyPath` quando valor e label sao o mesmo campo, ou
`valuePropertyPath` e `labelPropertyPath` quando o identificador e o texto vêm de
campos diferentes. Se a fonte precisa de status, politica de selecao, rota de detalhe,
display rico ou filtros corporativos estruturados, use `RESOURCE_ENTITY` com
`entityLookup` em vez de `LIGHT_LOOKUP`.

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

## Contrato Canonico

Campos que usam fontes remotas devem publicar o bloco canonico `x-ui.optionSource`.
O backend nao deve criar shapes paralelos para a mesma semantica.

Campos operacionais do contrato:

- `key`
- `type`
- `resourcePath`
- `filterField`
- `dependsOn`
- `dependencyFilterMap`

Endpoints de execucao:

- `POST /option-sources/{sourceKey}/options/filter`
- `GET /option-sources/{sourceKey}/options/by-ids`

## Limites Deste Contrato

Esta RFC nao define:

- suporte completo a todos os tipos no executor JPA padrao; `STATIC_CANONICAL` segue fora
  da cobertura compartilhada
- conversao automatica de qualquer campo textual em fonte de options
- exposicao de `stats/*` como API publica de options

## Endpoints Canonicos Propostos

### Filtro de opcoes por source

- `POST /{resource}/option-sources/{sourceKey}/options/filter`

### Reidratacao de selecionados por source

- `GET /{resource}/option-sources/{sourceKey}/options/by-ids`
- `POST /{resource}/option-sources/{sourceKey}/options/by-ids` quando a source precisar do filtro/contexto publico

Regras:

- o payload de resposta converge para `OptionDTO`
- o contrato da fonte vive em `x-ui.optionSource`
- o endpoint operacional nao vira a fonte primaria da semantica
- o `POST .../by-ids` reutiliza o filtro estrutural governado da source para evitar selected-value reload fora de contexto

## Request e Cascata

Quando a source depender de outros campos:

- `dependsOn` deve listar as dependencias relevantes
- `dependencyFilterMap` deve mapear dependencia -> chave de filtro quando o nome publicado em `dependsOn` nao for a chave esperada no payload de filtro
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

Regra minima:

- publicar `x-ui.optionSource`
- explicitar a capability de options derivadas no nivel certo de recurso, operacao ou campo

Com isso, o runtime consumidor pode:

- consumir a semantica canonica publicada pelo backend
- usar endpoints operacionais apenas como meio de execucao

## Cobertura no Angular Oficial

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

Decisao do contrato atual:

- `version` nao faz parte do bloco publico `x-ui.optionSource` em `1.0.0`
- o schema fechado rejeita chaves nao documentadas no bloco
- evolucoes de versionamento devem acontecer em revisao explicita do schema, do runtime Angular
  e dos editores, nao por `extraProperties` ou convencao local

## Plano de Implementacao

### Contrato

- fechar o shape canonico de `x-ui.optionSource`
- documentar tipos iniciais e regras de uso

### Starter

- publicar o bloco em `/schemas/filtered`
- suportar filtro e reidratacao para os tipos iniciais priorizados

### Host de referencia

- provar a superficie em `praxis-api-quickstart`
- validar cascata, filtro e reidratacao em fluxo real

### Runtime Angular

- consumir `optionSource` como referencia principal

### Exemplos e Conformidade

- atualizar exemplos oficiais
- alinhar docs de conformance e guias

## Introducao Didatica para Iniciantes

Se um campo precisa mostrar opcoes dinamicas, a plataforma precisa responder tres perguntas:

1. de onde vem a opcao
2. como filtrar opcoes validas para o contexto atual
3. como reidratar opcoes ja selecionadas

`x-ui.optionSource` existe para responder essas perguntas de forma canonica, sem depender de
contratos ad hoc espalhados entre backend e frontend.

Quando uma source registrada nao corresponde a uma propriedade estrutural do DTO ou do filtro,
o starter nao inventa um campo em `/schemas/filtered`. O catalogo registry-wide do resource owner
e materializado em `/schemas/domain?resource={resourceKey}`, derivado do mesmo
`OptionSourceRegistry`. Capabilities continuam indicando disponibilidade operacional e nao
duplicam o descriptor. Detalhes privados de execucao, como `executionMode`, provider, SQL,
datasource e contexto do host, permanecem fora dessas superficies.

## Exemplos Praticos Expandidos

### Exemplo 1: `DISTINCT_DIMENSION` para `universo`

Use quando o campo precisa listar valores distintos realmente observados no conjunto filtrado.

### Exemplo 2: `CATEGORICAL_BUCKET` para faixa salarial

Use quando o usuario escolhe categorias governadas, e nao valores brutos diretamente observados.

### Exemplo 3: Dependencias em cascata

Se a lista de opcoes depende de empresa, filial ou outro campo, publique `dependsOn` e
`filterField` quando necessario.

## Decisoes de Cobertura

Itens que exigem implementacao correspondente:

- cobertura de todos os tipos pelo executor JPA padrao
- formato final de hints adicionais de policy e cache

## Pitfalls Comuns

- tratar endpoint operacional como fonte de verdade semantica
- inferir fonte por heuristica local no runtime
- misturar semantica de stats com semantica publica de options
- publicar cascata sem `dependsOn` explicito

## Conclusao

`x-ui.optionSource` e a forma canonica de publicar opcoes derivadas no backend Praxis.
Endpoints operacionais executam filtro e reidratacao, mas nao competem com a semantica
publicada em `x-ui`.
