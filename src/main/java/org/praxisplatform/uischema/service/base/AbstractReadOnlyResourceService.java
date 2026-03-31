package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;

/**
 * Base resource-oriented para recursos estritamente de consulta.
 *
 * <p>
 * Diferente do legado, recursos somente leitura agora nascem de um boundary query-only real e nao
 * herdam create/update/delete apenas para desabilita-los.
 * </p>
 */
public abstract class AbstractReadOnlyResourceService<
        E,
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO
> extends AbstractBaseQueryResourceService<E, ResponseDTO, ID, FilterDTO> {

    protected AbstractReadOnlyResourceService(
            BaseCrudRepository<E, ID> repository,
            GenericSpecificationsBuilder<E> specificationsBuilder,
            Class<E> entityClass
    ) {
        super(repository, specificationsBuilder, entityClass);
    }

    protected AbstractReadOnlyResourceService(BaseCrudRepository<E, ID> repository, Class<E> entityClass) {
        super(repository, entityClass);
    }
}
