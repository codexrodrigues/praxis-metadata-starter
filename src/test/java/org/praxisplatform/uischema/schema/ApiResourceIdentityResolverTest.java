package org.praxisplatform.uischema.schema;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
}
