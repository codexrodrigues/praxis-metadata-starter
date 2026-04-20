package org.praxisplatform.uischema.exporting;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Engine canonico para CSV RFC-4180-like, com protecao basica contra formula injection.
 */
public class CsvCollectionExportEngine extends AbstractTabularCollectionExportEngine {

    public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";

    @Override
    public boolean supports(CollectionExportFormat format) {
        return format == CollectionExportFormat.CSV;
    }

    @Override
    public <T> CollectionExportResult export(
            CollectionExportRequest<?> request,
            List<T> rows,
            List<CollectionExportField> fields,
            CollectionExportValueResolver<T> valueResolver,
            Map<String, Object> metadata
    ) {
        StringBuilder csv = new StringBuilder();
        if (request == null || request.includeHeaders() != Boolean.FALSE) {
            appendCsvLine(csv, fields.stream().map(this::columnLabel).toList());
        }
        for (T row : rows) {
            appendCsvLine(csv, fields.stream()
                    .map(field -> normalizeValue(valueResolver.resolve(row, field)))
                    .toList());
        }
        return new CollectionExportResult(
                CollectionExportStatus.COMPLETED,
                CollectionExportFormat.CSV,
                request == null ? CollectionExportScope.AUTO : request.scope(),
                csv.toString().getBytes(StandardCharsets.UTF_8),
                resolveFileName(request, "csv"),
                CONTENT_TYPE,
                null,
                null,
                rowCount(rows),
                List.of(),
                metadata
        );
    }

    private void appendCsvLine(StringBuilder csv, List<?> values) {
        if (!csv.isEmpty()) {
            csv.append("\r\n");
        }
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(quoteCsv(values.get(index)));
        }
    }

    private String quoteCsv(Object value) {
        String text = value == null ? "" : value.toString();
        if (startsWithFormula(text)) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private boolean startsWithFormula(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        if (index >= text.length()) {
            return false;
        }
        return List.of('=', '+', '-', '@').contains(text.charAt(index));
    }
}
