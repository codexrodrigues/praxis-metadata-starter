package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Cross-repository JSON schema parity gate executed when {@code -Dontology.g2.catalog-schema} is provided. */
class OntologyG2SchemaParityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void contractFixtureMatchesCanonicalConfigSchemaWhenGatePathIsProvided() throws Exception {
        String canonical = System.getProperty("ontology.g2.catalog-schema");
        Assumptions.assumeTrue(canonical != null, "Cross-repository gate supplies the canonical schema path");
        assertThat(mapper.readTree(Path.of(canonical).toFile())).isEqualTo(
                mapper.readTree(getClass().getResourceAsStream("/contracts/domain-catalog-v0.2.schema.json")));
    }
}
