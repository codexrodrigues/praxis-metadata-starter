package org.praxisplatform.uischema.exporting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executor padrao que resolve campos efetivos e delega ao engine do formato solicitado.
 */
public class DefaultCollectionExportExecutor implements CollectionExportExecutor {

    private final List<CollectionExportEngine> engines;

    public DefaultCollectionExportExecutor(List<CollectionExportEngine> engines) {
        this.engines = engines == null ? List.of() : List.copyOf(engines);
    }

    @Override
    public <T> CollectionExportResult export(
            CollectionExportRequest<?> request,
            List<T> rows,
            List<CollectionExportField> defaultFields,
            CollectionExportValueResolver<T> valueResolver,
            Map<String, Object> metadata
    ) {
        Objects.requireNonNull(valueResolver, "valueResolver must not be null");
        CollectionExportRequest<?> effectiveRequest = request == null
                ? new CollectionExportRequest<>(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                : request;
        List<CollectionExportField> fields = resolveFields(effectiveRequest.fields(), defaultFields);
        CollectionExportEngine engine = engines.stream()
                .filter(candidate -> candidate.supports(effectiveRequest.format()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported collection export format: " + effectiveRequest.format().value()
                ));
        return engine.export(
                effectiveRequest,
                rows == null ? List.of() : rows,
                fields,
                valueResolver,
                metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }

    private List<CollectionExportField> resolveFields(
            List<CollectionExportField> requestedFields,
            List<CollectionExportField> defaultFields
    ) {
        List<CollectionExportField> defaults = defaultFields == null ? List.of() : defaultFields;
        if (requestedFields == null || requestedFields.isEmpty()) {
            return defaults;
        }

        Map<String, CollectionExportField> defaultsByKey = new LinkedHashMap<>();
        for (CollectionExportField field : defaults) {
            putIfPresent(defaultsByKey, field.key(), field);
            putIfPresent(defaultsByKey, field.valuePath(), field);
        }

        List<CollectionExportField> resolved = requestedFields.stream()
                .filter(Objects::nonNull)
                .filter(field -> field.exportable() != Boolean.FALSE && field.visible() != Boolean.FALSE)
                .map(field -> mergeWithDefault(field, defaultsByKey))
                .filter(Objects::nonNull)
                .toList();
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("No requested export fields are supported by this resource.");
        }
        return resolved;
    }

    private CollectionExportField mergeWithDefault(
            CollectionExportField requested,
            Map<String, CollectionExportField> defaultsByKey
    ) {
        CollectionExportField canonical = defaultsByKey.get(fieldKey(requested));
        if (canonical == null) {
            return null;
        }
        return new CollectionExportField(
                canonical.key(),
                requested.label() == null || requested.label().isBlank() ? canonical.label() : requested.label(),
                requested.visible(),
                requested.exportable(),
                requested.type() == null || requested.type().isBlank() ? canonical.type() : requested.type(),
                canonical.valuePath()
        );
    }

    private String fieldKey(CollectionExportField field) {
        if (field == null) {
            return "";
        }
        if (field.valuePath() != null && !field.valuePath().isBlank()) {
            return field.valuePath();
        }
        return field.key() == null ? "" : field.key();
    }

    private void putIfPresent(
            Map<String, CollectionExportField> fields,
            String key,
            CollectionExportField field
    ) {
        if (key != null && !key.isBlank()) {
            fields.putIfAbsent(key, field);
        }
    }
}
