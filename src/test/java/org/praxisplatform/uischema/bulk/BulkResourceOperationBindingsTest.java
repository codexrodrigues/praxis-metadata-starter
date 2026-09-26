package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BulkResourceOperationBindingsTest {

    @Test
    void sameBindingDrivesStrictResolverAndOpenApiOperationIdentity() {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class)) {
            RequestMappingHandlerMapping mvc = context.getBean(RequestMappingHandlerMapping.class);
            BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(mvc);
            assertTrue(bindings.diagnostics().isEmpty(), bindings.diagnostics().toString());

            OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
            when(documents.resolveGroupFromPath(anyString())).thenReturn("inventory");
            when(documents.getDocumentForGroupStrict("inventory")).thenReturn(openApiDocument("items.bulk.proposal", false));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var proposal = resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET");
            assertEquals("/api/items/bulk/proposals/{proposalId}", proposal.path());

            HandlerMethod proposalHandler = mvc.getHandlerMethods().entrySet().stream()
                    .filter(entry -> entry.getValue().getMethod().getName().equals("readProposal"))
                    .map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
            io.swagger.v3.oas.models.Operation openApi = new io.swagger.v3.oas.models.Operation()
                    .operationId("readProposal");
            new BulkResourceOperationIdCustomizer(bindings).customize(openApi, proposalHandler);

            assertEquals("items.bulk.proposal", openApi.getOperationId());
            assertEquals("items.bulk.cancel", bindings.operationIdFor(handler(mvc, "cancelExecution")).orElseThrow());
        }
    }

    @Test
    void incompleteOptInIsRecordedAndExcludedWithoutFailingTheMvcApplication() {
        try (AnnotationConfigWebApplicationContext context = context(IncompleteBulkController.class)) {
            BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(
                    context.getBean(RequestMappingHandlerMapping.class));

            assertFalse(bindings.diagnostics().isEmpty());
            assertTrue(bindings.handlerFor("items.bulk.cancel").isEmpty());
            assertTrue(bindings.operationIdFor(handler(context.getBean(RequestMappingHandlerMapping.class), "readProposal"))
                    .isEmpty());

            OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
            CanonicalOperationResolver resolver = new OpenApiCanonicalOperationResolver(documents,
                    context.getBean(RequestMappingHandlerMapping.class), bindings);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET"));
        }
    }

    @Test
    void duplicateGlobalIdWithAnotherMvcOperationExcludesTheBulkResource() {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class, CollisionController.class)) {
            BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(
                    context.getBean(RequestMappingHandlerMapping.class));

            assertTrue(bindings.diagnostics().stream().anyMatch(message -> message.contains("collides")));
            assertTrue(bindings.handlerFor("items.bulk.proposal").isEmpty());
            assertTrue(bindings.handlerFor("items.bulk.proposal-results").isEmpty());
        }
    }

    @Test
    void duplicateResourceKeyExcludesEveryBulkDeclarationForThatResource() {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class,
                DuplicateResourceKeyController.class)) {
            BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(
                    context.getBean(RequestMappingHandlerMapping.class));

            assertTrue(bindings.diagnostics().stream().anyMatch(message -> message.contains("exactly one bulk operation declaration")));
            assertTrue(bindings.handlerFor("items.bulk.proposal").isEmpty());
            assertTrue(bindings.handlerFor("items.alternate.bulk.proposal").isEmpty());
        }
    }

    @Test
    void sharedLifecycleRejectsConsumesAndHttpEntityRequestBodies() {
        try (AnnotationConfigWebApplicationContext context = context(BodyLifecycleController.class,
                MediaConstrainedLifecycleController.class)) {
            var bindings = BulkResourceOperationBindings.from(context.getBean(RequestMappingHandlerMapping.class));

            assertTrue(bindings.diagnostics().stream().anyMatch(message -> message.contains("HTTP entity parameters")));
            assertTrue(bindings.diagnostics().stream().anyMatch(message -> message.contains("consumes constraints")));
            assertTrue(bindings.handlerFor("body.bulk.cancel").isEmpty());
            assertTrue(bindings.handlerFor("media.bulk.cancel").isEmpty());
        }
    }

    @Test
    void strictBulkBindingRejectsTheFinalServedDocumentWhenAnotherCustomizerChangedItsIdentity() {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class)) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
            when(documents.resolveGroupFromPath(anyString())).thenReturn("inventory");
            when(documents.getDocumentForGroupStrict("inventory")).thenReturn(openApiDocument("rewritten.by.host", false));

            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET"));
        }
    }

    @Test
    void strictBulkBindingRejectsDuplicateIdentityInTheFinalServedDocument() {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class)) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
            when(documents.resolveGroupFromPath(anyString())).thenReturn("inventory");
            when(documents.getDocumentForGroupStrict("inventory")).thenReturn(openApiDocument("items.bulk.proposal", true));

            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET"));
        }
    }

    @Test
    void strictBulkBindingRejectsCustomizerCollisionInAnotherPublishedGroup() {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class)) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
            when(documents.resolveGroupFromPath(anyString())).thenReturn("inventory");
            when(documents.getDocumentForGroupStrict("inventory")).thenReturn(openApiDocument("items.bulk.proposal", false));
            when(documents.getDocumentForGroupStrict("other")).thenReturn(openApiDocument("items.bulk.proposal", true));

            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("inventory", "other"));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET"));
        }
    }

    @Test
    void strictBulkBindingTreatsRenamedTemplateVariablesForTheSameRouteAsOneIdentity() {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class)) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
            when(documents.resolveGroupFromPath(anyString())).thenReturn("inventory");
            when(documents.getDocumentForGroupStrict("inventory"))
                    .thenReturn(openApiDocument("items.bulk.proposal", false));
            when(documents.getDocumentForGroupStrict("other"))
                    .thenReturn(openApiDocumentAtPath("/api/items/bulk/proposals/{id}",
                            "items.bulk.proposal"));
            when(documents.resolveDocumentPath(org.mockito.ArgumentMatchers.any(
                    com.fasterxml.jackson.databind.JsonNode.class),
                    eq("/api/items/bulk/proposals/{proposalId}"), eq("GET")))
                    .thenReturn("/api/items/bulk/proposals/{id}");

            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings,
                    List.of("inventory", "other"));

            assertEquals("items.bulk.proposal",
                    resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET").operationId());
        }
    }

    @Test
    void strictBulkBindingObservesOpenApiGroupsRegisteredAfterResolverConstruction() {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class)) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
            when(documents.resolveGroupFromPath(anyString())).thenReturn("inventory");
            when(documents.getDocumentForGroupStrict("inventory"))
                    .thenReturn(openApiDocument("items.bulk.proposal", false));
            when(documents.getDocumentForGroupStrict("late-group"))
                    .thenReturn(openApiDocument("items.bulk.proposal", true));

            List<String> publishedGroups = new java.util.ArrayList<>(List.of("inventory"));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, () -> publishedGroups);
            publishedGroups.add("late-group");

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET"));
        }
    }

    @Test
    void strictBulkBindingChecksCallbacksAndWebhooksForIdentityCollisions() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context(CompleteBulkController.class)) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            for (boolean webhook : List.of(false, true)) {
                OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
                when(documents.resolveGroupFromPath(anyString())).thenReturn("inventory");
                when(documents.getDocumentForGroupStrict("inventory")).thenReturn(openApiDocumentWithNonPathCollision(webhook));
                var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET"));
            }
        }
    }

    private com.fasterxml.jackson.databind.JsonNode openApiDocumentWithNonPathCollision(boolean webhook) throws Exception {
        ObjectNode document = (ObjectNode) openApiDocument("items.bulk.proposal", false);
        ObjectNode operation = (ObjectNode) document.path("paths").path("/api/items/bulk/proposals/{proposalId}").path("get");
        if (webhook) {
            ((ObjectNode) document).putObject("webhooks").putObject("inventory-event").putObject("post")
                    .put("operationId", "items.bulk.proposal");
        } else {
            operation.putObject("callbacks").putObject("itemChanged")
                    .putObject("{$request.body#/callbackUrl}").putObject("post")
                    .put("operationId", "items.bulk.proposal");
        }
        return document;
    }

    private com.fasterxml.jackson.databind.JsonNode openApiDocument(String proposalId, boolean duplicate) {
        try {
            String duplicatePath = duplicate
                    ? ",\"/unrelated\":{\"get\":{\"operationId\":\"items.bulk.proposal\"}}" : "";
            return new ObjectMapper().readTree("{\"paths\":{\"/api/items/bulk/proposals/{proposalId}\":{\"get\":{\"operationId\":\""
                    + proposalId + "\"}}" + duplicatePath + "}}");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode openApiDocumentAtPath(String path, String operationId) {
        ObjectNode document = new ObjectMapper().createObjectNode();
        document.putObject("paths").putObject(path).putObject("get").put("operationId", operationId);
        return document;
    }

    @Test
    void wrongHttpMethodOrConflictingExplicitOperationIdExcludesTheWholeDeclaration() {
        try (AnnotationConfigWebApplicationContext context = context(ConflictingBulkController.class)) {
            BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(
                    context.getBean(RequestMappingHandlerMapping.class));

            assertTrue(bindings.diagnostics().stream().anyMatch(message -> message.contains("requires one canonical path")));
            assertTrue(bindings.diagnostics().stream().anyMatch(message -> message.contains("conflicts with the declared identity")),
                    bindings.diagnostics().toString());
            assertTrue(bindings.handlerFor("items.bulk.proposal").isEmpty());
        }
    }

    private AnnotationConfigWebApplicationContext context(Class<?>... controllers) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.register(controllers);
        context.refresh();
        return context;
    }

    private HandlerMethod handler(RequestMappingHandlerMapping mapping, String name) {
        return mapping.getHandlerMethods().values().stream()
                .filter(handler -> handler.getMethod().getName().equals(name)).findFirst().orElseThrow();
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfiguration { }

    @ApiResource(value = "/api/items", resourceKey = "inventory.items")
    @BulkResourceOperations(
            proposalOperationId = "items.bulk.proposal",
            proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution",
            executionResultsOperationId = "items.bulk.execution-results",
            cancelOperationId = "items.bulk.cancel")
    static class CompleteBulkController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/bulk/proposals/{proposalId}")
        @Operation(summary = "Read retained bulk proposal")
        public void readProposal() { }

        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/bulk/proposals/{proposalId}/results")
        public void readProposalResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/bulk/executions/{executionId}")
        public void readExecution() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/bulk/executions/{executionId}/results")
        public void readExecutionResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping("/bulk/executions/{executionId}/cancel")
        public void cancelExecution() { }
    }

    @ApiResource(value = "/api/collision", resourceKey = "inventory.collision")
    static class CollisionController {
        @GetMapping("/bulk")
        @Operation(operationId = "items.bulk.proposal")
        public void collision() { }
    }

    @ApiResource(value = "/api/items-alternate", resourceKey = "inventory.items")
    @BulkResourceOperations(
            proposalOperationId = "items.alternate.bulk.proposal",
            proposalResultsOperationId = "items.alternate.bulk.proposal-results",
            executionOperationId = "items.alternate.bulk.execution",
            executionResultsOperationId = "items.alternate.bulk.execution-results",
            cancelOperationId = "items.alternate.bulk.cancel")
    static class DuplicateResourceKeyController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/alternate/proposals/{proposalId}") public void proposal() { }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/alternate/proposals/{proposalId}/results") public void proposalResults() { }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/alternate/executions/{executionId}") public void execution() { }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/alternate/executions/{executionId}/results") public void executionResults() { }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping("/alternate/executions/{executionId}/cancel") public void cancel() { }
    }

    @ApiResource(value = "/api/incomplete", resourceKey = "inventory.items")
    @BulkResourceOperations(
            proposalOperationId = "items.bulk.proposal",
            proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution",
            executionResultsOperationId = "items.bulk.execution-results",
            cancelOperationId = "items.bulk.cancel")
    static class IncompleteBulkController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/bulk/proposals/{proposalId}")
        public void readProposal() { }

        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/bulk/proposals/{proposalId}/results")
        public void readProposalResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/bulk/executions/{executionId}")
        public void readExecution() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/bulk/executions/{executionId}/results")
        public void readExecutionResults() { }
    }

    @ApiResource(value = "/api/conflicting", resourceKey = "inventory.items")
    @BulkResourceOperations(
            proposalOperationId = "items.bulk.proposal",
            proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution",
            executionResultsOperationId = "items.bulk.execution-results",
            cancelOperationId = "items.bulk.cancel")
    static class ConflictingBulkController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/bulk/proposals/{proposalId}")
        @Operation(operationId = "different.operation")
        public void readProposal() { }

        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/bulk/proposals/{proposalId}/results")
        public void readProposalResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/bulk/executions/{executionId}")
        public void readExecution() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/bulk/executions/{executionId}/results")
        public void readExecutionResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @GetMapping("/bulk/executions/{executionId}/cancel")
        public void cancelExecution(@org.springframework.web.bind.annotation.RequestBody String body) { }
    }

    @ApiResource(value = "/api/body", resourceKey = "inventory.body")
    @BulkResourceOperations(
            proposalOperationId = "body.bulk.proposal",
            proposalResultsOperationId = "body.bulk.proposal-results",
            executionOperationId = "body.bulk.execution",
            executionResultsOperationId = "body.bulk.execution-results",
            cancelOperationId = "body.bulk.cancel")
    static class BodyLifecycleController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/body/proposals/{proposalId}") public void proposal() { }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/body/proposals/{proposalId}/results") public void proposalResults() { }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/body/executions/{executionId}") public void execution() { }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/body/executions/{executionId}/results") public void executionResults() { }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping("/body/executions/{executionId}/cancel")
        public void cancel(org.springframework.http.HttpEntity<String> body) { }
    }

    @ApiResource(value = "/api/media", resourceKey = "inventory.media")
    @BulkResourceOperations(
            proposalOperationId = "media.bulk.proposal",
            proposalResultsOperationId = "media.bulk.proposal-results",
            executionOperationId = "media.bulk.execution",
            executionResultsOperationId = "media.bulk.execution-results",
            cancelOperationId = "media.bulk.cancel")
    static class MediaConstrainedLifecycleController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/media/proposals/{proposalId}") public void proposal() { }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/media/proposals/{proposalId}/results") public void proposalResults() { }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/media/executions/{executionId}") public void execution() { }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/media/executions/{executionId}/results") public void executionResults() { }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping(value = "/media/executions/{executionId}/cancel", consumes = "application/json")
        public void cancel() { }
    }
}
