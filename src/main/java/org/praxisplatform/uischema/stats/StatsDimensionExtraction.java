package org.praxisplatform.uischema.stats;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Extracoes e transformacoes canonicas governadas aplicaveis a uma dimensao em {@code praxis.stats}.
 *
 * <p>
 * Separa o campo fisico/logico publicado no {@link StatsFieldRegistry} da funcao de derivacao do
 * bucket. Permite agrupar campos temporais elegiveis tanto por partes ciclicas de calendario
 * ({@link #HOUR_OF_DAY}, {@link #DAY_OF_WEEK}, {@link #DAY_OF_MONTH}, {@link #MONTH_OF_YEAR},
 * {@link #QUARTER_OF_YEAR}) quanto por truncamentos cronologicos lineares ({@link #HOUR},
 * {@link #DAY}, {@link #WEEK}, {@link #MONTH}, {@link #QUARTER}, {@link #YEAR}), em consultas 1D
 * ou multidimensionais (2D/ND Cross-Tab).
 * </p>
 */
public enum StatsDimensionExtraction {
    /** Usa a chave e o label nativos do campo sem transformacao temporal. */
    NONE,
    /** Hora do dia no fuso IANA solicitado (chaves inteiras {@code 0..23}). */
    HOUR_OF_DAY,
    /** Dia da semana ISO-8601 no fuso IANA solicitado (chaves inteiras {@code 1..7}, Segunda=1 a Domingo=7). */
    DAY_OF_WEEK,
    /** Dia do mes no fuso IANA solicitado (chaves inteiras {@code 1..31}). */
    DAY_OF_MONTH,
    /** Mes do ano no fuso IANA solicitado (chaves inteiras {@code 1..12}, Janeiro=1 a Dezembro=12). */
    MONTH_OF_YEAR,
    /** Trimestre do ano no fuso IANA solicitado (chaves inteiras {@code 1..4}). */
    QUARTER_OF_YEAR,
    /** Truncamento cronologico linear por hora ({@code YYYY-MM-DDTHH:00}). */
    HOUR,
    /** Truncamento cronologico linear por dia ({@code YYYY-MM-DD}). */
    DAY,
    /** Truncamento cronologico linear por semana ISO iniciando na segunda-feira ({@code YYYY-MM-DD}). */
    WEEK,
    /** Truncamento cronologico linear por mes ({@code YYYY-MM}). */
    MONTH,
    /** Truncamento cronologico linear por trimestre ({@code YYYY-Qn}). */
    QUARTER,
    /** Truncamento cronologico linear por ano ({@code YYYY}). */
    YEAR;

    private static final String[] DAY_OF_WEEK_LABELS = {
            "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"
    };

    private static final String[] MONTH_LABELS = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
    };

    private static final DateTimeFormatter HOUR_TRUNCATION_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH':00'");

    public boolean isTemporal() {
        return this != NONE;
    }

    public boolean isCyclic() {
        return this == HOUR_OF_DAY
                || this == DAY_OF_WEEK
                || this == DAY_OF_MONTH
                || this == MONTH_OF_YEAR
                || this == QUARTER_OF_YEAR;
    }

    public boolean isLinearTruncation() {
        return this == HOUR
                || this == DAY
                || this == WEEK
                || this == MONTH
                || this == QUARTER
                || this == YEAR;
    }

    public boolean requiresTimeComponent() {
        return this == HOUR_OF_DAY || this == HOUR;
    }

    /**
     * Extrai a chave canonica estavel a partir de um valor temporal convertido para {@code zone}.
     */
    public Object extractKey(Object rawValue, ZoneId zone) {
        if (rawValue == null) {
            return null;
        }
        if (this == NONE) {
            return rawValue;
        }
        ZoneId effectiveZone = zone == null ? ZoneId.of("UTC") : zone;
        if (rawValue instanceof LocalDate localDate) {
            if (requiresTimeComponent()) {
                throw new IllegalArgumentException(
                        "Temporal extraction " + name() + " requires a timestamp field with time component, not LocalDate."
                );
            }
            return extractFromLocalDate(localDate);
        }
        ZonedDateTime zoned = toZonedDateTime(rawValue, effectiveZone);
        if (zoned == null) {
            return null;
        }
        return switch (this) {
            case NONE -> rawValue;
            case HOUR_OF_DAY -> zoned.getHour();
            case DAY_OF_WEEK -> zoned.getDayOfWeek().getValue();
            case DAY_OF_MONTH -> zoned.getDayOfMonth();
            case MONTH_OF_YEAR -> zoned.getMonthValue();
            case QUARTER_OF_YEAR -> ((zoned.getMonthValue() - 1) / 3) + 1;
            case HOUR -> zoned.withMinute(0).withSecond(0).withNano(0).format(HOUR_TRUNCATION_FORMATTER);
            case DAY -> zoned.toLocalDate().toString();
            case WEEK -> zoned.toLocalDate().with(DayOfWeek.MONDAY).toString();
            case MONTH -> YearMonth.from(zoned).toString();
            case QUARTER -> zoned.getYear() + "-Q" + (((zoned.getMonthValue() - 1) / 3) + 1);
            case YEAR -> Integer.toString(zoned.getYear());
        };
    }

    private Object extractFromLocalDate(LocalDate date) {
        return switch (this) {
            case DAY_OF_WEEK -> date.getDayOfWeek().getValue();
            case DAY_OF_MONTH -> date.getDayOfMonth();
            case MONTH_OF_YEAR -> date.getMonthValue();
            case QUARTER_OF_YEAR -> ((date.getMonthValue() - 1) / 3) + 1;
            case DAY -> date.toString();
            case WEEK -> date.with(DayOfWeek.MONDAY).toString();
            case MONTH -> YearMonth.from(date).toString();
            case QUARTER -> date.getYear() + "-Q" + (((date.getMonthValue() - 1) / 3) + 1);
            case YEAR -> Integer.toString(date.getYear());
            default -> throw new IllegalArgumentException("Unsupported extraction for LocalDate: " + this);
        };
    }

    /**
     * Formata o label canonico neutro correspondente a uma chave extraida.
     */
    public String formatLabel(Object key) {
        if (key == null) {
            return "null";
        }
        if (this == NONE) {
            return String.valueOf(key);
        }
        if (key instanceof Number number) {
            int value = number.intValue();
            return switch (this) {
                case HOUR_OF_DAY -> String.format("%02d:00", value);
                case DAY_OF_WEEK -> (value >= 1 && value <= 7) ? DAY_OF_WEEK_LABELS[value - 1] : Integer.toString(value);
                case DAY_OF_MONTH -> String.format("%02d", value);
                case MONTH_OF_YEAR -> (value >= 1 && value <= 12) ? MONTH_LABELS[value - 1] : Integer.toString(value);
                case QUARTER_OF_YEAR -> "Q" + value;
                default -> String.valueOf(key);
            };
        }
        return String.valueOf(key);
    }

    /**
     * Retorna a sequencia ordenada de chaves para densificacao ({@code fillGaps = true}).
     */
    public List<Object> domainKeys(LocalDate from, LocalDate to, ZoneId zone) {
        return switch (this) {
            case NONE -> List.of();
            case HOUR_OF_DAY -> IntStream.rangeClosed(0, 23).boxed().map(Object.class::cast).toList();
            case DAY_OF_WEEK -> IntStream.rangeClosed(1, 7).boxed().map(Object.class::cast).toList();
            case DAY_OF_MONTH -> IntStream.rangeClosed(1, 31).boxed().map(Object.class::cast).toList();
            case MONTH_OF_YEAR -> IntStream.rangeClosed(1, 12).boxed().map(Object.class::cast).toList();
            case QUARTER_OF_YEAR -> IntStream.rangeClosed(1, 4).boxed().map(Object.class::cast).toList();
            case HOUR -> {
                if (from == null || to == null) {
                    yield List.of();
                }
                ZoneId effectiveZone = zone == null ? ZoneId.of("UTC") : zone;
                ZonedDateTime start = from.atStartOfDay(effectiveZone);
                ZonedDateTime endExclusive = to.plusDays(1).atStartOfDay(effectiveZone);
                List<Object> keys = new ArrayList<>();
                Instant cursor = start.toInstant();
                Instant endInstant = endExclusive.toInstant();
                while (cursor.isBefore(endInstant)) {
                    String key = cursor.atZone(effectiveZone).withMinute(0).withSecond(0).withNano(0)
                            .format(HOUR_TRUNCATION_FORMATTER);
                    if (keys.isEmpty() || !keys.get(keys.size() - 1).equals(key)) {
                        keys.add(key);
                    }
                    cursor = cursor.plusSeconds(3600);
                }
                yield List.copyOf(keys);
            }
            case DAY -> {
                if (from == null || to == null) {
                    yield List.of();
                }
                List<Object> keys = new ArrayList<>();
                LocalDate cursor = from;
                while (!cursor.isAfter(to)) {
                    keys.add(cursor.toString());
                    cursor = cursor.plusDays(1);
                }
                yield List.copyOf(keys);
            }
            case WEEK -> {
                if (from == null || to == null) {
                    yield List.of();
                }
                List<Object> keys = new ArrayList<>();
                LocalDate cursor = from.with(DayOfWeek.MONDAY);
                LocalDate endWeek = to.with(DayOfWeek.MONDAY);
                while (!cursor.isAfter(endWeek)) {
                    keys.add(cursor.toString());
                    cursor = cursor.plusWeeks(1);
                }
                yield List.copyOf(keys);
            }
            case MONTH -> {
                if (from == null || to == null) {
                    yield List.of();
                }
                List<Object> keys = new ArrayList<>();
                YearMonth cursor = YearMonth.from(from);
                YearMonth endMonth = YearMonth.from(to);
                while (!cursor.isAfter(endMonth)) {
                    keys.add(cursor.toString());
                    cursor = cursor.plusMonths(1);
                }
                yield List.copyOf(keys);
            }
            case QUARTER -> {
                if (from == null || to == null) {
                    yield List.of();
                }
                List<Object> keys = new ArrayList<>();
                LocalDate cursor = LocalDate.of(from.getYear(), (((from.getMonthValue() - 1) / 3) * 3) + 1, 1);
                LocalDate endQuarter = LocalDate.of(to.getYear(), (((to.getMonthValue() - 1) / 3) * 3) + 1, 1);
                while (!cursor.isAfter(endQuarter)) {
                    keys.add(cursor.getYear() + "-Q" + (((cursor.getMonthValue() - 1) / 3) + 1));
                    cursor = cursor.plusMonths(3);
                }
                yield List.copyOf(keys);
            }
            case YEAR -> {
                if (from == null || to == null) {
                    yield List.of();
                }
                List<Object> keys = new ArrayList<>();
                for (int year = from.getYear(); year <= to.getYear(); year++) {
                    keys.add(Integer.toString(year));
                }
                yield List.copyOf(keys);
            }
        };
    }

    /**
     * Converte um valor temporal suportado pelo JPA para {@link ZonedDateTime} no fuso solicitado.
     */
    public static ZonedDateTime toZonedDateTime(Object rawValue, ZoneId zone) {
        if (rawValue == null) {
            return null;
        }
        ZoneId effectiveZone = zone == null ? ZoneId.of("UTC") : zone;
        if (rawValue instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.withZoneSameInstant(effectiveZone);
        }
        if (rawValue instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.atZoneSameInstant(effectiveZone);
        }
        if (rawValue instanceof Instant instant) {
            return instant.atZone(effectiveZone);
        }
        if (rawValue instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(effectiveZone);
        }
        if (rawValue instanceof LocalDate localDate) {
            return localDate.atStartOfDay(effectiveZone);
        }
        if (rawValue instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().atZone(effectiveZone);
        }
        if (rawValue instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().atStartOfDay(effectiveZone);
        }
        if (rawValue instanceof java.util.Date utilDate) {
            return Instant.ofEpochMilli(utilDate.getTime()).atZone(effectiveZone);
        }
        if (rawValue instanceof TemporalAccessor accessor) {
            try {
                return Instant.from(accessor).atZone(effectiveZone);
            } catch (Exception ignored) {
                try {
                    return LocalDateTime.from(accessor).atZone(effectiveZone);
                } catch (Exception ignoredAgain) {
                    try {
                        return LocalDate.from(accessor).atStartOfDay(effectiveZone);
                    } catch (Exception ignoredFinal) {
                        return null;
                    }
                }
            }
        }
        if (rawValue instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(text).atZoneSameInstant(effectiveZone);
            } catch (Exception ignored) {
                // try next
            }
            try {
                return Instant.parse(text).atZone(effectiveZone);
            } catch (Exception ignored) {
                // try next
            }
            try {
                return ZonedDateTime.parse(text).withZoneSameInstant(effectiveZone);
            } catch (Exception ignored) {
                // try next
            }
            try {
                return LocalDateTime.parse(text).atZone(effectiveZone);
            } catch (Exception ignored) {
                // try next
            }
            try {
                return LocalDate.parse(text).atStartOfDay(effectiveZone);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    public static List<String> allTemporalExtractionNames() {
        return List.of(
                HOUR_OF_DAY.name(),
                DAY_OF_WEEK.name(),
                DAY_OF_MONTH.name(),
                MONTH_OF_YEAR.name(),
                QUARTER_OF_YEAR.name(),
                HOUR.name(),
                DAY.name(),
                WEEK.name(),
                MONTH.name(),
                QUARTER.name(),
                YEAR.name()
        );
    }
}
