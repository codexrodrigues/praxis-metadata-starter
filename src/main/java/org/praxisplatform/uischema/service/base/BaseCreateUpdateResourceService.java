package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

/**
 * Contrato agregado para resources que publicam leitura, criacao e atualizacao, sem exclusao.
 */
public interface BaseCreateUpdateResourceService<
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO,
        CreateDTO,
        UpdateDTO
> extends BaseResourceQueryService<ResponseDTO, ID, FilterDTO>,
        BaseCreateUpdateResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {
}
