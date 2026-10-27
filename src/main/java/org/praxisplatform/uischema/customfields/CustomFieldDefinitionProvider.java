package org.praxisplatform.uischema.customfields;

import java.util.List;

/** Host-owned, tenant-scoped definitions. Implementations must return only active definitions. */
public interface CustomFieldDefinitionProvider {
    List<CustomFieldDescriptor> findDefinitions(String resourceKey, String tenantId, String schemaType);
    default List<CustomFieldDescriptor> findDefinitions(String resourceKey, String tenantId) {
        return findDefinitions(resourceKey, tenantId, null);
    }
}
