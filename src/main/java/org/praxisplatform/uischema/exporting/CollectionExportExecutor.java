package org.praxisplatform.uischema.exporting;

import java.util.List;
import java.util.Map;

/**
 * Orquestra a escolha do engine de exportacao sem assumir como cada recurso obtem seus dados.
 */
public interface CollectionExportExecutor {

    <T> CollectionExportResult export(
            CollectionExportRequest<?> request,
            List<T> rows,
            List<CollectionExportField> defaultFields,
            CollectionExportValueResolver<T> valueResolver,
            Map<String, Object> metadata
    );
}
