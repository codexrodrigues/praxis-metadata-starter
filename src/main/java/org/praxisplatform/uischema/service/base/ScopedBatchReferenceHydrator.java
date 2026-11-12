package org.praxisplatform.uischema.service.base;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.praxisplatform.uischema.annotation.ResourceReference;

/**
 * Multi-tenant-safe batch hydrator for {@link ResourceReference} display fields on response DTOs.
 *
 * <p>Eliminates N+1 queries and prevents cross-tenant title leakage by routing each distinct
 * {@code resourceKey} batch lookup through {@link AbstractBaseQueryResourceService#resolveScopedTitlesByIds(Collection)},
 * which enforces the target resource's {@link AbstractBaseQueryResourceService#resolveResourceFilterAccessScope()}.</p>
 */
public final class ScopedBatchReferenceHydrator {

    private record ReferenceFieldBinding(
            String resourceKey,
            Field displayField,
            Field valueField
    ) {}

    private static final ClassValue<List<ReferenceFieldBinding>> BINDINGS_CACHE = new ClassValue<>() {
        @Override
        protected List<ReferenceFieldBinding> computeValue(Class<?> dtoType) {
            if (dtoType == null || dtoType.isRecord()) {
                return List.of();
            }
            Map<String, Field> fieldsByName = new LinkedHashMap<>();
            for (Class<?> c = dtoType; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                        field.setAccessible(true);
                        fieldsByName.putIfAbsent(field.getName(), field);
                    }
                }
            }
            List<ReferenceFieldBinding> bindings = new ArrayList<>();
            for (Field field : fieldsByName.values()) {
                if (field.getType() != String.class || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                ResourceReference ref = field.getAnnotation(ResourceReference.class);
                if (ref == null) {
                    String getterName = "get" + Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
                    for (Class<?> c = dtoType; c != null && c != Object.class && ref == null; c = c.getSuperclass()) {
                        try {
                            ref = c.getDeclaredMethod(getterName).getAnnotation(ResourceReference.class);
                        } catch (NoSuchMethodException ignored) {
                            // continue walking hierarchy
                        }
                    }
                }
                if (ref == null || ref.resourceKey().isBlank() || ref.valueField().isBlank()) {
                    continue;
                }
                String valueFieldName = ref.valueField().trim();
                if (valueFieldName.equals(field.getName())) {
                    continue;
                }
                Field fkField = fieldsByName.get(valueFieldName);
                if (fkField == null) {
                    continue;
                }
                bindings.add(new ReferenceFieldBinding(ref.resourceKey().trim(), field, fkField));
            }
            return List.copyOf(bindings);
        }
    };

    private final Function<String, Optional<AbstractBaseQueryResourceService<?, ?, ?, ?>>> serviceResolver;

    public ScopedBatchReferenceHydrator(
            Function<String, Optional<AbstractBaseQueryResourceService<?, ?, ?, ?>>> serviceResolver) {
        this.serviceResolver = Objects.requireNonNull(serviceResolver, "serviceResolver");
    }

    public ScopedBatchReferenceHydrator(
            Map<String, ? extends AbstractBaseQueryResourceService<?, ?, ?, ?>> servicesByResourceKey) {
        Map<String, AbstractBaseQueryResourceService<?, ?, ?, ?>> copy =
                Map.copyOf(Objects.requireNonNull(servicesByResourceKey, "servicesByResourceKey"));
        this.serviceResolver = key -> Optional.ofNullable(copy.get(key));
    }

    /**
     * Hydrates all null or blank {@link ResourceReference} display fields across {@code responses}
     * using at most 1 scoped batch query per distinct referenced {@code resourceKey}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R> List<R> hydrate(List<R> responses) {
        if (responses == null || responses.isEmpty()) {
            return responses;
        }
        R firstNonNull = responses.stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (firstNonNull == null) {
            return responses;
        }
        List<ReferenceFieldBinding> bindings = BINDINGS_CACHE.get(firstNonNull.getClass());
        if (bindings.isEmpty()) {
            return responses;
        }
        Map<String, Set<Object>> pendingIdsByResourceKey = new LinkedHashMap<>();
        for (ReferenceFieldBinding binding : bindings) {
            for (R item : responses) {
                if (item == null) continue;
                try {
                    String currentDisplay = (String) binding.displayField().get(item);
                    if (currentDisplay != null && !currentDisplay.isBlank()) {
                        continue;
                    }
                    Object fkValue = binding.valueField().get(item);
                    if (fkValue != null) {
                        pendingIdsByResourceKey
                                .computeIfAbsent(binding.resourceKey(), ignored -> new LinkedHashSet<>())
                                .add(fkValue);
                    }
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException("Cannot inspect @ResourceReference field", ex);
                }
            }
        }
        if (pendingIdsByResourceKey.isEmpty()) {
            return responses;
        }
        Map<String, Map<Object, String>> resolvedTitlesByResourceKey = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Object>> entry : pendingIdsByResourceKey.entrySet()) {
            Optional<AbstractBaseQueryResourceService<?, ?, ?, ?>> targetOpt =
                    serviceResolver.apply(entry.getKey());
            if (targetOpt.isPresent()) {
                AbstractBaseQueryResourceService targetService = targetOpt.get();
                Map<Object, String> titles = targetService.resolveScopedTitlesByIds(entry.getValue());
                resolvedTitlesByResourceKey.put(entry.getKey(), titles);
            }
        }
        for (ReferenceFieldBinding binding : bindings) {
            Map<Object, String> titles = resolvedTitlesByResourceKey.get(binding.resourceKey());
            if (titles == null || titles.isEmpty()) {
                continue;
            }
            for (R item : responses) {
                if (item == null) continue;
                try {
                    String currentDisplay = (String) binding.displayField().get(item);
                    if (currentDisplay != null && !currentDisplay.isBlank()) {
                        continue;
                    }
                    Object fkValue = binding.valueField().get(item);
                    if (fkValue != null) {
                        String resolved = resolveTitle(titles, fkValue);
                        if (resolved != null && !resolved.isBlank()) {
                            binding.displayField().set(item, resolved);
                        }
                    }
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException("Cannot populate @ResourceReference field", ex);
                }
            }
        }
        return responses;
    }

    private static String resolveTitle(Map<Object, String> titles, Object fkValue) {
        String direct = titles.get(fkValue);
        if (direct != null) {
            return direct;
        }
        if (fkValue instanceof Number num) {
            String asLong = titles.get(num.longValue());
            if (asLong != null) {
                return asLong;
            }
            String asInt = titles.get(num.intValue());
            if (asInt != null) {
                return asInt;
            }
        }
        return null;
    }
}
