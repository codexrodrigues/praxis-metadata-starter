package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.List;

/** Resource-owned domain seam. All calls run inside the engine's operational transaction. */
public interface BulkDomainResourceAdapter<ID, F> {
    record Candidate(String observedVersion, JsonNode facts, JsonNode plan) { }

    F readFilter(JsonNode normalizedFilter);
    List<ID> selectBulkIdentities(F filter, Collection<ID> included, Collection<ID> excluded, int maxTargets);
    String currentVersion(ID id);
    Candidate evaluate(ID id, String expectedVersion, List<BulkFieldChange> changes,
                       org.praxisplatform.uischema.service.base.ResourceMutationContext context, BulkEditableFields fields);
    /** Locks, checks version and executes the same lifecycle as the unit update. */
    void update(ID id, String expectedVersion, JsonNode candidate,
                org.praxisplatform.uischema.service.base.ResourceMutationContext context);
    /**
     * Discards accidental evaluation-only changes without flushing them. Stateless adapters have
     * nothing to discard; persistence adapters must restore a clean context before the next target.
     */
    default void discardEvaluationState() { }
}
