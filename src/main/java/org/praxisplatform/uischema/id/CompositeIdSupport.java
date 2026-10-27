package org.praxisplatform.uischema.id;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.IdClass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical introspection and wire encoding/decoding support for JPA {@link Embeddable}
 * composite primary keys and {@link CompositeResourceId} types.
 *
 * <p>On the HTTP/JSON/OpenAPI wire, a composite identifier is represented as a colon-delimited
 * canonical string in the declaration order of its components (for example, {@code "1001:1"} for
 * {@code (numfunc=1001, numvinc=1)}), escaping literal {@code '%'} and {@code ':'} characters
 * inside string components as {@code "%25"} and {@code "%3A"}.</p>
 *
 * <p>Note: Ordinary {@code @Embeddable} value objects used with {@code @Embedded} (such as
 * {@code LotacaoEmbeddable} or {@code EnderecoEmbeddable}) are distinguished from composite
 * identifiers so they remain structured JSON objects in DTOs and OpenAPI schemas while still
 * supporting compound path navigation in JPA Criteria queries.</p>
 */
public final class CompositeIdSupport {

    public static final String SEPARATOR = ":";
    private static final char SEPARATOR_CHAR = ':';
    private static final Map<Class<?>, List<ComponentDescriptor>> DESCRIPTOR_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> REGISTERED_COMPOSITE_ID_TYPES = ConcurrentHashMap.newKeySet();

    private CompositeIdSupport() {
    }

