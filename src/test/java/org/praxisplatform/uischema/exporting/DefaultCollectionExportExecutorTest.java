package org.praxisplatform.uischema.exporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultCollectionExportExecutorTest {

    private final CollectionExportExecutor executor = new DefaultCollectionExportExecutor(List.of(
            new CsvCollectionExportEngine(),
            new JsonCollectionExportEngine(new ObjectMapper())
    ));

    private final List<CollectionExportField> defaultFields = List.of(
            new CollectionExportField("id", "ID", true, true, "number", "id"),
            new CollectionExportField("name", "Name", true, true, "string", "name"),
            new CollectionExportField("amount", "Amount", true, true, "number", "amount")
    );

    @Test
    void exportsCsvWithRequestedFieldsAndFormulaInjectionGuard() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "orders",
                "/api/orders",
                CollectionExportFormat.CSV,
                CollectionExportScope.SELECTED,
                null,
                List.of(
                        new CollectionExportField("name", "Customer", true, true, "string", "name"),
                        new CollectionExportField("amount", null, true, true, null, "amount")
                ),
                null,
                null,
                null,
                Map.of(),
                true,
                false,
                null,
                "orders.csv",
                Map.of()
        );

        CollectionExportResult result = executor.export(
                request,
                List.of(new Row(1, "=Ana", new BigDecimal("123.45"))),
                defaultFields,
                this::valueFor,
                Map.of("resourceKey", "orders")
        );

        assertEquals(CollectionExportFormat.CSV, result.format());
        assertEquals("orders.csv", result.fileName());
        assertEquals("text/csv; charset=UTF-8", result.contentType());
        assertEquals(1L, result.rowCount());
        assertEquals("\"Customer\",\"Amount\"\r\n\"'=Ana\",\"123.45\"",
                new String(result.content(), StandardCharsets.UTF_8));
    }

    @Test
    void exportsCsvGuardsFormulaInjectionAfterLeadingWhitespace() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "orders",
                "/api/orders",
                CollectionExportFormat.CSV,
                CollectionExportScope.SELECTED,
                null,
                List.of(new CollectionExportField("name", "Name", true, true, "string", "name")),
                null,
                null,
                null,
                Map.of(),
                true,
                false,
                null,
                "orders.csv",
                Map.of()
        );

        CollectionExportResult result = executor.export(
                request,
                List.of(new Row(1, " \n=cmd", BigDecimal.ONE)),
                defaultFields,
                this::valueFor,
                Map.of()
        );

        assertEquals("\"Name\"\r\n\"' \n=cmd\"", new String(result.content(), StandardCharsets.UTF_8));
    }

    @Test
    void exportsJsonWithStableFieldOrder() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "list",
                "orders",
                "/api/orders",
                CollectionExportFormat.JSON,
                CollectionExportScope.CURRENT_PAGE,
                null,
                List.of(),
                null,
                null,
                null,
                Map.of(),
                true,
                false,
                null,
                "orders.json",
                Map.of()
        );

        CollectionExportResult result = executor.export(
                request,
                List.of(new Row(7, "Bruno", new BigDecimal("9.90"))),
                defaultFields,
                this::valueFor,
                Map.of()
        );

        assertEquals(CollectionExportFormat.JSON, result.format());
        assertEquals("application/json", result.contentType());
        assertEquals("[{\"id\":7,\"name\":\"Bruno\",\"amount\":\"9.90\"}]",
                new String(result.content(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsFormatsWithoutRegisteredEngine() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "orders",
                "/api/orders",
                CollectionExportFormat.PDF,
                CollectionExportScope.ALL,
                null,
                List.of(),
                null,
                null,
                null,
                Map.of(),
                true,
                false,
                null,
                "orders.pdf",
                Map.of()
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> executor.export(
                request,
                List.of(new Row(1, "Ana", BigDecimal.ONE)),
                defaultFields,
                this::valueFor,
                Map.of()
        ));
        assertEquals("Unsupported collection export format: pdf", error.getMessage());
    }

    @Test
    void rejectsRequestedFieldsWhenNoneAreSupported() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "orders",
                "/api/orders",
                CollectionExportFormat.CSV,
                CollectionExportScope.SELECTED,
                null,
                List.of(new CollectionExportField("unknown", "Unknown", true, true, "string", "unknown")),
                null,
                null,
                null,
                Map.of(),
                true,
                false,
                null,
                "orders.csv",
                Map.of()
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> executor.export(
                request,
                List.of(new Row(1, "Ana", BigDecimal.ONE)),
                defaultFields,
                this::valueFor,
                Map.of()
        ));
        assertEquals("No requested export fields are supported by this resource.", error.getMessage());
    }

    private Object valueFor(Row row, CollectionExportField field) {
        return switch (field.valuePath()) {
            case "id" -> row.id();
            case "name" -> row.name();
            case "amount" -> row.amount();
            default -> null;
        };
    }

    private record Row(Integer id, String name, BigDecimal amount) {
    }
}
