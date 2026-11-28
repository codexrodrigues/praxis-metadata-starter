package org.praxisplatform.uischema.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.bulk.BulkCapabilityContractProvider;
import org.praxisplatform.uischema.bulk.BulkControllerOperationIds;
import org.praxisplatform.uischema.bulk.BulkExecutionContract;
import org.praxisplatform.uischema.bulk.BulkExecutionMode;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.bulk.BulkResourceController;
import org.praxisplatform.uischema.bulk.BulkResourceOperation;
import org.praxisplatform.uischema.bulk.BulkSelectionMode;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.SurfaceCatalogResponse;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;

class BulkCapabilityContractProviderHookTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));

    @Test
    void capabilityServicePrioritizesContractProviderOverLegacyLifecycle() {
        BulkExecutionContract uniformContract = sampleContract(BulkMode.UNIFORM_UPDATE, false);
        BulkCapabilityContractProvider provider = new BulkCapabilityContractProvider() {
            @Override
            public Map<String, BulkExecutionContract> resourceContracts(String resourceKey) {
                return "hr.employees".equals(resourceKey)
                        ? Map.of("bulk-update", uniformContract)
                        : Map.of();
            }

            @Override
            public Map<String, ActionExecutionContract> actionContracts(List<ActionDefinition> definitions) {
                return Map.of();
            }
        };

        DefaultCapabilityService service = new DefaultCapabilityService(
                new StaticCanonicalCapabilityResolver(),
                new SurfaceCatalogService(null, null, null) {
                    @Override
                    public SurfaceCatalogResponse findByResourceKey(String resourceKey) {
                        return new SurfaceCatalogResponse(resourceKey, "/employees", "hr", null, List.of());
                    }

                    @Override
                    public SurfaceCatalogResponse findItemSurfaces(String resourceKey, Object resourceId) {
                        return new SurfaceCatalogResponse(resourceKey, "/employees", "hr", resourceId, List.of());
                    }
                },
                new ActionCatalogService(null, null, null) {
                    @Override
                    public ActionCatalogResponse findCollectionActions(String resourceKey) {
                        return new ActionCatalogResponse(resourceKey, "/employees", "hr", null, List.of());
                    }

                    @Override
                    public ActionCatalogResponse findItemActions(String resourceKey, Object resourceId) {
                        return new ActionCatalogResponse(resourceKey, "/employees", "hr", resourceId, List.of());
                    }
                },
                new StubOpenApiDocumentService(),
                new NoOpResourceOperationAvailabilityProvider(),
                new NoOpResourceStateSnapshotProvider(),
                null,
                () -> {
                    throw new AssertionError("legacy BulkOperationLifecycle must not be queried when BulkCapabilityContractProvider is active");
                },
                () -> provider
        );

        CapabilitySnapshot collection = service.collectionCapabilities("hr.employees", "/employees");
        CapabilitySnapshot item = service.itemCapabilities("hr.employees", "/employees", 10L);

        assertNotNull(collection.operations().get("bulk-update"));
        assertEquals(uniformContract, collection.operations().get("bulk-update").bulk());
        assertNotNull(item.operations().get("bulk-update"));
        assertEquals(uniformContract, item.operations().get("bulk-update").bulk());
    }

    @Test
    void autoConfigurationWiresContractProviderIntoActionCatalogServiceAndBacksOffCleanly() {
        ActionDefinition collectionAction = sampleCommandActionDefinition();
        BulkExecutionContract commandContract = sampleContract(BulkMode.DOMAIN_COMMAND, false);
        BulkCapabilityContractProvider provider = new BulkCapabilityContractProvider() {
            @Override
            public Map<String, BulkExecutionContract> resourceContracts(String resourceKey) {
                return Map.of();
            }

            @Override
            public Map<String, ActionExecutionContract> actionContracts(List<ActionDefinition> definitions) {
                Map<String, ActionExecutionContract> contracts = new LinkedHashMap<>();
                for (ActionDefinition def : definitions) {
                    if ("hr.employees.suspend-batch".equals(def.operation().operationId())) {
                        contracts.put(def.operation().operationId(), def.execution().withBulk(commandContract));
                    }
                }
                return contracts;
            }
        };

        ActionDefinitionRegistry registry = new ActionDefinitionRegistry() {
            @Override
            public List<ActionDefinition> findByResourceKey(String resourceKey) {
                return "hr.employees".equals(resourceKey) ? List.of(collectionAction) : List.of();
            }

            @Override
            public List<ActionDefinition> findByGroup(String group) {
                return "hr".equals(group) ? List.of(collectionAction) : List.of();
            }
        };

        runner.withBean(ActionDefinitionRegistry.class, () -> registry)
                .withBean(BulkCapabilityContractProvider.class, () -> provider)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ActionCatalogService.class);
                    ActionCatalogService actions = context.getBean(ActionCatalogService.class);
                    ActionCatalogResponse response = actions.findCollectionActions("hr.employees");
                    assertEquals(1, response.actions().size());
                    assertEquals(commandContract, response.actions().getFirst().execution().bulk());
                });

        runner.withBean(ActionDefinitionRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ActionCatalogService.class);
                    ActionCatalogService actions = context.getBean(ActionCatalogService.class);
                    ActionCatalogResponse response = actions.findCollectionActions("hr.employees");
                    assertEquals(1, response.actions().size());
                    assertNull(response.actions().getFirst().execution().bulk());
                });
    }

    @Test
    void bulkControllerMarkerEnablesImplicitOperationAndEvaluationIds() throws Exception {
        SampleConcreteController controller = new SampleConcreteController();
        Method uniformConfirmMethod = SampleAbstractBulkController.class.getMethod("confirmBulkUpdate");
        Method itemsConfirmMethod = SampleAbstractBulkController.class.getMethod("confirmBulkUpdateItems");

        HandlerMethod uniformHandler = new HandlerMethod(controller, uniformConfirmMethod);
        HandlerMethod itemsHandler = new HandlerMethod(controller, itemsConfirmMethod);

        BulkOperation uniformAnnotation = uniformConfirmMethod.getAnnotation(BulkOperation.class);
        BulkOperation itemsAnnotation = itemsConfirmMethod.getAnnotation(BulkOperation.class);

        assertEquals("hr.employees.bulk.update", BulkControllerOperationIds.operationId(uniformHandler));
        assertEquals("hr.employees.bulk.update.evaluate",
                BulkControllerOperationIds.evaluationId(uniformHandler, uniformAnnotation));
        assertEquals("hr.employees.bulk.update-items", BulkControllerOperationIds.operationId(itemsHandler));
        assertEquals("hr.employees.bulk.update-items.evaluate",
                BulkControllerOperationIds.evaluationId(itemsHandler, itemsAnnotation));
    }

    abstract static class SampleAbstractBulkController implements BulkResourceController {
        @PostMapping("/bulk/update")
        @BulkResourceOperation(BulkResourceOperation.Role.UNIFORM_CONFIRMATION)
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "", atomicity = ActionCollectionAtomicity.PER_ITEM)
        public void confirmBulkUpdate() {}

        @PostMapping("/bulk/update-items")
        @BulkResourceOperation(BulkResourceOperation.Role.ITEM_CONFIRMATION)
        @BulkOperation(mode = BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "", atomicity = ActionCollectionAtomicity.PER_ITEM)
        public void confirmBulkUpdateItems() {}
    }

    @ApiResource(value = "/employees", resourceKey = "hr.employees")
    static class SampleConcreteController extends SampleAbstractBulkController {}

    private static BulkExecutionContract sampleContract(BulkMode mode, boolean atomic) {
        String basePath = "/employees";
        ActionCollectionAtomicity atomicity = atomic
                ? ActionCollectionAtomicity.ATOMIC
                : ActionCollectionAtomicity.PER_ITEM;
        CanonicalOperationRef evalRef = new CanonicalOperationRef(
                "hr", "hr.employees.eval", basePath + "/bulk/update/evaluation", "POST");
        CanonicalOperationRef confirmRef = new CanonicalOperationRef(
                "hr", "hr.employees.confirm", basePath + "/bulk/update", "POST");
        CanonicalOperationRef proposalRef = new CanonicalOperationRef(
                "hr", "hr.employees.bulk.proposal", basePath + "/bulk/proposals/{proposalId}", "GET");
        CanonicalOperationRef proposalResultsRef = new CanonicalOperationRef(
                "hr", "hr.employees.bulk.proposal-results", basePath + "/bulk/proposals/{proposalId}/results", "GET");
        CanonicalOperationRef executionRef = new CanonicalOperationRef(
                "hr", "hr.employees.bulk.execution", basePath + "/bulk/executions/{executionId}", "GET");
        CanonicalOperationRef executionResultsRef = new CanonicalOperationRef(
                "hr", "hr.employees.bulk.execution-results", basePath + "/bulk/executions/{executionId}/results", "GET");
        CanonicalOperationRef cancelRef = new CanonicalOperationRef(
                "hr", "hr.employees.bulk.cancel", basePath + "/bulk/executions/{executionId}/cancel", "POST");
        CanonicalOperationRef putRef = new CanonicalOperationRef("hr", "updateEmployee", basePath + "/{id}", "PUT");

        CanonicalSchemaRef evalReq = new CanonicalSchemaRef("req-eval", "request", "/schemas/filtered?id=eval-req");
        CanonicalSchemaRef evalResp = new CanonicalSchemaRef("resp-eval", "response", "/schemas/filtered?id=eval-resp");
        CanonicalSchemaRef confirmReq = new CanonicalSchemaRef("req-conf", "request", "/schemas/filtered?id=conf-req");
        CanonicalSchemaRef confirmResp = new CanonicalSchemaRef("resp-conf", "response", "/schemas/filtered?id=conf-resp");
        CanonicalSchemaRef genericResp = new CanonicalSchemaRef("resp-gen", "response", "/schemas/filtered?id=gen-resp");
        CanonicalSchemaRef putReq = new CanonicalSchemaRef("req-put", "request", "/schemas/filtered?id=put-req");

        return new BulkExecutionContract(
                mode,
                new BulkExecutionContract.Operation(evalRef, evalReq, evalResp),
                new BulkExecutionContract.Operation(confirmRef, confirmReq, confirmResp),
                new BulkExecutionContract.Operation(proposalRef, null, genericResp),
                new BulkExecutionContract.Operation(proposalResultsRef, null, genericResp),
                new BulkExecutionContract.Operation(executionRef, null, genericResp),
                new BulkExecutionContract.Operation(executionResultsRef, null, genericResp),
                new BulkExecutionContract.Operation(cancelRef, null, genericResp),
                List.of(BulkSelectionMode.EXPLICIT),
                List.of(BulkExecutionMode.SYNC),
                atomicity,
                new BulkExecutionContract.Limits(atomic ? 50 : 200, 131072, 600_000L, 5_000L),
                mode == BulkMode.DOMAIN_COMMAND ? "/properties/parameters" : null,
                mode == BulkMode.DOMAIN_COMMAND ? null
                        : new BulkExecutionContract.EditableFields(putRef, putReq, List.of("status"), List.of())
        );
    }

    private static ActionDefinition sampleCommandActionDefinition() {
        return new ActionDefinition(
                "suspend-batch",
                "hr.employees",
                "/employees",
                "hr",
                ActionScope.COLLECTION,
                "Suspender em lote",
                "",
                new CanonicalOperationRef("hr", "hr.employees.suspend-batch", "/employees/actions/suspend-batch", "POST"),
                new CanonicalSchemaRef("req-act", "request", "/schemas/filtered?id=act-req"),
                new CanonicalSchemaRef("resp-act", "response", "/schemas/filtered?id=act-resp"),
                0,
                "ok",
                List.of(),
                List.of(),
                List.of(),
                ActionExecutionContract.defaults(ActionScope.COLLECTION),
                null,
                null
        );
    }

    private static final class StubOpenApiDocumentService implements OpenApiDocumentService {
        @Override public String resolveGroupFromPath(String path) { return "hr"; }
        @Override public com.fasterxml.jackson.databind.JsonNode getDocumentForGroup(String groupName) { return null; }
        @Override public String getOrComputeSchemaHash(String schemaId, java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> payloadSupplier) { return "unused"; }
        @Override public void clearCaches() {}
    }

    private static final class StaticCanonicalCapabilityResolver implements CanonicalCapabilityResolver {
        @Override public Map<String, Boolean> resolve(String resourcePath) { return Map.of("create", true); }
        @Override public Map<String, Boolean> resolve(com.fasterxml.jackson.databind.JsonNode openApiDocument, String resourcePath) { return resolve(resourcePath); }
        @Override public Map<String, CapabilityOperation> resolveCrudOperations(String resourcePath) {
            return Map.of("create", new CapabilityOperation("create", true, "COLLECTION", "POST", "create", AvailabilityDecision.allowAll()));
        }
        @Override public Map<String, CapabilityOperation> resolveCrudOperations(com.fasterxml.jackson.databind.JsonNode openApiDocument, String resourcePath) { return resolveCrudOperations(resourcePath); }
        @Override public Map<String, CapabilityOperation> resolveOperations(String resourcePath) { return resolveCrudOperations(resourcePath); }
        @Override public Map<String, CapabilityOperation> resolveOperations(com.fasterxml.jackson.databind.JsonNode openApiDocument, String resourcePath) { return resolveOperations(resourcePath); }
    }
}
