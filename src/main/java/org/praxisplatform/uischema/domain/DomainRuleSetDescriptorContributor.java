package org.praxisplatform.uischema.domain;

import java.util.List;

/**
 * Modular slice contributor for {@link DomainRuleSetDescriptor}s published in {@code /schemas/domain}.
 *
 * <p>Allows each domain slice or module to register its own {@code @Component} contributor without
 * editing a monolithic central catalog.</p>
 */
@FunctionalInterface
public interface DomainRuleSetDescriptorContributor {

    /**
     * Returns the rule set descriptors owned by this slice or module.
     */
    List<DomainRuleSetDescriptor> getDomainRuleSetDescriptors();
}
