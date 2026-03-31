package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

/**
 * Contrato agregado do novo core resource-oriented.
 */
public interface BaseResourceService<
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO,
        CreateDTO,
        UpdateDTO
> extends BaseResourceQueryService<ResponseDTO, ID, FilterDTO>,
        BaseResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {
}
