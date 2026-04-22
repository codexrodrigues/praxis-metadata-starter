package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupSelectionPolicy;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.SurfaceDefinition;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Gera o vocabulario semantico inicial a partir das superficies metadata-driven ja existentes.
 *
 * <p>
 * Esta primeira versao deriva dominio de actions e surfaces anotadas. Ela nao interpreta services
 * nem executa regras; apenas materializa conceitos, estados, operacoes e bindings explicaveis.
 * </p>
 */
public class SemanticDomainCatalogService {

    public static final String SCHEMA_VERSION = "praxis.domain-catalog/v0.2";

    private final ActionDefinitionRegistry actionDefinitionRegistry;
    private final SurfaceDefinitionRegistry surfaceDefinitionRegistry;
    private final OptionSourceRegistry optionSourceRegistry;
    private final OpenApiDocumentService openApiDocumentService;
    private final Clock clock;
    private final String serviceKey;
    private final String serviceName;
    private final String serviceVersion;

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
        this.actionDefinitionRegistry = actionDefinitionRegistry;
        this.surfaceDefinitionRegistry = surfaceDefinitionRegistry;
        this.optionSourceRegistry = optionSourceRegistry == null ? OptionSourceRegistry.empty() : optionSourceRegistry;
        this.openApiDocumentService = openApiDocumentService;
        this.clock = clock;
        this.serviceKey = serviceKey;
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
    }

    public DomainCatalogResponse findByResourceKey(String resourceKey) {
        List<ActionDefinition> actions = safe(actionDefinitionRegistry.findByResourceKey(resourceKey));
        List<SurfaceDefinition> surfaces = safe(surfaceDefinitionRegistry.findByResourceKey(resourceKey));
        return build(resourceKey, null, actions, surfaces);
    }

    public DomainCatalogResponse findByGroup(String group) {
        List<ActionDefinition> actions = safe(actionDefinitionRegistry.findByGroup(group));
        List<SurfaceDefinition> surfaces = safe(surfaceDefinitionRegistry.findByGroup(group));
        return build(null, group, actions, surfaces);
    }

    private DomainCatalogResponse build(
            String requestedResourceKey,
            String requestedGroup,
            List<ActionDefinition> actions,
            List<SurfaceDefinition> surfaces
    ) {
        Map<String, DomainCatalogResponse.DomainContextItem> contexts = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainNodeItem> nodes = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainEdgeItem> edges = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainBindingItem> bindings = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence = new LinkedHashMap<>();
        Map<String, DomainCatalogResponse.DomainGovernanceItem> governance = new LinkedHashMap<>();

        for (ActionDefinition action : sortActions(actions)) {
            String resourceKey = action.resourceKey();
            String contextKey = contextKey(resourceKey, action.group());
            ensureContext(contexts, contextKey, action.group());
            ensureConcept(nodes, resourceKey, contextKey, action.resourcePath(), action.group(), action.tags());
            addAction(action, contextKey, nodes, edges, bindings, evidence);
            addSchemaFields(action.resourceKey(), contextKey, action.group(), action.requestSchema(), "request",
                    nodes, edges, bindings, evidence, governance);
            addSchemaFields(action.resourceKey(), contextKey, action.group(), action.responseSchema(), "response",
                    nodes, edges, bindings, evidence, governance);
        }

        for (SurfaceDefinition surface : sortSurfaces(surfaces)) {
            String resourceKey = surface.resourceKey();
            String contextKey = contextKey(resourceKey, surface.group());
            ensureContext(contexts, contextKey, surface.group());
            ensureConcept(nodes, resourceKey, contextKey, surface.resourcePath(), surface.group(), surface.tags());
            addSurface(surface, contextKey, nodes, edges, bindings, evidence);
            addSchemaFields(surface.resourceKey(), contextKey, surface.group(), surface.schema(), surface.schemaType(),
                    nodes, edges, bindings, evidence, governance);
        }

        for (OptionSourceDescriptor descriptor : optionSourceRegistry.descriptors()) {
            String resourceKey = resourceKeyFromPath(descriptor.resourcePath());
            if (!matchesRequestedScope(resourceKey, requestedResourceKey, requestedGroup)) {
                continue;
            }
            String contextKey = contextKey(resourceKey, requestedGroup);
            ensureContext(contexts, contextKey, requestedGroup);
            ensureConcept(nodes, resourceKey, contextKey, descriptor.resourcePath(), requestedGroup, List.of("option-source"));
            addOptionSource(descriptor, resourceKey, contextKey, nodes, edges, bindings, evidence);
        }

        Instant generatedAt = Instant.now(clock);
        return new DomainCatalogResponse(
                SCHEMA_VERSION,
                new DomainCatalogResponse.DomainServiceInfo(serviceKey, serviceName, blankToNull(serviceVersion)),
                new DomainCatalogResponse.DomainReleaseInfo(
                        releaseKey(requestedResourceKey, requestedGroup, generatedAt),
                        generatedAt.toString(),
                        null
                ),
                List.copyOf(contexts.values()),
                List.copyOf(nodes.values()),
                List.copyOf(edges.values()),
                List.copyOf(bindings.values()),
                buildAliases(nodes),
                List.copyOf(evidence.values()),
                List.copyOf(governance.values())
        );
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
                        "resourceKey", action.resourceKey()
                ),
                nonNullList(action.tags()),
                null,
                action.group(),
                "active",
                glossary(firstText(action.title(), action.id()), action.description(), action.tags()),
                resolution(actionNodeKey, List.of(action.id(), action.title()), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));

        putEdge(edges, resourceNodeKey + ".has-action." + keyPart(action.id()), resourceNodeKey, actionNodeKey,
                "has_action", "Possui action " + firstText(action.title(), action.id()), 0.95, List.of(evidenceKey));

        for (String allowedState : nonNullList(action.allowedStates())) {
            String stateNodeKey = stateNodeKey(resourceNodeKey, allowedState);
            ensureState(nodes, stateNodeKey, contextKey, allowedState, "workflow-action.allowedStates", evidenceKey);
            putEdge(edges, actionNodeKey + ".allowed-state." + keyPart(allowedState), actionNodeKey, stateNodeKey,
                    "allowed_in_state", "Permitida no estado " + allowedState, 0.95, List.of(evidenceKey));
        }

        bindings.putIfAbsent("binding:" + actionNodeKey + ":workflow-action", new DomainCatalogResponse.DomainBindingItem(
                "binding:" + actionNodeKey + ":workflow-action",
                actionNodeKey,
                "workflow_action",
                operationTarget(action.id(), action.resourceKey(), action.resourcePath(), action.operation()),
                schemaLinks(action.requestSchema(), action.responseSchema()),
                List.of(evidenceKey)
        ));

        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "annotation",
                mapOfNonNull("kind", "java.annotation", "annotation", "WorkflowAction", "actionId", action.id()),
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

        nodes.putIfAbsent(surfaceNodeKey, new DomainCatalogResponse.DomainNodeItem(
                surfaceNodeKey,
                contextKey,
                "surface",
                firstText(surface.title(), surface.id()),
                surface.description(),
                "active",
                "ui-surface",
                0.95,
                mapOfNonNull(
                        "surfaceId", surface.id(),
                        "kind", surface.kind() != null ? surface.kind().name() : null,
                        "scope", surface.scope() != null ? surface.scope().name() : null,
                        "intent", surface.intent(),
                        "resourceKey", surface.resourceKey()
                ),
                nonNullList(surface.tags()),
                null,
                surface.group(),
                "active",
                glossary(firstText(surface.title(), surface.id()), surface.description(), surface.tags()),
                resolution(surfaceNodeKey, List.of(surface.id(), surface.title(), surface.intent()), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));

        putEdge(edges, resourceNodeKey + ".has-surface." + keyPart(surface.id()), resourceNodeKey, surfaceNodeKey,
                "has_surface", "Possui surface " + firstText(surface.title(), surface.id()), 0.95, List.of(evidenceKey));

        for (String allowedState : nonNullList(surface.allowedStates())) {
            String stateNodeKey = stateNodeKey(resourceNodeKey, allowedState);
            ensureState(nodes, stateNodeKey, contextKey, allowedState, "ui-surface.allowedStates", evidenceKey);
            putEdge(edges, surfaceNodeKey + ".allowed-state." + keyPart(allowedState), surfaceNodeKey, stateNodeKey,
                    "allowed_in_state", "Disponivel no estado " + allowedState, 0.95, List.of(evidenceKey));
        }

        bindings.putIfAbsent("binding:" + surfaceNodeKey + ":ui-surface", new DomainCatalogResponse.DomainBindingItem(
                "binding:" + surfaceNodeKey + ":ui-surface",
                surfaceNodeKey,
                "ui_surface",
                operationTarget(surface.id(), surface.resourceKey(), surface.resourcePath(), surface.operation()),
                schemaLinks(surface.schema()),
                List.of(evidenceKey)
        ));

        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "annotation",
                mapOfNonNull("kind", "java.annotation", "annotation", "UiSurface", "surfaceId", surface.id()),
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
                resolution(policyNodeKey, List.of(descriptor.key(), descriptor.resourcePath()), "exact-key-or-alias"),
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
                    "uses_concept", "Usa option-source " + descriptor.key(), 0.85, List.of(evidenceKey));
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

    private void addSchemaFields(
            String resourceKey,
            String contextKey,
            String group,
            CanonicalSchemaRef schemaRef,
            String schemaRole,
            Map<String, DomainCatalogResponse.DomainNodeItem> nodes,
            Map<String, DomainCatalogResponse.DomainEdgeItem> edges,
            Map<String, DomainCatalogResponse.DomainBindingItem> bindings,
            Map<String, DomainCatalogResponse.DomainEvidenceItem> evidence,
            Map<String, DomainCatalogResponse.DomainGovernanceItem> governance
    ) {
        if (schemaRef == null || !StringUtils.hasText(schemaRef.schemaId()) || openApiDocumentService == null) {
            return;
        }
        JsonNode schema = resolveSchema(group, schemaRef);
        if (schema == null || schema.isMissingNode()) {
            return;
        }
        JsonNode properties = unwrapArray(schema).path("properties");
        if (!properties.isObject()) {
            return;
        }
        Set<String> requiredFields = requiredFields(unwrapArray(schema).path("required"));
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            addField(resourceKey, contextKey, schemaRef, schemaRole, field.getKey(), field.getValue(),
                    requiredFields.contains(field.getKey()), nodes, edges, bindings, evidence, governance);
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

        nodes.putIfAbsent(fieldNodeKey, new DomainCatalogResponse.DomainNodeItem(
                fieldNodeKey,
                contextKey,
                "field",
                labelFromKey(fieldName),
                effectiveSchema.path("description").asText(null),
                "active",
                "dto_schema",
                0.85,
                mapOfNonNull(
                        "fieldName", fieldName,
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
                        "pattern", effectiveSchema.path("pattern").asText(null)
                ),
                List.of("dto-field", schemaRole),
                null,
                contextKey,
                "active",
                glossary(labelFromKey(fieldName), effectiveSchema.path("description").asText(null), List.of("dto-field", schemaRole)),
                resolution(fieldNodeKey, List.of(fieldName, labelFromKey(fieldName), schemaRef.schemaId()), "exact-key-or-alias"),
                List.of(evidenceKey)
        ));

        putEdge(edges, resourceKey + ".has-field." + keyPart(fieldName), resourceKey, fieldNodeKey,
                "has_field", "Possui campo " + fieldName, 0.85, List.of(evidenceKey));

        bindings.putIfAbsent("binding:" + fieldNodeKey + ":dto-field", new DomainCatalogResponse.DomainBindingItem(
                "binding:" + fieldNodeKey + ":dto-field",
                fieldNodeKey,
                "dto_field",
                mapOfNonNull(
                        "schemaId", schemaRef.schemaId(),
                        "schemaType", schemaRef.schemaType(),
                        "schemaRole", schemaRole,
                        "fieldName", fieldName
                ),
                List.of(new DomainCatalogResponse.DomainSchemaLink(
                        schemaRole,
                        schemaRef.url(),
                        schemaRef.schemaId(),
                        schemaRef.schemaType()
                )),
                List.of(evidenceKey)
        ));

        evidence.putIfAbsent(evidenceKey, evidence(
                evidenceKey,
                "dto_schema",
                mapOfNonNull(
                        "kind", "openapi.schema.property",
                        "schemaId", schemaRef.schemaId(),
                        "schemaRole", schemaRole,
                        "fieldName", fieldName
                ),
                "Campo derivado do schema OpenAPI " + schemaRef.schemaId() + ".",
                0.9
        ));

        DomainCatalogResponse.DomainGovernanceItem fieldGovernance =
                classifyFieldGovernance(fieldNodeKey, fieldName, effectiveSchema, schemaRef, schemaRole);
        if (fieldGovernance != null) {
            governance.putIfAbsent(fieldGovernance.governanceKey(), fieldGovernance);
        }
    }

    private DomainCatalogResponse.DomainGovernanceItem classifyFieldGovernance(
            String fieldNodeKey,
            String fieldName,
            JsonNode effectiveSchema,
            CanonicalSchemaRef schemaRef,
            String schemaRole
    ) {
        String searchable = normalizeForSearch(String.join(" ",
                fieldName,
                effectiveSchema.path("title").asText(""),
                effectiveSchema.path("description").asText(""),
                effectiveSchema.path("format").asText("")
        ));

        if (containsAny(searchable, "senha", "password", "token", "secret", "credential", "credencial", "api key", "apikey")) {
            return governanceItem(fieldNodeKey, "security", "restricted", "credential",
                    List.of("INTERNAL_POLICY"), "deny", "deny", "deny", "deny",
                    schemaRef, schemaRole, "security-sensitive-field-name", 0.78);
        }

        if (containsAny(searchable, "saude", "health", "medical", "medico", "cid", "diagnostico", "diagnosis")) {
            return governanceItem(fieldNodeKey, "privacy", "restricted", "sensitive_personal",
                    List.of("LGPD", "GDPR"), "mask", "deny", "review_required", "review_required",
                    schemaRef, schemaRole, "sensitive-personal-field-name", 0.72);
        }

        if (containsAny(searchable, "cpf", "cnpj", "rg", "documento", "document", "identidade", "passport",
                "email", "e mail", "telefone", "phone", "celular", "mobile", "endereco", "address",
                "nascimento", "birth")) {
            return governanceItem(fieldNodeKey, "privacy", "confidential", "personal",
                    List.of("LGPD", "GDPR"), "mask", "deny", "review_required", "review_required",
                    schemaRef, schemaRole, "personal-data-field-name", 0.74);
        }

        if (containsAny(searchable, "salario", "salary", "remuneracao", "remuneration", "valor liquido",
                "valorliquido", "amount", "pagamento", "payment", "payroll", "bonus", "beneficio",
                "benefit", "custo", "cost")) {
            return governanceItem(fieldNodeKey, "privacy", "confidential", "financial",
                    List.of("LGPD", "INTERNAL_POLICY"), "mask", "deny", "review_required", "allow",
                    schemaRef, schemaRole, "financial-field-name", 0.72);
        }

        if (containsAny(searchable, "risco", "risk", "ameaca", "threat", "missao", "mission",
                "objetivo", "objective", "local", "location", "severidade", "severity",
                "incidente", "incident", "prioridade", "priority")) {
            return governanceItem(fieldNodeKey, "security", "confidential", "operational",
                    List.of("INTERNAL_POLICY"), "summarize_only", "deny", "review_required", "review_required",
                    schemaRef, schemaRole, "operational-risk-field-name", 0.68);
        }

        if (containsAny(searchable, "regulatorio", "regulatory", "compliance", "jurisdicao",
                "jurisdiction", "acordo", "agreement", "licenca", "license", "homologacao",
                "homologation", "aprovacao", "approval", "bloqueio", "disabled", "blocked",
                "status")) {
            return governanceItem(fieldNodeKey, "compliance", "internal", "legal",
                    List.of("INTERNAL_POLICY", "REGULATORY"), "allow", "deny", "review_required", "allow",
                    schemaRef, schemaRole, "compliance-field-name", 0.66);
        }

        return null;
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
                null,
                null,
                null,
                mapOfNonNull(
                        "visibility", visibility,
                        "trainingUse", trainingUse,
                        "ruleAuthoring", ruleAuthoring,
                        "reasoningUse", reasoningUse,
                        "reason", reason,
                        "schemaId", schemaRef != null ? schemaRef.schemaId() : null,
                        "schemaRole", schemaRole
                ),
                "dto-field-heuristic",
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
            List<String> tags
    ) {
        nodes.putIfAbsent(resourceKey, new DomainCatalogResponse.DomainNodeItem(
                resourceKey,
                contextKey,
                "concept",
                labelFromKey(lastSegment(resourceKey)),
                null,
                "active",
                "api-resource",
                0.85,
                mapOfNonNull(
                        "resourceKey", resourceKey,
                        "resourcePath", resourcePath,
                        "group", group
                ),
                nonNullList(tags),
                null,
                contextKey,
                "active",
                glossary(labelFromKey(lastSegment(resourceKey)), null, tags),
                resolution(resourceKey, List.of(resourceKey, resourcePath, lastSegment(resourceKey)), "exact-key-or-alias"),
                List.of()
        ));
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
                resolution(stateNodeKey, List.of(state, labelFromKey(state)), "exact-key-or-alias"),
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
            Object actionId = node.metadata() != null ? node.metadata().get("actionId") : null;
            if (actionId instanceof String value) {
                addAlias(aliases, node.nodeKey(), value, "workflow-action-id", node.confidence());
            }
            Object surfaceId = node.metadata() != null ? node.metadata().get("surfaceId") : null;
            if (surfaceId instanceof String value) {
                addAlias(aliases, node.nodeKey(), value, "ui-surface-id", node.confidence());
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

    private String resourceKeyFromPath(String resourcePath) {
        if (!StringUtils.hasText(resourcePath)) {
            return "default.unknown";
        }
        String normalized = resourcePath.trim()
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");
        if (normalized.startsWith("api/")) {
            normalized = normalized.substring("api/".length());
        }
        if (!StringUtils.hasText(normalized)) {
            return "default.unknown";
        }
        return normalized.replace('/', '.');
    }

    private String stateNodeKey(String resourceNodeKey, String state) {
        return resourceNodeKey + ".estado." + keyPart(state);
    }

    private JsonNode resolveSchema(String group, CanonicalSchemaRef schemaRef) {
        if (schemaRef == null) {
            return null;
        }
        JsonNode document = resolveOpenApiDocument(group);
        if (document == null || document.isMissingNode()) {
            return null;
        }
        JsonNode directSchema = resolveComponentSchema(document, schemaRef.schemaId());
        if (directSchema != null && !directSchema.isMissingNode()) {
            return directSchema;
        }
        return resolveSchemaFromCanonicalUrl(document, schemaRef);
    }

    private JsonNode resolveOpenApiDocument(String group) {
        try {
            return openApiDocumentService.getDocumentForGroup(
                    StringUtils.hasText(group) ? group : "application"
            );
        } catch (RuntimeException ex) {
            return null;
        }
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

        JsonNode operationNode = document.path("paths").path(resolveDocumentPath(document.path("paths"), path)).path(operation);
        if (operationNode.isMissingNode()) {
            return null;
        }
        JsonNode allSchemas = document.path("components").path("schemas");
        if ("request".equalsIgnoreCase(schemaType)) {
            JsonNode requestSchema = selectPreferredContentNode(operationNode.path("requestBody").path("content")).path("schema");
            return resolveDomainSchema(requestSchema, allSchemas, new LinkedHashSet<>());
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
            for (JsonNode candidate : allOf) {
                JsonNode nested = resolveDomainSchema(candidate, allSchemas, visited);
                if (nested != null && !nested.isMissingNode() && hasDomainProperties(nested)) {
                    return nested;
                }
            }
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

    private String resolveDocumentPath(JsonNode pathsNode, String requestedPath) {
        if (pathsNode == null || pathsNode.isMissingNode()) {
            return requestedPath;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(requestedPath);
        String normalized = normalizeOpenApiPath(requestedPath);
        candidates.add(normalized);
        if (!"/".equals(normalized)) {
            candidates.add(normalized + "/");
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && !pathsNode.path(candidate).isMissingNode()) {
                return candidate;
            }
        }
        return normalized;
    }

    private String normalizeOpenApiPath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        String normalized = path.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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

    private String releaseKey(String resourceKey, String group, Instant generatedAt) {
        String scope = StringUtils.hasText(resourceKey) ? resourceKey : StringUtils.hasText(group) ? group : "all";
        return serviceKey + ":" + keyPart(scope) + ":" + generatedAt;
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
