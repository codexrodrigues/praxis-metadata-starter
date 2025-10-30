# Roadmap de Filtros — Lotes 2 e 3

Este documento descreve planos de evolução do mecanismo de filtros após o Lote 1 (core) já implementado. O objetivo é ampliar expressividade mantendo compatibilidade, legibilidade e portabilidade JPA.

- Lote 1 (entregue): operadores adicionais básicos (NOT_EQUAL, GTE/LTE, NOT_LIKE, STARTS/ENDS, NOT_IN, IS_NULL/IS_NOT_NULL, BETWEEN_EXCLUSIVE, NOT_BETWEEN, OUTSIDE_RANGE, ON_DATE, IN_LAST/NEXT_DAYS, SIZE_EQ/GT/LT, IS_TRUE/IS_FALSE)
- Lote 2 (multicampo e relação): comparações campo↔campo, sobreposição de intervalos e existência de relações
- Lote 3 (avançadas/dialeto): REGEX/UNACCENT/JSON*/GEO* via functions e modularização

## Lote 2 — Multicampo e Relação

### Objetivo
Habilitar regras envolvendo dois campos (no mesmo root ou em relação) e existência de registros relacionados.

### Operações alvo
- FIELD_EQ_FIELD, FIELD_GT_FIELD, FIELD_GTE_FIELD, FIELD_LT_FIELD, FIELD_LTE_FIELD
  - Compara o valor de um campo A com o valor de um campo B (ambos `Comparable`).
  - Ex.: `precoPromocional < precoCheio`.
- RANGE_OVERLAP
  - Verifica sobreposição entre dois intervalos (A:[startA,endA]) e (B:[startB,endB]).
  - Regra típica: `(startA <= endB) AND (startB <= endA)`; variantes exclusivas podem ser previstas.
- EXISTS_RELATION / NOT_EXISTS_RELATION
  - Aplica `EXISTS (subquery)` para relação 1:N / N:1 / N:N com predicados simples no lado filho.
  - Útil para “tem pelo menos um filho com …” (ou ausência).

### Proposta de API (@Filterable)
Sem quebrar compatibilidade, estender a anotação com parâmetros opcionais:
- `otherField` (String): caminho do outro campo (pode ser relation path) para FIELD_*_FIELD
- `rangeStartField`, `rangeEndField` (String): caminhos para delimitar intervalos do campo B em RANGE_OVERLAP
- `existsRelation` (String): caminho para a relação (join) em EXISTS_RELATION/NOT_EXISTS_RELATION
- `existsFilter` (String, opcional): nome simbólico de um predicado simples a aplicar no filho (ex.: `ativo=true` ou referenciar outro campo do DTO)

Exemplos (DTO):
```java
// precoPromocional < precoCheio
@Filterable(operation = FIELD_LT_FIELD, otherField = "precoCheio")
private BigDecimal precoPromocional;

// Sobreposição de intervalos: (vigencia: [inicio, fim]) ~ (campanha: [campanhaInicio, campanhaFim])
@Filterable(operation = RANGE_OVERLAP, rangeStartField = "campanhaInicio", rangeEndField = "campanhaFim")
private LocalDate vigenciaInicio; // Pares de campos: vigenciaInicio/vigenciaFim
private LocalDate vigenciaFim;

// Existe pelo menos um filho ativo
@Filterable(operation = EXISTS_RELATION, existsRelation = "itens")
private Boolean temItemAtivo; // true → EXISTS (join itens WHERE itens.ativo = true)
```

### Padrão de implementação
- Builders:
  - FieldVsFieldPredicateBuilder (variante por operação: EQ/GT/GTE/LT/LTE)
    - Resolve ambos os paths (root ou relation) e aplica o predicado campo↔campo
  - RangeOverlapPredicateBuilder
    - Recebe `rangeStartField`, `rangeEndField` (campo B) e usa os dois campos A do DTO (ex.: vigenciaInicio/vigenciaFim)
  - ExistsRelationPredicateBuilder / NotExistsRelationPredicateBuilder
    - Cria subquery com join na relação e predicados simples (p.ex., campo booleano no filho = true), conforme `existsFilter`

### Critérios de aceite do Lote 2
- Builders aceitam tipos `Comparable` e validam parâmetros obrigatórios
- Subqueries com EXISTS/NOT EXISTS geradas corretamente
- Documentação com exemplos e limitações (ex.: filtros complexos no filho não cobertos no primeiro momento)
- Testes cobrindo casos positivos/negativos e relações 1:N/N:N

### Riscos e mitigação
- Complexidade de configuração (paths):
  - Mitigar com validações e mensagens claras; exemplos nos guias
- Performance (EXISTS / joins múltiplos):
  - Testes de carga simples e recomendações de índices

## Lote 3 — Avançadas / Dialeto

### Objetivo
Adicionar operadores/funcionalidades específicas de dialeto, mantendo portabilidade opcional via fallback.

### Operações alvo e funções
- REGEX / NOT_REGEX (strings): `path REGEXP '...'` (MySQL/Postgres)
- UNACCENT_* (strings): remover acentuação para buscas (PostgreSQL `unaccent`)
- JSON_*(chaves/paths): `jsonb_exists`, `jsonb_path_query` (PostgreSQL) ou equivalentes em outros dialetos
- GEO*(geoespacial): within/near/inside polygon (Hibernate Spatial/JTS, funções específicas)

### Proposta técnica
- Modularização do registro de builders via SPI/Factory
  - `PredicateBuilderRegistry` por dialeto; builders registrados se `DataSource` informar dialeto suportado
  - Fallback: se função indisponível, rejeitar operação com mensagem clara
- Uso de `CriteriaBuilder#function(name, ...)` para chamar funções do banco
- Qualificadores na anotação:
  - `caseSensitive`, `accentInsensitive`, `locale`
  - `jsonPath` (para JSON_*), `geoSrid`, `geoDistance`

### Exemplo (@Filterable)
```java
// UNACCENT + LIKE (PostgreSQL)
@Filterable(operation = LIKE, relation = "pessoa.nome", accentInsensitive = true)
private String nome;

// REGEX
@Filterable(operation = REGEX, relation = "pessoa.login")
private String loginRegex;

// JSON path equals
@Filterable(operation = JSON_PATH_EQUALS, relation = "payload")
private String jsonPath; // ex.: $.cliente.id == 123
```

### Critérios de aceite do Lote 3
- Builders são ativados somente quando `function` suportada pelo dialeto
- Documentação com tabelas por dialeto (Postgres/MySQL/…)
- Testes condicionais por perfil (ou mocks de CriteriaBuilder.function)

### Riscos e mitigação
- Portabilidade baixa: explicitar escopo por dialeto e falhas amigáveis
- Segurança de expressão (REGEX/JSON): validar padrões e limites

## Entregas e ordem sugerida
1) Lote 2 — FIELD_*_FIELD e RANGE_OVERLAP
2) Lote 2 — EXISTS_RELATION/NOT_EXISTS_RELATION
3) Lote 3 — UNACCENT/REGEX (strings)
4) Lote 3 — JSON*
5) Lote 3 — GEO*

## Documentação correlata
- Guia: docs/guides/FILTROS-E-PAGINACAO.md (acrescentar seções “Lote 2” e “Lote 3” com exemplos)
- Exemplos: docs/examples/ (DTOs e payloads dedicados para cada lote)
