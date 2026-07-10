package org.praxisplatform.uischema.schema;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
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
        assertEquals(java.util.List.of("type", "mnemonic"), identity.get("metadataFields"));
        assertEquals("displayLabel", identity.get("displayLabelField"));
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

    @ApiResource(
            value = "/api/records",
            resourceKey = "test.records",
            identity = @ResourceIdentity(
                    keyField = "code",
                    titleField = "name",
                    metadataFields = {"type", "mnemonic"},
                    displayLabelField = "displayLabel"
            )
    )
    static class FixtureController {
        void list() { }
    }

    @ApiResource(value = "/api/unconfigured", resourceKey = "test.unconfigured")
    static class UnconfiguredController {
        void list() { }
    }
}
