package org.praxisplatform.uischema.exporting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

abstract class AbstractTabularCollectionExportEngine implements CollectionExportEngine {

    protected static final String DEFAULT_FILE_BASENAME = "collection-export";

    protected String columnKey(CollectionExportField field) {
        if (field == null) {
            return "";
        }
        if (field.key() != null && !field.key().isBlank()) {
            return field.key();
        }
        return field.valuePath() == null ? "" : field.valuePath();
    }

    protected String columnLabel(CollectionExportField field) {
        if (field == null) {
            return "";
        }
        if (field.label() != null && !field.label().isBlank()) {
            return field.label();
        }
        return columnKey(field);
    }

    protected String resolveFileName(CollectionExportRequest<?> request, String extension) {
        if (request != null && request.fileName() != null && !request.fileName().isBlank()) {
            return request.fileName();
        }
        return DEFAULT_FILE_BASENAME + "." + extension;
    }

    protected long rowCount(List<?> rows) {
        return rows == null ? 0L : rows.size();
    }

    protected Object normalizeValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    protected Object materializeValue(
            Object value,
            CollectionExportField field,
            CollectionExportRequest<?> request
    ) {
        if (request == null || request.applyFormatting() != Boolean.TRUE) {
            return normalizeValue(value);
        }

        CollectionExportFieldPresentation presentation = field == null ? null : field.presentation();
        if (value == null) {
            return presentation != null && hasText(presentation.nullDisplay())
                    ? presentation.nullDisplay()
                    : "";
        }

        String semanticType = firstText(
                presentation == null ? null : presentation.semanticType(),
                field == null ? null : field.type()
        ).toLowerCase(Locale.ROOT);
        String format = firstText(
                presentation == null ? null : presentation.format(),
                field == null ? null : field.format()
        );
        Locale locale = resolveLocale(presentation, request);
        ZoneId zoneId = resolveZoneId(presentation, request);

        return switch (semanticType) {
            case "currency" -> formatCurrency(value, presentation, locale);
            case "date" -> formatDate(value, format, locale);
            case "datetime", "date-time", "timestamp" -> formatDateTime(value, format, locale, zoneId);
            case "boolean", "bool" -> formatBoolean(value, presentation, locale);
            default -> normalizeValue(value);
        };
    }

    private Object formatCurrency(
            Object value,
            CollectionExportFieldPresentation presentation,
            Locale locale
    ) {
        if (!(value instanceof Number number)) {
            return normalizeValue(value);
        }
        NumberFormat formatter = NumberFormat.getCurrencyInstance(locale);
        String currency = presentation == null ? null : presentation.currency();
        if (hasText(currency)) {
            try {
                formatter.setCurrency(Currency.getInstance(currency.trim()));
            } catch (IllegalArgumentException ignored) {
                return normalizeValue(value);
            }
        }
        return formatter.format(number);
    }

    private Object formatDate(Object value, String format, Locale locale) {
        if (value instanceof LocalDate date) {
            return formatter(format, locale, "dd/MM/yyyy").format(date);
        }
        if (value instanceof Temporal temporal) {
            return formatter(format, locale, "dd/MM/yyyy").format(temporal);
        }
        return normalizeValue(value);
    }

    private Object formatDateTime(Object value, String format, Locale locale, ZoneId zoneId) {
        DateTimeFormatter formatter = formatter(format, locale, "dd/MM/yyyy HH:mm:ss");
        if (value instanceof Instant instant) {
            return formatter.withZone(zoneId).format(instant);
        }
        if (value instanceof ZonedDateTime dateTime) {
            return formatter.format(dateTime.withZoneSameInstant(zoneId));
        }
        if (value instanceof OffsetDateTime dateTime) {
            return formatter.format(dateTime.atZoneSameInstant(zoneId));
        }
        if (value instanceof LocalDateTime dateTime) {
            return formatter.format(dateTime);
        }
        if (value instanceof Temporal temporal) {
            return formatter.withZone(zoneId).format((TemporalAccessor) temporal);
        }
        return normalizeValue(value);
    }

    private Object formatBoolean(
            Object value,
            CollectionExportFieldPresentation presentation,
            Locale locale
    ) {
        if (!(value instanceof Boolean bool)) {
            return normalizeValue(value);
        }
        if (presentation != null) {
            String explicit = bool ? presentation.trueLabel() : presentation.falseLabel();
            if (hasText(explicit)) {
                return explicit;
            }
        }
        return "pt".equalsIgnoreCase(locale.getLanguage())
                ? bool ? "Sim" : "Nao"
                : bool.toString();
    }

    private DateTimeFormatter formatter(String format, Locale locale, String fallback) {
        String pattern = hasText(format) ? format.trim() : fallback;
        return DateTimeFormatter.ofPattern(pattern, locale);
    }

    private Locale resolveLocale(
            CollectionExportFieldPresentation presentation,
            CollectionExportRequest<?> request
    ) {
        String value = firstText(
                presentation == null ? null : presentation.locale(),
                request == null || request.localization() == null ? null : request.localization().locale()
        );
        return hasText(value) ? Locale.forLanguageTag(value.trim().replace('_', '-')) : Locale.getDefault();
    }

    private ZoneId resolveZoneId(
            CollectionExportFieldPresentation presentation,
            CollectionExportRequest<?> request
    ) {
        String value = firstText(
                presentation == null ? null : presentation.timeZone(),
                request == null || request.localization() == null ? null : request.localization().timeZone()
        );
        if (!hasText(value)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(value.trim());
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first.trim() : hasText(second) ? second.trim() : "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
