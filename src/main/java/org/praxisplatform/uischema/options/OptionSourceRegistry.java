package org.praxisplatform.uischema.options;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registro canônico de option-sources expostas por cada recurso.
 *
 * <p>
 * Option-sources representam superfícies derivadas de opcoes que podem ser consumidas por selects,
 * combos dependentes e outros componentes metadata-driven sem exigir endpoints personalizados por app.
 * Este registry organiza essas fontes por classe de recurso e chave canônica.
 * </p>
 */
public final class OptionSourceRegistry {

    private static final OptionSourceRegistry EMPTY = new OptionSourceRegistry(Map.of());

    private final Map<Class<?>, Map<String, OptionSourceDescriptor>> descriptorsByResource;

    private OptionSourceRegistry(Map<Class<?>, Map<String, OptionSourceDescriptor>> descriptorsByResource) {
        this.descriptorsByResource = descriptorsByResource;
    }

    /**
     * Retorna um registry vazio.
     *
     * @return registry vazio
     */
    public static OptionSourceRegistry empty() {
        return EMPTY;
    }

    /**
     * Cria um registry imutavel a partir de um mapa por recurso.
     *
     * @param descriptorsByResource mapa recurso -> option-sources registradas
     * @return registry resultante
     */
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

    /**
     * Cria um builder para montagem fluente do registry.
     *
     * @return builder do registry
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Combina varios registries em uma unica visao imutavel.
     *
     * @param registries registries a combinar
     * @return registry resultante da fusao
     */
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

    /**
     * Resolve uma option-source por classe de recurso e chave canonica.
     *
     * @param resourceClass classe da entidade/recurso
     * @param sourceKey chave da fonte
     * @return descritor correspondente, quando existir
     */
    public Optional<OptionSourceDescriptor> resolve(Class<?> resourceClass, String sourceKey) {
        if (resourceClass == null || sourceKey == null || sourceKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(descriptorsByResource.getOrDefault(resourceClass, Map.of()).get(sourceKey));
    }

    /**
     * Resolve uma option-source pelo path do recurso e pelo campo efetivo de filtro.
     *
     * <p>
     * Esse metodo e util para enriquecimentos documentais e para superfícies derivadas que partem
     * do contrato HTTP, e nao necessariamente da classe Java do recurso.
     * </p>
     *
     * @param resourcePath path do recurso HTTP
     * @param fieldName campo de filtro efetivo
     * @return descritor correspondente, quando existir
     */
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

    /**
     * Resolve uma option-source pelo path do recurso que hospeda a fonte e pela chave canonica.
     *
     * <p>
     * Esse metodo cobre cenarios em que um schema consumidor referencia uma fonte hospedada em
     * outro recurso, por exemplo um formulario de pedido que usa entidades de fornecedores como
     * lookup remoto.
     * </p>
     *
     * @param resourcePath path do recurso HTTP que hospeda a option-source
     * @param sourceKey chave canonica da fonte
     * @return descritor correspondente, quando existir
     */
    public Optional<OptionSourceDescriptor> resolveByResourcePathAndKey(String resourcePath, String sourceKey) {
        if (resourcePath == null || resourcePath.isBlank() || sourceKey == null || sourceKey.isBlank()) {
            return Optional.empty();
        }
        return descriptorsByResource.values().stream()
                .flatMap(byKey -> byKey.values().stream())
                .filter(descriptor -> resourcePath.equals(descriptor.resourcePath()))
                .filter(descriptor -> sourceKey.equals(descriptor.key()))
                .findFirst();
    }

    /**
     * Verifica se uma chave de option-source esta registrada para o recurso.
     *
     * @param resourceClass classe da entidade/recurso
     * @param sourceKey chave da fonte
     * @return {@code true} quando a fonte existir
     */
    public boolean contains(Class<?> resourceClass, String sourceKey) {
        return resolve(resourceClass, sourceKey).isPresent();
    }

    /**
     * Indica se o registry nao possui fontes registradas.
     *
     * @return {@code true} quando vazio
     */
    public boolean isEmpty() {
        return descriptorsByResource.isEmpty();
    }

    public static final class Builder {

        private final Map<Class<?>, Map<String, OptionSourceDescriptor>> descriptorsByResource = new LinkedHashMap<>();

        /**
         * Adiciona uma option-source ao builder para uma classe de recurso.
         *
         * @param resourceClass classe da entidade/recurso
         * @param descriptor descritor da fonte
         * @return o proprio builder
         */
        public Builder add(Class<?> resourceClass, OptionSourceDescriptor descriptor) {
            if (resourceClass == null || descriptor == null || descriptor.key() == null || descriptor.key().isBlank()) {
                return this;
            }
            descriptorsByResource
                    .computeIfAbsent(resourceClass, ignored -> new LinkedHashMap<>())
                    .put(descriptor.key(), descriptor);
            return this;
        }

        /**
         * Materializa o registry imutavel com as fontes acumuladas.
         *
         * @return registry construido
         */
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
