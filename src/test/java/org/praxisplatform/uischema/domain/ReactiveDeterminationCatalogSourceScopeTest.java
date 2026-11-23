package org.praxisplatform.uischema.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.bulk.BulkResourceOperation;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
import org.praxisplatform.uischema.bulk.BulkResourceOperations;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.determination.DefaultReactiveDeterminationDefinitionRegistry;
import org.praxisplatform.uischema.determination.ReactiveDeterminationDefinition;
import org.praxisplatform.uischema.determination.ReactiveDeterminationFormMode;
import org.praxisplatform.uischema.determination.ReactiveDeterminationInputBinding;
import org.praxisplatform.uischema.determination.ReactiveDeterminationMetadataCompiler;
import org.praxisplatform.uischema.determination.ReactiveDeterminationOutputBinding;
import org.praxisplatform.uischema.determination.ReactiveDeterminationProvenance;
import org.praxisplatform.uischema.determination.ReactiveDeterminationProvenanceKind;
import org.praxisplatform.uischema.determination.ReactiveDeterminationScope;
import org.praxisplatform.uischema.determination.ReactiveDeterminationTriggerMode;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class ReactiveDeterminationCatalogSourceScopeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Payload(String code, String label) { }

    @ApiResource(value = "/api/orders", resourceKey = "crm.orders")
    static class OrdersController {
        @Operation(operationId = "createOrder")
        public void createOrder() { }

        @Operation(operationId = "updateOrder")
        public void updateOrder() { }

        @Operation(operationId = "determineOrder")
        public void determineOrder() { }
    }

    @ApiResource(value = "/api/customers", resourceKey = "crm.customers")
    @BulkResourceOperations(
            proposalOperationId = "customerProposal",
            proposalResultsOperationId = "customerProposalResults",
            executionOperationId = "customerExecution",
            executionResultsOperationId = "customerExecutionResults",
            cancelOperationId = "cancelCustomer",
            updateSourceOperationId = "updateCustomer")
    static class CustomersController {
        @Operation(operationId = "createCustomer")
        public void createCustomer() { }

        @Operation(operationId = "determineCustomer")
        public void determineCustomer() { }

        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        public void proposal() { }

        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        public void proposalResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        public void execution() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        public void executionResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @Operation(operationId = "cancelCustomer")
        public void cancelCustomer() { }

        @BulkResourceOperation(BulkResourceOperation.Role.UPDATE_SOURCE)
        @Operation(operationId = "updateCustomer")
        public void updateCustomer(@RequestBody Payload body) { }

        @Operation(operationId = "evaluateCustomerBulk")
        public void evaluateCustomerBulk(@RequestBody Payload body) { }

        @BulkOperation(
                mode = BulkMode.UNIFORM_UPDATE,
                evaluationOperationId = "evaluateCustomerBulk",
                atomicity = ActionCollectionAtomicity.ATOMIC)
        @Operation(operationId = "bulkEditCustomer")
        public void bulkEditCustomer(@RequestBody Payload body) { }
    }

    private static ReactiveDeterminationDefinition determination(
            String id, String determineOpId, List<ReactiveDeterminationScope> scopes) {
        return new ReactiveDeterminationDefinition(
                id,
                scopes,
                determineOpId,
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/code"),
                List.of(new ReactiveDeterminationInputBinding("/code", "/code")),
                List.of(new ReactiveDeterminationOutputBinding("/label", "/label")),
                new ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST, "scope-fixture", "1"));
    }

    private static ObjectNode openapiDoc(String group, Map<String, String[]> pathMethodOpIds) {
        ObjectNode root = MAPPER.createObjectNode().put("openapi", "3.0.3");
        root.putObject("info").put("title", group);
        ObjectNode paths = root.putObject("paths");
        for (Map.Entry<String, String[]> entry : pathMethodOpIds.entrySet()) {
            String path = entry.getKey();
            String method = entry.getValue()[0];
            String opId = entry.getValue()[1];
            ObjectNode op = paths.putObject(path).putObject(method);
            op.put("operationId", opId);
            op.putObject("requestBody").putObject("content").putObject("application/json")
                    .putObject("schema").put("$ref", "#/components/schemas/Payload");
            op.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                    .putObject("schema").put("$ref", "#/components/schemas/Payload");
        }
        ObjectNode props = root.putObject("components").putObject("schemas").putObject("Payload")
                .put("type", "object").putObject("properties");
        props.putObject("code").put("type", "string");
        props.putObject("label").put("type", "string");
        return root;
    }

    @Test
    void filtersOutOtherResourceBulkOperationsBeforeOpenApiVerificationAndSharesCallIndexWithoutCrossCallMemoization()
            throws Exception {
        Map<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
        mappings.put(
                RequestMappingInfo.paths("/api/orders").methods(RequestMethod.POST).build(),
                new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("createOrder")));
        mappings.put(
                RequestMappingInfo.paths("/api/orders/determine").methods(RequestMethod.POST).build(),
                new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("determineOrder")));
        mappings.put(
                RequestMappingInfo.paths("/api/customers").methods(RequestMethod.POST).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("createCustomer")));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/determine").methods(RequestMethod.POST).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("determineCustomer")));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/bulk/proposal").methods(RequestMethod.GET).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("proposal")));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/bulk/proposal-results").methods(RequestMethod.GET).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("proposalResults")));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/bulk/execution").methods(RequestMethod.GET).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("execution")));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/bulk/execution-results").methods(RequestMethod.GET).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("executionResults")));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/bulk/cancel").methods(RequestMethod.POST).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("cancelCustomer")));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/{id}").methods(RequestMethod.PUT).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("updateCustomer", Payload.class)));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/bulk/evaluate").methods(RequestMethod.POST).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("evaluateCustomerBulk", Payload.class)));
        mappings.put(
                RequestMappingInfo.paths("/api/customers/bulk").methods(RequestMethod.POST).build(),
                new HandlerMethod(new CustomersController(), CustomersController.class.getMethod("bulkEditCustomer", Payload.class)));

        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);

        OpenApiDocumentService documents = mock(OpenApiDocumentService.class, CALLS_REAL_METHODS);
        ObjectNode ordersDoc = openapiDoc("orders", Map.of(
                "/api/orders", new String[] {"post", "createOrder"},
                "/api/orders/determine", new String[] {"post", "determineOrder"}));
        ObjectNode customersDoc = openapiDoc("customers", Map.ofEntries(
                Map.entry("/api/customers", new String[] {"post", "createCustomer"}),
                Map.entry("/api/customers/determine", new String[] {"post", "determineCustomer"}),
                Map.entry("/api/customers/bulk/proposal", new String[] {"get", "customerProposal"}),
                Map.entry("/api/customers/bulk/proposal-results", new String[] {"get", "customerProposalResults"}),
                Map.entry("/api/customers/bulk/execution", new String[] {"get", "customerExecution"}),
                Map.entry("/api/customers/bulk/execution-results", new String[] {"get", "customerExecutionResults"}),
                Map.entry("/api/customers/bulk/cancel", new String[] {"post", "cancelCustomer"}),
                Map.entry("/api/customers/{id}", new String[] {"put", "updateCustomer"}),
                Map.entry("/api/customers/bulk/evaluate", new String[] {"post", "evaluateCustomerBulk"}),
                Map.entry("/api/customers/bulk", new String[] {"post", "bulkEditCustomer"})));

        AtomicReference<Object> ordersToken = new AtomicReference<>(new Object());
        AtomicReference<Object> customersToken = new AtomicReference<>(new Object());
        when(documents.resolveGroupFromPath(anyString())).thenAnswer(call ->
                call.<String>getArgument(0).startsWith("/api/orders") ? "orders" : "customers");
        doReturn(ordersDoc).when(documents).getDocumentForGroup("orders");
        doReturn(ordersDoc).when(documents).getDocumentForGroupStrict("orders");
        doReturn(customersDoc).when(documents).getDocumentForGroup("customers");
        doReturn(customersDoc).when(documents).getDocumentForGroupStrict("customers");
        when(documents.localPublishedBulkOpenApiPublicationToken()).thenReturn(null);
        when(documents.groupDocumentCacheToken("orders")).thenAnswer(call -> ordersToken.get());
        when(documents.groupDocumentCacheToken("customers")).thenAnswer(call -> customersToken.get());

        BulkResourceOperationBindings bulkBindings = BulkResourceOperationBindings.from(handlerMapping);
        assertThat(bulkBindings.diagnostics()).isEmpty();
        assertThat(bulkBindings.declares("updateCustomer")).isTrue();
        assertThat(bulkBindings.declares("cancelCustomer")).isTrue();

        OpenApiCanonicalOperationResolver operations = new OpenApiCanonicalOperationResolver(
                documents, handlerMapping, bulkBindings, () -> List.of("orders", "customers"));
        ApiResourceIdentityResolver identities = new ApiResourceIdentityResolver(handlerMapping);
        FilteredSchemaReferenceResolver schemas = new FilteredSchemaReferenceResolver();
        OpenApiCanonicalCapabilityResolver capabilities = new OpenApiCanonicalCapabilityResolver(documents);

        var registry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> List.of(
                determination("orderDet", "determineOrder", List.of(
                        new ReactiveDeterminationScope("createOrder", ReactiveDeterminationFormMode.CREATE))),
                determination("customerDet", "determineCustomer", List.of(
                        new ReactiveDeterminationScope("updateCustomer", ReactiveDeterminationFormMode.EDIT))))));
        ReactiveDeterminationMetadataCompiler compiler =
                new ReactiveDeterminationMetadataCompiler(registry, operations, schemas, documents);
        ReactiveDeterminationCatalogSource source = new ReactiveDeterminationCatalogSource(
                registry, compiler, operations, documents, identities, schemas, capabilities, MAPPER);

        // Out-of-scope bulk operation (updateCustomer) must NOT trigger strict OpenAPI scans.
        List<ReactiveDeterminationCatalogSource.CompiledForm> orderFormsFirst = source.find("crm.orders", null);
        assertThat(orderFormsFirst).hasSize(1);
        verify(documents, times(0)).getDocumentForGroupStrict("orders");
        verify(documents, times(0)).getDocumentForGroupStrict("customers");
        String firstOrderHash = orderFormsFirst.getFirst().sourceDocumentHash();

        // While cache token is unchanged, sourceDocumentHash reuses cached hash; token rotation recomputes it.
        ordersDoc.putObject("info").put("title", "orders-mutated-before-token-rotation");
        assertThat(source.find("crm.orders", null).getFirst().sourceDocumentHash()).isEqualTo(firstOrderHash);
        ordersToken.set(new Object());
        assertThat(source.find("crm.orders", null).getFirst().sourceDocumentHash()).isNotEqualTo(firstOrderHash);

        // Even an in-scope form whose MVC endpoint also carries @BulkResourceOperation(UPDATE_SOURCE)
        // must NOT trigger cross-group strict OpenAPI scans; it verifies only its own group document.
        assertThat(source.find("crm.customers", null)).hasSize(1);
        verify(documents, times(0)).getDocumentForGroupStrict("orders");
        verify(documents, times(0)).getDocumentForGroupStrict("customers");

        // Rewriting the published operationId inside the form's own group still fails closed.
        ObjectNode customerPut = (ObjectNode) customersDoc.path("paths").path("/api/customers/{id}").path("put");
        String originalCustomerOpId = customerPut.path("operationId").asText();
        customerPut.put("operationId", "tamperedCustomer");
        assertThatThrownBy(() -> source.find("crm.customers", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differs from published operation");
        customerPut.put("operationId", originalCustomerOpId);

        // Batch bulk verification via resolveByOperationIdsForScope still runs one strict verification pass
        // across published groups and shares ONE ephemeral OperationIndex per call without cross-call memoization.
        assertThat(operations.resolveByOperationIdsForScope(
                        List.of("createOrder", "updateCustomer", "cancelCustomer"),
                        ref -> !"createOrder".equals(ref.operationId())))
                .containsOnlyKeys("updateCustomer", "cancelCustomer");
        verify(documents, times(1)).getDocumentForGroupStrict("orders");
        verify(documents, times(1)).getDocumentForGroupStrict("customers");

        assertThat(operations.resolveByOperationIdsForScope(
                        List.of("createOrder", "updateCustomer", "cancelCustomer"),
                        ref -> !"createOrder".equals(ref.operationId())))
                .containsOnlyKeys("updateCustomer", "cancelCustomer");
        verify(documents, times(2)).getDocumentForGroupStrict("orders");
        verify(documents, times(2)).getDocumentForGroupStrict("customers");

        // Fail-closed fence check: if withSchemaCacheReadLock fails to complete (returns null), bulk batch rejects.
        OpenApiDocumentService noopFenceDocuments = mock(OpenApiDocumentService.class);
        when(noopFenceDocuments.resolveGroupFromPath(anyString())).thenReturn("customers");
        when(noopFenceDocuments.withSchemaCacheReadLock(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        OpenApiCanonicalOperationResolver noopFenceResolver = new OpenApiCanonicalOperationResolver(
                noopFenceDocuments, handlerMapping, bulkBindings, () -> List.of("orders", "customers"));
        assertThatThrownBy(() -> noopFenceResolver.resolveByOperationIdsForScope(
                        List.of("updateCustomer"), ref -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document fence did not complete identity verification");
    }

    @Test
    void batchResolutionStillFailsClosedOnUnresolvedOperationOrMissingIdentity() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/orders").methods(RequestMethod.POST).build(),
                new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("createOrder"))));
        OpenApiDocumentService documents = mock(OpenApiDocumentService.class, CALLS_REAL_METHODS);
        when(documents.resolveGroupFromPath(anyString())).thenReturn("orders");
        OpenApiCanonicalOperationResolver operations = new OpenApiCanonicalOperationResolver(
                documents, handlerMapping, BulkResourceOperationBindings.empty(), List::of);
        FilteredSchemaReferenceResolver schemas = new FilteredSchemaReferenceResolver();
        OpenApiCanonicalCapabilityResolver capabilities = new OpenApiCanonicalCapabilityResolver(documents);

        ApiResourceIdentityResolver validIdentities = new ApiResourceIdentityResolver(handlerMapping);
        var unresolvedRegistry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> List.of(
                determination("missingDet", "determineOrder", List.of(
                        new ReactiveDeterminationScope("nonExistentOp", ReactiveDeterminationFormMode.CREATE))))));
        ReactiveDeterminationCatalogSource unresolvedSource = new ReactiveDeterminationCatalogSource(
                unresolvedRegistry,
                new ReactiveDeterminationMetadataCompiler(unresolvedRegistry, operations, schemas, documents),
                operations, documents, validIdentities, schemas, capabilities, MAPPER);
        assertThatThrownBy(() -> unresolvedSource.find("crm.orders", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved determination form operation: nonExistentOp");

        ApiResourceIdentityResolver emptyIdentities = mock(ApiResourceIdentityResolver.class);
        when(emptyIdentities.resolveResourceKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        var missingIdentityRegistry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> List.of(
                determination("noIdentityDet", "determineOrder", List.of(
                        new ReactiveDeterminationScope("createOrder", ReactiveDeterminationFormMode.CREATE))))));
        ReactiveDeterminationCatalogSource missingIdentitySource = new ReactiveDeterminationCatalogSource(
                missingIdentityRegistry,
                new ReactiveDeterminationMetadataCompiler(missingIdentityRegistry, operations, schemas, documents),
                operations, documents, emptyIdentities, schemas, capabilities, MAPPER);
        assertThatThrownBy(() -> missingIdentitySource.find("crm.other", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Determination form has no explicit resource identity: createOrder");

        // Incomplete bulk declaration must also fail closed in resolveMvcOperationsByIds.
        RequestMappingHandlerMapping incompleteMvc = mock(RequestMappingHandlerMapping.class);
        when(incompleteMvc.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/incomplete/bulk").methods(RequestMethod.POST).build(),
                new HandlerMethod(new IncompleteBulkController(),
                        IncompleteBulkController.class.getMethod("confirmBulk", Payload.class))));
        BulkResourceOperationBindings incompleteBindings = BulkResourceOperationBindings.from(incompleteMvc);
        OpenApiCanonicalOperationResolver incompleteResolver = new OpenApiCanonicalOperationResolver(
                documents, incompleteMvc, incompleteBindings, List::of);
        assertThatThrownBy(() -> incompleteResolver.resolveMvcOperationsByIds(List.of("incomplete.confirm")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declared bulk resource operation is incomplete or ambiguous");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findCompilesMultipleFormsInOneGroupUnderSingleReadLockAndFailsClosedOnNullFence() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/orders").methods(RequestMethod.POST).build(),
                new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("createOrder")),
                RequestMappingInfo.paths("/api/orders/{id}").methods(RequestMethod.PUT).build(),
                new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("updateOrder")),
                RequestMappingInfo.paths("/api/determinations/order").methods(RequestMethod.POST).build(),
                new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("determineOrder"))));

        ObjectNode ordersDoc = MAPPER.readTree("""
                {
                  "openapi": "3.1.0",
                  "paths": {
                    "/api/orders": {
                      "post": {
                        "operationId": "createOrder",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/OrderForm"}}}}
                      }
                    },
                    "/api/orders/{id}": {
                      "put": {
                        "operationId": "updateOrder",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/OrderForm"}}}}
                      }
                    }
                  },
                  "components": {"schemas": {"OrderForm": {"type": "object", "properties": {
                    "code": {"type": "string"},
                    "label": {"type": "string"}
                  }}}}
                }
                """).deepCopy();
        ObjectNode detDoc = MAPPER.readTree("""
                {
                  "openapi": "3.1.0",
                  "paths": {
                    "/api/determinations/order": {
                      "post": {
                        "operationId": "determineOrder",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/OrderForm"}}}},
                        "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/OrderForm"}}}}}
                      }
                    }
                  },
                  "components": {"schemas": {"OrderForm": {"type": "object", "properties": {
                    "code": {"type": "string"},
                    "label": {"type": "string"}
                  }}}}
                }
                """).deepCopy();

        OpenApiDocumentService documents = mock(OpenApiDocumentService.class, CALLS_REAL_METHODS);
        when(documents.resolveGroupFromPath(anyString())).thenAnswer(call ->
                call.getArgument(0).toString().contains("/determinations/") ? "determinations" : "orders");
        doReturn(ordersDoc).when(documents).getDocumentForGroup("orders");
        doReturn(detDoc).when(documents).getDocumentForGroup("determinations");
        doReturn(new Object()).when(documents).groupDocumentCacheToken("orders");

        OpenApiCanonicalOperationResolver operations = new OpenApiCanonicalOperationResolver(
                documents, handlerMapping, BulkResourceOperationBindings.empty(), () -> List.of("orders"));
        ApiResourceIdentityResolver identities = new ApiResourceIdentityResolver(handlerMapping);
        FilteredSchemaReferenceResolver schemas = new FilteredSchemaReferenceResolver();
        OpenApiCanonicalCapabilityResolver capabilities = new OpenApiCanonicalCapabilityResolver(documents);
        var registry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> List.of(
                determination("orderDet", "determineOrder", List.of(
                        new ReactiveDeterminationScope("createOrder", ReactiveDeterminationFormMode.CREATE),
                        new ReactiveDeterminationScope("updateOrder", ReactiveDeterminationFormMode.EDIT))))));
        ReactiveDeterminationMetadataCompiler compiler =
                new ReactiveDeterminationMetadataCompiler(registry, operations, schemas, documents);
        ReactiveDeterminationCatalogSource source = new ReactiveDeterminationCatalogSource(
                registry, compiler, operations, documents, identities, schemas, capabilities, MAPPER);

        List<ReactiveDeterminationCatalogSource.CompiledForm> forms = source.find("crm.orders", null);
        assertThat(forms).hasSize(2);
        // Single read-lock acquisition and single group document read across both forms in the same group.
        verify(documents, times(1)).withSchemaCacheReadLock(org.mockito.ArgumentMatchers.any());
        verify(documents, times(1)).getDocumentForGroup("orders");

        // Broken lock that returns null must fail closed rather than silently emitting empty forms.
        OpenApiDocumentService brokenLockDocs = mock(OpenApiDocumentService.class, CALLS_REAL_METHODS);
        doReturn("orders").when(brokenLockDocs).resolveGroupFromPath(anyString());
        doReturn(null).when(brokenLockDocs).withSchemaCacheReadLock(org.mockito.ArgumentMatchers.any());
        OpenApiCanonicalOperationResolver brokenOps = new OpenApiCanonicalOperationResolver(
                brokenLockDocs, handlerMapping, BulkResourceOperationBindings.empty(), () -> List.of("orders"));
        ReactiveDeterminationCatalogSource brokenSource = new ReactiveDeterminationCatalogSource(
                registry,
                new ReactiveDeterminationMetadataCompiler(registry, brokenOps, schemas, brokenLockDocs),
                brokenOps, brokenLockDocs, identities, schemas, capabilities, MAPPER);
        assertThatThrownBy(() -> brokenSource.find("crm.orders", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAPI document fence did not complete form determination compilation");
    }

    @Test
    void semanticDomainCatalogServiceBoundsCatalogCacheCapacity() {
        var actionRegistry = mock(org.praxisplatform.uischema.action.ActionDefinitionRegistry.class);
        var surfaceRegistry = mock(org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry.class);
        when(actionRegistry.findByResourceKey(anyString())).thenReturn(List.of());
        when(surfaceRegistry.findByResourceKey(anyString())).thenReturn(List.of());
        SemanticDomainCatalogService catalogService = new SemanticDomainCatalogService(
                actionRegistry,
                surfaceRegistry,
                org.praxisplatform.uischema.options.OptionSourceRegistry.empty(),
                null,
                null,
                java.time.Clock.systemUTC(),
                "crm",
                "CRM",
                "1.0",
                null,
                null,
                null,
                null,
                null
        );
        catalogService.setMaxCatalogCacheEntries(2);

        catalogService.findByResourceKey("crm.orders");
        catalogService.findByResourceKey("crm.customers");
        assertThat(catalogService.catalogCacheSize()).isEqualTo(2);
        verify(actionRegistry, times(1)).findByResourceKey("crm.orders");
        verify(actionRegistry, times(1)).findByResourceKey("crm.customers");

        // Touching "crm.orders" promotes it in the LRU cache so "crm.customers" becomes oldest.
        catalogService.findByResourceKey("crm.orders");
        verify(actionRegistry, times(1)).findByResourceKey("crm.orders");

        catalogService.findByResourceKey("crm.invoices");
        assertThat(catalogService.catalogCacheSize()).isEqualTo(2);

        catalogService.findByResourceKey("crm.orders");
        verify(actionRegistry, times(1)).findByResourceKey("crm.orders");

        catalogService.findByResourceKey("crm.customers");
        verify(actionRegistry, times(2)).findByResourceKey("crm.customers");
    }

    @ApiResource(value = "/api/incomplete", resourceKey = "crm.incomplete")
    static class IncompleteBulkController {
        @BulkOperation(
                mode = BulkMode.UNIFORM_UPDATE,
                evaluationOperationId = "incomplete.evaluate",
                atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "incomplete.confirm")
        public void confirmBulk(@RequestBody Payload body) { }
    }
}
