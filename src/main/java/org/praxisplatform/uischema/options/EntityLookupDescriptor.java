package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity semantics attached to an option-source when it represents a business lookup.
 */
public record EntityLookupDescriptor(
        String entityKey,
        String codePropertyPath,
        List<String> descriptionPropertyPaths,
        String statusPropertyPath,
        String disabledPropertyPath,
        String disabledReasonPropertyPath,
        List<String> searchPropertyPaths,
        Map<String, String> dependencyFilterMap,
        LookupSelectionPolicy selectionPolicy,
        LookupCapabilities capabilities,
        LookupDetailDescriptor detail,
        LookupDisplayDescriptor display,
        LookupFilteringDescriptor filtering
) {

    public EntityLookupDescriptor(
            String entityKey,
            String codePropertyPath,
            List<String> descriptionPropertyPaths,
            String statusPropertyPath,
            String disabledPropertyPath,
            String disabledReasonPropertyPath,
            List<String> searchPropertyPaths,
            Map<String, String> dependencyFilterMap,
            LookupSelectionPolicy selectionPolicy,
            LookupCapabilities capabilities,
            LookupDetailDescriptor detail
    ) {
        this(
                entityKey,
                codePropertyPath,
                descriptionPropertyPaths,
                statusPropertyPath,
                disabledPropertyPath,
                disabledReasonPropertyPath,
                searchPropertyPaths,
                dependencyFilterMap,
                selectionPolicy,
                capabilities,
                detail,
                null,
                null
        );
    }

    public EntityLookupDescriptor(
            String entityKey,
            String codePropertyPath,
            List<String> descriptionPropertyPaths,
            String statusPropertyPath,
            String disabledPropertyPath,
            String disabledReasonPropertyPath,
            List<String> searchPropertyPaths,
            Map<String, String> dependencyFilterMap,
            LookupSelectionPolicy selectionPolicy,
            LookupCapabilities capabilities,
            LookupDetailDescriptor detail,
            LookupFilteringDescriptor filtering
    ) {
        this(
                entityKey,
                codePropertyPath,
                descriptionPropertyPaths,
                statusPropertyPath,
                disabledPropertyPath,
                disabledReasonPropertyPath,
                searchPropertyPaths,
                dependencyFilterMap,
                selectionPolicy,
                capabilities,
                detail,
                null,
                filtering
        );
    }

    public EntityLookupDescriptor {
        entityKey = normalize(entityKey);
        codePropertyPath = normalize(codePropertyPath);
        descriptionPropertyPaths = normalizeList(descriptionPropertyPaths);
        statusPropertyPath = normalize(statusPropertyPath);
        disabledPropertyPath = normalize(disabledPropertyPath);
        disabledReasonPropertyPath = normalize(disabledReasonPropertyPath);
        searchPropertyPaths = normalizeList(searchPropertyPaths);
        dependencyFilterMap = normalizeMap(dependencyFilterMap);
    }

    /** Starts a rich, read-only directory descriptor using explicit domain property paths. */
    public static DirectoryBuilder directoryBuilder(String entityKey, String codePropertyPath, String... descriptionPropertyPaths) {
        return new DirectoryBuilder(entityKey, codePropertyPath, List.of(descriptionPropertyPaths));
    }

    public static final class DirectoryBuilder {
        private final String entityKey, code;
        private final List<String> descriptions;
        private String status, disabled, disabledReason;
        private List<String> search;
        private Map<String, String> dependencies = Map.of();
        private LookupSelectionPolicy selection;
        private LookupCapabilities capabilities = LookupCapabilities.standardDirectory();
        private LookupDetailDescriptor detail;
        private LookupDisplayDescriptor display;
        private LookupFilteringDescriptor filtering;

        private DirectoryBuilder(String entityKey, String code, List<String> descriptions) {
            if (normalize(entityKey) == null || normalize(code) == null || normalizeList(descriptions).isEmpty())
                throw new IllegalArgumentException("Directory requires entity, code and description paths");
            this.entityKey = entityKey; this.code = code; this.descriptions = normalizeList(descriptions);
            this.search = java.util.stream.Stream.concat(java.util.stream.Stream.of(code), this.descriptions.stream()).distinct().toList();
        }
        public DirectoryBuilder status(String path) { status = normalize(path); return this; }
        public DirectoryBuilder disabled(String path, String reasonPath) { disabled = path; disabledReason = reasonPath; return this; }
        public DirectoryBuilder searchPaths(String... paths) { search = List.of(paths); return this; }
        public DirectoryBuilder dependencies(Map<String, String> values) { dependencies = Map.copyOf(values); return this; }
        public DirectoryBuilder selectionPolicy(LookupSelectionPolicy value) { selection = java.util.Objects.requireNonNull(value); return this; }
        public DirectoryBuilder capabilities(LookupCapabilities value) { capabilities = java.util.Objects.requireNonNull(value); return this; }
        public DirectoryBuilder detail(LookupDetailDescriptor value) { detail = java.util.Objects.requireNonNull(value); return this; }
        public DirectoryBuilder detailSurface(String resourceKey, String surfaceId, String previewSurfaceId) {
            detail = new LookupDetailDescriptor(null, null, "drawer", "surface", surfaceId, "drawer", null,
                    "view", normalize(resourceKey) != null ? normalize(resourceKey) : entityKey, previewSurfaceId); return this;
        }
        public DirectoryBuilder detailSurface(String surfaceId, String previewSurfaceId) {
            detail = new LookupDetailDescriptor(null, null, "drawer", "surface", surfaceId, "drawer", null,
                    "view", entityKey, previewSurfaceId); return this;
        }
        public DirectoryBuilder display(LookupDisplayDescriptor value) { display = java.util.Objects.requireNonNull(value); return this; }
        public DirectoryBuilder filtering(LookupFilteringDescriptor value) { filtering = java.util.Objects.requireNonNull(value); return this; }
        public EntityLookupDescriptor build() {
            LookupSelectionPolicy effectiveSelection = selection != null ? selection : new LookupSelectionPolicy(null, status,
                    List.of(), List.of(), true, "This record is unavailable for selection", "Select an available record");
            LookupDetailDescriptor effectiveDetail = detail != null ? detail : new LookupDetailDescriptor(null, null, "drawer",
                    "surface", "detail", "drawer", null, "view", entityKey, "detail");
            List<LookupDisplayFieldDescriptor> fields = new java.util.ArrayList<>();
            fields.add(new LookupDisplayFieldDescriptor(code, code, null, null, null, null, null));
            descriptions.forEach(path -> fields.add(new LookupDisplayFieldDescriptor(path, path, null, null, null, null, null)));
            if (status != null) fields.add(new LookupDisplayFieldDescriptor(status, status, null, null, "badge", null, null));
            LookupDisplayDescriptor effectiveDisplay = display != null ? display : new LookupDisplayDescriptor("directory", null,
                    null, null, null, descriptions.getFirst(), fields, descriptions, List.of(), null,
                    false, true, true, status != null, false, true, Map.of(), null);
            LookupFilteringDescriptor effectiveFiltering = filtering != null ? filtering : new LookupFilteringDescriptor(List.of(), Map.of(),
                    List.of(new LookupSortOption("code", code, "asc", null),
                            new LookupSortOption("description", descriptions.getFirst(), "asc", null)), "description",
                    List.of(), null, List.of(new LookupSearchStrategyDefinition("text", "descriptive-text", 1)));
            return new EntityLookupDescriptor(entityKey, code, descriptions, status, disabled, disabledReason, search,
                    dependencies, effectiveSelection, capabilities, effectiveDetail, effectiveDisplay, effectiveFiltering);
        }
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (entityKey != null) {
            metadata.put("entityKey", entityKey);
        }
        if (codePropertyPath != null) {
            metadata.put("codePropertyPath", codePropertyPath);
        }
        if (!descriptionPropertyPaths.isEmpty()) {
            metadata.put("descriptionPropertyPaths", descriptionPropertyPaths);
        }
        if (statusPropertyPath != null) {
            metadata.put("statusPropertyPath", statusPropertyPath);
        }
        if (disabledPropertyPath != null) {
            metadata.put("disabledPropertyPath", disabledPropertyPath);
        }
        if (disabledReasonPropertyPath != null) {
            metadata.put("disabledReasonPropertyPath", disabledReasonPropertyPath);
        }
        if (!searchPropertyPaths.isEmpty()) {
            metadata.put("searchPropertyPaths", searchPropertyPaths);
        }
        if (!dependencyFilterMap.isEmpty()) {
            metadata.put("dependencyFilterMap", dependencyFilterMap);
        }
        if (selectionPolicy != null && !selectionPolicy.isEmpty()) {
            metadata.put("selectionPolicy", selectionPolicy.toMetadataMap());
        }
        if (capabilities != null) {
            metadata.put("capabilities", capabilities.toMetadataMap());
        }
        if (detail != null && !detail.isEmpty()) {
            metadata.put("detail", detail.toMetadataMap());
        }
        if (display != null && !display.isEmpty()) {
            metadata.put("display", display.toMetadataMap());
        }
        if (filtering != null && !filtering.isEmpty()) {
            metadata.put("filtering", filtering.toMetadataMap());
        }
        return metadata;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(EntityLookupDescriptor::normalize)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private static Map<String, String> normalizeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = normalize(key);
            String normalizedValue = normalize(value);
            if (normalizedKey != null && normalizedValue != null) {
                normalized.put(normalizedKey, normalizedValue);
            }
        });
        return Map.copyOf(normalized);
    }
}
