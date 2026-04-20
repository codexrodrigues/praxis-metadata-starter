package org.praxisplatform.uischema.exporting;

import java.util.List;
import java.util.Map;

/**
 * Engine de serializacao para um formato canonico de exportacao de colecao.
 */
public interface CollectionExportEngine {

    boolean supports(CollectionExportFormat format);

    <T> CollectionExportResult export(
            CollectionExportRequest<?> request,
            List<T> rows,
            List<CollectionExportField> fields,
            CollectionExportValueResolver<T> valueResolver,
            Map<String, Object> metadata
    );
}
