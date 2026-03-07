package org.praxisplatform.uischema.filter.range;

import java.util.List;

/**
 * Catálogo centralizado de aliases canônicos para bounds de range.
 *
 * <p>Objetivo: evitar drift entre normalização no starter e parsers
 * consumidores (ex.: quickstart) ao evoluir contrato de range.</p>
 */
public final class RangeBoundAliasRegistry {

    private static final List<String> LOWER_DATE_KEYS =
            List.of("startDate", "fromDate", "start", "from");
    private static final List<String> UPPER_DATE_KEYS =
            List.of("endDate", "toDate", "end", "to");

    private static final List<String> LOWER_MONEY_KEYS =
            List.of("minPrice", "valorMin", "min", "from", "start");
    private static final List<String> UPPER_MONEY_KEYS =
            List.of("maxPrice", "valorMax", "max", "to", "end");

    private static final List<String> LOWER_GENERIC_KEYS = List.of(
            "start", "from", "min", "lower", "fieldMin", "gte", "valorMin", "minPrice", "startDate"
    );
    private static final List<String> UPPER_GENERIC_KEYS = List.of(
            "end", "to", "max", "upper", "fieldMax", "lte", "valorMax", "maxPrice", "endDate"
    );

    private RangeBoundAliasRegistry() {
    }

    public static List<String> lowerDateKeys() {
        return LOWER_DATE_KEYS;
    }

    public static List<String> upperDateKeys() {
        return UPPER_DATE_KEYS;
    }

    public static List<String> lowerMoneyKeys() {
        return LOWER_MONEY_KEYS;
    }

    public static List<String> upperMoneyKeys() {
        return UPPER_MONEY_KEYS;
    }

    public static List<String> lowerGenericKeys() {
        return LOWER_GENERIC_KEYS;
    }

    public static List<String> upperGenericKeys() {
        return UPPER_GENERIC_KEYS;
    }
}
