package org.praxisplatform.uischema.customfields;

import java.util.List;

public class NoOpCustomFieldDefinitionProvider implements CustomFieldDefinitionProvider {
    @Override public List<CustomFieldDescriptor> findDefinitions(String resourceKey, String tenantId, String schemaType) {
        return List.of();
    }
}
