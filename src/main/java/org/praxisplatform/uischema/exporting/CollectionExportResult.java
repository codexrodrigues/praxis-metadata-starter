package org.praxisplatform.uischema.exporting;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Resultado produzido por um recurso que implementa exportacao.
 */
public record CollectionExportResult(
        CollectionExportStatus status,
        CollectionExportFormat format,
        CollectionExportScope scope,
        byte[] content,
        String fileName,
        String contentType,
        String downloadUrl,
        String jobId,
        Long rowCount,
        List<String> warnings,
        Map<String, Object> metadata
) {
    public CollectionExportResult {
        status = status == null ? CollectionExportStatus.COMPLETED : status;
        format = format == null ? CollectionExportFormat.CSV : format;
        scope = scope == null ? CollectionExportScope.AUTO : scope;
        content = content == null ? new byte[0] : content.clone();
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public CollectionExportResult(
            byte[] content,
            String fileName,
            String contentType,
            Long rowCount,
            Map<String, Object> metadata
    ) {
        this(
                CollectionExportStatus.COMPLETED,
                CollectionExportFormat.CSV,
                CollectionExportScope.AUTO,
                content,
                fileName,
                contentType,
                null,
                null,
                rowCount,
                List.of(),
                metadata
        );
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public static CollectionExportResult text(String content, String fileName, String contentType) {
        return new CollectionExportResult(
                content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8),
                fileName,
                contentType,
                null,
                Map.of()
        );
    }

    public static CollectionExportResult deferred(
            CollectionExportFormat format,
            CollectionExportScope scope,
            String downloadUrl,
            String jobId,
            String fileName,
            Map<String, Object> metadata
    ) {
        return new CollectionExportResult(
                CollectionExportStatus.DEFERRED,
                format,
                scope,
                null,
                fileName,
                null,
                downloadUrl,
                jobId,
                null,
                List.of(),
                metadata
        );
    }

    public boolean deferredStatus() {
        return status == CollectionExportStatus.DEFERRED;
    }
}
