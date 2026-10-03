package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.springdoc.core.models.MethodAttributes;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.core.service.OperationService;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springdoc.core.utils.PropertyResolverUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationScopedGenericResponseServiceTest {
    private final OperationService operations = mock(OperationService.class);
    private final PropertyResolverUtils resolver = mock(PropertyResolverUtils.class);
    private final SpringDocConfigProperties properties = openApi30Properties();
    private final AnnotationConfigApplicationContext applicationContext =
            new AnnotationConfigApplicationContext(AdviceA.class, AdviceB.class);
    private final Endpoint endpoint = new Endpoint();
    private final Map<String, Object> advice = Map.of("advice", new AdviceA(), "endpoint", endpoint);
    private final GenerationScopedGenericResponseService service = service();

    private static SpringDocConfigProperties openApi30Properties() {
        var properties = new SpringDocConfigProperties();
        properties.getApiDocs().setVersion(SpringDocConfigProperties.ApiDocs.OpenApiVersion.OPENAPI_3_0);
        return properties;
    }

    private GenerationScopedGenericResponseService service() {
        when(resolver.getSpecVersion()).thenReturn(SpecVersion.V30);
        when(resolver.resolve(anyString(), any(Locale.class))).thenAnswer(call -> call.getArgument(0));
        var builder = new GenerationScopedGenericResponseService(operations, properties, resolver);
        builder.setApplicationContext(applicationContext);
        return builder;
    }

    private GenericResponseService original() {
        var builder = new GenericResponseService(operations, properties, resolver);
        builder.setApplicationContext(applicationContext);
        return builder;
    }

    @AfterEach void releaseRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
            attributes.requestCompleted();
        RequestContextHolder.resetRequestAttributes();
        // AbstractOpenApiResource performs this in its generation finally block. Direct
        // builder tests must not carry Swagger's per-thread model context into another test.
        SpringDocAnnotationsUtils.clearCache(null);
        applicationContext.close();
    }

    @Test
    void oneHundredPreloadStyleGenerationsPreserveResponsesAndSchemasWithoutHistory() throws Exception {
        JsonNode expected = generate(original(), advice);
        Set<Object> delegates = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int generation = 0; generation < 100; generation++) {
            Components components = new Components();
            service.buildGenericResponse(components, advice, Locale.ROOT);
            Object frame = nonServletFrame();
            Object delegate = ReflectionTestUtils.getField(frame, "delegate");
            assertThat(delegates.add(delegate)).isTrue();
            assertThat((Collection<?>) ReflectionTestUtils.getField(delegate, "controllerAdviceInfos")).hasSize(1);
            assertThat((Collection<?>) ReflectionTestUtils.getField(delegate, "localExceptionHandlers")).hasSize(1);
            assertThat(snapshot(components, build(service, components))).isEqualTo(expected);
            service.customise(new OpenAPI().components(components));
            assertThat(nonServletFrame()).isNull();
        }
        assertThat((Collection<?>) ReflectionTestUtils.getField(service, "controllerAdviceInfos")).isEmpty();
        assertThat((Collection<?>) ReflectionTestUtils.getField(service, "localExceptionHandlers")).isEmpty();
    }

    @Test
    void realGlobalAndLocalErrorsKeepTheirReferencedSchemas() throws Exception {
        Components components = new Components();
        service.buildGenericResponse(components, advice, Locale.ROOT);
        ApiResponses responses = build(service, components);
        assertThat(responses).containsKeys("200", "400", "409");
        assertThat(components.getSchemas()).containsKeys("SuccessBody", "GlobalError", "LocalError");
        assertThat(responses.get("200").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/SuccessBody");
        assertThat(responses.get("400").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/GlobalError");
        assertThat(responses.get("409").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/LocalError");
        assertThat(snapshot(components, responses)).isEqualTo(
                generate(original(), advice));
    }

    @Test
    void servletFramesUseComponentsIdentityAndCleanUpIndependently() throws Exception {
        var request = new MockHttpServletRequest();
        var attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        Components first = new Components();
        Components second = new Components();
        assertThat(first).isEqualTo(second); // Equal shape is not equal generation identity.
        service.buildGenericResponse(first, advice, Locale.ROOT);
        service.buildGenericResponse(second, Map.of("advice", new AdviceB(), "endpoint", endpoint), Locale.ROOT);
        assertThat(requestFrames(request)).hasSize(2);
        assertThat(build(service, first)).containsKey("400").doesNotContainKey("422");
        assertThat(build(service, second)).containsKey("422").doesNotContainKey("400");
        service.customise(new OpenAPI().components(second));
        assertThat(requestFrames(request)).hasSize(1);
        assertThat(build(service, first)).containsKey("400");
        service.customise(new OpenAPI().components(first));
        assertThat(requestFrames(request)).isNull();
    }

    @Test
    void concurrentServletGroupsKeepAdviceAndComponentsSeparate() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var run = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> concurrentGeneration(new AdviceA(), ready, run));
            var second = executor.submit(() -> concurrentGeneration(new AdviceB(), ready, run));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            run.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).containsKey("400").doesNotContainKey("422");
            assertThat(second.get(10, TimeUnit.SECONDS)).containsKey("422").doesNotContainKey("400");
        } finally {
            run.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void disabledGenericResponsesNeedNoFrameAndMatchEmptyOriginalBuilder() throws Exception {
        properties.setOverrideWithGenericResponse(false);
        Components actualComponents = new Components();
        Components expectedComponents = new Components();
        var original = original();
        ApiResponses actualResponses = build(service, actualComponents);
        assertThat(snapshot(actualComponents, actualResponses))
                .isEqualTo(snapshot(expectedComponents, build(original, expectedComponents)));
        assertThat(nonServletFrame()).isNull();
        assertThat(actualResponses).containsOnlyKeys("200");
        assertThat(actualResponses.get("200").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/SuccessBody");
        assertThat(actualComponents.getSchemas()).containsOnlyKeys("SuccessBody");
    }

    @Test
    void differentComponentsWithoutGenericPreparationFailClosed() {
        Components original = new Components();
        service.buildGenericResponse(original, advice, Locale.ROOT);
        assertThatThrownBy(() -> build(service, new Components()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Components");
        assertThat(nonServletFrame()).isNull();
    }

    @Test
    void nextNonServletGenerationReplacesOneFrameAfterAnExternalFailure() throws Exception {
        Components abandoned = new Components();
        service.buildGenericResponse(abandoned, advice, Locale.ROOT);
        Object previous = nonServletFrame();
        // An external customizer can throw before the cleanup callback: no claim of interception.
        Components next = new Components();
        service.buildGenericResponse(next, Map.of("advice", new AdviceB(), "endpoint", endpoint), Locale.ROOT);
        assertThat(nonServletFrame()).isNotSameAs(previous);
        assertThat(ReflectionTestUtils.getField(nonServletFrame(), "components")).isSameAs(next);
        assertThat(build(service, next)).containsKey("422").doesNotContainKey("400");
        service.customise(new OpenAPI().components(next));
        assertThat(nonServletFrame()).isNull();
    }

    @Test
    void servletCompletionReleasesFrameWhenAnExternalCustomizerFailsBeforeCleanup() {
        var request = new MockHttpServletRequest();
        var attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        service.buildGenericResponse(new Components(), advice, Locale.ROOT);
        assertThat(requestFrames(request)).hasSize(1);
        attributes.requestCompleted();
        assertThat(requestFrames(request)).isNull();
    }

    @Test
    void observedPreparationFailureReleasesFrameAndRetryUsesFreshDelegate() throws Exception {
        doThrow(new IllegalStateException("advice failure")).doReturn(false).when(operations).isHidden(any());
        assertThatThrownBy(() -> service.buildGenericResponse(new Components(), advice, Locale.ROOT))
                .isInstanceOf(IllegalStateException.class).hasMessage("advice failure");
        assertThat(nonServletFrame()).isNull();
        assertThat(generate(service, advice)).isEqualTo(
                generate(original(), advice));
    }

    @Test
    void observedOperationBuildFailureReleasesFrameBeforeRetry() throws Exception {
        Components components = new Components();
        service.buildGenericResponse(components, advice, Locale.ROOT);
        assertThatThrownBy(() -> service.build(components, handler(), null, attributes()))
                .isInstanceOf(NullPointerException.class);
        assertThat(nonServletFrame()).isNull();
        assertThat(generate(service, advice)).isEqualTo(
                generate(original(), advice));
    }

    private ApiResponses concurrentGeneration(Object globalAdvice, CountDownLatch ready, CountDownLatch run) throws Exception {
        var request = new MockHttpServletRequest();
        var attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        try {
            Components components = new Components();
            service.buildGenericResponse(components, Map.of("advice", globalAdvice, "endpoint", endpoint), Locale.ROOT);
            ready.countDown();
            assertThat(run.await(5, TimeUnit.SECONDS)).isTrue();
            ApiResponses result = build(service, components);
            service.customise(new OpenAPI().components(components));
            assertThat(requestFrames(request)).isNull();
            return result;
        } finally {
            attributes.requestCompleted();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private Object nonServletFrame() {
        return ((ThreadLocal<?>) ReflectionTestUtils.getField(service, "nonServletFrame")).get();
    }
    private Map<?, ?> requestFrames(MockHttpServletRequest request) {
        return (Map<?, ?>) request.getAttribute((String) ReflectionTestUtils.getField(service, "requestAttribute"));
    }
    private HandlerMethod handler() throws NoSuchMethodException { return new HandlerMethod(endpoint, Endpoint.class.getMethod("read")); }
    private MethodAttributes attributes() throws NoSuchMethodException {
        var attributes = new MethodAttributes("application/json", "application/json", Locale.ROOT);
        // AbstractOpenApiResource calculates the method media types before responseBuilder.build.
        attributes.calculateConsumesProduces(handler().getMethod());
        return attributes;
    }
    private ApiResponses build(GenericResponseService builder, Components components) throws Exception {
        return builder.build(components, handler(), new Operation(), attributes());
    }
    private JsonNode generate(GenericResponseService builder, Map<String, Object> handlers) throws Exception {
        Components components = new Components();
        builder.buildGenericResponse(components, handlers, Locale.ROOT);
        return snapshot(components, build(builder, components));
    }
    private JsonNode snapshot(Components components, ApiResponses responses) {
        return new SchemaCanonicalizer().canonicalize(new ObjectMapper().valueToTree(
                Map.of("components", components, "responses", responses)));
    }

    public record SuccessBody(String value) { }
    public record GlobalError(String code, String message) { }
    public record LocalError(String problem) { }
    public record OtherError(String reason) { }
    @RestController public static class Endpoint {
        @GetMapping("/sample") public SuccessBody read() { return new SuccessBody("ok"); }
        @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT)
        @RequestMapping(produces = "application/json")
        public LocalError conflict() { return new LocalError("conflict"); }
    }
    @RestControllerAdvice public static class AdviceA {
        @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
        @RequestMapping(produces = "application/json")
        public GlobalError invalid() { return new GlobalError("invalid", "invalid request"); }
    }
    @RestControllerAdvice public static class AdviceB {
        @ExceptionHandler(ArithmeticException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
        public OtherError invalid() { return new OtherError("invalid computation"); }
    }
}
