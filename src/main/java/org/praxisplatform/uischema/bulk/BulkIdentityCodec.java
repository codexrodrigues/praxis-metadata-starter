package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Converts a bulk-operation identity between its exact JSON wire representation
 * and the resource's domain identifier type.
 *
 * @param <WI> the validated wire identity type
 * @param <ID> the resource domain identity type
 */
public interface BulkIdentityCodec<WI, ID> {

    WI readWire(JsonNode node);

    ID decode(WI wire);

    WI encode(ID id);

    Schema<?> wireSchema();

    String codecId();
}
