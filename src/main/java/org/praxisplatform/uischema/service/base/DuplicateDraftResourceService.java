package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

/**
 * Servico resource-oriented com preparo opcional de rascunho de duplicacao, sem persistencia.
 *
 * <p>Pode ser implementado diretamente por servicos delegados a adaptadores externos ou por
 * subclasses de {@link AbstractBaseResourceService}; nao exige heranca JPA.</p>
 */
public interface DuplicateDraftResourceService<ResponseDTO, ID, FilterDTO extends GenericFilterDTO, CreateDTO, UpdateDTO, DraftDTO>
        extends BaseResourceService<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>,
        DuplicateDraftResourceCommandService<ID, DraftDTO> {
}
