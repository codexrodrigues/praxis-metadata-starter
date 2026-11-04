package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Embeddable;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.*;
import java.math.*;
import java.util.*;

/** Detached previous-state values, without implicit traversal of managed entity associations. */
public final class ResourceStateCopySupport {
    private ResourceStateCopySupport() { }

    /**
     * Copies only explicitly supplied typed patch properties, including null, false and zero.
     * Omitted properties retain their current value. The operation schema and domain service
     * remain responsible for authorization, nullability and validation.
     * @param patch typed input that tracks setter presence
     * @param target full update draft receiving the supplied properties
     */
    public static void copySuppliedPatchProperties(org.praxisplatform.uischema.dto.PartialUpdateInput patch, Object target) {
        copySuppliedPatchProperties(patch, target, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /** Uses the HTTP mapper's public JSON names, naming strategy and admitted aliases. */
    public static void copySuppliedPatchProperties(org.praxisplatform.uischema.dto.PartialUpdateInput patch, Object target,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(mapper, "mapper");
        var copies = new IdentityHashMap<Object, Object>();
        var input = mapper.getDeserializationConfig().introspect(mapper.constructType(patch.getClass()));
        var output = mapper.getDeserializationConfig().introspect(mapper.constructType(target.getClass())).findProperties();
        for (var property : input.findProperties()) {
            if (!property.couldDeserialize() || !patch.wasSupplied(property.getName())) continue;
            var targetProperty = output.stream().filter(p -> p.couldDeserialize() && p.getName().equals(property.getName()))
                    .findFirst().orElseGet(() -> output.stream()
                            .filter(p -> p.couldDeserialize() && p.getInternalName().equals(property.getInternalName()))
                            .findFirst().orElse(null));
            var accessor = property.getAccessor();
            var mutator = targetProperty == null ? null : targetProperty.getNonConstructorMutator();
            if (accessor == null || mutator == null)
                throw new IllegalArgumentException("Supplied patch property cannot be mapped: " + property.getName());
            accessor.fixAccess(true);
            mutator.fixAccess(true);
            mutator.setValue(target, copy(accessor.getValue(patch), copies));
        }
    }

    static Object copy(Object value, IdentityHashMap<Object, Object> copies) {
        if (value == null) return null;
        Class<?> type = value.getClass();
        if (type.isEnum() || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof UUID || value instanceof java.net.URI || type.getName().startsWith("java.time.")) return value;
        if (copies.containsKey(value)) return copies.get(value);
        if (copies.size() >= 10_000) throw new IllegalStateException("Previous-state snapshot exceeds supported object count");
        if (!org.hibernate.Hibernate.isInitialized(value))
            throw new IllegalStateException("Override snapshotBeforeMutation for lazy domain associations");
        if (value instanceof JsonNode json) return json.deepCopy();
        if (value instanceof Date date) return date.clone();
        if (value instanceof Calendar calendar) return calendar.clone();
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(); copies.put(value, copy);
            map.forEach((key, item) -> copy.put(copy(key, copies), copy(item, copies))); return copy;
        }
        if (value instanceof Collection<?> items) {
            Collection<Object> copy = value instanceof Set<?> ? new LinkedHashSet<>() : new ArrayList<>(); copies.put(value, copy);
            items.forEach(item -> copy.add(copy(item, copies))); return copy;
        }
        if (type.isArray()) {
            Object copy = Array.newInstance(type.getComponentType(), Array.getLength(value)); copies.put(value, copy);
            for (int i = 0; i < Array.getLength(value); i++) Array.set(copy, i, copy(Array.get(value, i), copies));
            return copy;
        }
        if (type.isAnnotationPresent(Embeddable.class)) {
            Object copy = BeanUtils.instantiateClass(type); copies.put(value, copy);
            copyFields(value, copy, copies); return copy;
        }
        throw new IllegalStateException("Override snapshotBeforeMutation for mutable domain value " + type.getName());
    }

    static void copyFields(Object source, Object target, IdentityHashMap<Object, Object> copies) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                try {
                    field.setAccessible(true);
                    field.set(target, copy(field.get(source), copies));
                } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot snapshot resource state", ex); }
            }
        }
    }
}
