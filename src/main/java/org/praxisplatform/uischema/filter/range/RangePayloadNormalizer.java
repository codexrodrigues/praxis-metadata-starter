package org.praxisplatform.uischema.filter.range;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonicaliza payloads de range para o formato de lista usado pelos FilterDTOs
 * tipados no backend.
 */
public class RangePayloadNormalizer {

    private static final Set<Filterable.FilterOperation> RANGE_OPERATIONS = EnumSet.of(
            Filterable.FilterOperation.BETWEEN,
            Filterable.FilterOperation.BETWEEN_EXCLUSIVE,
            Filterable.FilterOperation.NOT_BETWEEN,
            Filterable.FilterOperation.OUTSIDE_RANGE
    );

    private static final List<String> LOWER_DATE_KEYS = List.of("startDate", "fromDate", "start", "from");
    private static final List<String> UPPER_DATE_KEYS = List.of("endDate", "toDate", "end", "to");
    private static final List<String> LOWER_MONEY_KEYS = List.of("minPrice", "valorMin", "min", "from", "start");
    private static final List<String> UPPER_MONEY_KEYS = List.of("maxPrice", "valorMax", "max", "to", "end");
    private static final List<String> LOWER_GENERIC_KEYS = List.of(
            "start", "from", "min", "lower", "fieldMin", "gte", "valorMin", "minPrice", "startDate"
    );
    private static final List<String> UPPER_GENERIC_KEYS = List.of(
            "end", "to", "max", "upper", "fieldMax", "lte", "valorMax", "maxPrice", "endDate"
    );

    private final Map<Class<?>, List<RangeFieldMetadata>> fieldCache = new ConcurrentHashMap<>();

    public boolean normalizeInPlace(ObjectNode payload, Class<?> filterClass) {
        if (payload == null || filterClass == null || !GenericFilterDTO.class.isAssignableFrom(filterClass)) {
            return false;
        }
        boolean changed = false;
        List<RangeFieldMetadata> rangeFields = fieldCache.computeIfAbsent(filterClass, this::discoverRangeFields);
        for (RangeFieldMetadata metadata : rangeFields) {
            JsonNode raw = payload.get(metadata.fieldName());
            if (raw == null || raw.isNull()) {
                continue;
            }
            ArrayNode normalized = normalizeRangeValue(raw, metadata.kind());
            if (normalized == null) {
                continue;
            }
            if (!raw.equals(normalized)) {
                payload.set(metadata.fieldName(), normalized);
                changed = true;
            }
        }
        return changed;
    }

    private List<RangeFieldMetadata> discoverRangeFields(Class<?> filterClass) {
        List<RangeFieldMetadata> result = new ArrayList<>();
        for (Field field : collectFields(filterClass)) {
            Filterable filterable = field.getAnnotation(Filterable.class);
            if (filterable == null || !RANGE_OPERATIONS.contains(filterable.operation())) {
                continue;
            }
            result.add(new RangeFieldMetadata(field.getName(), resolveKind(field)));
        }
        return result;
    }

    private List<Field> collectFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private RangeKind resolveKind(Field field) {
        UISchema uiSchema = field.getAnnotation(UISchema.class);
        if (uiSchema != null && uiSchema.numericFormat() == NumericFormat.CURRENCY) {
            return RangeKind.MONETARY;
        }

        Class<?> elementType = resolveElementType(field);
        if (elementType != null) {
            if (LocalDate.class.isAssignableFrom(elementType)) {
                return RangeKind.DATE;
            }
            if (Temporal.class.isAssignableFrom(elementType) || Date.class.isAssignableFrom(elementType)) {
                return RangeKind.DATE_TIME;
            }
            if (Number.class.isAssignableFrom(elementType)) {
                return isMonetaryName(field.getName()) ? RangeKind.MONETARY : RangeKind.NUMERIC;
            }
        }

        return isMonetaryName(field.getName()) ? RangeKind.MONETARY : RangeKind.UNKNOWN;
    }

