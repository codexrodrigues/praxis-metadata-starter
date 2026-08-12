package org.praxisplatform.uischema.formeffect;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.FormDetermination;
import org.praxisplatform.uischema.annotation.FormEffect;
import org.praxisplatform.uischema.annotation.FormEffectInput;
import org.praxisplatform.uischema.annotation.FormEffectOutput;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormEffectOpenApiCustomizerTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishesCanonicalDeterminationAndBindingsOnSourceOperation() throws Exception {
        Fixture fixture = fixture("create", CreateController.class, "create", "determineAddress");

        fixture.customizer().customise(fixture.openApi());

        Map<String, Object> xUi = (Map<String, Object>) fixture.openApi().getPaths()
                .get("/addresses").getPost().getExtensions().get("x-ui");
        java.util.List<Map<String, Object>> effects =
                (java.util.List<Map<String, Object>>) xUi.get("formEffects");
        Map<String, Object> effect = effects.getFirst();
        Map<String, Object> trigger = (Map<String, Object>) effect.get("trigger");
        Map<String, Object> operation = (Map<String, Object>) effect.get("operation");
        java.util.List<Map<String, Object>> outputs =
                (java.util.List<Map<String, Object>>) effect.get("outputs");

        assertEquals("address-from-postal-code", effect.get("id"));
        assertEquals(java.util.List.of("postalCode"), trigger.get("fields"));
        assertEquals("determineAddress", operation.get("operationId"));
        assertEquals("/addresses/determinations/postal-address", operation.get("path"));
        assertEquals("POST", operation.get("method"));
        assertTrue(operation.get("requestSchemaUrl").toString().contains("schemaType=request"));
        assertTrue(operation.get("responseSchemaUrl").toString().contains("schemaType=response"));
        assertEquals("city", outputs.getFirst().get("formField"));
        assertEquals("if-pristine", outputs.getFirst().get("writePolicy"));
    }

    @Test
    void rejectsEffectThatReferencesAnUnknownResponseField() throws Exception {
        Fixture fixture = fixture("invalid", InvalidController.class, "create", "determineAddress");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> fixture.customizer().customise(fixture.openApi()));

        assertTrue(error.getMessage().contains("unknownResponseField"));
    }

    @Test
    void rejectsEffectChainsWithinTheSameSourceOperation() throws Exception {
        Fixture fixture = fixture("chain", ChainedController.class, "create", "determineAddress");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> fixture.customizer().customise(fixture.openApi()));

        assertTrue(error.getMessage().contains("chaining is not supported"));
        assertTrue(error.getMessage().contains("city"));
    }

    @Test
    void ignoresAnnotatedHandlersOutsideTheCurrentOpenApiGroup() throws Exception {
        Fixture fixture = fixture("create", CreateController.class, "create", "determineAddress");
        fixture.openApi().setPaths(new Paths().addPathItem(
                "/unrelated",
                new PathItem().get(new io.swagger.v3.oas.models.Operation().operationId("unrelated"))));

        fixture.customizer().customise(fixture.openApi());

        assertEquals(1, fixture.openApi().getPaths().size());
        assertEquals("unrelated", fixture.openApi().getPaths().get("/unrelated").getGet().getOperationId());
    }

    private Fixture fixture(
            String sourceOperationId,
            Class<?> sourceControllerType,
            String sourceMethodName,
            String determinationOperationId
    ) throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);

        Method sourceMethod = sourceControllerType.getDeclaredMethod(sourceMethodName);
        Method determinationMethod = DeterminationController.class.getDeclaredMethod("determine");
        HandlerMethod sourceHandler = new HandlerMethod(sourceControllerType.getDeclaredConstructor().newInstance(), sourceMethod);
        HandlerMethod determinationHandler = new HandlerMethod(new DeterminationController(), determinationMethod);
        RequestMappingInfo sourceMapping = RequestMappingInfo.paths("/addresses").build();
        RequestMappingInfo determinationMapping = RequestMappingInfo.paths("/addresses/determinations/postal-address").build();

        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(sourceMapping, sourceHandler);
        handlers.put(determinationMapping, determinationHandler);
        when(handlerMapping.getHandlerMethods()).thenReturn(handlers);
        when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, HandlerMethod.class).getMethod().equals(determinationMethod)
                        ? new CanonicalOperationRef("addresses", determinationOperationId,
                        "/addresses/determinations/postal-address", "post")
                        : new CanonicalOperationRef("addresses", sourceOperationId, "/addresses", "post"));

        OpenAPI openApi = openApi(sourceOperationId, determinationOperationId);
        FormEffectOpenApiCustomizer customizer = new FormEffectOpenApiCustomizer(
                handlerMapping,
                operationResolver,
                new FilteredSchemaReferenceResolver());
        return new Fixture(customizer, openApi);
    }

    private OpenAPI openApi(String sourceOperationId, String determinationOperationId) {
        Schema<?> createSchema = new ObjectSchema()
                .addProperty("postalCode", new Schema<>().type("string"))
                .addProperty("city", new Schema<>().type("string"));
        Schema<?> determinationRequest = new ObjectSchema()
                .addProperty("postalCode", new Schema<>().type("string"));
        Schema<?> determinationData = new ObjectSchema()
                .addProperty("city", new Schema<>().type("string"));
        Schema<?> determinationResponse = new ObjectSchema()
                .addProperty("data", new Schema<>().$ref("#/components/schemas/AddressDeterminationData"));

        Components components = new Components()
                .addSchemas("CreateAddress", createSchema)
                .addSchemas("AddressDeterminationRequest", determinationRequest)
                .addSchemas("AddressDeterminationData", determinationData)
                .addSchemas("AddressDeterminationResponse", determinationResponse);

        io.swagger.v3.oas.models.Operation source = operation(
                sourceOperationId, "CreateAddress", "CreateAddress");
        source.setExtensions(new LinkedHashMap<>(Map.of(
                "x-ui", new LinkedHashMap<>(Map.of("responseSchema", "CreateAddress")))));
        io.swagger.v3.oas.models.Operation determination = operation(
                determinationOperationId,
                "AddressDeterminationRequest",
                "AddressDeterminationResponse");

        return new OpenAPI()
                .components(components)
                .paths(new Paths()
                        .addPathItem("/addresses", new PathItem().post(source))
                        .addPathItem("/addresses/determinations/postal-address", new PathItem().post(determination)));
    }

    private io.swagger.v3.oas.models.Operation operation(
            String operationId,
            String requestSchema,
            String responseSchema
    ) {
        return new io.swagger.v3.oas.models.Operation()
                .operationId(operationId)
                .requestBody(new io.swagger.v3.oas.models.parameters.RequestBody().content(
                        new Content().addMediaType("application/json", new MediaType().schema(
                                new Schema<>().$ref("#/components/schemas/" + requestSchema)))))
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().content(
                        new Content().addMediaType("application/json", new MediaType().schema(
                                new Schema<>().$ref("#/components/schemas/" + responseSchema))))));
    }

    static class CreateController {
        @PostMapping("/addresses")
        @FormEffect(
                id = "address-from-postal-code",
                triggerFields = "postalCode",
                operationId = "determineAddress",
                inputs = @FormEffectInput(formField = "postalCode", operationField = "postalCode"),
                outputs = @FormEffectOutput(operationField = "city", formField = "city")
        )
        public void create() {
        }
    }

    static class InvalidController {
        @PostMapping("/addresses")
        @FormEffect(
                id = "invalid-effect",
                triggerFields = "postalCode",
                operationId = "determineAddress",
                inputs = @FormEffectInput(formField = "postalCode", operationField = "postalCode"),
                outputs = @FormEffectOutput(operationField = "unknownResponseField", formField = "city")
        )
        public void create() {
        }
    }

    static class ChainedController {
        @PostMapping("/addresses")
        @FormEffect(
                id = "self-chain",
                triggerFields = "city",
                operationId = "determineAddress",
                inputs = @FormEffectInput(formField = "city", operationField = "postalCode"),
                outputs = @FormEffectOutput(operationField = "city", formField = "city")
        )
        public void create() {
        }
    }

    static class DeterminationController {
        @PostMapping("/addresses/determinations/postal-address")
        @Operation(operationId = "determineAddress")
        @FormDetermination
        public void determine() {
        }
    }

    private record Fixture(FormEffectOpenApiCustomizer customizer, OpenAPI openApi) {
    }
}
