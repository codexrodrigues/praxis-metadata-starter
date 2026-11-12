package org.praxisplatform.uischema.authoring;

import java.util.List;

/**
 * Recomendação determinística de índice de banco de dados derivada de {@code @Filterable}
 * ou {@code StatsFieldRegistry}.
 *
 * @param targetEntityName nome simples da entidade JPA dona da tabela onde a coluna reside
 * @param tableName nome físico da tabela SQL ({@code @Table(name = ...)} ou snake_case padrão)
 * @param propertyPath caminho da propriedade a partir da entidade raiz (ou na entidade alvo)
 * @param columnName nome físico da coluna SQL ({@code @Column}/{@code @JoinColumn} ou snake_case padrão)
 * @param indexKind tipo de índice recomendado ({@link FilterableIndexKind})
 * @param coveredByJpaMetadata indica se a entidade JPA já declara {@code @Id}, {@code unique = true},
 *                             {@code @UniqueConstraint} ou {@code @Index} cobrindo a coluna como líder
 * @param sourceFilterProperties propriedades do {@code FilterDTO} ou métricas de {@code StatsFieldRegistry} que exigem o índice
 * @param jpaIndexSnippet snippet pronto de {@code @Index(name = "...", columnList = "...")} para {@code @Table}
 * @param postgresDdl instrução idempotente {@code CREATE INDEX IF NOT EXISTS ...} para migração Flyway/Liquibase
 */
public record FilterableIndexRecommendation(
        String targetEntityName,
        String tableName,
        String propertyPath,
        String columnName,
        FilterableIndexKind indexKind,
        boolean coveredByJpaMetadata,
        List<String> sourceFilterProperties,
        String jpaIndexSnippet,
        String postgresDdl
) {
    public FilterableIndexRecommendation {
        sourceFilterProperties = sourceFilterProperties == null ? List.of() : List.copyOf(sourceFilterProperties);
    }
}
