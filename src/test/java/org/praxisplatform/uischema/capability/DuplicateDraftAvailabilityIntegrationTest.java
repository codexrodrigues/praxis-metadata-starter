package org.praxisplatform.uischema.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.uischema.action.*;
import org.praxisplatform.uischema.annotation.*;
import org.praxisplatform.uischema.controller.base.AbstractDuplicateDraftResourceController;
import org.praxisplatform.uischema.controller.docs.ActionCatalogController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.openapi.*;
import org.praxisplatform.uischema.schema.*;
import org.praxisplatform.uischema.service.base.DuplicateDraftResourceService;
import org.praxisplatform.uischema.surface.*;
import org.praxisplatform.uischema.validation.AnnotationConflictMode;
import org.springframework.context.ApplicationContext;
import org.springframework.hateoas.Link;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real inherited action/registry, canonical resolver/catalog/controller composition; no database. */
class DuplicateDraftAvailabilityIntegrationTest {
    static final String KEY = "test.drafts";
    static final String PATH = "/drafts";

    @ParameterizedTest
    @ValueSource(strings = {"missing-service", "missing-key", "null-create", "null-view", "null-duplicate"})
    void missingControllerCompositionCannotPrepareOrAdvertiseDraft(String missing) throws Exception {
        var f = new Fixture(true, true);
        var facade = mock(CapabilityService.class);
        when(facade.collectionOperationAvailability(KEY, PATH, "create")).thenReturn(AvailabilityDecision.allowAll());
        when(facade.itemOperationAvailability(KEY, PATH, "view", 7L)).thenReturn(AvailabilityDecision.allowAll());
        when(facade.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L)).thenReturn(AvailabilityDecision.allowAll());
        switch (missing) {
            case "missing-service" -> ReflectionTestUtils.setField(f.controller, "capabilityService", null);
            case "missing-key" -> f.controller.resourceKey = null;
            case "null-create" -> {
                when(facade.collectionOperationAvailability(KEY, PATH, "create")).thenReturn(null);
                ReflectionTestUtils.setField(f.controller, "capabilityService", facade);
            }
            case "null-view" -> {
                when(facade.itemOperationAvailability(KEY, PATH, "view", 7L)).thenReturn(null);
                ReflectionTestUtils.setField(f.controller, "capabilityService", facade);
            }
            case "null-duplicate" -> {
                when(facade.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L)).thenReturn(null);
                ReflectionTestUtils.setField(f.controller, "capabilityService", facade);
            }
        }
        var denied = assertThrows(ResponseStatusException.class, () -> f.controller.duplicateDraft(7L));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("duplicate-draft-unavailable", denied.getReason());
        assertFalse(f.controller.itemRels(7L).contains("duplicate-draft"));
        assertFalse(f.controller.entityRels(7L).contains("duplicate-draft"));
        f.mvc.perform(post(PATH + "/7/duplicate-draft")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(f.service, never()).duplicateDraft(any());
        verify(f.service, never()).create(any());
        when(f.service.supportsDuplicateDraft()).thenReturn(false);
        f.mvc.perform(post(PATH + "/7/duplicate-draft")).andExpect(status().isNotFound());
        verify(f.service, never()).duplicateDraft(any());
    }

    @Test
    void authorizedPreparationAndRealActionAgreeWithoutCrudWrites() throws Exception {
        var f = new Fixture(true, true);
        var original = f.document.deepCopy();
        assertEquals("duplicate-draft", f.registry.findByResourceKey(KEY).getFirst().id());
        assertTrue(f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L).allowed());
        assertTrue(f.actions.findItemActions(KEY, 7L).actions().getFirst().availability().allowed());
        var snapshot = f.capabilities.itemCapabilities(KEY, PATH, 7L);
        assertTrue(snapshot.operations().get("duplicate-draft").availability().allowed());
        assertTrue(snapshot.actions().getFirst().availability().allowed());
        assertTrue(f.controller.itemRels(7L).contains("duplicate-draft"));
        assertTrue(f.controller.entityRels(7L).contains("duplicate-draft"));
        f.mvc.perform(post(PATH + "/7/duplicate-draft"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("editable"))
                .andExpect(jsonPath("$.data.id").doesNotExist());
        verify(f.service).duplicateDraft(7L);
        verify(f.service, never()).create(any());
        verify(f.service, never()).update(any(), any());
        verify(f.service, never()).deleteById(any());
        assertEquals(original, f.document, "OpenAPI is independent of the current principal");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledSupportOrMissingMappingNeverAdvertisesExecutableOperation(boolean support) throws Exception {
        var f = new Fixture(support, !support);
        var snapshot = f.capabilities.itemCapabilities(KEY, PATH, 7L);
        assertFalse(snapshot.canonicalOperations().get("duplicate-draft"));
        assertFalse(snapshot.operations().get("duplicate-draft").supported());
        assertFalse(snapshot.operations().get("duplicate-draft").availability().allowed());
        assertFalse(snapshot.actions().getFirst().availability().allowed());
        assertFalse(f.actions.findItemActions(KEY, 7L).actions().getFirst().availability().allowed());
        assertFalse(f.controller.itemRels(7L).contains("duplicate-draft"));
        assertFalse(f.controller.entityRels(7L).contains("duplicate-draft"));
        f.mvc.perform(post(PATH + "/7/duplicate-draft"))
                .andExpect(status().is(support ? 403 : 404));
        verify(f.service, never()).duplicateDraft(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "view", "duplicate-draft"})
    void deniedPrerequisiteBlocksCatalogLinksSnapshotAndPreparation(String denied) throws Exception {
        var f = new Fixture(true, true);
        f.denied = denied;
        var before = f.document.deepCopy();
        assertDeniedEverywhere(f);
        if ("create".equals(denied)) {
            assertTrue(f.capabilities.itemOperationAvailability(KEY, PATH, "view", 7L).allowed());
            f.contexts.clear(); f.stateReads.set(0);
            assertFalse(f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L).allowed());
            assertEquals(0, f.stateReads.get(), "create denial must precede loading the source");
            assertEquals(List.of("create"), f.contexts.stream().map(ResourceOperationAvailabilityContext::operationId).toList());
        }
        assertEquals(before, f.document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "view", "duplicate-draft"})
    void providerExceptionNeverPermitsPreparationOrLeaksPrivateDetails(String failed) throws Exception {
        var f = new Fixture(true, true);
        f.failed = failed;
        assertDeniedEverywhere(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "view"})
    void surfaceDenialsCannotBeBypassedThroughTheActionOrDirectGuard(String denied) throws Exception {
        var f = new Fixture(true, true); f.deniedSurface = denied;
        assertDeniedEverywhere(f);
        assertFalse(("create".equals(denied)
                ? f.capabilities.collectionOperationAvailability(KEY, PATH, "create")
                : f.capabilities.itemOperationAvailability(KEY, PATH, "view", 7L)).allowed());
    }

    @Test
    void methodBasedHostPolicyDeniesCreateInGuardsAsItDoesInSnapshots() throws Exception {
        var f = new Fixture(true, true); f.denyPost = true;
        assertTrue(f.capabilities.itemOperationAvailability(KEY, PATH, "view", 7L).allowed());
        assertDeniedEverywhere(f);
    }

    @Test
    void failureDoesNotLeaveStateOrDecisionsInTheNextRequest() throws Exception {
        var f = new Fixture(true, true); f.failed = "view";
        assertFalse(f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L).allowed());
        f.failed = null; f.stateReads.set(0);
        assertTrue(f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L).allowed());
        assertEquals(1, f.stateReads.get());
    }

    @Test
    void unknownOperationRetainsCallerScopeWithoutInventingStructuralSupport() throws Exception {
        var f = new Fixture(true, true);
        assertTrue(f.capabilities.itemOperationAvailability(KEY, PATH, "custom-policy", null).allowed());
        var item = f.contexts.getLast();
        assertEquals("ITEM", item.scope());
        assertNull(item.resourceId());
        assertEquals(Map.of(), item.metadata());
        assertTrue(f.capabilities.collectionOperationAvailability(KEY, PATH, "custom-policy").allowed());
        assertEquals("COLLECTION", f.contexts.getLast().scope());
        assertEquals(Map.of(), f.contexts.getLast().metadata());
    }

    @Test
    void nullableMetadataFromExistingProvidersDoesNotChangeTheirAllowedDecision() throws Exception {
        var f = new Fixture(true, true); f.nullMetadata = true;
        assertTrue(f.capabilities.collectionOperationAvailability(KEY, PATH, "create").allowed());
        assertTrue(f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L).allowed());
        assertTrue(f.capabilities.itemCapabilities(KEY, PATH, 7L)
                .operations().get("duplicate-draft").availability().allowed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "view", "duplicate-draft"})
    void nullPrerequisitePolicyFailsClosed(String operation) throws Exception {
        var f = new Fixture(true, true); f.nullOperation = operation;
        assertDeniedEverywhere(f);
    }

    @Test
    void intentionallyConfiguredNoOpPolicyIsDifferentFromMissingCapabilityService() throws Exception {
        var f = new Fixture(true, true, true);
        assertTrue(f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L).allowed());
        assertTrue(f.controller.itemRels(7L).contains("duplicate-draft"));
        f.mvc.perform(post(PATH + "/7/duplicate-draft")).andExpect(status().isOk());
        verify(f.service).duplicateDraft(7L);
    }

    @Test
    void declaredActionRulesAlsoDenyGuardsAndOperations() throws Exception {
        var f = new Fixture(true, true); f.actionDenied = true;
        assertDeniedEverywhere(f);
    }

    @Test
    void manuallyConstructedCatalogWithoutOperationCompositionFailsClosed() throws Exception {
        var f = new Fixture(true, true);
        var unconfigured = new ActionCatalogService(f.registry, new DefaultActionAvailabilityEvaluator(),
                new DefaultActionAvailabilityContextResolver());
        assertFalse(unconfigured.findItemActions(KEY, 7L).actions().getFirst().availability().allowed());
        assertFalse(unconfigured.evaluateDeclaredDuplicateDraftAvailability(KEY, PATH, 7L).allowed());
    }

    @Test
    void objectAndScopeAuthorizationRemainServiceResponsibilities() throws Exception {
        var f = new Fixture(true, true);
        // The discovery provider knows only coarse policy; the service must reject all inaccessible sources.
        for (long id : List.of(8L, 9L, 10L)) {
            doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Source unavailable"))
                    .when(f.service).duplicateDraft(id);
            f.mvc.perform(post(PATH + "/" + id + "/duplicate-draft"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
        }
        verify(f.service, never()).create(any());
        verify(f.service, never()).update(any(), any());
    }

    @Test
    void metadataAndScopesAreIdenticalInGuardsSnapshotsAndRealActions() throws Exception {
        var f = new Fixture(true, true);
        f.capabilities.collectionOperationAvailability(KEY, PATH, "create");
        f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L);
        f.capabilities.itemCapabilities(KEY, PATH, 7L);
        f.actions.findItemActions(KEY, 7L);
        for (var context : f.contexts) {
            if (!Set.of("create", "view", "duplicate-draft").contains(context.operationId())) continue;
            boolean create = context.operationId().equals("create");
            assertEquals(create ? "COLLECTION" : "ITEM", context.scope());
            assertEquals(create ? null : 7L, context.resourceId());
            assertEquals(create ? null : ResourceStateSnapshot.of("ACTIVE"), context.resourceState());
            assertEquals(Map.of("preferredMethod", context.operationId().equals("view") ? "GET" : "POST",
                    "preferredRel", context.operationId().equals("view") ? "self" : context.operationId(),
                    "supported", true), context.metadata());
        }
        for (Runnable entry : List.<Runnable>of(
                () -> f.capabilities.itemCapabilities(KEY, PATH, 7L),
                () -> f.actions.findItemActions(KEY, 7L),
                () -> f.controller.itemRels(7L), () -> f.controller.entityRels(7L),
                () -> f.controller.duplicateDraft(7L))) {
            f.stateReads.set(0); entry.run();
            assertEquals(1, f.stateReads.get(), "one state read per response, including action and operation policies");
        }
        f.stateReads.set(0);
        f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L);
        f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L);
        assertEquals(2, f.stateReads.get(), "no state or authorization cache across requests");
    }

    @Test
    void documentCatalogKeepsUsefulDefinitionButRequiresAnItemAndNeverAnnouncesDeniedSupport() throws Exception {
        var f = new Fixture(true, true);
        f.mvc.perform(get("/schemas/actions").param("resource", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].id").value("duplicate-draft"))
                .andExpect(jsonPath("$.actions[0].availability.allowed").value(false))
                .andExpect(jsonPath("$.actions[0].availability.reason").value("resource-context-required"))
                .andExpect(jsonPath("$.actions[0].path").value(PATH + "/{id}/duplicate-draft"));
        assertFalse(f.actions.findByGroup("test").actions().getFirst().availability().allowed());
        when(f.service.supportsDuplicateDraft()).thenReturn(false);
        f.mvc.perform(get("/schemas/actions").param("resource", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].availability.reason").value("operation-not-supported"));
        verify(f.service, never()).duplicateDraft(any());
    }

    private static void assertDeniedEverywhere(Fixture f) throws Exception {
        var decision = f.capabilities.itemOperationAvailability(KEY, PATH, "duplicate-draft", 7L);
        assertFalse(decision.allowed()); assertEquals("duplicate-draft-unavailable", decision.reason());
        assertEquals(Map.of(), decision.metadata());
        var snapshot = f.capabilities.itemCapabilities(KEY, PATH, 7L);
        assertTrue(snapshot.canonicalOperations().get("duplicate-draft"));
        assertFalse(snapshot.operations().get("duplicate-draft").availability().allowed());
        assertFalse(snapshot.actions().getFirst().availability().allowed());
        assertFalse(f.actions.findItemActions(KEY, 7L).actions().getFirst().availability().allowed());
        assertFalse(f.controller.itemRels(7L).contains("duplicate-draft"));
        assertFalse(f.controller.entityRels(7L).contains("duplicate-draft"));
        f.mvc.perform(post(PATH + "/7/duplicate-draft")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist());
        f.mvc.perform(get(PATH + "/7/actions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].availability.allowed").value(false));
        f.mvc.perform(get(PATH + "/7/capabilities")).andExpect(status().isOk())
                .andExpect(jsonPath("$.operations['duplicate-draft'].availability.allowed").value(false))
                .andExpect(jsonPath("$.actions[0].availability.allowed").value(false));
        verify(f.service, never()).duplicateDraft(any());
        verify(f.service, never()).create(any());
    }

    static class Fixture {
        final Service service = mock(Service.class);
        final Controller controller = new Controller(service);
        final List<ResourceOperationAvailabilityContext> contexts = new ArrayList<>();
        final AtomicInteger stateReads = new AtomicInteger();
        String denied, failed, deniedSurface, nullOperation;
        boolean actionDenied, denyPost, nullMetadata;
        final com.fasterxml.jackson.databind.JsonNode document;
        final ActionDefinitionRegistry registry;
        final ActionCatalogService actions;
        final DefaultCapabilityService capabilities;
        final MockMvc mvc;
        SurfaceCatalogItem surface(String id) {
            boolean create = "create".equals(id);
            return new SurfaceCatalogItem(id, KEY, create ? SurfaceKind.FORM : SurfaceKind.VIEW,
                    create ? SurfaceScope.COLLECTION : SurfaceScope.ITEM, id, "Fixture", null,
                    id, create ? PATH : PATH + "/{id}", create ? "POST" : "GET", null, null,
                    SurfaceResponseCardinality.OBJECT,
                    id.equals(deniedSurface) ? AvailabilityDecision.deny("surface-denied", Map.of())
                            : AvailabilityDecision.allowAll(), 0, List.of());
        }
        Fixture(boolean support, boolean mapping) throws Exception {
            this(support, mapping, false);
        }
        Fixture(boolean support, boolean mapping, boolean noOpPolicy) throws Exception {
            when(service.supportsDuplicateDraft()).thenReturn(support);
            when(service.duplicateDraft(7L)).thenReturn(new Draft("editable"));
            document = new ObjectMapper().readTree("{\"paths\":{\"/drafts\":{\"post\":{}},"
                    + "\"/drafts/{id}\":{\"get\":{},\"put\":{},\"delete\":{}}"
                    + (mapping ? ",\"/drafts/{id}/duplicate-draft\":{\"post\":{}}" : "") + "}}");
            var documents = mock(OpenApiDocumentService.class, CALLS_REAL_METHODS);
            when(documents.resolveGroupFromPath(PATH)).thenReturn("test");
            when(documents.getDocumentForGroup("test")).thenReturn(document);
            ResourceStructuralCapabilityResolver structural = path -> new ResourceStructuralCapabilities(
                    false, false, false, false, false, false, false, service.supportsDuplicateDraft(), null, null);
            var canonical = new OpenApiCanonicalCapabilityResolver(documents, structural);
            ResourceStateSnapshotProvider states = (key, id) -> {
                stateReads.incrementAndGet(); return Optional.of(ResourceStateSnapshot.of("ACTIVE"));
            };
            ResourceOperationAvailabilityProvider provider = noOpPolicy ? new NoOpResourceOperationAvailabilityProvider() : context -> {
                contexts.add(context);
                if (context.operationId().equals(failed)) throw new IllegalStateException("private-tenant-object-details");
                if (denyPost && "POST".equals(context.metadata().get("preferredMethod")))
                    return AvailabilityDecision.deny("authenticated-mutations", Map.of());
                if (context.operationId().equals(denied)) return AvailabilityDecision.deny("policy-denied", Map.of());
                if (context.operationId().equals(nullOperation)) return null;
                return nullMetadata ? new AvailabilityDecision(true, null, null) : AvailabilityDecision.allowAll();
            };
            // Use the actual MVC registrations and inherited annotation; schema/document transport is a fixture.
            var initialMvc = standaloneSetup(controller).build();
            var handlerMapping = initialMvc.getDispatcherServlet().getWebApplicationContext()
                    .getBean(RequestMappingHandlerMapping.class);
            var operationResolver = mock(CanonicalOperationResolver.class);
            when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                    .thenAnswer(inv -> {
                        RequestMappingInfo info = inv.getArgument(1);
                        return new CanonicalOperationRef("test", "duplicateDraft", info.getPatternValues().iterator().next(), "POST");
                    });
            var schemas = mock(SchemaReferenceResolver.class);
            registry = new AnnotationDrivenActionDefinitionRegistry(handlerMapping, mock(ApplicationContext.class),
                    operationResolver, schemas, AnnotationConflictMode.FAIL, AnnotationConflictMode.FAIL);
            var defaultRules = new DefaultActionAvailabilityEvaluator();
            ActionAvailabilityEvaluator rules = (definition, context) -> actionDenied
                    ? AvailabilityDecision.deny("private-action-details", Map.of("private", "secret"))
                    : defaultRules.evaluate(definition, context);
            var surfaces = new SurfaceCatalogService(null, null, null) {
                @Override public SurfaceCatalogResponse findByResourceKey(String key) {
                    if (deniedSurface == null) throw SurfaceCatalogNotFoundException.unknownResourceKey(key);
                    return new SurfaceCatalogResponse(KEY, PATH, "test", null, List.of(surface("create")));
                }
                @Override public SurfaceCatalogResponse findItemSurfaces(String key, Object id) {
                    if (deniedSurface == null) throw SurfaceCatalogNotFoundException.unknownResourceKey(key);
                    return new SurfaceCatalogResponse(KEY, PATH, "test", id, List.of(surface("view")));
                }
            };
            actions = new ActionCatalogService(registry, rules, new DefaultActionAvailabilityContextResolver(states),
                    (definitions, consumer) -> consumer.apply(Map.of()),
                    new ResourceOperationAvailabilityEvaluator(canonical, documents, provider, states, surfaces));
            capabilities = new DefaultCapabilityService(canonical, surfaces, actions, documents, provider, states, structural);
            ReflectionTestUtils.setField(controller, "capabilityService", capabilities);
            ReflectionTestUtils.setField(controller, "actionCatalogService", actions);
            mvc = standaloneSetup(controller, new ActionCatalogController(actions)).build();
        }
    }
    interface Service extends DuplicateDraftResourceService<Response, Long, Filter, Draft, Draft, Draft> {}
    record Response(Long id) {}
    record Draft(String name) {}
    static class Filter implements GenericFilterDTO {}
    @ApiResource(value = PATH, resourceKey = KEY)
    @ApiGroup("test")
    static class Controller extends AbstractDuplicateDraftResourceController<Response, Long, Filter, Draft, Draft, Draft> {
        final Service service;
        String resourceKey = KEY;
        @Override protected String getResourceKeyOrNull() { return resourceKey; }
        Controller(Service service) { this.service = service; }
        @Override protected Service getService() { return service; }
        @Override protected Long getResponseId(Response dto) { return dto.id(); }
        @Override protected String getIdFieldName() { return "id"; }
        @Override protected String getBasePath() { return PATH; }
        List<String> itemRels(Long id) { return buildItemActionLinks(id).stream().map(Link::getRel).map(Object::toString).toList(); }
        List<String> entityRels(Long id) { return buildEntityActionLinks(id).stream().map(Link::getRel).map(Object::toString).toList(); }
    }
}
