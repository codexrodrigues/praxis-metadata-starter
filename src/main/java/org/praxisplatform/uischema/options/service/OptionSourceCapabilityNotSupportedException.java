package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;

/**
 * Raised when an option-source exists but does not support the requested operation.
 */
public class OptionSourceCapabilityNotSupportedException extends UnsupportedOperationException {

    public OptionSourceCapabilityNotSupportedException(
            OptionSourceDescriptor descriptor,
            OptionSourceOperation operation
    ) {
        super("Option source capability not supported for %s operation: %s"
                .formatted(operation, descriptor == null ? "<unknown>" : descriptor.key()));
    }
}
