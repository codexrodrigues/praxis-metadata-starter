package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.exporting.CollectionExportField;
import org.praxisplatform.uischema.exporting.CollectionExportFieldPresentation;
import org.praxisplatform.uischema.schema.DomainLineageResolver;
import org.praxisplatform.uischema.schema.DomainSchemaGovernance;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.springframework.core.ResolvableType;

/** Canonical DTO property inventory shared by default fields and export governance. */
final class ResourceExportSupport {
    private ResourceExportSupport() { }

    private static ObjectNode toGovernanceNode(DomainGovernance governance) {
        return DomainLineageResolver.toGovernanceNode(governance);
    }

    private static final ClassValue<ObjectNode> TYPE_GOVERNANCE = new ClassValue<>() {
        @Override protected ObjectNode computeValue(Class<?> type) {
            return DomainLineageResolver.resolveTypeGovernance(type);
        }
    };

    private record Property(Class<?> type, List<AnnotatedElement> elements) {
        ResolvableType resolvedType(ResolvableType owner) {
            AnnotatedElement element = elements.getFirst();
            return element instanceof Field field ? ResolvableType.forField(field, owner)
                    : ResolvableType.forType(((Method) element).getGenericReturnType(), owner);
        }
        <A extends java.lang.annotation.Annotation> A annotation(Class<A> type) {
            return elements.stream().map(element -> org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(type, element.getAnnotations())).filter(Objects::nonNull).findFirst().orElse(null);
        }
        ObjectNode effectiveGovernance(Class<?> ownerType) {
            ObjectNode ownerGov = ownerType != null ? TYPE_GOVERNANCE.get(ownerType) : null;
            ObjectNode combined = ownerGov;
            DomainGovernance primaryLocalGov = annotation(DomainGovernance.class);
            List<DomainLineage> lineages = new ArrayList<>(
                    elements.stream()
                            .map(element -> org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(DomainLineage.class, element.getAnnotations()))
                            .filter(Objects::nonNull)
                            .toList());
            Collections.reverse(lineages);
            for (DomainLineage lineage : lineages) {
                DomainLineageResolver.ResolvedLineage resolved =
                        DomainLineageResolver.resolve(lineage, primaryLocalGov, ownerGov);
                if (resolved.weakeningAttempted()) {
                    throw new IllegalArgumentException(resolved.weakeningReason());
                }
                combined = DomainSchemaGovernance.mergeOrCopy(
                        combined,
                        resolved.effectiveGovernance());
            }
            List<DomainGovernance> declarations = new ArrayList<>(
                    elements.stream().map(element -> org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(DomainGovernance.class, element.getAnnotations())).filter(Objects::nonNull).toList());
            Collections.reverse(declarations);
            for (DomainGovernance g : declarations) {
                combined = DomainSchemaGovernance.mergeOrCopy(combined, toGovernanceNode(g));
            }
            if (type != null && !scalar(type)) {
                combined = DomainSchemaGovernance.mergeOrCopy(combined, TYPE_GOVERNANCE.get(type));
            }
            return combined;
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
                    if (!types.containsKey(name) && !beanGetter && org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(UISchema.class, m.getAnnotations()) == null && org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(DomainGovernance.class, m.getAnnotations()) == null && org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(DomainLineage.class, m.getAnnotations()) == null) continue;
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
                if (!types.containsKey(name) && org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(UISchema.class, method.getAnnotations()) == null && org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(DomainGovernance.class, method.getAnnotations()) == null && org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(DomainLineage.class, method.getAnnotations()) == null) continue;
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
        ObjectNode accumulatedGovernance;
        try {
            accumulatedGovernance = TYPE_GOVERNANCE.get(type);
            if (!DomainSchemaGovernance.isExportable(accumulatedGovernance)) return false;
            for (String part : path.split("\\.")) {
                Class<?> raw = resolved.resolve();
                if (raw == null) return false;
                accumulatedGovernance = DomainSchemaGovernance.mergeOrCopy(accumulatedGovernance, TYPE_GOVERNANCE.get(raw));
                if (!DomainSchemaGovernance.isExportable(accumulatedGovernance)) return false;
                Property property = PROPERTIES.get(raw).get(part);
                if (property == null) return false;
                accumulatedGovernance = DomainSchemaGovernance.mergeOrCopy(
                        accumulatedGovernance,
                        property.effectiveGovernance(raw));
                if (!DomainSchemaGovernance.isExportable(accumulatedGovernance)) return false;
                resolved = property.resolvedType(resolved);
            }
            return !hasRestrictedDescendant(resolved, accumulatedGovernance, new HashSet<>());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean scalar(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class
                || (type.getName().startsWith("java.") && Number.class.isAssignableFrom(type)) || type == Boolean.class || type == Character.class
                || type == UUID.class || type == java.net.URI.class || type == java.net.URL.class
                || (type.getName().startsWith("java.time.") && java.time.temporal.TemporalAccessor.class.isAssignableFrom(type))
                || (type.getName().startsWith("java.") && (Date.class.isAssignableFrom(type) || Calendar.class.isAssignableFrom(type)));
    }

    private static boolean hasRestrictedDescendant(ResolvableType type, ObjectNode ancestorGovernance, Set<String> visited) {
        Class<?> raw = type.resolve();
        if (raw == null || raw == Object.class || com.fasterxml.jackson.databind.JsonNode.class.isAssignableFrom(raw)) return true; // Opaque JSON cannot prove field governance.
        if (scalar(raw)) return !DomainSchemaGovernance.isExportable(ancestorGovernance);
        String shape = type.toString();
        if (visited.contains(shape)) return false;
        if (visited.size() >= 128) return true;
        visited.add(shape);
        if (type.isArray()) return hasRestrictedDescendant(type.getComponentType(), ancestorGovernance, visited);
        if (Map.class.isAssignableFrom(raw)) {
            var map = type.as(Map.class);
            return hasRestrictedDescendant(map.getGeneric(0), ancestorGovernance, visited)
                    || hasRestrictedDescendant(map.getGeneric(1), ancestorGovernance, visited);
        }
        if (Iterable.class.isAssignableFrom(raw)) return hasRestrictedDescendant(type.as(Iterable.class).getGeneric(0), ancestorGovernance, visited);
        if (Optional.class.isAssignableFrom(raw)) return hasRestrictedDescendant(type.as(Optional.class).getGeneric(0), ancestorGovernance, visited);
        if (java.util.concurrent.atomic.AtomicReference.class.isAssignableFrom(raw))
            return hasRestrictedDescendant(type.as(java.util.concurrent.atomic.AtomicReference.class).getGeneric(0), ancestorGovernance, visited);
        if (raw.getName().startsWith("java.")) return true;
        if (hasDynamicJsonShape(raw)) return true;
        ObjectNode typeGovernance = DomainSchemaGovernance.mergeOrCopy(ancestorGovernance, TYPE_GOVERNANCE.get(raw));
        if (!DomainSchemaGovernance.isExportable(typeGovernance)) return true;
        for (Property property : PROPERTIES.get(raw).values()) {
            ObjectNode propertyGovernance = DomainSchemaGovernance.mergeOrCopy(typeGovernance, property.effectiveGovernance(raw));
            if (!DomainSchemaGovernance.isExportable(propertyGovernance)
                    || hasRestrictedDescendant(property.resolvedType(type), propertyGovernance, visited)) return true;
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
        checkValue(value, null, Collections.newSetFromMap(new IdentityHashMap<>()));
        return value;
    }

    private static void checkValue(Object value, ObjectNode ancestorGovernance, Set<Object> visited) {
        if (value == null) return;
        if (value instanceof com.fasterxml.jackson.databind.JsonNode || hasDynamicJsonShape(value.getClass()))
            throw new IllegalArgumentException("Dynamic JSON values require a typed export projection");
        if (scalar(value.getClass())) {
            if (!DomainSchemaGovernance.isExportable(ancestorGovernance)) {
                throw new IllegalArgumentException("Export value contains a restricted property");
            }
            return;
        }
        if (!visited.add(value)) return;
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> { checkValue(key, ancestorGovernance, visited); checkValue(item, ancestorGovernance, visited); }); return;
        }
        if (value instanceof Iterable<?> items) { items.forEach(item -> checkValue(item, ancestorGovernance, visited)); return; }
        if (value instanceof Optional<?> optional) { optional.ifPresent(item -> checkValue(item, ancestorGovernance, visited)); return; }
        if (value instanceof java.util.concurrent.atomic.AtomicReference<?> reference) { checkValue(reference.get(), ancestorGovernance, visited); return; }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) checkValue(Array.get(value, i), ancestorGovernance, visited);
            return;
        }
        if (value.getClass().getName().startsWith("java.")) throw new IllegalArgumentException("Opaque values cannot be exported");
        ObjectNode objectGovernance = DomainSchemaGovernance.mergeOrCopy(ancestorGovernance, TYPE_GOVERNANCE.get(value.getClass()));
        if (!DomainSchemaGovernance.isExportable(objectGovernance)) {
            throw new IllegalArgumentException("Export value contains a restricted property");
        }
        for (var entry : PROPERTIES.get(value.getClass()).entrySet()) {
            ObjectNode propertyGovernance = DomainSchemaGovernance.mergeOrCopy(
                    objectGovernance,
                    entry.getValue().effectiveGovernance(value.getClass()));
            if (!DomainSchemaGovernance.isExportable(propertyGovernance)) {
                throw new IllegalArgumentException("Export value contains a restricted property");
            }
            checkValue(ResourceKeysetSupport.read(value, entry.getKey()), propertyGovernance, visited);
        }
    }

