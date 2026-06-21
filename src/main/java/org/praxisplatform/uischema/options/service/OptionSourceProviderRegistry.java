package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;

/**
 * Resolves the provider responsible for an option-source operation.
 *
 * <p>
 * Host-specific providers can use {@code Ordered} or {@code @Order} to run before the
 * default JPA provider. If two providers at the same priority support the same
 * descriptor and operation, resolution fails as a configuration error instead of
 * choosing one implicitly.
 * </p>
 */
public interface OptionSourceProviderRegistry {

    OptionSourceProvider resolve(
            OptionSourceDescriptor descriptor,
            OptionSourceExecutionContext context,
            OptionSourceOperation operation
    );
}
