package org.praxisplatform.uischema.fact;

import org.springframework.beans.factory.ListableBeanFactory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.AnnotatedElement;
import java.util.*;

/** Inspects bean types without instantiating beans or executing domain computations. */
public final class AnnotationResourceFactCatalogProvider implements ResourceFactCatalogProvider {
    private final ListableBeanFactory beanFactory;
    private volatile List<ResourceFactDescriptor> catalog;
    public AnnotationResourceFactCatalogProvider(ListableBeanFactory beanFactory) { this.beanFactory = beanFactory; }
    @Override public List<ResourceFactDescriptor> findFacts(String resourceKey, String tenantId) {
        List<ResourceFactDescriptor> snapshot = catalog;
        if (snapshot == null) {
            synchronized (this) {
                if (catalog == null) catalog = discover();
                snapshot = catalog;
            }
        }
        return snapshot.stream().filter(f -> f.resourceKey().equals(resourceKey)).toList();
    }
    private List<ResourceFactDescriptor> discover() {
        Map<String, ResourceFactDescriptor> facts = new TreeMap<>();
        for (String name : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(name, false);
            if (type == null) continue;
            add(type, facts);
            ReflectionUtils.doWithMethods(type, method -> add(method, facts), ReflectionUtils.USER_DECLARED_METHODS);
        }
        return List.copyOf(facts.values());
    }
    private void add(AnnotatedElement element, Map<String, ResourceFactDescriptor> facts) {
        for (ResourceFact fact : AnnotatedElementUtils.getMergedRepeatableAnnotations(element, ResourceFact.class)) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (String parameter : fact.parameters()) {
                if (!parameter.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid fact parameter");
                parameters.put(parameter, Map.of("type", "string"));
            }
            var descriptor = new ResourceFactDescriptor(fact.key(), fact.resourceKey(), fact.label(),
                    fact.description(), fact.dataType(), fact.unit(), parameters, fact.deterministic(), fact.sourceKind(), governance(element));
            String key = descriptor.resourceKey() + ".fact." + descriptor.key();
            var previous = facts.putIfAbsent(key, descriptor);
            if (previous != null && !previous.equals(descriptor)) throw new IllegalStateException("Conflicting fact declaration: " + key);
        }
    }
    private Map<String, Object> governance(AnnotatedElement element) {
        DomainGovernance value = AnnotatedElementUtils.findMergedAnnotation(element, DomainGovernance.class);
        if (value == null) return Map.of();
        return Map.of("annotationType", value.kind().wireValue(), "classification", value.classification().wireValue(),
                "dataCategory", value.dataCategory().wireValue(), "complianceTags", List.of(value.complianceTags()),
                "reason", value.reason(), "confidence", value.confidence(),
                "aiUsage", Map.of("visibility", value.aiUsage().visibility().wireValue(),
                        "trainingUse", value.aiUsage().trainingUse().wireValue(),
                        "ruleAuthoring", value.aiUsage().ruleAuthoring().wireValue(),
                        "reasoningUse", value.aiUsage().reasoningUse().wireValue()));
    }

}
