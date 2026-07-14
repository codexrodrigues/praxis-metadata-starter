# RFC - Bucket de Stats com Identidade Estavel e Display Label

## Status

- estado: `proposed`
- issue: #98
- classe: `contrato-publico + arquitetural`
- aderencia: `lacuna-real-de-contrato`

## Decisao

Uma dimensao categorica de `praxis.stats` passa a possuir uma identidade de
bucket e, opcionalmente, um display label distintos. O request continua aceitando
somente o `field` canonico. Paths JPA nao sao parte do contrato HTTP.

```java
StatsFieldRegistry.builder()
        .labeledGroupByBucket(
                "departamento",
                "departamento.id",
                "departamento.nome",
                Set.of(StatsMetric.COUNT, StatsMetric.DISTINCT_COUNT)
        )
        .build();
```

```json
{
  "key": "D-ENG",
  "label": "Engenharia"
}
```

`key` e a unica identidade para selecao, cross-filter, drill-down e filtros
subsequentes. `label` serve somente para apresentacao.

## Modelo interno

`StatsFieldDescriptor` deve representar explicitamente `keyPropertyPath` e
`labelPropertyPath`. Para dimensoes sem label explicito, o path de label e o path
de key; esse e o unico comportamento legado preservado. Os builders de um unico
path continuam descrevendo essa semantica, sem criar um DTO HTTP v1/v2.

A execucao JPA seleciona e agrupa key e label na mesma query. Nenhuma hidratacao
por bucket e permitida. Relacoes aninhadas devem usar join criteria compartilhado,
e colunas escalares continuam validas.

## Nulos e mudanca de label

- key nula e um bucket valido, com `key = null` e `label = "null"` no primeiro
  corte; o texto e deliberadamente deterministico e nao pode ser usado como filtro.
- label nulo com key nao nula materializa `String.valueOf(key)` como fallback
  somente de apresentacao.
- uma dimensao rotulada deve manter uma relacao funcional de key para label no
  dataset analitico. Caso o dominio permita historico de labels para a mesma key,
  ele deve publicar uma view analitica que escolha o label aplicavel antes de
  registrar a dimensao; o starter nao infere atualidade de dados.

## Discovery publico

`StatsFieldRegistry` continua sendo a fonte de verdade. `/capabilities` apenas
projeta elegibilidade e semantica publica. `StatsFieldCapability.propertyPath`
deve ser removido no mesmo corte beta: ele e detalhe de executor e nao pode
fundamentar um consumidor. A capability pode publicar que uma dimensao tem
identidade e display separados, mas nunca seus paths internos.

`/schemas/filtered`, `/schemas/catalog`, ETag e `X-Schema-Hash` devem refletir
o shape efetivamente publicado. Nenhum consumidor deve reconstruir uma key pelo
label ou por um alias local.

## Fora do escopo

- lookup endpoint ad hoc para labels;
- inferencia de `id`, `nome` ou relacoes pelo nome;
- remapeamento de bucket no Angular;
- politica de dominio ou de seguranca especifica de HR;
- `POST /{resource}/stats/comparison`, que pertence a #99.

## Provas e migracao

1. Relacao JPA retorna key estavel e label humano em `group-by`.
2. Labels iguais com keys distintas permanecem buckets distintos.
3. Dimensao escalar e dimensao legada de path unico continuam funcionando.
4. Capabilities e schemas nao expõem paths novos ou antigos.
5. Charts/Table exibem label e emitem key em pelo menos um consumidor real.

A migracao e limpa por estarmos em beta: atualizar starter, Quickstart e runtime
no mesmo ciclo; nao introduzir descriptors, endpoints ou payloads paralelos.
