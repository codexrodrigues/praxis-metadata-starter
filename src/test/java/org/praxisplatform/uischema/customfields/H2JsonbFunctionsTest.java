package org.praxisplatform.uischema.customfields;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class H2JsonbFunctionsTest {
    @Test void extractsJsonScalarsFromStringsBytesAndMapsWithoutDatabase() {
        assertEquals("42", H2JsonbFunctions.jsonbExtractPathText("{\"size\":42}", "size"));
        assertEquals("true", H2JsonbFunctions.jsonbExtractPathText("{\"active\":true}".getBytes(StandardCharsets.UTF_8), "active"));
        assertEquals("value", H2JsonbFunctions.jsonbExtractPathText(Map.of("key", "value"), "key"));
        assertNull(H2JsonbFunctions.jsonbExtractPathText("{\"size\":null}", "size"));
        assertNull(H2JsonbFunctions.jsonbExtractPathText("{}", "size"));
        assertThrows(IllegalArgumentException.class, () -> H2JsonbFunctions.jsonbExtractPathText("invalid", "size"));
    }
}
