package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.openapi.GenerationScopedGenericResponseService;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.converters.AdditionalModelsConverter;
import org.springdoc.core.converters.FileSupportConverter;
import org.springdoc.core.converters.ResponseSupportConverter;
import org.springdoc.core.converters.WebFluxSupportConverter;
import org.springdoc.core.models.MethodAttributes;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.core.service.OperationService;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springdoc.core.utils.PropertyResolverUtils;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiModelConverterOrderingTest {
    @Test
    void canonicalResolverRunsAfterSpringdocRegardlessOfBeanDefinitionOrder() {
        List<JsonNode> snapshots = new ArrayList<>();
        // User configuration registers the real Metadata definitions before auto-configurations;
        // the normal auto-config path registers them after Springdoc core in this integration.
        for (boolean metadataFirst : new boolean[] {true, false}) {
            var documentProperties = new SpringDocConfigProperties();
            documentProperties.getApiDocs().setVersion(SpringDocConfigProperties.ApiDocs.OpenApiVersion.OPENAPI_3_0);
            ModelConverters registry = ModelConverters.getInstance(documentProperties.isOpenapi31());
            List<ModelConverter> previous = List.copyOf(registry.getConverters());
            try {
                WebApplicationContextRunner runner = new WebApplicationContextRunner()
                        .withConfiguration(AutoConfigurations.of(SpringDocConfiguration.class,
                                OpenApiResponseGenerationAutoConfiguration.class,
                                SpringDocWebMvcConfiguration.class, WebMvcAutoConfiguration.class))
                        .withBean(SpringDocConfigProperties.class, () -> documentProperties)
                        .withBean(ErrorAdvice.class, ErrorAdvice::new);
                runner = metadataFirst
                        ? runner.withUserConfiguration(OpenApiUiSchemaAutoConfiguration.class)
                        : runner.withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));
                runner.run(context -> {
                    assertThat(context).hasNotFailed();
                    List<String> definitions = Arrays.asList(context.getBeanDefinitionNames());
                    assertThat(definitions.indexOf("modelResolver") < definitions.indexOf("responseSupportConverter"))
                            .as("fixture exercises the requested bean definition order").isEqualTo(metadataFirst);

                    CustomOpenApiResolver canonical = context.getBean(CustomOpenApiResolver.class);
                    List<ModelConverter> injected = context.getBeanProvider(ModelConverter.class).orderedStream().toList();
                    assertThat(injected.getFirst()).isSameAs(canonical);
                    List<ModelConverter> effective = registry.getConverters();
                    int canonicalIndex = effective.indexOf(canonical);
                    assertThat(canonicalIndex).isGreaterThanOrEqualTo(3);
                    List<Class<? extends ModelConverter>> decorators = new ArrayList<>(List.of(
                            ResponseSupportConverter.class, FileSupportConverter.class, AdditionalModelsConverter.class));
                    if (ClassUtils.isPresent("reactor.core.publisher.Flux", getClass().getClassLoader()))
                        decorators.add(WebFluxSupportConverter.class);
                    else
                        assertThat(context).doesNotHaveBean(WebFluxSupportConverter.class);
                    for (Class<? extends ModelConverter> decorator : decorators) {
                        assertThat(indexOf(effective, decorator)).as(decorator.getSimpleName())
                                .isGreaterThanOrEqualTo(0).isLessThan(canonicalIndex);
                    }
                    assertThat(indexOf(effective, ModelResolver.class)).isGreaterThan(canonicalIndex);
                    assertThat(effective.stream().filter(converter -> converter instanceof CustomOpenApiResolver)).hasSize(1);

                    var adapter = context.getBean(GenerationScopedGenericResponseService.class);
                    var original = new GenericResponseService(context.getBean(OperationService.class),
                            context.getBean(SpringDocConfigProperties.class), context.getBean(PropertyResolverUtils.class));
                    original.setApplicationContext(context);
                    ErrorAdvice advice = context.getBean(ErrorAdvice.class);
                    JsonNode snapshot = responses(adapter, advice);
                    assertThat(snapshot).isEqualTo(responses(original, advice));
                    snapshots.add(snapshot);
                });
            } finally {
                SpringDocAnnotationsUtils.clearCache(null);
                // ModelConverters is process-global; restore exact converter identities and order.
                List.copyOf(registry.getConverters()).forEach(registry::removeConverter);
                for (int index = previous.size() - 1; index >= 0; index--) registry.addConverter(previous.get(index));
            }
        }
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(1)).isEqualTo(snapshots.get(0));
    }

    private static int indexOf(List<ModelConverter> converters, Class<? extends ModelConverter> type) {
        for (int index = 0; index < converters.size(); index++)
            if (converters.get(index).getClass().equals(type)) return index;
        return -1;
    }

    private static JsonNode responses(GenericResponseService builder, ErrorAdvice advice) throws Exception {
        Components components = new Components();
        builder.buildGenericResponse(components, Map.of("advice", advice), Locale.ROOT);
        HandlerMethod handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("read"));
        var attributes = new MethodAttributes("application/json", "application/json", Locale.ROOT);
        attributes.calculateConsumesProduces(handler.getMethod());
        ApiResponses responses = builder.build(components, handler, new Operation(), attributes);
        assertThat(responses).containsKeys("200", "400");
        assertThat(responses.get("200").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/GenerationOrderDto");
        assertThat(responses.get("400").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/GenerationOrderError");
        assertThat(components.getSchemas()).containsKeys("GenerationOrderDto", "GenerationOrderError");
        assertThat(components.getSchemas().keySet()).noneMatch(name -> name.startsWith("ResponseEntity"));
        Schema<?> value = (Schema<?>) components.getSchemas().get("GenerationOrderDto").getProperties().get("value");
        assertThat(value.getExtensions()).containsKey("x-ui");
        assertThat(((Map<?, ?>) value.getExtensions().get("x-ui")).get("label")).isEqualTo("Visible value");
        JsonNode result = new SchemaCanonicalizer().canonicalize(new ObjectMapper().valueToTree(
                Map.of("components", components, "responses", responses)));
        if (builder instanceof GenerationScopedGenericResponseService adapter)
            adapter.customise(new OpenAPI().components(components));
        return result;
    }

    public static class GenerationOrderDto {
        @UISchema(label = "Visible value")
        private String value;
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public record GenerationOrderError(String message) { }

    @RestController
    public static class Endpoint {
        @GetMapping(value = "/generation-order", produces = "application/json")
        public ResponseEntity<GenerationOrderDto> read() { return ResponseEntity.ok(new GenerationOrderDto()); }
    }

    @RestControllerAdvice
    public static class ErrorAdvice {
        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        @RequestMapping(produces = "application/json")
        public ResponseEntity<GenerationOrderError> invalid() {
            return ResponseEntity.badRequest().body(new GenerationOrderError("invalid"));
        }
    }
}
