package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;

import java.util.Map;

/**
 * Default context resolver with no host-specific private attributes.
 */
public class DefaultOptionSourceContextResolver implements OptionSourceContextResolver {

    @Override
    public OptionSourceExecutionContext resolve(
            OptionSourceDescriptor descriptor,
            OptionSourceOperation operation
    ) {
        return new OptionSourceExecutionContext(
                descriptor.key(),
                descriptor.type(),
                descriptor.resourcePath(),
                operation,
                Map.of()
        );
    }
}
