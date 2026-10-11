package org.praxisplatform.uischema.extension;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.util.OpenApiUiUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that Java date/time types are correctly mapped to their appropriate data types
 */
class TypeDetectionTest {

    @Test
    void testLocalDateMapping() {
        // Test that LocalDate fields (mapped as string with date format) are detected as DATE type
        Map<String, Object> uiExtension = new HashMap<>();
        
        // Simulate what SpringDoc generates for LocalDate: type="string", format="date"
        OpenApiUiUtils.populateUiDataType(uiExtension, "string", "date");
        
        assertEquals(FieldDataType.DATE.getValue(), uiExtension.get(FieldConfigProperties.TYPE.getValue()));
    }

    @Test
    void testLocalDateTimeMapping() {
        // Test that LocalDateTime fields (mapped as string with date-time format) are detected as DATE type
        Map<String, Object> uiExtension = new HashMap<>();
        
        // Simulate what SpringDoc generates for LocalDateTime: type="string", format="date-time"
        OpenApiUiUtils.populateUiDataType(uiExtension, "string", "date-time");
        
        assertEquals(FieldDataType.DATE.getValue(), uiExtension.get(FieldConfigProperties.TYPE.getValue()));
    }

    @Test
    void testBigDecimalMapping() {
        // Test that BigDecimal fields (configured as number type) are detected as NUMBER type
        Map<String, Object> uiExtension = new HashMap<>();
        
        // Simulate what SpringDoc generates for BigDecimal with OpenApiConfig: type="number", format="decimal"
        OpenApiUiUtils.populateUiDataType(uiExtension, "number", "decimal");
        
        assertEquals(FieldDataType.NUMBER.getValue(), uiExtension.get(FieldConfigProperties.TYPE.getValue()));
    }

    @Test
    void testBooleanMapping() {
        // Test that boolean fields are detected as BOOLEAN type
        Map<String, Object> uiExtension = new HashMap<>();
        
        OpenApiUiUtils.populateUiDataType(uiExtension, "boolean", null);
        
        assertEquals(FieldDataType.BOOLEAN.getValue(), uiExtension.get(FieldConfigProperties.TYPE.getValue()));
    }

    @Test
    void testStringMapping() {
        // Test that regular string fields are detected as TEXT type
        Map<String, Object> uiExtension = new HashMap<>();
        
        OpenApiUiUtils.populateUiDataType(uiExtension, "string", null);
        
        assertEquals(FieldDataType.TEXT.getValue(), uiExtension.get(FieldConfigProperties.TYPE.getValue()));
    }

    @Test
    void testEmailMapping() {
        // Test that email fields are detected as EMAIL type
        Map<String, Object> uiExtension = new HashMap<>();
        
        OpenApiUiUtils.populateUiDataType(uiExtension, "string", "email");
        
        assertEquals(FieldDataType.EMAIL.getValue(), uiExtension.get(FieldConfigProperties.TYPE.getValue()));
    }

    @Test
    void testDoesNotOverrideExistingType() {
        // Test that populateUiDataType doesn't override an existing type
        Map<String, Object> uiExtension = new HashMap<>();
        uiExtension.put(FieldConfigProperties.TYPE.getValue(), "custom-type");
        
        OpenApiUiUtils.populateUiDataType(uiExtension, "string", "date");
        
        assertEquals("custom-type", uiExtension.get(FieldConfigProperties.TYPE.getValue()));
    }
}