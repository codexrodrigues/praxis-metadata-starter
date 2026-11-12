package org.praxisplatform.uischema.authoring;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Relatório consolidado de auditoria e recomendação de índices de banco de dados
 * derivado estaticamente da entidade JPA, do {@code FilterDTO} e do {@code StatsFieldRegistry}.
 *
 * @param entityType classe da entidade JPA raiz analisada
 * @param filterDtoType classe do {@code FilterDTO} analisado
 * @param recommendations lista determinística de recomendações de índices B-Tree e GIN trigram
 * @param unresolvedPropertyPaths caminhos declarados em {@code @Filterable} que não puderam ser resolvidos na entidade
 */
public record FilterableIndexAdviceReport(
        Class<?> entityType,
        Class<?> filterDtoType,
        List<FilterableIndexRecommendation> recommendations,
        List<String> unresolvedPropertyPaths
) {
    public FilterableIndexAdviceReport {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        unresolvedPropertyPaths = unresolvedPropertyPaths == null ? List.of() : List.copyOf(unresolvedPropertyPaths);
    }

    /**
     * Retorna recomendações de índices B-Tree ({@code BTREE}, {@code BTREE_RANGE}, {@code BTREE_PATTERN})
     * que ainda não estão cobertas por {@code @Table(indexes = ...)}, {@code @Id} ou {@code unique = true}
     * na entidade JPA correspondente.
     */
    public List<FilterableIndexRecommendation> unindexedBtreeRecommendations() {
        return recommendations.stream()
                .filter(rec -> rec.indexKind() != FilterableIndexKind.GIN_TRGM && !rec.coveredByJpaMetadata())
                .toList();
    }

    /**
     * Retorna recomendações de índices PostgreSQL {@code GIN (pg_trgm)} para colunas textuais
     * consultadas via {@code FilterOperation.LIKE} ou busca ampla ({@code relations = {...}}).
     */
    public List<FilterableIndexRecommendation> ginTrigramRecommendations() {
        return recommendations.stream()
                .filter(rec -> rec.indexKind() == FilterableIndexKind.GIN_TRGM)
                .toList();
    }

    /**
     * Renderiza um script SQL/Flyway idempotente para PostgreSQL contendo a extensão {@code pg_trgm}
     * (quando aplicável) e todas as instruções {@code CREATE INDEX IF NOT EXISTS}.
     */
    public String renderPostgresMigrationSql() {
        if (recommendations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean needsPgTrgm = recommendations.stream()
                .anyMatch(rec -> rec.indexKind() == FilterableIndexKind.GIN_TRGM);
        if (needsPgTrgm) {
            sb.append("CREATE EXTENSION IF NOT EXISTS pg_trgm;\n\n");
        }
        for (FilterableIndexRecommendation rec : recommendations) {
            sb.append("-- Sources: ").append(String.join(", ", rec.sourceFilterProperties())).append('\n');
            sb.append(rec.postgresDdl()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Renderiza os snippets {@code @Index(name = "...", columnList = "...")} para as colunas B-Tree
     * ainda não indexadas na entidade raiz.
     */
    public String renderMissingJpaIndexesSnippet() {
        if (entityType == null) {
            return "";
        }
        return renderMissingJpaIndexesSnippet(entityType.getSimpleName());
    }

    /**
     * Renderiza os snippets {@code @Index(name = "...", columnList = "...")} para as colunas B-Tree
     * ainda não indexadas em uma entidade alvo específica.
     */
    public String renderMissingJpaIndexesSnippet(String targetEntitySimpleName) {
        return unindexedBtreeRecommendations().stream()
                .filter(rec -> rec.targetEntityName().equals(targetEntitySimpleName))
                .map(FilterableIndexRecommendation::jpaIndexSnippet)
                .distinct()
                .collect(Collectors.joining(",\n"));
    }
}
