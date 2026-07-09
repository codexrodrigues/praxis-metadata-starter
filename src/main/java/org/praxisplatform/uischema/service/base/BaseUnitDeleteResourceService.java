package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

/**
 * Contrato agregado para resources que publicam leitura, criacao, atualizacao e exclusao unitaria,
 * sem exclusao em lote.
 */
public interface BaseUnitDeleteResourceService<
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO,
        CreateDTO,
        UpdateDTO
> extends BaseResourceQueryService<ResponseDTO, ID, FilterDTO>,
        BaseUnitDeleteResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO>,
        BaseCreateUpdateResourceService<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO> {
}
