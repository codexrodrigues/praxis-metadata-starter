package org.praxisplatform.uischema.determination;

import java.util.List;

/** Registry imutavel dos bindings estruturais declarados pelos providers do host. */
public interface ReactiveDeterminationDefinitionRegistry {

    List<ReactiveDeterminationDefinition> findBySchemaOperationId(String schemaOperationId);
}
