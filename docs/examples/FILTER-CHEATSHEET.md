# Cheat Sheet — POST /{resource}/filter

Amostras de payload JSON para chamadas `POST /{resource}/filter` usando o DTO de exemplo (veja DTO-FILTROS-EXEMPLOS.md). Combine conforme a necessidade — apenas os campos presentes serão considerados.

- Guia completo: [Filtros e Paginação](../guides/FILTROS-E-PAGINACAO.md)

## Strings (LIKE, NOT_LIKE, STARTS_WITH, ENDS_WITH)

```http
POST /api/recursos/filter?page=0&size=20
Content-Type: application/json

{
  "nomeContem": "ana",
  "nomeNaoContem": "teste",
  "nomeComecaCom": "jo",
  "nomeTerminaCom": "silva"
}
```

## Igualdade/Diferença

```json
{
  "nomeIgual": "Carlos",
  "nomeDiferente": "Anônimo"
}
```

## Numéricos (>, >=, <, <=, BETWEEN, BETWEEN_EXCLUSIVE, NOT_BETWEEN, OUTSIDE_RANGE)

```json
{
  "valorMaiorQue": 100.0,
  "valorMaiorIgual": 200.0,
  "valorMenorQue": 1000.0,
  "valorMenorIgual": 999.99,
  "valorEntreInclusivo": [100.0, 500.0],
  "valorEntreExclusivo": [100.0, 500.0],
  "valorNaoEntre": [300.0, 400.0],
  "valorForaDoIntervalo": [0.0, 50.0]
}
```

## Datas (ON_DATE, IN_LAST_DAYS, IN_NEXT_DAYS)

```json
{
  "criadoEm": "2024-05-10",
  "criadosNosUltimosDias": 7,
  "vencemNosProximosDias": 3
}
```

## Listas (IN, NOT_IN)

```json
{
  "idsEmLista": [
    "b3d61a8d-3e78-4db0-bf95-4b627b3f6f0a",
    "2f3b611d-9bb2-4fb5-9df2-3f060ad0a2a1"
  ],
  "idsForaDaLista": [
    "f70e28f3-624b-4d59-90e7-7f1e0e77da9e"
  ]
}
```

## Tamanho de coleção (SIZE_EQ/GT/LT)

```json
{
  "tagsTamanhoIgual": 3,
  "tagsTamanhoMaiorQue": 5,
  "tagsTamanhoMenorQue": 2
}
```

## Booleanos e Null Checks

```json
{
  "ativoEhVerdadeiro": true,
  "ativoEhFalso": true,
  "descricaoIsNull": true,
  "descricaoIsNotNull": true
}
```

## Relações (LIKE com relation)

```json
{
  "tipoNomeContem": "administrativo"
}
```

## Dica: combinando múltiplos filtros

```json
{
  "nomeContem": "ana",
  "valorEntreInclusivo": [100.0, 500.0],
  "criadosNosUltimosDias": 15,
  "idsEmLista": ["2f3b611d-9bb2-4fb5-9df2-3f060ad0a2a1"],
  "ativoEhVerdadeiro": true
}
```

## Caso real completo (múltiplas categorias + paginação + 2 sorts)

```http
POST /api/recursos/filter?page=0&size=50&sort=tipoNomeContem,asc&sort=valorMaiorIgual,desc
Content-Type: application/json

{
  "nomeContem": "ana",
  "valorMaiorIgual": 200.0,
  "valorEntreInclusivo": [200.0, 1000.0],
  "criadosNosUltimosDias": 30,
  "idsEmLista": [
    "2f3b611d-9bb2-4fb5-9df2-3f060ad0a2a1",
    "b3d61a8d-3e78-4db0-bf95-4b627b3f6f0a"
  ],
  "tagsTamanhoMaiorQue": 2,
  "ativoEhVerdadeiro": true,
  "descricaoIsNotNull": true,
  "tipoNomeContem": "admin"
}
```

Explicação rápida:
- Filtros combinados por AND: string (LIKE), numéricos (>=, BETWEEN), datas relativas (últimos 30 dias), listas (IN), tamanho de coleção (SIZE_GT), booleano (IS_TRUE) e null check (IS_NOT_NULL).
- Ordenação primária por `tipoNomeContem` (mapeia para `tipo.nome` via relation no DTO) e secundária por `valorMaiorIgual` desc.
- Página 0 com 50 itens por página.

## Caso real 2 — datas exatas (ON_DATE), listas NOT_IN e ordenação invertida

```http
POST /api/recursos/filter?page=1&size=25&sort=valorMaiorIgual,asc&sort=tipoNomeContem,desc
Content-Type: application/json

{
  "criadoEm": "2024-06-15",
  "idsForaDaLista": [
    "f70e28f3-624b-4d59-90e7-7f1e0e77da9e",
    "c1d7f6ea-0f2a-4a34-b1f1-0a0e2b53a6e9"
  ],
  "nomeNaoContem": "teste",
  "ativoEhFalso": true
}
```

Explicação rápida:
- `criadoEm` usa `ON_DATE` para comparar apenas a data (ignora horas), aplicando janela de 24h.
- `idsForaDaLista` aplica `NOT_IN` para excluir elementos por ID.
- Ordenação invertida em relação ao exemplo anterior: primária por `valorMaiorIgual` asc e secundária por `tipoNomeContem` desc.
- Página 1 (segunda página), 25 itens por página.

## Paginação e Ordenação (incluindo sort por relation)

- Paginação via `page` (0‑based) e `size` (limite por política).
- Ordenação via `sort=campo,direcao`. Múltiplos `sort` são permitidos.
- Ordenação por relation: informe o nome do campo do DTO de filtro; o resolver mapeará para `relation` definido na anotação `@Filterable`.

Exemplo 1 — página 0, 20 itens, ordena por nome asc:

```http
POST /api/recursos/filter?page=0&size=20&sort=nomeContem,asc
Content-Type: application/json

{
  "nomeContem": "ana"
}
```

Exemplo 2 — múltiplas ordenações (nome asc, valor desc):

```http
POST /api/recursos/filter?page=1&size=50&sort=nomeContem,asc&sort=valorMaiorQue,desc
Content-Type: application/json

{
  "valorMaiorQue": 100.0
}
```

Exemplo 3 — ordenar por campo relacionado usando `relation` (ex.: `tipo.nome`):

No DTO de exemplo, `tipoNomeContem` tem `@Filterable(..., relation = "tipo.nome")`. Para ordenar pela relação, use o nome do campo do DTO no `sort`:

```http
POST /api/recursos/filter?page=0&size=20&sort=tipoNomeContem,asc
Content-Type: application/json

{
  "tipoNomeContem": "admin"
}
```

O builder mapeia automaticamente `tipoNomeContem` → `tipo.nome` para a cláusula ORDER BY.
