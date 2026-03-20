package org.praxisplatform.uischema.filter.relativeperiod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.web.FilterPayloadNormalizer;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RelativePeriodPayloadNormalizer implements FilterPayloadNormalizer {

    private static final EnumSet<Filterable.FilterOperation> BETWEEN_OPERATIONS = EnumSet.of(
            Filterable.FilterOperation.BETWEEN,
            Filterable.FilterOperation.BETWEEN_EXCLUSIVE,
            Filterable.FilterOperation.NOT_BETWEEN,
            Filterable.FilterOperation.OUTSIDE_RANGE
    );

    private final Clock clock;
    private final Map<Class<?>, List<RelativePeriodFieldMetadata>> fieldCache = new ConcurrentHashMap<>();

    public RelativePeriodPayloadNormalizer() {
        this(Clock.systemUTC());
    }

    RelativePeriodPayloadNormalizer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean normalizeInPlace(ObjectNode payload, Class<?> filterClass) {
        if (payload == null || filterClass == null || !GenericFilterDTO.class.isAssignableFrom(filterClass)) {
            return false;
        }

        boolean changed = false;
        List<RelativePeriodFieldMetadata> fields = fieldCache.computeIfAbsent(filterClass, this::discoverRelativePeriodFields);
        for (RelativePeriodFieldMetadata metadata : fields) {
            JsonNode presetNode = payload.get(metadata.presetFieldName());
            if (presetNode == null || presetNode.isNull()) {
                continue;
            }

            String preset = presetNode.asText(null);
            if (preset == null || preset.trim().isEmpty()) {
                payload.remove(metadata.presetFieldName());
                changed = true;
                continue;
            }

            ensureNoConflicts(payload, metadata);
            applyPreset(payload, metadata, preset.trim());
            payload.remove(metadata.presetFieldName());
            changed = true;
        }
        return changed;
    }

    private List<RelativePeriodFieldMetadata> discoverRelativePeriodFields(Class<?> filterClass) {
        List<Field> fields = collectFields(filterClass);
        Map<String, CompanionFields> companionsByAlias = new HashMap<>();
        for (Field field : fields) {
            Filterable filterable = field.getAnnotation(Filterable.class);
            if (filterable == null) {
                continue;
            }
            String alias = resolveAlias(field, filterable.relation());
            if (alias == null || alias.isBlank()) {
                continue;
            }
            CompanionFields companions = companionsByAlias.computeIfAbsent(alias, ignored -> new CompanionFields());
            if (filterable.operation() == Filterable.FilterOperation.ON_DATE) {
                companions.onDateField = field.getName();
            } else if (filterable.operation() == Filterable.FilterOperation.IN_LAST_DAYS) {
                companions.lastDaysField = field.getName();
            } else if (BETWEEN_OPERATIONS.contains(filterable.operation())) {
                companions.betweenField = field.getName();
                companions.betweenElementKind = resolveBetweenElementKind(field);
            }
        }

        List<RelativePeriodFieldMetadata> result = new ArrayList<>();
        for (Field field : fields) {
            UISchema uiSchema = field.getAnnotation(UISchema.class);
            if (uiSchema == null || uiSchema.controlType() != FieldControlType.INLINE_RELATIVE_PERIOD) {
                continue;
            }
            String presetFieldName = field.getName();
            String alias = presetFieldName.endsWith("Preset")
                    ? presetFieldName.substring(0, presetFieldName.length() - "Preset".length())
                    : presetFieldName;
            CompanionFields companions = companionsByAlias.get(alias);
            if (companions == null || (companions.onDateField == null && companions.lastDaysField == null && companions.betweenField == null)) {
                throw new IllegalStateException(
                        "Field '%s' uses INLINE_RELATIVE_PERIOD but no companion field ending with On, LastDays, or Between was found."
                                .formatted(presetFieldName));
            }
            result.add(new RelativePeriodFieldMetadata(
                    presetFieldName,
                    companions.onDateField,
                    companions.lastDaysField,
                    companions.betweenField,
                    companions.betweenElementKind
            ));
        }
        return result;
    }

    private void ensureNoConflicts(ObjectNode payload, RelativePeriodFieldMetadata metadata) {
        if (hasMeaningfulValue(payload, metadata.onDateField())
                || hasMeaningfulValue(payload, metadata.lastDaysField())
                || hasMeaningfulValue(payload, metadata.betweenField())) {
            throw new InvalidFilterPayloadException(
                    "Relative period preset for field '%s' conflicts with explicit absolute filters. Use only one source."
                            .formatted(metadata.alias()));
        }
    }

    private void applyPreset(ObjectNode payload, RelativePeriodFieldMetadata metadata, String preset) {
        LocalDate today = LocalDate.now(clock);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);

        switch (preset) {
            case RelativePeriodContract.TODAY -> setOnDate(payload, metadata.onDateField(), today);
            case RelativePeriodContract.YESTERDAY -> setOnDate(payload, metadata.onDateField(), today.minusDays(1));
            case RelativePeriodContract.LAST_7 -> setLastDays(payload, metadata.lastDaysField(), 7);
            case RelativePeriodContract.LAST_30 -> setLastDays(payload, metadata.lastDaysField(), 30);
            case RelativePeriodContract.THIS_MONTH -> setBetween(payload, metadata.betweenField(), metadata.betweenElementKind(),
                    today.with(TemporalAdjusters.firstDayOfMonth()), now);
            case RelativePeriodContract.LAST_MONTH -> {
                LocalDate lastMonthStart = today.minusMonths(1).with(TemporalAdjusters.firstDayOfMonth());
                OffsetDateTime lastMonthEnd = today
                        .with(TemporalAdjusters.firstDayOfMonth())
                        .atStartOfDay()
                        .atOffset(ZoneOffset.UTC)
                        .minusNanos(1);
                setBetween(payload, metadata.betweenField(), metadata.betweenElementKind(), lastMonthStart, lastMonthEnd);
            }
            case RelativePeriodContract.THIS_QUARTER -> {
                int quarterStartMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDate quarterStart = LocalDate.of(today.getYear(), quarterStartMonth, 1);
                setBetween(payload, metadata.betweenField(), metadata.betweenElementKind(), quarterStart, now);
            }
            case RelativePeriodContract.THIS_YEAR -> setBetween(payload, metadata.betweenField(), metadata.betweenElementKind(),
                    LocalDate.of(today.getYear(), 1, 1), now);
            default -> throw new InvalidFilterPayloadException(
                    "Unsupported relative period preset '%s' for field '%s'."
                            .formatted(preset, metadata.alias()));
        }
    }

    private void setOnDate(ObjectNode payload, String fieldName, LocalDate value) {
        if (fieldName == null) {
            throw unsupportedTarget("ON_DATE");
        }
        payload.put(fieldName, value.toString());
    }

    private void setLastDays(ObjectNode payload, String fieldName, int value) {
        if (fieldName == null) {
            throw unsupportedTarget("IN_LAST_DAYS");
        }
        payload.put(fieldName, value);
    }

    private void setBetween(ObjectNode payload, String fieldName, BetweenElementKind kind, LocalDate startDate, OffsetDateTime endDateTime) {
        if (fieldName == null || kind == null) {
            throw unsupportedTarget("BETWEEN");
        }
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        switch (kind) {
            case LOCAL_DATE -> {
                array.add(startDate.toString());
                array.add(endDateTime.toLocalDate().toString());
            }
            case OFFSET_DATE_TIME -> {
                array.add(startDate.atStartOfDay().atOffset(ZoneOffset.UTC).toString());
                array.add(endDateTime.toString());
            }
        }
        payload.set(fieldName, array);
    }

    private InvalidFilterPayloadException unsupportedTarget(String operation) {
        return new InvalidFilterPayloadException(
                "Relative period preset cannot be normalized because a companion field for %s is not available.".formatted(operation));
    }

    private boolean hasMeaningfulValue(ObjectNode payload, String fieldName) {
        if (fieldName == null || !payload.has(fieldName)) {
            return false;
        }
        JsonNode node = payload.get(fieldName);
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText("").trim().isEmpty();
        }
        if (node.isArray()) {
            return node.size() > 0;
        }
        return true;
    }

    private String resolveAlias(Field field, String relation) {
        if (relation != null) {
            String trimmed = relation.trim();
            if (!trimmed.isEmpty() && !trimmed.contains(".")) {
                return trimmed;
            }
        }
        String name = field.getName();
        if (name.endsWith("On")) {
            return name.substring(0, name.length() - 2);
        }
        if (name.endsWith("LastDays")) {
            return name.substring(0, name.length() - "LastDays".length());
        }
        if (name.endsWith("Between")) {
            return name.substring(0, name.length() - "Between".length());
        }
        return name;
    }

    private BetweenElementKind resolveBetweenElementKind(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType pt) || pt.getActualTypeArguments().length != 1) {
            return null;
        }
        Type elementType = pt.getActualTypeArguments()[0];
        if (elementType == LocalDate.class) {
            return BetweenElementKind.LOCAL_DATE;
        }
        if (elementType == OffsetDateTime.class) {
            return BetweenElementKind.OFFSET_DATE_TIME;
        }
        return null;
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

    private enum BetweenElementKind {
        LOCAL_DATE,
        OFFSET_DATE_TIME
    }

    private static final class CompanionFields {
        private String onDateField;
        private String lastDaysField;
        private String betweenField;
        private BetweenElementKind betweenElementKind;
    }

    private record RelativePeriodFieldMetadata(
            String presetFieldName,
            String onDateField,
            String lastDaysField,
            String betweenField,
            BetweenElementKind betweenElementKind) {
        String alias() {
            return presetFieldName.endsWith("Preset")
                    ? presetFieldName.substring(0, presetFieldName.length() - "Preset".length())
                    : presetFieldName;
        }
    }
}
