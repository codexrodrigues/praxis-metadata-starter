package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;

/**
 * Base Service para recursos somente leitura. Todas operações de escrita
 * lançam UnsupportedOperationException.
 */
public abstract class AbstractReadOnlyService<E, D, ID, FD extends GenericFilterDTO>
        extends AbstractBaseCrudService<E, D, ID, FD> {

    protected AbstractReadOnlyService(BaseCrudRepository<E, ID> repository,
                                      GenericSpecificationsBuilder<E> specificationsBuilder,
                                      Class<E> entityClass) {
        super(repository, specificationsBuilder, entityClass);
    }

    protected AbstractReadOnlyService(BaseCrudRepository<E, ID> repository,
                                      Class<E> entityClass) {
        super(repository, entityClass);
    }

    @Override
    public E save(E entity) { throw new UnsupportedOperationException("read-only resource"); }

    @Override
    public E update(ID id, E entity) { throw new UnsupportedOperationException("read-only resource"); }

    @Override
    public void deleteById(ID id) { throw new UnsupportedOperationException("read-only resource"); }

    @Override
    public void deleteAllById(Iterable<ID> ids) { throw new UnsupportedOperationException("read-only resource"); }
}

