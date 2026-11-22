package org.praxisplatform.uischema.options;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        LookupFilteringDescriptor filtering,
        LookupDialogDescriptor dialog,
        Class<?> sourceDtoClass
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
                null,
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
                filtering,
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
            LookupDisplayDescriptor display,
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
                display,
                filtering,
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
            LookupDisplayDescriptor display,
            LookupFilteringDescriptor filtering,
            LookupDialogDescriptor dialog
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
                display,
                filtering,
                dialog,
                null
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
        DirectoryBuilder builder = new DirectoryBuilder(entityKey, code, descriptions)
                .withDtoMetadata(responseDtoClass, code, descriptions, normalize(identity.keyField()));
        autoConfigureStatusFromDto(responseDtoClass, builder);
        return builder;
    }

    /**
     * Entity-aware variant of {@link #directoryBuilderFromDto(String, Class)} that resolves
     * flattened or aliased {@code ResponseDTO} fields to persistent JPA entity paths on
     * {@code entityClass} via {@link DtoEntityPathResolver} while keeping DTO field names in
     * {@link LookupDisplayFieldDescriptor#key()}.
     */
    public static DirectoryBuilder directoryBuilderFromDto(
            String entityKey,
            Class<?> responseDtoClass,
            Class<?> entityClass
    ) {
        Objects.requireNonNull(entityClass, "entityClass is required");
        return directoryBuilderFromDto(entityKey, responseDtoClass).withEntityClass(entityClass);
    }

    private static void autoConfigureStatusFromDto(Class<?> dtoClass, DirectoryBuilder builder) {
        record DetectedStatus(String propertyName, Class<? extends Enum<?>> optionEnumClass) {}
        DetectedStatus resourceStateCandidate = null;
        DetectedStatus optionEnumCandidate = null;

        for (Class<?> curr = dtoClass; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                boolean hasResourceState = f.isAnnotationPresent(org.praxisplatform.uischema.annotation.ResourceState.class);
                Class<? extends Enum<?>> enumClass = resolvePraxisUiOptionEnumClass(
                        f.getType(),
                        f.getAnnotation(org.praxisplatform.uischema.extension.annotation.UISchema.class)
                );
                if (hasResourceState && resourceStateCandidate == null) {
                    resourceStateCandidate = new DetectedStatus(f.getName(), enumClass);
                } else if (enumClass != null && optionEnumCandidate == null) {
                    optionEnumCandidate = new DetectedStatus(f.getName(), enumClass);
                }
            }
            for (java.lang.reflect.Method m : curr.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.isSynthetic() || m.isBridge()
                        || m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                    continue;
                }
                String propName = resolveGetterName(curr, m);
                if (propName == null) {
                    continue;
                }
                boolean hasResourceState = m.isAnnotationPresent(org.praxisplatform.uischema.annotation.ResourceState.class);
                Class<? extends Enum<?>> enumClass = resolvePraxisUiOptionEnumClass(
                        m.getReturnType(),
                        m.getAnnotation(org.praxisplatform.uischema.extension.annotation.UISchema.class)
                );
                if (hasResourceState && resourceStateCandidate == null) {
                    resourceStateCandidate = new DetectedStatus(propName, enumClass);
                } else if (enumClass != null && optionEnumCandidate == null) {
                    optionEnumCandidate = new DetectedStatus(propName, enumClass);
                }
            }
            if (curr.isRecord()) {
                for (var rc : curr.getRecordComponents()) {
                    boolean hasResourceState = rc.isAnnotationPresent(org.praxisplatform.uischema.annotation.ResourceState.class);
                    Class<? extends Enum<?>> enumClass = resolvePraxisUiOptionEnumClass(
                            rc.getType(),
                            rc.getAnnotation(org.praxisplatform.uischema.extension.annotation.UISchema.class)
                    );
                    if (hasResourceState && resourceStateCandidate == null) {
                        resourceStateCandidate = new DetectedStatus(rc.getName(), enumClass);
                    } else if (enumClass != null && optionEnumCandidate == null) {
                        optionEnumCandidate = new DetectedStatus(rc.getName(), enumClass);
                    }
                }
            }
        }

        DetectedStatus selected = resourceStateCandidate != null ? resourceStateCandidate : optionEnumCandidate;
        if (selected == null) {
            return;
        }
        builder.autoStatus(selected.propertyName());
        if (selected.optionEnumClass() == null) {
            return;
        }
        Object[] constants = selected.optionEnumClass().getEnumConstants();
        if (constants == null || constants.length == 0) {
            return;
        }
        Map<String, String> labels = new LinkedHashMap<>();
        List<String> allowed = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (Object constant : constants) {
            if (constant instanceof org.praxisplatform.uischema.option.PraxisUiOptionEnum optionEnum) {
                labels.put(optionEnum.value(), optionEnum.label());
                if (optionEnum.selectableInLookup()) {
                    allowed.add(optionEnum.value());
                } else {
                    blocked.add(optionEnum.value());
                }
            }
        }
        if (!labels.isEmpty()) {
            builder.statusLabels(labels);
        }
        if (!blocked.isEmpty()) {
            builder.autoSelectionPolicy(new LookupSelectionPolicy(
                    null,
                    selected.propertyName(),
                    allowed,
                    blocked,
                    true,
                    "This record is unavailable for selection",
                    "Select an available record"
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Enum<?>> resolvePraxisUiOptionEnumClass(
            Class<?> propertyType,
            org.praxisplatform.uischema.extension.annotation.UISchema uiSchema
    ) {
        if (uiSchema != null && uiSchema.optionEnum() != null && uiSchema.optionEnum() != Void.class
                && uiSchema.optionEnum().isEnum()
                && org.praxisplatform.uischema.option.PraxisUiOptionEnum.class.isAssignableFrom(uiSchema.optionEnum())) {
            return (Class<? extends Enum<?>>) uiSchema.optionEnum();
        }
        if (propertyType != null && propertyType.isEnum()
                && org.praxisplatform.uischema.option.PraxisUiOptionEnum.class.isAssignableFrom(propertyType)) {
            return (Class<? extends Enum<?>>) propertyType;
        }
        return null;
    }

    private static String resolveGetterName(Class<?> owner, java.lang.reflect.Method method) {
        String name = method.getName();
        if (owner.isRecord()) {
            return name;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return null;
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
        private final String entityKey;
        private String code;
        private List<String> descriptions;
        private String status, disabled, disabledReason;
        private List<String> search;
        private Map<String, String> dependencies = Map.of();
        private Map<String, String> statusLabels = Map.of();
        private Class<?> responseDtoClass;
        private Class<?> entityClass;
        private String dtoIdentityKeyField;
        private String dtoCodeField;
        private List<String> dtoDescriptions = List.of();
        private String dtoAutoStatusField;
        private String dtoStatusField;
        private final Map<String, String> resolvedDtoToEntityPaths = new LinkedHashMap<>();
        private boolean statusOverridden;
        private boolean selectionOverridden;
        private boolean searchOverridden;
        private LookupSelectionPolicy selection;
        private LookupCapabilities capabilities = LookupCapabilities.standardDirectory();
        private LookupDetailDescriptor detail;
        private LookupDisplayDescriptor display;
        private LookupFilteringDescriptor filtering;
        private LookupDialogDescriptor dialog;

        private DirectoryBuilder(String entityKey, String code, List<String> descriptions) {
            if (normalize(entityKey) == null || normalize(code) == null || normalizeList(descriptions).isEmpty()) {
                throw new IllegalArgumentException("Directory requires entity, code and description paths");
            }
            this.entityKey = entityKey;
            this.code = code;
            this.descriptions = normalizeList(descriptions);
            this.search = java.util.stream.Stream.concat(java.util.stream.Stream.of(code), this.descriptions.stream()).distinct().toList();
        }

        private DirectoryBuilder withDtoMetadata(
                Class<?> dtoClass,
                String codeField,
                List<String> descriptionFields,
                String identityKeyField
        ) {
            this.responseDtoClass = Objects.requireNonNull(dtoClass, "dtoClass");
            this.dtoCodeField = normalize(codeField);
            this.dtoDescriptions = normalizeList(descriptionFields);
            this.dtoIdentityKeyField = normalize(identityKeyField);
            return this;
        }

        private void autoStatus(String path) {
            this.dtoAutoStatusField = normalize(path);
            this.status = this.dtoAutoStatusField;
        }

        private void autoSelectionPolicy(LookupSelectionPolicy value) {
            this.selection = Objects.requireNonNull(value);
        }

        public DirectoryBuilder status(String path) {
            String normalized = normalize(path);
            this.statusOverridden = true;
            this.dtoStatusField = null;
            if (normalized == null) {
                this.status = null;
                return this;
            }
            if (this.entityClass != null) {
                if (DtoEntityPathResolver.isValidSingularEntityPath(this.entityClass, normalized)) {
                    this.status = normalized;
                    if (this.responseDtoClass != null && DtoEntityPathResolver.hasDtoProperty(this.responseDtoClass, normalized)) {
                        this.dtoStatusField = normalized;
                        this.resolvedDtoToEntityPaths.put(normalized, normalized);
                    }
                } else if (this.responseDtoClass != null && DtoEntityPathResolver.hasDtoProperty(this.responseDtoClass, normalized)) {
                    String resolved = DtoEntityPathResolver.resolveEntityPropertyPath(
                            this.responseDtoClass, this.entityClass, normalized, this.entityKey, this.dtoIdentityKeyField);
                    this.dtoStatusField = normalized;
                    this.status = resolved;
                    this.resolvedDtoToEntityPaths.put(normalized, resolved);
                } else {
                    DtoEntityPathResolver.assertValidSingularEntityPath(this.entityClass, normalized, normalized);
                }
            } else {
                this.status = normalized;
            }
            return this;
        }

        public DirectoryBuilder statusLabels(Map<String, String> values) {
            statusLabels = values == null ? Map.of() : normalizeMap(values);
            return this;
        }

        public DirectoryBuilder withDtoMetadata(Class<?> dtoClass) {
            responseDtoClass = Objects.requireNonNull(dtoClass, "dtoClass");
            if (dtoCodeField == null) {
                dtoCodeField = code;
            }
            if (dtoDescriptions.isEmpty()) {
                dtoDescriptions = descriptions;
            }
            return this;
        }

        /**
         * Binds a JPA entity class and deterministically resolves DTO-derived paths via
         * {@link DtoEntityPathResolver}, failing closed on derived/ambiguous/non-persistent paths.
         */
        public DirectoryBuilder withEntityClass(Class<?> entityClass) {
            this.entityClass = Objects.requireNonNull(entityClass, "entityClass is required");
            this.resolvedDtoToEntityPaths.clear();
            if (this.responseDtoClass != null) {
                String sourceCode = this.dtoCodeField != null ? this.dtoCodeField : this.code;
                String resolvedCode = DtoEntityPathResolver.resolveEntityPropertyPath(
                        this.responseDtoClass, this.entityClass, sourceCode, this.entityKey, this.dtoIdentityKeyField);
                this.code = resolvedCode;
                this.resolvedDtoToEntityPaths.put(sourceCode, resolvedCode);

                List<String> sourceDescs = !this.dtoDescriptions.isEmpty() ? this.dtoDescriptions : this.descriptions;
                List<String> resolvedDescs = new ArrayList<>(sourceDescs.size());
                for (String desc : sourceDescs) {
                    String resolvedDesc = DtoEntityPathResolver.resolveEntityPropertyPath(
                            this.responseDtoClass, this.entityClass, desc, this.entityKey, this.dtoIdentityKeyField);
                    resolvedDescs.add(resolvedDesc);
                    this.resolvedDtoToEntityPaths.put(desc, resolvedDesc);
                }
                this.descriptions = normalizeList(resolvedDescs);

                if (!this.statusOverridden && this.dtoAutoStatusField != null) {
                    String resolvedStatus = DtoEntityPathResolver.resolveEntityPropertyPath(
                            this.responseDtoClass, this.entityClass, this.dtoAutoStatusField, this.entityKey, this.dtoIdentityKeyField);
                    this.status = resolvedStatus;
                    this.resolvedDtoToEntityPaths.put(this.dtoAutoStatusField, resolvedStatus);
                    if (!this.selectionOverridden && this.selection != null) {
                        this.selection = new LookupSelectionPolicy(
                                this.selection.selectablePropertyPath(),
                                resolvedStatus,
                                this.selection.allowedStatuses(),
                                this.selection.blockedStatuses(),
                                this.selection.allowRetainInvalidExistingValue(),
                                this.selection.disabledReasonTemplate(),
                                this.selection.validationMessageTemplate()
                        );
                    }
                } else if (this.statusOverridden && this.status != null) {
                    status(this.status);
                }

                if (!this.searchOverridden) {
                    this.search = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(this.code),
                            this.descriptions.stream()
                    ).distinct().toList();
                } else if (this.search != null) {
                    this.search = resolveSearchPathsAgainstEntity(this.search);
                }
            } else {
                DtoEntityPathResolver.assertValidSingularEntityPath(this.entityClass, this.code, this.code);
                for (String desc : this.descriptions) {
                    DtoEntityPathResolver.assertValidSingularEntityPath(this.entityClass, desc, desc);
                }
                if (this.status != null) {
                    DtoEntityPathResolver.assertValidSingularEntityPath(this.entityClass, this.status, this.status);
                }
                for (String searchPath : this.search) {
                    DtoEntityPathResolver.assertValidSingularEntityPath(this.entityClass, searchPath, searchPath);
                }
            }
            return this;
        }

        private List<String> resolveSearchPathsAgainstEntity(List<String> rawSearchPaths) {
            List<String> resolved = new ArrayList<>(rawSearchPaths.size());
            for (String path : rawSearchPaths) {
                String clean = normalize(path);
                if (clean == null) {
                    continue;
                }
                if (DtoEntityPathResolver.isValidSingularEntityPath(this.entityClass, clean)) {
                    resolved.add(clean);
                } else if (this.responseDtoClass != null && DtoEntityPathResolver.hasDtoProperty(this.responseDtoClass, clean)) {
                    resolved.add(DtoEntityPathResolver.resolveEntityPropertyPath(
                            this.responseDtoClass, this.entityClass, clean, this.entityKey, this.dtoIdentityKeyField));
                } else {
                    DtoEntityPathResolver.assertValidSingularEntityPath(this.entityClass, clean, clean);
                }
            }
            return List.copyOf(new LinkedHashSet<>(resolved));
        }

        public DirectoryBuilder disabled(String path, String reasonPath) { disabled = path; disabledReason = reasonPath; return this; }
        public DirectoryBuilder searchPaths(String... paths) {
            searchOverridden = true;
            List<String> candidate = normalizeList(List.of(paths));
            search = entityClass != null ? resolveSearchPathsAgainstEntity(candidate) : candidate;
            return this;
        }
        public DirectoryBuilder dependencies(Map<String, String> values) { dependencies = Map.copyOf(values); return this; }
        public DirectoryBuilder selectionPolicy(LookupSelectionPolicy value) { selectionOverridden = true; selection = Objects.requireNonNull(value); return this; }
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
        public DirectoryBuilder dialog(LookupDialogDescriptor value) { dialog = Objects.requireNonNull(value); return this; }

        public EntityLookupDescriptor build() {
            LookupSelectionPolicy effectiveSelection = selection != null ? selection : new LookupSelectionPolicy(null, status,
                    List.of(), List.of(), true, "This record is unavailable for selection", "Select an available record");
            LookupDetailDescriptor effectiveDetail = detail != null ? detail : new LookupDetailDescriptor(null, null, "drawer",
                    "surface", "detail", "drawer", null, "view", entityKey, "detail");
            LookupDisplayDescriptor effectiveDisplay;
            if (display != null) {
                effectiveDisplay = display;
            } else if (responseDtoClass != null) {
                List<LookupDisplayFieldDescriptor> dtoFields;
                if (entityClass != null) {
                    dtoFields = new ArrayList<>();
                    Set<String> seenKeys = new LinkedHashSet<>();
                    String sourceCode = dtoCodeField != null ? dtoCodeField : code;
                    if (seenKeys.add(sourceCode)) {
                        dtoFields.add(LookupDisplayFieldDescriptor.fromDtoField(
                                responseDtoClass, sourceCode, resolvedDtoToEntityPaths.getOrDefault(sourceCode, code)));
                    }
                    List<String> sourceDescs = !dtoDescriptions.isEmpty() ? dtoDescriptions : descriptions;
                    for (String dtoDesc : sourceDescs) {
                        if (seenKeys.add(dtoDesc)) {
                            dtoFields.add(LookupDisplayFieldDescriptor.fromDtoField(
                                    responseDtoClass, dtoDesc, resolvedDtoToEntityPaths.getOrDefault(dtoDesc, dtoDesc)));
                        }
                    }
                    if (status != null) {
                        String effectiveDtoStatus = statusOverridden ? dtoStatusField : dtoAutoStatusField;
                        if (effectiveDtoStatus != null && seenKeys.add(effectiveDtoStatus)) {
                            dtoFields.add(LookupDisplayFieldDescriptor.fromDtoField(
                                    responseDtoClass, effectiveDtoStatus, resolvedDtoToEntityPaths.getOrDefault(effectiveDtoStatus, status)));
                        } else if (effectiveDtoStatus == null && seenKeys.add(status)) {
                            dtoFields.add(new LookupDisplayFieldDescriptor(status, status, null, null, "badge", null, null));
                        }
                    }
                } else {
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
                    dtoFields = LookupDisplayDescriptor.fieldsFromDto(responseDtoClass, curatedPaths);
                }
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
                    dependencies, effectiveSelection, capabilities, effectiveDetail, effectiveDisplay, effectiveFiltering, dialog, responseDtoClass);
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
        if (dialog != null && !dialog.isEmpty()) {
            metadata.put("dialog", dialog.toMetadataMap());
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
