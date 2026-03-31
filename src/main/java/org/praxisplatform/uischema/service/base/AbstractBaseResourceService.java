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

    @Override
    protected abstract ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID> getResourceMapper();

    @Override
    @Transactional
    public BaseResourceCommandService.SavedResult<ID, ResponseDTO> create(CreateDTO dto) {
        E entity = getResourceMapper().newEntity(dto);
        E saved = refreshManaged(getRepository().save(entity));
        return new BaseResourceCommandService.SavedResult<>(extractId(saved), getResourceMapper().toResponse(saved));
    }

    @Override
    @Transactional
    public ResponseDTO update(ID id, UpdateDTO dto) {
        E existing = findEntityById(id);
        getResourceMapper().applyUpdate(existing, dto);
        E saved = refreshManaged(getRepository().save(existing));
        return getResourceMapper().toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteById(ID id) {
        getRepository().findById(id).ifPresent(getRepository()::delete);
    }

    @Override
    @Transactional
    public void deleteAllById(Collection<ID> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("ids must not be null");
        }
        getRepository().deleteAllById(ids);
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