    static List<CollectionExportField> defaults(Class<?> type) {
        return buildExportFields(type, false);
    }

    /**
     * Derives all exportable {@link CollectionExportField}s from {@code @UISchema} and
     * {@code @DomainGovernance} on {@code type}, including {@code tableHidden = true} fields with
     * {@code defaultSelected = false} ({@code selectable = true}).
     */
    public static List<CollectionExportField> allExportableFields(Class<?> type) {
        return buildExportFields(type, true);
    }

    private static List<CollectionExportField> buildExportFields(Class<?> type, boolean includeTableHidden) {
        if (type == null) return List.of();
        return PROPERTIES.get(type).entrySet().stream()
                .filter(entry -> entry.getValue().annotation(UISchema.class) != null)
                .filter(entry -> (includeTableHidden || !entry.getValue().annotation(UISchema.class).tableHidden())
                        && exportable(type, entry.getKey()))
                .sorted(Comparator.comparingInt(entry -> entry.getValue().annotation(UISchema.class).order()))
                .map(entry -> {
                    Property property = entry.getValue();
                    UISchema ui = property.annotation(UISchema.class);
                    String semantic = null, format = null, currency = null;
                    if (property.type() == LocalDate.class) { semantic = "date"; format = "dd/MM/yyyy"; }
                    else if (property.type() == LocalDateTime.class) { semantic = "datetime"; format = "dd/MM/yyyy HH:mm"; }
                    else if (ui.controlType().name().equals("CURRENCY_INPUT") || ui.preset() == org.praxisplatform.uischema.extension.annotation.UISchemaPreset.MONETARY_AMOUNT) { semantic = "currency"; currency = "BRL"; }
                    else if (property.type() == Boolean.class || property.type() == boolean.class) semantic = "boolean";
                    return new CollectionExportField(entry.getKey(), ui.label().isBlank() ? entry.getKey() : ui.label(),
                            !ui.tableHidden(), true, property.type().getSimpleName(), entry.getKey(), format,
                            new CollectionExportFieldPresentation(semantic, format, currency, null, null,
                                    ui.labelTrue().isBlank() ? null : ui.labelTrue(), ui.labelFalse().isBlank() ? null : ui.labelFalse(), null));
                }).toList();
    }
}
