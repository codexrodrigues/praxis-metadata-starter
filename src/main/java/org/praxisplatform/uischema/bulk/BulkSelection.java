package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/** Exactly one selection form. Query is an intent, not an already frozen or authorized set. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BulkSelection<WI, F>(BulkSelectionMode mode, List<BulkTarget<WI>> targets,
                                   F filter, List<WI> excludedIds) {
    public BulkSelection {
        Objects.requireNonNull(mode, "mode is required");
        if (mode == BulkSelectionMode.EXPLICIT) {
            targets = BulkContractChecks.nonEmpty(targets);
            BulkContractChecks.unique(targets.stream().map(BulkTarget::id).toList());
            if (filter != null || excludedIds != null)
                throw new IllegalArgumentException("Explicit selection cannot contain query bindings");
        } else {
            Objects.requireNonNull(filter, "filter is required");
            if (targets != null) throw new IllegalArgumentException("Query selection cannot contain targets");
            excludedIds = excludedIds == null ? List.of() : List.copyOf(excludedIds);
            BulkContractChecks.unique(excludedIds);
        }
    }
}
