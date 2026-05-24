package org.praxisplatform.uischema.exporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void exportsCsvWithGovernedFormattingAndExcelFriendlyDialect() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "employees",
                "/api/employees",
                CollectionExportFormat.CSV,
                CollectionExportScope.CURRENT_PAGE,
                null,
                List.of(),
                null,
                null,
                null,
                Map.of(),
                true,
                true,
                null,
                "employees.csv",
                new CollectionExportFormatOptions(
                        new CollectionExportCsvOptions(";", "UTF-8", true, "crlf", true, true),
                        null
                ),
                new CollectionExportLocalization("pt-BR", "America/Sao_Paulo"),
                Map.of()
        );
        List<CollectionExportField> fields = List.of(
                new CollectionExportField("name", "Nome", true, true, "string", "name"),
                new CollectionExportField(
                        "salary",
                        "Salario",
                        true,
                        true,
                        "currency",
                        "salary",
                        null,
                        new CollectionExportFieldPresentation(
                                "currency",
                                null,
                                "BRL",
                                "pt-BR",
                                null,
                                null,
                                null,
                                null
                        )
                ),
                new CollectionExportField(
                        "admission",
                        "Admissao",
                        true,
                        true,
                        "date",
                        "admission",
                        "dd/MM/yyyy",
                        null
                ),
                new CollectionExportField(
                        "active",
                        "Ativo",
                        true,
                        true,
                        "boolean",
                        "active",
                        null,
                        new CollectionExportFieldPresentation(
                                "boolean",
                                null,
                                null,
                                "pt-BR",
                                null,
                                "Ativo",
                                "Inativo",
                                null
                        )
                )
        );

        CollectionExportResult result = executor.export(
                request,
                List.of(new Employee("Sol Drax", new BigDecimal("41000.00"), LocalDate.of(2022, 6, 13), true)),
                fields,
                this::employeeValueFor,
                Map.of()
        );

        assertEquals("text/csv; charset=UTF-8", result.contentType());
        assertEquals(
                "\uFEFFsep=;\r\n\"Nome\";\"Salario\";\"Admissao\";\"Ativo\"\r\n"
                        + "\"Sol Drax\";\"R$\u00A041.000,00\";\"13/06/2022\";\"Ativo\"",
                new String(result.content(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void exportsJsonWithGovernedFormattingWhenRequested() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "employees",
                "/api/employees",
                CollectionExportFormat.JSON,
                CollectionExportScope.CURRENT_PAGE,
                null,
                List.of(),
                null,
                null,
                null,
                Map.of(),
                true,
                true,
                null,
                "employees.json",
                null,
                new CollectionExportLocalization("pt-BR", "America/Sao_Paulo"),
                Map.of()
        );
        List<CollectionExportField> fields = List.of(
                new CollectionExportField(
                        "salary",
                        "Salario",
                        true,
                        true,
                        "currency",
                        "salary",
                        null,
                        new CollectionExportFieldPresentation("currency", null, "BRL", "pt-BR", null, null, null, null)
                ),
                new CollectionExportField("admission", "Admissao", true, true, "date", "admission", "dd/MM/yyyy", null)
        );

        CollectionExportResult result = executor.export(
                request,
                List.of(new Employee("Sol Drax", new BigDecimal("41000.00"), LocalDate.of(2022, 6, 13), true)),
                fields,
                this::employeeValueFor,
                Map.of()
        );

        assertEquals("[{\"salary\":\"R$\u00A041.000,00\",\"admission\":\"13/06/2022\"}]",
                new String(result.content(), StandardCharsets.UTF_8));
    }

    @Test
    void keepsRawValuesWhenFormattingIsOmitted() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "employees",
                "/api/employees",
                CollectionExportFormat.JSON,
                CollectionExportScope.CURRENT_PAGE,
                null,
                List.of(),
                null,
                null,
                null,
                Map.of(),
                true,
                null,
                null,
                "employees.json",
                null,
                new CollectionExportLocalization("pt-BR", "America/Sao_Paulo"),
                Map.of()
        );
        List<CollectionExportField> fields = List.of(
                new CollectionExportField(
                        "salary",
                        "Salario",
                        true,
                        true,
                        "currency",
                        "salary",
                        null,
                        new CollectionExportFieldPresentation("currency", null, "BRL", "pt-BR", null, null, null, null)
                ),
                new CollectionExportField("admission", "Admissao", true, true, "date", "admission", "dd/MM/yyyy", null)
        );

        CollectionExportResult result = executor.export(
                request,
                List.of(new Employee("Sol Drax", new BigDecimal("41000.00"), LocalDate.of(2022, 6, 13), true)),
                fields,
                this::employeeValueFor,
                Map.of()
        );

        assertEquals("[{\"salary\":\"41000.00\",\"admission\":\"2022-06-13\"}]",
                new String(result.content(), StandardCharsets.UTF_8));
    }

    @Test
    void preservesCanonicalPresentationWhenRequestTriesToOverrideFieldSemantics() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "orders",
                "/api/orders",
                CollectionExportFormat.CSV,
                CollectionExportScope.SELECTED,
                null,
                List.of(new CollectionExportField(
                        "amount",
                        "Amount",
                        true,
                        true,
                        "currency",
                        "amount",
                        "dd/MM/yyyy",
                        new CollectionExportFieldPresentation("currency", null, "BRL", "pt-BR", null, null, null, null)
                )),
                null,
                null,
                null,
                Map.of(),
                true,
                true,
                null,
                "orders.csv",
                null,
                new CollectionExportLocalization("pt-BR", "America/Sao_Paulo"),
                Map.of()
        );

        CollectionExportResult result = executor.export(
                request,
                List.of(new Row(1, "Ana", new BigDecimal("123.45"))),
                defaultFields,
                this::valueFor,
                Map.of()
        );

        assertEquals("\"Amount\"\r\n\"123.45\"", new String(result.content(), StandardCharsets.UTF_8));
    }

    @Test
    void excludesHiddenFieldsFromDefaultExportButKeepsThemRequestable() {
        List<CollectionExportField> fields = List.of(
                new CollectionExportField("name", "Name", true, true, "string", "name"),
                new CollectionExportField("amount", "Amount", false, true, "number", "amount")
        );
        CollectionExportRequest<?> defaultRequest = new CollectionExportRequest<>(
                "table", "orders", "/api/orders", CollectionExportFormat.CSV, CollectionExportScope.ALL,
                null, List.of(), null, null, null, Map.of(), true, false, null, "orders.csv", Map.of()
        );

        CollectionExportResult defaultResult = executor.export(
                defaultRequest,
                List.of(new Row(1, "Ana", new BigDecimal("123.45"))),
                fields,
                this::valueFor,
                Map.of()
        );

        assertEquals("\"Name\"\r\n\"Ana\"", new String(defaultResult.content(), StandardCharsets.UTF_8));

        CollectionExportRequest<?> explicitRequest = new CollectionExportRequest<>(
                "table", "orders", "/api/orders", CollectionExportFormat.CSV, CollectionExportScope.ALL,
                null,
                List.of(new CollectionExportField("amount", "Amount", true, true, "number", "amount")),
                null, null, null, Map.of(), true, false, null, "orders.csv", Map.of()
        );

        CollectionExportResult explicitResult = executor.export(
                explicitRequest,
                List.of(new Row(1, "Ana", new BigDecimal("123.45"))),
                fields,
                this::valueFor,
                Map.of()
        );

        assertEquals("\"Amount\"\r\n\"123.45\"", new String(explicitResult.content(), StandardCharsets.UTF_8));
    }

    @Test
    void deserializesGovernedExportRequestShapeFromJson() throws Exception {
        String json = """
                {
                  "format": "csv",
                  "scope": "currentPage",
                  "includeHeaders": true,
                  "applyFormatting": true,
                  "fields": [
                    {
                      "key": "salary",
                      "label": "Salario",
                      "type": "currency",
                      "valuePath": "salary",
                      "format": "currency",
                      "presentation": {
                        "semanticType": "currency",
                        "currency": "BRL",
                        "locale": "pt-BR",
                        "timeZone": "America/Sao_Paulo"
                      }
                    }
                  ],
                  "formatOptions": {
                    "csv": {
                      "excelCompatibility": true
                    }
                  },
                  "localization": {
                    "locale": "pt-BR",
                    "timeZone": "America/Sao_Paulo"
                  }
                }
                """;

        CollectionExportRequest<?> request = new ObjectMapper().readValue(json, CollectionExportRequest.class);

        assertEquals(CollectionExportFormat.CSV, request.format());
        assertEquals(CollectionExportScope.CURRENT_PAGE, request.scope());
        assertEquals("pt-BR", request.localization().locale());
        assertEquals("America/Sao_Paulo", request.localization().timeZone());
        assertEquals(Boolean.TRUE, request.formatOptions().csv().excelCompatibility());
        assertEquals("BRL", request.fields().get(0).presentation().currency());
        assertNull(request.formatOptions().excel());
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
        assertEquals("One or more requested export fields are not supported by this resource.", error.getMessage());
    }

    @Test
    void rejectsRequestedFieldsWhenAnyFieldIsUnsupported() {
        CollectionExportRequest<?> request = new CollectionExportRequest<>(
                "table",
                "orders",
                "/api/orders",
                CollectionExportFormat.CSV,
                CollectionExportScope.SELECTED,
                null,
                List.of(
                        new CollectionExportField("name", "Name", true, true, "string", "name"),
                        new CollectionExportField("unknown", "Unknown", true, true, "string", "unknown")
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

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> executor.export(
                request,
                List.of(new Row(1, "Ana", BigDecimal.ONE)),
                defaultFields,
                this::valueFor,
                Map.of()
        ));
        assertEquals("One or more requested export fields are not supported by this resource.", error.getMessage());
    }

    private Object valueFor(Row row, CollectionExportField field) {
        return switch (field.valuePath()) {
            case "id" -> row.id();
            case "name" -> row.name();
            case "amount" -> row.amount();
            default -> null;
        };
    }

    private Object employeeValueFor(Employee row, CollectionExportField field) {
        return switch (field.valuePath()) {
            case "name" -> row.name();
            case "salary" -> row.salary();
            case "admission" -> row.admission();
            case "active" -> row.active();
            default -> null;
        };
    }

    private record Row(Integer id, String name, BigDecimal amount) {
    }

    private record Employee(String name, BigDecimal salary, LocalDate admission, Boolean active) {
    }
}
