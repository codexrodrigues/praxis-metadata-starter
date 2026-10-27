package org.praxisplatform.uischema.exporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomFieldCollectionExportTest {
    record Row(Map<String,Object> customFields) {}
    private final List<CollectionExportField> fields = List.of(
            new CollectionExportField("customFields.size", "Size", true, true, "integer", "customFields.size"));
    private final DefaultCollectionExportExecutor executor = new DefaultCollectionExportExecutor(List.of(
            new CsvCollectionExportEngine(), new JsonCollectionExportEngine(new ObjectMapper()), new ExcelCollectionExportEngine()));
    private CollectionExportRequest<?> request(CollectionExportFormat format) {
        return new CollectionExportRequest<>("table", "people", "/people", format, CollectionExportScope.ALL,
                null, List.of(), null, null, null, Map.of(), true, false, null, null, Map.of());
    }
    @Test void traversesRecordsAndNestedMapsAndPreservesNull() {
        assertEquals(42, CollectionExportValueResolver.readFieldValue(new Row(Map.of("size",42)), "customFields.size"));
        assertNull(CollectionExportValueResolver.readFieldValue(new Row(null), "customFields.size"));
        assertEquals("value", CollectionExportValueResolver.readFieldValue(Map.of("outer", Map.of("inner", "value")), "outer.inner"));
        assertThrows(IllegalArgumentException.class, () -> CollectionExportValueResolver.readFieldValue(new Row(Map.of()), "class.classLoader"));
    }
    @Test void exportsNestedCustomFieldsInAllTabularFormats() throws Exception {
        var rows = List.of(new Row(Map.of("size",42)));
        var csv = executor.export(request(CollectionExportFormat.CSV), rows, fields, CollectionExportValueResolver.reflective(), Map.of());
        assertEquals("\"Size\"\r\n\"42\"", new String(csv.content(), StandardCharsets.UTF_8));
        var json = executor.export(request(CollectionExportFormat.JSON), rows, fields, CollectionExportValueResolver.reflective(), Map.of());
        assertEquals("[{\"customFields.size\":42}]", new String(json.content(), StandardCharsets.UTF_8));
        var excel = executor.export(request(CollectionExportFormat.EXCEL), rows, fields, CollectionExportValueResolver.reflective(), Map.of());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.content()))) {
            assertEquals("42", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }
    @Test void preservesExistingHostResolverAuthority() {
        var csv = executor.export(request(CollectionExportFormat.CSV), List.of(new Row(Map.of("size",42))), fields,
                (row, field) -> "redacted", Map.of());
        assertTrue(new String(csv.content(), StandardCharsets.UTF_8).contains("redacted"));
        assertFalse(new String(csv.content(), StandardCharsets.UTF_8).contains("42"));
    }
}
