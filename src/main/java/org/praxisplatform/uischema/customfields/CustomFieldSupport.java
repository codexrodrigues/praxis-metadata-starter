package org.praxisplatform.uischema.customfields;

import java.lang.reflect.*;
import java.util.*;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** Cached introspection shared by all custom-field consumers, including record DTOs. */
public final class CustomFieldSupport {
    private CustomFieldSupport() {}
    public record Property(String name, String resourceKey, Field field, Method accessor) {
        public Map<String, Object> read(Object bean) {
            try {
                Object value;
                if (accessor != null) { accessor.trySetAccessible(); value = accessor.invoke(bean); }
                else { field.trySetAccessible(); value = field.get(bean); }
                if (value == null) return Map.of();
                if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("@CustomFields requires Map<String, Object>");
                Map<String, Object> result = new LinkedHashMap<>();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Custom field keys must be strings");
                    result.put(key, entry.getValue());
                }
                return result;
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot read customFields", e); }
        }
    }
    private static final ClassValue<Optional<Property>> PROPERTIES = new ClassValue<>() {
        @Override protected Optional<Property> computeValue(Class<?> type) {
            Property found = null;
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    CustomFields annotation = field.getAnnotation(CustomFields.class);
                    if (annotation != null) found = merge(found, checked(field.getName(), field.getType(), annotation, field, null));
                }
                for (Method method : current.getDeclaredMethods()) {
                    CustomFields annotation = method.getAnnotation(CustomFields.class);
                    if (annotation != null) {
                        if (method.getParameterCount() != 0) throw new IllegalArgumentException("@CustomFields method must be a getter");
                        String name = method.getName().startsWith("get") && method.getName().length() > 3
                                ? java.beans.Introspector.decapitalize(method.getName().substring(3)) : method.getName();
                        found = merge(found, checked(name, method.getReturnType(), annotation, null, method));
                    }
                }
            }
            return Optional.ofNullable(found);
        }
    };
    private static Property checked(String name, Class<?> type, CustomFields annotation, Field field, Method method) {
        if (!"customFields".equals(name) || !Map.class.isAssignableFrom(type))
            throw new IllegalArgumentException("@CustomFields requires a Map property named customFields");
        return new Property(name, annotation.resourceKey().trim(), field, method);
    }
    private static Property merge(Property current, Property candidate) {
        if (current != null && (!current.name().equals(candidate.name()) || !current.resourceKey().equals(candidate.resourceKey())))
            throw new IllegalArgumentException("Conflicting @CustomFields declarations");
        if (current == null) return candidate;
        return new Property(current.name(), current.resourceKey(),
                current.field() != null ? current.field() : candidate.field(),
                current.accessor() != null ? current.accessor() : candidate.accessor());
    }
    public static Optional<Property> property(Class<?> type) { return PROPERTIES.get(type); }
    public static Map<String, Object> read(Object value) {
        return value == null ? Map.of() : property(value.getClass()).map(p -> p.read(value)).orElse(Map.of());
    }
    /**
     * Captures server-owned values, detaching nested JSON maps, collections and arrays before
     * mapping. Scalar leaves are retained; this does not clone arbitrary mutable host POJOs.
     */
    public static Map<String, Object> snapshotReadOnlyValues(Object entity, Set<String> keys) {
        if (keys.isEmpty()) return Map.of();
        requireEntityProperty(entity);
        Map<String, Object> current = read(entity);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : keys) if (current.containsKey(key)) snapshot.put(key, copyValue(current.get(key)));
        return snapshot;
    }
    /** Restores both values and absence without reverting any writable field or domain-hook decision. */
    public static void restoreReadOnlyValues(Object entity, Set<String> keys, Map<String, Object> snapshot) {
        if (keys.isEmpty()) return;
        Property property = requireEntityProperty(entity);
        Map<String, Object> current = property.read(entity);
        boolean changed = keys.stream().anyMatch(key -> current.containsKey(key) != snapshot.containsKey(key)
                || !sameValue(current.get(key), snapshot.get(key)));
        if (!changed) return;
        Map<String, Object> restored = new LinkedHashMap<>(current);
        for (String key : keys) {
            if (snapshot.containsKey(key)) restored.put(key, snapshot.get(key));
            else restored.remove(key);
        }
        writeValues(entity, property, restored);
        Map<String, Object> written = property.read(entity);
        if (!sameValue(restored, written)) throw new IllegalStateException("Cannot restore read-only customFields on entity");
    }
    private static Property requireEntityProperty(Object entity) {
        if (entity == null) throw new IllegalStateException("Entity is required to preserve read-only customFields");
        return property(entity.getClass()).orElseThrow(() ->
                new IllegalStateException("Entity must declare @CustomFields to preserve read-only values"));
    }
    private static void writeValues(Object entity, Property property, Map<String, Object> values) {
        try {
            if (property.accessor() != null) {
                for (Class<?> type = entity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                    for (Method method : type.getDeclaredMethods()) {
                        if (method.getName().equals("setCustomFields") && method.getParameterCount() == 1
                                && method.getParameterTypes()[0].isInstance(values)) {
                            method.trySetAccessible();
                            method.invoke(entity, values);
                            return;
                        }
                    }
                }
            }
            if (property.field() != null && !Modifier.isFinal(property.field().getModifiers())
                    && property.field().getType().isInstance(values)) {
                property.field().trySetAccessible();
                property.field().set(entity, values);
                return;
            }
            Object container;
            if (property.accessor() != null) {
                property.accessor().trySetAccessible();
                container = property.accessor().invoke(entity);
            } else {
                property.field().trySetAccessible();
                container = property.field().get(entity);
            }
            if (!(container instanceof Map<?, ?>)) throw new IllegalStateException("Entity customFields has no writable container");
            @SuppressWarnings("unchecked") Map<String, Object> target = (Map<String, Object>) container;
            target.clear();
            target.putAll(values);
        } catch (ReflectiveOperationException | UnsupportedOperationException e) {
            throw new IllegalStateException("Cannot restore read-only customFields on entity", e);
        }
    }
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, copyValue(nested)));
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            Collection<Object> copy = value instanceof Set<?> ? new LinkedHashSet<>() : new ArrayList<>();
            collection.forEach(nested -> copy.add(copyValue(nested)));
            return copy;
        }
        if (value != null && value.getClass().isArray()) {
            Object copy = Array.newInstance(value.getClass().getComponentType(), Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) Array.set(copy, i, copyValue(Array.get(value, i)));
            return copy;
        }
        return value;
    }
    private static boolean sameValue(Object left, Object right) {
        if (left == right) return true;
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            return a.keySet().equals(b.keySet()) && a.keySet().stream().allMatch(key -> sameValue(a.get(key), b.get(key)));
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameValue(a.get(i), b.get(i))) return false;
            return true;
        }
        if (left != null && right != null && left.getClass().isArray() && right.getClass().isArray()) {
            if (Array.getLength(left) != Array.getLength(right)) return false;
            for (int i = 0; i < Array.getLength(left); i++) if (!sameValue(Array.get(left, i), Array.get(right, i))) return false;
            return true;
        }
        return Objects.equals(left, right);
    }
    public static String resourceKey(Class<?> type, String fallback) {
        Optional<Property> property = property(type);
        if (property.isPresent() && !property.get().resourceKey().isBlank()) return property.get().resourceKey();
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(type, ApiResource.class);
        return resource == null || resource.resourceKey().isBlank() ? fallback : resource.resourceKey().trim();
    }
}
