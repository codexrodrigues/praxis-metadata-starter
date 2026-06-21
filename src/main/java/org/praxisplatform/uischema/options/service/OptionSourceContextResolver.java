package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;

/**
 * Resolves internal provider execution context for option-source operations.
 */
public interface OptionSourceContextResolver {

    OptionSourceExecutionContext resolve(
            OptionSourceDescriptor descriptor,
            OptionSourceOperation operation
    );
}
