package org.praxisplatform.uischema.configuration;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.OrderedOpenApiJsonResponse;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiWriterAutoConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiWriterAutoConfiguration.class));

    @Test
    void sortsActualWireNamesRecursivelyAndPreservesArraysAndNumericPrecision() {
        runner.withPropertyValues("springdoc.writer-with-order-by-keys=true").run(context -> {
            var advice = context.getBean(OrderedOpenApiJsonResponse.class);
            var json = parameter("openapiJson");
            assertThat(advice.supports(json, ByteArrayHttpMessageConverter.class)).isTrue();
            assertThat(advice.supports(parameter(MultipleOpenApiWebMvcResource.class, "openapiJson"),
                    ByteArrayHttpMessageConverter.class)).isTrue();
            assertThat(advice.supports(parameter("openapiYaml"), ByteArrayHttpMessageConverter.class)).isFalse();
            String first = """
                    {"schema":{"enum":["INHERIT","ALLOW","DENY"],"default":"INHERIT"},"z":0.12345678901234567890,"example":[-0.0,1e+0003,{"b":null,"a":"\\u0061"}]}""";
            String second = """
                    {"z":0.12345678901234567890,"schema":{"default":"INHERIT","enum":["INHERIT","ALLOW","DENY"]},"example":[-0.0,1e+0003,{"a":"\\u0061","b":null}]}""";
            assertThat(write(advice, json, first)).isEqualTo(write(advice, json, second))
                    .isEqualTo("""
                            {"example":[-0.0,1e+0003,{"a":"\\u0061","b":null}],"schema":{"default":"INHERIT","enum":["INHERIT","ALLOW","DENY"]},"z":0.12345678901234567890}""");
            assertThatThrownBy(() -> write(advice, json, "{\"x\":1,\"\\u0078\":2}"))
                    .isInstanceOf(IllegalStateException.class);
            String longValue = "x".repeat(20_100_000);
            assertThat(write(advice, json, "{\"z\":\"" + longValue + "\",\"a\":-0.0}"))
                    .isEqualTo("{\"a\":-0.0,\"z\":\"" + longValue + "\"}");
        });
    }

    @Test
    void leavesTheDefaultWriterAloneWithoutTheSortingOption() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OrderedOpenApiJsonResponse.class));
    }

    private static MethodParameter parameter(String name) {
        return parameter(OpenApiWebMvcResource.class, name);
    }

    private static MethodParameter parameter(Class<?> resource, String name) {
        var method = java.util.Arrays.stream(resource.getMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        return new MethodParameter(method, -1);
    }

    private static String write(OrderedOpenApiJsonResponse advice, MethodParameter parameter, String input) {
        var response = new ServletServerHttpResponse(new MockHttpServletResponse());
        byte[] original = input.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(original.length);
        byte[] output = advice.beforeBodyWrite(original, parameter, MediaType.APPLICATION_JSON,
                ByteArrayHttpMessageConverter.class, null, response);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(output.length);
        return new String(output, StandardCharsets.UTF_8);
    }
}
