package org.praxisplatform.uischema.authoring;

/**
 * Classificação canônica de índices de banco de dados recomendados a partir
 * dos contratos declarativos {@code @Filterable} e {@code StatsFieldRegistry}.
 */
public enum FilterableIndexKind {

    /**
     * Índice B-Tree padrão para igualdade ({@code EQUAL}), conjuntos ({@code IN}),
     * nulidade, chaves estrangeiras de junção/subquery correlacionada e dimensões {@code GROUP BY}.
     */
    BTREE,

    /**
     * Índice B-Tree ordenado para predicados SARGable de intervalo temporal ou numérico
     * ({@code BETWEEN}, {@code >=}, {@code <=}, {@code ON_DATE}, {@code IN_LAST_DAYS}, {@code IN_NEXT_DAYS}).
     */
    BTREE_RANGE,

    /**
     * Índice B-Tree otimizado para busca por prefixo ({@code STARTS_WITH}).
     */
    BTREE_PATTERN,

    /**
     * Índice GIN trigram ({@code pg_trgm} / {@code gin_trgm_ops}) no PostgreSQL para buscas textuais
     * por substring ({@code LIKE '%termo%'}) e campos de busca ampla ({@code relations = {...}}).
     */
    GIN_TRGM
}