    private Class<?> resolveElementType(Field field) {
        Class<?> raw = field.getType();
        if (raw.isArray()) {
            return raw.getComponentType();
        }

        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 0) {
                return raw;
            }
            Type first = args[0];
            if (first instanceof Class<?> cls) {
                return cls;
            }
            if (first instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> cls) {
                return cls;
            }
        }
        return raw;
    }

    private boolean isMonetaryName(String fieldName) {
        String normalized = String.valueOf(fieldName).toLowerCase(Locale.ROOT);
        return normalized.contains("valor")
                || normalized.contains("preco")
                || normalized.contains("price")
                || normalized.contains("salario")
                || normalized.contains("salary")
                || normalized.contains("amount");
    }

    private ArrayNode normalizeRangeValue(JsonNode raw, RangeKind kind) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (raw == null || raw.isNull()) {
            return null;
        }
        if (raw.isArray()) {
            return normalizeFromArray(raw, kind);
        }
        if (raw.isObject()) {
            return normalizeFromObject((ObjectNode) raw, kind);
        }
        if (isMeaningful(raw)) {
            ArrayNode arr = factory.arrayNode();
            arr.add(raw);
            return arr;
        }
        return null;
    }

    private ArrayNode normalizeFromArray(JsonNode arrayNode, RangeKind kind) {
        if (arrayNode != null && arrayNode.size() > 2) {
            throw new InvalidFilterPayloadException("Range payload accepts at most two bounds.");
        }

        JsonNodeFactory factory = JsonNodeFactory.instance;
        JsonNode lower = arrayNode.size() > 0 ? sanitizeArrayBound(arrayNode.get(0)) : null;
        JsonNode upper = arrayNode.size() > 1 ? sanitizeArrayBound(arrayNode.get(1)) : null;

        if (isMeaningful(lower) || isMeaningful(upper)) {
            if (isMeaningful(lower) && isMeaningful(upper) && shouldSwap(kind, lower, upper)) {
                JsonNode tmp = lower;
                lower = upper;
                upper = tmp;
            }

            ArrayNode arr = factory.arrayNode();
            if (isMeaningful(lower)) {
                arr.add(lower);
            } else if (isMeaningful(upper)) {
                // Preserva semântica de "apenas limite superior" como [null, upper].
                arr.addNull();
            }
            if (isMeaningful(upper)) {
                arr.add(upper);
            }
            return arr.size() == 0 ? null : arr;
        }

        ArrayNode arr = factory.arrayNode();
        for (JsonNode item : arrayNode) {
            JsonNode candidate = sanitizeArrayBound(item);
            if (!isMeaningful(candidate)) {
                continue;
            }
            arr.add(candidate);
            if (arr.size() == 2) {
                break;
            }
        }
        if (arr.size() == 2 && shouldSwap(kind, arr.get(0), arr.get(1))) {
            JsonNode first = arr.get(0);
            JsonNode second = arr.get(1);
            arr.removeAll();
            arr.add(second);
            arr.add(first);
        }
        return arr.size() == 0 ? null : arr;
    }

    private ArrayNode normalizeFromObject(ObjectNode objectNode, RangeKind kind) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        Map<String, JsonNode> lowerMap = lowercaseKeyMap(objectNode);
        JsonNode nestedBetween = lowerMap.get("between");
        if (nestedBetween != null && (nestedBetween.isArray() || nestedBetween.isObject())) {
            ArrayNode nested = normalizeRangeValue(nestedBetween, kind);
            if (nested != null) {
                return nested;
            }
        }

        JsonNode lower = firstByKeys(lowerMap, lowerKeys(kind));
        JsonNode upper = firstByKeys(lowerMap, upperKeys(kind));
        if (!isMeaningful(lower) && !isMeaningful(upper)) {
            return null;
        }

        ArrayNode arr = factory.arrayNode();
        if (isMeaningful(lower)) {
            arr.add(lower);
        } else if (isMeaningful(upper)) {
            // Preserva semântica de "apenas limite superior" como [null, upper].
            arr.addNull();
        }
        if (isMeaningful(upper)) {
            arr.add(upper);
        }
        if (arr.size() == 2 && isMeaningful(arr.get(0)) && isMeaningful(arr.get(1))
                && shouldSwap(kind, arr.get(0), arr.get(1))) {
            JsonNode first = arr.get(0);
            JsonNode second = arr.get(1);
            arr.removeAll();
            arr.add(second);
            arr.add(first);
        }
        return arr.size() == 0 ? null : arr;
    }

    private JsonNode sanitizeArrayBound(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual() && node.asText("").trim().isEmpty()) {
            return null;
        }
        return node;
    }

    private Map<String, JsonNode> lowercaseKeyMap(ObjectNode objectNode) {
        Map<String, JsonNode> map = new HashMap<>();
        objectNode.fields().forEachRemaining(entry ->
                map.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        return map;
    }

    private JsonNode firstByKeys(Map<String, JsonNode> source, List<String> keys) {
        for (String key : keys) {
            JsonNode value = source.get(key.toLowerCase(Locale.ROOT));
            if (isMeaningful(value)) {
                return value;
            }
        }
        return null;
    }

    private List<String> lowerKeys(RangeKind kind) {
        return switch (kind) {
            case DATE, DATE_TIME -> concat(LOWER_DATE_KEYS, LOWER_GENERIC_KEYS);
            case MONETARY -> concat(LOWER_MONEY_KEYS, LOWER_GENERIC_KEYS);
            default -> LOWER_GENERIC_KEYS;
        };
    }

    private List<String> upperKeys(RangeKind kind) {
        return switch (kind) {
            case DATE, DATE_TIME -> concat(UPPER_DATE_KEYS, UPPER_GENERIC_KEYS);
            case MONETARY -> concat(UPPER_MONEY_KEYS, UPPER_GENERIC_KEYS);
            default -> UPPER_GENERIC_KEYS;
        };
    }

    private List<String> concat(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return merged;
    }

    private boolean isMeaningful(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return !value.asText("").trim().isEmpty();
        }
        return true;
    }

    private boolean shouldSwap(RangeKind kind, JsonNode lower, JsonNode upper) {
        return switch (kind) {
            case NUMERIC, MONETARY -> shouldSwapNumeric(lower, upper);
            case DATE -> shouldSwapDate(lower, upper);
            case DATE_TIME -> shouldSwapDateTime(lower, upper);
            default -> false;
        };
    }

    private boolean shouldSwapNumeric(JsonNode lower, JsonNode upper) {
        BigDecimal l = parseBigDecimal(lower);
        BigDecimal u = parseBigDecimal(upper);
        return l != null && u != null && l.compareTo(u) > 0;
    }

    private boolean shouldSwapDate(JsonNode lower, JsonNode upper) {
        LocalDate l = parseLocalDate(lower);
        LocalDate u = parseLocalDate(upper);
        return l != null && u != null && l.isAfter(u);
    }

    private boolean shouldSwapDateTime(JsonNode lower, JsonNode upper) {
        Instant l = parseInstant(lower);
        Instant u = parseInstant(upper);
        return l != null && u != null && l.isAfter(u);
    }

    private LocalDate parseLocalDate(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String text = node.asText("").trim();
        if (text.isEmpty()) return null;
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String text = node.asText("").trim();
        if (text.isEmpty()) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // fallback
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // fallback
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) {
            return new BigDecimal(node.asText());
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) return null;

        String normalized = text.replace(" ", "");
        if (normalized.contains(",") && normalized.contains(".")) {
            int lastComma = normalized.lastIndexOf(',');
            int lastDot = normalized.lastIndexOf('.');
            if (lastComma > lastDot) {
                normalized = normalized.replace(".", "").replace(',', '.');
            } else {
                normalized = normalized.replace(",", "");
            }
        } else if (normalized.contains(",")) {
            normalized = normalized.replace(',', '.');
        }
        normalized = normalized.replaceAll("[^0-9+\\-\\.]", "");
        if (normalized.isEmpty() || ".".equals(normalized) || "-".equals(normalized) || "+".equals(normalized)) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private enum RangeKind {
        DATE,
        DATE_TIME,
        NUMERIC,
        MONETARY,
        UNKNOWN
    }

    private record RangeFieldMetadata(String fieldName, RangeKind kind) {
    }
}
