package org.praxisplatform.uischema.exporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine canonico para JSON tabular, preservando a ordem dos campos exportados.
 */
public class JsonCollectionExportEngine extends AbstractTabularCollectionExportEngine {

    public static final String CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;

    public JsonCollectionExportEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(CollectionExportFormat format) {
        return format == CollectionExportFormat.JSON;
    }

    @Override
    public <T> CollectionExportResult export(
            CollectionExportRequest<?> request,
            List<T> rows,
            List<CollectionExportField> fields,
            CollectionExportValueResolver<T> valueResolver,
            Map<String, Object> metadata
    ) {
        List<Map<String, Object>> payload = rows.stream()
                .map(row -> toJsonRow(row, fields, valueResolver))
                .toList();
        try {
            return new CollectionExportResult(
                    CollectionExportStatus.COMPLETED,
                    CollectionExportFormat.JSON,
                    request == null ? CollectionExportScope.AUTO : request.scope(),
                    objectMapper.writeValueAsBytes(payload),
                    resolveFileName(request, "json"),
                    CONTENT_TYPE,
                    null,
                    null,
                    rowCount(rows),
                    List.of(),
                    metadata
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize collection export as JSON", ex);
        }
    }

    private <T> Map<String, Object> toJsonRow(
            T row,
            List<CollectionExportField> fields,
            CollectionExportValueResolver<T> valueResolver
    ) {
        Map<String, Object> jsonRow = new LinkedHashMap<>();
        for (CollectionExportField field : fields) {
            jsonRow.put(columnKey(field), normalizeValue(valueResolver.resolve(row, field)));
        }
        return jsonRow;
    }
}
