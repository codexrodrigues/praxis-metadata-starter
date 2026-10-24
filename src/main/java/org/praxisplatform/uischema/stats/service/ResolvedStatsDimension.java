package org.praxisplatform.uischema.stats.service;

import org.praxisplatform.uischema.stats.StatsDimensionExtraction;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.dto.StatsDimensionRequest;

/**
 * Dimensao validada e associada ao seu {@link StatsFieldDescriptor} canonico.
 */
public record ResolvedStatsDimension(
        StatsDimensionRequest request,
        StatsFieldDescriptor descriptor
) {
    public String field() {
        return descriptor.field();
    }

    public StatsDimensionExtraction extraction() {
        return request.effectiveExtraction();
    }

    public String alias() {
        return request.effectiveAlias();
    }
}
