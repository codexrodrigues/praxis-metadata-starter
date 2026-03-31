package org.praxisplatform.uischema.controller.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;

/**
 * Variante query-only canonica para recursos somente leitura.
 *
 * <p>
 * Diferentemente do legado, esta base nao expoe endpoints de escrita para devolver {@code 405}.
 * A superficie HTTP publicada e estritamente a de leitura.
 * </p>
 */
public abstract class AbstractReadOnlyResourceController<ResponseDTO, ID, FD extends GenericFilterDTO>
        extends AbstractResourceQueryController<ResponseDTO, ID, FD> {

    @Override
    protected abstract BaseResourceQueryService<ResponseDTO, ID, FD> getService();

    @Override
    protected boolean isReadOnlyResource() {
        return true;
    }
}
