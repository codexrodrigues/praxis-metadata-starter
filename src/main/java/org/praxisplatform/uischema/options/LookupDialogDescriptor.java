package org.praxisplatform.uischema.options;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rich selection dialog contract published under {@code x-ui.optionSource.dialog}.
 */
public record LookupDialogDescriptor(
        Boolean enabled,
        String title,
        String size,
        Boolean previewPanel,
        Boolean allowColumnChooser,
        Boolean allowSavedViews,
        List<LookupResultColumnDescriptor> resultColumns,
        String openActionLabel,
        String applyActionLabel,
        String cancelActionLabel
) {

    private static final Set<String> ALLOWED_SIZES = Set.of("sm", "md", "lg", "xl", "full");

    public LookupDialogDescriptor(
            Boolean enabled,
            String title,
            String size,
            Boolean previewPanel,
            List<LookupResultColumnDescriptor> resultColumns
    ) {
        this(enabled, title, size, previewPanel, null, null, resultColumns, null, null, null);
    }

    public LookupDialogDescriptor {
        title = normalize(title);
        size = normalize(size);
        if (size != null && !ALLOWED_SIZES.contains(size)) {
            throw new IllegalArgumentException("Unsupported lookup dialog size: " + size);
        }
        resultColumns = normalizeColumns(resultColumns);
        openActionLabel = normalize(openActionLabel);
        applyActionLabel = normalize(applyActionLabel);
        cancelActionLabel = normalize(cancelActionLabel);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return enabled == null
                && title == null
                && size == null
                && previewPanel == null
                && allowColumnChooser == null
                && allowSavedViews == null
                && resultColumns.isEmpty()
                && openActionLabel == null
                && applyActionLabel == null
                && cancelActionLabel == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "enabled", enabled);
        putIfNotNull(metadata, "title", title);
        putIfNotNull(metadata, "size", size);
        putIfNotNull(metadata, "previewPanel", previewPanel);
        putIfNotNull(metadata, "allowColumnChooser", allowColumnChooser);
        putIfNotNull(metadata, "allowSavedViews", allowSavedViews);
        if (!resultColumns.isEmpty()) {
            metadata.put("resultColumns", resultColumns.stream()
                    .map(LookupResultColumnDescriptor::toMetadataMap)
                    .toList());
        }
        putIfNotNull(metadata, "openActionLabel", openActionLabel);
        putIfNotNull(metadata, "applyActionLabel", applyActionLabel);
        putIfNotNull(metadata, "cancelActionLabel", cancelActionLabel);
        return metadata;
    }

    public static final class Builder {
        private Boolean enabled;
        private String title;
        private String size;
        private Boolean previewPanel;
        private Boolean allowColumnChooser;
        private Boolean allowSavedViews;
        private final List<LookupResultColumnDescriptor> resultColumns = new ArrayList<>();
        private String openActionLabel;
        private String applyActionLabel;
        private String cancelActionLabel;

        public Builder enabled(Boolean value) { enabled = value; return this; }
        public Builder title(String value) { title = value; return this; }
        public Builder size(String value) { size = value; return this; }
        public Builder previewPanel(Boolean value) { previewPanel = value; return this; }
        public Builder allowColumnChooser(Boolean value) { allowColumnChooser = value; return this; }
        public Builder allowSavedViews(Boolean value) { allowSavedViews = value; return this; }

        public Builder resultColumns(List<LookupResultColumnDescriptor> columns) {
            resultColumns.clear();
            if (columns != null) {
                columns.stream().filter(c -> c != null).forEach(resultColumns::add);
            }
            return this;
        }

        public Builder addResultColumn(LookupResultColumnDescriptor column) {
            if (column != null) {
                resultColumns.add(column);
            }
            return this;
        }

        public Builder openActionLabel(String value) { openActionLabel = value; return this; }
        public Builder applyActionLabel(String value) { applyActionLabel = value; return this; }
        public Builder cancelActionLabel(String value) { cancelActionLabel = value; return this; }

        public LookupDialogDescriptor build() {
            return new LookupDialogDescriptor(
                    enabled,
                    title,
                    size,
                    previewPanel,
                    allowColumnChooser,
                    allowSavedViews,
                    resultColumns,
                    openActionLabel,
                    applyActionLabel,
                    cancelActionLabel
            );
        }
    }

    private static List<LookupResultColumnDescriptor> normalizeColumns(List<LookupResultColumnDescriptor> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(col -> col != null)
                .toList();
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
