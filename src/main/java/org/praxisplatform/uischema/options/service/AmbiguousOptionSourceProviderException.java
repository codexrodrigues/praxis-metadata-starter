package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;

/**
 * Raised when provider ordering cannot select a single option-source provider.
 */
public class AmbiguousOptionSourceProviderException extends UnsupportedOperationException {

    public AmbiguousOptionSourceProviderException(
            OptionSourceDescriptor descriptor,
            OptionSourceOperation operation,
            OptionSourceProvider first,
            OptionSourceProvider second
    ) {
        super("Ambiguous option source providers for %s operation %s"
                .formatted(
                        descriptor == null ? "<unknown>" : descriptor.key(),
                        operation
                ));
    }
}
