package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.*;
import io.swagger.v3.oas.models.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.configuration.PraxisBulkWebAutoConfiguration;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.filter.web.FilterPayloadNormalizer;
import org.praxisplatform.uischema.openapi.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkDeclarativeProtocolTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void strictConverterUsesRealResourceIdentityAndNormalizesNestedFilters() throws Exception {
        AtomicInteger normalized = new AtomicInteger();
        FilterPayloadNormalizer normalizer = (node, type) -> {
            assertEquals(BulkDeclarativeJpaHttpTest.Filter.class, type); normalized.incrementAndGet(); node.put("name", "normalized"); return true;
        };
        var converter = new PraxisBulkProtocolHttpMessageConverter(mapper, List.of(normalizer));
        var controller = BulkDeclarativeJpaHttpTest.ItemController.class;
        var method = controller.getMethod("evaluateBulkUpdate", BulkUniformEvaluationRequest.class);
        String query = """
                {"executionMode":"SYNC","selection":{"mode":"QUERY","filter":{"name":"raw"}},
                 "changes":[{"field":"name","operator":"SET","value":"next"}]}
                """;
        var request = (BulkUniformEvaluationRequest<?, ?>) converter.read(method.getGenericParameterTypes()[0], controller,
                new MockHttpInputMessage(query.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("normalized", ((BulkDeclarativeJpaHttpTest.Filter) request.selection().filter()).name);
        assertEquals(1, normalized.get());
        for (String token : List.of("1", "\"01\"", "\"9223372036854775808\"")) {
            String body = "{\"executionMode\":\"SYNC\",\"selection\":{\"mode\":\"EXPLICIT\",\"targets\":[{\"id\":" + token
                    + ",\"expectedVersion\":\"v0\"}]},\"changes\":[{\"field\":\"name\",\"operator\":\"CLEAR\"}]}";
            assertThrows(HttpMessageNotReadableException.class, () -> converter.read(method.getGenericParameterTypes()[0], controller,
                    new MockHttpInputMessage(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        String duplicate = query.replace("\"executionMode\":\"SYNC\"", "\"executionMode\":\"SYNC\",\"executionMode\":\"SYNC\"");
        assertThrows(HttpMessageNotReadableException.class, () -> converter.read(method.getGenericParameterTypes()[0], controller,
                new MockHttpInputMessage(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertFalse(converter.canWrite(BulkConfirmationRequest.class, MediaType.APPLICATION_JSON));
    }

    @Test void identityFactoriesPreserveDistinctIntegerLongAndStringWireContracts() {
        assertEquals("integer", BulkResourceTypes.identityCodec(Integer.class).codecId());
        assertEquals("long", BulkResourceTypes.identityCodec(Long.class).codecId());
        assertEquals("string", BulkResourceTypes.identityCodec(String.class).codecId());
        assertThrows(IllegalArgumentException.class, () -> BulkResourceTypes.identityCodec(Double.class));
    }

    @Test void inheritedNineEndpointBindingsAndCommandAreDiscoverableWithoutAnyOpenApiFetchOrDatabase() {
        try (var context = mvcContext()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            assertTrue(bindings.diagnostics().isEmpty(), bindings.diagnostics().toString());
            assertEquals(3, bindings.bulkOperations().size());
            var documents = mock(OpenApiDocumentService.class);
            when(documents.resolveGroupFromPath(anyString())).thenReturn("p3");
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("p3"));
            var discovered = new BulkOperationIdentityDiscovery(bindings, resolver).discover("installation");
            assertEquals(3, discovered.size()); verify(documents, never()).getDocumentForGroupStrict(anyString());
            var uniform = bindings.bulkOperations().stream().filter(b -> b.mode() == BulkMode.UNIFORM_UPDATE).findFirst().orElseThrow();
            var ref = resolver.resolve(uniform.evaluationHandler(), bindings.mappingFor(uniform.evaluationHandler()).orElseThrow());
            var body = resolver.requireResourceRequestBody(uniform.resourceKey(), ref, mapper.getTypeFactory());
            assertEquals(String.class, body.bodyType().containedType(0).getRawClass());
            assertEquals(BulkDeclarativeJpaHttpTest.Filter.class, body.bodyType().containedType(1).getRawClass());
            var document = new OpenAPI().paths(new Paths());
            for (var binding : bindings.bulkOperations()) {
                var path = bindings.mappingFor(binding.evaluationHandler()).orElseThrow().getPatternValues().iterator().next();
                document.getPaths().addPathItem(path, new PathItem().post(new Operation().operationId(binding.evaluationOperationId())));
            }
            new CustomOpenApiResolver(mapper).synthesizeBulkEvaluationSchemas(document, bindings);
            var schemas = document.getComponents().getSchemas();
            assertEquals("int64-decimal", mapper.valueToTree(schemas.get("ItemBulkUniformEvaluationRequest")).at("/properties/selection/properties/targets/items/properties/id/format").asText());
            assertNotNull(schemas.get("ItemBulkCommandEvaluationRequest").getProperties().get("parameters"));
        }
    }

    private static org.springframework.web.context.support.AnnotationConfigWebApplicationContext mvcContext() {
        var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("objectMapper", new ObjectMapper().findAndRegisterModules());
            factory.registerSingleton("operations", mock(CanonicalOperationResolver.class));
        });
        context.register(Mvc.class, BulkDeclarativeJpaHttpTest.ItemController.class);
        context.refresh(); return context;
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class Mvc { }

    @Test void autoConfigurationPlacesOneDefaultConverterFirstAndHonorsHostOverrideWithoutStartingBulkInfrastructure() {
        var existing = new PraxisBulkProtocolHttpMessageConverter(mapper, List.of());
        for (boolean override : List.of(false, true)) {
            var runner = new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PraxisBulkWebAutoConfiguration.class))
                    .withBean(ObjectMapper.class, () -> mapper)
                    .withBean(BulkResourceOperationBindings.class, BulkResourceOperationBindings::empty)
                    .withBean(CanonicalOperationResolver.class, () -> mock(CanonicalOperationResolver.class))
                    .withBean(CustomOpenApiResolver.class, () -> new CustomOpenApiResolver(mapper));
            if (override) runner = runner.withBean(PraxisBulkProtocolHttpMessageConverter.class, () -> existing);
            boolean hostOverride = override;
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                var converter = context.getBean(PraxisBulkProtocolHttpMessageConverter.class);
                if (hostOverride) assertSame(existing, converter);
                var converters = new ArrayList<org.springframework.http.converter.HttpMessageConverter<?>>();
                converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper));
                context.getBean(WebMvcConfigurer.class).extendMessageConverters(converters);
                assertSame(converter, converters.getFirst());
                assertEquals(1, context.getBeansOfType(PraxisBulkProtocolHttpMessageConverter.class).size());
                assertTrue(context.getBeansOfType(BulkExecutionInfrastructure.class).isEmpty());
                assertTrue(context.getBeansOfType(BulkOperationLifecycle.class).isEmpty());
                assertTrue(context.getBeansOfType(BulkOperationStatusLookup.class).isEmpty());
            });
        }
    }
    @Test void statusLookupIsConditionalAndHonorsTheHostOverrideWithoutDoingIoAtBootstrap() {
        for (boolean override : List.of(false,true)) {
            var infrastructure = mock(BulkExecutionInfrastructure.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
            var existing = mock(BulkOperationStatusLookup.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
            var runner = new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PraxisBulkWebAutoConfiguration.class))
                    .withBean(ObjectMapper.class, () -> mapper)
                    .withBean(BulkResourceOperationBindings.class,BulkResourceOperationBindings::empty)
                    .withBean(CanonicalOperationResolver.class, () -> mock(CanonicalOperationResolver.class))
                    .withBean(CustomOpenApiResolver.class, () -> new CustomOpenApiResolver(mapper))
                    .withBean(BulkExecutionInfrastructure.class, () -> infrastructure);
            if (override) runner = runner.withBean(BulkOperationStatusLookup.class, () -> existing);
            boolean hostOverride = override;
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(1,context.getBeansOfType(BulkOperationStatusLookup.class).size());
                if (hostOverride) assertSame(existing,context.getBean(BulkOperationStatusLookup.class));
                verifyNoInteractions(infrastructure);
            });
        }
    }

    @Test void inheritedStatusRoutingUsesTheDeclaredResourceKeyAndFailsClosedWithoutInfrastructure() {
        var controller = new BulkDeclarativeJpaHttpTest.ItemController();
        var id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller,"bulkProposalOperationId",id));
        var lookup = mock(BulkOperationStatusLookup.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        controller.engine = mock(JpaResourceBulkEngine.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(controller.engine.resolveProposalOperationId(lookup,"p3.items",id)).thenReturn("p3.items.bulk.update");
        when(controller.engine.resolveExecutionOperationId(lookup,"p3.items",id)).thenReturn("p3.command");
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"bulkStatusLookup",lookup);
        assertEquals("p3.items.bulk.update",org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller,"bulkProposalOperationId",id));
        assertEquals("p3.command",org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller,"bulkExecutionOperationId",id));
    }
    @Test void explicitConfirmationIdsAreUsedByInheritedHandlersWithoutAliases() throws Exception {
        assertEquals("existing.items.uniform", BulkControllerOperationIds.updateConfirmationId(ExplicitIds.class,false));
        assertEquals("p4.explicit-items.bulk.update-items",BulkControllerOperationIds.updateConfirmationId(ExplicitIds.class,true));
        var method=ExplicitIds.class.getMethod("confirmBulkUpdate",BulkConfirmationRequest.class,String.class);
        assertEquals("existing.items.uniform",BulkControllerOperationIds.operationId(new org.springframework.web.method.HandlerMethod(new ExplicitIds(),method)));
    }
    @org.praxisplatform.uischema.annotation.ApiResource(value="/p4/items",resourceKey="p4.explicit-items")
    static class ExplicitIds extends BulkDeclarativeJpaHttpTest.ItemController {
        @Override
        @BulkResourceOperation(BulkResourceOperation.Role.UNIFORM_CONFIRMATION)
        @io.swagger.v3.oas.annotations.Operation(operationId="existing.items.uniform")
        public org.springframework.http.ResponseEntity<org.praxisplatform.uischema.rest.response.RestApiResponse<BulkExecution>> confirmBulkUpdate(
                BulkConfirmationRequest request,String key) { return super.confirmBulkUpdate(request,key); }
    }

}
