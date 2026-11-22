package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.uischema.extension.annotation.UISchema;

/**
 * Semantic field projected inside a rich entity lookup option.
 */
public record LookupDisplayFieldDescriptor(
        String key,
        String propertyPath,
        String label,
        String icon,
        String presentation,
        String tone,
        String format
) {

    /**
     * Resolves a {@link LookupDisplayFieldDescriptor} by introspecting {@link UISchema} on {@code dtoClass}
     * (walking fields, record components, superclasses, interfaces, getters, and dotted sub-DTO paths),
     * failing closed if {@code propertyPath} does not exist on {@code dtoClass}.
     */
    public static LookupDisplayFieldDescriptor fromDtoField(Class<?> dtoClass, String propertyPath) {
        Objects.requireNonNull(dtoClass, "dtoClass");
        String normalizedPath = normalize(propertyPath);
        if (normalizedPath == null) {
            throw new IllegalArgumentException("propertyPath is required");
        }
        return resolveFromDtoField(dtoClass, normalizedPath, normalizedPath);
    }

    /**
     * Resolves a {@link LookupDisplayFieldDescriptor} by introspecting {@link UISchema} on {@code dtoClass}
     * for {@code dtoField} (preserving {@code key = dtoField} as the DTO wire field name) while binding
     * {@code propertyPath = entityPropertyPath} to the resolved JPA entity path.
     */
    public static LookupDisplayFieldDescriptor fromDtoField(
            Class<?> dtoClass,
            String dtoField,
            String entityPropertyPath
    ) {
        Objects.requireNonNull(dtoClass, "dtoClass");
        String normalizedDtoField = normalize(dtoField);
        if (normalizedDtoField == null) {
            throw new IllegalArgumentException("dtoField is required");
        }
        String normalizedEntityPath = normalize(entityPropertyPath);
        if (normalizedEntityPath == null) {
            throw new IllegalArgumentException("entityPropertyPath is required");
        }
        return resolveFromDtoField(dtoClass, normalizedDtoField, normalizedEntityPath);
    }

    private static LookupDisplayFieldDescriptor resolveFromDtoField(
            Class<?> dtoClass,
            String dtoField,
            String entityPropertyPath
    ) {
        DtoEntityPathResolver.DtoPropertyMatch match = DtoEntityPathResolver.resolveDtoProperty(dtoClass, dtoField);
        Class<?> propertyType = match.propertyType();
        UISchema ui = match.findUiSchema();
        String label = ui != null && !ui.label().isBlank() ? ui.label().trim() : dtoField;
        String icon = ui != null && !ui.icon().isBlank() ? ui.icon().trim() : "label";
        String presentation = inferPresentation(propertyType, ui);
        String format = inferFormat(propertyType, ui, presentation);
        return new LookupDisplayFieldDescriptor(
                dtoField, entityPropertyPath, label, icon, presentation, "neutral", format);
    }

    private static String inferPresentation(Class<?> fieldType, UISchema ui) {
        if (ui != null) {
            if (ui.controlType() == org.praxisplatform.uischema.FieldControlType.CURRENCY_INPUT
                    || ui.preset() == org.praxisplatform.uischema.extension.annotation.UISchemaPreset.MONETARY_AMOUNT
                    || ui.numericFormat() == org.praxisplatform.uischema.NumericFormat.CURRENCY) {
                return "currency";
            }
            if (ui.type() == org.praxisplatform.uischema.FieldDataType.DATE
                    || fieldType == java.time.LocalDate.class) {
                return "date";
            }
            if (fieldType == java.time.LocalDateTime.class || fieldType == java.time.OffsetDateTime.class) {
                return "datetime";
            }
            if (ui.type() == org.praxisplatform.uischema.FieldDataType.BOOLEAN
                    || fieldType == Boolean.class
                    || fieldType == boolean.class) {
                return "badge";
            }
        } else {
            if (fieldType == java.time.LocalDate.class) {
                return "date";
            }
            if (fieldType == java.time.LocalDateTime.class || fieldType == java.time.OffsetDateTime.class) {
                return "datetime";
            }
            if (fieldType == Boolean.class || fieldType == boolean.class) {
                return "badge";
            }
        }
        return "text";
    }

    private static String inferFormat(Class<?> fieldType, UISchema ui, String presentation) {
        if ("currency".equals(presentation)) {
            return "BRL";
        }
        if ("date".equals(presentation)) {
            return "dd/MM/yyyy";
        }
        if ("datetime".equals(presentation)) {
            return "dd/MM/yyyy HH:mm";
        }
        return null;
    }

    public LookupDisplayFieldDescriptor {
        key = normalize(key);
        propertyPath = normalize(propertyPath);
        label = normalize(label);
        icon = normalize(icon);
        presentation = normalize(presentation);
        tone = normalize(tone);
        format = normalize(format);
    }

    public boolean isEmpty() {
        return key == null
                && propertyPath == null
                && label == null
                && icon == null
                && presentation == null
                && tone == null
                && format == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "key", key);
        putIfNotNull(metadata, "propertyPath", propertyPath);
        putIfNotNull(metadata, "label", label);
        putIfNotNull(metadata, "icon", icon);
        putIfNotNull(metadata, "presentation", presentation);
        putIfNotNull(metadata, "tone", tone);
        putIfNotNull(metadata, "format", format);
        return metadata;
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
