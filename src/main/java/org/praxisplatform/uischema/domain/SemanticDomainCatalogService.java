package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.uischema.customfields.CustomFieldDescriptor;
import org.praxisplatform.uischema.customfields.CustomFieldDefinitionProvider;
import org.praxisplatform.uischema.customfields.ResourceTenantContextResolver;
import org.praxisplatform.uischema.fact.ResourceFactCatalogProvider;
import org.praxisplatform.uischema.fact.ResourceFactDescriptor;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupSelectionPolicy;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.FilteredSchemaProjection;
import org.praxisplatform.uischema.surface.RelatedResourceSurface;
import org.praxisplatform.uischema.surface.SurfaceDefinition;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Gera o vocabulario semantico inicial a partir das superficies metadata-driven ja existentes.
 *
 * <p>
 * Esta primeira versao deriva dominio de actions e surfaces anotadas. Ela nao interpreta services
 * nem executa regras; apenas materializa conceitos, estados, operacoes e bindings explicaveis.
 * </p>
 */
public class SemanticDomainCatalogService {
    private static final String DOMAIN_GOVERNANCE_EXTENSION = "x-domain-governance";
    private static final ObjectMapper HASH_OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final SchemaCanonicalizer HASH_CANONICALIZER = new SchemaCanonicalizer();

    public static final String SCHEMA_VERSION = "praxis.domain-catalog/v0.2";

