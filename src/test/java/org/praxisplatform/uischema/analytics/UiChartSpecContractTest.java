package org.praxisplatform.uischema.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiChartSpecContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void funnelAndPyramidExamplesAreValid() throws Exception {
        JsonSchema schema = chartSchema();

        assertTrue(schema.validate(example("x-ui-chart-funnel.valid.json")).isEmpty());
        assertTrue(schema.validate(example("x-ui-chart-pyramid.valid.json")).isEmpty());
    }

    @Test
    void funnelRequiresExactlyOneMetric() throws Exception {
        assertFalse(chartSchema()
                .validate(example("x-ui-chart-funnel.invalid.json"))
                .isEmpty());
    }

    private JsonSchema chartSchema() throws Exception {
        JsonNode schemaDocument = objectMapper.readTree(
                Path.of("docs/spec/x-ui-chart.schema.json").toFile());
        return JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaDocument);
    }

    private JsonNode example(String fileName) throws Exception {
        return objectMapper.readTree(
                Path.of("docs/spec/examples", fileName).toFile());
    }
}
