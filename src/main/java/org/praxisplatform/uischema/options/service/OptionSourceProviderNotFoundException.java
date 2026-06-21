package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;

/**
 * Raised when a registered option source has no provider for the requested operation.
 */
public class OptionSourceProviderNotFoundException extends UnsupportedOperationException {

    public OptionSourceProviderNotFoundException(
            OptionSourceDescriptor descriptor,
            OptionSourceOperation operation
    ) {
        super("Option source provider not found for %s operation: %s".formatted(
                operation,
                descriptor == null ? "<unknown>" : descriptor.key()
        ));
    }
}
