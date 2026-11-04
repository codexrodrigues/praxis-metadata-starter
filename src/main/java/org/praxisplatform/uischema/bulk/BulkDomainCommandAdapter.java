package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;

/** Domain command extension; evaluation is detached, mutation must lock and check the supplied ETag. */
public interface BulkDomainCommandAdapter<ID> {
    BulkDomainResourceAdapter.Candidate evaluate(ID id, String expectedVersion, JsonNode parameters);
    void execute(ID id, String expectedVersion, JsonNode evaluatedPlan);
}
