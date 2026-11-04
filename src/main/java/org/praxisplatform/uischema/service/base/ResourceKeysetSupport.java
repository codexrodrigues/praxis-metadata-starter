package org.praxisplatform.uischema.service.base;

import jakarta.persistence.criteria.*;
import org.praxisplatform.uischema.id.JpaCriteriaPathSupport;
import org.praxisplatform.uischema.util.CursorEncoder;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** SQL keyset predicates and reversible, escaped cursor tuples. Nulls are explicitly ordered. */
final class ResourceKeysetSupport {
    private ResourceKeysetSupport() { }

    static Object read(Object bean, String path) {
        Object value = bean;
        for (String part : path.split("\\.")) {
            if (value == null) return null;
            if (value instanceof org.hibernate.proxy.HibernateProxy) value = org.hibernate.Hibernate.unproxy(value);
            if (value instanceof Map<?, ?> map) { value = map.get(part); continue; }
            Class<?> type = value.getClass();
            try {
                Method method = null;
                for (String name : List.of("get" + Character.toUpperCase(part.charAt(0)) + part.substring(1),
                        "is" + Character.toUpperCase(part.charAt(0)) + part.substring(1), part)) {
                    try { method = type.getMethod(name); break; } catch (NoSuchMethodException ignored) { }
                }
                if (method != null) { method.setAccessible(true); value = method.invoke(value); continue; }
                Field field = null;
                for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                    try { field = c.getDeclaredField(part); break; } catch (NoSuchFieldException ignored) { }
                }
                if (field == null) throw new IllegalArgumentException("Unknown resource property: " + path);
                field.setAccessible(true);
                value = field.get(value);
            } catch (ReflectiveOperationException ex) { throw new IllegalArgumentException("Cannot read resource property: " + path, ex); }
        }
        return value;
    }

    static String encode(Object entity, Sort sort) {
        return CursorEncoder.BASE64_URL.encode(sortSignature(sort) + ":" + sort.stream().map(order -> {
            Object value = read(entity, order.getProperty());
            return value == null ? "~" : escape(value instanceof Enum<?> enumeration ? enumeration.name() : value.toString());
        }).collect(java.util.stream.Collectors.joining(":")));
    }

    static List<String> decode(String cursor, Sort sort) {
        try {
            String[] parts = CursorEncoder.BASE64_URL.decode(cursor).split(":", -1);
            if (parts.length != sort.stream().count() + 1 || !parts[0].equals(sortSignature(sort)))
                throw new InvalidFilterPayloadException("Cursor does not match resource sort");
            List<String> values = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) values.add(parts[i].equals("~") ? null : unescape(parts[i]));
            return values;
        } catch (InvalidFilterPayloadException ex) { throw ex; }
        catch (RuntimeException ex) { throw new InvalidFilterPayloadException("Invalid resource cursor"); }
    }

    private static String sortSignature(Sort sort) {
        String definition = sort.stream().map(order -> order.getProperty() + ":" + order.getDirection()
                + ":" + order.getNullHandling() + ":" + order.isIgnoreCase())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(definition.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 12);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String escape(String value) {
        return value.isEmpty() ? "%00" : value.replace("%", "%25").replace(":", "%3A").replace("~", "%7E");
    }

    private static String unescape(String value) {
        if (value.equals("%00")) return "";
        // Validate before decoding, so malformed sequences cannot silently change a boundary.
        if (value.replace("%25", "").replace("%3A", "").replace("%7E", "").contains("%"))
            throw new IllegalArgumentException("Invalid cursor escape");
        return value.replace("%7E", "~").replace("%3A", ":").replace("%25", "%");
    }

    static boolean nullsFirst(Sort.Order order) {
        return order.getNullHandling() == Sort.NullHandling.NULLS_FIRST;
    }

    static Sort reverse(Sort sort) {
        return Sort.by(sort.stream().map(order -> order.with(order.isAscending() ? Sort.Direction.DESC : Sort.Direction.ASC)
                .with(nullsFirst(order) ? Sort.NullHandling.NULLS_LAST : Sort.NullHandling.NULLS_FIRST)).toList());
    }

    static List<jakarta.persistence.criteria.Order> orders(CriteriaBuilder cb, Root<?> root, Sort sort) {
        List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            validateScalarSort(root.getModel(), order.getProperty());
            Path<?> path = JpaCriteriaPathSupport.resolvePath(root, order.getProperty(), true);
            if (order.isIgnoreCase() && path.getJavaType() != String.class)
                throw new IllegalArgumentException("Ignore-case sort requires a string property");
            orders.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(path), nullsFirst(order) ? 0 : 1)
                    .otherwise(nullsFirst(order) ? 1 : 0)));
            Expression<?> expression = order.isIgnoreCase() ? cb.lower(path.as(String.class)) : path;
            orders.add(order.isAscending() ? cb.asc(expression) : cb.desc(expression));
        }
        return orders;
    }

    private static void validateScalarSort(jakarta.persistence.metamodel.ManagedType<?> owner, String path) {
        String[] components = path.split("\\.");
        for (int index = 0; index < components.length; index++) {
            var attribute = owner.getAttribute(components[index]);
            if (attribute.isCollection()) throw new IllegalArgumentException("Collection-valued sort requires a resource projection: " + path);
            if (index < components.length - 1) {
                if (!(attribute instanceof jakarta.persistence.metamodel.SingularAttribute<?, ?> singular)
                        || !(singular.getType() instanceof jakarta.persistence.metamodel.ManagedType<?> nested))
                    throw new IllegalArgumentException("Invalid scalar sort path: " + path);
                owner = nested;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <E> Specification<E> boundary(Sort sort, List<String> values, boolean after) {
        return (root, query, cb) -> {
            List<Predicate> alternatives = new ArrayList<>();
            List<Predicate> equalPrefix = new ArrayList<>();
            int index = 0;
            for (Sort.Order order : sort) {
                Path<?> path = JpaCriteriaPathSupport.resolvePath(root, order.getProperty(), true);
                String raw = values.get(index++);
                Object value = raw == null ? null : org.praxisplatform.uischema.id.CompositeIdSupport.coerceScalar(raw, path.getJavaType());
                Expression expression = path;
                if (order.isIgnoreCase()) {
                    if (path.getJavaType() != String.class) throw new IllegalArgumentException("Ignore-case sort requires a string property");
                    expression = cb.lower(path.as(String.class));
                    if (value != null) value = value.toString().toLowerCase(Locale.ROOT);
                }
                Predicate comparison;
                if (value == null) {
                    comparison = after == nullsFirst(order) ? cb.isNotNull(path) : cb.disjunction();
                } else {
                    if (!(value instanceof Comparable comparable)) throw new IllegalArgumentException("Cursor sort must be comparable");
                    comparison = after == order.isAscending() ? cb.greaterThan(expression, comparable) : cb.lessThan(expression, comparable);
                    if (after != nullsFirst(order)) comparison = cb.or(comparison, cb.isNull(path));
                }
                List<Predicate> term = new ArrayList<>(equalPrefix);
                term.add(comparison);
                alternatives.add(cb.and(term.toArray(Predicate[]::new)));
                equalPrefix.add(value == null ? cb.isNull(path) : cb.equal(expression, value));
            }
            return cb.or(alternatives.toArray(Predicate[]::new));
        };
    }
}
