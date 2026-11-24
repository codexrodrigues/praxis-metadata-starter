package org.praxisplatform.uischema.stats.service.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.praxisplatform.intelligence.contracts.error.IntelligenceErrorCode;
import org.praxisplatform.intelligence.contracts.error.IntelligenceExecutionException;
import org.praxisplatform.intelligence.contracts.spi.TabularColumnDescriptor;
import org.praxisplatform.intelligence.contracts.spi.TabularFrameSource;
import org.praxisplatform.intelligence.contracts.spi.TabularLogicalType;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.id.JpaCriteriaPathSupport;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Implementacao segura e em streaming de {@link TabularFrameSource} apoiada por JPA Criteria Tuple
 * queries (sem carregar grafos ORM em heap) sobre a mesma {@link Specification} efetiva que combina
 * filtro funcional do cliente, {@code ResourceFilterAccessScope} e interceptors de governanca.
 *
 * <p>
 * Apenas campos declarados no {@code StatsFieldRegistry} efetivo sao projetados nas tuplas. Quando
 * {@code entityKeyColumn} e informado (caso de varreduras de anomalias individuais), a identidade da
 * entidade e emitida com tipo logico {@link TabularLogicalType#ENTITY_ID_TO_PSEUDONYMIZE} para que
 * o executor aplique pseudonimizacao efemera antes de qualquer transporte remoto.
 * </p>
 *
 * @param <E> tipo da entidade JPA
 * @param <ID> tipo da identidade da entidade
 */
public class JpaStatsTabularFrameSource<E, ID> implements TabularFrameSource {

    private static final int DEFAULT_BATCH_SIZE = 1_000;

    private final String datasetKey;
    private final EntityManager entityManager;
    private final BaseCrudRepository<E, ID> repository;
    private final Class<E> entityClass;
    private final Specification<E> effectiveSpecification;
    private final List<String> identityPaths;
    private final Function<E, ID> idExtractor;
    private final String entityKeyColumn;
    private final Map<String, StatsFieldDescriptor> projectedFields;
    private final int maxRows;
    private final int batchSize;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final List<TabularColumnDescriptor> columns;

    private volatile OptionalLong cachedRowCount;

    public JpaStatsTabularFrameSource(
            String datasetKey,
            EntityManager entityManager,
            BaseCrudRepository<E, ID> repository,
            Class<E> entityClass,
            Specification<E> effectiveSpecification,
            List<String> identityPaths,
            Function<E, ID> idExtractor,
            String entityKeyColumn,
            Map<String, StatsFieldDescriptor> projectedFields,
            int maxRows
    ) {
        this(
                datasetKey,
                entityManager,
                repository,
                entityClass,
                effectiveSpecification,
                identityPaths,
                idExtractor,
                entityKeyColumn,
                projectedFields,
                maxRows,
                DEFAULT_BATCH_SIZE,
                null
        );
    }

    public JpaStatsTabularFrameSource(
            String datasetKey,
            EntityManager entityManager,
            BaseCrudRepository<E, ID> repository,
            Class<E> entityClass,
            Specification<E> effectiveSpecification,
            List<String> identityPaths,
            Function<E, ID> idExtractor,
            String entityKeyColumn,
            Map<String, StatsFieldDescriptor> projectedFields,
            int maxRows,
            TransactionTemplate readOnlyTransactionTemplate
    ) {
        this(
                datasetKey,
                entityManager,
                repository,
                entityClass,
                effectiveSpecification,
                identityPaths,
                idExtractor,
                entityKeyColumn,
                projectedFields,
                maxRows,
                DEFAULT_BATCH_SIZE,
                readOnlyTransactionTemplate
        );
    }

    public JpaStatsTabularFrameSource(
            String datasetKey,
            EntityManager entityManager,
            BaseCrudRepository<E, ID> repository,
            Class<E> entityClass,
            Specification<E> effectiveSpecification,
            List<String> identityPaths,
            Function<E, ID> idExtractor,
            String entityKeyColumn,
            Map<String, StatsFieldDescriptor> projectedFields,
            int maxRows,
            int batchSize,
            TransactionTemplate readOnlyTransactionTemplate
    ) {
        if (datasetKey == null || datasetKey.isBlank()) {
            throw new IllegalArgumentException("datasetKey must not be blank");
        }
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        Objects.requireNonNull(effectiveSpecification, "effectiveSpecification must not be null");
        if (identityPaths == null || identityPaths.isEmpty()) {
            throw new IllegalArgumentException("identityPaths must not be empty");
        }
        Objects.requireNonNull(projectedFields, "projectedFields must not be null");
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (entityManager == null && repository == null) {
            throw new IllegalArgumentException("Either entityManager or repository must be provided");
        }

        this.datasetKey = datasetKey.trim();
        this.entityManager = entityManager;
        this.repository = repository;
        this.entityClass = entityClass;
        this.effectiveSpecification = effectiveSpecification;
        this.identityPaths = List.copyOf(identityPaths);
        this.idExtractor = idExtractor;
        this.entityKeyColumn = (entityKeyColumn == null || entityKeyColumn.isBlank())
                ? null
                : entityKeyColumn.trim();
        this.projectedFields = Collections.unmodifiableMap(new LinkedHashMap<>(projectedFields));
        this.maxRows = maxRows;
        this.batchSize = batchSize;
        this.readOnlyTransactionTemplate = readOnlyTransactionTemplate;
        this.columns = buildColumns();
    }

    @Override
    public String datasetKey() {
        return datasetKey;
    }

    @Override
    public List<TabularColumnDescriptor> columns() {
        return columns;
    }

    @Override
    public OptionalLong estimatedRowCount() {
        OptionalLong current = cachedRowCount;
        if (current != null) {
            return current;
        }
        OptionalLong computed = executeInReadOnlyTransaction(this::computeRowCount);
        cachedRowCount = computed;
        return computed;
    }

    private OptionalLong computeRowCount() {
        if (repository != null) {
            return OptionalLong.of(repository.count(effectiveSpecification));
        }
        if (entityManager != null) {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
            Root<E> root = countQuery.from(entityClass);
            Predicate predicate = effectiveSpecification.toPredicate(root, countQuery, cb);
            if (predicate != null) {
                countQuery.where(predicate);
            }
            countQuery.select(countQuery.isDistinct() ? cb.countDistinct(root) : cb.count(root));
            return OptionalLong.of(entityManager.createQuery(countQuery).getSingleResult());
        }
        return OptionalLong.empty();
    }

    @Override
    public void streamRows(Consumer<Map<String, Object>> rowConsumer) {
        Objects.requireNonNull(rowConsumer, "rowConsumer must not be null");
        executeInReadOnlyTransactionWithoutResult(() -> {
            OptionalLong estimated = estimatedRowCount();
            if (estimated.isPresent() && estimated.getAsLong() > maxRows) {
                throw new IntelligenceExecutionException(
                        IntelligenceErrorCode.PAYLOAD_TOO_LARGE,
                        "Filtered dataset size (" + estimated.getAsLong()
                                + ") exceeds maximum allowed analytical rows (" + maxRows + ")"
                );
            }

            if (entityManager != null) {
                streamWithCriteriaTuples(rowConsumer);
            } else {
                streamWithRepositoryPages(rowConsumer);
            }
        });
    }

    private <T> T executeInReadOnlyTransaction(java.util.function.Supplier<T> supplier) {
        if (readOnlyTransactionTemplate != null) {
            return readOnlyTransactionTemplate.execute(status -> supplier.get());
        }
        return supplier.get();
    }

    private void executeInReadOnlyTransactionWithoutResult(Runnable action) {
        if (readOnlyTransactionTemplate != null) {
            readOnlyTransactionTemplate.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    private void streamWithCriteriaTuples(Consumer<Map<String, Object>> rowConsumer) {
        List<Map.Entry<String, StatsFieldDescriptor>> fieldEntries = List.copyOf(projectedFields.entrySet());
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        long emitted = 0L;
        Object[] lastIdentityValues = null;

        while (true) {
            CriteriaQuery<Tuple> cq = cb.createTupleQuery();
            Root<E> root = cq.from(entityClass);
            Predicate basePredicate = effectiveSpecification.toPredicate(root, cq, cb);

            List<Path<?>> scalarIdPaths = resolveScalarIdentityPaths(root);
            List<Selection<?>> selections = new ArrayList<>(scalarIdPaths.size() + fieldEntries.size());
            List<Order> orders = new ArrayList<>(scalarIdPaths.size());
            for (Path<?> scalarIdPath : scalarIdPaths) {
                selections.add(scalarIdPath);
                orders.add(cb.asc(scalarIdPath));
            }
            for (Map.Entry<String, StatsFieldDescriptor> entry : fieldEntries) {
                selections.add(JpaCriteriaPathSupport.resolvePath(root, entry.getValue().keyPropertyPath(), true));
            }

            if (lastIdentityValues != null) {
                Predicate keysetPredicate = buildKeysetAfterPredicate(cb, scalarIdPaths, lastIdentityValues);
                Predicate combined = basePredicate != null ? cb.and(basePredicate, keysetPredicate) : keysetPredicate;
                cq.where(combined);
            } else if (basePredicate != null) {
                cq.where(basePredicate);
            }

            cq.multiselect(selections);
            if (!orders.isEmpty()) {
                cq.orderBy(orders);
            }

            List<Tuple> batch = entityManager.createQuery(cq)
                    .setMaxResults(batchSize)
                    .getResultList();

            if (batch.isEmpty()) {
                break;
            }

            int fieldOffset = scalarIdPaths.size();
            for (Tuple tuple : batch) {
                emitted++;
                if (emitted > maxRows) {
                    throw new IntelligenceExecutionException(
                            IntelligenceErrorCode.PAYLOAD_TOO_LARGE,
                            "Streamed dataset exceeded maximum allowed analytical rows (" + maxRows + ")"
                    );
                }

                Map<String, Object> row = new LinkedHashMap<>(columns.size());
                if (entityKeyColumn != null) {
                    row.put(entityKeyColumn, extractTupleIdentityKey(tuple, fieldOffset));
                }
                for (int i = 0; i < fieldEntries.size(); i++) {
                    String colName = fieldEntries.get(i).getKey();
                    Object rawVal = tuple.get(fieldOffset + i);
                    row.put(colName, normalizeScalarValue(rawVal));
                }
                rowConsumer.accept(Collections.unmodifiableMap(row));
            }

            if (batch.size() < batchSize) {
                break;
            }
            Tuple lastTuple = batch.getLast();
            lastIdentityValues = new Object[fieldOffset];
            for (int i = 0; i < fieldOffset; i++) {
                lastIdentityValues[i] = lastTuple.get(i);
            }
        }
    }

    private List<Path<?>> resolveScalarIdentityPaths(Root<E> root) {
        List<Path<?>> scalarPaths = new ArrayList<>(identityPaths.size());
        for (String idPath : identityPaths) {
            Path<?> resolvedIdPath = JpaCriteriaPathSupport.resolvePath(root, idPath, true);
            Class<?> javaType = resolvedIdPath.getJavaType();
            if (CompositeIdSupport.isCompositeIdType(javaType)) {
                List<CompositeIdSupport.ComponentDescriptor> components = CompositeIdSupport.descriptors(javaType);
                if (!components.isEmpty()) {
                    for (CompositeIdSupport.ComponentDescriptor component : components) {
                        scalarPaths.add(resolvedIdPath.get(component.name()));
                    }
                    continue;
                }
            }
            scalarPaths.add(resolvedIdPath);
        }
        return scalarPaths;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate buildKeysetAfterPredicate(
            CriteriaBuilder cb,
            List<Path<?>> idPaths,
            Object[] lastIdentityValues
    ) {
        List<Predicate> alternatives = new ArrayList<>(idPaths.size());
        List<Predicate> equalPrefix = new ArrayList<>(idPaths.size());
        for (int i = 0; i < idPaths.size(); i++) {
            Path rawPath = idPaths.get(i);
            Object value = lastIdentityValues[i];
            if (value == null) {
                throw new IllegalStateException("Keyset pagination requires non-null identity component at index " + i);
            }
            if (!(value instanceof Comparable comparable)) {
                throw new IllegalStateException(
                        "Keyset pagination requires Comparable identity component: " + value.getClass().getName()
                );
            }
            Predicate after = cb.greaterThan((Expression) rawPath, comparable);
            List<Predicate> term = new ArrayList<>(equalPrefix.size() + 1);
            term.addAll(equalPrefix);
            term.add(after);
            alternatives.add(cb.and(term.toArray(Predicate[]::new)));
            equalPrefix.add(cb.equal(rawPath, value));
        }
        return cb.or(alternatives.toArray(Predicate[]::new));
    }

    private void streamWithRepositoryPages(Consumer<Map<String, Object>> rowConsumer) {
        List<Map.Entry<String, StatsFieldDescriptor>> fieldEntries = List.copyOf(projectedFields.entrySet());
        Sort deterministicSort = JpaCriteriaPathSupport.expandCompositeSort(
                entityClass,
                Sort.by(identityPaths.stream().map(Sort.Order::asc).toList())
        );

        long emitted = 0L;
        int pageIndex = 0;

        while (true) {
            Page<E> page = repository.findAll(
                    effectiveSpecification,
                    PageRequest.of(pageIndex, batchSize, deterministicSort)
            );
            List<E> content = page.getContent();
            if (content.isEmpty()) {
                break;
            }

            for (E entity : content) {
                emitted++;
                if (emitted > maxRows) {
                    throw new IntelligenceExecutionException(
                            IntelligenceErrorCode.PAYLOAD_TOO_LARGE,
                            "Streamed dataset exceeded maximum allowed analytical rows (" + maxRows + ")"
                    );
                }

                Map<String, Object> row = new LinkedHashMap<>(columns.size());
                if (entityKeyColumn != null) {
                    row.put(entityKeyColumn, extractEntityIdentityKey(entity));
                }
                for (Map.Entry<String, StatsFieldDescriptor> entry : fieldEntries) {
                    Object rawVal = readPropertyPath(entity, entry.getValue().keyPropertyPath());
                    row.put(entry.getKey(), normalizeScalarValue(rawVal));
                }
                rowConsumer.accept(Collections.unmodifiableMap(row));
            }

            if (!page.hasNext()) {
                break;
            }
            pageIndex++;
        }
    }

    private String extractTupleIdentityKey(Tuple tuple, int scalarIdentityCount) {
        List<Object> parts = new ArrayList<>(scalarIdentityCount);
        for (int i = 0; i < scalarIdentityCount; i++) {
            parts.add(tuple.get(i));
        }
        return CompositeIdSupport.encodeComponents(parts);
    }

    private String extractEntityIdentityKey(E entity) {
        if (idExtractor != null) {
            ID extracted = idExtractor.apply(entity);
            return extracted == null ? null : CompositeIdSupport.stringify(extracted);
        }
        List<Object> parts = new ArrayList<>(identityPaths.size());
        for (String path : identityPaths) {
            parts.add(readPropertyPath(entity, path));
        }
        return CompositeIdSupport.encodeComponents(parts);
    }

    private List<TabularColumnDescriptor> buildColumns() {
        List<TabularColumnDescriptor> result = new ArrayList<>(
                (entityKeyColumn != null ? 1 : 0) + projectedFields.size()
        );
        if (entityKeyColumn != null) {
            result.add(new TabularColumnDescriptor(
                    entityKeyColumn,
                    TabularLogicalType.ENTITY_ID_TO_PSEUDONYMIZE
            ));
        }
        for (Map.Entry<String, StatsFieldDescriptor> entry : projectedFields.entrySet()) {
            TabularLogicalType logicalType = inferLogicalType(entityClass, entry.getValue().keyPropertyPath());
            result.add(new TabularColumnDescriptor(entry.getKey(), logicalType));
        }
        return List.copyOf(result);
    }

    private static TabularLogicalType inferLogicalType(Class<?> rootClass, String propertyPath) {
        Class<?> javaType = resolveJavaType(rootClass, propertyPath);
        if (javaType == null) {
            return TabularLogicalType.STRING;
        }
        if (javaType == BigDecimal.class) {
            return TabularLogicalType.DECIMAL;
        }
        if (javaType == Double.class || javaType == double.class
                || javaType == Float.class || javaType == float.class) {
            return TabularLogicalType.DOUBLE;
        }
        if (javaType == Long.class || javaType == long.class
                || javaType == Integer.class || javaType == int.class
                || javaType == Short.class || javaType == short.class
                || javaType == Byte.class || javaType == byte.class
                || javaType == BigInteger.class) {
            return TabularLogicalType.INTEGER;
        }
        if (javaType == Boolean.class || javaType == boolean.class) {
            return TabularLogicalType.BOOLEAN;
        }
        if (javaType == LocalDate.class || javaType == Date.class) {
            return TabularLogicalType.DATE;
        }
        if (javaType == Instant.class
                || javaType == OffsetDateTime.class
                || javaType == ZonedDateTime.class
                || javaType == LocalDateTime.class
                || javaType == Timestamp.class
                || javaType == java.util.Date.class) {
            return TabularLogicalType.TIMESTAMP;
        }
        return TabularLogicalType.STRING;
    }

    private static Class<?> resolveJavaType(Class<?> rootClass, String propertyPath) {
        if (rootClass == null || propertyPath == null || propertyPath.isBlank()) {
            return null;
        }
        Class<?> current = rootClass;
        for (String segment : propertyPath.split("\\.")) {
            Class<?> next = findMemberType(current, segment);
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static Class<?> findMemberType(Class<?> owner, String name) {
        for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name).getType();
            } catch (NoSuchFieldException ignored) {
                // Fall back to getter method or superclass
            }
        }
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String getter : List.of("get" + capitalized, "is" + capitalized, name)) {
            try {
                Method method = owner.getMethod(getter);
                if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
                    return method.getReturnType();
                }
            } catch (NoSuchMethodException ignored) {
                // Try next accessor name
            }
        }
        return null;
    }

    private static Object readPropertyPath(Object root, String propertyPath) {
        Object current = root;
        for (String segment : propertyPath.split("\\.")) {
            if (current == null) {
                return null;
            }
            if (current instanceof org.hibernate.proxy.HibernateProxy) {
                current = org.hibernate.Hibernate.unproxy(current);
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
                continue;
            }
            Class<?> type = current.getClass();
            String capitalized = Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
            Method accessor = null;
            for (String candidate : List.of("get" + capitalized, "is" + capitalized, segment)) {
                try {
                    accessor = type.getMethod(candidate);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Try next candidate
                }
            }
            if (accessor != null) {
                try {
                    accessor.setAccessible(true);
                    current = accessor.invoke(current);
                    continue;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Cannot invoke property accessor for " + propertyPath, ex);
                }
            }
            Field field = findField(type, segment);
            if (field == null) {
                throw new IllegalArgumentException("Unknown resource property: " + propertyPath);
            }
            try {
                field.setAccessible(true);
                current = field.get(current);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Cannot read resource property: " + propertyPath, ex);
            }
        }
        return current;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Walk superclass chain
            }
        }
        return null;
    }

    private static Object normalizeScalarValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        if (value instanceof Timestamp sqlTimestamp) {
            return sqlTimestamp.toInstant().toString();
        }
        if (value instanceof java.util.Date utilDate) {
            return utilDate.toInstant().toString();
        }
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return CompositeIdSupport.stringify(value);
    }
}
