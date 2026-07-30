package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

/**
 * Opt-in resource contract for updates protected by a strong item {@code ETag}/{@code If-Match}.
 *
 * <p>The implementation must lock/read the current persisted version and call
 * {@link ResourceVersionUpdatePrecondition#requireMatch(long)} inside the same transaction that
 * performs the update. This keeps ordinary non-versioned resources source compatible.</p>
 */
public interface VersionedCreateUpdateResourceService<
        ResponseDTO,
        ID,
        FilterDTO extends GenericFilterDTO,
        CreateDTO,
        UpdateDTO
> extends BaseCreateUpdateResourceService<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO> {

    @Override
    default ResponseDTO update(ID id, UpdateDTO dto) {
        throw ResourceVersionPreconditionException.required();
    }

    ResponseDTO update(ID id, UpdateDTO dto, ResourceVersionUpdatePrecondition<ID> precondition);
}
