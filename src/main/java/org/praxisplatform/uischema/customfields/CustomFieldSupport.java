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
        return candidate.accessor() != null || current == null ? candidate : current;
    }
    public static Optional<Property> property(Class<?> type) { return PROPERTIES.get(type); }
    public static Map<String, Object> read(Object value) {
        return value == null ? Map.of() : property(value.getClass()).map(p -> p.read(value)).orElse(Map.of());
    }
    public static String resourceKey(Class<?> type, String fallback) {
        Optional<Property> property = property(type);
        if (property.isPresent() && !property.get().resourceKey().isBlank()) return property.get().resourceKey();
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(type, ApiResource.class);
        return resource == null || resource.resourceKey().isBlank() ? fallback : resource.resourceKey().trim();
    }
}
