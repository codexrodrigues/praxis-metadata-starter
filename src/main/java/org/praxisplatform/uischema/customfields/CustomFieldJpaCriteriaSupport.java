package org.praxisplatform.uischema.customfields;

import jakarta.persistence.criteria.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.hibernate.query.criteria.JpaExpression;
import org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;

/** Builds only declared, filterable JSONB predicates; keys remain SQL literal parameters. */
public final class CustomFieldJpaCriteriaSupport {
    private CustomFieldJpaCriteriaSupport() {}
    public static List<Predicate> predicates(Map<String, Object> values, List<CustomFieldDescriptor> definitions,
            Root<?> root, CriteriaBuilder cb) {
        if (values.isEmpty()) return List.of();
        String property = CustomFieldSupport.property(root.getJavaType())
                .orElseThrow(() -> new InvalidFilterPayloadException("Entity has no @CustomFields container")).name();
        Map<String, CustomFieldDescriptor> allowed = new LinkedHashMap<>();
        for (CustomFieldDescriptor descriptor : definitions) {
            if (allowed.putIfAbsent(descriptor.key(), descriptor) != null) throw new IllegalStateException("Duplicate custom field definition");
        }
        List<Predicate> predicates = new ArrayList<>();
        values.forEach((key, value) -> {
            CustomFieldDescriptor d = allowed.get(key);
            if (d == null || !d.filterable()) throw new InvalidFilterPayloadException("Custom field is not filterable: " + key);
            if (value == null) return;
            Expression<String> text = cb.function("jsonb_extract_path_text", String.class, root.get(property), cb.literal(key));
            FilterOperation operation = d.filterOperation();
            if (operation == FilterOperation.IS_NULL) {
                if (!(value instanceof Boolean)) throw new InvalidFilterPayloadException("IS_NULL requires a boolean");
                if (Boolean.TRUE.equals(value)) predicates.add(cb.isNull(text));
                return;
            }
            if (operation == FilterOperation.IN) {
                if (!(value instanceof Collection<?> collection)) throw new InvalidFilterPayloadException("IN requires a collection");
                List<Object> normalized = new ArrayList<>();
                for (Object item : collection) normalized.add(normalize(d, item));
                predicates.add(typed(text, d).in(normalized));
                return;
            }
            Object normalized = normalize(d, value);
            Expression<?> expression = typed(text, d);
            switch (operation) {
                case EQUAL -> predicates.add(cb.equal(expression, normalized));
                case LIKE -> {
                    if (!(normalized instanceof String string) || (d.dataType() != CustomFieldDataType.STRING && d.dataType() != CustomFieldDataType.ENUM))
                        throw new InvalidFilterPayloadException("LIKE requires a string custom field");
                    predicates.add(cb.like(cb.lower(text), "%" + string.toLowerCase(Locale.ROOT) + "%"));
                }
                case GREATER_OR_EQUAL -> predicates.add(compare(cb, expression, normalized, true));
                case LESS_OR_EQUAL -> predicates.add(compare(cb, expression, normalized, false));
                default -> throw new InvalidFilterPayloadException("Unsupported custom field filter operation: " + operation);
            }
        });
        return predicates;
    }
    private static Object normalize(CustomFieldDescriptor d, Object value) {
        if (value == null || !CustomFieldValidator.matchesType(d, value)) throw new InvalidFilterPayloadException("Invalid custom field filter value: " + d.key());
        return switch (d.dataType()) {
            case INTEGER, DECIMAL -> new BigDecimal(value.toString());
            case DATE -> value instanceof LocalDate ? value : LocalDate.parse(value.toString());
            case DATE_TIME -> {
                if (value instanceof OffsetDateTime time) yield time;
                if (value instanceof Instant instant) yield instant.atOffset(ZoneOffset.UTC);
                if (value instanceof LocalDateTime time) yield time.atOffset(ZoneOffset.UTC);
                try { yield OffsetDateTime.parse(value.toString()); }
                catch (java.time.format.DateTimeParseException e) { yield LocalDateTime.parse(value.toString()).atOffset(ZoneOffset.UTC); }
            }
            case ARRAY -> throw new InvalidFilterPayloadException("ARRAY custom fields do not support scalar filtering");
            default -> value;
        };
    }
    private static Expression<?> typed(Expression<String> text, CustomFieldDescriptor d) {
        Class<?> type = switch (d.dataType()) {
            case INTEGER, DECIMAL -> BigDecimal.class;
            case BOOLEAN -> Boolean.class;
            case DATE -> LocalDate.class;
            case DATE_TIME -> OffsetDateTime.class;
            default -> String.class;
        };
        // Hibernate 6.6 Expression.as no longer emits SQL CAST; JpaExpression.cast does.
        return type == String.class ? text : ((JpaExpression<?>) text).cast(type);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate compare(CriteriaBuilder cb, Expression<?> expression, Object value, boolean minimum) {
        if (!(value instanceof Comparable) || value instanceof Boolean) throw new InvalidFilterPayloadException("Comparison requires an ordered scalar");
        return minimum ? cb.greaterThanOrEqualTo((Expression) expression, (Comparable) value)
                : cb.lessThanOrEqualTo((Expression) expression, (Comparable) value);
    }
}
