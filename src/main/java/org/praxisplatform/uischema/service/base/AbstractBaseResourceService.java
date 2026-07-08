package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/**
 * Base transacional mutante do novo core resource-oriented.
 *
 * <p>
 * Toda a superficie de leitura vive em {@link AbstractBaseQueryResourceService}. Esta classe sobe
 * apenas quando o recurso precisa de create/update/delete, evitando que recursos read-only herdem
 * semantica de comando desabilitada.
 * </p>
 *
 * <p>
 * O contrato desta base pressupoe que a escrita do recurso continua sendo modelada por operacoes
 * tipadas e por um {@link ResourceMapper} canonico, sem introduzir dispatchers genericos,
 * contratos paralelos ou paths especiais para semantica que deveria permanecer
 * resource-oriented.
 * </p>
 */
public abstract class AbstractBaseResourceService<
        E,
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO,
        CreateDTO,
        UpdateDTO
> extends AbstractBaseQueryResourceService<E, ResponseDTO, ID, FilterDTO>
        implements BaseResourceService<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO> {

    protected AbstractBaseResourceService(
            BaseCrudRepository<E, ID> repository,
            GenericSpecificationsBuilder<E> specificationsBuilder,
            Class<E> entityClass
    ) {
        super(repository, specificationsBuilder, entityClass);
    }

    protected AbstractBaseResourceService(BaseCrudRepository<E, ID> repository, Class<E> entityClass) {
        super(repository, entityClass);
    }

    /**
     * Fornece o mapper canonico responsavel por traduzir DTOs de escrita e resposta.
     */
    @Override
    protected abstract ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID> getResourceMapper();

    @Override
    @Transactional
    public BaseResourceCommandService.SavedResult<ID, ResponseDTO> create(CreateDTO dto) {
        E entity = getResourceMapper().newEntity(dto);
        beforeCreate(dto, entity);
        E saved = refreshManaged(getRepository().save(entity));
        afterCreate(dto, saved);
        return new BaseResourceCommandService.SavedResult<>(extractId(saved), getResourceMapper().toResponse(saved));
    }

    @Override
    @Transactional
    public ResponseDTO update(ID id, UpdateDTO dto) {
        E existing = findEntityById(id);
        beforeUpdate(id, existing, dto);
        getResourceMapper().applyUpdate(existing, dto);
        E saved = refreshManaged(getRepository().save(existing));
        afterUpdate(id, saved, dto);
        return getResourceMapper().toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteById(ID id) {
        getRepository().findById(id).ifPresent(entity -> {
            beforeDelete(id, entity);
            getRepository().delete(entity);
            afterDelete(id, entity);
        });
    }

    @Override
    @Transactional
    public void deleteAllById(Collection<ID> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("ids must not be null");
        }
        beforeDeleteAllById(ids);
        getRepository().deleteAllById(ids);
        afterDeleteAllById(ids);
    }

    /**
     * Hook transacional chamado depois que o mapper cria a entidade e antes do primeiro save.
     */
    protected void beforeCreate(CreateDTO dto, E entity) {
    }

    /**
     * Hook transacional chamado depois do save/refresh e antes da conversao para response DTO.
     */
    protected void afterCreate(CreateDTO dto, E entity) {
    }

    /**
     * Hook transacional chamado depois da carga da entidade existente e antes do mapper aplicar o update.
     */
    protected void beforeUpdate(ID id, E entity, UpdateDTO dto) {
    }

    /**
     * Hook transacional chamado depois do save/refresh e antes da conversao para response DTO.
     */
    protected void afterUpdate(ID id, E entity, UpdateDTO dto) {
    }

    /**
     * Hook transacional chamado antes da exclusao individual, quando a entidade existe.
     */
    protected void beforeDelete(ID id, E entity) {
    }

    /**
     * Hook transacional chamado depois da exclusao individual, quando a entidade existe.
     */
    protected void afterDelete(ID id, E entity) {
    }

    /**
     * Hook transacional chamado antes da exclusao em lote por IDs.
     */
    protected void beforeDeleteAllById(Collection<ID> ids) {
    }

    /**
     * Hook transacional chamado depois da exclusao em lote por IDs.
     */
    protected void afterDeleteAllById(Collection<ID> ids) {
    }

    private E refreshManaged(E entity) {
        if (getEntityManager() == null) {
            return entity;
        }
        getEntityManager().flush();
        E managed = getEntityManager().contains(entity) ? entity : getEntityManager().merge(entity);
        getEntityManager().refresh(managed);
        return managed;
    }
}