    private final ActionDefinitionRegistry actionDefinitionRegistry;
    private final SurfaceDefinitionRegistry surfaceDefinitionRegistry;
    private final OptionSourceRegistry optionSourceRegistry;
    private final OpenApiDocumentService openApiDocumentService;
    private final CanonicalCapabilityResolver canonicalCapabilityResolver;
    private final CustomFieldDefinitionProvider customFieldDefinitionProvider;
    private final ResourceFactCatalogProvider resourceFactCatalogProvider;
    private final ResourceTenantContextResolver tenantContextResolver;
    private final DomainRuleSetCatalogProvider domainRuleSetCatalogProvider;
    private final ReactiveDeterminationCatalogSource determinationCatalogSource;
    private final org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver apiResourceQuickFilterResolver;
    private final Clock clock;
    private final String serviceKey;
    private final String serviceName;
    private final String serviceVersion;
    private final ConcurrentMap<String, DomainCatalogResponse> catalogCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> strictSchemaResolution = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public SemanticDomainCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry,
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            OptionSourceRegistry optionSourceRegistry,
            OpenApiDocumentService openApiDocumentService,
            Clock clock,
            String serviceKey,
            String serviceName,
            String serviceVersion
    ) {
        this(
                actionDefinitionRegistry,
                surfaceDefinitionRegistry,
                optionSourceRegistry,
                openApiDocumentService,
                null,
                clock,
                serviceKey,
                serviceName,
                serviceVersion
        );
    }

    public SemanticDomainCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry,
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            OptionSourceRegistry optionSourceRegistry,
            OpenApiDocumentService openApiDocumentService,
            CanonicalCapabilityResolver canonicalCapabilityResolver,
            Clock clock,
            String serviceKey,
            String serviceName,
            String serviceVersion
    ) {
        this(actionDefinitionRegistry, surfaceDefinitionRegistry, optionSourceRegistry, openApiDocumentService,
                canonicalCapabilityResolver, clock, serviceKey, serviceName, serviceVersion, null, null, null, null);
    }

    public SemanticDomainCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry, SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            OptionSourceRegistry optionSourceRegistry, OpenApiDocumentService openApiDocumentService,
            CanonicalCapabilityResolver canonicalCapabilityResolver, Clock clock,
            String serviceKey, String serviceName, String serviceVersion,
            CustomFieldDefinitionProvider customFieldDefinitionProvider,
            ResourceFactCatalogProvider resourceFactCatalogProvider,
            ResourceTenantContextResolver tenantContextResolver
    ) {
        this(
                actionDefinitionRegistry,
                surfaceDefinitionRegistry,
                optionSourceRegistry,
                openApiDocumentService,
                canonicalCapabilityResolver,
                clock,
                serviceKey,
                serviceName,
                serviceVersion,
                customFieldDefinitionProvider,
                resourceFactCatalogProvider,
                tenantContextResolver,
                null
        );
    }

    public SemanticDomainCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry, SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            OptionSourceRegistry optionSourceRegistry, OpenApiDocumentService openApiDocumentService,
            CanonicalCapabilityResolver canonicalCapabilityResolver, Clock clock,
            String serviceKey, String serviceName, String serviceVersion,
            CustomFieldDefinitionProvider customFieldDefinitionProvider,
            ResourceFactCatalogProvider resourceFactCatalogProvider,
            ResourceTenantContextResolver tenantContextResolver,
            DomainRuleSetCatalogProvider domainRuleSetCatalogProvider
    ) {
        this(actionDefinitionRegistry, surfaceDefinitionRegistry, optionSourceRegistry, openApiDocumentService,
                canonicalCapabilityResolver, clock, serviceKey, serviceName, serviceVersion,
                customFieldDefinitionProvider, resourceFactCatalogProvider, tenantContextResolver,
                domainRuleSetCatalogProvider, null);
    }

    public SemanticDomainCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry, SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            OptionSourceRegistry optionSourceRegistry, OpenApiDocumentService openApiDocumentService,
            CanonicalCapabilityResolver canonicalCapabilityResolver, Clock clock,
            String serviceKey, String serviceName, String serviceVersion,
            CustomFieldDefinitionProvider customFieldDefinitionProvider,
            ResourceFactCatalogProvider resourceFactCatalogProvider,
            ResourceTenantContextResolver tenantContextResolver,
            DomainRuleSetCatalogProvider domainRuleSetCatalogProvider,
            ReactiveDeterminationCatalogSource determinationCatalogSource
    ) {
        this(actionDefinitionRegistry, surfaceDefinitionRegistry, optionSourceRegistry, openApiDocumentService,
                canonicalCapabilityResolver, clock, serviceKey, serviceName, serviceVersion,
                customFieldDefinitionProvider, resourceFactCatalogProvider, tenantContextResolver,
                domainRuleSetCatalogProvider, determinationCatalogSource, null);
    }

    public SemanticDomainCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry, SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            OptionSourceRegistry optionSourceRegistry, OpenApiDocumentService openApiDocumentService,
            CanonicalCapabilityResolver canonicalCapabilityResolver, Clock clock,
            String serviceKey, String serviceName, String serviceVersion,
            CustomFieldDefinitionProvider customFieldDefinitionProvider,
            ResourceFactCatalogProvider resourceFactCatalogProvider,
            ResourceTenantContextResolver tenantContextResolver,
            DomainRuleSetCatalogProvider domainRuleSetCatalogProvider,
            ReactiveDeterminationCatalogSource determinationCatalogSource,
            org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver apiResourceQuickFilterResolver
    ) {
        this.determinationCatalogSource = determinationCatalogSource;
        this.apiResourceQuickFilterResolver = apiResourceQuickFilterResolver;
        this.customFieldDefinitionProvider = customFieldDefinitionProvider;
        this.resourceFactCatalogProvider = resourceFactCatalogProvider;
        this.tenantContextResolver = tenantContextResolver;
        this.domainRuleSetCatalogProvider = domainRuleSetCatalogProvider;
        this.actionDefinitionRegistry = actionDefinitionRegistry;
        this.surfaceDefinitionRegistry = surfaceDefinitionRegistry;
        this.optionSourceRegistry = optionSourceRegistry == null ? OptionSourceRegistry.empty() : optionSourceRegistry;
        this.openApiDocumentService = openApiDocumentService;
        this.canonicalCapabilityResolver = canonicalCapabilityResolver;
        this.clock = clock;
        this.serviceKey = serviceKey;
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
    }

    public DomainCatalogResponse findByResourceKey(String resourceKey) {
        return findCatalog(resourceKey, null);
    }

    public DomainCatalogResponse findByGroup(String group) {
        return findCatalog(null, group);
    }

    /**
     * Builds the semantic domain catalog for a resource in strict mode: requires an
     * {@link OpenApiDocumentService}, resolves group documents via
     * {@link OpenApiDocumentService#getDocumentForGroupStrict(String)} without fallback, and
     * fails closed if any declared canonical schema cannot be resolved.
     */
    public DomainCatalogResponse findByResourceKeyStrict(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            throw new IllegalStateException("Explicit resourceKey is required for strict domain catalog resolution");
        }
        return findCatalogStrict(resourceKey.trim(), null);
    }

    /**
     * Builds the semantic domain catalog for an OpenAPI group in strict mode: requires an
     * {@link OpenApiDocumentService}, resolves group documents via
     * {@link OpenApiDocumentService#getDocumentForGroupStrict(String)} without fallback, and
     * fails closed if any declared canonical schema cannot be resolved.
     */
    public DomainCatalogResponse findByGroupStrict(String group) {
        if (!StringUtils.hasText(group)) {
            throw new IllegalStateException("Explicit OpenAPI group is required for strict domain catalog resolution");
        }
        return findCatalogStrict(null, group.trim());
    }

    private DomainCatalogResponse findCatalogStrict(String resourceKey, String group) {
        if (openApiDocumentService == null) {
            throw new IllegalStateException("OpenApiDocumentService is required for strict domain catalog resolution");
        }
        return openApiDocumentService.withSchemaCacheReadLock(() -> {
            Boolean previous = strictSchemaResolution.get();
            strictSchemaResolution.set(Boolean.TRUE);
            try {
                return buildCatalogUncached(resourceKey, group);
            } finally {
                strictSchemaResolution.set(previous);
            }
        });
    }

    private DomainCatalogResponse findCatalog(String resourceKey, String group) {
        return openApiDocumentService == null ? findCatalogWithStableCache(resourceKey, group)
                : openApiDocumentService.withSchemaCacheReadLock(() -> findCatalogWithStableCache(resourceKey, group));
    }

    private DomainCatalogResponse findCatalogWithStableCache(String resourceKey, String group) {
        String tenant = tenantContextResolver == null ? null : tenantContextResolver.resolveTenant().orElse(null);
        List<ReactiveDeterminationCatalogSource.CompiledForm> forms = determinationCatalogSource == null
                ? List.of() : determinationCatalogSource.find(resourceKey, group);
        String baseKey = resourceKey != null ? "resource:" + resourceKey : "group:" + firstText(group, "");
        if (tenant == null
                && customFieldDefinitionProvider == null
                && resourceFactCatalogProvider == null
                && domainRuleSetCatalogProvider == null
                && determinationCatalogSource == null
                && apiResourceQuickFilterResolver == null) {
            return catalogCache.computeIfAbsent(baseKey, ignored -> build(resourceKey, group,
                    resourceKey != null ? safe(actionDefinitionRegistry.findByResourceKey(resourceKey)) : safe(actionDefinitionRegistry.findByGroup(group)),
                    resourceKey != null ? safe(surfaceDefinitionRegistry.findByResourceKey(resourceKey)) : safe(surfaceDefinitionRegistry.findByGroup(group)),
                    Map.of(), Map.of(), Map.of(), List.of(), Map.of()));
        }
        List<ActionDefinition> actions = resourceKey != null ? safe(actionDefinitionRegistry.findByResourceKey(resourceKey)) : safe(actionDefinitionRegistry.findByGroup(group));
        List<SurfaceDefinition> surfaces = resourceKey != null ? safe(surfaceDefinitionRegistry.findByResourceKey(resourceKey)) : safe(surfaceDefinitionRegistry.findByGroup(group));
        Set<String> resources = new java.util.TreeSet<>();
        if (StringUtils.hasText(resourceKey)) resources.add(resourceKey);
        actions.forEach(a -> resources.add(a.resourceKey()));
        surfaces.forEach(s -> resources.add(s.resourceKey()));
        forms.forEach(form -> resources.add(form.resourceKey()));
        Map<String, List<CustomFieldDescriptor>> fields = new LinkedHashMap<>();
        Map<String, List<ResourceFactDescriptor>> facts = new LinkedHashMap<>();
        Map<String, List<DomainRuleSetDescriptor>> ruleSets = new LinkedHashMap<>();
        for (String resource : resources) {
            if (customFieldDefinitionProvider != null) {
                List<CustomFieldDescriptor> definitions = safe(customFieldDefinitionProvider.findDefinitions(resource, tenant));
                Set<String> keys = new LinkedHashSet<>();
                for (CustomFieldDescriptor field : definitions) {
                    if (!keys.add(field.key())) throw new IllegalStateException("Duplicate custom field for resource " + resource);
                }
                if (!definitions.isEmpty()) fields.put(resource, definitions.stream().sorted(Comparator.comparing(CustomFieldDescriptor::key)).toList());
            }
            if (resourceFactCatalogProvider != null) {
                List<ResourceFactDescriptor> definitions = safe(resourceFactCatalogProvider.findFacts(resource, tenant));
                Set<String> keys = new LinkedHashSet<>();
                for (ResourceFactDescriptor fact : definitions) {
                    if (!resource.equals(fact.resourceKey()) || !keys.add(fact.key()))
                        throw new IllegalStateException("Invalid or duplicate fact for resource " + resource);
                }
                if (!definitions.isEmpty()) facts.put(resource, definitions.stream().sorted(Comparator.comparing(ResourceFactDescriptor::key)).toList());
            }
            if (domainRuleSetCatalogProvider != null) {
                List<DomainRuleSetDescriptor> definitions = safe(domainRuleSetCatalogProvider.findRuleSets(resource, tenant));
                Set<String> keys = new LinkedHashSet<>();
                for (DomainRuleSetDescriptor ruleSet : definitions) {
                    if (ruleSet == null
                            || !resource.equals(ruleSet.resourceKey())
                            || !keys.add(ruleSet.ruleSetKey() + "::" + ruleSet.operationKey())) {
                        throw new IllegalStateException("Invalid or duplicate rule set for resource " + resource);
                    }
                }
                if (!definitions.isEmpty()) {
                    ruleSets.put(resource, definitions.stream()
                            .sorted(Comparator.comparing(DomainRuleSetDescriptor::ruleSetKey)
                                    .thenComparing(DomainRuleSetDescriptor::operationKey))
                            .toList());
                }
            }
        }
        Map<String, List<Map<String, Object>>> quickFilters = collectQuickFiltersByResource(resources);
        String cacheKey = baseKey;
        if (StringUtils.hasText(tenant) || !fields.isEmpty() || !facts.isEmpty() || !ruleSets.isEmpty()
                || !forms.isEmpty() || !quickFilters.isEmpty()) {
            cacheKey += ":extensions:" + SchemaHashUtil.sha256Hex(HASH_CANONICALIZER.canonicalize(
                    HASH_OBJECT_MAPPER.valueToTree(mapOfNonNull(
                            "tenant", tenant,
                            "fields", fields,
                            "facts", facts,
                            "ruleSets", ruleSetHashSummaries(ruleSets),
                            "determinationForms", forms,
                            "quickFilters", quickFilters
                    ))));
        }
        return catalogCache.computeIfAbsent(cacheKey, ignored -> build(
                resourceKey, group, actions, surfaces, fields, facts, ruleSets, forms, quickFilters));
    }

    private DomainCatalogResponse buildCatalogUncached(String resourceKey, String group) {
        boolean strict = Boolean.TRUE.equals(strictSchemaResolution.get());
        if (strict && StringUtils.hasText(group)) {
            resolveOpenApiDocument(group);
        }
        String tenant = tenantContextResolver == null ? null : tenantContextResolver.resolveTenant().orElse(null);
        List<ReactiveDeterminationCatalogSource.CompiledForm> forms = determinationCatalogSource == null
                ? List.of() : determinationCatalogSource.find(resourceKey, group);
        List<ActionDefinition> actions = resourceKey != null
                ? safe(actionDefinitionRegistry.findByResourceKey(resourceKey))
                : safe(actionDefinitionRegistry.findByGroup(group));
        List<SurfaceDefinition> surfaces = resourceKey != null
                ? safe(surfaceDefinitionRegistry.findByResourceKey(resourceKey))
                : safe(surfaceDefinitionRegistry.findByGroup(group));
        if (strict && actions.isEmpty() && surfaces.isEmpty() && forms.isEmpty()) {
            throw new IllegalStateException(
                    "Strict domain catalog found no registered actions, surfaces, or determination forms for scope: "
                            + firstText(resourceKey, group));
        }
        Set<String> resources = new java.util.TreeSet<>();
        if (StringUtils.hasText(resourceKey)) resources.add(resourceKey);
        actions.forEach(a -> resources.add(a.resourceKey()));
        surfaces.forEach(s -> resources.add(s.resourceKey()));
        forms.forEach(form -> resources.add(form.resourceKey()));
        Map<String, List<CustomFieldDescriptor>> fields = new LinkedHashMap<>();
        Map<String, List<ResourceFactDescriptor>> facts = new LinkedHashMap<>();
        Map<String, List<DomainRuleSetDescriptor>> ruleSets = new LinkedHashMap<>();
        for (String resource : resources) {
            if (customFieldDefinitionProvider != null) {
                List<CustomFieldDescriptor> definitions = safe(customFieldDefinitionProvider.findDefinitions(resource, tenant));
                Set<String> keys = new LinkedHashSet<>();
                for (CustomFieldDescriptor field : definitions) {
                    if (!keys.add(field.key())) throw new IllegalStateException("Duplicate custom field for resource " + resource);
                }
                if (!definitions.isEmpty()) fields.put(resource, definitions.stream().sorted(Comparator.comparing(CustomFieldDescriptor::key)).toList());
            }
            if (resourceFactCatalogProvider != null) {
                List<ResourceFactDescriptor> definitions = safe(resourceFactCatalogProvider.findFacts(resource, tenant));
                Set<String> keys = new LinkedHashSet<>();
                for (ResourceFactDescriptor fact : definitions) {
                    if (!resource.equals(fact.resourceKey()) || !keys.add(fact.key()))
                        throw new IllegalStateException("Invalid or duplicate fact for resource " + resource);
                }
                if (!definitions.isEmpty()) facts.put(resource, definitions.stream().sorted(Comparator.comparing(ResourceFactDescriptor::key)).toList());
            }
            if (domainRuleSetCatalogProvider != null) {
                List<DomainRuleSetDescriptor> definitions = safe(domainRuleSetCatalogProvider.findRuleSets(resource, tenant));
                Set<String> keys = new LinkedHashSet<>();
                for (DomainRuleSetDescriptor ruleSet : definitions) {
                    if (ruleSet == null
                            || !resource.equals(ruleSet.resourceKey())
                            || !keys.add(ruleSet.ruleSetKey() + "::" + ruleSet.operationKey())) {
                        throw new IllegalStateException("Invalid or duplicate rule set for resource " + resource);
                    }
                }
                if (!definitions.isEmpty()) {
                    ruleSets.put(resource, definitions.stream()
                            .sorted(Comparator.comparing(DomainRuleSetDescriptor::ruleSetKey)
                                    .thenComparing(DomainRuleSetDescriptor::operationKey))
                            .toList());
                }
            }
        }
        Map<String, List<Map<String, Object>>> quickFilters = collectQuickFiltersByResource(resources);
        return build(resourceKey, group, actions, surfaces, fields, facts, ruleSets, forms, quickFilters);
    }

    private Map<String, List<Map<String, Object>>> collectQuickFiltersByResource(Set<String> resources) {
        if (apiResourceQuickFilterResolver == null || resources == null || resources.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String resource : resources) {
            List<Map<String, Object>> resolved = safe(apiResourceQuickFilterResolver.resolveByResourceKey(resource));
            if (!resolved.isEmpty()) {
                result.put(resource, List.copyOf(resolved));
            }
        }
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    private DomainCatalogResponse build(
            String requestedResourceKey,
            String requestedGroup,
            List<ActionDefinition> actions,
            List<SurfaceDefinition> surfaces,
            Map<String, List<CustomFieldDescriptor>> customFields,
            Map<String, List<ResourceFactDescriptor>> resourceFacts,
            Map<String, List<DomainRuleSetDescriptor>> domainRuleSets,
            List<ReactiveDeterminationCatalogSource.CompiledForm> forms,
            Map<String, List<Map<String, Object>>> quickFiltersByResource
    ) {
        Map<String, DomainCatalogResponse.DomainContextItem> contexts = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainNodeItem> nodes = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainEdgeItem> edges = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainBindingItem> bindings = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainGovernanceItem> governance = new LinkedHashMap<>();
        Map<String, String> resourceDescriptions = resourceDescriptions(actions, surfaces);
        Map<String, String> canonicalResourceKeysByPath = canonicalResourceKeysByPath(actions, surfaces);
        Map<String, Map<String, Boolean>> capabilitiesByResourcePath = new LinkedHashMap<>();
        Set<String> analyticCapabilitiesAdded = new LinkedHashSet<>();

        for (ActionDefinition action : sortActions(actions)) {
            String resourceKey = action.resourceKey();
            String contextKey = contextKey(resourceKey, action.group());
            ensureContext(contexts, contextKey, action.group());
            ensureConcept(nodes, resourceKey, contextKey, action.resourcePath(), action.group(), action.tags(),
                    resourceDescriptions.get(resourceKey), capabilitiesByResourcePath);
            addAnalyticCapabilities(action.resourceKey(), contextKey, action.group(), action.resourcePath(),
                    nodes, edges, bindings, evidence, capabilitiesByResourcePath, analyticCapabilitiesAdded);
            addAction(action, contextKey, nodes, edges, bindings, evidence);
            addSchemaFields(action.resourceKey(), contextKey, action.group(), action.requestSchema(), "request",
                    canonicalResourceKeysByPath, nodes, edges, bindings, evidence, governance);
            addSchemaFields(action.resourceKey(), contextKey, action.group(), action.responseSchema(), "response",
                    canonicalResourceKeysByPath, nodes, edges, bindings, evidence, governance);
        }

        for (SurfaceDefinition surface : sortSurfaces(surfaces)) {
            String resourceKey = surface.resourceKey();
            String contextKey = contextKey(resourceKey, surface.group());
            ensureContext(contexts, contextKey, surface.group());
            ensureConcept(nodes, resourceKey, contextKey, surface.resourcePath(), surface.group(), surface.tags(),
                    resourceDescriptions.get(resourceKey), capabilitiesByResourcePath);
            addAnalyticCapabilities(surface.resourceKey(), contextKey, surface.group(), surface.resourcePath(),
                    nodes, edges, bindings, evidence, capabilitiesByResourcePath, analyticCapabilitiesAdded);
            addSurface(surface, contextKey, nodes, edges, bindings, evidence);
            addSchemaFields(surface.resourceKey(), contextKey, surface.group(), surface.schema(), surface.schemaType(),
                    canonicalResourceKeysByPath, nodes, edges, bindings, evidence, governance);
        }

        for (OptionSourceDescriptor descriptor : optionSourceRegistry.descriptors()) {
            String resourceKey = resourceKeyForPath(descriptor.resourcePath(), canonicalResourceKeysByPath);
            if (!matchesRequestedScope(resourceKey, requestedResourceKey, requestedGroup)) {
                continue;
            }
            String effectiveGroup = firstText(
                    requestedGroup,
                    openApiDocumentService != null
                            ? openApiDocumentService.resolveGroupFromPath(descriptor.resourcePath())
                            : null
            );
            String contextKey = contextKey(resourceKey, effectiveGroup);
            ensureContext(contexts, contextKey, effectiveGroup);
            ensureConcept(nodes, resourceKey, contextKey, descriptor.resourcePath(), effectiveGroup,
                    List.of("option-source"), null, capabilitiesByResourcePath);
            addAnalyticCapabilities(resourceKey, contextKey, effectiveGroup, descriptor.resourcePath(),
                    nodes, edges, bindings, evidence, capabilitiesByResourcePath, analyticCapabilitiesAdded);
            addOptionSource(descriptor, resourceKey, contextKey, nodes, edges, bindings, evidence);
        }

        addDeterminations(forms, contexts, nodes, edges, bindings, evidence, governance,
                canonicalResourceKeysByPath, capabilitiesByResourcePath);

        Set<String> extendedResources = new LinkedHashSet<>(customFields.keySet());
        extendedResources.addAll(resourceFacts.keySet());
        extendedResources.addAll(domainRuleSets.keySet());
        extendedResources.addAll(quickFiltersByResource.keySet());
        for (String resource : extendedResources) {
            String context = nodes.containsKey(resource) ? nodes.get(resource).contextKey() : contextKey(resource, requestedGroup);
            String quickFilterPath = apiResourceQuickFilterResolver != null
                    ? apiResourceQuickFilterResolver.resolveResourcePathByResourceKey(resource).orElse(null)
                    : null;
            ensureContext(contexts, context, requestedGroup);
            ensureConcept(nodes, resource, context, quickFilterPath, requestedGroup, List.of(), null, capabilitiesByResourcePath);
            for (CustomFieldDescriptor field : customFields.getOrDefault(resource, List.of())) {
                String fieldName = "customFields." + field.key();
                addExtension(resource, context, resource + ".field." + fieldName, "field", "custom_field",
                        firstText(field.label(), field.key()), field.description(),
                        mapOfNonNull("fieldName", fieldName, "resourceKey", resource, "dataType", field.dataType(),
                                "required", field.required(), "readOnly", field.readOnly(), "filterable", field.filterable(),
                                "sortable", field.sortable(), "exportable", field.exportable()),
                        field.governance(), nodes, edges, bindings, evidence, governance);
            }
            for (ResourceFactDescriptor fact : resourceFacts.getOrDefault(resource, List.of())) {
                addExtension(resource, context, resource + ".fact." + fact.key(), "fact", "resource_fact",
                        firstText(fact.label(), fact.key()), fact.description(),
                        mapOfNonNull("resourceKey", resource, "factPath", "facts." + fact.key(), "dataType", fact.dataType(),
                                "unit", fact.unit(), "icon", blankToNull(fact.icon()),
                                "tone", blankToNull(fact.tone()), "valuePresentation", blankToNull(fact.valuePresentation()),
                                "thresholds", fact.thresholds() == null || fact.thresholds().isEmpty() ? null : fact.thresholds(),
                                "parameters", fact.parameters(), "deterministic", fact.deterministic(),
                                "sourceKind", fact.sourceKind(), "authority", fact.authority(),
                                "evaluationPhase", fact.evaluationPhase(), "missingValuePolicy", fact.missingValuePolicy(),
                                "scope", fact.scope(), "clientOverridable", Boolean.FALSE),
                        fact.governance(), nodes, edges, bindings, evidence, governance);
            }
            for (DomainRuleSetDescriptor ruleSet : domainRuleSets.getOrDefault(resource, List.of())) {
                addRuleSet(resource, context, ruleSet, contexts, nodes, edges, bindings, evidence);
            }
            for (Map<String, Object> quickFilter : quickFiltersByResource.getOrDefault(resource, List.of())) {
                addQuickFilter(resource, context, quickFilterPath, quickFilter, nodes, edges, bindings, evidence);
            }
        }

        synthesizeLineageGraph(nodes, edges, bindings, governance);
        enrichConceptKnowledgeGraphMetadata(contexts, nodes, edges, requestedGroup, capabilitiesByResourcePath);
        List<DomainCatalogResponse.DomainContextItem> contextItems = List.copyOf(contexts.values());
        List<DomainCatalogResponse.DomainNodeItem> nodeItems = List.copyOf(nodes.values());
        List<DomainCatalogResponse.DomainEdgeItem> edgeItems = List.copyOf(edges.values());
        List<DomainCatalogResponse.DomainBindingItem> bindingItems = List.copyOf(bindings.values());
        List<DomainCatalogResponse.DomainAliasItem> aliasItems = buildAliases(nodes);
        List<DomainCatalogResponse.DomainEvidenceItem> evidenceItems = List.copyOf(evidence.values());
        List<DomainCatalogResponse.DomainGovernanceItem> governanceItems = List.copyOf(governance.values());
        String sourceHash = sourceHash(
                requestedResourceKey,
                requestedGroup,
                contextItems,
                nodeItems,
                edgeItems,
                bindingItems,
                aliasItems,
                evidenceItems,
                governanceItems
        );
        Instant generatedAt = Instant.now(clock);
        return DomainCatalogSemanticValidator.validate(new DomainCatalogResponse(
                SCHEMA_VERSION,
                new DomainCatalogResponse.DomainServiceInfo(serviceKey, serviceName, blankToNull(serviceVersion)),
                new DomainCatalogResponse.DomainReleaseInfo(
                        releaseKey(requestedResourceKey, requestedGroup, sourceHash),
                        generatedAt.toString(),
                        sourceHash
                ),
                blankToNull(requestedResourceKey),
                contextItems,
                nodeItems,
                edgeItems,
                bindingItems,
                aliasItems,
                evidenceItems,
                governanceItems
        ));
    }

    private String exactKey(String... parts) {
        return SchemaHashUtil.sha256Hex(HASH_CANONICALIZER.canonicalize(HASH_OBJECT_MAPPER.valueToTree(parts)));
    }

    private void ensureConceptReference(Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            String key, ConceptRootBinding identity, String evidenceKey) {
        DomainCatalogResponse.DomainNodeItem existing = nodes.get(key);
        if (existing != null) {
            if (!"concept".equals(existing.nodeType()) || !identity.serviceKey().equals(existing.metadata().get("ownerServiceKey"))
                    || !identity.contextKey().equals(existing.contextKey()))
                throw new IllegalStateException("Ambiguous concept reference identity: " + key);
            return;
        }
        nodes.put(key, new DomainCatalogResponse.DomainNodeItem(key, identity.contextKey(), "concept",
                labelFromKey(lastSegment(identity.resourceKey())), null, "active", "concept-reference", 1.0,
                mapOfNonNull("projectionRole", "REFERENCE", "ownerServiceKey", identity.serviceKey(),
                        "resourceKey", identity.resourceKey(), "contextKey", identity.contextKey()),
                List.of("referenced-concept"), identity.serviceKey(), identity.contextKey(), "active", Map.of(),
                resolution(key, List.of(identity.resourceKey()), "exact-key"),
                evidenceKey == null ? List.of() : List.of(evidenceKey)));
    }

    private void addDeterminations(List<ReactiveDeterminationCatalogSource.CompiledForm> forms,
            Map<String, DomainCatalogResponse.DomainContextItem> contexts,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence,
            Map<String, DomainCatalogResponse.DomainGovernanceItem> governance,
            Map<String, String> canonicalResourceKeysByPath,
            Map<String, Map<String, Boolean>> capabilitiesByResourcePath) {
        for (var form : forms) {
            String resource = form.resourceKey(), context = contextKey(resource, form.operation().group());
            ensureContext(contexts, context, form.operation().group());
            ensureConcept(nodes, resource, context, null, form.operation().group(), List.of(), null, capabilitiesByResourcePath);
            addSchemaFields(resource, context, form.operation().group(), form.schema(), "request",
                    canonicalResourceKeysByPath, nodes, edges, bindings, evidence, governance);
            for (var determination : form.determinations()) {
                JsonNode compiled = HASH_OBJECT_MAPPER.valueToTree(determination);
                String id = compiled.path("id").asText(), mode = compiled.at("/scope/formMode").asText();
                String key = "determination:" + exactKey(serviceKey, resource, form.operation().operationId(), mode, id);
                String proof = "evidence:" + key;
                Map<String, Object> metadata = mapOfNonNull("operationKind", "reactive_determination",
                        "resourceKey", resource, "ownerServiceKey", serviceKey, "reactiveDetermination", determination);
                nodes.put(key, new DomainCatalogResponse.DomainNodeItem(key, context, "operation", id,
                        "Determinação reativa do formulário " + form.operation().operationId(), "active",
                        "reactive-determination", 1.0, metadata, List.of("reactive-determination"), serviceKey,
                        context, "active", Map.of(), resolution(key, List.of(id), "exact-key"), List.of(proof)));
                putEdge(edges, resource + ":governed-by:" + key, resource, key,
                        "governed_by", "Determinação " + id, 1.0, List.of(proof));
                @SuppressWarnings("unchecked") Map<String,Object> capability = (Map<String,Object>) determination.get("capability");
                bindings.put(key + ":api", new DomainCatalogResponse.DomainBindingItem(key + ":api", key,
                        "api_operation", capability, List.of(), List.of(proof)));
                bindings.put(key + ":form", new DomainCatalogResponse.DomainBindingItem(key + ":form", key,
                        "form_config", mapOfNonNull("schemaOperationId", form.operation().operationId(), "formMode", mode,
                                "schemaUrl", form.schema().url(), "configurationPath", "/x-ui/reactiveDeterminations",
                                "determinationId", id, "configuration", determination),
                        List.of(new DomainCatalogResponse.DomainSchemaLink("form", form.schema().url(),
                                form.schema().schemaId(), "request")), List.of(proof)));
                evidence.put(proof, evidence(proof, "inference", mapOfNonNull("kind", "reactive-determination",
                        "resourceKey", resource, "schemaOperationId", form.operation().operationId(), "formMode", mode,
                        "determinationId", id, "provenance", determination.get("provenance")),
                        "Projeção da saída validada de ReactiveDeterminationMetadataCompiler", 1.0));
                for (String side : List.of("inputs", "outputs")) for (JsonNode binding : compiled.path(side)) {
                    String pointer = binding.path("fieldPath").asText();
                    String fieldKey = "form-field:" + exactKey(serviceKey, resource, form.operation().operationId(), mode, pointer);
                    JsonNode shape = form.fields().get(pointer);
                    if (shape == null) throw new IllegalStateException("Missing compiled field evidence: " + pointer);
                    nodes.putIfAbsent(fieldKey, new DomainCatalogResponse.DomainNodeItem(fieldKey, context, "field", pointer,
                            shape.path("description").asText(null), "active", "dto_schema", 1.0,
                            mapOfNonNull("resourceKey", resource, "fieldPath", pointer, "schemaOperationId", form.operation().operationId(),
                                    "formMode", mode, "schemaId", form.schema().schemaId(), "schemaUrl", form.schema().url()),
                            List.of("form-field"), serviceKey, context, "active", Map.of(),
                            resolution(fieldKey, List.of(pointer), "exact-key"), List.of(proof)));
                    var protection = classifyFieldGovernance(fieldKey, pointer, shape, form.schema(), "request");
                    if (protection != null) mergeFieldGovernance(governance, protection);
                    boolean input = side.equals("inputs");
                    putEdge(edges, key + ":" + side + ":" + exactKey(pointer), input ? key : fieldKey, input ? fieldKey : key,
                            input ? "depends_on" : "governed_by", pointer, 1.0, List.of(proof));
                }
            }
        }
    }

    private void addExtension(String resource, String context, String nodeKey, String type, String source,
            String label, String description, Map<String, Object> metadata, Map<String, Object> definitionGovernance,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence,
            Map<String, DomainCatalogResponse.DomainGovernanceItem> governance) {
        String evidenceKey = "evidence:" + nodeKey + ":" + source;
        metadata.put("sourceType", source);
        nodes.put(nodeKey, new DomainCatalogResponse.DomainNodeItem(nodeKey, context, type, label, description,
                "active", source, 1.0, metadata, List.of(source), null, context, "active",
                glossary(label, description, List.of(source)),
                resolution(nodeKey, matchKeys(
                        label,
                        metadata.get("factPath") instanceof String fp ? fp : null,
                        metadata.get("fieldName") instanceof String fn ? fn : null
                ), "exact-key"),
                List.of(evidenceKey)));
        putEdge(edges, resource + "." + type + ":" + nodeKey, resource, nodeKey,
                "fact".equals(type) ? "provides_fact" : "has_field", label, 1.0, List.of(evidenceKey));
        bindings.put("binding:" + nodeKey, new DomainCatalogResponse.DomainBindingItem("binding:" + nodeKey,
                nodeKey, "fact".equals(type) ? "resource_fact" : "dto_field", metadata, List.of(), List.of(evidenceKey)));
        evidence.put(evidenceKey, evidence(evidenceKey, source,
                mapOfNonNull("resourceKey", resource, "kind", source), description, 1.0));
        JsonNode declaration = HASH_OBJECT_MAPPER.valueToTree(definitionGovernance == null ? Map.of() : definitionGovernance);
        DomainCatalogResponse.DomainGovernanceItem explicitGovernance =
                explicitGovernance(nodeKey, declaration, null, null, source, false);
        if (explicitGovernance != null) governance.put(explicitGovernance.governanceKey(), explicitGovernance);
    }

    private void addQuickFilter(
            String resource,
            String context,
            String resourcePath,
            Map<String, Object> quickFilter,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence
    ) {
        if (quickFilter == null || !(quickFilter.get("id") instanceof String rawId) || rawId.isBlank()) {
            return;
        }
        String id = rawId.trim();
        String label = quickFilter.get("label") instanceof String l && !l.isBlank() ? l.trim() : id;
        String description = quickFilter.get("description") instanceof String d && !d.isBlank() ? d.trim() : null;
        String icon = quickFilter.get("icon") instanceof String i && !i.isBlank() ? i.trim() : null;
        String tone = quickFilter.get("tone") instanceof String t && !t.isBlank() ? t.trim() : null;
        Boolean isDefault = Boolean.TRUE.equals(quickFilter.get("isDefault")) ? Boolean.TRUE : null;
        Integer order = quickFilter.get("order") instanceof Number n && n.intValue() != 0 ? n.intValue() : null;
        Object filterObj = quickFilter.get("filter");
        List<String> filterFields = new ArrayList<>();
        if (filterObj instanceof Map<?, ?> filterMap) {
            for (Object key : filterMap.keySet()) {
                if (key != null && !String.valueOf(key).isBlank()) {
                    filterFields.add(String.valueOf(key).trim());
                }
            }
        }

        String nodeKey = resource + ".quickFilter." + keyPart(id);
        String evidenceKey = "evidence:" + nodeKey + ":quick-filter";
        Map<String, Object> metadata = mapOfNonNull(
                "sourceType", "quick_filter",
                "kind", "quick_filter",
                "resourceKey", resource,
                "resourcePath", blankToNull(resourcePath),
                "quickFilterId", id,
                "filter", filterObj,
                "filterFields", filterFields.isEmpty() ? null : List.copyOf(filterFields),
                "icon", icon,
                "tone", tone,
                "isDefault", isDefault,
                "order", order
        );
        nodes.putIfAbsent(nodeKey, new DomainCatalogResponse.DomainNodeItem(
                nodeKey,
                context,
                "quick_filter",
                label,
                description,
                "active",
                "quick_filter",
                1.0,
                metadata,
                List.of("quick-filter"),
                null,
                context,
                "active",
                glossary(label, description, List.of("quick-filter")),
                resolution(nodeKey, matchKeys(id, label), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));
        putEdge(edges, resource + ".has-quick-filter." + keyPart(id), resource, nodeKey,
                "has_quick_filter", "Possui filtro rápido " + label, 1.0, List.of(evidenceKey));
        for (String fieldName : filterFields) {
            String fieldNodeKey = resource + ".field." + keyPart(fieldName);
            if (nodes.containsKey(fieldNodeKey)) {
                putEdge(edges, nodeKey + ".filters-field." + keyPart(fieldName), nodeKey, fieldNodeKey,
                        "filters_field", "Filtra por " + fieldName, 1.0, List.of(evidenceKey));
            }
        }
        bindings.putIfAbsent("binding:" + nodeKey + ":quick-filter", new DomainCatalogResponse.DomainBindingItem(
                "binding:" + nodeKey + ":quick-filter",
                nodeKey,
                "quick_filter",
                metadata,
                List.of(),
                List.of(evidenceKey)
        ));
        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "annotation",
                mapOfNonNull(
                        "kind", "quick_filter",
                        "annotation", "QuickFilter",
                        "resourceKey", resource,
                        "quickFilterId", id
                ),
                firstText(description, "Filtro rápido operacional declarado para o recurso " + resource + "."),
                1.0
        ));
    }

    private void addRuleSet(
            String resourceKey,
            String contextKey,
            DomainRuleSetDescriptor descriptor,
            Map<String, DomainCatalogResponse.DomainContextItem> contexts,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence
    ) {
        RuleSetDefinition definition = descriptor.ruleSetDefinition();
        RuleSetRef ref = definition.ref();
        String ruleSetKey = descriptor.ruleSetKey();
        String baseRuleKeyPart = exactKey(serviceKey, resourceKey, ruleSetKey, descriptor.operationKey());
        String policyNodeKey = "ruleset:" + baseRuleKeyPart;
        String evidenceKey = "evidence:" + policyNodeKey + ":rule-set";

        List<String> availableRoots = List.copyOf(descriptor.availableRoots());
        List<String> conceptRoots = List.copyOf(descriptor.conceptRoots());
        List<String> referencedFactPaths = List.copyOf(descriptor.referencedFactPaths());
        Map<String, List<String>> factPathsByRoot = descriptor.factPathsByRoot();
        Map<String, DomainRuleSetDescriptor.RuleReasonDescriptor> reasonsByCode = descriptor.reasonDescriptors();
        Map<String, DecisionSlot> slotsByKey = new LinkedHashMap<>();
        List<Map<String, Object>> slotItems = new ArrayList<>();
        Set<String> reasonCodes = new LinkedHashSet<>();
        Set<String> targetFields = new LinkedHashSet<>();

        for (DecisionSlot slot : safe(definition.slots())) {
            slotsByKey.put(slot.slotKey(), slot);
            slotItems.add(mapOfNonNull(
                    "slotKey", slot.slotKey(),
                    "stage", slot.stage() != null ? slot.stage().name() : null,
                    "cardinality", slot.cardinality() != null ? slot.cardinality().name() : null,
                    "overridePolicy", slot.overridePolicy() != null ? slot.overridePolicy().name() : null,
                    "aggregationPolicy", slot.aggregationPolicy() != null ? slot.aggregationPolicy().name() : null,
                    "requiredControlDependencies", slot.requiredControlDependencies(),
                    "parameterizable", slot.parameterTemplate() != null ? Boolean.TRUE : null,
                    "templateKey", slot.parameterTemplate() != null ? blankToNull(slot.parameterTemplate().templateKey()) : null,
                    "templateReasonCode", slot.parameterTemplate() != null ? blankToNull(slot.parameterTemplate().falseReasonCode()) : null
            ));
        }

        if (safe(definition.bindings()).isEmpty()) {
            for (DomainRuleSetDescriptor.RuleReasonDescriptor reason : reasonsByCode.values()) {
                reasonCodes.add(reason.code());
                if (StringUtils.hasText(reason.targetField())) {
                    targetFields.add(reason.targetField());
                }
            }
        }

        for (String factPath : referencedFactPaths) {
            if (factPath.startsWith(PraxisFactTreeBuilder.ROOT_DRAFT + ".")) {
                String candidate = factPath.substring((PraxisFactTreeBuilder.ROOT_DRAFT + ".").length());
                if (nodes.containsKey(resourceKey + ".field." + keyPart(candidate))) {
                    targetFields.add(candidate);
                }
            } else if (factPath.startsWith(PraxisFactTreeBuilder.ROOT_CURRENT + ".")) {
                String candidate = factPath.substring((PraxisFactTreeBuilder.ROOT_CURRENT + ".").length());
                if (nodes.containsKey(resourceKey + ".field." + keyPart(candidate))) {
                    targetFields.add(candidate);
                }
            }
        }

        List<Map<String, Object>> ruleItems = new ArrayList<>();
        for (DecisionBinding binding : safe(definition.bindings())) {
            DecisionSlot slot = slotsByKey.get(binding.slotKey());
            String codeCandidate = blankToNull(binding.falseReasonCode());
            if (codeCandidate == null && slot != null && slot.parameterTemplate() != null) {
                codeCandidate = blankToNull(slot.parameterTemplate().falseReasonCode());
            }
            if (binding.enabled() && codeCandidate != null) {
                reasonCodes.add(codeCandidate);
            }
            DomainRuleSetDescriptor.RuleReasonDescriptor matchedReason = codeCandidate != null
                    ? reasonsByCode.get(codeCandidate)
                    : reasonsByCode.get(binding.bindingKey());
            if (matchedReason == null) {
                for (DomainRuleSetDescriptor.RuleReasonDescriptor candidate : reasonsByCode.values()) {
                    if (binding.slotKey().equals(candidate.slotKey())) {
                        matchedReason = candidate;
                        break;
                    }
                }
            }
            // Java bindings have no JSON Logic falseReasonCode. Their host-declared
            // reasons belong to the active slot and must also reach the public index.
            if (binding.enabled() && binding.executor() != null
                    && binding.executor().type() == org.praxisplatform.rules.contract.RuleExecutorType.JAVA) {
                for (DomainRuleSetDescriptor.RuleReasonDescriptor reason : reasonsByCode.values()) {
                    if (binding.slotKey().equals(reason.slotKey()) || binding.bindingKey().equals(reason.code())) {
                        reasonCodes.add(reason.code());
                        if (StringUtils.hasText(reason.targetField())) {
                            targetFields.add(reason.targetField());
                        }
                    }
                }
            }
            if (binding.enabled() && matchedReason != null && StringUtils.hasText(matchedReason.targetField())) {
                targetFields.add(matchedReason.targetField());
            }
            String effectiveFalseDecision = binding.falseDecision() != null
                    ? binding.falseDecision().name()
                    : (slot != null && slot.parameterTemplate() != null && slot.parameterTemplate().falseDecision() != null
                            ? slot.parameterTemplate().falseDecision().name()
                            : null);
            List<String> effectiveRequiredFactPaths = !safe(binding.requiredFactPaths()).isEmpty()
                    ? List.copyOf(safe(binding.requiredFactPaths()))
                    : (slot != null && slot.parameterTemplate() != null
                            ? List.copyOf(safe(slot.parameterTemplate().requiredFactPaths()))
                            : List.of());
            JsonNode effectiveExpression = binding.executor() != null && binding.executor().expression() != null
                    ? binding.executor().expression()
                    : (slot != null && slot.parameterTemplate() != null && slot.parameterTemplate().executor() != null
                            ? slot.parameterTemplate().executor().expression()
                            : null);
            Set<String> bindingReferencedPaths = new LinkedHashSet<>(
                    PraxisFactTreeBuilder.extractReferencedFactPaths(effectiveExpression));
            for (String reqPath : effectiveRequiredFactPaths) {
                if (reqPath != null && reqPath.contains(".")) {
                    bindingReferencedPaths.add(reqPath);
                }
            }
            ruleItems.add(mapOfNonNull(
                    "bindingKey", binding.bindingKey(),
                    "slotKey", binding.slotKey(),
                    "source", binding.source() != null ? binding.source().name() : null,
                    "compositionPolicy", binding.compositionPolicy() != null ? binding.compositionPolicy().name() : null,
                    "order", binding.order(),
                    "enabled", binding.enabled(),
                    "parameterized", binding.parameterization() != null ? Boolean.TRUE : null,
                    "falseDecision", effectiveFalseDecision,
                    "reasonCode", matchedReason != null ? matchedReason.code() : codeCandidate,
                    "targetField", matchedReason != null ? matchedReason.targetField() : null,
                    "message", matchedReason != null ? matchedReason.message() : null,
                    "origin", matchedReason != null ? matchedReason.origin() : null,
                    "lineage", matchedReason != null ? matchedReason.lineage() : null,
                    "requiredFactPaths", effectiveRequiredFactPaths.isEmpty() ? null : effectiveRequiredFactPaths,
                    "referencedFactPaths", bindingReferencedPaths.isEmpty() ? null : List.copyOf(bindingReferencedPaths)
            ));
        }

        Map<String, Object> metadata = mapOfNonNull(
                "policyKind", "domain_rule_set",
                "resourceKey", resourceKey,
                "domainKey", ref != null ? blankToNull(ref.domainKey()) : null,
                "boundedContextKey", ref != null ? blankToNull(ref.boundedContextKey()) : null,
                "ruleSetKey", ruleSetKey,
                "operationKey", descriptor.operationKey(),
                "version", ref != null ? Integer.valueOf(ref.version()) : null,
                "failPolicy", definition.failPolicy() != null ? definition.failPolicy().name() : null,
                "availableRoots", availableRoots.isEmpty() ? null : availableRoots,
                "conceptRoots", conceptRoots.isEmpty() ? null : conceptRoots,
                "conceptRootBindings", descriptor.conceptRootBindings(),
                "referencedFactPaths", referencedFactPaths.isEmpty() ? null : referencedFactPaths,
                "factPathsByRoot", factPathsByRoot.isEmpty() ? null : factPathsByRoot,
                "slotCount", slotItems.size(),
                "bindingCount", ruleItems.size(),
                "slots", slotItems.isEmpty() ? null : List.copyOf(slotItems),
                "rules", ruleItems.isEmpty() ? null : List.copyOf(ruleItems),
                "reasonCodes", reasonCodes.isEmpty() ? null : List.copyOf(reasonCodes),
                "targetFields", targetFields.isEmpty() ? null : List.copyOf(targetFields)
        );

        for (var conceptEntry : descriptor.conceptRootBindings().entrySet()) {
            String root = conceptEntry.getKey();
            ConceptRootBinding identity = conceptEntry.getValue();
            String target = serviceKey.equals(identity.serviceKey()) ? identity.resourceKey()
                    : "concept:" + exactKey(identity.serviceKey(), identity.contextKey(), identity.resourceKey());
            ensureContext(contexts, identity.contextKey(), null);
            ensureConceptReference(nodes, target, identity, evidenceKey);
            putEdge(edges, policyNodeKey + ".root." + root, policyNodeKey, target,
                    "references", identity.role(), 1.0, List.of(evidenceKey));
        }

        List<String> tags = new ArrayList<>(List.of("rule-set", "domain-rules", "shacl-shape"));
        if (StringUtils.hasText(descriptor.operationKey())) {
            tags.add(descriptor.operationKey().toLowerCase(Locale.ROOT));
        }

        nodes.putIfAbsent(policyNodeKey, new DomainCatalogResponse.DomainNodeItem(
                policyNodeKey,
                contextKey,
                "policy_hint",
                descriptor.label(),
                descriptor.description(),
                "active",
                "rule-set",
                0.98,
                metadata,
                List.copyOf(tags),
                null,
                contextKey,
                "active",
                glossary(descriptor.label(), descriptor.description(), tags),
                resolution(policyNodeKey, matchKeys(ruleSetKey, descriptor.label(), descriptor.operationKey()), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));

        putEdge(edges, resourceKey + ".governed-by." + baseRuleKeyPart, resourceKey, policyNodeKey,
                "governed_by", "Governado por RuleSet " + ruleSetKey, 0.98, List.of(evidenceKey));

        for (String targetField : targetFields) {
            String fieldNodeKey = resourceKey + ".field." + keyPart(targetField);
            if (nodes.containsKey(fieldNodeKey)) {
                putEdge(edges, fieldNodeKey + ".governed-by." + baseRuleKeyPart, fieldNodeKey, policyNodeKey,
                        "governed_by", "Campo " + targetField + " governado por RuleSet " + ruleSetKey, 0.96, List.of(evidenceKey));
            }
        }

        bindings.putIfAbsent("binding:" + policyNodeKey + ":rule-set", new DomainCatalogResponse.DomainBindingItem(
                "binding:" + policyNodeKey + ":rule-set",
                policyNodeKey,
                "rule_definition",
                mapOfNonNull(
                        "resourceKey", resourceKey,
                        "domainKey", ref != null ? blankToNull(ref.domainKey()) : null,
                        "boundedContextKey", ref != null ? blankToNull(ref.boundedContextKey()) : null,
                        "ruleSetKey", ruleSetKey,
                        "operationKey", descriptor.operationKey(),
                        "version", ref != null ? Integer.valueOf(ref.version()) : null,
                        "failPolicy", definition.failPolicy() != null ? definition.failPolicy().name() : null,
                        "availableRoots", availableRoots.isEmpty() ? null : availableRoots,
                        "conceptRoots", conceptRoots.isEmpty() ? null : conceptRoots,
                "conceptRootBindings", descriptor.conceptRootBindings(),
                        "referencedFactPaths", referencedFactPaths.isEmpty() ? null : referencedFactPaths,
                        "factPathsByRoot", factPathsByRoot.isEmpty() ? null : factPathsByRoot,
                        "reasonCodes", reasonCodes.isEmpty() ? null : List.copyOf(reasonCodes),
                        "targetFields", targetFields.isEmpty() ? null : List.copyOf(targetFields)
                ),
                List.of(),
                List.of(evidenceKey)
        ));

        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "inference",
                mapOfNonNull(
                        "kind", "praxis.rules.ruleset",
                        "resourceKey", resourceKey,
                        "domainKey", ref != null ? blankToNull(ref.domainKey()) : null,
                        "boundedContextKey", ref != null ? blankToNull(ref.boundedContextKey()) : null,
                        "ruleSetKey", ruleSetKey,
                        "operationKey", descriptor.operationKey(),
                        "version", ref != null ? Integer.valueOf(ref.version()) : null
                ),
                "Política determinística derivada de RuleSetDefinition (" + ruleSetKey + ") para o recurso " + resourceKey + ".",
                0.98
        ));
    }

    private Map<String, Object> ruleSetHashSummaries(Map<String, List<DomainRuleSetDescriptor>> ruleSets) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Map.Entry<String, List<DomainRuleSetDescriptor>> entry : ruleSets.entrySet()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (DomainRuleSetDescriptor descriptor : entry.getValue()) {
                RuleSetDefinition def = descriptor.ruleSetDefinition();
                items.add(mapOfNonNull(
                        "ruleSetKey", descriptor.ruleSetKey(),
                        "operationKey", descriptor.operationKey(),
                        "version", def.ref() != null ? def.ref().version() : null,
                        "availableRoots", List.copyOf(descriptor.availableRoots()),
                        "conceptRoots", List.copyOf(descriptor.conceptRoots()),
                        "conceptRootBindings", descriptor.conceptRootBindings(),
                        "referencedFactPaths", List.copyOf(descriptor.referencedFactPaths()),
                        "factPathsByRoot", descriptor.factPathsByRoot(),
                        "reasonDescriptors", descriptor.reasonDescriptors()
                ));
            }
            summary.put(entry.getKey(), List.copyOf(items));
        }
        return Map.copyOf(summary);
    }

    private void addAction(
            ActionDefinition action,
            String contextKey,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence
    ) {
        String resourceNodeKey = action.resourceKey();
        String actionNodeKey = resourceNodeKey + ".action." + keyPart(action.id());
        String evidenceKey = "evidence:" + actionNodeKey + ":workflow-action";

        nodes.putIfAbsent(actionNodeKey, new DomainCatalogResponse.DomainNodeItem(
                actionNodeKey,
                contextKey,
                "action",
                firstText(action.title(), action.id()),
                action.description(),
                "active",
                "workflow-action",
                0.95,
                mapOfNonNull(
                        "actionId", action.id(),
                        "scope", action.scope() != null ? action.scope().name() : null,
                        "resourceKey", action.resourceKey(),
                        "legalPurpose", blankToNull(action.legalPurpose()),
                        "legalBasisRef", blankToNull(action.legalBasisRef())
                ),
                nonNullList(action.tags()),
                null,
                action.group(),
                "active",
                glossary(firstText(action.title(), action.id()), action.description(), action.tags()),
                resolution(actionNodeKey, matchKeys(action.id(), action.title()), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));

        putEdge(edges, resourceNodeKey + ".has-action." + keyPart(action.id()), resourceNodeKey, actionNodeKey,
                "has_action", "Possui action " + firstText(action.title(), action.id()), 0.95, List.of(evidenceKey));

        for (String allowedState : nonNullList(action.allowedStates())) {
            String stateNodeKey = stateNodeKey(resourceNodeKey, allowedState);
            ensureState(nodes, stateNodeKey, contextKey, allowedState, "workflow-action.allowedStates", evidenceKey);
            putEdge(edges, resourceNodeKey + ".has-state." + keyPart(allowedState), resourceNodeKey, stateNodeKey,
                    "has_state", "Possui estado " + allowedState, 0.95, List.of(evidenceKey));
            putEdge(edges, actionNodeKey + ".allowed-state." + keyPart(allowedState), actionNodeKey, stateNodeKey,
                    "allowed_in_state", "Permitida no estado " + allowedState, 0.95, List.of(evidenceKey));
        }

        Map<String, Object> actionTarget = new LinkedHashMap<>(
                operationTarget(action.id(), action.resourceKey(), action.resourcePath(), action.operation())
        );
        if (StringUtils.hasText(action.legalPurpose())) {
            actionTarget.put("legalPurpose", action.legalPurpose().trim());
        }
        if (StringUtils.hasText(action.legalBasisRef())) {
            actionTarget.put("legalBasisRef", action.legalBasisRef().trim());
        }

        bindings.putIfAbsent("binding:" + actionNodeKey + ":workflow-action", new DomainCatalogResponse.DomainBindingItem(
                "binding:" + actionNodeKey + ":workflow-action",
                actionNodeKey,
                "workflow_action",
                Map.copyOf(actionTarget),
                schemaLinks(action.requestSchema(), action.responseSchema()),
                List.of(evidenceKey)
        ));

        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "annotation",
                mapOfNonNull(
                        "kind", "java.annotation",
                        "annotation", "WorkflowAction",
                        "actionId", action.id(),
                        "legalPurpose", blankToNull(action.legalPurpose()),
                        "legalBasisRef", blankToNull(action.legalBasisRef())
                ),
                "Action derivada de @WorkflowAction para o recurso " + action.resourceKey() + ".",
                0.98
        ));
    }

    private void addSurface(
            SurfaceDefinition surface,
            String contextKey,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence
    ) {
        String resourceNodeKey = surface.resourceKey();
        String surfaceNodeKey = resourceNodeKey + ".surface." + keyPart(surface.id());
        String evidenceKey = "evidence:" + surfaceNodeKey + ":ui-surface";
        RelatedResourceSurface relatedResource = surface.relatedResource();
        boolean hasRelatedChild = relatedResource != null
                && relatedResource.present()
                && StringUtils.hasText(relatedResource.childResourceKey());
        String relatedChildKey = hasRelatedChild ? relatedResource.childResourceKey().trim() : null;

        Map<String, Object> surfaceMetadata = mapOfNonNull(
                "surfaceId", surface.id(),
                "kind", surface.kind() != null ? surface.kind().name() : null,
                "scope", surface.scope() != null ? surface.scope().name() : null,
                "intent", surface.intent(),
                "resourceKey", surface.resourceKey(),
                "relatedChildResourceKey", relatedChildKey,
                "relatedChildResourcePath", hasRelatedChild ? blankToNull(relatedResource.childResourcePath()) : null,
                "relatedChildParentField", hasRelatedChild ? blankToNull(relatedResource.childParentField()) : null,
                "legalPurpose", blankToNull(surface.legalPurpose()),
                "legalBasisRef", blankToNull(surface.legalBasisRef())
        );

        nodes.putIfAbsent(surfaceNodeKey, new DomainCatalogResponse.DomainNodeItem(
                surfaceNodeKey,
                contextKey,
                "surface",
                firstText(surface.title(), surface.id()),
                surface.description(),
                "active",
                "ui-surface",
                0.95,
                surfaceMetadata,
                nonNullList(surface.tags()),
                null,
                surface.group(),
                "active",
                glossary(firstText(surface.title(), surface.id()), surface.description(), surface.tags()),
                resolution(surfaceNodeKey, matchKeys(surface.id(), surface.title(), surface.intent()), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));

        putEdge(edges, resourceNodeKey + ".has-surface." + keyPart(surface.id()), resourceNodeKey, surfaceNodeKey,
                "has_surface", "Possui surface " + firstText(surface.title(), surface.id()), 0.95, List.of(evidenceKey));

        if (hasRelatedChild) {
            putEdge(edges, surfaceNodeKey + ".references-child." + keyPart(relatedChildKey), surfaceNodeKey, relatedChildKey,
                    "references", "Expõe coleção relacionada " + relatedChildKey, 0.95, List.of(evidenceKey));
            if (!relatedChildKey.equals(resourceNodeKey)) {
                putEdge(edges, resourceNodeKey + ".references-child." + keyPart(relatedChildKey), resourceNodeKey, relatedChildKey,
                        "references", "Possui coleção relacionada " + relatedChildKey + " via surface " + firstText(surface.title(), surface.id()),
                        0.95, List.of(evidenceKey));
            }
        }

        for (String allowedState : nonNullList(surface.allowedStates())) {
            String stateNodeKey = stateNodeKey(resourceNodeKey, allowedState);
            ensureState(nodes, stateNodeKey, contextKey, allowedState, "ui-surface.allowedStates", evidenceKey);
            putEdge(edges, resourceNodeKey + ".has-state." + keyPart(allowedState), resourceNodeKey, stateNodeKey,
                    "has_state", "Possui estado " + allowedState, 0.95, List.of(evidenceKey));
            putEdge(edges, surfaceNodeKey + ".allowed-state." + keyPart(allowedState), surfaceNodeKey, stateNodeKey,
                    "allowed_in_state", "Disponivel no estado " + allowedState, 0.95, List.of(evidenceKey));
        }

        Map<String, Object> target = new LinkedHashMap<>(
                operationTarget(surface.id(), surface.resourceKey(), surface.resourcePath(), surface.operation())
        );
        if (hasRelatedChild) {
            target.put("relatedChildResourceKey", relatedChildKey);
            if (StringUtils.hasText(relatedResource.childResourcePath())) {
                target.put("relatedChildResourcePath", relatedResource.childResourcePath().trim());
            }
            if (StringUtils.hasText(relatedResource.childParentField())) {
                target.put("relatedChildParentField", relatedResource.childParentField().trim());
            }
        }
        if (StringUtils.hasText(surface.legalPurpose())) {
            target.put("legalPurpose", surface.legalPurpose().trim());
        }
        if (StringUtils.hasText(surface.legalBasisRef())) {
            target.put("legalBasisRef", surface.legalBasisRef().trim());
        }

        bindings.putIfAbsent("binding:" + surfaceNodeKey + ":ui-surface", new DomainCatalogResponse.DomainBindingItem(
                "binding:" + surfaceNodeKey + ":ui-surface",
                surfaceNodeKey,
                "ui_surface",
                Map.copyOf(target),
                schemaLinks(surface.schema()),
                List.of(evidenceKey)
        ));

        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "annotation",
                mapOfNonNull(
                        "kind", "java.annotation",
                        "annotation", "UiSurface",
                        "surfaceId", surface.id(),
                        "relatedChildResourceKey", relatedChildKey,
                        "legalPurpose", blankToNull(surface.legalPurpose()),
                        "legalBasisRef", blankToNull(surface.legalBasisRef())
                ),
                "Surface derivada de @UiSurface para o recurso " + surface.resourceKey() + ".",
                0.98
        ));
    }

    private void addOptionSource(
            OptionSourceDescriptor descriptor,
            String resourceKey,
            String contextKey,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence
    ) {
        String policyNodeKey = resourceKey + ".policy." + keyPart(descriptor.key()) + ".selection";
        String evidenceKey = "evidence:" + policyNodeKey + ":option-source";
        EntityLookupDescriptor entityLookup = descriptor.entityLookup();
        LookupSelectionPolicy selectionPolicy = entityLookup != null ? entityLookup.selectionPolicy() : null;

        nodes.putIfAbsent(policyNodeKey, new DomainCatalogResponse.DomainNodeItem(
                policyNodeKey,
                contextKey,
                "policy_hint",
                "Politica de selecao " + descriptor.key(),
                "Politica documental de selecao publicada por option-source.",
                "active",
                "option-source",
                0.9,
                descriptor.toMetadataMap(),
                List.of("option-source", "lookup", "selection-policy"),
                null,
                contextKey,
                "active",
                glossary("Politica de selecao " + descriptor.key(), "Politica documental de selecao publicada por option-source.", List.of("option-source", "selection-policy")),
                resolution(policyNodeKey, matchKeys(descriptor.key(), descriptor.resourcePath()), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));

        if (selectionPolicy != null && !selectionPolicy.allowedStatuses().isEmpty()) {
            putEdge(edges, resourceKey + ".selectable-when." + keyPart(descriptor.key()), resourceKey, policyNodeKey,
                    "selectable_when", "Selecionavel quando atende a politica da option-source", 0.9, List.of(evidenceKey));
        }
        if (selectionPolicy != null && !selectionPolicy.blockedStatuses().isEmpty()) {
            putEdge(edges, resourceKey + ".blocked-when." + keyPart(descriptor.key()), resourceKey, policyNodeKey,
                    "blocked_when", "Bloqueado quando atende a politica de bloqueio da option-source", 0.9, List.of(evidenceKey));
        }
        if (selectionPolicy == null || selectionPolicy.isEmpty()) {
            putEdge(edges, resourceKey + ".uses-option-source." + keyPart(descriptor.key()), resourceKey, policyNodeKey,
                    "governed_by", "Política da option-source " + descriptor.key(), 0.85, List.of(evidenceKey));
        }

        bindings.putIfAbsent("binding:" + policyNodeKey + ":option-source", new DomainCatalogResponse.DomainBindingItem(
                "binding:" + policyNodeKey + ":option-source",
                policyNodeKey,
                "option_source",
                mapOfNonNull(
                        "key", descriptor.key(),
                        "type", descriptor.type() != null ? descriptor.type().name() : null,
                        "resourcePath", descriptor.resourcePath(),
                        "filterField", descriptor.effectiveFilterField(),
                        "entityKey", entityLookup != null ? entityLookup.entityKey() : null
                ),
                List.of(),
                List.of(evidenceKey)
        ));

        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "option_source",
                mapOfNonNull(
                        "kind", "option-source",
                        "key", descriptor.key(),
                        "resourcePath", descriptor.resourcePath()
                ),
                "Politica derivada de OptionSourceDescriptor para " + descriptor.resourcePath() + ".",
                0.95
        ));
    }

    private void addAnalyticCapabilities(
            String resourceKey,
            String contextKey,
            String group,
            String resourcePath,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence,
            Map<String, Map<String, Boolean>> capabilitiesByResourcePath,
            Set<String> analyticCapabilitiesAdded
    ) {
        if (!StringUtils.hasText(resourceKey) || !StringUtils.hasText(resourcePath) || openApiDocumentService == null) {
            return;
        }
        String normalizedResourcePath = firstText(normalizeResourcePath(resourcePath), resourcePath.trim());
        if (!analyticCapabilitiesAdded.add(resourceKey + "::" + normalizedResourcePath)) {
            return;
        }
        JsonNode paths = openApiPaths(group, resourcePath);
        if (paths == null || !paths.isObject()) {
            return;
        }
        Map<String, Boolean> canonicalCapabilities = resolveResourceCapabilities(
                group,
                resourcePath,
                capabilitiesByResourcePath
        );
        Iterator<Map.Entry<String, JsonNode>> entries = paths.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String path = entry.getKey();
            String capabilityKind = statsCapabilityKind(resourcePath, path);
            if (StringUtils.hasText(capabilityKind) && isStatsCapabilitySupported(canonicalCapabilities, capabilityKind)) {
                String method = firstText(firstMethod(entry.getValue()), "POST");
                String nodeKey = resourceKey + ".stats." + keyPart(capabilityKind);
                String evidenceKey = "evidence:" + nodeKey + ":openapi-stats";
                nodes.putIfAbsent(nodeKey, new DomainCatalogResponse.DomainNodeItem(
                        nodeKey,
                        contextKey,
                        "stats",
                        labelFromKey(capabilityKind),
                        "Capability analitica publicada pelo endpoint " + path + ".",
                        "active",
                        "openapi-stats",
                        0.9,
                        mapOfNonNull(
                                "capabilityKey", "stats." + capabilityKind,
                                "resourceKey", resourceKey,
                                "resourcePath", resourcePath,
                                "path", path,
                                "method", method,
                                "group", group
                        ),
                        List.of("stats", "analytics", capabilityKind),
                        null,
                        contextKey,
                        "active",
                        glossary(labelFromKey(capabilityKind), "Capability analitica publicada pelo endpoint " + path + ".", List.of("stats", "analytics")),
                        resolution(nodeKey, matchKeys(capabilityKind, "stats", path), "exact-key-or-alias"),
                        List.of(evidenceKey)
                ));
                putEdge(edges, resourceKey + ".has-stats." + keyPart(capabilityKind), resourceKey, nodeKey,
                        "has_stats", "Possui capability analitica " + labelFromKey(capabilityKind), 0.9, List.of(evidenceKey));
                bindings.putIfAbsent("binding:" + nodeKey + ":openapi-stats", new DomainCatalogResponse.DomainBindingItem(
                        "binding:" + nodeKey + ":openapi-stats",
                        nodeKey,
                        "stats_endpoint",
                        mapOfNonNull(
                                "capabilityKey", "stats." + capabilityKind,
                                "resourceKey", resourceKey,
                                "resourcePath", resourcePath,
                                "path", path,
                                "method", method,
                                "group", group
                        ),
                        List.of(),
                        List.of(evidenceKey)
                ));
                evidence.putIfAbsent(evidenceKey, evidence(
                        evidenceKey,
                        "openapi_stats",
                        mapOfNonNull(
                                "kind", "openapi.operation",
                                "capabilityKey", "stats." + capabilityKind,
                                "resourceKey", resourceKey,
                                "path", path,
                                "method", method
                        ),
                        "Capability stats derivada do endpoint OpenAPI " + path + ".",
                        0.95
                ));
            }
            addUiAnalyticsProjectionsFromPathItem(
                    resourceKey,
                    contextKey,
                    group,
                    resourcePath,
                    normalizedResourcePath,
                    path,
                    entry.getValue(),
                    nodes,
                    edges,
                    bindings,
                    evidence
            );
        }
    }

    private void addUiAnalyticsProjectionsFromPathItem(
            String resourceKey,
            String contextKey,
            String group,
            String resourcePath,
            String normalizedResourcePath,
            String path,
            JsonNode pathItem,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence
    ) {
        if (pathItem == null || !pathItem.isObject()) {
            return;
        }
        String normalizedPath = normalizeResourcePath(path);
        if (!StringUtils.hasText(normalizedPath)
                || !(normalizedPath.equals(normalizedResourcePath)
                || normalizedPath.startsWith(normalizedResourcePath + "/"))) {
            return;
        }
        for (String method : List.of("post", "get", "put", "patch", "delete")) {
            JsonNode operation = pathItem.path(method);
            if (!operation.isObject()) {
                continue;
            }
            JsonNode projections = operation.path("x-ui").path("analytics").path("projections");
            if (!projections.isArray()) {
                continue;
            }
            for (JsonNode projection : projections) {
                if (!projection.isObject()) {
                    continue;
                }
                String projectionId = blankToNull(projection.path("id").asText(null));
                if (!StringUtils.hasText(projectionId)) {
                    continue;
                }
                String label = firstText(projection.path("label").asText(null), labelFromKey(projectionId));
                String description = firstText(
                        projection.path("description").asText(null),
                        "Projeção analítica curada (" + label + ") publicada via @UiAnalytics para o recurso " + resourceKey + "."
                );
                String intent = blankToNull(projection.path("intent").asText(null));
                String defaultFamily = blankToNull(projection.path("preferredVisualization").path("defaultFamily").asText(null));
                String sourceKind = blankToNull(projection.path("source").path("kind").asText(null));
                String sourceOperation = blankToNull(projection.path("source").path("operation").asText(null));
                String sourcePath = firstText(projection.path("source").path("path").asText(null), path);
                String primaryDimensionField = blankToNull(projection.path("semantics").path("primaryDimension").path("field").asText(null));
                String primaryDimensionLabel = blankToNull(projection.path("semantics").path("primaryDimension").path("label").asText(null));
                JsonNode primaryMetrics = projection.path("semantics").path("primaryMetrics");
                String primaryMetricField = primaryMetrics.isArray() && !primaryMetrics.isEmpty()
                        ? blankToNull(primaryMetrics.get(0).path("field").asText(null))
                        : null;
                String primaryMetricAggregation = primaryMetrics.isArray() && !primaryMetrics.isEmpty()
                        ? blankToNull(primaryMetrics.get(0).path("aggregation").asText(null))
                        : null;
                JsonNode recordOpen = projection.path("interactions").path("recordOpen");
                String recordOpenResourceKey = recordOpen.isObject()
                        ? blankToNull(recordOpen.path("target").path("resourceKey").asText(null))
                        : null;
                String recordOpenSurfaceId = recordOpen.isObject()
                        ? blankToNull(recordOpen.path("target").path("surfaceId").asText(null))
                        : null;

                String nodeKey = resourceKey + ".stats.projection." + keyPart(projectionId);
                String evidenceKey = "evidence:" + nodeKey + ":ui-analytics";
                Map<String, Object> metadata = mapOfNonNull(
                        "statsKind", "analytics_projection",
                        "projectionId", projectionId,
                        "intent", intent,
                        "defaultFamily", defaultFamily,
                        "sourceKind", sourceKind,
                        "sourceOperation", sourceOperation,
                        "sourcePath", sourcePath,
                        "primaryDimensionField", primaryDimensionField,
                        "primaryDimensionLabel", primaryDimensionLabel,
                        "primaryMetricField", primaryMetricField,
                        "primaryMetricAggregation", primaryMetricAggregation,
                        "recordOpenResourceKey", recordOpenResourceKey,
                        "recordOpenSurfaceId", recordOpenSurfaceId,
                        "resourceKey", resourceKey,
                        "ownerServiceKey", serviceKey,
                        "projectionRole", "DEFINITION",
                        "resourcePath", resourcePath,
                        "group", group
                );

                List<String> tags = new ArrayList<>(List.of("stats", "analytics", "analytics-projection"));
                if (intent != null) {
                    tags.add(intent);
                }
                if (sourceOperation != null) {
                    tags.add(sourceOperation);
                }

                nodes.putIfAbsent(nodeKey, new DomainCatalogResponse.DomainNodeItem(
                        nodeKey,
                        contextKey,
                        "stats",
                        label,
                        description,
                        "active",
                        "ui-analytics",
                        0.95,
                        metadata,
                        List.copyOf(tags),
                        null,
                        contextKey,
                        "active",
                        glossary(label, description, tags),
                        resolution(nodeKey, matchKeys(projectionId, label, intent, primaryDimensionField, primaryMetricField), "exact-key-or-alias"),
                        List.of(evidenceKey)
                ));
                putEdge(edges, resourceKey + ".has-stats.projection." + keyPart(projectionId), resourceKey, nodeKey,
                        "has_stats", "Possui projeção analítica curada " + label, 0.95, List.of(evidenceKey));
                if (recordOpenResourceKey != null) {
                    putEdge(edges, nodeKey + ".references." + keyPart(recordOpenResourceKey), nodeKey, recordOpenResourceKey,
                            "references", "Drill-down analítico abre recurso " + recordOpenResourceKey, 0.92, List.of(evidenceKey));
                    if (!recordOpenResourceKey.equals(resourceKey)) {
                        putEdge(edges, resourceKey + ".references." + keyPart(recordOpenResourceKey), resourceKey, recordOpenResourceKey,
                                "references", "Projeção analítica " + label + " referencia " + recordOpenResourceKey + " em drill-down", 0.92, List.of(evidenceKey));
                    }
                }
                bindings.putIfAbsent("binding:" + nodeKey + ":ui-analytics", new DomainCatalogResponse.DomainBindingItem(
                        "binding:" + nodeKey + ":ui-analytics",
                        nodeKey,
                        "stats_endpoint",
                        metadata,
                        List.of(),
                        List.of(evidenceKey)
                ));
                evidence.putIfAbsent(evidenceKey, evidence(
                        evidenceKey,
                        "openapi_stats",
                        mapOfNonNull(
                                "kind", "java.annotation",
                                "annotation", "UiAnalytics",
                                "projectionId", projectionId,
                                "resourceKey", resourceKey,
                                "path", sourcePath
                        ),
                        "Projeção analítica curada derivada de @UiAnalytics (" + projectionId + ") para o recurso " + resourceKey + ".",
                        0.98
                ));
            }
        }
    }

    private void addSchemaFields(
            String resourceKey,
            String contextKey,
            String group,
            CanonicalSchemaRef schemaRef,
            String schemaRole,
            Map<String, String> canonicalResourceKeysByPath,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence,
            Map<String, DomainCatalogResponse.DomainGovernanceItem> governance
    ) {
        if (schemaRef == null || !StringUtils.hasText(schemaRef.schemaId())) {
            return;
        }
        boolean strict = Boolean.TRUE.equals(strictSchemaResolution.get());
        if (openApiDocumentService == null) {
            if (strict) {
                throw new IllegalStateException("OpenApiDocumentService is required for strict domain catalog resolution");
            }
            return;
        }
        JsonNode document = resolveOpenApiDocument(group);
        JsonNode schema = resolveSchemaFromDocument(document, schemaRef);
        if (schema == null || schema.isMissingNode()) {
            if (strict) {
                throw new IllegalStateException(
                        "Unresolved canonical schema in strict domain catalog: " + schemaRef.schemaId()
                                + " (group=" + firstText(group, "") + ")");
            }
            return;
        }
        addSchemaFieldTree(resourceKey, contextKey, schemaRef, schemaRole, unwrapArray(schema), document, "", new LinkedHashSet<>(), 0,
                canonicalResourceKeysByPath, nodes, edges, bindings, evidence, governance);
    }

    private void addSchemaFieldTree(String resourceKey, String contextKey, CanonicalSchemaRef schemaRef,
            String schemaRole, JsonNode schema, JsonNode document, String prefix, Set<String> seenRefs, int depth,
            Map<String,String> canonicalResourceKeysByPath,
            Map<String,DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String,DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String,DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String,DomainCatalogResponse.DomainEvidenceItem> evidence,
            Map<String,DomainCatalogResponse.DomainGovernanceItem> governance) {
        if (schema == null || schema.isMissingNode()) return;
        if (depth > 32) throw new IllegalStateException("Domain field projection exceeds structural depth 32");
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (!ref.startsWith("#/components/schemas/")) throw new IllegalStateException("Non-local field schema reference");
            if (seenRefs.contains(ref)) return; // a recursive model is represented once per branch
            seenRefs = new LinkedHashSet<>(seenRefs); seenRefs.add(ref);
            schema = document == null ? null : org.praxisplatform.uischema.schema.DomainSchemaGovernance.inherit(schema, document.at(ref.substring(1)));
            if (schema == null || schema.isMissingNode()) throw new IllegalStateException("Unresolved field schema: " + ref);
        }
        for (JsonNode branch : schema.path("allOf"))
            addSchemaFieldTree(resourceKey, contextKey, schemaRef, schemaRole,
                    org.praxisplatform.uischema.schema.DomainSchemaGovernance.inherit(schema, branch), document, prefix, seenRefs, depth+1,
                    canonicalResourceKeysByPath, nodes, edges, bindings, evidence, governance);
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) return;
        Set<String> required = requiredFields(schema.path("required"));
        var fields = properties.fields();
        while (fields.hasNext()) {
            var field = fields.next(); String name = prefix + field.getKey();
            JsonNode governedField = org.praxisplatform.uischema.schema.DomainSchemaGovernance.inherit(schema, field.getValue());
            addField(resourceKey, contextKey, schemaRef, schemaRole, name, governedField, required.contains(field.getKey()),
                    canonicalResourceKeysByPath, nodes, edges, bindings, evidence, governance);
            addSchemaFieldTree(resourceKey, contextKey, schemaRef, schemaRole, governedField, document, name+".", seenRefs, depth+1,
                    canonicalResourceKeysByPath, nodes, edges, bindings, evidence, governance);
        }
    }

    private void addField(
            String resourceKey,
            String contextKey,
            CanonicalSchemaRef schemaRef,
            String schemaRole,
            String fieldName,
            JsonNode fieldSchema,
            boolean required,
            Map<String, String> canonicalResourceKeysByPath,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence,
            Map<String, DomainCatalogResponse.DomainGovernanceItem> governance
    ) {
        String fieldNodeKey = resourceKey + ".field." + keyPart(fieldName);
        String evidenceKey = "evidence:" + fieldNodeKey + ":" + keyPart(schemaRef.schemaId());
        JsonNode effectiveSchema = unwrapArray(fieldSchema);
        String type = firstText(effectiveSchema.path("type").asText(null), fieldSchema.path("type").asText(null));
        JsonNode resourceRefNode = effectiveSchema.path("x-ui").path("resourceReference");
        String referencedResourceKey = resourceRefNode.isObject()
                ? blankToNull(resourceRefNode.path("resourceKey").asText(null))
                : null;
        Map<String, Object> resourceReferenceMetadata = referencedResourceKey != null
                ? mapOfNonNull(
                        "resourceKey", referencedResourceKey,
                        "valueField", blankToNull(resourceRefNode.path("valueField").asText(null)),
                        "surfaceId", blankToNull(resourceRefNode.path("surfaceId").asText(null)),
                        "previewSurfaceId", blankToNull(resourceRefNode.path("previewSurfaceId").asText(null)),
                        "detailPresentation", blankToNull(resourceRefNode.path("detailPresentation").asText(null))
                )
                : null;

        JsonNode optionSourceNode = effectiveSchema.path("x-ui").path("optionSource");
        Map<String, Object> optionSourceMetadata = null;
        if (optionSourceNode.isObject()) {
            String optionSourceKey = blankToNull(optionSourceNode.path("key").asText(null));
            String optionSourceType = blankToNull(optionSourceNode.path("type").asText(null));
            String optionSourceResourcePath = blankToNull(optionSourceNode.path("resourcePath").asText(null));
            String optionSourceEntityKey = blankToNull(optionSourceNode.path("entityKey").asText(null));
            List<String> optionSourceDependsOn = textValues(optionSourceNode.path("dependsOn"));
            if (referencedResourceKey == null && "RESOURCE_ENTITY".equalsIgnoreCase(optionSourceType)) {
                String normalizedOptionPath = normalizeResourcePath(optionSourceResourcePath);
                String canonicalFromPath = normalizedOptionPath != null && canonicalResourceKeysByPath != null
                        ? canonicalResourceKeysByPath.get(normalizedOptionPath)
                        : null;
                String detailResourceKey = blankToNull(optionSourceNode.path("detail").path("resourceKey").asText(null));
                if (StringUtils.hasText(canonicalFromPath)) {
                    referencedResourceKey = canonicalFromPath;
                } else if (detailResourceKey != null && detailResourceKey.contains(".")) {
                    referencedResourceKey = detailResourceKey;
                } else if (StringUtils.hasText(optionSourceResourcePath)) {
                    referencedResourceKey = resourceKeyForPath(
                            optionSourceResourcePath,
                            canonicalResourceKeysByPath != null ? canonicalResourceKeysByPath : Map.of()
                    );
                }
            }
            if (optionSourceKey != null || optionSourceType != null || optionSourceResourcePath != null) {
                optionSourceMetadata = mapOfNonNull(
                        "key", optionSourceKey,
                        "type", optionSourceType,
                        "resourcePath", optionSourceResourcePath,
                        "entityKey", optionSourceEntityKey,
                        "referencedResourceKey", referencedResourceKey,
                        "dependsOn", optionSourceDependsOn != null && !optionSourceDependsOn.isEmpty() ? optionSourceDependsOn : null
                );
            }
        }

        JsonNode lineageNode = effectiveSchema.path(DOMAIN_GOVERNANCE_EXTENSION).path("lineage");
        Map<String, Object> lineageMetadata = null;
        String canonicalAttributeId = null;
        String lineageTransformation = null;
        if (lineageNode.isObject()) {
            canonicalAttributeId = blankToNull(lineageNode.path("attributeId").asText(null));
            String sourceClass = blankToNull(lineageNode.path("sourceClass").asText(null));
            String sourceSimpleName = blankToNull(lineageNode.path("sourceSimpleName").asText(null));
            String sourceResourceKey = blankToNull(lineageNode.path("sourceResourceKey").asText(null));
            List<String> sourcePaths = textValues(lineageNode.path("sourcePaths"));
            lineageTransformation = firstText(blankToNull(lineageNode.path("transformation").asText(null)), "direct");
            String provenance = firstText(blankToNull(lineageNode.path("provenance").asText(null)), "declared_lineage");
            lineageMetadata = mapOfNonNull(
                    "attributeId", canonicalAttributeId,
                    "sourceClass", sourceClass,
                    "sourceSimpleName", sourceSimpleName,
                    "sourceResourceKey", sourceResourceKey,
                    "sourcePaths", sourcePaths,
                    "transformation", lineageTransformation,
                    "provenance", provenance
            );
        }

        Map<String, Object> fieldMetadata = mapOfNonNull(
                "fieldName", fieldName,
                "resourceKey", resourceKey,
                "schemaId", schemaRef.schemaId(),
                "schemaRole", schemaRole,
                "schemaType", schemaRef.schemaType(),
                "type", type,
                "format", effectiveSchema.path("format").asText(null),
                "required", required,
                "enum", textValues(effectiveSchema.path("enum")),
                "minimum", numericValue(effectiveSchema.path("minimum")),
                "maximum", numericValue(effectiveSchema.path("maximum")),
                "minLength", numericValue(effectiveSchema.path("minLength")),
                "maxLength", numericValue(effectiveSchema.path("maxLength")),
                "pattern", effectiveSchema.path("pattern").asText(null),
                "referencedResourceKey", referencedResourceKey,
                "resourceReference", resourceReferenceMetadata,
                "optionSource", optionSourceMetadata,
                "canonicalAttributeId", canonicalAttributeId,
                "lineage", lineageMetadata
        );

        DomainCatalogResponse.DomainNodeItem existingFieldNode = nodes.get(fieldNodeKey);
        if (existingFieldNode != null && !fieldName.equals(existingFieldNode.metadata().get("fieldName")))
            throw new IllegalStateException("Ambiguous domain field identity: " + fieldName);
        if (existingFieldNode == null) {
            nodes.put(fieldNodeKey, new DomainCatalogResponse.DomainNodeItem(
                    fieldNodeKey,
                    contextKey,
                    "field",
                    labelFromKey(fieldName),
                    effectiveSchema.path("description").asText(null),
                    "active",
                    "dto_schema",
                    0.85,
                    fieldMetadata,
                    List.of("dto-field", schemaRole),
                    null,
                    contextKey,
                    "active",
                    glossary(labelFromKey(fieldName), effectiveSchema.path("description").asText(null), List.of("dto-field", schemaRole)),
                    resolution(fieldNodeKey, matchKeys(fieldName, labelFromKey(fieldName), schemaRef.schemaId(), canonicalAttributeId), "exact-key-or-alias"),
                    List.of(evidenceKey)
            ));
        } else {
            Map<String, Object> existingMeta = existingFieldNode.metadata() != null ? existingFieldNode.metadata() : Map.of();
            if (existingMeta.get("lineage") instanceof Map<?, ?> existingLineage && lineageMetadata != null) {
                Object existingAttrId = existingLineage.get("attributeId");
                if (existingAttrId != null && canonicalAttributeId != null && !Objects.equals(existingAttrId, canonicalAttributeId)) {
                    throw new IllegalStateException(
                            "Conflicting domain lineage for homonym field '" + fieldName + "' on resource '"
                                    + resourceKey + "': '" + existingAttrId + "' vs '" + canonicalAttributeId + "'");
                }
                Object existingSourceClass = existingLineage.get("sourceClass");
                Object currentSourceClass = lineageMetadata.get("sourceClass");
                if (existingSourceClass != null && currentSourceClass != null && !Objects.equals(existingSourceClass, currentSourceClass)) {
                    throw new IllegalStateException(
                            "Conflicting domain lineage sourceClass for homonym field '" + fieldName + "' on resource '"
                                    + resourceKey + "': '" + existingSourceClass + "' vs '" + currentSourceClass + "'");
                }
            }
            Map<String, Object> mergedMetadata = new LinkedHashMap<>(existingMeta);
            if (referencedResourceKey != null && !existingMeta.containsKey("referencedResourceKey")) {
                mergedMetadata.put("referencedResourceKey", referencedResourceKey);
            }
            if (resourceReferenceMetadata != null && !existingMeta.containsKey("resourceReference")) {
                mergedMetadata.put("resourceReference", resourceReferenceMetadata);
            }
            if (optionSourceMetadata != null && !existingMeta.containsKey("optionSource")) {
                mergedMetadata.put("optionSource", optionSourceMetadata);
            }
            if (canonicalAttributeId != null && !existingMeta.containsKey("canonicalAttributeId")) {
                mergedMetadata.put("canonicalAttributeId", canonicalAttributeId);
            }
            if (lineageMetadata != null && !existingMeta.containsKey("lineage")) {
                mergedMetadata.put("lineage", lineageMetadata);
            }
            Set<String> mergedSchemaIds = new LinkedHashSet<>();
            if (existingMeta.get("schemaId") instanceof String firstSchemaId && StringUtils.hasText(firstSchemaId)) {
                mergedSchemaIds.add(firstSchemaId);
            }
            if (existingMeta.get("schemaIds") instanceof Collection<?> existingIds) {
                existingIds.forEach(item -> {
                    if (item != null && StringUtils.hasText(String.valueOf(item))) {
                        mergedSchemaIds.add(String.valueOf(item));
                    }
                });
            }
            if (StringUtils.hasText(schemaRef.schemaId())) {
                mergedSchemaIds.add(schemaRef.schemaId());
            }
            if (mergedSchemaIds.size() > 1) {
                mergedMetadata.put("schemaIds", List.copyOf(mergedSchemaIds));
            }
            Set<String> mergedSchemaRoles = new LinkedHashSet<>();
            if (existingMeta.get("schemaRole") instanceof String firstRole && StringUtils.hasText(firstRole)) {
                mergedSchemaRoles.add(firstRole);
            }
            if (existingMeta.get("schemaRoles") instanceof Collection<?> existingRoles) {
                existingRoles.forEach(item -> {
                    if (item != null && StringUtils.hasText(String.valueOf(item))) {
                        mergedSchemaRoles.add(String.valueOf(item));
                    }
                });
            }
            if (StringUtils.hasText(schemaRole)) {
                mergedSchemaRoles.add(schemaRole);
            }
            if (mergedSchemaRoles.size() > 1) {
                mergedMetadata.put("schemaRoles", List.copyOf(mergedSchemaRoles));
            }
            Set<String> mergedTags = new LinkedHashSet<>(existingFieldNode.tags() != null ? existingFieldNode.tags() : List.of());
            mergedTags.add("dto-field");
            if (StringUtils.hasText(schemaRole)) {
                mergedTags.add(schemaRole);
            }
            Set<String> mergedEvidenceKeys = new LinkedHashSet<>(
                    existingFieldNode.sourceEvidenceKeys() != null ? existingFieldNode.sourceEvidenceKeys() : List.of()
            );
            mergedEvidenceKeys.add(evidenceKey);
            Set<String> mergedMatchKeys = new LinkedHashSet<>();
            if (existingFieldNode.resolution() != null
                    && existingFieldNode.resolution().get("matchKeys") instanceof Collection<?> existingKeys) {
                for (Object item : existingKeys) {
                    if (item != null && StringUtils.hasText(String.valueOf(item))) {
                        mergedMatchKeys.add(String.valueOf(item));
                    }
                }
            }
            mergedMatchKeys.addAll(matchKeys(fieldName, labelFromKey(fieldName), schemaRef.schemaId(), canonicalAttributeId));
            Map<String, Object> mergedResolution = resolution(
                    fieldNodeKey,
                    List.copyOf(mergedMatchKeys),
                    "exact-key-or-alias"
            );
            nodes.put(fieldNodeKey, new DomainCatalogResponse.DomainNodeItem(
                    existingFieldNode.nodeKey(),
                    existingFieldNode.contextKey(),
                    existingFieldNode.nodeType(),
                    existingFieldNode.label(),
                    firstText(existingFieldNode.description(), effectiveSchema.path("description").asText(null)),
                    existingFieldNode.status(),
                    existingFieldNode.source(),
                    existingFieldNode.confidence(),
                    Map.copyOf(mergedMetadata),
                    List.copyOf(mergedTags),
                    existingFieldNode.owner(),
                    existingFieldNode.semanticOwner(),
                    existingFieldNode.lifecycle(),
                    existingFieldNode.businessGlossary(),
                    mergedResolution,
                    List.copyOf(mergedEvidenceKeys)
            ));
        }

        putEdge(edges, resourceKey + ".has-field." + keyPart(fieldName), resourceKey, fieldNodeKey,
                "has_field", "Possui campo " + fieldName, 0.85, List.of(evidenceKey));

        if (referencedResourceKey != null) {
            putEdge(edges, fieldNodeKey + ".references." + keyPart(referencedResourceKey), fieldNodeKey, referencedResourceKey,
                    "references", "Campo " + fieldName + " referencia " + referencedResourceKey, 0.95, List.of(evidenceKey));
            if (!referencedResourceKey.equals(resourceKey)) {
                putEdge(edges, resourceKey + ".references." + keyPart(referencedResourceKey), resourceKey, referencedResourceKey,
                        "references", "Referencia " + referencedResourceKey + " via campo " + fieldName, 0.95, List.of(evidenceKey));
            }
        }

        String bindingKey = "binding:" + fieldNodeKey + ":dto-field";
        DomainCatalogResponse.DomainBindingItem existingBinding = bindings.get(bindingKey);
        Map<String, Object> mergedTarget = new LinkedHashMap<>(
                existingBinding != null && existingBinding.target() != null ? existingBinding.target() : Map.of()
        );
        mergedTarget.putIfAbsent("resourceKey", resourceKey);
        mergedTarget.putIfAbsent("schemaId", schemaRef.schemaId());
        mergedTarget.putIfAbsent("schemaType", schemaRef.schemaType());
        mergedTarget.putIfAbsent("schemaRole", schemaRole);
        mergedTarget.putIfAbsent("fieldName", fieldName);
        if (referencedResourceKey != null) {
            mergedTarget.put("referencedResourceKey", referencedResourceKey);
        }
        if (canonicalAttributeId != null) {
            mergedTarget.putIfAbsent("canonicalAttributeId", canonicalAttributeId);
        }
        if (lineageMetadata != null) {
            mergedTarget.putIfAbsent("lineage", lineageMetadata);
        }
        Map<String, Object> currentRepresentation = mapOfNonNull(
                "schemaId", schemaRef.schemaId(),
                "schemaRole", schemaRole,
                "schemaType", schemaRef.schemaType(),
                "schemaUrl", schemaRef.url(),
                "fieldPath", "/" + fieldName.replace('.', '/'),
                "required", required,
                "attributeId", canonicalAttributeId,
                "transformation", lineageTransformation
        );
        LinkedHashSet<Map<String, Object>> representations = new LinkedHashSet<>();
        if (mergedTarget.get("representations") instanceof Collection<?> existingReps) {
            for (Object item : existingReps) {
                if (item instanceof Map<?, ?> repMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> castRep = (Map<String, Object>) repMap;
                    representations.add(castRep);
                }
            }
        }
        representations.add(currentRepresentation);
        mergedTarget.put("representations", List.copyOf(representations));

        LinkedHashSet<DomainCatalogResponse.DomainSchemaLink> mergedSchemaLinks = new LinkedHashSet<>(
                existingBinding != null && existingBinding.schemaLinks() != null ? existingBinding.schemaLinks() : List.of()
        );
        mergedSchemaLinks.add(new DomainCatalogResponse.DomainSchemaLink(
                schemaRole,
                schemaRef.url(),
                schemaRef.schemaId(),
                schemaRef.schemaType()
        ));
        LinkedHashSet<String> mergedBindingEvidence = new LinkedHashSet<>(
                existingBinding != null && existingBinding.evidenceKeys() != null ? existingBinding.evidenceKeys() : List.of()
        );
        mergedBindingEvidence.add(evidenceKey);
        bindings.put(bindingKey, new DomainCatalogResponse.DomainBindingItem(
                bindingKey,
                fieldNodeKey,
                "dto_field",
                Map.copyOf(mergedTarget),
                List.copyOf(mergedSchemaLinks),
                List.copyOf(mergedBindingEvidence)
        ));

        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "dto_schema",
                mapOfNonNull(
                        "kind", "openapi.schema.property",
                        "schemaId", schemaRef.schemaId(),
                        "schemaRole", schemaRole,
                        "fieldName", fieldName,
                        "referencedResourceKey", referencedResourceKey
                ),
                "Campo derivado do schema OpenAPI " + schemaRef.schemaId() + ".",
                0.9
        ));

        DomainCatalogResponse.DomainGovernanceItem fieldGovernance =
                classifyFieldGovernance(fieldNodeKey, fieldName, effectiveSchema, schemaRef, schemaRole);
        if (fieldGovernance != null) {
            mergeFieldGovernance(governance, fieldGovernance);
        }
    }

    private void mergeFieldGovernance(Map<String, DomainCatalogResponse.DomainGovernanceItem> governance,
            DomainCatalogResponse.DomainGovernanceItem declaration) {
        JsonNode combined = HASH_OBJECT_MAPPER.valueToTree(declaration);
        var existing = governance.values().stream().filter(item -> declaration.nodeKey().equals(item.nodeKey())).toList();
        for (var item : existing) {
            combined = org.praxisplatform.uischema.schema.DomainSchemaGovernance.merge(HASH_OBJECT_MAPPER.valueToTree(item), combined);
            governance.remove(item.governanceKey());
        }
        var effective = HASH_OBJECT_MAPPER.convertValue(combined, DomainCatalogResponse.DomainGovernanceItem.class);
        governance.put(effective.governanceKey(), effective);
    }

    /**
     * Sintetiza arestas de linhagem ({@code same_as}, {@code materializes}, {@code depends_on}),
     * unifica representacoes que compartilham o mesmo {@code canonicalAttributeId} ({@code P44}, {@code P58})
     * e verifica ausencia de ciclos entre campos derivados ({@code P64}).
     */
    private void synthesizeLineageGraph(
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainGovernanceItem> governance
    ) {
        Map<String, List<DomainCatalogResponse.DomainNodeItem>> byAttributeId = new LinkedHashMap<>();
        Map<String, List<String>> deriveTargetsByFieldNode = new LinkedHashMap<>();

        for (DomainCatalogResponse.DomainNodeItem node : new ArrayList<>(nodes.values())) {
            if (!"field".equals(node.nodeType()) || node.metadata() == null) {
                continue;
            }
            if (node.metadata().get("canonicalAttributeId") instanceof String attrId && StringUtils.hasText(attrId)) {
                byAttributeId.computeIfAbsent(attrId.trim(), k -> new ArrayList<>()).add(node);
            }
            if (node.metadata().get("lineage") instanceof Map<?, ?> lineageMap) {
                if (Boolean.TRUE.equals(lineageMap.get("weakeningAttempted"))) {
                    String reason = lineageMap.get("weakeningReason") instanceof String wr && StringUtils.hasText(wr)
                            ? wr
                            : "weakened lineage governance";
                    throw new IllegalStateException(
                            "Weakened @DomainLineage governance rejected on field node " + node.nodeKey() + ": " + reason);
                }
                String transformation = lineageMap.get("transformation") instanceof String tr && StringUtils.hasText(tr)
                        ? tr.trim()
                        : "direct";
                String targetResourceKey = lineageMap.get("sourceResourceKey") instanceof String srk && StringUtils.hasText(srk)
                        ? srk.trim()
                        : (node.metadata().get("resourceKey") instanceof String rk ? rk : null);
                if (StringUtils.hasText(targetResourceKey) && lineageMap.get("sourcePaths") instanceof Collection<?> sourcePaths) {
                    for (Object rawPath : sourcePaths) {
                        if (rawPath == null || !StringUtils.hasText(String.valueOf(rawPath))) {
                            continue;
                        }
                        String sourcePath = String.valueOf(rawPath).trim();
                        String targetFieldNodeKey = targetResourceKey + ".field." + keyPart(sourcePath);
                        if (targetFieldNodeKey.equals(node.nodeKey())) {
                            if ("derived".equals(transformation) || "aggregated".equals(transformation)) {
                                throw new IllegalStateException(
                                        "Self-referential derived @DomainLineage detected on field node: " + node.nodeKey());
                            }
                            continue;
                        }
                        if (nodes.containsKey(targetFieldNodeKey)) {
                            deriveTargetsByFieldNode
                                    .computeIfAbsent(node.nodeKey(), k -> new ArrayList<>())
                                    .add(targetFieldNodeKey);
                            String edgeType = ("alias".equals(transformation) || "formatted".equals(transformation) || "direct".equals(transformation))
                                    ? "materializes"
                                    : "depends_on";
                            String edgeLabel = "materializes".equals(edgeType)
                                    ? "Materializa atributo de origem " + sourcePath
                                    : "Deriva de " + sourcePath;
                            putEdge(
                                    edges,
                                    node.nodeKey() + "." + edgeType.replace('_', '-') + "." + keyPart(targetFieldNodeKey),
                                    node.nodeKey(),
                                    targetFieldNodeKey,
                                    edgeType,
                                    edgeLabel,
                                    0.95,
                                    node.sourceEvidenceKeys() != null ? node.sourceEvidenceKeys() : List.of()
                            );
                        }
                    }
                }
            }
        }

        for (String startNode : deriveTargetsByFieldNode.keySet()) {
            detectLineageDerivationCycle(startNode, deriveTargetsByFieldNode, new LinkedHashSet<>(), 0);
        }

        for (Map.Entry<String, List<DomainCatalogResponse.DomainNodeItem>> entry : byAttributeId.entrySet()) {
            String attributeId = entry.getKey();
            List<DomainCatalogResponse.DomainNodeItem> groupNodes = entry.getValue();
            if (groupNodes.size() <= 1) {
                continue;
            }
            List<String> linkedNodeKeys = groupNodes.stream()
                    .map(DomainCatalogResponse.DomainNodeItem::nodeKey)
                    .toList();
            LinkedHashSet<Map<String, Object>> sharedRepresentations = new LinkedHashSet<>();
            LinkedHashSet<String> sharedMatchKeys = new LinkedHashSet<>();
            sharedMatchKeys.add(attributeId);

            JsonNode unifiedGovNode = null;
            for (DomainCatalogResponse.DomainNodeItem peer : groupNodes) {
                if (peer.metadata().get("fieldName") instanceof String fn && StringUtils.hasText(fn)) {
                    sharedMatchKeys.add(fn);
                }
                String bindingKey = "binding:" + peer.nodeKey() + ":dto-field";
                DomainCatalogResponse.DomainBindingItem peerBinding = bindings.get(bindingKey);
                if (peerBinding != null && peerBinding.target() != null
                        && peerBinding.target().get("representations") instanceof Collection<?> reps) {
                    for (Object item : reps) {
                        if (item instanceof Map<?, ?> repMap) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> castRep = (Map<String, Object>) repMap;
                            sharedRepresentations.add(castRep);
                        }
                    }
                }
                for (DomainCatalogResponse.DomainGovernanceItem govItem : governance.values()) {
                    if (peer.nodeKey().equals(govItem.nodeKey())) {
                        unifiedGovNode = org.praxisplatform.uischema.schema.DomainSchemaGovernance.mergeOrCopy(
                                unifiedGovNode,
                                HASH_OBJECT_MAPPER.valueToTree(govItem)
                        );
                    }
                }
            }

            DomainCatalogResponse.DomainNodeItem primary = groupNodes.getFirst();
            for (int i = 1; i < groupNodes.size(); i++) {
                DomainCatalogResponse.DomainNodeItem peer = groupNodes.get(i);
                List<String> combinedEvidence = new ArrayList<>();
                if (primary.sourceEvidenceKeys() != null) {
                    combinedEvidence.addAll(primary.sourceEvidenceKeys());
                }
                if (peer.sourceEvidenceKeys() != null) {
                    for (String ev : peer.sourceEvidenceKeys()) {
                        if (!combinedEvidence.contains(ev)) {
                            combinedEvidence.add(ev);
                        }
                    }
                }
                putEdge(
                        edges,
                        peer.nodeKey() + ".same-as." + keyPart(primary.nodeKey()),
                        peer.nodeKey(),
                        primary.nodeKey(),
                        "same_as",
                        "Compartilha atributo semantico " + attributeId,
                        0.98,
                        List.copyOf(combinedEvidence)
                );
            }

            for (DomainCatalogResponse.DomainNodeItem peer : groupNodes) {
                Map<String, Object> updatedMeta = new LinkedHashMap<>(peer.metadata());
                updatedMeta.put("linkedRepresentationNodes", linkedNodeKeys);
                updatedMeta.put("sharedRepresentations", List.copyOf(sharedRepresentations));

                Set<String> nodeMatchKeys = new LinkedHashSet<>();
                if (peer.resolution() != null && peer.resolution().get("matchKeys") instanceof Collection<?> existingKeys) {
                    for (Object item : existingKeys) {
                        if (item != null && StringUtils.hasText(String.valueOf(item))) {
                            nodeMatchKeys.add(String.valueOf(item));
                        }
                    }
                }
                nodeMatchKeys.addAll(sharedMatchKeys);

                nodes.put(peer.nodeKey(), new DomainCatalogResponse.DomainNodeItem(
                        peer.nodeKey(),
                        peer.contextKey(),
                        peer.nodeType(),
                        peer.label(),
                        peer.description(),
                        peer.status(),
                        peer.source(),
                        peer.confidence(),
                        Map.copyOf(updatedMeta),
                        peer.tags(),
                        peer.owner(),
                        peer.semanticOwner(),
                        peer.lifecycle(),
                        peer.businessGlossary(),
                        resolution(peer.nodeKey(), List.copyOf(nodeMatchKeys), "exact-key-or-alias"),
                        peer.sourceEvidenceKeys()
                ));

                if (unifiedGovNode != null) {
                    var existingGovItems = governance.values().stream()
                            .filter(item -> peer.nodeKey().equals(item.nodeKey()))
                            .toList();
                    for (var oldItem : existingGovItems) {
                        governance.remove(oldItem.governanceKey());
                    }
                    com.fasterxml.jackson.databind.node.ObjectNode peerGovCopy = unifiedGovNode.deepCopy();
                    String annType = peerGovCopy.path("annotationType").asText("privacy");
                    String govKey = "governance:" + peer.nodeKey() + ":" + annType;
                    peerGovCopy.put("nodeKey", peer.nodeKey());
                    peerGovCopy.put("governanceKey", govKey);
                    DomainCatalogResponse.DomainGovernanceItem updatedGov =
                            HASH_OBJECT_MAPPER.convertValue(peerGovCopy, DomainCatalogResponse.DomainGovernanceItem.class);
                    governance.put(govKey, updatedGov);
                }
            }
        }
    }

    private void detectLineageDerivationCycle(
            String currentNode,
            Map<String, List<String>> deriveTargetsByFieldNode,
            Set<String> activePath,
            int depth
    ) {
        if (depth > org.praxisplatform.uischema.schema.DomainLineageResolver.MAX_LINEAGE_DEPTH) {
            throw new IllegalStateException("Domain lineage derivation exceeds maximum depth ("
                    + org.praxisplatform.uischema.schema.DomainLineageResolver.MAX_LINEAGE_DEPTH + ") at " + currentNode);
        }
        if (!activePath.add(currentNode)) {
            throw new IllegalStateException("Cyclic domain lineage derivation detected in catalog involving " + currentNode);
        }
        try {
            for (String target : deriveTargetsByFieldNode.getOrDefault(currentNode, List.of())) {
                detectLineageDerivationCycle(target, deriveTargetsByFieldNode, activePath, depth + 1);
            }
        } finally {
            activePath.remove(currentNode);
        }
    }

    /**
     * Sintetiza a topologia de Knowledge Graph e Data Products (System of Context) diretamente
     * nos nós {@code concept} a partir das arestas e capacidades já extraídas, sem exigir
     * configuração manual nos hosts.
     */
    private void enrichConceptKnowledgeGraphMetadata(
            Map<String, DomainCatalogResponse.DomainContextItem> contexts,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            String requestedGroup,
            Map<String, Map<String, Boolean>> capabilitiesByResourcePath
    ) {
        for (DomainCatalogResponse.DomainEdgeItem edge : new ArrayList<>(edges.values())) {
            if (!"references".equals(edge.edgeType())) {
                continue;
            }
            String targetKey = edge.targetNodeKey();
            if (StringUtils.hasText(targetKey) && !nodes.containsKey(targetKey)) {
                String targetContext = contextKey(targetKey, requestedGroup);
                ensureContext(contexts, targetContext, requestedGroup);
                ensureConceptReference(nodes, targetKey,
                        new ConceptRootBinding("reference", serviceKey, targetContext, targetKey), null);
            }
        }

        Map<String, Set<String>> referencedByConcept = new LinkedHashMap<>();
        Map<String, Set<String>> childConceptsByConcept = new LinkedHashMap<>();
        Map<String, Set<String>> parentConceptsByConcept = new LinkedHashMap<>();
        Map<String, Map<String, String>> conceptFactRootMapByConcept = new LinkedHashMap<>();
        Map<String, Map<String, ConceptRootBinding>> identitiesByConcept = new LinkedHashMap<>();
        Map<String, Set<String>> ambiguousRootsByConcept = new LinkedHashMap<>();
        Map<String, Set<String>> governedRuleSetsByConcept = new LinkedHashMap<>();
        Map<String, Set<String>> governedOperationsByConcept = new LinkedHashMap<>();
        Map<String, Set<String>> governedReasonCodesByConcept = new LinkedHashMap<>();
        Map<String, Set<String>> explicitRuleSetRootsByConcept = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> factPathsByRootByConcept = new LinkedHashMap<>();
        Set<String> conceptsWithAnalytics = new LinkedHashSet<>();
        Set<String> conceptsWithActions = new LinkedHashSet<>();

        for (DomainCatalogResponse.DomainEdgeItem edge : edges.values()) {
            String src = edge.sourceNodeKey();
            String dst = edge.targetNodeKey();
            if ("has_stats".equals(edge.edgeType()) || "provides_fact".equals(edge.edgeType())) {
                conceptsWithAnalytics.add(src);
            }
            if ("has_action".equals(edge.edgeType())) {
                conceptsWithActions.add(src);
            }
            if ("governed_by".equals(edge.edgeType())) {
                DomainCatalogResponse.DomainNodeItem srcNode = nodes.get(src);
                DomainCatalogResponse.DomainNodeItem policyNode = nodes.get(dst);
                if (srcNode != null
                        && "concept".equals(srcNode.nodeType())
                        && policyNode != null
                        && policyNode.metadata() != null) {
                    Object ruleSetKeyObj = policyNode.metadata().get("ruleSetKey");
                    if (ruleSetKeyObj instanceof String ruleSetKey && StringUtils.hasText(ruleSetKey)) {
                        governedRuleSetsByConcept.computeIfAbsent(src, ignored -> new LinkedHashSet<>()).add(ruleSetKey);
                    }
                    Object opKeyObj = policyNode.metadata().get("operationKey");
                    if (opKeyObj instanceof String opKey && StringUtils.hasText(opKey)) {
                        governedOperationsByConcept.computeIfAbsent(src, ignored -> new LinkedHashSet<>()).add(opKey);
                    }
                    Object reasonCodesObj = policyNode.metadata().get("reasonCodes");
                    if (reasonCodesObj instanceof List<?> reasons) {
                        for (Object item : reasons) {
                            if (item instanceof String code && StringUtils.hasText(code)) {
                                governedReasonCodesByConcept.computeIfAbsent(src, ignored -> new LinkedHashSet<>()).add(code);
                            }
                        }
                    }
                    Object declaredBindings = policyNode.metadata().get("conceptRootBindings");
                    if (declaredBindings instanceof Map<?, ?> declared) {
                        Map<String, String> roots = conceptFactRootMapByConcept.computeIfAbsent(src, ignored -> new LinkedHashMap<>());
                        Map<String, ConceptRootBinding> identities = identitiesByConcept.computeIfAbsent(src, ignored -> new LinkedHashMap<>());
                        Set<String> ambiguous = ambiguousRootsByConcept.computeIfAbsent(src, ignored -> new LinkedHashSet<>());
                        declared.forEach((root, identity) -> {
                            if (identity instanceof ConceptRootBinding binding) {
                                String name = String.valueOf(root);
                                ConceptRootBinding previous = identities.putIfAbsent(name, binding);
                                if (previous != null && !previous.equals(binding)) ambiguous.add(name);
                                if (ambiguous.contains(name)) roots.remove(name);
                                else roots.put(name, binding.resourceKey());
                            }
                        });
                    }
                    Object conceptRootsObj = policyNode.metadata().get("conceptRoots");
                    if (conceptRootsObj instanceof List<?> roots) {
                        for (Object item : roots) {
                            if (item instanceof String root && StringUtils.hasText(root)) {
                                explicitRuleSetRootsByConcept.computeIfAbsent(src, ignored -> new LinkedHashSet<>()).add(root);
                            }
                        }
                    }
                    Object factPathsByRootObj = policyNode.metadata().get("factPathsByRoot");
                    if (factPathsByRootObj instanceof Map<?, ?> pathsByRoot) {
                        Map<String, Set<String>> targetMap = factPathsByRootByConcept
                                .computeIfAbsent(src, ignored -> new LinkedHashMap<>());
                        for (Map.Entry<?, ?> rootEntry : pathsByRoot.entrySet()) {
                            if (rootEntry.getKey() instanceof String root && StringUtils.hasText(root)) {
                                Set<String> rootPaths = targetMap.computeIfAbsent(root, ignored -> new java.util.TreeSet<>());
                                if (rootEntry.getValue() instanceof List<?> pathList) {
                                    for (Object pathObj : pathList) {
                                        if (pathObj instanceof String path && StringUtils.hasText(path)) {
                                            rootPaths.add(path);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if ("references".equals(edge.edgeType())) {
                DomainCatalogResponse.DomainNodeItem srcNode = nodes.get(src);
                if (srcNode != null && "concept".equals(srcNode.nodeType()) && !src.equals(dst)) {
                    referencedByConcept.computeIfAbsent(src, ignored -> new LinkedHashSet<>()).add(dst);
                    if (edge.edgeKey() != null && edge.edgeKey().contains(".references-child.")) {
                        childConceptsByConcept.computeIfAbsent(src, ignored -> new LinkedHashSet<>()).add(dst);
                        parentConceptsByConcept.computeIfAbsent(dst, ignored -> new LinkedHashSet<>()).add(src);
                    }
                }
            }
        }

        Map<String, Set<String>> analyzedByDataProducts = new LinkedHashMap<>();
        Map<String, String> semanticRoleByConcept = new LinkedHashMap<>();

        for (DomainCatalogResponse.DomainNodeItem node : nodes.values()) {
            if (!"concept".equals(node.nodeType()) || "concept-reference".equals(node.source())) {
                continue;
            }
            String conceptKey = node.nodeKey();
            boolean readOnly = node.metadata() != null
                    && Boolean.TRUE.equals(node.metadata().get("readOnly"))
                    && !conceptsWithActions.contains(conceptKey);
            boolean hasAnalytics = conceptsWithAnalytics.contains(conceptKey);
            Set<String> refs = referencedByConcept.getOrDefault(conceptKey, Set.of());
            boolean isDataProduct = readOnly && hasAnalytics && !refs.isEmpty();

            String semanticRole = isDataProduct
                    ? "analytical_data_product"
                    : (readOnly ? "reference_or_read_projection" : "operational_resource");
            semanticRoleByConcept.put(conceptKey, semanticRole);

            if (isDataProduct) {
                for (String targetConcept : refs) {
                    analyzedByDataProducts.computeIfAbsent(targetConcept, ignored -> new LinkedHashSet<>()).add(conceptKey);
                    putEdge(
                            edges,
                            conceptKey + ".materializes." + keyPart(targetConcept),
                            conceptKey,
                            targetConcept,
                            "materializes",
                            "Data Product analítico consolida e analisa " + targetConcept,
                            0.95,
                            node.sourceEvidenceKeys()
                    );
                }
            }
        }

        for (Map.Entry<String, DomainCatalogResponse.DomainNodeItem> entry : new ArrayList<>(nodes.entrySet())) {
            DomainCatalogResponse.DomainNodeItem node = entry.getValue();
            if (!"concept".equals(node.nodeType()) || "concept-reference".equals(node.source())) {
                continue;
            }
            String conceptKey = node.nodeKey();
            String semanticRole = semanticRoleByConcept.getOrDefault(conceptKey, "operational_resource");
            Set<String> refs = referencedByConcept.getOrDefault(conceptKey, Set.of());
            Set<String> children = childConceptsByConcept.getOrDefault(conceptKey, Set.of());
            Set<String> parents = parentConceptsByConcept.getOrDefault(conceptKey, Set.of());
            Set<String> dataProducts = analyzedByDataProducts.getOrDefault(conceptKey, Set.of());
            Set<String> governedRuleSets = governedRuleSetsByConcept.getOrDefault(conceptKey, Set.of());
            Set<String> governedOperations = governedOperationsByConcept.getOrDefault(conceptKey, Set.of());
            Set<String> governedReasonCodes = governedReasonCodesByConcept.getOrDefault(conceptKey, Set.of());
            Set<String> explicitRuleSetRoots = explicitRuleSetRootsByConcept.getOrDefault(conceptKey, Set.of());

            Map<String, String> conceptFactRoots = new LinkedHashMap<>(
                    conceptFactRootMapByConcept.getOrDefault(conceptKey, Map.of())
            );
            Map<String, Object> updatedMeta = new LinkedHashMap<>(node.metadata() != null ? node.metadata() : Map.of());
            updatedMeta.put("semanticRole", semanticRole);
            if ("analytical_data_product".equals(semanticRole)) {
                updatedMeta.put("dataProduct", Boolean.TRUE);
                updatedMeta.put("analyzesConcepts", refs.stream().sorted().toList());
            }
            if (!refs.isEmpty()) {
                updatedMeta.put("referencedConcepts", refs.stream().sorted().toList());
            }
            if (!children.isEmpty()) {
                updatedMeta.put("childConcepts", children.stream().sorted().toList());
            }
            if (!parents.isEmpty()) {
                updatedMeta.put("parentConcepts", parents.stream().sorted().toList());
            }
            if (!dataProducts.isEmpty()) {
                updatedMeta.put("analyzedByDataProducts", dataProducts.stream().sorted().toList());
            }
            if (!conceptFactRoots.isEmpty()) {
                updatedMeta.put("conceptFactRootMap", Collections.unmodifiableMap(conceptFactRoots));
            }
            if (!governedRuleSets.isEmpty()) {
                Set<String> ontologicalFactRoots = new LinkedHashSet<>();
                ontologicalFactRoots.add(PraxisFactTreeBuilder.ROOT_DRAFT);
                ontologicalFactRoots.add(PraxisFactTreeBuilder.ROOT_CURRENT);
                ontologicalFactRoots.addAll(conceptFactRoots.keySet());
                for (String explicitRoot : explicitRuleSetRoots) {
                    if (!Set.of(
                            PraxisFactTreeBuilder.ROOT_DRAFT,
                            PraxisFactTreeBuilder.ROOT_CURRENT,
                            PraxisFactTreeBuilder.ROOT_NEIGHBORHOOD,
                            PraxisFactTreeBuilder.ROOT_CONTEXT
                    ).contains(explicitRoot)) {
                        ontologicalFactRoots.add(explicitRoot);
                    }
                }
                ontologicalFactRoots.add(PraxisFactTreeBuilder.ROOT_NEIGHBORHOOD);
                ontologicalFactRoots.add(PraxisFactTreeBuilder.ROOT_CONTEXT);
                updatedMeta.put("ontologicalFactRoots", List.copyOf(ontologicalFactRoots));
                Map<String, Set<String>> conceptPathsByRoot = factPathsByRootByConcept.getOrDefault(conceptKey, Map.of());
                if (!conceptPathsByRoot.isEmpty()) {
                    Map<String, List<String>> orderedPathsByRoot = new LinkedHashMap<>();
                    for (String root : ontologicalFactRoots) {
                        Set<String> paths = conceptPathsByRoot.getOrDefault(root, Set.of());
                        orderedPathsByRoot.put(root, List.copyOf(paths));
                    }
                    for (Map.Entry<String, Set<String>> rootEntry : conceptPathsByRoot.entrySet()) {
                        if (!orderedPathsByRoot.containsKey(rootEntry.getKey()) && !rootEntry.getValue().isEmpty()) {
                            orderedPathsByRoot.put(rootEntry.getKey(), List.copyOf(rootEntry.getValue()));
                        }
                    }
                    updatedMeta.put("ontologicalFactPathsByRoot", Collections.unmodifiableMap(orderedPathsByRoot));
                }
            }
            if (!governedRuleSets.isEmpty()) {
                updatedMeta.put("governedRuleSets", governedRuleSets.stream().sorted().toList());
                if (!governedOperations.isEmpty()) {
                    updatedMeta.put("governedOperations", governedOperations.stream().sorted().toList());
                }
                if (!governedReasonCodes.isEmpty()) {
                    updatedMeta.put("governedReasonCodes", governedReasonCodes.stream().sorted().toList());
                }
            }

            List<String> updatedTags = new ArrayList<>(node.tags() != null ? node.tags() : List.of());
            if ("analytical_data_product".equals(semanticRole)) {
                if (!updatedTags.contains("data-product")) {
                    updatedTags.add("data-product");
                }
                if (!updatedTags.contains("analytical-projection")) {
                    updatedTags.add("analytical-projection");
                }
            }
            if (!governedRuleSets.isEmpty() && !updatedTags.contains("rule-governed")) {
                updatedTags.add("rule-governed");
            }

            nodes.put(conceptKey, new DomainCatalogResponse.DomainNodeItem(
                    node.nodeKey(),
                    node.contextKey(),
                    node.nodeType(),
                    node.label(),
                    node.description(),
                    node.status(),
                    node.source(),
                    node.confidence(),
                    Map.copyOf(updatedMeta),
                    List.copyOf(updatedTags),
                    node.owner(),
                    node.semanticOwner(),
                    node.lifecycle(),
                    node.businessGlossary(),
                    node.resolution(),
                    node.sourceEvidenceKeys()
            ));
        }
    }

    private DomainCatalogResponse.DomainGovernanceItem classifyFieldGovernance(
            String fieldNodeKey,
            String fieldName,
            JsonNode effectiveSchema,
            CanonicalSchemaRef schemaRef,
            String schemaRole
    ) {
        DomainCatalogResponse.DomainGovernanceItem explicitGovernance =
                explicitFieldGovernance(fieldNodeKey, effectiveSchema, schemaRef, schemaRole);
        if (explicitGovernance != null) {
            return explicitGovernance;
        }

        String searchable = normalizeForSearch(String.join(" ",
                fieldName,
                effectiveSchema.path("title").asText(""),
                effectiveSchema.path("description").asText(""),
                effectiveSchema.path("format").asText("")
        ));

        if (containsAny(searchable, "senha", "password", "token", "secret", "credential", "credencial", "api key", "apikey")) {
            return governanceItem(fieldNodeKey, "security", "restricted", "credential",
                    List.of("INTERNAL_POLICY"), "deny", "deny", "deny", "deny",
                    schemaRef, schemaRole, "security-sensitive-field-name", "dto-field-heuristic", 0.78);
        }

        if (containsAny(searchable, "saude", "health", "medical", "medico", "cid", "diagnostico", "diagnosis")) {
            return governanceItem(fieldNodeKey, "privacy", "restricted", "sensitive_personal",
                    List.of("LGPD", "GDPR"), "mask", "deny", "review_required", "review_required",
                    schemaRef, schemaRole, "sensitive-personal-field-name", "dto-field-heuristic", 0.72);
        }

        if (containsAny(searchable, "cpf", "cnpj", "rg", "documento", "document", "identidade", "passport",
                "email", "e mail", "telefone", "phone", "celular", "mobile", "endereco", "address",
                "nascimento", "birth")) {
            return governanceItem(fieldNodeKey, "privacy", "confidential", "personal",
                    List.of("LGPD", "GDPR"), "mask", "deny", "review_required", "review_required",
                    schemaRef, schemaRole, "personal-data-field-name", "dto-field-heuristic", 0.74);
        }

        if (containsAny(searchable, "salario", "salary", "remuneracao", "remuneration", "valor liquido",
                "valorliquido", "amount", "pagamento", "payment", "payroll", "bonus", "beneficio",
                "benefit", "custo", "cost")) {
            return governanceItem(fieldNodeKey, "privacy", "confidential", "financial",
                    List.of("LGPD", "INTERNAL_POLICY"), "mask", "deny", "review_required", "allow",
                    schemaRef, schemaRole, "financial-field-name", "dto-field-heuristic", 0.72);
        }

        if (containsAny(searchable, "risco", "risk", "ameaca", "threat", "missao", "mission",
                "objetivo", "objective", "local", "location", "severidade", "severity",
                "incidente", "incident", "prioridade", "priority")) {
            return governanceItem(fieldNodeKey, "security", "confidential", "operational",
                    List.of("INTERNAL_POLICY"), "summarize_only", "deny", "review_required", "review_required",
                    schemaRef, schemaRole, "operational-risk-field-name", "dto-field-heuristic", 0.68);
        }

        if (containsAny(searchable, "regulatorio", "regulatory", "compliance", "jurisdicao",
                "jurisdiction", "acordo", "agreement", "licenca", "license", "homologacao",
                "homologation", "aprovacao", "approval", "bloqueio", "disabled", "blocked",
                "status")) {
            return governanceItem(fieldNodeKey, "compliance", "internal", "legal",
                    List.of("INTERNAL_POLICY", "REGULATORY"), "allow", "deny", "review_required", "allow",
                    schemaRef, schemaRole, "compliance-field-name", "dto-field-heuristic", 0.66);
        }

        return null;
    }

    private DomainCatalogResponse.DomainGovernanceItem explicitFieldGovernance(
            String fieldNodeKey,
            JsonNode effectiveSchema,
            CanonicalSchemaRef schemaRef,
            String schemaRole
    ) {
        return explicitGovernance(fieldNodeKey, effectiveSchema.path(DOMAIN_GOVERNANCE_EXTENSION),
                schemaRef, schemaRole, "java.annotation", true);
    }

    private DomainCatalogResponse.DomainGovernanceItem explicitGovernance(
            String nodeKey, JsonNode governanceNode, CanonicalSchemaRef schemaRef, String schemaRole,
            String defaultSource, boolean staticSchemaDeclaration
    ) {
        if (!governanceNode.isObject()) return null;
        String annotationType = governanceNode.path("annotationType").asText(null);
        String classification = governanceNode.path("classification").asText(null);
        String dataCategory = governanceNode.path("dataCategory").asText(null);
        if (staticSchemaDeclaration && (!StringUtils.hasText(annotationType)
                || !StringUtils.hasText(classification)
                || !StringUtils.hasText(dataCategory))) return null;

        Map<String, Object> aiUsage = governanceNode.path("aiUsage").isObject()
                ? HASH_OBJECT_MAPPER.convertValue(governanceNode.path("aiUsage"),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {})
                : new LinkedHashMap<>();
        if (staticSchemaDeclaration) {
            JsonNode declaredAiUsage = governanceNode.path("aiUsage");
            aiUsage.put("visibility", firstText(declaredAiUsage.path("visibility").asText(null), "allow"));
            aiUsage.put("trainingUse", firstText(declaredAiUsage.path("trainingUse").asText(null), "deny"));
            aiUsage.put("ruleAuthoring", firstText(declaredAiUsage.path("ruleAuthoring").asText(null), "review_required"));
            aiUsage.put("reasoningUse", firstText(declaredAiUsage.path("reasoningUse").asText(null), "allow"));
        }
        if (aiUsage.containsKey("visibility")) {
            aiUsage.putIfAbsent("valueEnforcementScope", "runtime_payload_only");
            aiUsage.putIfAbsent("catalogMetadataDiscoverable", Boolean.TRUE);
        }
        if (governanceNode.hasNonNull("reason")) {
            aiUsage.put("reason", governanceNode.path("reason").asText());
        } else if (staticSchemaDeclaration) {
            aiUsage.putIfAbsent("reason", "explicit-domain-governance");
        }
        if (schemaRef != null && schemaRef.schemaId() != null) aiUsage.put("schemaId", schemaRef.schemaId());
        if (schemaRole != null) aiUsage.put("schemaRole", schemaRole);
        List<String> declaredComplianceTags = textValues(governanceNode.path("complianceTags"));
        return new DomainCatalogResponse.DomainGovernanceItem(
                "governance:" + nodeKey + (staticSchemaDeclaration ? ":" + annotationType : ""),
                nodeKey, annotationType, classification, dataCategory,
                declaredComplianceTags,
                declaredComplianceTags != null && declaredComplianceTags.stream().anyMatch("NON_EXPORTABLE"::equalsIgnoreCase)
                        ? Boolean.FALSE : governanceNode.path("exportable").isBoolean() ? governanceNode.path("exportable").booleanValue() : null,
                governanceNode.path("owner").asText(null), governanceNode.path("steward").asText(null),
                governanceNode.path("retentionPolicy").asText(null), aiUsage,
                firstText(governanceNode.path("source").asText(null), defaultSource),
                governanceNode.path("confidence").isNumber() ? governanceNode.path("confidence").doubleValue() : 1.0d);
    }

    private DomainCatalogResponse.DomainGovernanceItem governanceItem(
            String fieldNodeKey,
            String annotationType,
            String classification,
            String dataCategory,
            List<String> complianceTags,
            String visibility,
            String trainingUse,
            String ruleAuthoring,
            String reasoningUse,
            CanonicalSchemaRef schemaRef,
            String schemaRole,
            String reason,
            String evidenceSource,
            Double confidence
    ) {
        String governanceKey = "governance:" + fieldNodeKey + ":" + annotationType;
        return new DomainCatalogResponse.DomainGovernanceItem(
                governanceKey,
                fieldNodeKey,
                annotationType,
                classification,
                dataCategory,
                complianceTags,
                complianceTags.stream().anyMatch("NON_EXPORTABLE"::equalsIgnoreCase) ? Boolean.FALSE : null,
                null,
                null,
                null,
                mapOfNonNull(
                        "visibility", visibility,
                        "trainingUse", trainingUse,
                        "ruleAuthoring", ruleAuthoring,
                        "reasoningUse", reasoningUse,
                        "valueEnforcementScope", "runtime_payload_only",
                        "catalogMetadataDiscoverable", Boolean.TRUE,
                        "reason", reason,
                        "schemaId", schemaRef != null ? schemaRef.schemaId() : null,
                        "schemaRole", schemaRole
                ),
                evidenceSource,
                confidence
        );
    }

    private void ensureContext(
            Map<String, DomainCatalogResponse.DomainContextItem> contexts,
            String contextKey,
            String group
    ) {
        contexts.putIfAbsent(contextKey, new DomainCatalogResponse.DomainContextItem(
                contextKey,
                labelFromKey(contextKey),
                null,
                null,
                StringUtils.hasText(group) ? "openapi-group" : "resource-key",
                "active",
                List.of(),
                0.75,
                StringUtils.hasText(group) ? group : contextKey,
                "active",
                glossary(labelFromKey(contextKey), null, List.of("bounded-context"))
        ));
    }

    private void ensureConcept(
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            String resourceKey,
            String contextKey,
            String resourcePath,
            String group,
            List<String> tags,
            String description,
            Map<String, Map<String, Boolean>> capabilitiesByResourcePath
    ) {
        DomainCatalogResponse.DomainNodeItem existing = nodes.get(resourceKey);
        if (existing != null && (!"concept".equals(existing.nodeType())
                || !contextKey.equals(existing.contextKey())
                || !serviceKey.equals(existing.metadata().get("ownerServiceKey"))))
            throw new IllegalStateException("Ambiguous concept definition identity: " + resourceKey);
        if (existing != null && !"concept-reference".equals(existing.source())) {
            if (!StringUtils.hasText(existing.description()) && StringUtils.hasText(description)) {
                nodes.put(resourceKey, new DomainCatalogResponse.DomainNodeItem(
                        existing.nodeKey(),
                        existing.contextKey(),
                        existing.nodeType(),
                        existing.label(),
                        description,
                        existing.status(),
                        existing.source(),
                        existing.confidence(),
                        existing.metadata(),
                        existing.tags(),
                        existing.owner(),
                        existing.semanticOwner(),
                        existing.lifecycle(),
                        glossary(existing.label(), description, existing.tags()),
                        existing.resolution(),
                        existing.sourceEvidenceKeys()
                ));
            }
            return;
        }

        Map<String, Boolean> capabilities = resolveResourceCapabilities(
                group,
                resourcePath,
                capabilitiesByResourcePath
        );
        Boolean readOnly = capabilities != null && !capabilities.isEmpty()
                ? Boolean.valueOf(FilteredSchemaProjection.readOnly(null, capabilities))
                : inferReadOnlyFromOpenApiPaths(group, resourcePath);

        nodes.put(resourceKey, new DomainCatalogResponse.DomainNodeItem(
                resourceKey,
                contextKey,
                "concept",
                labelFromKey(lastSegment(resourceKey)),
                blankToNull(description),
                "active",
                "api-resource",
                0.85,
                mapOfNonNull(
                        "projectionRole", "DEFINITION",
                        "ownerServiceKey", serviceKey,
                        "resourceKey", resourceKey,
                        "resourcePath", resourcePath,
                        "group", group,
                        "readOnly", readOnly,
                        "capabilities", capabilities
                ),
                nonNullList(tags),
                null,
                contextKey,
                "active",
                glossary(labelFromKey(lastSegment(resourceKey)), blankToNull(description), tags),
                resolution(resourceKey, matchKeys(resourceKey, resourcePath, lastSegment(resourceKey)), "exact-key-or-alias"),
                List.of()
        ));
    }

    private Boolean inferReadOnlyFromOpenApiPaths(String group, String resourcePath) {
        if (!StringUtils.hasText(resourcePath) || openApiDocumentService == null) {
            return null;
        }
        String normalizedResourcePath = normalizeResourcePath(resourcePath);
        if (!StringUtils.hasText(normalizedResourcePath)) {
            return null;
        }
        JsonNode paths = openApiPaths(group, resourcePath);
        if (paths == null || !paths.isObject()) {
            return null;
        }
        boolean hasAnyOperation = false;
        Iterator<Map.Entry<String, JsonNode>> entries = paths.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String normalizedPath = normalizeResourcePath(entry.getKey());
            if (!StringUtils.hasText(normalizedPath)
                    || !(normalizedPath.equals(normalizedResourcePath)
                    || normalizedPath.startsWith(normalizedResourcePath + "/"))) {
                continue;
            }
            JsonNode pathItem = entry.getValue();
            if (pathItem == null || !pathItem.isObject()) {
                continue;
            }
            hasAnyOperation = true;
            if ((normalizedPath.equals(normalizedResourcePath) && pathItem.has("post"))
                    || pathItem.has("put")
                    || pathItem.has("patch")
                    || pathItem.has("delete")
                    || normalizedPath.contains("/actions/")
                    || normalizedPath.contains("/bulk/")) {
                return Boolean.FALSE;
            }
        }
        return hasAnyOperation ? Boolean.TRUE : null;
    }

    private void ensureState(
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            String stateNodeKey,
            String contextKey,
            String state,
            String source,
            String evidenceKey
    ) {
        nodes.putIfAbsent(stateNodeKey, new DomainCatalogResponse.DomainNodeItem(
                stateNodeKey,
                contextKey,
                "state",
                labelFromKey(state),
                null,
                "active",
                source,
                0.9,
                mapOfNonNull("state", state, "evidenceKey", evidenceKey),
                List.of(),
                null,
                contextKey,
                "active",
                glossary(labelFromKey(state), null, List.of("state")),
                resolution(stateNodeKey, matchKeys(state, labelFromKey(state)), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));
    }

    private void putEdge(
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            String edgeKey,
            String sourceNodeKey,
            String targetNodeKey,
            String edgeType,
            String label,
            Double confidence,
            List<String> evidenceKeys
    ) {
        edges.putIfAbsent(edgeKey, new DomainCatalogResponse.DomainEdgeItem(
                edgeKey,
                sourceNodeKey,
                targetNodeKey,
                edgeType,
                label,
                confidence,
                evidenceKeys
        ));
    }

    private DomainCatalogResponse.DomainEvidenceItem evidence(
            String evidenceKey,
            String evidenceType,
            Map<String, Object> sourceRef,
            String summary,
            Double confidence
    ) {
        return new DomainCatalogResponse.DomainEvidenceItem(evidenceKey, evidenceType, sourceRef, summary, confidence);
    }

    private Map<String, String> resourceDescriptions(
            List<ActionDefinition> actions,
            List<SurfaceDefinition> surfaces
    ) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (SurfaceDefinition surface : sortSurfaces(surfaces)) {
            if ("response".equalsIgnoreCase(surface.schemaType())) {
                putResourceDescriptionIfAbsent(descriptions, surface.resourceKey(), surface.group(), surface.schema());
            }
        }
        for (ActionDefinition action : sortActions(actions)) {
            putResourceDescriptionIfAbsent(descriptions, action.resourceKey(), action.group(), action.responseSchema());
        }
        for (SurfaceDefinition surface : sortSurfaces(surfaces)) {
            putResourceDescriptionIfAbsent(descriptions, surface.resourceKey(), surface.group(), surface.schema());
        }
        for (ActionDefinition action : sortActions(actions)) {
            putResourceDescriptionIfAbsent(descriptions, action.resourceKey(), action.group(), action.requestSchema());
        }
        return descriptions;
    }

    private void putResourceDescriptionIfAbsent(
            Map<String, String> descriptions,
            String resourceKey,
            String group,
            CanonicalSchemaRef schemaRef
    ) {
        if (!StringUtils.hasText(resourceKey) || descriptions.containsKey(resourceKey)) {
            return;
        }
        String description = schemaDescription(group, schemaRef);
        if (StringUtils.hasText(description)) {
            descriptions.put(resourceKey, description);
        }
    }

    private String schemaDescription(String group, CanonicalSchemaRef schemaRef) {
        if (schemaRef == null || !StringUtils.hasText(schemaRef.schemaId()) || openApiDocumentService == null) {
            return null;
        }
        JsonNode schema = resolveSchema(group, schemaRef);
        if (schema == null || schema.isMissingNode()) {
            return null;
        }
        return unwrapArray(schema).path("description").asText(null);
    }

    private List<DomainCatalogResponse.DomainAliasItem> buildAliases(
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes
    ) {
        Map<String, DomainCatalogResponse.DomainAliasItem> aliases = new LinkedHashMap<>();
        for (DomainCatalogResponse.DomainNodeItem node : nodes.values()) {
            addAlias(aliases, node.nodeKey(), node.label(), "generated-label", node.confidence());
            Object fieldName = node.metadata() != null ? node.metadata().get("fieldName") : null;
            if (fieldName instanceof String value) {
                addAlias(aliases, node.nodeKey(), value, "schema-field-name", node.confidence());
            }
            Object canonicalAttributeId = node.metadata() != null ? node.metadata().get("canonicalAttributeId") : null;
            if (canonicalAttributeId instanceof String value) {
                addAlias(aliases, node.nodeKey(), value, "domain-lineage-attribute-id", node.confidence());
            }
            Object actionId = node.metadata() != null ? node.metadata().get("actionId") : null;
            if (actionId instanceof String value) {
                addAlias(aliases, node.nodeKey(), value, "workflow-action-id", node.confidence());
            }
            Object surfaceId = node.metadata() != null ? node.metadata().get("surfaceId") : null;
            if (surfaceId instanceof String value) {
                addAlias(aliases, node.nodeKey(), value, "ui-surface-id", node.confidence());
            }
            Object ruleSetKey = node.metadata() != null ? node.metadata().get("ruleSetKey") : null;
            if (ruleSetKey instanceof String value) {
                addAlias(aliases, node.nodeKey(), value, "rule-set-key", node.confidence());
            }
        }
        return List.copyOf(aliases.values());
    }

    private void addAlias(
            Map<String, DomainCatalogResponse.DomainAliasItem> aliases,
            String nodeKey,
            String alias,
            String source,
            Double confidence
    ) {
        if (!StringUtils.hasText(nodeKey) || !StringUtils.hasText(alias)) {
            return;
        }
        String normalizedAlias = alias.trim();
        if (nodeKey.equals(normalizedAlias)) {
            return;
        }
        String aliasKey = "alias:" + nodeKey + ":" + keyPart(source) + ":" + keyPart(normalizedAlias);
        aliases.putIfAbsent(aliasKey, new DomainCatalogResponse.DomainAliasItem(
                aliasKey,
                nodeKey,
                normalizedAlias,
                null,
                source,
                confidence
        ));
    }

    private Map<String, Object> glossary(String preferredTerm, String description, List<String> examples) {
        return mapOfNonNull(
                "preferredTerm", preferredTerm,
                "description", description,
                "examples", nonNullList(examples)
        );
    }

    private Map<String, Object> resolution(String canonicalKey, List<String> matchKeys, String ambiguityPolicy) {
        return mapOfNonNull(
                "canonicalKey", canonicalKey,
                "matchKeys", nonNullList(matchKeys),
                "ambiguityPolicy", ambiguityPolicy
        );
    }

    private List<String> matchKeys(String... values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return nonNullList(java.util.Arrays.asList(values));
    }

    private Map<String, Object> operationTarget(
            String id,
            String resourceKey,
            String resourcePath,
            org.praxisplatform.uischema.openapi.CanonicalOperationRef operation
    ) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", id);
        target.put("resourceKey", resourceKey);
        target.put("resourcePath", resourcePath);
        if (operation != null) {
            target.put("operationId", operation.operationId());
            target.put("path", operation.path());
            target.put("method", operation.method());
            target.put("group", operation.group());
        }
        return target;
    }

    private List<DomainCatalogResponse.DomainSchemaLink> schemaLinks(CanonicalSchemaRef... refs) {
        List<DomainCatalogResponse.DomainSchemaLink> links = new ArrayList<>();
        for (CanonicalSchemaRef ref : refs) {
            if (ref == null) {
                continue;
            }
            links.add(new DomainCatalogResponse.DomainSchemaLink(
                    ref.schemaType(),
                    ref.url(),
                    ref.schemaId(),
                    ref.schemaType()
            ));
        }
        return List.copyOf(links);
    }

    private List<ActionDefinition> sortActions(List<ActionDefinition> definitions) {
        return definitions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(ActionDefinition::resourceKey, Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(ActionDefinition::order)
                        .thenComparing(ActionDefinition::id, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private List<SurfaceDefinition> sortSurfaces(List<SurfaceDefinition> definitions) {
        return definitions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(SurfaceDefinition::resourceKey, Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(SurfaceDefinition::order)
                        .thenComparing(SurfaceDefinition::id, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<String> nonNullList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
    }

    private String contextKey(String resourceKey, String group) {
        if (StringUtils.hasText(resourceKey) && resourceKey.contains(".")) {
            return resourceKey.substring(0, resourceKey.indexOf('.'));
        }
        if (StringUtils.hasText(group)) {
            return keyPart(group);
        }
        return "default";
    }

    private boolean matchesRequestedScope(String resourceKey, String requestedResourceKey, String requestedGroup) {
        if (StringUtils.hasText(requestedResourceKey)) {
            return requestedResourceKey.equals(resourceKey);
        }
        if (StringUtils.hasText(requestedGroup)) {
            return requestedGroup.equals(contextKey(resourceKey, requestedGroup));
        }
        return true;
    }

    private Map<String, String> canonicalResourceKeysByPath(
            List<ActionDefinition> actions,
            List<SurfaceDefinition> surfaces
    ) {
        Map<String, String> resourceKeysByPath = new LinkedHashMap<>();
        for (ActionDefinition action : actions) {
            registerCanonicalResourceKey(resourceKeysByPath, action.resourcePath(), action.resourceKey());
        }
        for (SurfaceDefinition surface : surfaces) {
            registerCanonicalResourceKey(resourceKeysByPath, surface.resourcePath(), surface.resourceKey());
        }
        return Map.copyOf(resourceKeysByPath);
    }

    private void registerCanonicalResourceKey(
            Map<String, String> resourceKeysByPath,
            String resourcePath,
            String resourceKey
    ) {
        String normalizedPath = normalizeResourcePath(resourcePath);
        if (normalizedPath != null && StringUtils.hasText(resourceKey)) {
            resourceKeysByPath.putIfAbsent(normalizedPath, resourceKey.trim());
        }
    }

    private String resourceKeyForPath(String resourcePath, Map<String, String> canonicalResourceKeysByPath) {
        String normalizedPath = normalizeResourcePath(resourcePath);
        String canonicalResourceKey = normalizedPath == null
                ? null
                : canonicalResourceKeysByPath.get(normalizedPath);
        return StringUtils.hasText(canonicalResourceKey)
                ? canonicalResourceKey
                : resourceKeyFromPath(resourcePath);
    }

    private String resourceKeyFromPath(String resourcePath) {
        String normalizedPath = normalizeResourcePath(resourcePath);
        if (!StringUtils.hasText(normalizedPath)) {
            return "default.unknown";
        }
        String normalized = normalizedPath.replaceAll("^/+", "");
        if (normalized.startsWith("api/")) {
            normalized = normalized.substring("api/".length());
        }
        if (!StringUtils.hasText(normalized)) {
            return "default.unknown";
        }
        return normalized.replace('/', '.');
    }

    private String normalizeResourcePath(String resourcePath) {
        if (!StringUtils.hasText(resourcePath)) {
            return null;
        }
        String normalized = resourcePath.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String stateNodeKey(String resourceNodeKey, String state) {
        return resourceNodeKey + ".estado." + keyPart(state);
    }

    private JsonNode resolveSchema(String group, CanonicalSchemaRef schemaRef) {
        if (schemaRef == null) {
            return null;
        }
        return resolveSchemaFromDocument(resolveOpenApiDocument(group), schemaRef);
    }

    private JsonNode resolveSchemaFromDocument(JsonNode document, CanonicalSchemaRef schemaRef) {
        if (schemaRef == null || document == null || document.isMissingNode()) {
            return null;
        }
        JsonNode directSchema = resolveComponentSchema(document, schemaRef.schemaId());
        if (directSchema != null && !directSchema.isMissingNode()) {
            return directSchema;
        }
        return resolveSchemaFromCanonicalUrl(document, schemaRef);
    }

    private JsonNode resolveOpenApiDocument(String group) {
        if (Boolean.TRUE.equals(strictSchemaResolution.get())) {
            if (!StringUtils.hasText(group)) {
                throw new IllegalStateException("Explicit OpenAPI group is required for strict domain catalog resolution");
            }
            JsonNode document = openApiDocumentService.getDocumentForGroupStrict(group.trim());
            if (document == null || document.isMissingNode() || !document.isObject()) {
                throw new IllegalStateException("Strict OpenAPI document is missing for group: " + group);
            }
            return document;
        }
        try {
            return openApiDocumentService.getDocumentForGroup(
                    StringUtils.hasText(group) ? group : "application"
            );
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private JsonNode openApiPaths(String group, String resourcePath) {
        String resolvedGroup = StringUtils.hasText(group)
                ? group
                : openApiDocumentService != null ? openApiDocumentService.resolveGroupFromPath(resourcePath) : null;
        if (!StringUtils.hasText(resolvedGroup)) {
            return null;
        }
        if (Boolean.TRUE.equals(strictSchemaResolution.get())) {
            JsonNode document = resolveOpenApiDocument(resolvedGroup);
            return document.path("paths");
        }
        try {
            JsonNode document = openApiDocumentService.getDocumentForGroup(resolvedGroup);
            return document == null ? null : document.path("paths");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Map<String, Boolean> resolveResourceCapabilities(
            String group,
            String resourcePath,
            Map<String, Map<String, Boolean>> cache
    ) {
        if (canonicalCapabilityResolver == null || !StringUtils.hasText(resourcePath)) {
            return null;
        }
        String cacheKey = firstText(normalizeResourcePath(resourcePath), resourcePath.trim());
        if (cache != null && cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        try {
            String resolvedGroup = StringUtils.hasText(group)
                    ? group
                    : openApiDocumentService != null ? openApiDocumentService.resolveGroupFromPath(resourcePath) : null;
            JsonNode document = openApiDocumentService != null && StringUtils.hasText(resolvedGroup)
                    ? (Boolean.TRUE.equals(strictSchemaResolution.get())
                            ? resolveOpenApiDocument(resolvedGroup)
                            : openApiDocumentService.getDocumentForGroup(resolvedGroup))
                    : null;
            Map<String, Boolean> resolved = document != null
                    ? canonicalCapabilityResolver.resolve(document, resourcePath)
                    : canonicalCapabilityResolver.resolve(resourcePath);
            Map<String, Boolean> normalized = resolved != null && !resolved.isEmpty() ? resolved : null;
            if (cache != null) {
                cache.put(cacheKey, normalized);
            }
            return normalized;
        } catch (RuntimeException ex) {
            if (Boolean.TRUE.equals(strictSchemaResolution.get())) {
                throw ex;
            }
            if (cache != null) {
                cache.put(cacheKey, null);
            }
            return null;
        }
    }

    private boolean isStatsCapabilitySupported(Map<String, Boolean> canonicalCapabilities, String capabilityKind) {
        if (canonicalCapabilities == null || canonicalCapabilities.isEmpty()) {
            return true;
        }
        String operationKey = switch (capabilityKind) {
            case "groupBy" -> "statsGroupBy";
            case "timeSeries" -> "statsTimeSeries";
            case "distribution" -> "statsDistribution";
            case "comparison" -> "statsComparison";
            default -> null;
        };
        return operationKey != null && Boolean.TRUE.equals(canonicalCapabilities.get(operationKey));
    }

    private String statsCapabilityKind(String resourcePath, String path) {
        if (!StringUtils.hasText(resourcePath) || !StringUtils.hasText(path)) {
            return null;
        }
        String normalizedResourcePath = resourcePath.replaceAll("/+$", "");
        String normalizedPath = path.replaceAll("/+$", "");
        if (!normalizedPath.startsWith(normalizedResourcePath + "/stats/")) {
            return null;
        }
        String suffix = normalizedPath.substring((normalizedResourcePath + "/stats/").length());
        if (!List.of("group-by", "timeseries", "distribution", "comparison").contains(suffix)) {
            return null;
        }
        return switch (suffix) {
            case "group-by" -> "groupBy";
            case "timeseries" -> "timeSeries";
            default -> suffix;
        };
    }

    private String firstMethod(JsonNode pathItem) {
        if (pathItem == null || !pathItem.isObject()) {
            return null;
        }
        for (String method : List.of("post", "get", "put", "patch", "delete")) {
            if (pathItem.has(method)) {
                return method.toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private JsonNode resolveComponentSchema(JsonNode document, String schemaId) {
        if (!StringUtils.hasText(schemaId)) {
            return null;
        }
        JsonNode schema = document.path("components").path("schemas").path(schemaId);
        return schema.isMissingNode() ? null : schema;
    }

    private JsonNode resolveSchemaFromCanonicalUrl(JsonNode document, CanonicalSchemaRef schemaRef) {
        Map<String, String> query = parseQuery(schemaRef.url());
        String path = query.get("path");
        String operation = firstText(query.get("operation"), "get").toLowerCase(Locale.ROOT);
        String schemaType = firstText(query.get("schemaType"), schemaRef.schemaType());
        if (!StringUtils.hasText(path)) {
            return null;
        }

        JsonNode operationNode = document.path("paths")
                .path(openApiDocumentService.resolveDocumentPath(document.path("paths"), path))
                .path(operation);
        if (operationNode.isMissingNode()) {
            return null;
        }
        JsonNode allSchemas = document.path("components").path("schemas");
        if ("request".equalsIgnoreCase(schemaType)) {
            return new FilteredSchemaProjection(HASH_OBJECT_MAPPER).select(document,
                    openApiDocumentService.resolveDocumentPath(document.path("paths"), path, operation), operation, "request").schema();
        }

        JsonNode xUiResponseSchema = operationNode.path("x-ui").path("responseSchema");
        if (xUiResponseSchema.isTextual()) {
            JsonNode schema = resolveComponentSchema(document, xUiResponseSchema.asText());
            if (schema != null && !schema.isMissingNode()) {
                return schema;
            }
        }

        JsonNode responseSchema = selectPreferredContentNode(operationNode.path("responses").path("200").path("content")).path("schema");
        if (responseSchema.isMissingNode()) {
            responseSchema = selectPreferredContentNode(operationNode.path("responses").path("201").path("content")).path("schema");
        }
        return resolveDomainSchema(responseSchema, allSchemas, new LinkedHashSet<>());
    }

    private JsonNode resolveDomainSchema(JsonNode schemaNode, JsonNode allSchemas, Set<String> visited) {
        if (schemaNode == null || schemaNode.isMissingNode() || schemaNode.isNull()) {
            return null;
        }
        if (schemaNode.has("$ref")) {
            String schemaName = extractSchemaNameFromRef(schemaNode.path("$ref").asText());
            if (!StringUtils.hasText(schemaName) || !visited.add(schemaName)) {
                return null;
            }
            JsonNode referencedSchema = allSchemas.path(schemaName);
            JsonNode nested = resolveDomainSchema(referencedSchema, allSchemas, visited);
            if (nested != null && !nested.isMissingNode()) {
                return nested;
            }
            return referencedSchema.isMissingNode() ? null : referencedSchema;
        }
        if (schemaNode.has("items")) {
            JsonNode nested = resolveDomainSchema(schemaNode.path("items"), allSchemas, visited);
            if (nested != null && !nested.isMissingNode()) {
                return nested;
            }
        }

        JsonNode properties = schemaNode.path("properties");
        for (String wrapperProperty : List.of("data", "content")) {
            JsonNode nestedProperty = properties.path(wrapperProperty);
            JsonNode nested = resolveDomainSchema(nestedProperty, allSchemas, visited);
            if (nested != null && !nested.isMissingNode() && hasDomainProperties(nested)) {
                return nested;
            }
        }

        JsonNode allOf = schemaNode.path("allOf");
        if (allOf.isArray()) {
            return schemaNode; // all intersection branches contribute fields; never select the first one
        }

        JsonNode oneOf = schemaNode.path("oneOf");
        if (oneOf.isArray()) {
            for (JsonNode candidate : oneOf) {
                JsonNode nested = resolveDomainSchema(candidate, allSchemas, visited);
                if (nested != null && !nested.isMissingNode() && hasDomainProperties(nested)) {
                    return nested;
                }
            }
        }

        return schemaNode;
    }

    private boolean hasDomainProperties(JsonNode schemaNode) {
        JsonNode properties = unwrapArray(schemaNode).path("properties");
        if (!properties.isObject()) {
            return false;
        }
        Iterator<String> fieldNames = properties.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!Set.of("status", "message", "errors", "links", "_links", "page").contains(fieldName)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode selectPreferredContentNode(JsonNode contentRoot) {
        if (contentRoot == null || contentRoot.isMissingNode()) {
            return contentRoot;
        }
        JsonNode applicationJson = contentRoot.path("application/json");
        if (!applicationJson.isMissingNode()) {
            return applicationJson;
        }
        JsonNode any = contentRoot.path("*/*");
        if (!any.isMissingNode()) {
            return any;
        }
        Iterator<JsonNode> values = contentRoot.elements();
        return values.hasNext() ? values.next() : contentRoot;
    }

    private Map<String, String> parseQuery(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!StringUtils.hasText(url) || !url.contains("?")) {
            return values;
        }
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            if (!StringUtils.hasText(pair)) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String extractSchemaNameFromRef(String ref) {
        if (!StringUtils.hasText(ref)) {
            return null;
        }
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private JsonNode unwrapArray(JsonNode schema) {
        if (schema != null && "array".equals(schema.path("type").asText(null)) && schema.has("items")) {
            return schema.path("items");
        }
        return schema;
    }

    private Set<String> requiredFields(JsonNode requiredNode) {
        if (requiredNode == null || !requiredNode.isArray()) {
            return Set.of();
        }
        Set<String> required = new LinkedHashSet<>();
        requiredNode.forEach(node -> {
            if (node != null && node.isTextual()) {
                required.add(node.asText());
            }
        });
        return required;
    }

    private List<String> textValues(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (value != null && !value.isNull()) {
                values.add(value.asText());
            }
        });
        return values.isEmpty() ? null : List.copyOf(values);
    }

    private Number numericValue(JsonNode node) {
        return node != null && node.isNumber() ? node.numberValue() : null;
    }

    private boolean containsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && value.contains(normalizeForSearch(candidate))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForSearch(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String keyPart(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.trim()
                .replace('_', '-')
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9.-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }

    private String labelFromKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "Default";
        }
        String normalized = value
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('.', ' ')
                .replace('-', ' ')
                .replace('_', ' ')
                .trim();
        if (!StringUtils.hasText(normalized)) {
            return value;
        }
        String[] parts = normalized.split("\\s+");
        List<String> labels = new ArrayList<>();
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            labels.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1).toLowerCase(Locale.ROOT));
        }
        return String.join(" ", labels);
    }

    private String lastSegment(String value) {
        if (!StringUtils.hasText(value) || !value.contains(".")) {
            return value;
        }
        return value.substring(value.lastIndexOf('.') + 1);
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private String sourceHash(
            String resourceKey,
            String group,
            List<DomainCatalogResponse.DomainContextItem> contexts,
            List<DomainCatalogResponse.DomainNodeItem> nodes,
            List<DomainCatalogResponse.DomainEdgeItem> edges,
            List<DomainCatalogResponse.DomainBindingItem> bindings,
            List<DomainCatalogResponse.DomainAliasItem> aliases,
            List<DomainCatalogResponse.DomainEvidenceItem> evidence,
            List<DomainCatalogResponse.DomainGovernanceItem> governance
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("service", new DomainCatalogResponse.DomainServiceInfo(serviceKey, serviceName, blankToNull(serviceVersion)));
        payload.put("scope", mapOfNonNull(
                "resourceKey", resourceKey,
                "group", group
        ));
        payload.put("contexts", contexts);
        payload.put("nodes", nodes);
        payload.put("edges", edges);
        payload.put("bindings", bindings);
        payload.put("aliases", aliases);
        payload.put("evidence", evidence);
        payload.put("governance", governance);
        JsonNode canonical = HASH_CANONICALIZER.canonicalize(HASH_OBJECT_MAPPER.valueToTree(payload));
        return SchemaHashUtil.sha256Hex(canonical);
    }

    private String releaseKey(String resourceKey, String group, String sourceHash) {
        String scope = StringUtils.hasText(resourceKey) ? resourceKey : StringUtils.hasText(group) ? group : "all";
        String hashPart = StringUtils.hasText(sourceHash) && sourceHash.length() > 16
                ? sourceHash.substring(0, 16)
                : firstText(sourceHash, "unknown");
        return serviceKey + ":" + keyPart(scope) + ":" + hashPart;
    }

    private Map<String, Object> mapOfNonNull(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                values.put(String.valueOf(key), value);
            }
        }
        return values;
    }
}
