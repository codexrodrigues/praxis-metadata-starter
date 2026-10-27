package org.praxisplatform.uischema.fact;
import java.util.List;
/** Host SPI for tenant-scoped computed or historical fact definitions. */
@FunctionalInterface
public interface ResourceFactCatalogProvider {
    List<ResourceFactDescriptor> findFacts(String resourceKey, String tenantId);
}
