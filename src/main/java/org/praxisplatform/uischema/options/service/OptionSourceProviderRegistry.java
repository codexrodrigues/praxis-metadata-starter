package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;

/**
 * Resolves the provider responsible for an option-source operation.
 */
public interface OptionSourceProviderRegistry {

    OptionSourceProvider resolve(
            OptionSourceDescriptor descriptor,
            OptionSourceExecutionContext context,
            OptionSourceOperation operation
    );
}
