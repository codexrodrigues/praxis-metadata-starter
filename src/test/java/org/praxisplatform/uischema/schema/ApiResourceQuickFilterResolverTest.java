package org.praxisplatform.uischema.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.QuickFilter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiResourceQuickFilterResolverTest {

    @Test
    @SuppressWarnings("unchecked")
    void resolvesQuickFiltersFromCanonicalApiResource() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        Method method = FixtureController.class.getDeclaredMethod("list");
        HandlerMethod handlerMethod = new HandlerMethod(new FixtureController(), method);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(RequestMappingInfo.paths("/fixture").build(), handlerMethod));

        ApiResourceQuickFilterResolver resolver = new ApiResourceQuickFilterResolver(handlerMapping, new ObjectMapper());

        List<Map<String, Object>> filters = resolver.resolve("/api/items/");
        assertEquals(3, filters.size());

        Map<String, Object> f0 = filters.get(0);
        assertEquals("active", f0.get("id"));
        assertEquals("Ativos", f0.get("label"));
        assertEquals("check_circle", f0.get("icon"));
        assertEquals(Map.of("ativo", true), f0.get("filter"));

        Map<String, Object> f1 = filters.get(1);
        assertEquals("priority", f1.get("id"));
        assertEquals("Alta Prioridade", f1.get("label"));
        assertEquals(Map.of("nivel", "ALTO", "urgente", true), f1.get("filter"));

        Map<String, Object> f2 = filters.get(2);
        assertEquals("json", f2.get("id"));
        assertEquals("JSON format", f2.get("label"));
        Map<String, Object> jsonFilter = (Map<String, Object>) f2.get("filter");
        assertEquals("TI", jsonFilter.get("categoria"));
        assertEquals(10, jsonFilter.get("pontos"));
    }

    @Test
    void ignoresResourcesWithoutQuickFilters() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        Method method = UnconfiguredController.class.getDeclaredMethod("list");
        HandlerMethod handlerMethod = new HandlerMethod(new UnconfiguredController(), method);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(RequestMappingInfo.paths("/unconfigured").build(), handlerMethod));

        ApiResourceQuickFilterResolver resolver = new ApiResourceQuickFilterResolver(handlerMapping, new ObjectMapper());

        assertTrue(resolver.resolve("/api/unconfigured").isEmpty());
    }

    @Test
    void handlesBlankOrNullResourcePath() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApiResourceQuickFilterResolver resolver = new ApiResourceQuickFilterResolver(handlerMapping, new ObjectMapper());

        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(resolver.resolve("   ").isEmpty());
    }

    @Test
    void usesCanonicalMvcHandlerMappingWhenAdditionalMappingsExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
            context.registerBean("controllerEndpointHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
            context.registerBean(ApiResourceQuickFilterResolver.class);

            context.refresh();

            assertTrue(context.containsBean("apiResourceQuickFilterResolver"));
        }
    }

    @ApiResource(
            value = "/api/items",
            resourceKey = "test.items",
            quickFilters = {
                    @QuickFilter(id = "active", label = "Ativos", filter = "ativo=true", icon = "check_circle"),
                    @QuickFilter(id = "priority", label = "Alta Prioridade", filter = "nivel=ALTO&urgente=true"),
                    @QuickFilter(id = "json", label = "JSON format", filter = "{\"categoria\":\"TI\",\"pontos\":10}")
            }
    )
    static class FixtureController {
        void list() { }
    }

    @ApiResource(value = "/api/unconfigured", resourceKey = "test.unconfigured")
    static class UnconfiguredController {
        void list() { }
    }
}
