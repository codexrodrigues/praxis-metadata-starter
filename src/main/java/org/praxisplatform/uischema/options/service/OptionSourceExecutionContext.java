package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceType;

import java.util.Map;

/**
 * Private execution context passed to option-source providers.
 *
 * <p>
 * The structural fields identify the public source and operation being executed. The
 * {@code attributes} map is reserved for host-private data resolved by
 * {@link OptionSourceContextResolver}; it must not be copied to schemas, OpenAPI,
 * response payloads, examples, logs intended for clients, or error messages.
 * </p>
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
