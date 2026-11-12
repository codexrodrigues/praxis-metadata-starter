package org.praxisplatform.uischema.options;

/**
 * Modular slice contributor for {@link OptionSourceDescriptor}s aggregated into the canonical
 * {@link OptionSourceRegistry}.
 *
 * <p>Allows slices or domain modules to contribute option sources without overriding the
 * auto-configured {@link OptionSourceRegistry} bean or disabling per-service
 * {@code getDeclaredOptionSourceRegistry()} discovery.</p>
 */
@FunctionalInterface
public interface OptionSourceRegistryContributor {

    /**
     * Contributes option-source descriptors into the shared registry builder.
     *
     * @param builder shared option source registry builder
     */
    void contribute(OptionSourceRegistry.Builder builder);
}
