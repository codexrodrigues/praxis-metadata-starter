package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Convenience base class that wires required components for {@link BaseCrudService} implementations
 * and applies transactional semantics to write operations.
 *
 * @param <E>  Entity type
 * @param <D>  DTO type
 * @param <ID> Identifier type
 * @param <FD> Filter DTO type
 */
public abstract class AbstractBaseCrudService<E, D, ID, FD extends GenericFilterDTO>
        implements BaseCrudService<E, D, ID, FD> {

    private final BaseCrudRepository<E, ID> repository;
    private final GenericSpecificationsBuilder<E> specificationsBuilder;
    private final Class<E> entityClass;

    protected AbstractBaseCrudService(BaseCrudRepository<E, ID> repository,
                                      GenericSpecificationsBuilder<E> specificationsBuilder,
                                      Class<E> entityClass) {
        this.repository = repository;
        this.specificationsBuilder = specificationsBuilder;
        this.entityClass = entityClass;
    }

    protected AbstractBaseCrudService(BaseCrudRepository<E, ID> repository,
                                      Class<E> entityClass) {
        this(repository, new GenericSpecificationsBuilder<>(), entityClass);
    }

    @Override
    public BaseCrudRepository<E, ID> getRepository() {
        return repository;
    }

    @Override
    public GenericSpecificationsBuilder<E> getSpecificationsBuilder() {
        return specificationsBuilder;
    }

    @Override
    public Class<E> getEntityClass() {
        return entityClass;
    }

    @Override
    @Transactional
    public E save(E entity) {
        return BaseCrudService.super.save(entity);
    }

    @Override
    @Transactional
    public E update(ID id, E entity) {
        return BaseCrudService.super.update(id, entity);
    }

    @Override
    @Transactional
    public void deleteById(ID id) {
        BaseCrudService.super.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteAllById(Iterable<ID> ids) {
        BaseCrudService.super.deleteAllById(ids);
    }
}
