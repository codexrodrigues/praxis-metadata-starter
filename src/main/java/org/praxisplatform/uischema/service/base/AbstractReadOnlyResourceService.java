package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;

import java.util.Collection;

/**
 * Base resource-oriented para recursos estritamente de consulta.
 *
 * <p>
 * Diferente do legado, a semantica de somente leitura fica no proprio boundary de service e nao em
 * um pseudo-CRUD que responde {@code 405} por heranca de escrita.
 * </p>
 */
public abstract class AbstractReadOnlyResourceService<
        E,
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO
> extends AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, Void, Void> {

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

    @Override
    public BaseResourceCommandService.SavedResult<ID, ResponseDTO> create(Void dto) {
        throw new UnsupportedOperationException("read-only resource");
    }

    @Override
    public ResponseDTO update(ID id, Void dto) {
        throw new UnsupportedOperationException("read-only resource");
    }

    @Override
    public void deleteById(ID id) {
        throw new UnsupportedOperationException("read-only resource");
    }

    @Override
    public void deleteAllById(Collection<ID> ids) {
        throw new UnsupportedOperationException("read-only resource");
    }
}
