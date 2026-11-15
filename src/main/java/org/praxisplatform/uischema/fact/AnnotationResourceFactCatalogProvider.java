package org.praxisplatform.uischema.fact;

import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/** Inspects bean types without instantiating beans or executing domain computations. */
public final class AnnotationResourceFactCatalogProvider implements ResourceFactCatalogProvider {
    private record InspectionKey(Class<?> type, Class<?> ownerContext) {}

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
        Set<InspectionKey> inspectedTypes = new HashSet<>();
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
            inspectType(beanFactory.getType(name, false), null, facts, inspectedTypes, factoryDeclarations);
            if (beanFactory instanceof ConfigurableListableBeanFactory factory) {
                inspectDefinition(factory.getMergedBeanDefinition(name), factory, facts, inspectedTypes, factoryDeclarations,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
                // Existing proxies expose target-class metadata without resolving their target.
                Object singleton = factory.getSingleton(name);
                if (singleton != null) inspectType(AopUtils.getTargetClass(singleton), null, facts, inspectedTypes, factoryDeclarations);
            }
        }
        return List.copyOf(facts.values());
    }

    private void inspectDefinition(BeanDefinition definition, ConfigurableListableBeanFactory factory,
            Map<String, ResourceFactDescriptor> facts, Set<InspectionKey> inspectedTypes, Set<Method> factoryDeclarations,
            Set<BeanDefinition> inspectedDefinitions) {
        if (!inspectedDefinitions.add(definition)) return;
        inspectType(definition.getResolvableType().resolve(), null, facts, inspectedTypes, factoryDeclarations);
        if (definition.getFactoryMethodName() == null) {
            if (definition instanceof AbstractBeanDefinition concrete && concrete.hasBeanClass()) {
                inspectType(concrete.getBeanClass(), null, facts, inspectedTypes, factoryDeclarations);
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
            Set<InspectionKey> inspectedTypes, Set<Method> factoryDeclarations) {
        Class<?> returnType = method.getReturnType();
        add(method, returnType, facts);
        inspectType(returnType, null, facts, inspectedTypes, factoryDeclarations);
    }

    private void inspectType(Class<?> type, Class<?> ownerContext, Map<String, ResourceFactDescriptor> facts,
            Set<InspectionKey> inspectedTypes, Set<Method> factoryDeclarations) {
        if (type == null || type == Object.class || type == Record.class) return;
        type = ClassUtils.getUserClass(type);
        Class<?> effectiveOwner = ownerContext != null ? ClassUtils.getUserClass(ownerContext) : type;
        if (!inspectedTypes.add(new InspectionKey(type, effectiveOwner))) return;
        add(type, effectiveOwner, facts);
        ReflectionUtils.doWithMethods(type, method -> add(method, effectiveOwner, facts),
                method -> ReflectionUtils.USER_DECLARED_METHODS.matches(method) && !factoryDeclarations.contains(method));
        for (Class<?> contract : ClassUtils.getAllInterfacesForClassAsSet(type)) {
            inspectType(contract, effectiveOwner, facts, inspectedTypes, factoryDeclarations);
        }
        if (AbstractResourceQueryController.class.isAssignableFrom(type)) {
            Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(type, AbstractResourceQueryController.class);
            if (typeArgs != null && typeArgs.length >= 1 && typeArgs[0] != null && typeArgs[0] != Object.class) {
                inspectType(typeArgs[0], type, facts, inspectedTypes, factoryDeclarations);
            }
        }
    }

    private void add(AnnotatedElement element, Class<?> ownerContext, Map<String, ResourceFactDescriptor> facts) {
        for (ResourceFact fact : AnnotatedElementUtils.getMergedRepeatableAnnotations(element, ResourceFact.class)) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (String parameter : fact.parameters()) {
                if (!parameter.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid fact parameter");
                parameters.put(parameter, Map.of("type", "string"));
            }
            String effectiveResourceKey = resolveResourceKey(fact, element, ownerContext);
            var descriptor = new ResourceFactDescriptor(fact.key(), effectiveResourceKey, fact.label(),
                    fact.description(), fact.dataType(), fact.unit(), parameters, fact.deterministic(),
                    fact.sourceKind(), governance(element), fact.icon(), fact.tone(),
                    fact.valuePresentation(), thresholds(fact),
                    fact.authority(), fact.evaluationPhase(), fact.missingValuePolicy(), fact.scope());
            String key = descriptor.resourceKey() + ".fact." + descriptor.key();
            var previous = facts.putIfAbsent(key, descriptor);
            if (previous != null && !previous.equals(descriptor)) throw new IllegalStateException("Conflicting fact declaration: " + key);
        }
    }

    private String resolveResourceKey(ResourceFact fact, AnnotatedElement element, Class<?> ownerContext) {
        String declaredKey = fact.resourceKey() == null ? "" : fact.resourceKey().strip();
        Class<?> resolvedOwner = ownerContext != null
                ? ClassUtils.getUserClass(ownerContext)
                : (element instanceof Class<?> cls ? ClassUtils.getUserClass(cls)
                   : (element instanceof Method method ? ClassUtils.getUserClass(method.getDeclaringClass()) : null));
        String ownerApiResourceKey = resolveApiResourceKey(resolvedOwner);
        if (!declaredKey.isEmpty() && !ownerApiResourceKey.isEmpty() && !declaredKey.equals(ownerApiResourceKey)) {
            String location = resolvedOwner != null ? resolvedOwner.getName() : String.valueOf(element);
            throw new IllegalStateException(
                    "Conflicting @ResourceFact.resourceKey '" + declaredKey
                            + "' and @ApiResource.resourceKey '" + ownerApiResourceKey + "' on " + location);
        }
        return !declaredKey.isEmpty() ? declaredKey : ownerApiResourceKey;
    }

    private String resolveApiResourceKey(Class<?> candidate) {
        if (candidate == null) {
            return "";
        }
        ApiResource resource = AnnotatedElementUtils.findMergedAnnotation(candidate, ApiResource.class);
        return (resource != null && StringUtils.hasText(resource.resourceKey()))
                ? resource.resourceKey().strip()
                : "";
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> governance(AnnotatedElement element) {
        Class<?> ownerClass = element instanceof Method method
                ? method.getDeclaringClass()
                : (element instanceof Class<?> clazz ? clazz : null);
        com.fasterxml.jackson.databind.node.ObjectNode ownerTypeGov = ownerClass != null
                ? org.praxisplatform.uischema.schema.DomainLineageResolver.resolveTypeGovernance(ownerClass)
                : null;
        org.praxisplatform.uischema.annotation.DomainLineage lineage =
                AnnotatedElementUtils.findMergedAnnotation(element, org.praxisplatform.uischema.annotation.DomainLineage.class);
        DomainGovernance directGov = AnnotatedElementUtils.findMergedAnnotation(element, DomainGovernance.class);
        if (lineage != null) {
            var resolved = org.praxisplatform.uischema.schema.DomainLineageResolver.resolve(lineage, directGov, ownerTypeGov);
            if (resolved.weakeningAttempted()) {
                throw new IllegalArgumentException(resolved.weakeningReason());
            }
            Map<String, Object> ext = org.praxisplatform.uischema.schema.DomainLineageResolver.toExtensionMap(resolved.effectiveGovernance());
            return ext != null ? ext : Map.of();
        }
        com.fasterxml.jackson.databind.node.ObjectNode directNode =
                org.praxisplatform.uischema.schema.DomainLineageResolver.toGovernanceNode(directGov);
        com.fasterxml.jackson.databind.node.ObjectNode merged =
                org.praxisplatform.uischema.schema.DomainSchemaGovernance.mergeOrCopy(ownerTypeGov, directNode);
        if (merged == null) return Map.of();
        Map<String, Object> ext = org.praxisplatform.uischema.schema.DomainLineageResolver.toExtensionMap(merged);
        return ext != null ? ext : Map.of();
    }

    private List<Map<String, Object>> thresholds(ResourceFact fact) {
        if (fact.thresholds() == null || fact.thresholds().length == 0) return List.of();
        List<Map<String, Object>> items = new ArrayList<>(fact.thresholds().length);
        for (org.praxisplatform.uischema.annotation.Threshold threshold : fact.thresholds()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (!Double.isNaN(threshold.min())) entry.put("min", threshold.min());
            if (!Double.isNaN(threshold.max())) entry.put("max", threshold.max());
            if (!Double.isNaN(threshold.value())) entry.put("value", threshold.value());
            if (threshold.equalsValue() != null && !threshold.equalsValue().isBlank()) entry.put("equals", threshold.equalsValue());
            if (threshold.tone() != null && !threshold.tone().isBlank()) entry.put("tone", threshold.tone());
            if (threshold.label() != null && !threshold.label().isBlank()) entry.put("label", threshold.label());
            if (!entry.isEmpty()) items.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(items);
    }

}
