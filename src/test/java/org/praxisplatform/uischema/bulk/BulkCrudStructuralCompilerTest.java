package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkCrudStructuralCompilerTest {
    static final String RESOURCE = "crud.items";
    static final String SOURCE = "crud.update";

    @Test
    void bothModesUseOneExplicitUnitDtoAndOneGroupSnapshotWithoutWorkflow() {
        try (var context = context(CrudController.class)) {
            var mapper = mapper();
            var documents = new Documents(document());
            var descriptors = compile(context, mapper, documents);
            assertEquals(2, descriptors.size());
            assertEquals(1, documents.reads.get());
            for (var descriptor : descriptors) {
                assertEquals(7, descriptor.operations().size());
                assertThrows(IllegalStateException.class, descriptor::action);
                var update = descriptor.update();
                assertEquals(SOURCE, update.operation().operationId());
                assertEquals("PUT", update.operation().method());
                assertEquals(Update.class.getName(), update.requestJavaType());
                assertEquals(Set.of("identity", "revision"), update.protectedFields());
                assertEquals(Set.of("display_name"), update.editableFields().writableFields(BulkMode.UNIFORM_UPDATE));
                assertEquals(Set.of("display_name", "priority", "active"), update.editableFields().writableFields(BulkMode.PER_ITEM_UPDATE));
                assertEquals(Set.of("display_name"), update.editableFields().clearableFields(descriptor.mode()));
                assertFalse(update.requestSchema().schema().path("properties").has("changes"));
                assertTrue(descriptor.operation(BulkOperationStructuralDescriptor.Role.EVALUATION)
                        .requestSchema().orElseThrow().schema().path("properties").has("changes"));
                assertNotNull(update.filteredRequest());
                assertTrue(descriptor.operations().stream().filter(op -> op.requestSchema().isPresent()).count() == 2);
                assertEquals("praxis.bulk.structure/4", BulkStructuralSegmentDigest.canonicalContent(descriptor).path("structureVersion").asText());
            }
            var original = descriptors.getFirst().update().requestSchema().schema();
            ((ObjectNode) original).removeAll();
            assertTrue(descriptors.getFirst().update().requestSchema().schema().has("properties"));
        }
    }

    @Test
    void mapperNamingAndNullHandlingComeFromTheConfiguredMapper() {
        try (var context = context(CrudController.class)) {
            assertThrows(IllegalArgumentException.class, () -> compile(context, new ObjectMapper(), new Documents(document())));
            var mapper = mapper();
            mapper.setDefaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.SKIP));
            assertThrows(IllegalArgumentException.class, () -> compile(context, mapper, new Documents(document())));
            assertEquals(2, compile(context, mapper(), new Documents(document())).size());
        }
    }

    @Test
    void sourceRawSchemaAndDeclarationsFenceProtectedReadOnlyAndClearEligibility() {
        try (var context = context(CrudController.class)) {
            var readOnly = document();
            property(readOnly, "display_name").put("readOnly", true);
            assertThrows(IllegalArgumentException.class, () -> compile(context, mapper(), new Documents(readOnly)));
            var notNullable = document();
            property(notNullable, "display_name").remove("nullable");
            assertThrows(IllegalArgumentException.class, () -> compile(context, mapper(), new Documents(notNullable)));
            var missing = document();
            ((ObjectNode) sourceSchema(missing).path("properties")).remove("display_name");
            assertThrows(IllegalArgumentException.class, () -> compile(context, mapper(), new Documents(missing)));
        }
        try (var context = context(ProtectedController.class)) {
            assertThrows(IllegalArgumentException.class, () -> compile(context, mapper(), new Documents(document())));
        }
    }

    @Test
    void misspelledProtectedWireNameFailsBeforeCompilingAnAllowlist() {
        try (var context = context(MisspelledProtectedController.class)) {
            var failure = assertThrows(IllegalStateException.class, () -> compile(context, mapper(), new Documents(document())));
            assertTrue(failure.getMessage().contains("absent from the actual DTO or request schema"));
        }
    }

    @Test
    void protectedWireNamesMustBeDeserializableWithTheConfiguredMapper() throws Exception {
        try (var context = context(CrudController.class)) {
            var readOnly = mapper().addMixIn(Update.class, ReadOnlyIdentity.class);
            assertThrows(com.fasterxml.jackson.databind.exc.InvalidDefinitionException.class,
                    () -> readOnly.readValue("{\"identity\":7,\"revision\":2}", Update.class));
            var readOnlyFailure = assertThrows(IllegalStateException.class,
                    () -> compile(context, readOnly, new Documents(document())));
            assertTrue(readOnlyFailure.getMessage().contains("absent from the actual DTO or request schema"));

            var ignored = mapper().addMixIn(Update.class, IgnoredIdentity.class);
            var decoded = ignored.readValue("{\"identity\":7,\"revision\":2}", Update.class);
            assertNull(decoded.identity(), "the real configured decoder discards the protected input");
            assertEquals(2L, decoded.revision().longValue());
            var creator = ignored.getDeserializationConfig().introspect(ignored.constructType(Update.class))
                    .findProperties().stream().filter(property -> property.getName().equals("identity")).findFirst().orElseThrow();
            assertTrue(creator.couldDeserialize(), "creator metadata alone does not prove the record input is accepted");
            var ignoredFailure = assertThrows(IllegalStateException.class,
                    () -> compile(context, ignored, new Documents(document())));
            assertTrue(ignoredFailure.getMessage().contains("absent from the actual DTO or request schema"));
        }
    }
    @Test
    void effectiveClassIgnoralsRejectProtectedInputsButAllowSettersRemainsDeserializable() throws Exception {
        try (var context = context(CrudController.class)) {
            var byMixin = mapper().addMixIn(Update.class, ClassIgnoredIdentity.class);
            var byOverride = mapper();
            byOverride.configOverride(Update.class).setIgnorals(
                    com.fasterxml.jackson.annotation.JsonIgnoreProperties.Value.forIgnoredProperties("identity"));
            for (var configured : List.of(byMixin, byOverride)) {
                var decoded = configured.readValue("{\"identity\":7,\"revision\":2}", Update.class);
                assertNull(decoded.identity());
                assertEquals(2L, decoded.revision().longValue());
                var failure = assertThrows(IllegalStateException.class,
                        () -> compile(context, configured, new Documents(document())));
                assertTrue(failure.getMessage().contains("absent from the actual DTO or request schema"));
            }
            var allowByMixin = mapper().addMixIn(Update.class, ClassAllowsIdentityInput.class);
            var allowByOverride = mapper();
            allowByOverride.configOverride(Update.class).setIgnorals(
                    com.fasterxml.jackson.annotation.JsonIgnoreProperties.Value.forIgnoredProperties("identity").withAllowSetters());
            for (var configured : List.of(allowByMixin, allowByOverride)) {
                var decoded = configured.readValue("{\"identity\":7,\"revision\":2}", Update.class);
                assertEquals(7, decoded.identity().intValue());
                assertEquals(2, compile(context, configured, new Documents(document())).size());
            }
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("identity")
    abstract static class ClassIgnoredIdentity { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = "identity", allowSetters = true)
    abstract static class ClassAllowsIdentityInput { }

    abstract static class ReadOnlyIdentity {
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        abstract Integer identity();
    }
    abstract static class IgnoredIdentity {
        @com.fasterxml.jackson.annotation.JsonIgnore abstract Integer identity();
    }

    @Test
    void digestIncludesSourceMetadataProtectedFieldsAndBothModesWithoutRetainingMutableInput() {
        try (var context = context(CrudController.class)) {
            var first = compile(context, mapper(), new Documents(document())).getFirst();
            var hash = BulkStructuralSegmentDigest.compute(first);
            assertEquals(hash, BulkStructuralSegmentDigest.compute(compile(context, mapper(), new Documents(document())).getFirst()));
            var changed = document();
            property(changed, "priority").put("description", "Metadata-only change in the other update mode");
            assertNotEquals(hash, BulkStructuralSegmentDigest.compute(compile(context, mapper(), new Documents(changed)).getFirst()));
            var content = BulkStructuralSegmentDigest.canonicalContent(first);
            assertEquals(List.of("identity", "revision"), new ObjectMapper().convertValue(content.at("/update/protectedFields"), List.class));
            for (String pointer : List.of("/update/requestJavaType", "/update/operation/operationId", "/update/requestSchema/mediaType")) {
                var copy = content.deepCopy();
                ((ObjectNode) copy.at(pointer.substring(0, pointer.lastIndexOf('/')))).put(pointer.substring(pointer.lastIndexOf('/') + 1), "changed");
                assertNotEquals(hash, BulkCanonicalJson.structuralDescriptorDigest(copy));
            }
            var copy = content.deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode) copy.at("/update/editableFields/PER_ITEM_UPDATE/writable")).add("unexpected");
            assertNotEquals(hash, BulkCanonicalJson.structuralDescriptorDigest(copy));
        }
        try (var firstContext = context(CrudController.class); var secondContext = context(AdditionalProtectedController.class)) {
            assertNotEquals(BulkStructuralSegmentDigest.compute(compile(firstContext, mapper(), new Documents(document())).getFirst()),
                    BulkStructuralSegmentDigest.compute(compile(secondContext, mapper(), new Documents(document())).getFirst()));
        }
    }

    @Test
    void sourceIdentityGroupAndSchemaDriftFailClosed() {
        try (var context = context(CrudController.class)) {
            var changed = document();
            ((ObjectNode) changed.at("/paths/~1crud-items~1{id}/put")).put("operationId", "different");
            assertThrows(IllegalStateException.class, () -> compile(context, mapper(), new Documents(changed)));
            var bodyMissing = document();
            ((ObjectNode) bodyMissing.at("/paths/~1crud-items~1{id}/put")).remove("requestBody");
            assertThrows(IllegalStateException.class, () -> compile(context, mapper(), new Documents(bodyMissing)));
            var wrongGroup = new Documents(document()) {
                @Override public String resolveGroupFromPath(String path) { return path.endsWith("/{id}") ? "other" : "crud"; }
            };
            assertThrows(IllegalStateException.class, () -> compile(context, mapper(), wrongGroup));
        }
    }

    @Test
    void structurallyValidUpdatesCannotBecomeAnOperationalDescriptorOrActionProjection() {
        try (var context = context(CrudController.class)) {
            var descriptor = compile(context, mapper(), new Documents(document())).getFirst();
            var provider = mock(BulkOperationDescriptorProvider.class);
            var failure = assertThrows(IllegalArgumentException.class, () -> BulkOperationalDescriptorComposer.compose(descriptor, provider));
            assertTrue(failure.getMessage().contains("not supported"));
            verifyNoInteractions(provider);
        }
    }

    static List<BulkOperationStructuralDescriptor> compile(AnnotationConfigWebApplicationContext context, ObjectMapper mapper, Documents documents) {
        var mvc = context.getBean(RequestMappingHandlerMapping.class);
        var bindings = BulkResourceOperationBindings.from(mvc);
        assertTrue(bindings.diagnostics().isEmpty(), bindings.diagnostics().toString());
        var actions = mock(ActionDefinitionRegistry.class);
        var compiler = new BulkOperationStructuralCompiler(bindings,
                new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("crud")), documents, actions, mapper,
                new FilteredSchemaReferenceResolver());
        var result = compiler.compileAll();
        verifyNoInteractions(actions);
        return result;
    }

    static ObjectMapper mapper() { return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE); }
    static AnnotationConfigWebApplicationContext context(Class<?> controller) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Mvc.class, controller);
        context.refresh();
        return context;
    }
    @Configuration @EnableWebMvc static class Mvc { }

    static ObjectNode document() {
        var root = new ObjectMapper().createObjectNode().put("openapi", "3.0.1");
        var paths = root.putObject("paths");
        for (String name : List.of("proposal", "proposal-results", "execution", "results", "cancel")) {
            var op = operation(paths, "/crud-items/bulk/" + name, name.equals("cancel") ? "post" : "get", "crud." + name);
            response(op);
        }
        for (String name : List.of("uniform", "items")) {
            var evaluation = operation(paths, "/crud-items/bulk/" + name + "/evaluation", "post", "crud." + name + ".evaluation");
            request(evaluation).putObject("properties").putObject("changes").put("type", "array").putObject("items").put("type", "object");
            response(evaluation);
            var confirmation = operation(paths, "/crud-items/bulk/" + name, "post", "crud." + name);
            request(confirmation).putObject("properties").putObject("proposalId").put("type", "string");
            response(confirmation);
        }
        var update = operation(paths, "/crud-items/{id}", "put", SOURCE);
        var properties = request(update).putObject("properties");
        properties.putObject("display_name").put("type", "string").put("nullable", true);
        properties.putObject("priority").put("type", "integer");
        properties.putObject("active").put("type", "boolean");
        properties.putObject("identity").put("type", "integer");
        properties.putObject("revision").put("type", "integer");
        properties.putObject("spare").put("type", "string");
        response(update);
        return root;
    }
    static ObjectNode operation(ObjectNode paths, String path, String method, String id) { return paths.putObject(path).putObject(method).put("operationId", id); }
    static ObjectNode request(ObjectNode op) { return op.putObject("requestBody").putObject("content").putObject("application/json").putObject("schema").put("type", "object"); }
    static void response(ObjectNode op) { op.putObject("responses").putObject("200").putObject("content").putObject("application/json").putObject("schema").put("type", "object").putObject("properties").putObject("proposalId").put("type", "string"); }
    static JsonNode sourceSchema(JsonNode document) { return document.at("/paths/~1crud-items~1{id}/put/requestBody/content/application~1json/schema"); }
    static ObjectNode property(JsonNode document, String name) { return (ObjectNode) sourceSchema(document).path("properties").path(name); }
    static class Documents implements OpenApiDocumentService {
        final JsonNode value;
        final AtomicInteger reads = new AtomicInteger();
        Documents(JsonNode value) { this.value = value.deepCopy(); }
        @Override public JsonNode getDocumentForGroup(String group) { throw new AssertionError("strict source required"); }
        @Override public JsonNode getDocumentForGroupStrict(String group) { reads.incrementAndGet(); return value.deepCopy(); }
        @Override public String resolveGroupFromPath(String path) { return "crud"; }
        @Override public String getOrComputeSchemaHash(String id, java.util.function.Supplier<JsonNode> value) {
            throw new AssertionError("schema hash cache is not composition authority");
        }
        @Override public void clearCaches() { throw new AssertionError("compilation must not clear caches"); }
    }

    public record Update(
            @BulkEditable(allowClear = true) @Schema(nullable = true) String displayName,
            @BulkEditable(modes = BulkMode.PER_ITEM_UPDATE) Integer priority,
            @BulkEditable(modes = BulkMode.PER_ITEM_UPDATE) Boolean active,
            Integer identity, Long revision, String spare) { }
    public record Evaluation(List<String> changes) { }
    public record Confirmation(String proposalId) { }
    @ApiResource(value = "/crud-items", resourceKey = RESOURCE)
    @BulkResourceOperations(proposalOperationId = "crud.proposal", proposalResultsOperationId = "crud.proposal-results",
            executionOperationId = "crud.execution", executionResultsOperationId = "crud.results", cancelOperationId = "crud.cancel",
            updateSourceOperationId = SOURCE, protectedUpdateFields = {"identity", "revision"})
    public static class CrudController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL) @GetMapping("/bulk/proposal") public Confirmation proposal() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS) @GetMapping("/bulk/proposal-results") public Confirmation proposals() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION) @GetMapping("/bulk/execution") public Confirmation execution() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS) @GetMapping("/bulk/results") public Confirmation results() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL) @PostMapping("/bulk/cancel") public Confirmation cancel() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.UPDATE_SOURCE) @PutMapping("/{id}") public Update update(@RequestBody Update body) { return body; }
        @Operation(operationId = "crud.uniform.evaluation") @PostMapping("/bulk/uniform/evaluation") public Confirmation evaluate(@RequestBody Evaluation body) { return null; }
        @Operation(operationId = "crud.items.evaluation") @PostMapping("/bulk/items/evaluation") public Confirmation evaluateItems(@RequestBody Evaluation body) { return null; }
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "crud.uniform.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.uniform") @PostMapping("/bulk/uniform") public Confirmation confirm(@RequestBody Confirmation body) { return body; }
        @BulkOperation(mode = BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "crud.items.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.items") @PostMapping("/bulk/items") public Confirmation confirmItems(@RequestBody Confirmation body) { return body; }
    }
    @ApiResource(value = "/crud-items", resourceKey = RESOURCE)
    @BulkResourceOperations(proposalOperationId = "crud.proposal", proposalResultsOperationId = "crud.proposal-results",
            executionOperationId = "crud.execution", executionResultsOperationId = "crud.results", cancelOperationId = "crud.cancel",
            updateSourceOperationId = SOURCE, protectedUpdateFields = {"display_name"})
    public static class ProtectedController extends CrudController { }
    @ApiResource(value = "/crud-items", resourceKey = RESOURCE)
    @BulkResourceOperations(proposalOperationId = "crud.proposal", proposalResultsOperationId = "crud.proposal-results",
            executionOperationId = "crud.execution", executionResultsOperationId = "crud.results", cancelOperationId = "crud.cancel",
            updateSourceOperationId = SOURCE, protectedUpdateFields = {"revision", "identity", "spare"})
    public static class AdditionalProtectedController extends CrudController { }
    @ApiResource(value = "/crud-items", resourceKey = RESOURCE)
    @BulkResourceOperations(proposalOperationId = "crud.proposal", proposalResultsOperationId = "crud.proposal-results",
            executionOperationId = "crud.execution", executionResultsOperationId = "crud.results", cancelOperationId = "crud.cancel",
            updateSourceOperationId = SOURCE, protectedUpdateFields = {"revision_typo"})
    public static class MisspelledProtectedController extends CrudController { }
}
