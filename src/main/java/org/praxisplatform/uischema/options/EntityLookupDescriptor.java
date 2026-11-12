package org.praxisplatform.uischema.options;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.uischema.annotation.ResourceIdentity;

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

    /**
     * Starts a rich directory descriptor by auto-deriving {@code codePropertyPath},
     * {@code descriptionPropertyPaths} ({@code titleField}, {@code subtitleField}, and
     * {@code metadataFields}), and {@link LookupDisplayDescriptor} field labels/icons/formats
     * directly from {@link ResourceIdentity} and {@code @UISchema} on {@code responseDtoClass}
     * ({@code PLAT-EVO-09}).
     *
     * @param entityKey canonical resource key of the lookup target
     * @param responseDtoClass target {@code ResponseDTO} class annotated with {@link ResourceIdentity}
     * @return pre-populated {@link DirectoryBuilder}
     */
    public static DirectoryBuilder directoryBuilderFromDto(String entityKey, Class<?> responseDtoClass) {
        Objects.requireNonNull(responseDtoClass, "responseDtoClass is required");
        ResourceIdentity identity = findResourceIdentity(responseDtoClass);
        if (identity == null) {
            throw new IllegalArgumentException(
                    "Missing @ResourceIdentity on DTO class " + responseDtoClass.getName());
        }
        String title = normalize(identity.titleField());
        String code = resolveBusinessCodeField(responseDtoClass, identity, title);
        if (code == null || title == null) {
            throw new IllegalArgumentException(
                    "@ResourceIdentity on " + responseDtoClass.getName() + " must declare non-blank keyField and titleField");
        }
        List<String> descriptions = new ArrayList<>();
        descriptions.add(title);
        String subtitle = normalize(identity.subtitleField());
        if (subtitle != null && !subtitle.equals(code) && !descriptions.contains(subtitle)) {
            descriptions.add(subtitle);
        }
        if (identity.metadataFields() != null) {
            for (String meta : identity.metadataFields()) {
                String clean = normalize(meta);
                if (clean != null && !clean.equals(code) && !descriptions.contains(clean)) {
                    descriptions.add(clean);
                }
            }
        }
        return new DirectoryBuilder(entityKey, code, descriptions).withDtoMetadata(responseDtoClass);
    }

    private static String resolveBusinessCodeField(
            Class<?> dtoClass, ResourceIdentity identity, String titleField) {
        String keyField = normalize(identity.keyField());
        String subtitleField = normalize(identity.subtitleField());
        if (subtitleField != null
                && !subtitleField.equals(titleField)
                && hasEnterpriseCodePreset(dtoClass, subtitleField)) {
            return subtitleField;
        }
        for (Class<?> curr = dtoClass; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                String name = f.getName();
                if (!name.equals(titleField) && isVisibleEnterpriseCode(
                        f.getAnnotation(org.praxisplatform.uischema.extension.annotation.UISchema.class))) {
                    return name;
                }
            }
        }
        return keyField;
    }

    private static boolean hasEnterpriseCodePreset(Class<?> dtoClass, String propertyName) {
        for (Class<?> curr = dtoClass; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            try {
                java.lang.reflect.Field f = curr.getDeclaredField(propertyName);
                if (isVisibleEnterpriseCode(
                        f.getAnnotation(org.praxisplatform.uischema.extension.annotation.UISchema.class))) {
                    return true;
                }
            } catch (NoSuchFieldException ignored) {
                // inspect getters and superclass
            }
            String getter = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
            try {
                java.lang.reflect.Method m = curr.getDeclaredMethod(getter);
                if (isVisibleEnterpriseCode(
                        m.getAnnotation(org.praxisplatform.uischema.extension.annotation.UISchema.class))) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // continue walking hierarchy
            }
        }
        return false;
    }

    private static boolean isVisibleEnterpriseCode(
            org.praxisplatform.uischema.extension.annotation.UISchema ui) {
        return ui != null
                && ui.preset() == org.praxisplatform.uischema.extension.annotation.UISchemaPreset.ENTERPRISE_CODE
                && (!ui.tableHidden() || !ui.formHidden());
    }

    private static ResourceIdentity findResourceIdentity(Class<?> dtoClass) {
        for (Class<?> curr = dtoClass; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            ResourceIdentity ann = curr.getAnnotation(ResourceIdentity.class);
            if (ann != null) {
                return ann;
            }
        }
        return null;
    }

    public static final class DirectoryBuilder {
        private final String entityKey, code;
        private final List<String> descriptions;
        private String status, disabled, disabledReason;
        private List<String> search;
        private Map<String, String> dependencies = Map.of();
        private Map<String, String> statusLabels = Map.of();
        private Class<?> responseDtoClass;
        private LookupSelectionPolicy selection;
        private LookupCapabilities capabilities = LookupCapabilities.standardDirectory();
        private LookupDetailDescriptor detail;
        private LookupDisplayDescriptor display;
        private LookupFilteringDescriptor filtering;

        private DirectoryBuilder(String entityKey, String code, List<String> descriptions) {
            if (normalize(entityKey) == null || normalize(code) == null || normalizeList(descriptions).isEmpty()) {
                throw new IllegalArgumentException("Directory requires entity, code and description paths");
            }
            this.entityKey = entityKey;
            this.code = code;
            this.descriptions = normalizeList(descriptions);
            this.search = java.util.stream.Stream.concat(java.util.stream.Stream.of(code), this.descriptions.stream()).distinct().toList();
        }

        public DirectoryBuilder status(String path) { status = normalize(path); return this; }

        public DirectoryBuilder statusLabels(Map<String, String> values) {
            statusLabels = values == null ? Map.of() : normalizeMap(values);
            return this;
        }

        public DirectoryBuilder withDtoMetadata(Class<?> dtoClass) {
            responseDtoClass = Objects.requireNonNull(dtoClass, "dtoClass");
            return this;
        }

        public DirectoryBuilder disabled(String path, String reasonPath) { disabled = path; disabledReason = reasonPath; return this; }
        public DirectoryBuilder searchPaths(String... paths) { search = List.of(paths); return this; }
        public DirectoryBuilder dependencies(Map<String, String> values) { dependencies = Map.copyOf(values); return this; }
        public DirectoryBuilder selectionPolicy(LookupSelectionPolicy value) { selection = Objects.requireNonNull(value); return this; }
        public DirectoryBuilder capabilities(LookupCapabilities value) { capabilities = Objects.requireNonNull(value); return this; }
        public DirectoryBuilder detail(LookupDetailDescriptor value) { detail = Objects.requireNonNull(value); return this; }

        public DirectoryBuilder detailSurface(String resourceKey, String surfaceId, String previewSurfaceId) {
            detail = new LookupDetailDescriptor(null, null, "drawer", "surface", surfaceId, "drawer", null,
                    "view", normalize(resourceKey) != null ? normalize(resourceKey) : entityKey, previewSurfaceId);
            return this;
        }

        public DirectoryBuilder detailSurface(String surfaceId, String previewSurfaceId) {
            detail = new LookupDetailDescriptor(null, null, "drawer", "surface", surfaceId, "drawer", null,
                    "view", entityKey, previewSurfaceId);
            return this;
        }

        public DirectoryBuilder display(LookupDisplayDescriptor value) { display = Objects.requireNonNull(value); return this; }
        public DirectoryBuilder filtering(LookupFilteringDescriptor value) { filtering = Objects.requireNonNull(value); return this; }

        public EntityLookupDescriptor build() {
            LookupSelectionPolicy effectiveSelection = selection != null ? selection : new LookupSelectionPolicy(null, status,
                    List.of(), List.of(), true, "This record is unavailable for selection", "Select an available record");
            LookupDetailDescriptor effectiveDetail = detail != null ? detail : new LookupDetailDescriptor(null, null, "drawer",
                    "surface", "detail", "drawer", null, "view", entityKey, "detail");
            LookupDisplayDescriptor effectiveDisplay;
            if (display != null) {
                effectiveDisplay = display;
            } else if (responseDtoClass != null) {
                List<String> curatedPaths = new ArrayList<>();
                curatedPaths.add(code);
                for (String desc : descriptions) {
                    if (!curatedPaths.contains(desc)) {
                        curatedPaths.add(desc);
                    }
                }
                if (status != null && !curatedPaths.contains(status)) {
                    curatedPaths.add(status);
                }
                List<LookupDisplayFieldDescriptor> dtoFields = LookupDisplayDescriptor.fieldsFromDto(responseDtoClass, curatedPaths);
                effectiveDisplay = new LookupDisplayDescriptor("directory", "form", "comfortable", "inline", "list",
                        descriptions.getFirst(), dtoFields, descriptions, List.of(), null,
                        false, true, true, status != null, false, true, statusLabels, 0);
            } else {
                List<LookupDisplayFieldDescriptor> fields = new ArrayList<>();
                fields.add(new LookupDisplayFieldDescriptor(code, code, null, null, null, null, null));
                descriptions.forEach(path -> fields.add(new LookupDisplayFieldDescriptor(path, path, null, null, null, null, null)));
                if (status != null) fields.add(new LookupDisplayFieldDescriptor(status, status, null, null, "badge", null, null));
                effectiveDisplay = new LookupDisplayDescriptor("directory", null,
                        null, null, null, descriptions.getFirst(), fields, descriptions, List.of(), null,
                        false, true, true, status != null, false, true, statusLabels, null);
            }
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
