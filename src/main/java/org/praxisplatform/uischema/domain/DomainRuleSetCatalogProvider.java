package org.praxisplatform.uischema.domain;

import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.violation.RuleErrorCatalog;

import java.util.Collection;
import java.util.List;

/**
 * SPI de host para publicação de {@link RuleSetDefinition}s governados e suas raízes ontológicas
 * de fatos ({@code draft}, {@code current}, {@code <conceito>}, {@code neighborhood}, {@code context})
 * no catálogo semântico {@code /schemas/domain}.
 */
@FunctionalInterface
public interface DomainRuleSetCatalogProvider {

    List<DomainRuleSetDescriptor> findRuleSets(String resourceKey, String tenantId);

    static DomainRuleSetCatalogProvider empty() {
        return (resourceKey, tenantId) -> List.of();
    }

    static DomainRuleSetCatalogProvider ofDescriptors(Collection<DomainRuleSetDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return empty();
        }
        return of(descriptors, List.of(), null);
    }

    static DomainRuleSetCatalogProvider ofDefinitions(Collection<RuleSetDefinition> definitions) {
        return ofDefinitions(definitions, null);
    }

    static DomainRuleSetCatalogProvider ofDefinitions(
            Collection<RuleSetDefinition> definitions,
            RuleErrorCatalog errorCatalog
    ) {
        return of(List.of(), definitions, errorCatalog);
    }

    static DomainRuleSetCatalogProvider of(
            Collection<DomainRuleSetDescriptor> explicitDescriptors,
            Collection<RuleSetDefinition> definitions,
            RuleErrorCatalog errorCatalog
    ) {
        java.util.Map<String, DomainRuleSetDescriptor> byKey = new java.util.LinkedHashMap<>();
        if (explicitDescriptors != null) {
            for (DomainRuleSetDescriptor descriptor : explicitDescriptors) {
                if (descriptor != null) {
                    String key = descriptor.ruleSetKey() + "::" + descriptor.operationKey();
                    if (byKey.putIfAbsent(key, descriptor) != null)
                        throw new IllegalArgumentException("Duplicate RuleSet operation descriptor: " + key);
                }
            }
        }
        if (definitions != null) {
            for (RuleSetDefinition definition : definitions) {
                if (definition != null) {
                    String key = definition.ref().ruleSetKey() + "::" + definition.ref().operationKey();
                    if (byKey.containsKey(key)) continue;
                    DomainRuleSetDescriptor derived = DomainRuleSetDescriptor.of(definition, errorCatalog, java.util.Map.of());
                    byKey.putIfAbsent(derived.ruleSetKey() + "::" + derived.operationKey(), derived);
                }
            }
        }
        if (byKey.isEmpty()) {
            return empty();
        }
        List<DomainRuleSetDescriptor> descriptors = List.copyOf(byKey.values());
        return (resourceKey, tenantId) -> descriptors.stream()
                .filter(d -> d.resourceKey().equals(resourceKey))
                .toList();
    }
}
