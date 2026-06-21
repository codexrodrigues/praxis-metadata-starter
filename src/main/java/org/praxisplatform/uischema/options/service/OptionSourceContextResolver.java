package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;

/**
 * Resolves private provider execution context for option-source operations.
 *
 * <p>
 * Hosts may replace the default resolver to pass tenant, user, session, datasource
 * handle, or other private execution attributes to providers. These attributes are
 * never part of the public {@code x-ui.optionSource}, OpenAPI schema, example payloads,
 * or error contract.
 * </p>
 */
public interface OptionSourceContextResolver {

    OptionSourceExecutionContext resolve(
            OptionSourceDescriptor descriptor,
            OptionSourceOperation operation
    );
}
