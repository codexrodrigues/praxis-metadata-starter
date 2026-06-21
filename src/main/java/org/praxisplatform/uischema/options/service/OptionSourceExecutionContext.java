package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceType;

import java.util.Map;

/**
 * Internal execution context for option-source providers.
 */
public record OptionSourceExecutionContext(
        String sourceKey,
        OptionSourceType sourceType,
        String resourcePath,
        OptionSourceOperation operation,
        Map<String, Object> attributes
) {
    public OptionSourceExecutionContext {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
