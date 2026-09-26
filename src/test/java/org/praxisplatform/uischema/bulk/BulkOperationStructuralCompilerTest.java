package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionInteractionPolicy;
import org.praxisplatform.uischema.action.ActionOutcomeMode;
import org.praxisplatform.uischema.action.ActionOutcomePolicy;
import org.praxisplatform.uischema.action.ActionPreconditionPolicy;
import org.praxisplatform.uischema.action.ActionRequirement;
import org.praxisplatform.uischema.action.ActionRefreshPolicy;
import org.praxisplatform.uischema.action.ActionResourceVersionTransport;
import org.praxisplatform.uischema.action.ActionSelectionPolicy;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkOperationStructuralCompilerTest {
    private static final String RESOURCE = "inventory.items";
    private static final String ACTION_ID = "items.bulk-approve";
    private static final String EVALUATION_ID = ACTION_ID + ".evaluation";

    @Test
    void composesSevenRealOperationsAgainstOneStrictGroupAndKeepsBodylessRolesBodyless() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

            BulkOperationStructuralDescriptor descriptor = compiler.compileAll().getFirst();

            assertEquals(RESOURCE, descriptor.resourceKey());
            assertEquals("inventory", descriptor.openApiGroup());
            assertEquals(BulkMode.DOMAIN_COMMAND, descriptor.mode());
            assertEquals(ActionCollectionAtomicity.PER_ITEM, descriptor.atomicity());
            assertEquals(List.of(BulkOperationStructuralDescriptor.Role.values()),
                    descriptor.operations().stream().map(BulkOperationStructuralDescriptor.Operation::role).toList());
            assertEquals(List.of("GET", "GET", "GET", "GET", "POST", "POST", "POST"),
                    descriptor.operations().stream().map(operation -> operation.reference().method()).toList());
            assertEquals(List.of(false, false, false, false, false, true, true),
                    descriptor.operations().stream().map(operation -> operation.requestSchema().isPresent()).toList());
            assertEquals(List.of(200, 202), descriptor.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .responseSchema().variants().stream().map(variant -> variant.status()).toList());
            assertEquals(1, documents.strictReads(), "all seven schema reads must use one exact group snapshot");
            assertEquals("approveSelectedItems", descriptor.action().id());
            assertEquals(List.of("BULK_APPROVE"), descriptor.action().requiredAuthorities());

            var returnedSchema = (com.fasterxml.jackson.databind.node.ObjectNode) descriptor
                    .operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .requestSchema().orElseThrow().schema();
            returnedSchema.remove("type");
            assertTrue(descriptor.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .requestSchema().orElseThrow().schema().has("type"));
            assertNotSame(returnedSchema, descriptor.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .requestSchema().orElseThrow().schema());
        }
    }

    @Test
    void rejectsAChangedActionDefinitionInsteadOfComposingAnUnrelatedWorkflowAction() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var wrongAction = new ActionDefinition("another-action", RESOURCE, "/api/items", "inventory",
                    ActionScope.COLLECTION, "Wrong action", "", new CanonicalOperationRef("inventory",
                    ACTION_ID, "/api/items/actions/bulk-approve", "POST"),
                    new CanonicalSchemaRef("request", "request", "/schemas/request"),
                    new CanonicalSchemaRef("response", "response", "/schemas/response"), 0, "",
                    List.of(), List.of(), List.of(), ActionExecutionContract.defaults(ActionScope.COLLECTION));

            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(wrongAction), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsMissingStrictResponseSchemaAndDoesNotReturnPartialDescriptor() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(false));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsLifecycleRequestBodyPublishedByTheOpenApiCustomizer() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            JsonNode document = document(true);
            ((ObjectNode) document.path("paths").path("/api/items/bulk/executions/{executionId}").path("get"))
                    .putObject("requestBody");
            var documents = new TestDocuments(document);
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
            assertEquals(1, documents.strictReads(), "lifecycle validation must use the captured group snapshot");
        }
    }

    @Test
    void refusesCompositionWhenAnInvalidOrphanDeclarationCoexistsWithAValidBinding() {
        try (var context = context(CompleteBulkController.class, OrphanBulkController.class)) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());
            var confirmation = mvc.getHandlerMethods().entrySet().stream()
                    .map(java.util.Map.Entry::getValue)
                    .filter(handler -> handler.getMethod().getName().equals("confirm"))
                    .filter(handler -> handler.getBeanType().equals(CompleteBulkController.class))
                    .findFirst().orElseThrow();

            assertTrue(bindings.bulkOperationFor(confirmation).isPresent());
            assertThrows(IllegalStateException.class, compiler::compileAll);
            assertEquals(0, documents.strictReads(), "diagnostics must stop before reading schema documents");
        }
    }

    @Test
    void rejectsSameActionIdentityWhenCatalogSemanticsDifferFromWorkflowAnnotation() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            ActionDefinition canonical = actionDefinition();
            ActionDefinition stale = new ActionDefinition(canonical.id(), canonical.resourceKey(),
                    canonical.resourcePath(), canonical.group(), ActionScope.ITEM, canonical.title(),
                    canonical.description(), canonical.operation(), canonical.requestSchema(), canonical.responseSchema(),
                    canonical.order(), canonical.successMessage(), canonical.requiredAuthorities(),
                    canonical.allowedStates(), canonical.tags(), canonical.execution());
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(stale), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsSameActionWhenCanonicalSchemaReferencesDiffer() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            ActionDefinition canonical = actionDefinition();
            ActionDefinition stale = new ActionDefinition(canonical.id(), canonical.resourceKey(),
                    canonical.resourcePath(), canonical.group(), canonical.scope(), canonical.title(),
                    canonical.description(), canonical.operation(),
                    new CanonicalSchemaRef("wrong-request", "request", "/schemas/filtered?wrong=request"),
                    canonical.responseSchema(), canonical.order(), canonical.successMessage(),
                    canonical.requiredAuthorities(), canonical.allowedStates(), canonical.tags(), canonical.execution());
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(stale), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsMatchingButInvalidActionExecutionContract() throws Exception {
        WorkflowAction workflow = InvalidCollectionIfMatch.class
                .getDeclaredMethod("action").getAnnotation(WorkflowAction.class);
        ActionDefinition canonical = actionDefinition();
        ActionExecutionContract invalidExecution = new ActionExecutionContract(
                new ActionInteractionPolicy(workflow.interactionMode(), workflow.riskLevel(),
                        workflow.confirmationRequired(), workflow.reversible()),
                new ActionPreconditionPolicy(workflow.idempotencyKey(), workflow.correlationId(),
                        workflow.resourceVersion(), workflow.resourceVersionTransport(),
                        workflow.resourceVersionField(), workflow.resourceVersionTargetResourceKey(),
                        workflow.resourceVersionTargetIdField()),
                new ActionSelectionPolicy(workflow.selectionIdsField(), workflow.selectionVersionsField(),
                        workflow.maxSelection()),
                new ActionOutcomePolicy(workflow.outcomeMode(), workflow.atomicity()),
                new ActionRefreshPolicy(workflow.refreshItem(), workflow.refreshCollection(),
                        workflow.refreshActions(), workflow.refreshCapabilities(), List.of(workflow.invalidatesResourceKeys())));
        ActionDefinition matchingInvalidCatalog = new ActionDefinition(workflow.id(), canonical.resourceKey(),
                canonical.resourcePath(), canonical.group(), workflow.scope(), workflow.title(), workflow.description(),
                canonical.operation(), canonical.requestSchema(), canonical.responseSchema(), workflow.order(),
                workflow.successMessage(), List.of(workflow.requiredAuthorities()), List.of(workflow.allowedStates()),
                List.of(workflow.tags()), invalidExecution);

        assertFalse(BulkOperationStructuralCompiler.workflowMatches(matchingInvalidCatalog, workflow));
    }

    @Test
    void permitsCanonicalPathTemplateAliasesAcrossPublishedGroupsAndRejectsChangedTargetIdentity() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            JsonNode other = document(true);
            JsonNode primaryAlias = aliasProposalTemplate(other);
            TestDocuments documents = new TestDocuments(primaryAlias, other);
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("inventory", "other"));
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

            assertEquals(7, compiler.compileAll().getFirst().operations().size());
            assertEquals(2, documents.strictReads());

            ((ObjectNode) other.path("paths").path("/api/items/bulk/proposals/{proposalId}").path("get"))
                    .put("operationId", "changed.proposal.identity");
            TestDocuments changedTarget = new TestDocuments(primaryAlias, other);
            var changedResolver = new OpenApiCanonicalOperationResolver(changedTarget, mvc, bindings,
                    List.of("inventory", "other"));
            var changedCompiler = new BulkOperationStructuralCompiler(bindings, changedResolver, changedTarget,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());
            assertThrows(IllegalStateException.class, changedCompiler::compileAll);

            JsonNode duplicateElsewhere = aliasProposalTemplate(document(true));
            operation((ObjectNode) duplicateElsewhere.path("paths"), "/api/unrelated", "get",
                    "items.bulk.proposal", false, true);
            TestDocuments duplicateDocument = new TestDocuments(primaryAlias, duplicateElsewhere);
            var duplicateResolver = new OpenApiCanonicalOperationResolver(duplicateDocument, mvc, bindings,
                    List.of("inventory", "other"));
            var duplicateCompiler = new BulkOperationStructuralCompiler(bindings, duplicateResolver, duplicateDocument,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());
            assertThrows(IllegalStateException.class, duplicateCompiler::compileAll);
        }
    }

    private AnnotationConfigWebApplicationContext context(Class<?>... controllers) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.register(controllers.length == 0 ? new Class<?>[] {CompleteBulkController.class} : controllers);
        context.refresh();
        return context;
    }

    private ActionDefinitionRegistry registry(ActionDefinition action) {
        return new ActionDefinitionRegistry() {
            @Override public List<ActionDefinition> findByResourceKey(String resourceKey) {
                return RESOURCE.equals(resourceKey) ? List.of(action) : List.of();
            }
            @Override public List<ActionDefinition> findByGroup(String group) { return List.of(action); }
        };
    }

    private ActionDefinition actionDefinition() {
        var schemaReferences = new FilteredSchemaReferenceResolver();
        CanonicalOperationRef operation = new CanonicalOperationRef("inventory",
                ACTION_ID, "/api/items/actions/bulk-approve", "POST");
        return new ActionDefinition("approveSelectedItems", RESOURCE, "/api/items", "inventory",
                ActionScope.COLLECTION, "Aprovar selecionados", "", operation,
                schemaReferences.requestSchema(operation), schemaReferences.responseSchema(operation), 10, "Aprovados",
                List.of("BULK_APPROVE"), List.of("READY"), List.of("workflow"),
                new ActionExecutionContract(
                        new ActionInteractionPolicy(null, null, false, false),
                        new ActionPreconditionPolicy(null, null, null, ActionResourceVersionTransport.NONE),
                        new ActionSelectionPolicy(null, null, null),
                        new ActionOutcomePolicy(ActionOutcomeMode.SINGLE, ActionCollectionAtomicity.PER_ITEM),
                        new ActionRefreshPolicy(false, true, true, true, List.of())));
    }

    private JsonNode document(boolean includeEveryResponse) {
        var root = new ObjectMapper().createObjectNode();
        root.put("openapi", "3.1.0");
        var paths = root.putObject("paths");
        operation(paths, "/api/items/bulk/proposals/{proposalId}", "get", "items.bulk.proposal", false, true);
        operation(paths, "/api/items/bulk/proposals/{proposalId}/results", "get", "items.bulk.proposal-results", false, true);
        operation(paths, "/api/items/bulk/executions/{executionId}", "get", "items.bulk.execution", false, true);
        operation(paths, "/api/items/bulk/executions/{executionId}/results", "get", "items.bulk.execution-results", false, true);
        operation(paths, "/api/items/bulk/executions/{executionId}/cancel", "post", "items.bulk.cancel", false, true);
        operation(paths, "/api/items/actions/bulk-approve/evaluation", "post", EVALUATION_ID, true, true);
        operation(paths, "/api/items/actions/bulk-approve", "post", ACTION_ID, true, includeEveryResponse);
        return root;
    }

    private JsonNode aliasProposalTemplate(JsonNode source) {
        ObjectNode root = (ObjectNode) source.deepCopy();
        ObjectNode paths = (ObjectNode) root.path("paths");
        JsonNode proposal = paths.remove("/api/items/bulk/proposals/{proposalId}");
        paths.set("/api/items/bulk/proposals/{id}", proposal);
        JsonNode results = paths.remove("/api/items/bulk/proposals/{proposalId}/results");
        paths.set("/api/items/bulk/proposals/{id}/results", results);
        return root;
    }

    private void operation(com.fasterxml.jackson.databind.node.ObjectNode paths, String path, String method,
            String operationId, boolean withRequestBody, boolean withResponse) {
        var operation = paths.putObject(path).putObject(method);
        operation.put("operationId", operationId);
        if (withRequestBody) {
            var content = operation.putObject("requestBody").putObject("content");
            content.set("application/json", mediaSchema());
        }
        var responses = operation.putObject("responses");
        if (withResponse) {
            var success = responses.putObject("200");
            success.putObject("content").set("application/json", mediaSchema());
            if (ACTION_ID.equals(operationId)) {
                var accepted = responses.putObject("202");
                accepted.putObject("content").set("application/json", mediaSchema());
            }
        }
    }

    private JsonNode mediaSchema() {
        var media = new ObjectMapper().createObjectNode();
        media.putObject("schema").put("type", "object").putObject("properties")
                .putObject("accepted").put("type", "boolean");
        return media;
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfiguration { }

    @ApiResource(value = "/api/items", resourceKey = RESOURCE)
    @BulkResourceOperations(proposalOperationId = "items.bulk.proposal",
            proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution",
            executionResultsOperationId = "items.bulk.execution-results",
            cancelOperationId = "items.bulk.cancel")
    static class CompleteBulkController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/bulk/proposals/{proposalId}") public String proposal() { return ""; }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/bulk/proposals/{proposalId}/results") public String proposalResults() { return ""; }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/bulk/executions/{executionId}") public String execution() { return ""; }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/bulk/executions/{executionId}/results") public String executionResults() { return ""; }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping("/bulk/executions/{executionId}/cancel") public String cancel() { return ""; }

        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = EVALUATION_ID,
                atomicity = ActionCollectionAtomicity.PER_ITEM)
        @WorkflowAction(id = "approveSelectedItems", title = "Aprovar selecionados",
                scope = ActionScope.COLLECTION, order = 10, successMessage = "Aprovados",
                atomicity = ActionCollectionAtomicity.PER_ITEM, requiredAuthorities = {"BULK_APPROVE"},
                allowedStates = {"READY"}, tags = {"workflow"})
        @Operation(operationId = ACTION_ID)
        @PostMapping("/actions/bulk-approve")
        public String confirm(@RequestBody ConfirmationRequest request) { return ""; }

        @Operation(operationId = EVALUATION_ID)
        @PostMapping("/actions/bulk-approve/evaluation")
        public String evaluate(@RequestBody EvaluationRequest request) { return ""; }
    }

    @ApiResource(value = "/api/orphan", resourceKey = "inventory.orphan")
    static class OrphanBulkController {
        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "orphan.evaluation",
                atomicity = ActionCollectionAtomicity.PER_ITEM)
        @WorkflowAction(id = "orphan", title = "Orphan", scope = ActionScope.COLLECTION,
                atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "orphan.confirmation")
        @PostMapping("/actions/orphan")
        public String confirm(@RequestBody ConfirmationRequest request) { return ""; }
    }

    static class InvalidCollectionIfMatch {
        @WorkflowAction(id = "approveSelectedItems", title = "Aprovar selecionados",
                description = "", scope = ActionScope.COLLECTION, order = 10, successMessage = "Aprovados",
                requiredAuthorities = {"BULK_APPROVE"}, allowedStates = {"READY"}, tags = {"workflow"},
                atomicity = ActionCollectionAtomicity.PER_ITEM, resourceVersion = ActionRequirement.REQUIRED,
                resourceVersionTransport = ActionResourceVersionTransport.IF_MATCH,
                refreshItem = false, refreshCollection = true, refreshActions = true, refreshCapabilities = true)
        void action() { }
    }

    record ConfirmationRequest(String reason) { }
    record EvaluationRequest(List<String> ids) { }

    private static final class TestDocuments implements OpenApiDocumentService {
        private final java.util.Map<String, JsonNode> documents;
        private final AtomicInteger strictReads = new AtomicInteger();
        private TestDocuments(JsonNode document) { this(document, null); }
        private TestDocuments(JsonNode primary, JsonNode other) {
            this.documents = new java.util.HashMap<>();
            this.documents.put("inventory", primary.deepCopy());
            if (other != null) this.documents.put("other", other.deepCopy());
        }
        @Override public String resolveGroupFromPath(String path) { return "inventory"; }
        @Override public JsonNode getDocumentForGroup(String groupName) { return getDocumentForGroupStrict(groupName); }
        @Override public JsonNode getDocumentForGroupStrict(String groupName) {
            strictReads.incrementAndGet();
            JsonNode document = documents.get(groupName);
            if (document == null) throw new IllegalStateException("unexpected group " + groupName);
            return document.deepCopy();
        }
        int strictReads() { return strictReads.get(); }
        @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
            throw new UnsupportedOperationException("schema hash is not part of structural composition");
        }
        @Override public void clearCaches() { }
    }
}
