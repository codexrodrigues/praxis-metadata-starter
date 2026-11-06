package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedOpenApiOperationIndexTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CanonicalOperationRef OP = new CanonicalOperationRef("main", "items.read", "/api/items/{id}", "GET");

    @ApiResource(value = "/api/items", resourceKey = "items")
    static class Controller {
        @Operation(operationId = "items.read") public void read() { }
        @Operation(operationId = "items.other") public void other() { }
        @Operation(operationId = "items.write") public void write(
                @org.springframework.web.bind.annotation.RequestBody Payload body) { }
        @Operation(operationId = "items.batch") public void batch(
                @org.springframework.web.bind.annotation.RequestBody BatchPayload body) { }
    }
    record Payload(String name) { }
    record BatchPayload(java.util.List<String> names) { }

    @Test void fullCompositionCannotReturnAfterMvcChangesAndDoesNotRetainAValidationExemption() throws Exception {
        var f = new Fixture(true);
        var scope = new CanonicalOpenApiGroupSnapshot.CaptureScope(f.documents, false);
        var changed = new LinkedHashMap<>(f.mappings);
        changed.put(RequestMappingInfo.paths("/api/unrelated").methods(RequestMethod.GET).build(),
                new HandlerMethod(new Controller(), Controller.class.getMethod("other")));
        assertThrows(IllegalStateException.class, () -> scope.compose(() -> {
            f.validate(scope.capture("main"));
            when(f.handlers.getHandlerMethods()).thenReturn(changed);
            // Reads still use the immutable captured registry; the enclosing result must fail.
            f.validate(scope.capture("main"));
            return "must-not-escape";
        }));
        assertThrows(IllegalStateException.class, () -> f.validate(scope.capture("main")));
        assertThrows(IllegalStateException.class, () -> scope.compose(() -> "stale"));
        var fresh = new CanonicalOpenApiGroupSnapshot.CaptureScope(f.documents, false);
        assertEquals("fresh", fresh.compose(() -> { f.validate(fresh.capture("main")); return "fresh"; }));
    }

    @Test void bodyBatchPreservesOrderingAndTypesAndRejectsRegistryChangeBeforeReturningAnyResult() throws Exception {
        var f = new Fixture(true);
        var mappings = new LinkedHashMap<>(f.mappings);
        mappings.put(RequestMappingInfo.paths("/api/items").methods(RequestMethod.POST).build(),
                new HandlerMethod(new Controller(), Controller.class.getMethod("write", Payload.class)));
        mappings.put(RequestMappingInfo.paths("/api/items/batch").methods(RequestMethod.POST).build(),
                new HandlerMethod(new Controller(), Controller.class.getMethod("batch", BatchPayload.class)));
        when(f.handlers.getHandlerMethods()).thenReturn(mappings);
        when(f.documents.resolveGroupFromPath("/api/items")).thenReturn("main");
        when(f.documents.resolveGroupFromPath("/api/items/batch")).thenReturn("main");
        var snapshot = f.snapshot();
        var operations = List.of(new CanonicalOperationRef("main", "items.batch", "/api/items/batch", "POST"),
                new CanonicalOperationRef("main", "items.write", "/api/items", "POST"));
        var bodies = f.resolver.requireResourceRequestBodies("items", operations, JSON.getTypeFactory(), snapshot);
        assertEquals(operations, bodies.stream().map(CanonicalRequestBodyBinding::operation).toList());
        assertEquals(List.of(BatchPayload.class, Payload.class), bodies.stream().map(b -> b.bodyType().getRawClass()).toList());
        when(f.handlers.getHandlerMethods()).thenReturn(mappings, f.mappings);
        assertThrows(IllegalStateException.class, () ->
                f.resolver.requireResourceRequestBodies("items", operations, JSON.getTypeFactory(), snapshot));
        when(f.handlers.getHandlerMethods()).thenReturn(mappings);
        assertThrows(IllegalStateException.class, () -> f.resolver.requireResourceRequestBodies("items",
                List.of(operations.getFirst(), OP), JSON.getTypeFactory(), snapshot));
        verify(f.resolver, times(1)).indexMappings();
    }

    @Test void requestBodiesShareTheCompositionIndexAndRetainTypeAndLiveRegistryChecks() throws Exception {
        var f = new Fixture(true);
        var write = new HandlerMethod(new Controller(), Controller.class.getMethod("write", Payload.class));
        var writeMapping = RequestMappingInfo.paths("/api/items").methods(RequestMethod.POST).build();
        var mappings = new LinkedHashMap<>(f.mappings);
        mappings.put(writeMapping, write);
        when(f.handlers.getHandlerMethods()).thenReturn(mappings);
        when(f.documents.resolveGroupFromPath("/api/items")).thenReturn("main");
        var snapshot = f.snapshot();
        f.validate(snapshot);
        var operation = new CanonicalOperationRef("main", "items.write", "/api/items", "POST");
        for (int i = 0; i < 2; i++) {
            var body = f.resolver.requireResourceRequestBody("items", operation, JSON.getTypeFactory(), snapshot);
            assertEquals(Payload.class, body.bodyType().getRawClass());
        }
        verify(f.resolver, times(1)).indexMappings();
        assertThrows(IllegalStateException.class, () ->
                f.resolver.requireResourceRequestBody("foreign", operation, JSON.getTypeFactory(), snapshot));
        assertThrows(IllegalArgumentException.class, () -> f.resolver.requireResourceRequestBody("items",
                new CanonicalOperationRef("other", "items.write", "/api/items", "POST"), JSON.getTypeFactory(), snapshot));
        when(f.handlers.getHandlerMethods()).thenReturn(f.mappings);
        assertThrows(IllegalStateException.class, () ->
                f.resolver.requireResourceRequestBody("items", operation, JSON.getTypeFactory(), snapshot));
    }

    @Test void compositionIndexesUnrelatedHandlersOnceAndNewScopeRebuildsThem() throws Exception {
        var f = new Fixture(true);
        var unrelated = new HandlerMethod(new Controller(), Controller.class.getMethod("other"));
        var mappings = new LinkedHashMap<>(f.mappings);
        mappings.put(RequestMappingInfo.paths("/api/unrelated").methods(RequestMethod.GET).build(), unrelated);
        when(f.handlers.getHandlerMethods()).thenReturn(mappings);
        var scope = new CanonicalOpenApiGroupSnapshot.CaptureScope(f.documents, false);
        f.validate(scope.capture("main"));
        f.validate(scope.capture("main"));
        var other = scope.capture("other");
        when(f.documents.resolveGroupFromPath(OP.path())).thenReturn("other");
        f.resolver.requireResourceOperations("items", List.of(
                new CanonicalOperationRef("other", OP.operationId(), OP.path(), OP.method())), other);
        verify(f.resolver, times(1)).indexMappings();
        when(f.documents.resolveGroupFromPath(OP.path())).thenReturn("main");
        f.validate(f.snapshot());
        verify(f.resolver, times(2)).indexMappings();
    }

    @Test void cachedMvcIndexRejectsEvenAnUnrelatedRegistryChange() throws Exception {
        var f = new Fixture(true);
        var snapshot = f.snapshot();
        f.validate(snapshot);
        var changed = new LinkedHashMap<>(f.mappings);
        changed.put(RequestMappingInfo.paths("/api/unrelated").methods(RequestMethod.GET).build(),
                new HandlerMethod(new Controller(), Controller.class.getMethod("other")));
        when(f.handlers.getHandlerMethods()).thenReturn(changed);
        assertThrows(IllegalStateException.class, () -> f.validate(snapshot));
        f.validate(f.snapshot());
    }

    @Test void indexedAddressStillRejectsAnUnrestrictedMethodCollision() throws Exception {
        var f = new Fixture(true);
        var changed = new LinkedHashMap<>(f.mappings);
        changed.put(RequestMappingInfo.paths(OP.path()).build(),
                new HandlerMethod(new Controller(), Controller.class.getMethod("other")));
        when(f.handlers.getHandlerMethods()).thenReturn(changed);
        assertThrows(IllegalStateException.class, () -> f.validate(f.snapshot()));
    }

    @Test void publishedBatchReusesIndexWithoutConsumingAliasEvidenceAndStillChecksLiveMvc() throws Exception {
        var f = new Fixture(true);
        var snapshot = f.snapshot();
        f.validate(snapshot); f.validate(snapshot);
        verify(f.documents, times(1)).getDocumentForGroupStrict("other");
        var duplicate = RequestMappingInfo.paths("/api/duplicate").methods(RequestMethod.GET).build();
        var changed = new LinkedHashMap<>(f.mappings);
        changed.put(duplicate, f.mappings.values().iterator().next());
        when(f.handlers.getHandlerMethods()).thenReturn(changed);
        assertThrows(IllegalStateException.class, () -> f.validate(snapshot));
    }

    @Test void rotatedOrSuspendedPublicationCannotReuseCapturedIndex() throws Exception {
        var f = new Fixture(true);
        f.token.set(new String("equal-content-distinct-publication"));
        var snapshot = f.snapshot(); f.validate(snapshot);
        f.token.set(new String("equal-content-distinct-publication"));
        assertThrows(IllegalStateException.class, () -> f.validate(snapshot));
        f.validate(f.snapshot());
        f.token.set(null);
        assertThrows(IllegalStateException.class, () -> f.validate(snapshot));
    }

    @Test void oneCompositionSharesItsGlobalIndexAcrossDifferentExactGroups() throws Exception {
        var f = new Fixture(true);
        var scope = new CanonicalOpenApiGroupSnapshot.CaptureScope(f.documents, false);
        f.validate(scope.capture("main"));
        var other = scope.capture("other");
        when(f.documents.resolveGroupFromPath(OP.path())).thenReturn("other");
        var otherOperation = new CanonicalOperationRef("other", OP.operationId(), OP.path(), OP.method());
        assertEquals(List.of(otherOperation), f.resolver.requireResourceOperations("items", List.of(otherOperation), other));
        // One initial main capture and one global index: the second group must not copy main again.
        verify(f.documents, times(1)).getDocumentForGroupStrict("main");
        verify(f.documents, times(2)).getDocumentForGroupStrict("other");
        assertSame(other, scope.capture("other"));
        // A group invalidation fences an already captured snapshot even without publication replacement.
        when(f.documents.groupDocumentCacheToken("other")).thenReturn(new Object());
        assertThrows(IllegalStateException.class,
                () -> f.resolver.requireResourceOperations("items", List.of(otherOperation), other));
    }

    @Test void newPublicationRechecksGlobalPathCallbackAndWebhookCollisions() throws Exception {
        for (String location : List.of("path", "callback", "webhook")) {
            var f = new Fixture(true);
            f.validate(f.snapshot());
            if (location.equals("path"))
                f.other.withObject("/paths").putObject("/rogue").putObject("get").put("operationId", OP.operationId());
            else if (location.equals("webhook"))
                f.other.putObject("webhooks").putObject("hook").putObject("post").put("operationId", OP.operationId());
            else
                f.other.withObject("/paths").withObject("/~1api~1items~1{code}")
                        .withObject("/get").putObject("callbacks").putObject("hook")
                        .putObject("{$request.body#/url}").putObject("post").put("operationId", OP.operationId());
            f.token.set(new Object());
            assertThrows(IllegalStateException.class, () -> f.validate(f.snapshot()), location);
        }
    }

    @Test void unpublishedSourceIsNotMemoizedAndAddedGroupsAreNotIgnored() throws Exception {
        var f = new Fixture(false);
        var snapshot = f.snapshot(); f.validate(snapshot);
        f.other.withObject("/paths").putObject("/rogue").putObject("get").put("operationId", OP.operationId());
        assertThrows(IllegalStateException.class, () -> f.validate(snapshot));
        verify(f.documents, times(2)).getDocumentForGroupStrict("other");

        var published = new Fixture(true);
        var captured = published.snapshot(); published.validate(captured);
        published.groups.set(List.of("main", "other", "new-group"));
        doReturn(f.other).when(published.documents).getDocumentForGroupStrict("new-group");
        assertThrows(IllegalStateException.class, () -> published.validate(captured));
    }

    @Test void publicationChangeDuringIndexBuildFailsBeforeEvidenceCanBeReused() throws Exception {
        var f = new Fixture(true);
        var snapshot = f.snapshot();
        when(f.documents.getDocumentForGroupStrict("other")).thenAnswer(call -> {
            f.token.set(new Object()); return f.other;
        });
        assertThrows(IllegalStateException.class, () -> f.validate(snapshot));
    }

    @Test void mvcChangeDuringDocumentIndexingInvalidatesTheWholeBatch() throws Exception {
        var f = new Fixture(true);
        var snapshot = f.snapshot();
        when(f.documents.getDocumentForGroupStrict("other")).thenAnswer(call -> {
            var changed = new LinkedHashMap<>(f.mappings);
            changed.put(RequestMappingInfo.paths("/duplicate").methods(RequestMethod.GET).build(),
                    f.mappings.values().iterator().next());
            when(f.handlers.getHandlerMethods()).thenReturn(changed);
            return f.other;
        });
        assertThrows(IllegalStateException.class, () -> f.validate(snapshot));
    }

    private static class Fixture {
        final AtomicReference<Object> token;
        final AtomicReference<List<String>> groups = new AtomicReference<>(List.of("main", "other"));
        final OpenApiDocumentService documents = mock(OpenApiDocumentService.class, CALLS_REAL_METHODS);
        final RequestMappingHandlerMapping handlers = mock(RequestMappingHandlerMapping.class);
        final org.praxisplatform.uischema.bulk.BulkResourceOperationBindings bindings =
                org.praxisplatform.uischema.bulk.BulkResourceOperationBindings.empty();
        final ObjectNode main = document(OP.path());
        final ObjectNode other = document("/api/items/{code}");
        final Map<RequestMappingInfo, HandlerMethod> mappings;
        final OpenApiCanonicalOperationResolver resolver;

        Fixture(boolean published) throws Exception {
            token = new AtomicReference<>(published ? new Object() : null);
            mappings = Map.of(RequestMappingInfo.paths(OP.path()).methods(RequestMethod.GET).build(),
                    new HandlerMethod(new Controller(), Controller.class.getMethod("read")));
            when(handlers.getHandlerMethods()).thenReturn(mappings);
            when(documents.resolveGroupFromPath(OP.path())).thenReturn("main");
            doReturn(main).when(documents).getDocumentForGroupStrict("main");
            doReturn(other).when(documents).getDocumentForGroupStrict("other");
            when(documents.localPublishedBulkOpenApiPublicationToken()).thenAnswer(call -> token.get());
            when(documents.groupDocumentCacheToken(anyString())).thenAnswer(call -> token.get());
            resolver = spy(new OpenApiCanonicalOperationResolver(documents, handlers, bindings, groups::get));
        }
        CanonicalOpenApiGroupSnapshot snapshot() { return CanonicalOpenApiGroupSnapshot.capture(documents, "main"); }
        void validate(CanonicalOpenApiGroupSnapshot snapshot) {
            assertEquals(List.of(OP), resolver.requireResourceOperations("items", List.of(OP), snapshot));
        }
        private static ObjectNode document(String path) {
            var root = JSON.createObjectNode().put("openapi", "3.0.3");
            root.putObject("paths").putObject(path).putObject("get").put("operationId", OP.operationId());
            return root;
        }
    }
}
