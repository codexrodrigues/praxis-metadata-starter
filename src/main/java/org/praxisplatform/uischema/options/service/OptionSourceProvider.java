package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Internal provider SPI for option-source execution.
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
