package org.praxisplatform.uischema.determination;

import java.util.List;

/** Registry imutavel dos bindings estruturais declarados pelos providers do host. */
public interface ReactiveDeterminationDefinitionRegistry {

    /** Exact form-operation identities declared by the captured registry, for canonical discovery. */
    java.util.Set<String> schemaOperationIds();

    List<ReactiveDeterminationDefinition> findBySchemaOperationId(String schemaOperationId);
}
