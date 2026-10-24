package org.praxisplatform.uischema.configuration;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.uischema.rest.response.RestApiResource;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SpringDocConfigProperties.ApiDocs.OpenApiVersion;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.junit.jupiter.api.Assertions.*;

class PraxisMetadataAutoConfigurationCollectionDialectTest {
    private static final Map<String, String> CONTAINERS = Map.of(
            "RestApiResponseListEntityModelRowDto", "data",
            "PageEntityModelRowDto", "content",
            "CursorPageEntityModelRowDto", "content");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realConverterCollectionsAreFlattenedForListPageAndCursor(boolean openApi31) {
        OpenAPI document = document(openApi31);
        Map<String, Schema> schemas = document.getComponents().getSchemas();
        CONTAINERS.forEach((name, property) -> {
            Schema<?> array = arrayProperty(schemas, name, property);
            assertNotNull(array.getItems());
            assertEquals("#/components/schemas/RestApiResource", array.getItems().get$ref());
            if (openApi31) {
                assertEquals("PageEntityModelRowDto".equals(name)
                        ? Set.of("array", "null") : Set.of("array"), array.getTypes());
                assertFalse(array instanceof ArraySchema);
            } else {
                assertEquals("array", array.getType());
                assertInstanceOf(ArraySchema.class, array);
            }
        });

        new PraxisMetadataAutoConfiguration().restApiResourceComponentCustomizer().customise(document);

        CONTAINERS.forEach((name, property) -> {
            Schema<?> array = arrayProperty(schemas, name, property);
            assertEquals("#/components/schemas/RestApiResourceRowDto", array.getItems().get$ref(), name);
        });
        Schema<?> resource = schemas.get("RestApiResourceRowDto");
        assertNotNull(resource);
        assertEquals(2, resource.getAllOf().size());
        assertEquals("#/components/schemas/RowDto", resource.getAllOf().get(0).get$ref());
        Schema<?> links = resource.getAllOf().get(1);
        assertEquals("#/components/schemas/RestApiLinks", links.getProperties().get("_links").get$ref());
        Schema<?> row = schemas.get("RowDto");
        assertTrue(row.getProperties().containsKey("nomeCompleto"));
        assertTrue(row.getProperties().containsKey("cpf"));
        assertFalse(row.getProperties().containsKey("content"));
        assertFalse(links.getProperties().containsKey("links"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realConverterDecimalFieldsAndArrayItemsReceiveCanonicalFormat(boolean openApi31) {
        OpenAPI document = document(openApi31);
        Schema<?> row = document.getComponents().getSchemas().get("RowDto");
        Schema<?> total = row.getProperties().get("total");
        Schema<?> amountArray = row.getProperties().get("amounts");
        Schema<?> nullableTotal = row.getProperties().get("nullableTotal");
        assertNull(nullableTotal.getFormat());
        assertNull(total.getFormat());
        assertNull(amountArray.getItems().getFormat());
        if (openApi31) assertFalse(amountArray instanceof ArraySchema);

        new PraxisMetadataAutoConfiguration().restApiResourceComponentCustomizer().customise(document);

        assertEquals("decimal", total.getFormat());
        assertEquals("decimal", nullableTotal.getFormat());
        assertEquals("decimal", amountArray.getItems().getFormat());
        if (openApi31) {
            assertEquals(Set.of("number"), total.getTypes());
            assertEquals(Set.of("number", "null"), nullableTotal.getTypes());
            assertEquals(Set.of("number"), amountArray.getItems().getTypes());
            assertNull(total.getType());
        } else {
            assertEquals("number", total.getType());
            assertEquals("number", amountArray.getItems().getType());
        }
    }

    @Test
    void ambiguousCollectionAndNumericTypesAreNotChosenArbitrarily() {
        OpenAPI document = document(true);
        Map<String, Schema> schemas = document.getComponents().getSchemas();
        CONTAINERS.forEach((name, property) -> {
            Schema<?> array = arrayProperty(schemas, name, property);
            array.setTypes(Set.of("array", "object"));
        });
        Schema<?> row = schemas.get("RowDto");
        Schema<?> total = row.getProperties().get("total");
        total.setTypes(Set.of("number", "string"));

        new PraxisMetadataAutoConfiguration().restApiResourceComponentCustomizer().customise(document);

        CONTAINERS.forEach((name, property) -> {
            Schema<?> array = arrayProperty(schemas, name, property);
            assertEquals("#/components/schemas/RestApiResource", array.getItems().get$ref());
            assertEquals(Set.of("array", "object"), array.getTypes());
        });
        assertNull(schemas.get("RestApiResourceRowDto"));
        assertNull(total.getFormat());
        assertEquals(Set.of("number", "string"), total.getTypes());
    }

    private static Schema<?> arrayProperty(Map<String, Schema> schemas, String container, String property) {
        Schema<?> schema = schemas.get(container);
        assertNotNull(schema, container);
        Schema<?> array = schema.getProperties().get(property);
        assertNotNull(array, property);
        return array;
    }

    private OpenAPI document(boolean openApi31) {
        SpringDocConfigProperties properties = new SpringDocConfigProperties();
        properties.getApiDocs().setVersion(openApi31 ? OpenApiVersion.OPENAPI_3_1 : OpenApiVersion.OPENAPI_3_0);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("springDocConfigProperties", properties);
        var resolver = new OpenApiUiSchemaAutoConfiguration().modelResolver(
                Json.mapper(), beans.getBeanProvider(SpringDocConfigProperties.class));
        assertEquals(openApi31, resolver.isOpenapi31());
        ModelConverters converters = new ModelConverters(openApi31);
        converters.addConverter(resolver);
        Map<String, Schema> schemas = new LinkedHashMap<>();
        for (Class<?> dto : List.of(RowDto.class, ListEnvelope.class, PageEnvelope.class, CursorEnvelope.class)) {
            var resolved = converters.resolveAsResolvedSchema(new AnnotatedType(dto).resolveAsRef(true));
            assertNotNull(resolved.schema);
            assertNotNull(resolved.schema.get$ref());
            schemas.putAll(resolved.referencedSchemas);
        }
        return new OpenAPI().openapi(openApi31 ? "3.1.0" : "3.0.3")
                .components(new Components().schemas(schemas));
    }

    public static class RowDto {
        private String nomeCompleto;
        private String cpf;
        private BigDecimal total;
        @io.swagger.v3.oas.annotations.media.Schema(types = {"number", "null"})
        private BigDecimal nullableTotal;
        private List<BigDecimal> amounts;
        public String getNomeCompleto() { return nomeCompleto; }
        public String getCpf() { return cpf; }
        public BigDecimal getTotal() { return total; }
        public BigDecimal getNullableTotal() { return nullableTotal; }
        public List<BigDecimal> getAmounts() { return amounts; }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "RestApiResponseListEntityModelRowDto")
    public static class ListEnvelope {
        private List<RestApiResource> data;
        public List<RestApiResource> getData() { return data; }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "PageEntityModelRowDto")
    public static class PageEnvelope {
        @io.swagger.v3.oas.annotations.media.Schema(types = {"array", "null"})
        private List<RestApiResource> content;
        public List<RestApiResource> getContent() { return content; }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CursorPageEntityModelRowDto")
    public static class CursorEnvelope {
        private List<RestApiResource> content;
        public List<RestApiResource> getContent() { return content; }
    }
}
