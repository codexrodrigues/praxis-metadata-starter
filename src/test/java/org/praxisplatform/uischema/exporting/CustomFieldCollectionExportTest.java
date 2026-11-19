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

    @Test void formatsStringDatesCurrencyAndMultiEnumOptionLabelsInCustomFieldExport() {
        var formattedFields = List.of(
                new CollectionExportField("customFields.data_vig", "Vigência", true, true, "date", "customFields.data_vig"),
                new CollectionExportField("customFields.auxilio", "Auxílio", true, true, "number", "customFields.auxilio", null,
                        new CollectionExportFieldPresentation("currency", null, "BRL", "pt-BR", null, null, null, null)),
                new CollectionExportField("customFields.mods", "Modalidades", true, true, "string", "customFields.mods", null,
                        new CollectionExportFieldPresentation("string", null, null, "pt-BR", null, null, null, null,
                                Map.of("PRES", "Presencial", "REM", "Remoto"))));
        var row = new Row(Map.of(
                "data_vig", "2026-11-19",
                "auxilio", new java.math.BigDecimal("1250.50"),
                "mods", List.of("PRES", "REM")));
        var formattedReq = new CollectionExportRequest<>("table", "people", "/people",
                CollectionExportFormat.CSV, CollectionExportScope.ALL,
                null, List.of(), null, null, null, Map.of(), true, true, null, null, Map.of());
        var csv = executor.export(formattedReq, List.of(row), formattedFields,
                CollectionExportValueResolver.reflective(), Map.of());
        String output = new String(csv.content(), StandardCharsets.UTF_8);
        assertTrue(output.contains("19/11/2026"), output);
        assertTrue(output.contains("1.250,50"), output);
        assertTrue(output.contains("Presencial, Remoto"), output);
    }

    @Test void formatsFreeArrayValuesWithoutValueLabelsUsingCommaSeparatorAndRespectsDefaultVisibility() {
        var visibleFreeArray = new CollectionExportField("customFields.tags", "Tags", true, true, "string", "customFields.tags");
        var hiddenField = new CollectionExportField("customFields.secret", "Oculto", false, true, "string", "customFields.secret");
        var freeArrayFields = List.of(visibleFreeArray, hiddenField);
        var row = new Row(Map.of("tags", List.of("JAVA", "SPRING"), "secret", "TOP_SECRET"));
        var req = new CollectionExportRequest<>("table", "people", "/people",
                CollectionExportFormat.CSV, CollectionExportScope.ALL,
                null, List.of(), null, null, null, Map.of(), true, true, null, null, Map.of());
        var csv = executor.export(req, List.of(row), freeArrayFields,
                CollectionExportValueResolver.reflective(), Map.of());
        String output = new String(csv.content(), StandardCharsets.UTF_8);
        assertFalse(hiddenField.visible());
        assertTrue(output.contains("JAVA, SPRING"), output);
        assertFalse(output.contains("TOP_SECRET"), output);
    }
}
