package org.praxisplatform.uischema.bulk;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Discovers default {@link BulkOperationDescriptorProvider} instances from declarative
 * {@link BulkResourceOperations} controllers paired with {@link AbstractBaseResourceService}
 * (or typed {@link AbstractResourceQueryController}) beans.
 *
 * <p>Explicit host-registered {@link BulkOperationDescriptorProvider} beans always take precedence
 * over auto-discovered providers for the same {@code confirmationOperationId}.</p>
 */
public final class AnnotationDrivenBulkDescriptorDiscovery {

    private static final int DEFAULT_MAX_TARGETS = 200;
    private static final int DEFAULT_MAX_REQUEST_BYTES = 100_000;
    private static final Duration DEFAULT_PROPOSAL_LIFETIME = Duration.ofMinutes(5);
    private static final Duration DEFAULT_UNIT_DEADLINE = Duration.ofSeconds(5);

    private final BulkResourceOperationBindings bindings;
    private final BulkExecutionInfrastructure runtime;
    private final Map<String, AbstractBaseResourceService<?, ?, ?, ?, ?, ?>> servicesByResourceKey;

    public AnnotationDrivenBulkDescriptorDiscovery(
            BulkResourceOperationBindings bindings,
            BulkExecutionInfrastructure runtime,
            Collection<? extends AbstractBaseResourceService<?, ?, ?, ?, ?, ?>> services
    ) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        Map<String, AbstractBaseResourceService<?, ?, ?, ?, ?, ?>> byKey = new LinkedHashMap<>();
        if (services != null) {
            for (AbstractBaseResourceService<?, ?, ?, ?, ?, ?> service : services) {
                if (service != null && service.getResourceKey() != null && !service.getResourceKey().isBlank()) {
                    byKey.putIfAbsent(service.getResourceKey().trim(), service);
                }
            }
        }
        this.servicesByResourceKey = Map.copyOf(byKey);
    }

    /**
     * Generates default {@link BulkOperationDescriptorProvider} metadata for every validated
     * {@link BulkOperationBinding} whose controller is annotated with {@link BulkResourceOperations}
     * and backed by a known {@link AbstractBaseResourceService} (or resolvable resource identity type),
     * skipping any {@code confirmationOperationId} already supplied in {@code explicitProviders}.
     */
    public List<BulkOperationDescriptorProvider> discoverProviders(
            Collection<? extends BulkOperationDescriptorProvider> explicitProviders
    ) {
        Set<String> covered = new java.util.HashSet<>();
        if (explicitProviders != null) {
            for (BulkOperationDescriptorProvider provider : explicitProviders) {
                if (provider != null && provider.confirmationOperationId() != null) {
                    covered.add(provider.confirmationOperationId());
                }
            }
        }
        List<BulkOperationDescriptorProvider> discovered = new ArrayList<>();
        for (BulkOperationBinding binding : bindings.bulkOperations()) {
            if (covered.contains(binding.confirmationOperationId())) {
                continue;
            }
            HandlerMethod confirmation = binding.confirmationHandler();
            Class<?> controllerType = ClassUtils.getUserClass(confirmation.getBeanType());
            if (!AnnotatedElementUtils.hasAnnotation(controllerType, BulkResourceOperations.class)) {
                continue;
            }
            AbstractBaseResourceService<?, ?, ?, ?, ?, ?> service = resolveService(binding.resourceKey(), controllerType);
            if (service == null && !AbstractResourceQueryController.class.isAssignableFrom(controllerType)) {
                continue;
            }
            BulkIdentityCodec<?, ?> codec = service != null
                    ? service.resolveBulkIdentityCodec()
                    : resolveControllerIdentityCodec(controllerType);
            if (codec == null) {
                continue;
            }
            discovered.add(createDefaultProvider(binding, controllerType, codec, runtime));
        }
        return List.copyOf(discovered);
    }

    /**
     * Combines explicit host-owned providers with discovered defaults while guaranteeing explicit
     * host definitions always win per {@code confirmationOperationId}.
     */
    public static List<BulkOperationDescriptorProvider> mergeWithDiscoveredProviders(
            BulkResourceOperationBindings bindings,
            BulkExecutionInfrastructure runtime,
            Collection<? extends AbstractBaseResourceService<?, ?, ?, ?, ?, ?>> services,
            List<BulkOperationDescriptorProvider> explicitProviders
    ) {
        List<BulkOperationDescriptorProvider> safeExplicit = explicitProviders == null ? List.of() : List.copyOf(explicitProviders);
        if (bindings == null || runtime == null) {
            return safeExplicit;
        }
        List<BulkOperationDescriptorProvider> discovered =
                new AnnotationDrivenBulkDescriptorDiscovery(bindings, runtime, services)
                        .discoverProviders(safeExplicit);
        if (discovered.isEmpty()) {
            return safeExplicit;
        }
        List<BulkOperationDescriptorProvider> merged = new ArrayList<>(safeExplicit.size() + discovered.size());
        merged.addAll(safeExplicit);
        merged.addAll(discovered);
        return List.copyOf(merged);
    }

    private AbstractBaseResourceService<?, ?, ?, ?, ?, ?> resolveService(String resourceKey, Class<?> controllerType) {
        AbstractBaseResourceService<?, ?, ?, ?, ?, ?> byKey = servicesByResourceKey.get(resourceKey);
        if (byKey != null) {
            return byKey;
        }
        ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(controllerType, ApiResource.class);
        if (apiResource != null && !apiResource.resourceKey().isBlank()) {
            return servicesByResourceKey.get(apiResource.resourceKey().trim());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static BulkIdentityCodec<?, ?> resolveControllerIdentityCodec(Class<?> controllerType) {
        Class<?> idClass = ResolvableType.forClass(controllerType)
                .as(AbstractResourceQueryController.class)
                .getGeneric(1)
                .resolve();
        if (idClass == null || idClass == Object.class) {
            return null;
        }
        if (idClass == Integer.class) {
            return BulkIdentityCodecs.integers();
        }
        if (idClass == Long.class) {
            return BulkIdentityCodecs.longs();
        }
        if (idClass == String.class) {
            return BulkIdentityCodecs.strings();
        }
        if (idClass == UUID.class) {
            return BulkIdentityCodecs.uuids();
        }
        if (CompositeIdSupport.isCompositeIdType(idClass)) {
            return BulkIdentityCodecs.composite((Class<Object>) idClass);
        }
        return null;
    }

    private static BulkOperationDescriptorProvider createDefaultProvider(
            BulkOperationBinding binding,
            Class<?> controllerType,
            BulkIdentityCodec<?, ?> codec,
            BulkExecutionInfrastructure runtime
    ) {
        String providerId = "praxis-annotation:" + binding.resourceKey() + ":"
                + binding.mode().name().toLowerCase(Locale.ROOT);
        String revision = computeProviderRevision(binding, controllerType, codec);
        String schemaPointer = binding.mode() == BulkMode.PER_ITEM_UPDATE
                ? "/properties/items/items/properties/id"
                : "/properties/selection/properties/targets/items/properties/id";
        Set<BulkSelectionMode> selectionModes = binding.mode() == BulkMode.UNIFORM_UPDATE
                ? EnumSet.of(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY)
                : EnumSet.of(BulkSelectionMode.EXPLICIT);
        BulkOperationalProfile profile = new BulkOperationalProfile(
                EnumSet.of(binding.mode()),
                EnumSet.of(BulkExecutionMode.SYNC),
                selectionModes,
                DEFAULT_MAX_TARGETS,
                DEFAULT_MAX_REQUEST_BYTES,
                DEFAULT_PROPOSAL_LIFETIME,
                DEFAULT_UNIT_DEADLINE
        );
        return new BulkOperationDescriptorProvider() {
            @Override
            public String confirmationOperationId() {
                return binding.confirmationOperationId();
            }

            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public String providerRevision() {
                return revision;
            }

            @Override
            public BulkIdentityCodec<?, ?> identityCodec() {
                return codec;
            }

            @Override
            public String identitySchemaPointer() {
                return schemaPointer;
            }

            @Override
            public BulkOperationalProfile profile() {
                return profile;
            }

            @Override
            public BulkExecutionInfrastructure executionInfrastructure() {
                return runtime;
            }
        };
    }

    private static String computeProviderRevision(
            BulkOperationBinding binding,
            Class<?> controllerType,
            BulkIdentityCodec<?, ?> codec
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder payload = new StringBuilder();
            payload.append("annotation-v1|")
                    .append(controllerType.getName()).append('|')
                    .append(binding.resourceKey()).append('|')
                    .append(binding.confirmationOperationId()).append('|')
                    .append(binding.evaluationOperationId()).append('|')
                    .append(binding.mode().name()).append('|')
                    .append(binding.atomicity().name()).append('|')
                    .append(binding.updateSourceOperationId().orElse("")).append('|')
                    .append(codec.codecId());
            binding.protectedUpdateFields().stream().sorted()
                    .forEach(field -> payload.append('|').append(field));
            byte[] hash = digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            return "annotation-v1:" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
