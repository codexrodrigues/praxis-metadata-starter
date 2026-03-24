package org.praxisplatform.uischema.options;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of option sources exposed by each resource.
 */
public final class OptionSourceRegistry {

    private static final OptionSourceRegistry EMPTY = new OptionSourceRegistry(Map.of());

    private final Map<Class<?>, Map<String, OptionSourceDescriptor>> descriptorsByResource;

    private OptionSourceRegistry(Map<Class<?>, Map<String, OptionSourceDescriptor>> descriptorsByResource) {
        this.descriptorsByResource = descriptorsByResource;
    }

    public static OptionSourceRegistry empty() {
        return EMPTY;
    }

    public static OptionSourceRegistry of(Map<Class<?>, Collection<OptionSourceDescriptor>> descriptorsByResource) {
        if (descriptorsByResource == null || descriptorsByResource.isEmpty()) {
            return empty();
        }
        Map<Class<?>, Map<String, OptionSourceDescriptor>> mapped = new LinkedHashMap<>();
        descriptorsByResource.forEach((resourceClass, descriptors) -> {
            if (resourceClass == null || descriptors == null || descriptors.isEmpty()) {
                return;
            }
            Map<String, OptionSourceDescriptor> byKey = new LinkedHashMap<>();
            for (OptionSourceDescriptor descriptor : descriptors) {
                if (descriptor == null || descriptor.key() == null || descriptor.key().isBlank()) {
                    continue;
                }
                byKey.put(descriptor.key(), descriptor);
            }
            if (!byKey.isEmpty()) {
                mapped.put(resourceClass, Map.copyOf(byKey));
            }
        });
        return mapped.isEmpty() ? empty() : new OptionSourceRegistry(Map.copyOf(mapped));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static OptionSourceRegistry merge(OptionSourceRegistry... registries) {
        if (registries == null || registries.length == 0) {
            return empty();
        }
        Builder builder = builder();
        for (OptionSourceRegistry registry : registries) {
            if (registry == null || registry.isEmpty()) {
                continue;
            }
            registry.descriptorsByResource.forEach((resourceClass, byKey) ->
                    byKey.values().forEach(descriptor -> builder.add(resourceClass, descriptor)));
        }
        return builder.build();
    }

    public Optional<OptionSourceDescriptor> resolve(Class<?> resourceClass, String sourceKey) {
        if (resourceClass == null || sourceKey == null || sourceKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(descriptorsByResource.getOrDefault(resourceClass, Map.of()).get(sourceKey));
    }

    public Optional<OptionSourceDescriptor> resolveByResourcePathAndField(String resourcePath, String fieldName) {
        if (resourcePath == null || resourcePath.isBlank() || fieldName == null || fieldName.isBlank()) {
            return Optional.empty();
        }
        return descriptorsByResource.values().stream()
                .flatMap(byKey -> byKey.values().stream())
                .filter(descriptor -> resourcePath.equals(descriptor.resourcePath()))
                .filter(descriptor -> fieldName.equals(descriptor.effectiveFilterField()))
                .findFirst();
    }

    public boolean contains(Class<?> resourceClass, String sourceKey) {
        return resolve(resourceClass, sourceKey).isPresent();
    }

    public boolean isEmpty() {
        return descriptorsByResource.isEmpty();
    }

    public static final class Builder {

        private final Map<Class<?>, Map<String, OptionSourceDescriptor>> descriptorsByResource = new LinkedHashMap<>();

        public Builder add(Class<?> resourceClass, OptionSourceDescriptor descriptor) {
            if (resourceClass == null || descriptor == null || descriptor.key() == null || descriptor.key().isBlank()) {
                return this;
            }
            descriptorsByResource
                    .computeIfAbsent(resourceClass, ignored -> new LinkedHashMap<>())
                    .put(descriptor.key(), descriptor);
            return this;
        }

        public OptionSourceRegistry build() {
            if (descriptorsByResource.isEmpty()) {
                return OptionSourceRegistry.empty();
            }
            Map<Class<?>, Collection<OptionSourceDescriptor>> values = new LinkedHashMap<>();
            descriptorsByResource.forEach((resourceClass, byKey) -> values.put(resourceClass, byKey.values()));
            return OptionSourceRegistry.of(values);
        }
    }
}
