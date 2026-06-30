package org.praxisplatform.uischema.options;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.util.Collection;
import java.util.List;

/**
 * Canonical request envelope for option-source selected-value reload by IDs.
 *
 * <p>The public HTTP endpoint remains
 * {@code GET /{resource}/option-sources/{sourceKey}/options/by-ids?ids=...}.
 * This envelope gives provider-backed services the same typed extension point
 * used by filter requests, while keeping ID order stable.</p>
 */
public record OptionSourceByIdsRequest<FD extends GenericFilterDTO>(
        FD filter,
        Collection<Object> ids
) {

    public OptionSourceByIdsRequest {
        ids = ids == null ? List.of() : List.copyOf(ids);
    }
}
