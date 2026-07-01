package org.praxisplatform.uischema.controller.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.LegacyBackedResourceService;

/**
 * Base canonica para recursos mutaveis cujo contrato publico e Praxis-native, mas cuja escrita
 * e executada por um adaptador legado do host.
 *
 * <p>
 * Esta base publica apenas o baseline CRUD herdado de {@link AbstractResourceController}. Recursos
 * que tambem oferecem {@code duplicate-draft} devem estender
 * {@link AbstractDuplicateDraftLegacyBackedResourceController}, mantendo a operacao opcional como
 * opt-in real no OpenAPI, em capabilities e em _links.
 * </p>
 */
public abstract class AbstractLegacyBackedResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends AbstractResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> {

    @Override
    protected abstract LegacyBackedResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> getService();
}
