package org.praxisplatform.uischema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldControlTypeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonSerializationUsesEnumValue() throws JsonProcessingException {
        for (FieldControlType type : FieldControlType.values()) {
            String json = mapper.writeValueAsString(type);
            assertEquals("\"" + type.getValue() + "\"", json);
        }
    }

    @Test
    void fromValueIsCaseInsensitive() {
        for (FieldControlType type : FieldControlType.values()) {
            assertEquals(type, FieldControlType.fromValue(type.getValue().toUpperCase()));
            assertEquals(type, FieldControlType.fromValue(type.getValue().toLowerCase()));
        }
    }
}

