package org.praxisplatform.uischema.exporting;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Engine canonico para CSV RFC-4180-like, com protecao basica contra formula injection.
 */
public class CsvCollectionExportEngine extends AbstractTabularCollectionExportEngine {

    public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";
    private static final String DEFAULT_DELIMITER = ",";
    private static final String DEFAULT_LINE_ENDING = "\r\n";

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
        CollectionExportCsvOptions options = request == null || request.formatOptions() == null
                ? null
                : request.formatOptions().csv();
        String delimiter = resolveDelimiter(options);
        String lineEnding = resolveLineEnding(options);
        if (resolveIncludeSepDirective(options)) {
            csv.append("sep=").append(delimiter);
        }
        if (request == null || request.includeHeaders() != Boolean.FALSE) {
            appendCsvLine(csv, fields.stream().map(this::columnLabel).toList(), delimiter, lineEnding);
        }
        for (T row : rows) {
            appendCsvLine(csv, fields.stream()
                    .map(field -> materializeValue(valueResolver.resolve(row, field), field, request))
                    .toList(), delimiter, lineEnding);
        }
        String content = csv.toString();
        if (resolveIncludeBom(options)) {
            content = "\uFEFF" + content;
        }
        String contentType = resolveContentType(options);
        return new CollectionExportResult(
                CollectionExportStatus.COMPLETED,
                CollectionExportFormat.CSV,
                request == null ? CollectionExportScope.AUTO : request.scope(),
                content.getBytes(resolveCharset(options)),
                resolveFileName(request, "csv"),
                contentType,
                null,
                null,
                rowCount(rows),
                List.of(),
                metadata
        );
    }

    private void appendCsvLine(StringBuilder csv, List<?> values, String delimiter, String lineEnding) {
        if (!csv.isEmpty()) {
            csv.append(lineEnding);
        }
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(delimiter);
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

    private String resolveDelimiter(CollectionExportCsvOptions options) {
        String delimiter = options == null ? null : options.delimiter();
        if (delimiter == null || delimiter.isBlank()) {
            return Boolean.TRUE.equals(options == null ? null : options.excelCompatibility())
                    ? ";"
                    : DEFAULT_DELIMITER;
        }
        return delimiter;
    }

    private String resolveLineEnding(CollectionExportCsvOptions options) {
        String lineEnding = options == null ? null : options.lineEnding();
        if (lineEnding == null || lineEnding.isBlank() || "crlf".equalsIgnoreCase(lineEnding)) {
            return DEFAULT_LINE_ENDING;
        }
        if ("lf".equalsIgnoreCase(lineEnding)) {
            return "\n";
        }
        return lineEnding;
    }

    private boolean resolveIncludeBom(CollectionExportCsvOptions options) {
        return Boolean.TRUE.equals(options == null ? null : options.includeBom())
                || Boolean.TRUE.equals(options == null ? null : options.excelCompatibility());
    }

    private boolean resolveIncludeSepDirective(CollectionExportCsvOptions options) {
        if (Boolean.TRUE.equals(options == null ? null : options.includeSepDirective())) {
            return true;
        }
        return Boolean.TRUE.equals(options == null ? null : options.excelCompatibility())
                && ";".equals(resolveDelimiter(options));
    }

    private Charset resolveCharset(CollectionExportCsvOptions options) {
        String encoding = options == null ? null : options.encoding();
        return encoding == null || encoding.isBlank()
                ? StandardCharsets.UTF_8
                : Charset.forName(encoding.trim());
    }

    private String resolveContentType(CollectionExportCsvOptions options) {
        return "text/csv; charset=" + resolveCharset(options).name();
    }
}
