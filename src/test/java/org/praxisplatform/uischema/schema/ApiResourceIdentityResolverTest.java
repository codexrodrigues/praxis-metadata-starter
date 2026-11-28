package org.praxisplatform.uischema.schema;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.customfields.CustomFieldSupport;
import org.praxisplatform.uischema.customfields.CustomFields;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiResourceIdentityResolverTest {

    @Test
    void resolvesStructuredIdentityFromCanonicalApiResource() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        Method method = FixtureController.class.getDeclaredMethod("list");
        HandlerMethod handlerMethod = new HandlerMethod(new FixtureController(), method);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(RequestMappingInfo.paths("/fixture").build(), handlerMethod));

        ApiResourceIdentityResolver resolver = new ApiResourceIdentityResolver(handlerMapping);

        Map<String, Object> identity = resolver.resolve("/api/records/").orElseThrow();
        assertEquals("code", identity.get("keyField"));
        assertEquals("name", identity.get("titleField"));
        assertEquals("codename", identity.get("subtitleField"));
        assertEquals("avatarUrl", identity.get("avatarField"));
        assertEquals(java.util.List.of("type", "mnemonic"), identity.get("metadataFields"));
        assertEquals("displayLabel", identity.get("displayLabelField"));

        Map<String, Object> resourceMeta = resolver.resolveResourceMetadata("/api/records/").orElseThrow();
        assertEquals("test.records", resourceMeta.get("resourceKey"));
        assertEquals("Records", resourceMeta.get("title"));
        assertEquals("badge", resourceMeta.get("icon"));
        assertEquals("human-resources", resourceMeta.get("visualTone"));
    }

    @Test
    void resolvesProjectedChildIdentityAndIconForRelatedUiSurface() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        Method parentHeroMethod = ParentEmployeeController.class.getDeclaredMethod("heroProfile");
        Method childListMethod = ChildHeroProfileController.class.getDeclaredMethod("list");
        Map<RequestMappingInfo, HandlerMethod> mappings = Map.of(
                RequestMappingInfo.paths("/api/employees/{id}/hero-profile").methods(RequestMethod.GET).build(),
                new HandlerMethod(new ParentEmployeeController(), parentHeroMethod),
                RequestMappingInfo.paths("/api/hero-profiles").methods(RequestMethod.GET).build(),
                new HandlerMethod(new ChildHeroProfileController(), childListMethod)
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);

        ApiResourceIdentityResolver resolver = new ApiResourceIdentityResolver(handlerMapping);

        Map<String, Object> identity = resolver.resolve(
                "/api/employees",
                "/api/employees/{id}/hero-profile",
                "get"
        ).orElseThrow();
        assertEquals("funcionarioId", identity.get("keyField"));
        assertEquals("nomeCompleto", identity.get("titleField"));
        assertEquals("codinome", identity.get("subtitleField"));
        assertEquals("fotoPerfilUrl", identity.get("avatarField"));
        assertEquals(java.util.List.of("cargo", "departamento"), identity.get("metadataFields"));

        Map<String, Object> metadata = resolver.resolveResourceMetadata(
                "/api/employees",
                "/api/employees/{id}/hero-profile",
                "get"
        ).orElseThrow();
        assertEquals("shield_person", metadata.get("icon"));
        assertEquals("test.hero-profiles", resolver.resolveResourceKey(
                "/api/employees", "/api/employees/{id}/hero-profile", "get").orElseThrow());
    }

    @Test
    void ignoresResourcesWithoutExplicitIdentity() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        Method method = UnconfiguredController.class.getDeclaredMethod("list");
        HandlerMethod handlerMethod = new HandlerMethod(new UnconfiguredController(), method);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(RequestMappingInfo.paths("/unconfigured").build(), handlerMethod));

        ApiResourceIdentityResolver resolver = new ApiResourceIdentityResolver(handlerMapping);

        assertTrue(resolver.resolve("/api/unconfigured").isEmpty());
    }

    @Test
    void resolvesSemanticKeyWithoutInventingVisualMetadata() throws Exception {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/unconfigured").methods(RequestMethod.GET).build(),
                new HandlerMethod(new UnconfiguredController(), UnconfiguredController.class.getDeclaredMethod("list"))));
        var resolver = new ApiResourceIdentityResolver(mapping);
        assertEquals("test.unconfigured", resolver.resolveResourceKey(
                "/api/unconfigured", "/api/unconfigured", "get").orElseThrow());
        assertEquals("test.unconfigured", resolver.resolveResourceKey(
                "/api/unconfigured/", "/api/unconfigured/missing-operation", "post").orElseThrow());
        assertTrue(resolver.resolveResourceMetadata("/api/unconfigured").isEmpty());
    }

    @Test
    void relatedResourcePathHasSamePrecedenceForSemanticKeyAndExistingIdentity() throws Exception {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/employees/{employeeId}/conflicting-child").methods(RequestMethod.GET).build(),
                new HandlerMethod(new ParentEmployeeController(), ParentEmployeeController.class.getDeclaredMethod("conflictingChild")),
                RequestMappingInfo.paths("/api/hero-profiles").methods(RequestMethod.GET).build(),
                new HandlerMethod(new ChildHeroProfileController(), ChildHeroProfileController.class.getDeclaredMethod("list")),
                RequestMappingInfo.paths("/api/unconfigured").methods(RequestMethod.GET).build(),
                new HandlerMethod(new UnconfiguredController(), UnconfiguredController.class.getDeclaredMethod("list"))));
        var resolver = new ApiResourceIdentityResolver(mapping);
        String operation = "/api/employees/{id}/conflicting-child";
        assertEquals("test.hero-profiles", resolver.resolveResourceKey("/api/employees", operation, "get").orElseThrow());
        assertEquals("test.hero-profiles", resolver.resolveResourceMetadata("/api/employees", operation, "get")
                .orElseThrow().get("resourceKey"));
        assertEquals("funcionarioId", resolver.resolve("/api/employees", operation, "get").orElseThrow().get("keyField"));
    }

    @Test
    void relatedKeyRemainsFallbackWhenChildPathIsNotRegistered() throws Exception {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/employees/{id}/child-by-key").methods(RequestMethod.GET).build(),
                new HandlerMethod(new ParentEmployeeController(), ParentEmployeeController.class.getDeclaredMethod("childByKey")),
                RequestMappingInfo.paths("/api/unconfigured").methods(RequestMethod.GET).build(),
                new HandlerMethod(new UnconfiguredController(), UnconfiguredController.class.getDeclaredMethod("list"))));
        var resolver = new ApiResourceIdentityResolver(mapping);
        assertEquals("test.unconfigured", resolver.resolveResourceKey(
                "/api/employees", "/api/employees/{id}/child-by-key", "get").orElseThrow());
    }

    @Test
    void usesCanonicalMvcHandlerMappingWhenAdditionalMappingsExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
            context.registerBean("controllerEndpointHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
            context.registerBean(ApiResourceIdentityResolver.class);

            context.refresh();

            assertTrue(context.containsBean("apiResourceIdentityResolver"));
        }
    }

    @Test
    void resolvesInheritedItemDtoIdentityAcrossMultipleGenericControllerLayers() throws Exception {
        var resolver = resolverForGenericController(new ContractIdentityController());

        Map<String, Object> identity = resolver.resolve(
                "/api/contracts", "/api/contracts/{id}", "get").orElseThrow();

        assertEquals(Map.of(
                "keyField", "id",
                "titleField", "number",
                "subtitleField", "supplierName",
                "metadataFields", java.util.List.of("status", "currency"),
                "displayLabelField", "number"
        ), identity);
    }

    @Test
    void keepsConcreteCollectionOverrideAlignedWithInheritedItemIdentity() throws Exception {
        var resolver = resolverForGenericController(new ContractIdentityController());

        Map<String, Object> itemIdentity = resolver.resolve(
                "/api/contracts", "/api/contracts/{id}", "get").orElseThrow();
        Map<String, Object> filterIdentity = resolver.resolve(
                "/api/contracts", "/api/contracts/filter", "post").orElseThrow();

        assertEquals("number", filterIdentity.get("titleField"));
        assertEquals(filterIdentity, itemIdentity);
    }

    @Test
    void preservesMethodIdentityPrecedenceOverResolvedDtoAndResourceIdentity() throws Exception {
        var resolver = resolverForGenericController(new ContractIdentityController());

        assertEquals(Map.of("titleField", "methodTitle"), resolver.resolve(
                "/api/contracts", "/api/contracts/{id}/method-identity", "get").orElseThrow());
    }

    @Test
    void preservesProjectionDtoIdentityInsteadOfUsingParentResourceIdentity() throws Exception {
        var resolver = resolverForGenericController(new ContractIdentityController());

        assertEquals(Map.of("keyField", "contractId", "titleField", "summaryTitle"), resolver.resolve(
                "/api/contracts", "/api/contracts/{id}/summary", "get").orElseThrow());
    }

    @Test
    void retainsApiResourceFallbackWhenTheResolvedDtoHasNoIdentity() throws Exception {
        var resolver = resolverForGenericController(new FallbackIdentityController());

        assertEquals(Map.of("keyField", "id", "titleField", "fallbackTitle"), resolver.resolve(
                "/api/contracts", "/api/contracts/{id}", "get").orElseThrow());
    }

    @Test
    void doesNotInferIdentityFromUnannotatedDtoFieldsOrUnresolvedGenericReturn() throws Exception {
        var unannotatedResolver = resolverForGenericController(new NoIdentityController());
        var unresolvedResolver = resolverForGenericController(new UnresolvedIdentityController<>());

        assertTrue(unannotatedResolver.resolve("/api/contracts", "/api/contracts/{id}", "get").isEmpty());
        assertTrue(unresolvedResolver.resolve("/api/contracts", "/api/contracts/{id}", "get").isEmpty());
    }

    private ApiResourceIdentityResolver resolverForGenericController(Object controller) throws Exception {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        Method itemMethod = GenericIdentityController.class.getDeclaredMethod("getById", String.class);
        Method filterMethod = controller instanceof ContractIdentityController
                ? ContractIdentityController.class.getDeclaredMethod("filter")
                : GenericIdentityController.class.getDeclaredMethod("filter");
        when(mapping.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/contracts/{id}").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, itemMethod),
                RequestMappingInfo.paths("/api/contracts/filter").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, filterMethod),
                RequestMappingInfo.paths("/api/contracts/{id}/method-identity").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, GenericIdentityController.class.getDeclaredMethod("methodIdentity")),
                RequestMappingInfo.paths("/api/contracts/{id}/summary").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, GenericIdentityController.class.getDeclaredMethod("summary"))));
        return new ApiResourceIdentityResolver(mapping);
    }

    static class GenericIdentityController<ResponseDTO, ProjectionDTO> {
        public ResponseEntity<RestApiResponse<ResponseDTO>> getById(String id) { return null; }

        public ResponseEntity<RestApiResponse<Page<EntityModel<ResponseDTO>>>> filter() { return null; }

        @ResourceIdentity(titleField = "methodTitle")
        public ResponseEntity<RestApiResponse<ResponseDTO>> methodIdentity() { return null; }

        @UiSurface(id = "summary", title = "Summary", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM)
        public ResponseEntity<RestApiResponse<ProjectionDTO>> summary() { return null; }
    }

    static class IntermediateIdentityController<ResponseDTO, ProjectionDTO>
            extends GenericIdentityController<ResponseDTO, ProjectionDTO> { }

    @ApiResource(value = "/api/contracts", resourceKey = "test.contracts",
            identity = @ResourceIdentity(keyField = "id", titleField = "parentTitle"))
    static class ContractIdentityController extends IntermediateIdentityController<ContractIdentityDto, ContractSummaryDto> {
        @Override
        public ResponseEntity<RestApiResponse<Page<EntityModel<ContractIdentityDto>>>> filter() { return null; }
    }

    @ApiResource(value = "/api/contracts", resourceKey = "test.contracts",
            identity = @ResourceIdentity(keyField = "id", titleField = "fallbackTitle"))
    static class FallbackIdentityController extends IntermediateIdentityController<UnannotatedIdentityDto, UnannotatedIdentityDto> { }

    @ApiResource(value = "/api/contracts", resourceKey = "test.contracts")
    static class NoIdentityController extends IntermediateIdentityController<UnannotatedIdentityDto, UnannotatedIdentityDto> { }

    @ApiResource(value = "/api/contracts", resourceKey = "test.contracts")
    static class UnresolvedIdentityController<T> extends IntermediateIdentityController<T, T> { }

    @ResourceIdentity(keyField = "id", titleField = "number", subtitleField = "supplierName",
            metadataFields = {"status", "currency"}, displayLabelField = "number")
    record ContractIdentityDto(String id, String number, String supplierName, String status, String currency) { }

    @ResourceIdentity(keyField = "contractId", titleField = "summaryTitle")
    record ContractSummaryDto(String contractId, String summaryTitle) { }

    record UnannotatedIdentityDto(String id, String name, String number) { }

    @ApiResource(
            value = "/api/records",
            resourceKey = "test.records",
            title = "Records",
            icon = "badge",
            visualTone = "human-resources",
            identity = @ResourceIdentity(
                    keyField = "code",
                    titleField = "name",
                    subtitleField = "codename",
                    avatarField = "avatarUrl",
                    metadataFields = {"type", "mnemonic"},
                    displayLabelField = "displayLabel"
            )
    )
    static class FixtureController {
        void list() { }
    }

    @ApiResource(
            value = "/api/employees",
            resourceKey = "test.employees",
            icon = "users",
            identity = @ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    metadataFields = {"cargoNome", "departamentoNome"}
            )
    )
    static class ParentEmployeeController {
        @UiSurface(id = "conflicting-child", title = "Child by path", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM,
                relatedChildResourceKey = "test.unconfigured", relatedChildResourcePath = "/api/hero-profiles")
        ResponseEntity<RestApiResponse<Object>> conflictingChild() { return null; }

        @UiSurface(id = "child-by-key", title = "Child by key", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM,
                relatedChildResourceKey = "test.unconfigured", relatedChildResourcePath = "/api/not-registered")
        ResponseEntity<RestApiResponse<Object>> childByKey() { return null; }

        @UiSurface(
                id = "hero-profile",
                kind = SurfaceKind.READ_PROJECTION,
                scope = SurfaceScope.ITEM,
                title = "Perfil 360",
                icon = "shield_person",
                relatedChildResourceKey = "test.hero-profiles",
                relatedChildResourcePath = "/api/hero-profiles"
        )
        ResponseEntity<RestApiResponse<Object>> heroProfile() {
            return null;
        }
    }

    @ApiResource(
            value = "/api/hero-profiles",
            resourceKey = "test.hero-profiles",
            icon = "badge",
            identity = @ResourceIdentity(
                    keyField = "funcionarioId",
                    titleField = "nomeCompleto",
                    subtitleField = "codinome",
                    avatarField = "fotoPerfilUrl",
                    metadataFields = {"cargo", "departamento"}
            )
    )
    static class ChildHeroProfileController {
        void list() { }
    }

    @ApiResource(value = "/api/unconfigured", resourceKey = "test.unconfigured")
    static class UnconfiguredController {
        void list() { }
    }

    @Test
    void resolvesResourcePathWithFallbackToControllerAndResponseDtoIdentity() throws NoSuchMethodException {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        var ctrlIdentityController = new StandaloneControllerIdentityController();
        var dtoIdentityController = new DtoBackedIdentityController();
        HandlerMethod projectionMethod = new HandlerMethod(
                dtoIdentityController,
                DtoBackedIdentityController.class.getDeclaredMethod("summary")
        );
        HandlerMethod canonicalMethod = new HandlerMethod(
                dtoIdentityController,
                DtoBackedIdentityController.class.getDeclaredMethod("detail")
        );
        HandlerMethod ctrlMethod = new HandlerMethod(
                ctrlIdentityController,
                StandaloneControllerIdentityController.class.getDeclaredMethod("list")
        );

        Map<RequestMappingInfo, HandlerMethod> mappings = new java.util.LinkedHashMap<>();
        mappings.put(RequestMappingInfo.paths("/api/dto-identity/summary").build(), projectionMethod);
        mappings.put(RequestMappingInfo.paths("/api/dto-identity/detail").build(), canonicalMethod);
        mappings.put(RequestMappingInfo.paths("/api/ctrl-identity").build(), ctrlMethod);
        when(mapping.getHandlerMethods()).thenReturn(mappings);

        var resolver = new ApiResourceIdentityResolver(mapping);

        Map<String, Object> ctrlIdentity = resolver.resolve("/api/ctrl-identity").orElseThrow();
        assertEquals("codigo", ctrlIdentity.get("keyField"));
        assertEquals("titulo", ctrlIdentity.get("titleField"));

        Map<String, Object> dtoIdentity = resolver.resolve("/api/dto-identity").orElseThrow();
        assertEquals("id", dtoIdentity.get("keyField"));
        assertEquals("nomePrincipal", dtoIdentity.get("titleField"));
        assertEquals("cargoAtual", dtoIdentity.get("subtitleField"));
        assertEquals(java.util.List.of("lotacao", "situacao"), dtoIdentity.get("metadataFields"));
    }

    @ApiResource(value = "/api/ctrl-identity", resourceKey = "test.ctrl-identity")
    @ResourceIdentity(keyField = "codigo", titleField = "titulo")
    static class StandaloneControllerIdentityController {
        void list() { }
    }

    @ResourceIdentity(keyField = "id", titleField = "nomePrincipal", subtitleField = "cargoAtual", metadataFields = {"lotacao", "situacao"})
    record DtoBackedIdentityPayload(Long id, String nomePrincipal, String cargoAtual, String lotacao, String situacao) {
    }

    record DtoProjectionPayload(String titulo) {
    }

    @ApiResource(value = "/api/dto-identity", resourceKey = "test.dto-identity")
    static class DtoBackedIdentityController {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "summary",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.READ_PROJECTION,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.COLLECTION,
                title = "Summary",
                description = "Summary projection."
        )
        org.springframework.http.ResponseEntity<org.praxisplatform.uischema.rest.response.RestApiResponse<DtoProjectionPayload>> summary() {
            return null;
        }

        org.springframework.http.ResponseEntity<org.praxisplatform.uischema.rest.response.RestApiResponse<DtoBackedIdentityPayload>> detail() {
            return null;
        }
    }

    @Test
    void handlesNullCustomFieldsDtoAndResolvesRelatedChildResourcePathAndCustomFieldKeySafely() throws Exception {
        assertTrue(CustomFieldSupport.property(null).isEmpty());

        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        var parentController = new SpiParentController();
        var childWithCustomFieldsController = new ChildAbonoController();
        var childWithoutCustomFieldsController = new ChildHeroProfileController();

        Map<RequestMappingInfo, HandlerMethod> mappings = new java.util.LinkedHashMap<>();
        mappings.put(
                RequestMappingInfo.paths("/api/employees/{id}/abonos-permanencia-vinculo").methods(RequestMethod.GET).build(),
                new HandlerMethod(parentController, SpiParentController.class.getDeclaredMethod("abonos")));
        mappings.put(
                RequestMappingInfo.paths("/api/employees/{id}/child-by-key-only").methods(RequestMethod.GET).build(),
                new HandlerMethod(parentController, SpiParentController.class.getDeclaredMethod("childByKeyOnly")));
        mappings.put(
                RequestMappingInfo.paths("/api/employees/{id}/plain-child").methods(RequestMethod.GET).build(),
                new HandlerMethod(parentController, SpiParentController.class.getDeclaredMethod("plainChild")));
        mappings.put(
                RequestMappingInfo.paths("/api/employees/{id}/missing-child").methods(RequestMethod.GET).build(),
                new HandlerMethod(parentController, SpiParentController.class.getDeclaredMethod("missingChild")));
        mappings.put(
                RequestMappingInfo.paths("/api/abonos-permanencia").methods(RequestMethod.GET).build(),
                new HandlerMethod(childWithCustomFieldsController, ChildAbonoController.class.getDeclaredMethod("list")));
        mappings.put(
                RequestMappingInfo.paths("/api/hero-profiles").methods(RequestMethod.GET).build(),
                new HandlerMethod(childWithoutCustomFieldsController, ChildHeroProfileController.class.getDeclaredMethod("list")));
        when(mapping.getHandlerMethods()).thenReturn(mappings);

        var resolver = new ApiResourceIdentityResolver(mapping);

        assertEquals("/api/abonos-permanencia", resolver.resolveRelatedChildResourcePath(
                "/api/employees/{id}/abonos-permanencia-vinculo", "get").orElseThrow());
        assertEquals("/api/hero-profiles", resolver.resolveRelatedChildResourcePath(
                "/api/employees/{id}/child-by-key-only", "get").orElseThrow());
        assertEquals("/api/unregistered-child", resolver.resolveRelatedChildResourcePath(
                "/api/employees/{id}/missing-child", "get").orElseThrow());

        assertEquals("test.abonos", resolver.resolveDtoCustomFieldResourceKey(
                "/api/employees/{id}/abonos-permanencia-vinculo", "get", "response").orElseThrow());
        assertTrue(resolver.resolveDtoCustomFieldResourceKey(
                "/api/employees/{id}/plain-child", "get", "response").isEmpty());
        assertTrue(resolver.resolveDtoCustomFieldResourceKey(
                "/api/employees/{id}/missing-child", "get", "response").isEmpty());
    }

    @ResourceIdentity(keyField = "id", titleField = "processo")
    static class ChildAbonoDto {
        @CustomFields(resourceKey = "test.abonos")
        private Map<String, Object> customFields;
        public Map<String, Object> getCustomFields() { return customFields; }
    }

    @ApiResource(value = "/api/abonos-permanencia", resourceKey = "test.abonos")
    static class ChildAbonoController {
        ResponseEntity<RestApiResponse<Page<ChildAbonoDto>>> list() { return null; }
    }

    @ApiResource(value = "/api/spi-employees", resourceKey = "test.spi-employees")
    static class SpiParentController {
        @UiSurface(id = "abonos", title = "Abonos", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM,
                relatedChildResourceKey = "test.abonos", relatedChildResourcePath = "/api/abonos-permanencia")
        ResponseEntity<RestApiResponse<Page<?>>> abonos() { return null; }

        @UiSurface(id = "child-by-key", title = "Child Key", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM,
                relatedChildResourceKey = "test.hero-profiles", relatedChildResourcePath = "/api/not-registered")
        ResponseEntity<RestApiResponse<Page<?>>> childByKeyOnly() { return null; }

        @UiSurface(id = "plain-child", title = "Plain Child", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM,
                relatedChildResourceKey = "test.hero-profiles", relatedChildResourcePath = "/api/hero-profiles")
        ResponseEntity<RestApiResponse<Page<?>>> plainChild() { return null; }

        @UiSurface(id = "missing-child", title = "Missing Child", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM,
                relatedChildResourcePath = "/api/unregistered-child")
        ResponseEntity<RestApiResponse<Page<?>>> missingChild() { return null; }
    }
}