    /**
     * Explicitly registers a class as a composite resource identifier type.
     * Called automatically when inspecting {@code @EmbeddedId}, {@code @IdClass}, or controller/service
     * {@code ID} generic parameters.
     */
    public static void registerCompositeIdType(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isEnum() || type.isArray()) {
            return;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jakarta.") || name.startsWith("org.springframework.")) {
            return;
        }
        if (type.isAnnotationPresent(Embeddable.class) || CompositeResourceId.class.isAssignableFrom(type)) {
            REGISTERED_COMPOSITE_ID_TYPES.add(type);
        }
    }

    /**
     * Scans a JPA entity class hierarchy for {@link EmbeddedId} fields and {@link IdClass}
     * declarations, registering their composite key types automatically.
     */
    public static void registerEntityCompositeIdTypes(Class<?> entityClass) {
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            IdClass idClass = current.getAnnotation(IdClass.class);
            if (idClass != null && idClass.value() != null) {
                REGISTERED_COMPOSITE_ID_TYPES.add(idClass.value());
            }
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(EmbeddedId.class)) {
                    REGISTERED_COMPOSITE_ID_TYPES.add(field.getType());
                }
            }
            current = current.getSuperclass();
        }
    }

    public static boolean isEmbeddableValueType(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isEnum() || type.isArray()) {
            return false;
        }
        return type.isAnnotationPresent(Embeddable.class);
    }

    public static boolean isCompositeIdType(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isEnum() || type.isArray()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jakarta.") || name.startsWith("org.springframework.")) {
            return false;
        }
        if (CompositeResourceId.class.isAssignableFrom(type) || REGISTERED_COMPOSITE_ID_TYPES.contains(type)) {
            return true;
        }
        return type.isAnnotationPresent(Embeddable.class) && hasCompositeIdNamingConvention(type);
    }

    private static boolean hasCompositeIdNamingConvention(Class<?> type) {
        String simpleName = type.getSimpleName();
        return simpleName.endsWith("Id")
                || simpleName.endsWith("ID")
                || simpleName.endsWith("Pk")
                || simpleName.endsWith("PK")
                || simpleName.endsWith("Key")
                || simpleName.endsWith("Chave");
    }

    public static boolean isCompositeIdValue(Object value) {
        return value != null && isCompositeIdType(value.getClass());
    }

    public static List<ComponentDescriptor> descriptors(Class<?> compositeType) {
        if (!isCompositeIdType(compositeType)) {
            return List.of();
        }
        return DESCRIPTOR_CACHE.computeIfAbsent(compositeType, CompositeIdSupport::introspectDescriptors);
    }

    public static List<String> componentNames(Class<?> compositeType) {
        return descriptors(compositeType).stream()
                .map(ComponentDescriptor::name)
                .toList();
    }

    public static String encode(Object compositeId) {
        if (compositeId == null) {
            return null;
        }
        Class<?> type = compositeId.getClass();
        List<ComponentDescriptor> components = descriptors(type);
        if (components.isEmpty()) {
            return String.valueOf(compositeId);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                sb.append(SEPARATOR_CHAR);
            }
            Object value = components.get(i).read(compositeId);
            if (value == null) {
                throw new IllegalArgumentException(
                        "Composite identifier component '" + components.get(i).name()
                                + "' on " + type.getSimpleName() + " must not be null."
                );
            }
            sb.append(escapeComponent(String.valueOf(value)));
        }
        return sb.toString();
    }

    public static <T> T decode(String wire, Class<T> targetType) {
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (wire == null || wire.isBlank()) {
            throw new IllegalArgumentException(
                    "Composite identifier for " + targetType.getSimpleName() + " must be a non-blank string."
            );
        }
        List<ComponentDescriptor> components = descriptors(targetType);
        if (components.isEmpty()) {
            throw new IllegalArgumentException(
                    "Type " + targetType.getName() + " has no composite identifier components."
            );
        }
        List<String> rawParts = splitWireParts(wire.trim());
        if (rawParts.size() != components.size()) {
            throw new IllegalArgumentException(
                    "Invalid composite identifier '" + wire + "' for " + targetType.getSimpleName()
                            + ": expected " + components.size() + " components ("
                            + String.join(SEPARATOR, componentNames(targetType))
                            + ") but got " + rawParts.size() + "."
            );
        }
        List<Object> coercedValues = new ArrayList<>(components.size());
        for (int i = 0; i < components.size(); i++) {
            ComponentDescriptor descriptor = components.get(i);
            String unescaped = unescapeComponent(rawParts.get(i));
            if (unescaped.isEmpty() && descriptor.type() != String.class) {
                throw new IllegalArgumentException(
                        "Component '" + descriptor.name() + "' in composite identifier '" + wire + "' must not be empty."
                );
            }
            coercedValues.add(coerceScalar(unescaped, descriptor.type()));
        }
        return instantiate(targetType, components, coercedValues);
    }

    public static <T> T fromComponentValues(Class<T> targetType, List<Object> values) {
        List<ComponentDescriptor> components = descriptors(targetType);
        if (components.size() != values.size()) {
            throw new IllegalArgumentException(
                    "Expected " + components.size() + " component values for " + targetType.getSimpleName()
                            + " but got " + values.size() + "."
            );
        }
        List<Object> normalized = new ArrayList<>(components.size());
        for (int i = 0; i < components.size(); i++) {
            Object val = values.get(i);
            if (val == null) {
                throw new IllegalArgumentException(
                        "Composite identifier component '" + components.get(i).name()
                                + "' on " + targetType.getSimpleName() + " must not be null."
                );
            }
            Class<?> expectedType = components.get(i).type();
            if (!expectedType.isInstance(val)) {
                val = coerceScalar(String.valueOf(val), expectedType);
            }
            normalized.add(val);
        }
        return instantiate(targetType, components, normalized);
    }

    public static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (isCompositeIdValue(value)) {
            return encode(value);
        }
        return String.valueOf(value);
    }

    public static String stringifyNullable(Object value) {
        return value == null ? null : stringify(value);
    }

    @SuppressWarnings("unchecked")
    public static Object coerceIfComposite(Object value, Class<?> targetType) {
        if (value == null || !isCompositeIdType(targetType)) {
            return value;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (value instanceof String str) {
            return decode(str, (Class<Object>) targetType);
        }
        if (value instanceof Collection<?> col) {
            return col.stream()
                    .map(item -> coerceIfComposite(item, targetType))
                    .toList();
        }
        return value;
    }

    public static Object coerceScalar(String raw, Class<?> targetType) {
        if (raw == null) {
            return null;
        }
        if (targetType == null || targetType == Object.class || targetType == String.class) {
            return raw;
        }
        if (isCompositeIdType(targetType)) {
            return decode(raw, targetType);
        }
        try {
            if (targetType == Long.class || targetType == long.class) {
                return Long.valueOf(raw);
            }
            if (targetType == Integer.class || targetType == int.class) {
                return Integer.valueOf(raw);
            }
            if (targetType == Short.class || targetType == short.class) {
                return Short.valueOf(raw);
            }
            if (targetType == Byte.class || targetType == byte.class) {
                return Byte.valueOf(raw);
            }
            if (targetType == BigDecimal.class) {
                return new BigDecimal(raw);
            }
            if (targetType == BigInteger.class) {
                return new BigInteger(raw);
            }
            if (targetType == Double.class || targetType == double.class) {
                return Double.valueOf(raw);
            }
            if (targetType == Float.class || targetType == float.class) {
                return Float.valueOf(raw);
            }
            if (targetType == Boolean.class || targetType == boolean.class) {
                return Boolean.valueOf(raw);
            }
            if (targetType == UUID.class) {
                return UUID.fromString(raw);
            }
            if (targetType == LocalDate.class) {
                return LocalDate.parse(raw);
            }
            if (targetType == LocalDateTime.class) {
                return LocalDateTime.parse(raw);
            }
            if (targetType == Instant.class) {
                return Instant.parse(raw);
            }
            if (targetType == OffsetDateTime.class) {
                return OffsetDateTime.parse(raw);
            }
            if (targetType == ZonedDateTime.class) {
                return ZonedDateTime.parse(raw);
            }
            if (Enum.class.isAssignableFrom(targetType)) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object enumValue = Enum.valueOf((Class<? extends Enum>) targetType.asSubclass(Enum.class), raw);
                return enumValue;
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "Cannot convert composite identifier component value '" + raw + "' to " + targetType.getSimpleName() + ".",
                    ex
            );
        }
        return raw;
    }

    private static List<ComponentDescriptor> introspectDescriptors(Class<?> compositeType) {
        if (compositeType.isRecord()) {
            RecordComponent[] recordComponents = compositeType.getRecordComponents();
            if (recordComponents == null || recordComponents.length == 0) {
                return List.of();
            }
            List<ComponentDescriptor> result = new ArrayList<>(recordComponents.length);
            for (RecordComponent rc : recordComponents) {
                var accessor = rc.getAccessor();
                accessor.setAccessible(true);
                Field field = findFieldSilently(compositeType, rc.getName());
                if (field != null) {
                    field.setAccessible(true);
                }
                result.add(new ComponentDescriptor(rc.getName(), rc.getType(), field, accessor));
            }
            return Collections.unmodifiableList(result);
        }

        List<ComponentDescriptor> result = new ArrayList<>();
        Class<?> current = compositeType;
        List<Class<?>> hierarchy = new ArrayList<>();
        while (current != null && current != Object.class) {
            hierarchy.add(0, current);
            current = current.getSuperclass();
        }
        for (Class<?> clazz : hierarchy) {
            for (Field field : clazz.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                result.add(new ComponentDescriptor(field.getName(), field.getType(), field, null));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static Field findFieldSilently(Class<?> clazz, String name) {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static <T> T instantiate(Class<T> targetType, List<ComponentDescriptor> components, List<Object> values) {
        try {
            Class<?>[] paramTypes = components.stream()
                    .map(ComponentDescriptor::type)
                    .toArray(Class<?>[]::new);
            try {
                Constructor<T> canonical = targetType.getDeclaredConstructor(paramTypes);
                canonical.setAccessible(true);
                return canonical.newInstance(values.toArray());
            } catch (NoSuchMethodException ignored) {
                // Fall back to no-arg constructor and field reflection for standard JPA @Embeddable classes
            }
            Constructor<T> noArg = targetType.getDeclaredConstructor();
            noArg.setAccessible(true);
            T instance = noArg.newInstance();
            for (int i = 0; i < components.size(); i++) {
                Field field = components.get(i).field();
                if (field == null) {
                    throw new IllegalStateException("Cannot set component '" + components.get(i).name() + "' on " + targetType.getName());
                }
                field.set(instance, values.get(i));
            }
            return instance;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("Failed to instantiate composite identifier " + targetType.getName(), ex);
        }
    }

    private static String escapeComponent(String raw) {
        return raw.replace("%", "%25").replace(":", "%3A");
    }

    private static String unescapeComponent(String escaped) {
        return escaped.replace("%3A", ":").replace("%3a", ":").replace("%25", "%");
    }

    private static List<String> splitWireParts(String wire) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < wire.length(); i++) {
            if (wire.charAt(i) == SEPARATOR_CHAR) {
                parts.add(wire.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(wire.substring(start));
        return parts;
    }

    public record ComponentDescriptor(
            String name,
            Class<?> type,
            Field field,
            java.lang.reflect.Method accessor
    ) {
        public Object read(Object target) {
            try {
                if (accessor != null) {
                    return accessor.invoke(target);
                }
                if (field != null) {
                    return field.get(target);
                }
                throw new IllegalStateException("No reader for component " + name);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Failed to read composite identifier component " + name, ex);
            }
        }
    }
}
