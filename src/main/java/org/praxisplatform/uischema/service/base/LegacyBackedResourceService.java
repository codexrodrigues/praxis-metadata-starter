package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

/**
 * Servico completo para resources mutaveis resource-oriented com escrita delegada ao host legado.
 */
public interface LegacyBackedResourceService<ResponseDTO, ID, FilterDTO extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends BaseResourceService<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>,
        LegacyBackedResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {
}
