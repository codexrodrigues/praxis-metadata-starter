package org.praxisplatform.uischema.options;

import java.lang.reflect.Field;
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
     * (walking fields, record components, superclasses, and getters), failing closed if {@code propertyPath}
     * does not exist on {@code dtoClass}.
     */
    public static LookupDisplayFieldDescriptor fromDtoField(Class<?> dtoClass, String propertyPath) {
        Objects.requireNonNull(dtoClass, "dtoClass");
        String normalizedPath = normalize(propertyPath);
        if (normalizedPath == null) {
            throw new IllegalArgumentException("propertyPath is required");
        }
        Field field = findField(dtoClass, normalizedPath);
        java.lang.reflect.Method getter = findGetter(dtoClass, normalizedPath);
        if (field == null && getter == null) {
            throw new IllegalArgumentException(
                    "Property '" + normalizedPath + "' not found on DTO class " + dtoClass.getName());
        }
        Class<?> propertyType = field != null ? field.getType() : getter.getReturnType();
        UISchema ui = findUiSchema(dtoClass, field, getter, normalizedPath);
        String label = ui != null && !ui.label().isBlank() ? ui.label().trim() : normalizedPath;
        String icon = ui != null && !ui.icon().isBlank() ? ui.icon().trim() : "label";
        String presentation = inferPresentation(propertyType, ui);
        String format = inferFormat(propertyType, ui, presentation);
        return new LookupDisplayFieldDescriptor(
                normalizedPath, normalizedPath, label, icon, presentation, "neutral", format);
    }

    private static UISchema findUiSchema(
            Class<?> dtoClass,
            Field field,
            java.lang.reflect.Method getter,
            String propertyName
    ) {
        if (field != null) {
            UISchema onField = field.getAnnotation(UISchema.class);
            if (onField != null) {
                return onField;
            }
        }
        if (dtoClass.isRecord() && dtoClass.getRecordComponents() != null) {
            for (java.lang.reflect.RecordComponent rc : dtoClass.getRecordComponents()) {
                if (propertyName.equals(rc.getName())) {
                    UISchema onRc = rc.getAnnotation(UISchema.class);
                    if (onRc != null) {
                        return onRc;
                    }
                }
            }
        }
        if (getter != null) {
            UISchema onGetter = getter.getAnnotation(UISchema.class);
            if (onGetter != null) {
                return onGetter;
            }
        }
        return null;
    }

    private static java.lang.reflect.Method findGetter(Class<?> dtoClass, String propertyName) {
        String cap = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        java.util.List<String> candidates = java.util.List.of("get" + cap, "is" + cap, propertyName);
        for (Class<?> current = dtoClass; current != null && current != Object.class; current = current.getSuperclass()) {
            for (String candidate : candidates) {
                try {
                    java.lang.reflect.Method m = current.getDeclaredMethod(candidate);
                    if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                        return m;
                    }
                } catch (NoSuchMethodException ignored) {
                    // continue walking hierarchy
                }
            }
        }
        return null;
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

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // continue walking hierarchy
            }
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
