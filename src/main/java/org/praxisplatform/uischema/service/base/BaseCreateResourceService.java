package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

/** Contrato agregado para resources que publicam leitura e criacao, sem atualizacao ou exclusao. */
public interface BaseCreateResourceService<
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO,
        CreateDTO
> extends BaseResourceQueryService<ResponseDTO, ID, FilterDTO>,
        BaseCreateResourceCommandService<ResponseDTO, ID, CreateDTO> {
}
