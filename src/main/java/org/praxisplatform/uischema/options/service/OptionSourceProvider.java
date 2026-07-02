package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Supported host extension point for option-source execution.
 *
 * <p>
 * Implement this SPI when a host needs to serve a public Praxis option source from a
 * backend that is not the starter's default JPA path, such as an external catalog,
 * registered query, table function, remote service, or another host-specific lookup
 * mechanism. The public contract remains the canonical Praxis endpoint and
 * {@code x-ui.optionSource}; private execution details stay inside the host provider.
 * </p>
 *
 * <p>
 * Providers are resolved after Praxis validates public request semantics such as
 * search policy, page size, includeIds, structured filters, capabilities, dependencies
 * and sort. Host providers may use {@link OptionSourceExecutionRequest#sortKey()} and
 * the already validated {@code Pageable}, but must not publish SQL, datasource,
 * package, bind parameters, provider names, or context attributes through schemas,
 * OpenAPI, errors, or {@code OptionDTO.extra}.
 * </p>
 *
 * <p>
 * For {@link #byIds(OptionSourceExecutionRequest)}, providers should return concrete
 * {@link OptionDTO} instances for found ids and omit ids that were not found. The
 * canonical executor also normalizes provider output before publishing the HTTP
 * response, removing {@code null} items and options without ids.
 * </p>
 */
public interface OptionSourceProvider {

    boolean supports(
            OptionSourceDescriptor descriptor,
            OptionSourceExecutionContext context,
            OptionSourceOperation operation
    );

    Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request);

    List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request);
}
