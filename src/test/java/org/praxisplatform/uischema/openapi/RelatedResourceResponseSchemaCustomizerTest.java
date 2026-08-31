package org.praxisplatform.uischema.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.rest.response.RestApiResource;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelatedResourceResponseSchemaCustomizerTest {

    @Test
    void registersConcreteDomainAndFlattenedResourceSchemasForHandlersInTheCurrentGroup() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SampleController controller = new SampleController();
        HandlerMethod relatedHandler = handler(controller, "relatedChildren");
        HandlerMethod outsideHandler = handler(controller, "outsideChildren");
        HandlerMethod unresolvedHandler = handler(controller, "unresolvedChild");
        RequestMappingInfo relatedMapping = RequestMappingInfo.paths("/parents/{id}/children").build();
        RequestMappingInfo outsideMapping = RequestMappingInfo.paths("/outside/{id}/children").build();
        RequestMappingInfo unresolvedMapping = RequestMappingInfo.paths("/parents/{id}/unresolved").build();

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                relatedMapping, relatedHandler,
                outsideMapping, outsideHandler,
                unresolvedMapping, unresolvedHandler
        ));
        when(operationResolver.resolve(relatedHandler, relatedMapping)).thenReturn(
                new CanonicalOperationRef("parents", "relatedChildren", "/parents/{id}/children", "get")
        );
        when(operationResolver.resolve(outsideHandler, outsideMapping)).thenReturn(
                new CanonicalOperationRef("outside", "outsideChildren", "/outside/{id}/children", "get")
        );

        OpenAPI openApi = new OpenAPI()
                .components(new Components().schemas(new java.util.LinkedHashMap<>(Map.of(
                        "RestApiLinks", new Schema<>().type("object")
                ))))
                .paths(new Paths().addPathItem(
                        "/parents/{id}/children",
                        new PathItem().get(new Operation())
                ));

        new RelatedResourceResponseSchemaCustomizer(handlerMapping, operationResolver).customise(openApi);

        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        assertTrue(schemas.containsKey("ChildDto"));
        assertFalse(schemas.containsKey("OutsideDto"));
        Schema<?> resourceSchema = schemas.get("RestApiResourceChildDto");
        assertNotNull(resourceSchema);
        assertEquals(2, resourceSchema.getAllOf().size());
        assertEquals("#/components/schemas/ChildDto", resourceSchema.getAllOf().getFirst().get$ref());
        assertTrue(resourceSchema.getAllOf().get(1).getProperties().containsKey("_links"));
        assertFalse(resourceSchema.getAllOf().get(1).getProperties().containsKey("content"));
    }

    @Test
    void doesNothingWhenTheOpenApiDocumentHasNoPaths() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of());
        OpenAPI openApi = new OpenAPI();

        new RelatedResourceResponseSchemaCustomizer(
                handlerMapping,
                mock(CanonicalOperationResolver.class)
        ).customise(openApi);

        assertTrue(openApi.getComponents() == null || openApi.getComponents().getSchemas() == null);
    }

    private HandlerMethod handler(Object controller, String methodName) throws NoSuchMethodException {
        Method method = java.util.Arrays.stream(controller.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return new HandlerMethod(controller, method);
    }

    static class ChildDto {
        public Long id;
        public String name;
    }

    static class OutsideDto {
        public Long id;
    }

    static class SampleController {
        ResponseEntity<RestApiResponse<List<RestApiResource<ChildDto>>>> relatedChildren() {
            return null;
        }

        ResponseEntity<RestApiResponse<List<RestApiResource<OutsideDto>>>> outsideChildren() {
            return null;
        }

        <T> RestApiResource<T> unresolvedChild() {
            return null;
        }
    }
}
