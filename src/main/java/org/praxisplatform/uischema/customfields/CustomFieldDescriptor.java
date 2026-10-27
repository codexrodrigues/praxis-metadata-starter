package org.praxisplatform.uischema.customfields;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;

/** Immutable canonical definition shared by schema, validation, filtering and discovery. */
public record CustomFieldDescriptor(
        String key, String label, String description, CustomFieldDataType dataType,
        FieldControlType controlType, String group, int order, boolean required, boolean readOnly,
        boolean filterable, boolean sortable, boolean exportable, Object defaultValue,
        BigDecimal minimum, BigDecimal maximum, Integer minLength, Integer maxLength,
        String pattern, String patternMessage, String requiredMessage, List<String> enumValues,
        List<Map<String, Object>> options, OptionSourceDescriptor optionSource,
        Filterable.FilterOperation filterOperation, Map<String, Object> governance) {
    private static final Set<String> RESERVED_KEYS = Set.of(
            "constructor", "prototype", "hasOwnProperty", "isPrototypeOf",
            "propertyIsEnumerable", "toLocaleString", "toString", "valueOf");
    public CustomFieldDescriptor {
        requireSafeKey(key);
        Objects.requireNonNull(dataType, "dataType");
        controlType = controlType == null ? FieldControlType.AUTO : controlType;
        filterOperation = filterOperation == null ? Filterable.FilterOperation.EQUAL : filterOperation;
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0)
            throw new IllegalArgumentException("minimum exceeds maximum");
        if ((minLength != null && minLength < 0) || (maxLength != null && maxLength < 0)
                || (minLength != null && maxLength != null && minLength > maxLength))
            throw new IllegalArgumentException("Invalid length bounds");
        if (pattern != null) Pattern.compile(pattern);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        if (!enumValues.isEmpty() && dataType != CustomFieldDataType.STRING && dataType != CustomFieldDataType.ENUM)
            throw new IllegalArgumentException("enumValues is supported only for STRING and ENUM custom fields");
        if (dataType == CustomFieldDataType.ENUM && enumValues.isEmpty())
            throw new IllegalArgumentException("ENUM custom fields require non-empty enumValues");
        options = options == null ? List.of() : options.stream().map(CustomFieldDescriptor::immutableMap).toList();
        governance = governance == null ? Map.of() : immutableMap(governance);
        defaultValue = immutableValue(defaultValue);
    }
    public static void requireSafeKey(String key) {
        if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || RESERVED_KEYS.contains(key))
            throw new IllegalArgumentException("Invalid custom field key");
    }
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((k, v) -> copy.put(Objects.requireNonNull(k), immutableValue(v)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, immutableValue(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> list) return list.stream().map(CustomFieldDescriptor::immutableValue).toList();
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++)
                copy.add(immutableValue(java.lang.reflect.Array.get(value, i)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
