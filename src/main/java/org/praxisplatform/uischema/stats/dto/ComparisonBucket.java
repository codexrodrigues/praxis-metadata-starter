package org.praxisplatform.uischema.stats.dto;

import java.util.Map;

/** Unioned comparison bucket, keyed by the governed bucket identity. */
public record ComparisonBucket(Object key, String label, Map<String, ComparisonMetricValue> values) { }
