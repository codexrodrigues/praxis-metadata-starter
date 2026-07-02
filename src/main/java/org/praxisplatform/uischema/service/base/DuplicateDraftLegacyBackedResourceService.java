package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

/**
 * Servico legacy-backed para resources que tambem publicam a operacao opcional de rascunho duplicado.
 */
public interface DuplicateDraftLegacyBackedResourceService<ResponseDTO, ID, FilterDTO extends GenericFilterDTO, CreateDTO, UpdateDTO, DraftDTO>
        extends LegacyBackedResourceService<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>,
        DuplicateDraftLegacyBackedResourceCommandService<ID, DraftDTO> {
}
