package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;

/**
 * Base resource-oriented para recursos estritamente de consulta.
 *
 * <p>
 * Recursos somente leitura nascem de um boundary query-only real e nao carregam
 * responsabilidades de command.
 * </p>
 *
 * <p>
 * Esta base e a contrapartida de servico de
 * {@link org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController} e deve
 * ser a escolha padrao quando o recurso publica apenas leitura, filtros, stats e discovery.
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
