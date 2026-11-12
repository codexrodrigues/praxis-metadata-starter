package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.*;
import java.math.*;
import java.util.*;

/** Detached previous-state values, without implicit traversal of managed entity associations. */
public final class ResourceStateCopySupport {
    private static final com.fasterxml.jackson.databind.ObjectMapper DEFAULT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private ResourceStateCopySupport() { }

    /**
     * Copies only explicitly supplied typed patch properties, including null, false and zero.
     * Omitted properties retain their current value. The operation schema and domain service
     * remain responsible for authorization, nullability and validation.
     * @param patch typed input that tracks setter presence
     * @param target full update draft receiving the supplied properties
     */
    public static void copySuppliedPatchProperties(org.praxisplatform.uischema.dto.PartialUpdateInput patch, Object target) {
        copySuppliedPatchProperties(patch, target, DEFAULT_MAPPER);
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
        if (type.isRecord()) {
            try {
                RecordComponent[] components = type.getRecordComponents();
                Class<?>[] paramTypes = new Class<?>[components.length];
                Object[] args = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    paramTypes[i] = components[i].getType();
                    Method accessor = components[i].getAccessor();
                    accessor.setAccessible(true);
                    args[i] = copy(accessor.invoke(value), copies);
                }
                Constructor<?> canonical = type.getDeclaredConstructor(paramTypes);
                canonical.setAccessible(true);
                Object copy = canonical.newInstance(args);
                copies.put(value, copy);
                return copy;
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Cannot snapshot record value " + type.getName(), ex);
            }
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
                    Object fieldValue = field.get(source);
                    if (isJpaToOneAssociationField(field)) {
                        field.set(target, fieldValue != null && org.hibernate.Hibernate.isInitialized(fieldValue) ? fieldValue : null);
                    } else if (isJpaToManyEntityAssociationField(field)) {
                        if (fieldValue != null && org.hibernate.Hibernate.isInitialized(fieldValue) && fieldValue instanceof Collection<?> coll) {
                            Collection<Object> detached = fieldValue instanceof Set<?> ? new LinkedHashSet<>(coll) : new ArrayList<>(coll);
                            field.set(target, detached);
                        } else {
                            field.set(target, null);
                        }
                    } else if (field.isAnnotationPresent(ElementCollection.class)) {
                        field.set(target, fieldValue != null && org.hibernate.Hibernate.isInitialized(fieldValue) ? copy(fieldValue, copies) : null);
                    } else if (field.isAnnotationPresent(jakarta.persistence.Embedded.class)
                            || field.isAnnotationPresent(jakarta.persistence.EmbeddedId.class)) {
                        field.set(target, copyEmbeddedValue(fieldValue, copies));
                    } else {
                        field.set(target, copy(fieldValue, copies));
                    }
                } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot snapshot resource state", ex); }
            }
        }
    }

    private static Object copyEmbeddedValue(Object value, IdentityHashMap<Object, Object> copies) {
        if (value == null) return null;
        if (copies.containsKey(value)) return copies.get(value);
        Class<?> type = value.getClass();
        if (type.isRecord() || type.isAnnotationPresent(Embeddable.class)) {
            return copy(value, copies);
        }
        Object copy = BeanUtils.instantiateClass(type);
        copies.put(value, copy);
        copyFields(value, copy, copies);
        return copy;
    }

    private static boolean isJpaToOneAssociationField(Field field) {
        return field.isAnnotationPresent(ManyToOne.class)
                || field.isAnnotationPresent(OneToOne.class);
    }

    private static boolean isJpaToManyEntityAssociationField(Field field) {
        return field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class);
    }
}
