package org.praxisplatform.uischema.capability;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.LegalTreatmentPurpose;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher.DtoFactPropertyMetadata;
import org.praxisplatform.uischema.bulk.BulkResourceOperations;
import org.praxisplatform.uischema.capability.ResourceDecisionPointDescriptor.DecisionPointKind;
import org.praxisplatform.uischema.controller.base.AbstractCreateUpdateResourceController;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Serviço canônico (`PLAT-EVO-07`) que deriva automaticamente os
 * {@link ResourceDecisionPointDescriptor}s de qualquer controller anotado com {@link ApiResource} e
 * seus DTOs associados ({@code ResponseDTO}, {@code CreateDTO}, {@code UpdateDTO}).
 *
 * <p>Consolida em uma única projeção determinística (percorrendo toda a hierarquia de superclasses
 * e interfaces do controller e herdando linhagem de {@code ResponseDTO} para DTOs de escrita
 * conforme a regra canônica {@code P10.8-R01}):
 * <ul>
 *   <li>Operações estruturais do recurso ({@code RESOURCE_QUERY}, {@code RESOURCE_CREATE},
 *       {@code RESOURCE_UPDATE}, {@code RESOURCE_DELETE}, {@code COLLECTION_EXPORT},
 *       {@code AI_TOOL_EXECUTION});</li>
 *   <li>Ações de workflow ({@link WorkflowAction}) com {@code legalPurpose} (normalizando
 *       {@link LegalTreatmentPurpose#UNSPECIFIED} para {@code null}) e {@code legalBasisRef};</li>
 *   <li>Superfícies semânticas ({@link UiSurface}) com {@code legalPurpose} e
 *       {@code legalBasisRef};</li>
 *   <li>Operações em lote ({@link BulkOperation} / {@link BulkResourceOperations});</li>
 *   <li>Pontos de decisão de projeção de leitura ({@code FIELD_PROJECTION}) e escrita
 *       ({@code FIELD_WRITE}) campo a campo a partir de {@code @DomainGovernance} e
 *       {@code @DomainLineage}, identificando campos restritos ou fail-closed.</li>
 * </ul>
 */
public class ResourceDecisionPointCatalogService {
    private final DtoMetadataFactCatalogEnricher dtoEnricher;

    public ResourceDecisionPointCatalogService() {
        this(new DtoMetadataFactCatalogEnricher());
    }

    public ResourceDecisionPointCatalogService(DtoMetadataFactCatalogEnricher dtoEnricher) {
        this.dtoEnricher = Objects.requireNonNull(dtoEnricher, "dtoEnricher");
    }

    /**
     * Deriva todos os {@link ResourceDecisionPointDescriptor}s de um controller anotado com
     * {@link ApiResource}, resolvendo automaticamente seus tipos genéricos de {@code ResponseDTO},
     * {@code CreateDTO} e {@code UpdateDTO} quando herdados da hierarquia canônica de controllers.
     *
     * @param controllerClass classe do controller
     * @return lista imutável de pontos de decisão governados em ordem determinística
     */
    public List<ResourceDecisionPointDescriptor> deriveForController(Class<?> controllerClass) {
        Objects.requireNonNull(controllerClass, "controllerClass");
        Class<?> responseDto = null;
        Class<?> createDto = null;
        Class<?> updateDto = null;

        if (AbstractCreateUpdateResourceController.class.isAssignableFrom(controllerClass)) {
            ResolvableType resolvable =
                    ResolvableType.forClass(controllerClass)
                            .as(AbstractCreateUpdateResourceController.class);
            responseDto = resolvable.getGeneric(0).resolve();
            createDto = resolvable.getGeneric(3).resolve();
            updateDto = resolvable.getGeneric(4).resolve();
        } else if (AbstractResourceQueryController.class.isAssignableFrom(controllerClass)) {
            ResolvableType resolvable =
                    ResolvableType.forClass(controllerClass)
                            .as(AbstractResourceQueryController.class);
            responseDto = resolvable.getGeneric(0).resolve();
        }
        return deriveForResource(controllerClass, responseDto, createDto, updateDto);
    }

    /**
     * Deriva todos os {@link ResourceDecisionPointDescriptor}s de um controller {@link ApiResource}
     * com classes de DTO explicitamente informadas.
     *
     * @param controllerClass classe do controller anotada com {@link ApiResource}
     * @param responseDtoClass classe do DTO de leitura/projeção (opcional)
     * @param createDtoClass classe do DTO de criação (opcional)
     * @param updateDtoClass classe do DTO de atualização (opcional)
     * @return lista imutável de pontos de decisão governados
     */
    public List<ResourceDecisionPointDescriptor> deriveForResource(
            Class<?> controllerClass,
            Class<?> responseDtoClass,
            Class<?> createDtoClass,
            Class<?> updateDtoClass) {
        Objects.requireNonNull(controllerClass, "controllerClass");
        ApiResource apiResource =
                AnnotatedElementUtils.findMergedAnnotation(controllerClass, ApiResource.class);
        if (apiResource == null) {
            throw new IllegalArgumentException(
                    "Controller "
                            + controllerClass.getName()
                            + " is not annotated with @ApiResource");
        }

        String resourceKey = apiResource.resourceKey().trim();
        String resourcePath = resolvePrimaryPath(apiResource);
        boolean mutable =
                AbstractCreateUpdateResourceController.class.isAssignableFrom(controllerClass)
                        || createDtoClass != null
                        || updateDtoClass != null;

        List<ResourceDecisionPointDescriptor> points = new ArrayList<>();

        // 1. Structural resource-level decision points
        points.add(
                structuralPoint(
                        resourceKey,
                        resourcePath,
                        DecisionPointKind.RESOURCE_QUERY,
                        "query",
                        "Consulta e filtragem do recurso " + resourceKey));
        if (mutable) {
            points.add(
                    structuralPoint(
                            resourceKey,
                            resourcePath,
                            DecisionPointKind.RESOURCE_CREATE,
                            "create",
                            "Criação de registro em " + resourceKey));
            points.add(
                    structuralPoint(
                            resourceKey,
                            resourcePath,
                            DecisionPointKind.RESOURCE_UPDATE,
                            "update",
                            "Atualização de registro em " + resourceKey));
            points.add(
                    structuralPoint(
                            resourceKey,
                            resourcePath,
                            DecisionPointKind.RESOURCE_DELETE,
                            "delete",
                            "Exclusão de registro em " + resourceKey));
        }
        points.add(
                structuralPoint(
                        resourceKey,
                        resourcePath,
                        DecisionPointKind.COLLECTION_EXPORT,
                        "export",
                        "Exportação governada de coleção de " + resourceKey));
        points.add(
                structuralPoint(
                        resourceKey,
                        resourcePath,
                        DecisionPointKind.AI_TOOL_EXECUTION,
                        "ai-tool",
                        "Invocação de ferramentas de IA e agentes sobre " + resourceKey));

        // 2. Workflow actions (@WorkflowAction) across class hierarchy
        for (WorkflowAction action : collectWorkflowActions(controllerClass)) {
            String actionId = action.id().trim();
            String purpose = normalizeLegalPurpose(action.legalPurpose());
            String basis = blankToNull(action.legalBasisRef());
            points.add(
                    new ResourceDecisionPointDescriptor(
                            resourceKey + ":action:" + actionId,
                            resourceKey,
                            resourcePath,
                            DecisionPointKind.WORKFLOW_ACTION,
                            actionId,
                            blankToDefault(action.title(), actionId),
                            blankToDefault(action.description(), action.title()),
                            purpose,
                            basis,
                            nonBlankList(action.requiredAuthorities()),
                            nonBlankList(action.allowedStates()),
                            purpose != null && basis != null,
                            purpose == null || basis == null,
                            "internal",
                            "operational",
                            "NON_SENSITIVE",
                            "NONE",
                            List.of(),
                            null));
        }

        // 3. Semantic surfaces (@UiSurface) across class hierarchy
        for (UiSurface surface : collectUiSurfaces(controllerClass)) {
            String surfaceId = surface.id().trim();
            String purpose = normalizeLegalPurpose(surface.legalPurpose());
            String basis = blankToNull(surface.legalBasisRef());
            points.add(
                    new ResourceDecisionPointDescriptor(
                            resourceKey + ":surface:" + surfaceId,
                            resourceKey,
                            resourcePath,
                            DecisionPointKind.SURFACE_AVAILABILITY,
                            surfaceId,
                            blankToDefault(surface.title(), surfaceId),
                            blankToDefault(surface.description(), surface.title()),
                            purpose,
                            basis,
                            nonBlankList(surface.requiredAuthorities()),
                            nonBlankList(surface.allowedStates()),
                            purpose != null && basis != null,
                            purpose == null || basis == null,
                            "internal",
                            "operational",
                            "NON_SENSITIVE",
                            "NONE",
                            List.of(),
                            null));
        }

        // 4. Bulk operations (@BulkOperation / @BulkResourceOperations) across class hierarchy
        for (BulkOperation bulk : collectBulkOperations(controllerClass)) {
            String bulkId = bulk.evaluationOperationId().trim();
            points.add(
                    new ResourceDecisionPointDescriptor(
                            resourceKey + ":bulk:" + bulkId,
                            resourceKey,
                            resourcePath,
                            DecisionPointKind.BULK_OPERATION,
                            bulkId,
                            bulkId,
                            "Operação em lote " + bulk.mode().name() + " (" + bulkId + ")",
                            null,
                            null,
                            List.of(),
                            List.of(),
                            true,
                            false,
                            "internal",
                            "operational",
                            "NON_SENSITIVE",
                            "NONE",
                            List.of(),
                            null));
        }

        // 5. Field projections on ResponseDTO (FIELD_PROJECTION)
        if (responseDtoClass != null) {
            Map<String, DtoFactPropertyMetadata> readFields =
                    dtoEnricher.inspectDto(responseDtoClass);
            for (DtoFactPropertyMetadata field : readFields.values()) {
                boolean failClosed =
                        !field.explicitGovernanceDeclared()
                                || !field.exportable()
                                || "deny".equalsIgnoreCase(field.aiVisibility());
                points.add(
                        new ResourceDecisionPointDescriptor(
                                resourceKey + ":field.projection:" + field.propertyName(),
                                resourceKey,
                                resourcePath,
                                DecisionPointKind.FIELD_PROJECTION,
                                field.propertyName(),
                                blankToDefault(field.labelPtBr(), field.propertyName()),
                                blankToDefault(
                                        field.descriptionPtBr(),
                                        blankToDefault(field.labelPtBr(), field.propertyName())),
                                null,
                                null,
                                List.of(),
                                List.of(),
                                field.explicitGovernanceDeclared(),
                                failClosed,
                                field.classification(),
                                field.dataCategory(),
                                field.sensitivity(),
                                field.redaction(),
                                field.complianceTags(),
                                field.lineageAttributeId()));
            }
        }

        // 6. Field writes on CreateDTO / UpdateDTO (FIELD_WRITE) with ResponseDTO lineage fallback (P10.8-R01)
        Map<String, DtoFactPropertyMetadata> writeFields = new LinkedHashMap<>();
        if (createDtoClass != null) {
            writeFields.putAll(dtoEnricher.inspectWriteDto(createDtoClass, responseDtoClass));
        }
        if (updateDtoClass != null) {
            writeFields.putAll(dtoEnricher.inspectWriteDto(updateDtoClass, responseDtoClass));
        }
        for (DtoFactPropertyMetadata field : writeFields.values()) {
            boolean failClosed =
                    !field.explicitGovernanceDeclared()
                            || (field.lineageAttributeId() == null
                                    || field.lineageAttributeId().isBlank());
            points.add(
                    new ResourceDecisionPointDescriptor(
                            resourceKey + ":field.write:" + field.propertyName(),
                            resourceKey,
                            resourcePath,
                            DecisionPointKind.FIELD_WRITE,
                            field.propertyName(),
                            blankToDefault(field.labelPtBr(), field.propertyName()),
                            blankToDefault(
                                    field.descriptionPtBr(),
                                    blankToDefault(field.labelPtBr(), field.propertyName())),
                            null,
                            null,
                            List.of(),
                            List.of(),
                            field.explicitGovernanceDeclared(),
                            failClosed,
                            field.classification(),
                            field.dataCategory(),
                            field.sensitivity(),
                            field.redaction(),
                            field.complianceTags(),
                            field.lineageAttributeId()));
        }

        return List.copyOf(points);
    }

    private static ResourceDecisionPointDescriptor structuralPoint(
            String resourceKey,
            String resourcePath,
            DecisionPointKind kind,
            String targetId,
            String description) {
        return new ResourceDecisionPointDescriptor(
                resourceKey + ":operation:" + targetId,
                resourceKey,
                resourcePath,
                kind,
                targetId,
                targetId,
                description,
                null,
                null,
                List.of(),
                List.of(),
                true,
                false,
                "internal",
                "operational",
                "NON_SENSITIVE",
                "NONE",
                List.of(),
                null);
    }

    private static List<WorkflowAction> collectWorkflowActions(Class<?> controllerClass) {
        Map<String, WorkflowAction> byId = new LinkedHashMap<>();
        WorkflowAction classLevel =
                AnnotatedElementUtils.findMergedAnnotation(controllerClass, WorkflowAction.class);
        if (classLevel != null && !classLevel.id().isBlank()) {
            byId.put(classLevel.id().trim(), classLevel);
        }
        for (Method m : collectHierarchyMethods(controllerClass)) {
            WorkflowAction a = AnnotatedElementUtils.findMergedAnnotation(m, WorkflowAction.class);
            if (a != null && !a.id().isBlank()) {
                byId.put(a.id().trim(), a);
            }
        }
        return List.copyOf(byId.values());
    }

    private static List<UiSurface> collectUiSurfaces(Class<?> controllerClass) {
        Map<String, UiSurface> byId = new LinkedHashMap<>();
        UiSurface classLevel =
                AnnotatedElementUtils.findMergedAnnotation(controllerClass, UiSurface.class);
        if (classLevel != null && !classLevel.id().isBlank()) {
            byId.put(classLevel.id().trim(), classLevel);
        }
        for (Method m : collectHierarchyMethods(controllerClass)) {
            UiSurface s = AnnotatedElementUtils.findMergedAnnotation(m, UiSurface.class);
            if (s != null && !s.id().isBlank()) {
                byId.put(s.id().trim(), s);
            }
        }
        return List.copyOf(byId.values());
    }

    private static List<BulkOperation> collectBulkOperations(Class<?> controllerClass) {
        Map<String, BulkOperation> byId = new LinkedHashMap<>();
        for (Method m : collectHierarchyMethods(controllerClass)) {
            BulkOperation op = AnnotatedElementUtils.findMergedAnnotation(m, BulkOperation.class);
            if (op != null && !op.evaluationOperationId().isBlank()) {
                byId.put(op.evaluationOperationId().trim(), op);
            }
        }
        return List.copyOf(byId.values());
    }

    private static List<Method> collectHierarchyMethods(Class<?> leafClass) {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = leafClass; c != null && c != Object.class; c = c.getSuperclass()) {
            types.add(c);
            types.addAll(Arrays.asList(c.getInterfaces()));
        }
        Collections.reverse(types);
        List<Method> methods = new ArrayList<>();
        for (Class<?> t : types) {
            methods.addAll(Arrays.asList(t.getDeclaredMethods()));
        }
        return methods;
    }

    private static String normalizeLegalPurpose(LegalTreatmentPurpose purpose) {
        if (purpose == null || purpose == LegalTreatmentPurpose.UNSPECIFIED) {
            return null;
        }
        return blankToNull(purpose.wireValue());
    }

    private static String resolvePrimaryPath(ApiResource resource) {
        if (resource.value() != null
                && resource.value().length > 0
                && !resource.value()[0].isBlank()) {
            return resource.value()[0].trim();
        }
        if (resource.path() != null
                && resource.path().length > 0
                && !resource.path()[0].isBlank()) {
            return resource.path()[0].trim();
        }
        return "/" + resource.resourceKey().trim().replace('.', '/');
    }

    private static List<String> nonBlankList(String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(values)
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .forEach(set::add);
        return List.copyOf(set);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
