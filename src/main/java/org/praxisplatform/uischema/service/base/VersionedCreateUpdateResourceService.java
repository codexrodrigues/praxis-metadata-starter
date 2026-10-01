package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
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

    /**
     * Returns the body and its persisted revision after the last modifying hook and flush,
     * inside the mutation transaction. The revision must be present; a pre-flush revision,
     * the expected input revision or a later independent lookup cannot certify this result.
     * Implementations must validate the captured result with
     * {@link ResourceRepresentationResult#requirePersistedVersion()} before leaving that
     * transaction, so a missing revision rolls back the mutation. The controller's defensive
     * validation occurs after the service returns and cannot roll back an already committed
     * implementation that violates this contract.
     */
    ResourceRepresentationResult<ResponseDTO> update(
            ID id, UpdateDTO dto, ResourceVersionUpdatePrecondition<ID> precondition);
}
