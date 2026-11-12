package org.praxisplatform.uischema.authoring;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.praxisplatform.uischema.extension.ResolverUtils;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Analisador estático que deriva recomendações determinísticas de índices B-Tree e PostgreSQL GIN ({@code pg_trgm})
 * a partir dos contratos declarativos {@link Filterable} (em {@code FilterDTO}) e {@link StatsFieldRegistry},
 * confrontando-os com os metadados JPA ({@link Table#indexes()}, {@link UniqueConstraint}, {@link Id} e {@link Column#unique()})
 * da entidade de domínio e de suas associações ({@code @ManyToOne}, {@code @OneToOne}, {@code @OneToMany}, {@code @Embedded}).
 */
public class FilterableIndexAdvisor {

    public FilterableIndexAdviceReport analyze(Class<?> entityType, Class<?> filterDtoType) {
        return analyze(entityType, filterDtoType, null);
    }

    public FilterableIndexAdviceReport analyze(
            Class<?> entityType,
            Class<?> filterDtoType,
            StatsFieldRegistry statsFields
    ) {
        if (entityType == null || filterDtoType == null) {
            return new FilterableIndexAdviceReport(entityType, filterDtoType, List.of(), List.of());
        }

        Map<RecommendationKey, MutableRecommendation> acc = new LinkedHashMap<>();
        Set<String> unresolvedPaths = new LinkedHashSet<>();

        Map<String, Filterable> filterablesByProperty = collectFilterables(filterDtoType);
        for (var entry : filterablesByProperty.entrySet()) {
            String dtoProp = entry.getKey();
            Filterable filterable = entry.getValue();
            List<String> targetPaths = extractTargetPaths(dtoProp, filterable);
            for (String rawPath : targetPaths) {
                String path = rawPath == null ? "" : rawPath.trim();
                if (path.isEmpty()) {
                    continue;
                }
                boolean resolved = analyzePropertyPath(
                        entityType,
                        path,
                        filterable.operation(),
                        dtoProp,
                        acc
                );
                if (!resolved) {
                    unresolvedPaths.add(path);
                }
            }
        }

        if (statsFields != null && !statsFields.isEmpty()) {
            for (StatsFieldDescriptor descriptor : statsFields.descriptors()) {
                String keyPath = descriptor.keyPropertyPath();
                if (keyPath != null && !keyPath.isBlank()) {
                    FilterOperation pseudoOp = descriptor.timeSeriesEligible()
                            ? FilterOperation.BETWEEN
                            : FilterOperation.EQUAL;
                    boolean resolvedKey = analyzePropertyPath(
                            entityType,
                            keyPath.trim(),
                            pseudoOp,
                            "stats:" + descriptor.field(),
                            acc
                    );
                    if (!resolvedKey) {
                        unresolvedPaths.add(keyPath.trim());
                    }
                }
                String labelPath = descriptor.labelPropertyPath();
                if (labelPath != null && !labelPath.isBlank() && !labelPath.trim().equals(keyPath == null ? "" : keyPath.trim())) {
                    boolean resolvedLabel = analyzePropertyPath(
                            entityType,
                            labelPath.trim(),
                            FilterOperation.EQUAL,
                            "stats:" + descriptor.field() + " (label)",
                            acc
                    );
                    if (!resolvedLabel) {
                        unresolvedPaths.add(labelPath.trim());
                    }
                }
            }
        }

        analyzeDefaultSortColumns(entityType, acc);
        Set<Class<?>> tableOwners = new LinkedHashSet<>();
        tableOwners.add(entityType);
        for (MutableRecommendation rec : acc.values()) {
            if (rec.tableOwnerEntity != null) {
                tableOwners.add(rec.tableOwnerEntity);
            }
        }
        for (Class<?> owner : tableOwners) {
            validateDeclaredTableIndexes(owner, unresolvedPaths);
        }

        List<FilterableIndexRecommendation> recommendations = acc.values().stream()
                .map(MutableRecommendation::toImmutable)
                .toList();

        return new FilterableIndexAdviceReport(
                entityType,
                filterDtoType,
                recommendations,
                List.copyOf(unresolvedPaths)
        );
    }

    private Map<String, Filterable> collectFilterables(Class<?> filterDtoType) {
        Map<String, Filterable> result = new LinkedHashMap<>();
        Set<String> shadowedOrDeclaredFields = new LinkedHashSet<>();

        if (filterDtoType.isRecord() && filterDtoType.getRecordComponents() != null) {
            for (RecordComponent rc : filterDtoType.getRecordComponents()) {
                Filterable f = ResolverUtils.getAnnotation(Filterable.class, rc.getAnnotations());
                if (f == null && rc.getAccessor() != null) {
                    f = ResolverUtils.getAnnotation(Filterable.class, rc.getAccessor().getAnnotations());
                }
                shadowedOrDeclaredFields.add(rc.getName());
                if (f != null) {
                    result.putIfAbsent(rc.getName(), f);
                }
            }
        }

        Class<?> current = filterDtoType;
        while (current != null && current != Object.class && current != Record.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String fieldName = field.getName();
                if (shadowedOrDeclaredFields.contains(fieldName)) {
                    continue;
                }
                Filterable f = ResolverUtils.getAnnotation(Filterable.class, field.getAnnotations());
                Method sameClassGetter = findDeclaredGetterForProperty(current, fieldName);
                if (f == null && sameClassGetter != null) {
                    f = ResolverUtils.getAnnotation(Filterable.class, sameClassGetter.getAnnotations());
                }
                // A field declared in a subclass shadows superclass declarations of the same name,
                // even when the subclass field intentionally omits @Filterable.
                shadowedOrDeclaredFields.add(fieldName);
                if (f != null) {
                    result.putIfAbsent(fieldName, f);
                }
            }
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge() || method.getParameterCount() != 0) {
                    continue;
                }
                String prop = extractGetterPropertyName(method);
                if (prop == null || shadowedOrDeclaredFields.contains(prop)) {
                    continue;
                }
                Filterable f = ResolverUtils.getAnnotation(Filterable.class, method.getAnnotations());
                if (f != null) {
                    result.putIfAbsent(prop, f);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private Method findDeclaredGetterForProperty(Class<?> declaringClass, String propertyName) {
        for (Method method : declaringClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge() || method.getParameterCount() != 0) {
                continue;
            }
            if (propertyName.equals(extractGetterPropertyName(method))) {
                return method;
            }
        }
        return null;
    }

    private List<String> extractTargetPaths(String dtoPropertyName, Filterable filterable) {
        String[] multi = filterable.relations();
        if (multi != null && multi.length > 0) {
            List<String> list = new ArrayList<>(multi.length);
            for (String candidate : multi) {
                if (candidate != null && !candidate.isBlank()) {
                    list.add(candidate.trim());
                }
            }
            if (!list.isEmpty()) {
                return list;
            }
        }
        if (filterable.relation() != null && !filterable.relation().isBlank()) {
            return List.of(filterable.relation().trim());
        }
        return List.of(dtoPropertyName);
    }

    private boolean analyzePropertyPath(
            Class<?> rootEntityType,
            String propertyPath,
            FilterOperation operation,
            String sourceLabel,
            Map<RecommendationKey, MutableRecommendation> acc
    ) {
        String[] segments = propertyPath.split("\\.");
        Class<?> currentTableOwner = rootEntityType;
        Class<?> currentFieldOwner = rootEntityType;
        Map<String, String> embeddedOverrides = new LinkedHashMap<>();
        StringBuilder currentRelativePath = new StringBuilder();
        StringBuilder embeddedSubPath = new StringBuilder();
        boolean insideEmbedded = false;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean isLast = (i == segments.length - 1);
            Field field = findFieldInHierarchy(currentFieldOwner, segment);
            if (field == null) {
                return false;
            }

            if (currentRelativePath.length() > 0) {
                currentRelativePath.append('.');
            }
            currentRelativePath.append(segment);

            if (insideEmbedded) {
                if (embeddedSubPath.length() > 0) {
                    embeddedSubPath.append('.');
                }
                embeddedSubPath.append(segment);
            }

            if (isSingularAssociation(field)) {
                OneToOne oneToOne = field.getAnnotation(OneToOne.class);
                if (oneToOne != null && !oneToOne.mappedBy().isBlank()) {
                    // Inverse (non-owning) @OneToOne: the FK column resides on field.getType(), not currentTableOwner.
                    Field inverseField = findFieldInHierarchy(field.getType(), oneToOne.mappedBy().trim());
                    if (inverseField != null) {
                        String inverseFkCol = resolveJoinColumnName(inverseField);
                        registerRecommendation(
                                field.getType(),
                                oneToOne.mappedBy().trim(),
                                inverseField,
                                inverseFkCol,
                                FilterableIndexKind.BTREE,
                                isLast ? sourceLabel : sourceLabel + " (inverse @OneToOne FK join)",
                                acc
                        );
                    }
                } else {
                    String fkColumn = embeddedOverrides.getOrDefault(
                            insideEmbedded ? embeddedSubPath.toString() : segment,
                            resolveJoinColumnName(field)
                    );
                    registerRecommendation(
                            currentTableOwner,
                            currentRelativePath.toString(),
                            field,
                            fkColumn,
                            FilterableIndexKind.BTREE,
                            isLast ? sourceLabel : sourceLabel + " (FK join)",
                            acc
                    );
                }
                if (isLast) {
                    return true;
                }
                currentTableOwner = field.getType();
                currentFieldOwner = field.getType();
                embeddedOverrides.clear();
                currentRelativePath.setLength(0);
                embeddedSubPath.setLength(0);
                insideEmbedded = false;
                continue;
            }

            if (isCollectionAssociation(field)) {
                Class<?> elementType = resolveCollectionElementType(field);
                if (elementType == null) {
                    return false;
                }
                OneToMany oneToMany = field.getAnnotation(OneToMany.class);
                if (oneToMany != null && !oneToMany.mappedBy().isBlank()) {
                    Field inverseField = findFieldInHierarchy(elementType, oneToMany.mappedBy().trim());
                    if (inverseField != null) {
                        String inverseFkCol = resolveJoinColumnName(inverseField);
                        registerRecommendation(
                                elementType,
                                oneToMany.mappedBy().trim(),
                                inverseField,
                                inverseFkCol,
                                FilterableIndexKind.BTREE,
                                sourceLabel + " (EXISTS correlation FK)",
                                acc
                        );
                    }
                }
                if (isLast) {
                    return true;
                }
                currentTableOwner = elementType;
                currentFieldOwner = elementType;
                embeddedOverrides.clear();
                currentRelativePath.setLength(0);
                embeddedSubPath.setLength(0);
                insideEmbedded = false;
                continue;
            }

            if (isEmbeddedField(field)) {
                String prefix = insideEmbedded ? embeddedSubPath.toString() + "." : "";
                for (var entry : collectAttributeOverrides(field).entrySet()) {
                    embeddedOverrides.putIfAbsent(prefix + entry.getKey(), entry.getValue());
                }
                if (isLast) {
                    if (field.isAnnotationPresent(EmbeddedId.class) && currentTableOwner != rootEntityType) {
                        return true;
                    }
                    Class<?> embCurrent = field.getType();
                    while (embCurrent != null && embCurrent != Object.class && embCurrent != Record.class) {
                        for (Field subField : embCurrent.getDeclaredFields()) {
                            if (Modifier.isStatic(subField.getModifiers())
                                    || Modifier.isTransient(subField.getModifiers())
                                    || subField.isSynthetic()
                                    || hasTransientAnnotation(subField)
                                    || isFormulaField(subField)) {
                                continue;
                            }
                            String subCol = embeddedOverrides.getOrDefault(
                                    prefix + subField.getName(),
                                    resolveColumnName(subField)
                            );
                            registerRecommendation(
                                    currentTableOwner,
                                    currentRelativePath.toString() + "." + subField.getName(),
                                    field.isAnnotationPresent(EmbeddedId.class) ? field : subField,
                                    subCol,
                                    FilterableIndexKind.BTREE,
                                    sourceLabel,
                                    acc
                            );
                            return true;
                        }
                        embCurrent = embCurrent.getSuperclass();
                    }
                    return false;
                }
                insideEmbedded = true;
                currentFieldOwner = field.getType();
                continue;
            }

            if (!isLast) {
                return false;
            }

            // Se o segmento final for o @Id da entidade associada (ex.: department.id),
            // a chave estrangeira no pai já foi registrada e o @Id na associada já é PK.
            if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(EmbeddedId.class)) {
                if (currentTableOwner != rootEntityType) {
                    return true;
                }
            }

            // Campos virtuais @Formula são expressões SQL avaliadas em tempo de consulta,
            // não colunas físicas passíveis de @Table(indexes = {@Index(...)}).
            if (isFormulaField(field)) {
                return true;
            }

            String overrideKey = insideEmbedded ? embeddedSubPath.toString() : segment;
            String columnName = embeddedOverrides.getOrDefault(overrideKey, resolveColumnName(field));
            FilterableIndexKind kind = classifyIndexKind(operation, field.getType());
            registerRecommendation(
                    currentTableOwner,
                    currentRelativePath.toString(),
                    field,
                    columnName,
                    kind,
                    sourceLabel,
                    acc
            );
            return true;
        }
        return false;
    }

    private FilterableIndexKind classifyIndexKind(FilterOperation operation, Class<?> fieldType) {
        if (operation == null) {
            return FilterableIndexKind.BTREE;
        }
        boolean isTextual = CharSequence.class.isAssignableFrom(fieldType);
        return switch (operation) {
            case LIKE, NOT_LIKE, ENDS_WITH -> isTextual ? FilterableIndexKind.GIN_TRGM : FilterableIndexKind.BTREE;
            case STARTS_WITH -> isTextual ? FilterableIndexKind.BTREE_PATTERN : FilterableIndexKind.BTREE;
            case BETWEEN, BETWEEN_EXCLUSIVE, NOT_BETWEEN, OUTSIDE_RANGE,
                 GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
                 ON_DATE, IN_LAST_DAYS, IN_NEXT_DAYS -> FilterableIndexKind.BTREE_RANGE;
            default -> FilterableIndexKind.BTREE;
        };
    }

    private void registerRecommendation(
            Class<?> tableOwnerEntity,
            String propertyPath,
            Field leafField,
            String columnName,
            FilterableIndexKind indexKind,
            String sourceLabel,
            Map<RecommendationKey, MutableRecommendation> acc
    ) {
        if (isImmutableOrSubselectEntity(tableOwnerEntity)) {
            return;
        }
        String tableName = resolveTableName(tableOwnerEntity);
        boolean isGin = (indexKind == FilterableIndexKind.GIN_TRGM);
        RecommendationKey key = new RecommendationKey(
                tableOwnerEntity.getName(),
                columnName.toLowerCase(Locale.ROOT),
                isGin ? "GIN_TRGM" : "BTREE_FAMILY"
        );

        MutableRecommendation existing = acc.get(key);
        if (existing != null) {
            existing.sourceFilterProperties.add(sourceLabel);
            if (existing.indexKind == FilterableIndexKind.BTREE
                    && (indexKind == FilterableIndexKind.BTREE_RANGE || indexKind == FilterableIndexKind.BTREE_PATTERN)) {
                existing.indexKind = indexKind;
            }
            return;
        }

        boolean covered = !isGin && isCoveredByJpaBtreeMetadata(tableOwnerEntity, leafField, columnName);
        String safeIndexBase = buildIndexName(tableName, columnName, isGin ? "trgm" : "idx");
        String jpaSnippet = isGin
                ? "// PostgreSQL GIN trigram index required via Flyway/Liquibase DDL: " + safeIndexBase
                : "@Index(name = \"" + safeIndexBase + "\", columnList = \"" + columnName + "\")";
        String postgresDdl = switch (indexKind) {
            case GIN_TRGM -> "CREATE INDEX IF NOT EXISTS " + safeIndexBase + " ON " + tableName
                    + " USING gin (LOWER(" + columnName + ") gin_trgm_ops);";
            case BTREE_PATTERN -> "CREATE INDEX IF NOT EXISTS " + safeIndexBase + " ON " + tableName
                    + " (LOWER(" + columnName + ") varchar_pattern_ops);";
            default -> "CREATE INDEX IF NOT EXISTS " + safeIndexBase + " ON " + tableName
                    + " (" + columnName + ");";
        };

        MutableRecommendation created = new MutableRecommendation(
                tableOwnerEntity,
                tableOwnerEntity.getSimpleName(),
                tableName,
                propertyPath,
                columnName,
                indexKind,
                covered,
                jpaSnippet,
                postgresDdl
        );
        created.sourceFilterProperties.add(sourceLabel);
        acc.put(key, created);
    }

    private boolean isCoveredByJpaBtreeMetadata(Class<?> entityType, Field field, String columnName) {
        if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(EmbeddedId.class)) {
            return true;
        }
        Column col = field.getAnnotation(Column.class);
        if (col != null && col.unique()) {
            return true;
        }
        JoinColumn joinCol = field.getAnnotation(JoinColumn.class);
        if (joinCol != null && joinCol.unique()) {
            return true;
        }

        String targetColLower = columnName.toLowerCase(Locale.ROOT);
        String targetFieldLower = field.getName().toLowerCase(Locale.ROOT);

        Class<?> current = entityType;
        while (current != null && current != Object.class) {
            Table table = current.getAnnotation(Table.class);
            if (table != null) {
                for (Index index : table.indexes()) {
                    String leading = extractLeadingColumn(index.columnList());
                    if (leading.equals(targetColLower)) {
                        return true;
                    }
                }
                for (UniqueConstraint uc : table.uniqueConstraints()) {
                    String[] cols = uc.columnNames();
                    if (cols != null && cols.length > 0 && cols[0] != null) {
                        String leading = normalizeColumnToken(cols[0]);
                        if (leading.equals(targetColLower)) {
                            return true;
                        }
                    }
                }
            }
            for (Field declared : current.getDeclaredFields()) {
                if (Modifier.isStatic(declared.getModifiers()) || declared.isSynthetic()) {
                    continue;
                }
                if (declared.isAnnotationPresent(Id.class)) {
                    String idCol = resolveColumnName(declared).toLowerCase(Locale.ROOT);
                    if (idCol.equals(targetColLower) || declared.getName().toLowerCase(Locale.ROOT).equals(targetFieldLower)) {
                        return true;
                    }
                }
                if (declared.isAnnotationPresent(EmbeddedId.class)) {
                    String leadingEmbeddedIdCol = resolveLeadingEmbeddedIdColumn(declared);
                    if (leadingEmbeddedIdCol != null && leadingEmbeddedIdCol.toLowerCase(Locale.ROOT).equals(targetColLower)) {
                        return true;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private String resolveLeadingEmbeddedIdColumn(Field embeddedIdField) {
        Map<String, String> overrides = collectAttributeOverrides(embeddedIdField);
        Class<?> embCurrent = embeddedIdField.getType();
        while (embCurrent != null && embCurrent != Object.class && embCurrent != Record.class) {
            for (Field subField : embCurrent.getDeclaredFields()) {
                if (Modifier.isStatic(subField.getModifiers())
                        || Modifier.isTransient(subField.getModifiers())
                        || subField.isSynthetic()
                        || hasTransientAnnotation(subField)
                        || isFormulaField(subField)) {
                    continue;
                }
                return overrides.getOrDefault(subField.getName(), resolveColumnName(subField));
            }
            embCurrent = embCurrent.getSuperclass();
        }
        return null;
    }

    private void analyzeDefaultSortColumns(
            Class<?> entityType,
            Map<RecommendationKey, MutableRecommendation> acc
    ) {
        if (isImmutableOrSubselectEntity(entityType)) {
            return;
        }
        Set<String> visitedFields = new LinkedHashSet<>();
        List<Field> defaultSortFields = new ArrayList<>();
        Class<?> current = entityType;
        while (current != null && current != Object.class && current != Record.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || Modifier.isTransient(field.getModifiers())
                        || field.isSynthetic()
                        || !visitedFields.add(field.getName())
                        || hasTransientAnnotation(field)
                        || isFormulaField(field)) {
                    continue;
                }
                if (field.isAnnotationPresent(DefaultSortColumn.class)) {
                    defaultSortFields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        defaultSortFields.sort((a, b) -> Integer.compare(
                a.getAnnotation(DefaultSortColumn.class).priority(),
                b.getAnnotation(DefaultSortColumn.class).priority()
        ));
        for (Field field : defaultSortFields) {
            analyzePropertyPath(
                    entityType,
                    field.getName(),
                    FilterOperation.EQUAL,
                    "entity:@DefaultSortColumn(" + field.getName() + ")",
                    acc
            );
        }
    }

    private void validateDeclaredTableIndexes(Class<?> entityType, Set<String> unresolvedPaths) {
        if (isImmutableOrSubselectEntity(entityType)) {
            return;
        }
        Set<String> physicalColumns = collectPhysicalColumnNames(entityType);
        Class<?> current = entityType;
        while (current != null && current != Object.class) {
            Table table = current.getAnnotation(Table.class);
            if (table != null) {
                for (Index index : table.indexes()) {
                    if (index.columnList() == null || index.columnList().isBlank()) {
                        continue;
                    }
                    for (String part : index.columnList().split(",")) {
                        String token = part.trim();
                        if (token.isEmpty()) {
                            continue;
                        }
                        String col = normalizeColumnToken(token.split("\\s+")[0]);
                        if (!col.isEmpty() && !physicalColumns.contains(col)) {
                            unresolvedPaths.add("@Table(indexes) -> " + entityType.getSimpleName() + "." + col);
                        }
                    }
                }
                for (UniqueConstraint uc : table.uniqueConstraints()) {
                    if (uc.columnNames() == null) {
                        continue;
                    }
                    for (String rawCol : uc.columnNames()) {
                        if (rawCol == null || rawCol.isBlank()) {
                            continue;
                        }
                        String col = normalizeColumnToken(rawCol);
                        if (!col.isEmpty() && !physicalColumns.contains(col)) {
                            unresolvedPaths.add("@Table(uniqueConstraints) -> " + entityType.getSimpleName() + "." + col);
                        }
                    }
                }
            }
            current = current.getSuperclass();
        }
    }

    private Set<String> collectPhysicalColumnNames(Class<?> entityType) {
        Set<String> columns = new LinkedHashSet<>();
        Class<?> current = entityType;
        while (current != null && current != Object.class && current != Record.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || Modifier.isTransient(field.getModifiers())
                        || field.isSynthetic()
                        || hasTransientAnnotation(field)
                        || isFormulaField(field)
                        || isCollectionAssociation(field)
                        || field.isAnnotationPresent(jakarta.persistence.ElementCollection.class)) {
                    continue;
                }
                if (isSingularAssociation(field)) {
                    OneToOne oneToOne = field.getAnnotation(OneToOne.class);
                    if (oneToOne != null && !oneToOne.mappedBy().isBlank()) {
                        continue;
                    }
                    jakarta.persistence.JoinColumns joinCols = field.getAnnotation(jakarta.persistence.JoinColumns.class);
                    if (joinCols != null && joinCols.value() != null && joinCols.value().length > 0) {
                        for (JoinColumn jc : joinCols.value()) {
                            if (jc != null && jc.name() != null && !jc.name().isBlank()) {
                                columns.add(normalizeColumnToken(jc.name()));
                            }
                        }
                    } else {
                        columns.add(normalizeColumnToken(resolveJoinColumnName(field)));
                    }
                    continue;
                }
                if (isEmbeddedField(field)) {
                    collectEmbeddedPhysicalColumns(field.getType(), "", collectAttributeOverrides(field), columns);
                    continue;
                }
                columns.add(normalizeColumnToken(resolveColumnName(field)));
            }
            current = current.getSuperclass();
        }
        return columns;
    }

    private void collectEmbeddedPhysicalColumns(
            Class<?> embeddableType,
            String prefix,
            Map<String, String> overrides,
            Set<String> columns
    ) {
        Class<?> current = embeddableType;
        while (current != null && current != Object.class && current != Record.class) {
            for (Field subField : current.getDeclaredFields()) {
                if (Modifier.isStatic(subField.getModifiers())
                        || Modifier.isTransient(subField.getModifiers())
                        || subField.isSynthetic()
                        || hasTransientAnnotation(subField)
                        || isFormulaField(subField)
                        || isCollectionAssociation(subField)
                        || subField.isAnnotationPresent(jakarta.persistence.ElementCollection.class)) {
                    continue;
                }
                String key = prefix.isEmpty() ? subField.getName() : prefix + "." + subField.getName();
                if (isEmbeddedField(subField)) {
                    Map<String, String> merged = new LinkedHashMap<>(overrides);
                    for (var entry : collectAttributeOverrides(subField).entrySet()) {
                        merged.putIfAbsent(key + "." + entry.getKey(), entry.getValue());
                    }
                    collectEmbeddedPhysicalColumns(subField.getType(), key, merged, columns);
                    continue;
                }
                if (isSingularAssociation(subField)) {
                    jakarta.persistence.JoinColumns joinCols = subField.getAnnotation(jakarta.persistence.JoinColumns.class);
                    if (joinCols != null && joinCols.value() != null && joinCols.value().length > 0) {
                        for (JoinColumn jc : joinCols.value()) {
                            if (jc != null && jc.name() != null && !jc.name().isBlank()) {
                                columns.add(normalizeColumnToken(jc.name()));
                            }
                        }
                    } else {
                        String col = overrides.getOrDefault(key, resolveJoinColumnName(subField));
                        columns.add(normalizeColumnToken(col));
                    }
                    continue;
                }
                String col = overrides.getOrDefault(key, resolveColumnName(subField));
                columns.add(normalizeColumnToken(col));
            }
            current = current.getSuperclass();
        }
    }

    private String normalizeColumnToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replace("\"", "")
                .replace("`", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean hasTransientAnnotation(Field field) {
        for (var ann : field.getAnnotations()) {
            if ("Transient".equals(ann.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isFormulaField(Field field) {
        for (var ann : field.getAnnotations()) {
            if ("Formula".equals(ann.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isImmutableOrSubselectEntity(Class<?> entityType) {
        Class<?> current = entityType;
        while (current != null && current != Object.class) {
            for (var ann : current.getAnnotations()) {
                String simpleName = ann.annotationType().getSimpleName();
                if ("Immutable".equals(simpleName) || "Subselect".equals(simpleName)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private String extractLeadingColumn(String columnList) {
        if (columnList == null || columnList.isBlank()) {
            return "";
        }
        String first = columnList.split(",")[0].trim();
        if (first.isEmpty()) {
            return "";
        }
        return normalizeColumnToken(first.split("\\s+")[0]);
    }

    private String buildIndexName(String tableName, String columnName, String suffix) {
        String raw = "idx_" + tableName + "_" + columnName + ("idx".equals(suffix) ? "" : "_" + suffix);
        String normalized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (normalized.length() <= 63) {
            return normalized;
        }
        return normalized.substring(0, 63);
    }

    private String resolveTableName(Class<?> entityType) {
        Class<?> current = entityType;
        while (current != null && current != Object.class) {
            Table table = current.getAnnotation(Table.class);
            if (table != null && table.name() != null && !table.name().isBlank()) {
                return table.name().trim();
            }
            current = current.getSuperclass();
        }
        return toSnakeCase(entityType.getSimpleName());
    }

    private String resolveColumnName(Field field) {
        Column col = field.getAnnotation(Column.class);
        if (col != null && col.name() != null && !col.name().isBlank()) {
            return col.name().trim();
        }
        return toSnakeCase(field.getName());
    }

    private String resolveJoinColumnName(Field field) {
        JoinColumn joinCol = field.getAnnotation(JoinColumn.class);
        if (joinCol != null && joinCol.name() != null && !joinCol.name().isBlank()) {
            return joinCol.name().trim();
        }
        jakarta.persistence.JoinColumns joinCols = field.getAnnotation(jakarta.persistence.JoinColumns.class);
        if (joinCols != null && joinCols.value() != null) {
            for (JoinColumn jc : joinCols.value()) {
                if (jc != null && jc.name() != null && !jc.name().isBlank()) {
                    return jc.name().trim();
                }
            }
        }
        return toSnakeCase(field.getName()) + "_id";
    }

    private Map<String, String> collectAttributeOverrides(Field embeddedField) {
        Map<String, String> overrides = new LinkedHashMap<>();
        AttributeOverride single = embeddedField.getAnnotation(AttributeOverride.class);
        if (single != null && !single.name().isBlank() && !single.column().name().isBlank()) {
            overrides.put(single.name().trim(), single.column().name().trim());
        }
        AttributeOverrides multi = embeddedField.getAnnotation(AttributeOverrides.class);
        if (multi != null) {
            for (AttributeOverride item : multi.value()) {
                if (item != null && !item.name().isBlank() && !item.column().name().isBlank()) {
                    overrides.put(item.name().trim(), item.column().name().trim());
                }
            }
        }
        return overrides;
    }

    private boolean isSingularAssociation(Field field) {
        return field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class);
    }

    private boolean isCollectionAssociation(Field field) {
        return field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class)
                || Collection.class.isAssignableFrom(field.getType());
    }

    private boolean isEmbeddedField(Field field) {
        return field.isAnnotationPresent(Embedded.class)
                || field.isAnnotationPresent(EmbeddedId.class)
                || field.getType().isAnnotationPresent(Embeddable.class);
    }

    private Class<?> resolveCollectionElementType(Field field) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> cls) {
                return cls;
            }
        }
        return null;
    }

    private Field findFieldInHierarchy(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private String extractGetterPropertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3 && !name.equals("getClass")) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return null;
    }

    private static String toSnakeCase(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() + 6);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && (Character.isLowerCase(input.charAt(i - 1))
                        || Character.isDigit(input.charAt(i - 1))
                        || (i + 1 < input.length() && Character.isLowerCase(input.charAt(i + 1))))) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private record RecommendationKey(String entityClassName, String columnNameLower, String family) {
    }

    private static final class MutableRecommendation {
        private final Class<?> tableOwnerEntity;
        private final String targetEntityName;
        private final String tableName;
        private final String propertyPath;
        private final String columnName;
        private FilterableIndexKind indexKind;
        private final boolean coveredByJpaMetadata;
        private final Set<String> sourceFilterProperties = new LinkedHashSet<>();
        private final String jpaIndexSnippet;
        private final String postgresDdl;

        private MutableRecommendation(
                Class<?> tableOwnerEntity,
                String targetEntityName,
                String tableName,
                String propertyPath,
                String columnName,
                FilterableIndexKind indexKind,
                boolean coveredByJpaMetadata,
                String jpaIndexSnippet,
                String postgresDdl
        ) {
            this.tableOwnerEntity = tableOwnerEntity;
            this.targetEntityName = targetEntityName;
            this.tableName = tableName;
            this.propertyPath = propertyPath;
            this.columnName = columnName;
            this.indexKind = indexKind;
            this.coveredByJpaMetadata = coveredByJpaMetadata;
            this.jpaIndexSnippet = jpaIndexSnippet;
            this.postgresDdl = postgresDdl;
        }

        private FilterableIndexRecommendation toImmutable() {
            return new FilterableIndexRecommendation(
                    targetEntityName,
                    tableName,
                    propertyPath,
                    columnName,
                    indexKind,
                    coveredByJpaMetadata,
                    List.copyOf(sourceFilterProperties),
                    jpaIndexSnippet,
                    postgresDdl
            );
        }
    }
}
