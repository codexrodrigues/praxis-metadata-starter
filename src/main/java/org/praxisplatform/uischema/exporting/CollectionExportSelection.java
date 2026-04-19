package org.praxisplatform.uischema.exporting;

import java.util.List;

/**
 * Estado de selecao do componente de colecao no momento da exportacao.
 */
public record CollectionExportSelection(
        String mode,
        String keyField,
        List<Object> selectedKeys,
        Boolean allMatchingSelected,
        List<Object> excludedKeys
) {
    public CollectionExportSelection {
        selectedKeys = selectedKeys == null ? List.of() : List.copyOf(selectedKeys);
        excludedKeys = excludedKeys == null ? List.of() : List.copyOf(excludedKeys);
    }
}
