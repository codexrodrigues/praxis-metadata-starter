package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.exporting.CollectionExportField;
import org.praxisplatform.uischema.exporting.CollectionExportFieldPresentation;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.springframework.core.ResolvableType;

/** Canonical DTO property inventory shared by default fields and export governance. */
final class ResourceExportSupport {
    private ResourceExportSupport() { }

    private record Property(Class<?> type, List<AnnotatedElement> elements) {
        ResolvableType resolvedType(ResolvableType owner) {
            AnnotatedElement element = elements.getFirst();
            return element instanceof Field field ? ResolvableType.forField(field, owner)
                    : ResolvableType.forType(((Method) element).getGenericReturnType(), owner);
        }
        <A extends java.lang.annotation.Annotation> A annotation(Class<A> type) {
            return elements.stream().map(element -> element.getAnnotation(type)).filter(Objects::nonNull).findFirst().orElse(null);
        }
        boolean exportable() {
            return elements.stream().map(element -> element.getAnnotation(DomainGovernance.class)).filter(Objects::nonNull)
                    .allMatch(g -> g.exportable() && Arrays.stream(g.complianceTags()).noneMatch("NON_EXPORTABLE"::equalsIgnoreCase));
        }
    }

    private static final ClassValue<Map<String, Property>> PROPERTIES = new ClassValue<>() {
        @Override protected Map<String, Property> computeValue(Class<?> type) {
            Map<String, Class<?>> types = new LinkedHashMap<>();
            Map<String, List<AnnotatedElement>> elements = new LinkedHashMap<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                    types.putIfAbsent(f.getName(), f.getType());
                    elements.computeIfAbsent(f.getName(), ignored -> new ArrayList<>()).add(f);
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) continue;
                    String name = m.getName().startsWith("get") && m.getName().length() > 3
                            ? java.beans.Introspector.decapitalize(m.getName().substring(3))
                            : m.getName().startsWith("is") && m.getName().length() > 2
                            ? java.beans.Introspector.decapitalize(m.getName().substring(2)) : m.getName();
                    boolean beanGetter = Modifier.isPublic(m.getModifiers()) && m.getReturnType() != void.class
                            && ((m.getName().startsWith("get") && m.getName().length() > 3)
                            || (m.getName().startsWith("is") && m.getName().length() > 2
                                && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)));
                    if (!types.containsKey(name) && !beanGetter && m.getAnnotation(UISchema.class) == null && m.getAnnotation(DomainGovernance.class) == null) continue;
                    types.putIfAbsent(name, m.getReturnType());
                    elements.computeIfAbsent(name, ignored -> new ArrayList<>()).add(m);
                }
                if (c.isRecord()) for (RecordComponent component : c.getRecordComponents())
                    elements.computeIfAbsent(component.getName(), ignored -> new ArrayList<>()).add(component);
            }
            for (Class<?> contract : org.springframework.util.ClassUtils.getAllInterfacesForClass(type))
            for (Method method : contract.getDeclaredMethods()) {
                if (!method.getDeclaringClass().isInterface() || method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) continue;
                String name = method.getName().startsWith("get") && method.getName().length() > 3
                        ? java.beans.Introspector.decapitalize(method.getName().substring(3))
                        : method.getName().startsWith("is") && method.getName().length() > 2
                        ? java.beans.Introspector.decapitalize(method.getName().substring(2)) : method.getName();
                if (!types.containsKey(name) && method.getAnnotation(UISchema.class) == null && method.getAnnotation(DomainGovernance.class) == null) continue;
                types.putIfAbsent(name, method.getReturnType());
                elements.computeIfAbsent(name, ignored -> new ArrayList<>()).add(method);
            }
            Map<String, Property> result = new LinkedHashMap<>();
            types.forEach((name, propertyType) -> result.put(name, new Property(propertyType, List.copyOf(elements.get(name)))));
            return Collections.unmodifiableMap(result);
        }
    };

    static boolean exportable(Class<?> type, String path) {
        if (type == null || path == null || path.isBlank()) return false;
        ResolvableType resolved = ResolvableType.forClass(type);
        for (String part : path.split("\\.")) {
            Class<?> raw = resolved.resolve();
            if (raw == null) return false;
            Property property = PROPERTIES.get(raw).get(part);
            if (property == null || !property.exportable()) return false;
            resolved = property.resolvedType(resolved);
        }
        return !hasRestrictedDescendant(resolved, new HashSet<>());
    }

    private static boolean scalar(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class
                || (type.getName().startsWith("java.") && Number.class.isAssignableFrom(type)) || type == Boolean.class || type == Character.class
                || type == UUID.class || type == java.net.URI.class || type == java.net.URL.class
                || (type.getName().startsWith("java.time.") && java.time.temporal.TemporalAccessor.class.isAssignableFrom(type))
                || (type.getName().startsWith("java.") && (Date.class.isAssignableFrom(type) || Calendar.class.isAssignableFrom(type)));
    }

    private static boolean hasRestrictedDescendant(ResolvableType type, Set<String> visited) {
        Class<?> raw = type.resolve();
        if (raw == null || raw == Object.class || com.fasterxml.jackson.databind.JsonNode.class.isAssignableFrom(raw)) return true; // Opaque JSON cannot prove field governance.
        if (scalar(raw)) return false;
        String shape = type.toString();
        if (visited.contains(shape)) return false;
        if (visited.size() >= 128) return true;
        visited.add(shape);
        if (type.isArray()) return hasRestrictedDescendant(type.getComponentType(), visited);
        if (Map.class.isAssignableFrom(raw)) {
            var map = type.as(Map.class);
            return hasRestrictedDescendant(map.getGeneric(0), visited) || hasRestrictedDescendant(map.getGeneric(1), visited);
        }
        if (Iterable.class.isAssignableFrom(raw)) return hasRestrictedDescendant(type.as(Iterable.class).getGeneric(0), visited);
        if (Optional.class.isAssignableFrom(raw)) return hasRestrictedDescendant(type.as(Optional.class).getGeneric(0), visited);
        if (java.util.concurrent.atomic.AtomicReference.class.isAssignableFrom(raw))
            return hasRestrictedDescendant(type.as(java.util.concurrent.atomic.AtomicReference.class).getGeneric(0), visited);
        if (raw.getName().startsWith("java.")) return true;
        if (hasDynamicJsonShape(raw)) return true;
        for (Property property : PROPERTIES.get(raw).values()) {
            if (!property.exportable() || hasRestrictedDescendant(property.resolvedType(type), visited)) return true;
        }
        return false;
    }

    private static boolean hasDynamicJsonShape(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                var any = method.getAnnotation(com.fasterxml.jackson.annotation.JsonAnyGetter.class);
                var value = method.getAnnotation(com.fasterxml.jackson.annotation.JsonValue.class);
                if ((any != null && any.enabled()) || (value != null && value.value())) return true;
            }
            for (Field field : current.getDeclaredFields()) {
                var any = field.getAnnotation(com.fasterxml.jackson.annotation.JsonAnyGetter.class);
                var value = field.getAnnotation(com.fasterxml.jackson.annotation.JsonValue.class);
                if ((any != null && any.enabled()) || (value != null && value.value())) return true;
            }
        }
        return Arrays.stream(type.getInterfaces()).anyMatch(ResourceExportSupport::hasDynamicJsonShape);
    }

    /** Checks concrete values too: a permitted declared base type must not hide a restricted subtype. */
    static Object requireExportableValue(Object value) {
        checkValue(value, Collections.newSetFromMap(new IdentityHashMap<>()));
        return value;
    }

    private static void checkValue(Object value, Set<Object> visited) {
        if (value == null) return;
        if (value instanceof com.fasterxml.jackson.databind.JsonNode || hasDynamicJsonShape(value.getClass()))
            throw new IllegalArgumentException("Dynamic JSON values require a typed export projection");
        if (scalar(value.getClass()) || !visited.add(value)) return;
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> { checkValue(key, visited); checkValue(item, visited); }); return;
        }
        if (value instanceof Iterable<?> items) { items.forEach(item -> checkValue(item, visited)); return; }
        if (value instanceof Optional<?> optional) { optional.ifPresent(item -> checkValue(item, visited)); return; }
        if (value instanceof java.util.concurrent.atomic.AtomicReference<?> reference) { checkValue(reference.get(), visited); return; }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) checkValue(Array.get(value, i), visited);
            return;
        }
        if (value.getClass().getName().startsWith("java.")) throw new IllegalArgumentException("Opaque values cannot be exported");
        for (var entry : PROPERTIES.get(value.getClass()).entrySet()) {
            if (!entry.getValue().exportable()) throw new IllegalArgumentException("Export value contains a restricted property");
            checkValue(ResourceKeysetSupport.read(value, entry.getKey()), visited);
        }
    }

    static List<CollectionExportField> defaults(Class<?> type) {
        if (type == null) return List.of();
        return PROPERTIES.get(type).entrySet().stream()
                .filter(entry -> entry.getValue().annotation(UISchema.class) != null)
                .filter(entry -> !entry.getValue().annotation(UISchema.class).tableHidden() && exportable(type, entry.getKey()))
                .sorted(Comparator.comparingInt(entry -> entry.getValue().annotation(UISchema.class).order()))
                .map(entry -> {
                    Property property = entry.getValue();
                    UISchema ui = property.annotation(UISchema.class);
                    String semantic = null, format = null, currency = null;
                    if (property.type() == LocalDate.class) { semantic = "date"; format = "dd/MM/yyyy"; }
                    else if (property.type() == LocalDateTime.class) { semantic = "datetime"; format = "dd/MM/yyyy HH:mm"; }
                    else if (ui.controlType().name().equals("CURRENCY_INPUT")) { semantic = "currency"; }
                    else if (property.type() == Boolean.class || property.type() == boolean.class) semantic = "boolean";
                    return new CollectionExportField(entry.getKey(), ui.label().isBlank() ? entry.getKey() : ui.label(),
                            true, true, property.type().getSimpleName(), entry.getKey(), format,
                            new CollectionExportFieldPresentation(semantic, format, currency, null, null,
                                    ui.labelTrue().isBlank() ? null : ui.labelTrue(), ui.labelFalse().isBlank() ? null : ui.labelFalse(), null));
                }).toList();
    }
}
