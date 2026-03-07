package org.praxisplatform.uischema.filter.range;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Canonicaliza payloads de range para o formato de lista usado pelos FilterDTOs
 * tipados no backend.
 */
public class RangePayloadNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RangePayloadNormalizer.class);
    private static final Set<Filterable.FilterOperation> RANGE_OPERATIONS = EnumSet.of(
            Filterable.FilterOperation.BETWEEN,
            Filterable.FilterOperation.BETWEEN_EXCLUSIVE,
            Filterable.FilterOperation.NOT_BETWEEN,
            Filterable.FilterOperation.OUTSIDE_RANGE
    );
    private static final String SCALAR_PAYLOAD_ERROR =
            "Range payload escalar é inválido. Use [min], [null,max], [min,max] ou objeto canônico.";
    private static final long LEGACY_LOG_SAMPLE_LIMIT = 5L;

    private static final List<String> LOWER_DATE_KEYS = RangeBoundAliasRegistry.lowerDateKeys();
    private static final List<String> UPPER_DATE_KEYS = RangeBoundAliasRegistry.upperDateKeys();
    private static final List<String> LOWER_MONEY_KEYS = RangeBoundAliasRegistry.lowerMoneyKeys();
    private static final List<String> UPPER_MONEY_KEYS = RangeBoundAliasRegistry.upperMoneyKeys();
    private static final List<String> LOWER_GENERIC_KEYS = RangeBoundAliasRegistry.lowerGenericKeys();
    private static final List<String> UPPER_GENERIC_KEYS = RangeBoundAliasRegistry.upperGenericKeys();

    private final boolean allowScalarPayload;
    private final boolean logLegacyScalarPayload;
    private final Map<Class<?>, List<RangeFieldMetadata>> fieldCache = new ConcurrentHashMap<>();
    private final AtomicLong legacyScalarPayloadTotal = new AtomicLong(0);
    private final Map<RangeKind, AtomicLong> legacyScalarPayloadByKind = new ConcurrentHashMap<>();

    public RangePayloadNormalizer() {
        this(false, true);
    }

    public RangePayloadNormalizer(boolean allowScalarPayload, boolean logLegacyScalarPayload) {
        this.allowScalarPayload = allowScalarPayload;
        this.logLegacyScalarPayload = logLegacyScalarPayload;
    }

    public boolean normalizeInPlace(ObjectNode payload, Class<?> filterClass) {
        if (payload == null || filterClass == null || !GenericFilterDTO.class.isAssignableFrom(filterClass)) {
            return false;
        }
        boolean changed = false;
        List<RangeFieldMetadata> rangeFields = fieldCache.computeIfAbsent(filterClass, this::discoverRangeFields);
        for (RangeFieldMetadata metadata : rangeFields) {
            RangeInputSource source = resolveInputSource(payload, metadata);
            if (source == null) {
                continue;
            }
            ArrayNode normalized = normalizeRangeValue(source.raw(), metadata.kind());
            if (normalized == null) {
                if (hasExplicitContent(source.raw())) {
                    throw new InvalidFilterPayloadException(
                            "Range payload for field '" + metadata.fieldName() + "' does not provide recognized bounds."
                    );
                }
                continue;
            }
            JsonNode current = payload.get(metadata.fieldName());
            if (current == null || !current.equals(normalized)) {
                payload.set(metadata.fieldName(), normalized);
                changed = true;
            }
            for (String consumedKey : source.consumedKeys()) {
                if (consumedKey == null || consumedKey.equals(metadata.fieldName())) {
                    continue;
                }
                if (payload.has(consumedKey)) {
                    payload.remove(consumedKey);
                    changed = true;
                }
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
            String fieldName = field.getName();
            String relationAlias = resolveSimpleRelationAlias(filterable.relation());
            String fieldAlias = resolveFieldAlias(fieldName);
            RangeKind kind = resolveKind(field);
            result.add(new RangeFieldMetadata(
                    fieldName,
                    relationAlias,
                    fieldAlias,
                    buildLegacySplitAliases(fieldAlias, relationAlias, kind, true),
                    buildLegacySplitAliases(fieldAlias, relationAlias, kind, false),
                    kind
            ));
        }
        return result;
    }

    private RangeInputSource resolveInputSource(ObjectNode payload, RangeFieldMetadata metadata) {
        List<RangeInputSource> candidates = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        addDirectSourceCandidate(payload, metadata.fieldName(), candidates, seenKeys);
        addDirectSourceCandidate(payload, metadata.relationAlias(), candidates, seenKeys);
        addDirectSourceCandidate(payload, metadata.fieldAlias(), candidates, seenKeys);

        RangeInputSource splitAliasSource = buildSplitAliasSource(payload, metadata);
        if (splitAliasSource != null) {
            candidates.add(splitAliasSource);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        RangeInputSource primary = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            RangeInputSource candidate = candidates.get(i);
            if (!primary.raw().equals(candidate.raw())) {
                throw new InvalidFilterPayloadException(
                        "Range payload for field '" + metadata.fieldName()
                                + "' provides conflicting sources. Use only one source."
                );
            }
        }
        return primary;
    }

    private void addDirectSourceCandidate(
            ObjectNode payload,
            String key,
            List<RangeInputSource> candidates,
            Set<String> seenKeys
    ) {
        if (key == null || key.isBlank() || !seenKeys.add(key) || !payload.has(key)) {
            return;
        }
        candidates.add(new RangeInputSource(payload.get(key), List.of(key)));
    }

    private RangeInputSource buildSplitAliasSource(ObjectNode payload, RangeFieldMetadata metadata) {
        KeyValue lower = firstPresentSplitKey(payload, metadata.lowerSplitAliases());
        KeyValue upper = firstPresentSplitKey(payload, metadata.upperSplitAliases());
        if (lower == null && upper == null) {
            return null;
        }

        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        List<String> consumed = new ArrayList<>(2);
        if (lower != null) {
            consumed.add(lower.key());
            merged.set("minPrice", lower.value());
        }
        if (upper != null) {
            consumed.add(upper.key());
            merged.set("maxPrice", upper.value());
        }
        return new RangeInputSource(merged, consumed);
    }

    private KeyValue firstPresentSplitKey(ObjectNode payload, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        KeyValue selected = null;
        for (String key : keys) {
            if (key == null || key.isBlank() || !payload.has(key)) {
                continue;
            }
            JsonNode value = payload.get(key);
            if (selected == null) {
                selected = new KeyValue(key, value);
                continue;
            }
            if (!selected.value().equals(value)) {
                throw new InvalidFilterPayloadException(
                        "Range payload provides conflicting split bounds aliases ('"
                                + selected.key() + "' and '" + key + "')."
                );
            }
        }
        return selected;
    }

    private boolean hasExplicitContent(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText("").trim().isEmpty();
        }
        return true;
    }

    private String resolveSimpleRelationAlias(String relation) {
        if (relation == null) {
            return null;
        }
        String normalized = relation.trim();
        if (normalized.isEmpty() || normalized.contains(".")) {
            return null;
        }
        return normalized;
    }

    private String resolveFieldAlias(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return null;
        }
        if (fieldName.endsWith("Between")) {
            return fieldName.substring(0, fieldName.length() - "Between".length());
        }
        if (fieldName.endsWith("Range")) {
            return fieldName.substring(0, fieldName.length() - "Range".length());
        }
        return null;
    }

    private List<String> buildLegacySplitAliases(
            String fieldAlias,
            String relationAlias,
            RangeKind kind,
            boolean lower
    ) {
        if (kind != RangeKind.MONETARY) {
            return List.of();
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        String suffix = lower ? "Min" : "Max";
        if (fieldAlias != null && !fieldAlias.isBlank()) {
            aliases.add(fieldAlias + suffix);
        }
        if (relationAlias != null && !relationAlias.isBlank()) {
            aliases.add(relationAlias + suffix);
        }
        return List.copyOf(aliases);
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
            if (!allowScalarPayload) {
                throw new InvalidFilterPayloadException(SCALAR_PAYLOAD_ERROR);
            }
            trackLegacyScalarPayload(kind, raw);
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
        if (nestedBetween != null) {
            if (nestedBetween.isArray() || nestedBetween.isObject() || isMeaningful(nestedBetween)) {
                ArrayNode nested = normalizeRangeValue(nestedBetween, kind);
                if (nested != null) {
                    return nested;
                }
            }
        }

        JsonNode lower = firstByKeys(lowerMap, lowerKeys(kind));
        JsonNode upper = firstByKeys(lowerMap, upperKeys(kind));
        if (!isMeaningful(lower) && !isMeaningful(upper)) {
            if (hasRangeObjectSignal(lowerMap, kind)) {
                ArrayNode invalid = factory.arrayNode();
                // sentinela para preservar validação strict no builder (400 em runtime)
                invalid.addNull();
                return invalid;
            }
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

    private boolean hasRangeObjectSignal(Map<String, JsonNode> source, RangeKind kind) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        if (source.containsKey("between")) {
            return true;
        }
        if (containsAnyKey(source, lowerKeys(kind)) || containsAnyKey(source, upperKeys(kind))) {
            return true;
        }
        return kind == RangeKind.MONETARY && source.containsKey("currency");
    }

    private boolean containsAnyKey(Map<String, JsonNode> source, List<String> keys) {
        if (source == null || keys == null || keys.isEmpty()) {
            return false;
        }
        for (String key : keys) {
            if (key != null && source.containsKey(key.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
        if (node.isNumber()) return RangeNumberParser.parse(node.numberValue());
        String text = node.asText("");
        return text == null || text.trim().isEmpty() ? null : RangeNumberParser.parse(text);
    }

    private void trackLegacyScalarPayload(RangeKind kind, JsonNode raw) {
        long total = legacyScalarPayloadTotal.incrementAndGet();
        long kindCount = legacyScalarPayloadByKind
                .computeIfAbsent(kind, ignored -> new AtomicLong(0))
                .incrementAndGet();

        if (!logLegacyScalarPayload) {
            return;
        }
        if (kindCount <= LEGACY_LOG_SAMPLE_LIMIT || kindCount % 100 == 0) {
            LOGGER.warn(
                    "[RangePayloadNormalizer] Payload range escalar legado normalizado (kind={}, kindCount={}, total={}, nodeType={}). " +
                            "Recomendado migrar para array/objeto canônico. Para fallback legado, mantenha " +
                            "praxis.filter.range.allow-scalar-payload=true.",
                    kind, kindCount, total, raw != null ? raw.getNodeType() : null
            );
        }
    }

    private enum RangeKind {
        DATE,
        DATE_TIME,
        NUMERIC,
        MONETARY,
        UNKNOWN
    }

    private record RangeFieldMetadata(
            String fieldName,
            String relationAlias,
            String fieldAlias,
            List<String> lowerSplitAliases,
            List<String> upperSplitAliases,
            RangeKind kind
    ) {
    }

    private record RangeInputSource(JsonNode raw, List<String> consumedKeys) {
    }

    private record KeyValue(String key, JsonNode value) {
    }
}
