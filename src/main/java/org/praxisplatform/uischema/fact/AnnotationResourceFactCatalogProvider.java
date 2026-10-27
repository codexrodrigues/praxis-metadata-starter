package org.praxisplatform.uischema.fact;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        Set<Class<?>> inspectedTypes = new HashSet<>();
        Set<Method> factoryDeclarations = new HashSet<>();
        if (beanFactory instanceof ConfigurableListableBeanFactory factory) {
            for (String name : factory.getBeanDefinitionNames()) {
                // Resolve types without creation before examining factory metadata.
                factory.getType(name, false);
                collectFactoryDeclarations(factory.getMergedBeanDefinition(name), factory, factoryDeclarations,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
            }
        }
        for (String name : beanFactory.getBeanDefinitionNames()) {
            inspectType(beanFactory.getType(name, false), facts, inspectedTypes, factoryDeclarations);
            if (beanFactory instanceof ConfigurableListableBeanFactory factory) {
                inspectDefinition(factory.getMergedBeanDefinition(name), factory, facts, inspectedTypes, factoryDeclarations,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
                // Existing proxies expose target-class metadata without resolving their target.
                Object singleton = factory.getSingleton(name);
                if (singleton != null) inspectType(AopUtils.getTargetClass(singleton), facts, inspectedTypes, factoryDeclarations);
            }
        }
        return List.copyOf(facts.values());
    }

    private void inspectDefinition(BeanDefinition definition, ConfigurableListableBeanFactory factory,
            Map<String, ResourceFactDescriptor> facts, Set<Class<?>> inspectedTypes, Set<Method> factoryDeclarations,
            Set<BeanDefinition> inspectedDefinitions) {
        if (!inspectedDefinitions.add(definition)) return;
        inspectType(definition.getResolvableType().resolve(), facts, inspectedTypes, factoryDeclarations);
        if (definition.getFactoryMethodName() == null) {
            if (definition instanceof AbstractBeanDefinition concrete && concrete.hasBeanClass()) {
                inspectType(concrete.getBeanClass(), facts, inspectedTypes, factoryDeclarations);
            }
        } else {
            Method resolved = definition instanceof RootBeanDefinition root ? root.getResolvedFactoryMethod() : null;
            if (resolved != null) {
                inspectFactoryMethod(resolved, facts, inspectedTypes, factoryDeclarations);
            } else {
                List<Method> candidates = factoryMethods(definition, factory);
                if (candidates.size() == 1) {
                    inspectFactoryMethod(candidates.getFirst(), facts, inspectedTypes, factoryDeclarations);
                } else if (candidates.stream().anyMatch(this::declaresFacts)) {
                    throw new IllegalStateException("Ambiguous fact factory method: " + definition.getFactoryMethodName());
                }
            }
        }
        BeanDefinition origin = definition.getOriginatingBeanDefinition();
        if (origin != null) inspectDefinition(origin, factory, facts, inspectedTypes, factoryDeclarations, inspectedDefinitions);
        if (definition instanceof RootBeanDefinition root && root.getDecoratedDefinition() != null) {
            inspectDefinition(root.getDecoratedDefinition().getBeanDefinition(), factory, facts, inspectedTypes, factoryDeclarations,
                    inspectedDefinitions);
        }
    }

    private void collectFactoryDeclarations(BeanDefinition definition, ConfigurableListableBeanFactory factory,
            Set<Method> declarations, Set<BeanDefinition> visited) {
        if (!visited.add(definition)) return;
        if (definition.getFactoryMethodName() != null) declarations.addAll(factoryMethods(definition, factory));
        if (definition.getOriginatingBeanDefinition() != null) {
            collectFactoryDeclarations(definition.getOriginatingBeanDefinition(), factory, declarations, visited);
        }
        if (definition instanceof RootBeanDefinition root && root.getDecoratedDefinition() != null) {
            collectFactoryDeclarations(root.getDecoratedDefinition().getBeanDefinition(), factory, declarations, visited);
        }
    }

    private List<Method> factoryMethods(BeanDefinition definition, ConfigurableListableBeanFactory factory) {
        String ownerName = definition.getFactoryBeanName();
        Class<?> factoryType;
        if (ownerName == null) {
            factoryType = definitionType(definition);
        } else {
            Object owner = factory.getSingleton(ownerName);
            factoryType = owner != null ? AopUtils.getTargetClass(owner) : factory.getType(ownerName, false);
            if (owner == null && factory.containsBeanDefinition(ownerName)) {
                Class<?> declaredType = definitionType(factory.getMergedBeanDefinition(ownerName));
                if (declaredType != null) factoryType = declaredType;
            }
        }
        if (factoryType == null) return List.of();
        return Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(ClassUtils.getUserClass(factoryType)))
                .filter(method -> method.getName().equals(definition.getFactoryMethodName())
                        && !method.isBridge() && !method.isSynthetic()
                        && Modifier.isStatic(method.getModifiers()) == (definition.getFactoryBeanName() == null))
                .toList();
    }

    private Class<?> definitionType(BeanDefinition definition) {
        return definition.getFactoryMethodName() == null
                && definition instanceof AbstractBeanDefinition concrete && concrete.hasBeanClass()
                ? concrete.getBeanClass() : definition.getResolvableType().resolve();
    }

    private boolean declaresFacts(Method factoryMethod) {
        if (!AnnotatedElementUtils.getMergedRepeatableAnnotations(factoryMethod, ResourceFact.class).isEmpty()) return true;
        return declaresFacts(factoryMethod.getReturnType(), new HashSet<>());
    }

    private boolean declaresFacts(Class<?> type, Set<Class<?>> visited) {
        if (!visited.add(type)) return false;
        if (!AnnotatedElementUtils.getMergedRepeatableAnnotations(type, ResourceFact.class).isEmpty()) return true;
        if (Arrays.stream(ReflectionUtils.getAllDeclaredMethods(type))
                .anyMatch(method -> !AnnotatedElementUtils.getMergedRepeatableAnnotations(method, ResourceFact.class).isEmpty())) {
            return true;
        }
        return ClassUtils.getAllInterfacesForClassAsSet(type).stream().anyMatch(contract -> declaresFacts(contract, visited));
    }

    private void inspectFactoryMethod(Method method, Map<String, ResourceFactDescriptor> facts,
            Set<Class<?>> inspectedTypes, Set<Method> factoryDeclarations) {
        add(method, facts);
        inspectType(method.getReturnType(), facts, inspectedTypes, factoryDeclarations);
    }

    private void inspectType(Class<?> type, Map<String, ResourceFactDescriptor> facts, Set<Class<?>> inspectedTypes,
            Set<Method> factoryDeclarations) {
        if (type == null) return;
        type = ClassUtils.getUserClass(type);
        if (!inspectedTypes.add(type)) return;
        add(type, facts);
        ReflectionUtils.doWithMethods(type, method -> add(method, facts),
                method -> ReflectionUtils.USER_DECLARED_METHODS.matches(method) && !factoryDeclarations.contains(method));
        for (Class<?> contract : ClassUtils.getAllInterfacesForClassAsSet(type)) {
            inspectType(contract, facts, inspectedTypes, factoryDeclarations);
        }
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
