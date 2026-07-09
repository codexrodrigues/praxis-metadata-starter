package org.praxisplatform.uischema.options;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.util.Collection;
import java.util.List;

/**
 * Canonical request envelope for option-source selected-value reload by IDs.
 *
 * <p>{@code GET /{resource}/option-sources/{sourceKey}/options/by-ids?ids=...}
 * remains the lightweight reload path for self-sufficient IDs. The contextual
 * {@code POST /{resource}/option-sources/{sourceKey}/options/by-ids} path uses
 * this envelope to pass the same governed filter context used by filter
 * requests, while keeping ID order stable.</p>
 */
public record OptionSourceByIdsRequest<FD extends GenericFilterDTO>(
        FD filter,
        Collection<Object> ids
) {

    public OptionSourceByIdsRequest {
        ids = ids == null ? List.of() : List.copyOf(ids);
    }
}
