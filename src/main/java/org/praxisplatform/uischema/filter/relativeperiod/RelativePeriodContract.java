package org.praxisplatform.uischema.filter.relativeperiod;

import java.util.Set;

public final class RelativePeriodContract {

    public static final String TODAY = "today";
    public static final String YESTERDAY = "yesterday";
    public static final String LAST_7 = "last7";
    public static final String LAST_30 = "last30";
    public static final String THIS_MONTH = "thisMonth";
    public static final String LAST_MONTH = "lastMonth";
    public static final String THIS_QUARTER = "thisQuarter";
    public static final String THIS_YEAR = "thisYear";

    public static final Set<String> SUPPORTED_PRESETS = Set.of(
            TODAY,
            YESTERDAY,
            LAST_7,
            LAST_30,
            THIS_MONTH,
            LAST_MONTH,
            THIS_QUARTER,
            THIS_YEAR
    );

    private RelativePeriodContract() {
    }
}
