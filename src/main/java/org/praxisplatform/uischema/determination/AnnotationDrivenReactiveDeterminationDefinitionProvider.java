package org.praxisplatform.uischema.determination;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.annotation.DeterminationInput;
import org.praxisplatform.uischema.annotation.DeterminationOutput;
import org.praxisplatform.uischema.annotation.DeterminationScope;
import org.praxisplatform.uischema.annotation.ReactiveDetermination;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider canonico que descobre declaracoes {@link ReactiveDetermination} diretamente nos metodos
 * HTTP dos controllers Spring ({@code PLAT-EVO-16}), eliminando classes manuais de configuracao
 * {@link ReactiveDeterminationDefinitionProvider} nos projetos hosts.
 */
public final class AnnotationDrivenReactiveDeterminationDefinitionProvider
        implements ReactiveDeterminationDefinitionProvider {

    @Nullable
    private final RequestMappingHandlerMapping handlerMapping;

    @Nullable
    private final ApplicationContext applicationContext;

    @Nullable
    private final List<Class<?>> explicitControllerClasses;

    public AnnotationDrivenReactiveDeterminationDefinitionProvider(
            @Nullable RequestMappingHandlerMapping handlerMapping,
            @Nullable ApplicationContext applicationContext
    ) {
        this.handlerMapping = handlerMapping;
        this.applicationContext = applicationContext;
        this.explicitControllerClasses = null;
    }

    private AnnotationDrivenReactiveDeterminationDefinitionProvider(Collection<Class<?>> controllerClasses) {
        this.handlerMapping = null;
        this.applicationContext = null;
        this.explicitControllerClasses = List.copyOf(Objects.requireNonNull(controllerClasses, "controllerClasses"));
    }

    /**
     * Cria uma instancia deterministica a partir de classes de controller explicitas, ideal para
     * testes unitarios e gates de conformidade sem subir contexto Spring completo.
     */
    public static AnnotationDrivenReactiveDeterminationDefinitionProvider fromControllerClasses(
            Class<?>... controllerClasses
    ) {
        if (controllerClasses == null || controllerClasses.length == 0) {
            return new AnnotationDrivenReactiveDeterminationDefinitionProvider(List.of());
        }
        return new AnnotationDrivenReactiveDeterminationDefinitionProvider(Arrays.asList(controllerClasses));
    }

    /**
     * Cria uma instancia deterministica a partir de uma colecao de classes de controller.
     */
    public static AnnotationDrivenReactiveDeterminationDefinitionProvider fromControllerClasses(
            Collection<Class<?>> controllerClasses
    ) {
        return new AnnotationDrivenReactiveDeterminationDefinitionProvider(
                controllerClasses == null ? List.of() : controllerClasses
        );
    }

    @Override
    public Collection<ReactiveDeterminationDefinition> definitions() {
        Set<Class<?>> candidateClasses = resolveCandidateControllerClasses();
        if (candidateClasses.isEmpty()) {
            return List.of();
        }

        Map<String, String> ownerById = new LinkedHashMap<>();
        List<ReactiveDeterminationDefinition> discovered = new ArrayList<>();

        for (Class<?> rawControllerClass : candidateClasses) {
            if (rawControllerClass == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(rawControllerClass);
            Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(
                    userClass,
                    method -> !method.isBridge() && !method.isSynthetic()
            );
            for (Method method : methods) {
                ReactiveDetermination annotation = AnnotatedElementUtils.findMergedAnnotation(
                        method,
                        ReactiveDetermination.class
                );
                if (annotation == null) {
                    continue;
                }
                ReactiveDeterminationDefinition definition = buildDefinition(userClass, method, annotation);
                String methodSignature = userClass.getName() + "#" + method.getName();
                String previousOwner = ownerById.putIfAbsent(definition.id(), methodSignature);
                if (previousOwner != null && !previousOwner.equals(methodSignature)) {
                    throw new IllegalStateException(
                            "Duplicate @ReactiveDetermination id '" + definition.id()
                                    + "' declared on both " + previousOwner + " and " + methodSignature
                    );
                }
                if (previousOwner == null) {
                    discovered.add(definition);
                }
            }
        }

        discovered.sort(Comparator.comparing(ReactiveDeterminationDefinition::id));
        return List.copyOf(discovered);
    }

    private Set<Class<?>> resolveCandidateControllerClasses() {
        Set<Class<?>> classes = new LinkedHashSet<>();
        if (explicitControllerClasses != null) {
            for (Class<?> controllerClass : explicitControllerClasses) {
                if (controllerClass != null) {
                    classes.add(ClassUtils.getUserClass(controllerClass));
                }
            }
            return classes;
        }

        if (handlerMapping != null) {
            for (HandlerMethod handlerMethod : handlerMapping.getHandlerMethods().values()) {
                if (handlerMethod != null && handlerMethod.getBeanType() != null) {
                    classes.add(ClassUtils.getUserClass(handlerMethod.getBeanType()));
                }
            }
        }

        if (applicationContext != null) {
            String[] controllerBeanNames = applicationContext.getBeanNamesForAnnotation(Controller.class);
            for (String beanName : controllerBeanNames) {
                Class<?> beanType = applicationContext.getType(beanName, false);
                if (beanType != null) {
                    classes.add(ClassUtils.getUserClass(beanType));
                }
            }
        }
        return classes;
    }

    private ReactiveDeterminationDefinition buildDefinition(
            Class<?> controllerClass,
            Method method,
            ReactiveDetermination annotation
    ) {
        String methodLocation = controllerClass.getSimpleName() + "#" + method.getName();
        String id = requireNonBlank(annotation.id(), "@ReactiveDetermination.id on " + methodLocation);

        Operation operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
        if (operation == null || !StringUtils.hasText(operation.operationId())) {
            throw new IllegalArgumentException(
                    "@ReactiveDetermination '" + id + "' on " + methodLocation
                            + " requires an explicit @Operation(operationId = \"...\") on the same handler method."
            );
        }
        String determinationOperationId = operation.operationId().trim();

        List<ReactiveDeterminationScope> scopes = resolveScopes(controllerClass, annotation, id, methodLocation);
        List<ReactiveDeterminationInputBinding> inputs = new ArrayList<>();
        List<String> autoSourcePaths = new ArrayList<>();
        boolean inferredInputs = annotation.inputs().length == 0;
        if (inferredInputs) {
            for (String prop : inferRequestBodyPropertyNames(controllerClass, method)) {
                String ptr = toJsonPointer(prop, "inferred @RequestBody field", id, methodLocation);
                inputs.add(new ReactiveDeterminationInputBinding(ptr, ptr));
                autoSourcePaths.add(ptr);
            }
        } else {
            for (DeterminationInput input : annotation.inputs()) {
                String fieldPath;
                String requestPath;
                if (StringUtils.hasText(input.value())) {
                    if (StringUtils.hasText(input.field()) || StringUtils.hasText(input.request())) {
                        throw new IllegalArgumentException(
                                "@ReactiveDetermination '" + id + "' on " + methodLocation
                                        + " must not combine DeterminationInput.value with field/request."
                        );
                    }
                    fieldPath = toJsonPointer(input.value(), "input.value", id, methodLocation);
                    requestPath = fieldPath;
                } else {
                    fieldPath = toJsonPointer(input.field(), "input.field", id, methodLocation);
                    requestPath = toJsonPointer(input.request(), "input.request", id, methodLocation);
                }
                inputs.add(new ReactiveDeterminationInputBinding(fieldPath, requestPath));
                if (input.triggerSource()) {
                    autoSourcePaths.add(fieldPath);
                }
            }
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException(
                    "@ReactiveDetermination '" + id + "' on " + methodLocation + " must declare at least one input."
            );
        }

        List<ReactiveDeterminationOutputBinding> outputs = new ArrayList<>();
        if (annotation.outputs().length == 0) {
            for (String prop : inferResponseDtoPropertyNames(controllerClass, method)) {
                String ptr = toJsonPointer(prop, "inferred ResponseEntity<T> field", id, methodLocation);
                outputs.add(new ReactiveDeterminationOutputBinding(ptr, ptr));
            }
        } else {
            for (DeterminationOutput output : annotation.outputs()) {
                String responsePath;
                String fieldPath;
                if (StringUtils.hasText(output.value())) {
                    if (StringUtils.hasText(output.response()) || StringUtils.hasText(output.field())) {
                        throw new IllegalArgumentException(
                                "@ReactiveDetermination '" + id + "' on " + methodLocation
                                        + " must not combine DeterminationOutput.value with response/field."
                        );
                    }
                    responsePath = toJsonPointer(output.value(), "output.value", id, methodLocation);
                    fieldPath = responsePath;
                } else {
                    responsePath = toJsonPointer(output.response(), "output.response", id, methodLocation);
                    fieldPath = toJsonPointer(output.field(), "output.field", id, methodLocation);
                }
                outputs.add(new ReactiveDeterminationOutputBinding(responsePath, fieldPath));
            }
        }
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException(
                    "@ReactiveDetermination '" + id + "' on " + methodLocation + " must declare at least one output."
            );
        }

        List<String> sourcePaths;
        if (annotation.sourceFields().length > 0) {
            sourcePaths = Arrays.stream(annotation.sourceFields())
                    .map(source -> toJsonPointer(source, "sourceFields", id, methodLocation))
                    .toList();
        } else if (annotation.triggerMode().requiresSourcePaths()) {
            sourcePaths = List.copyOf(new LinkedHashSet<>(autoSourcePaths));
            if (sourcePaths.isEmpty()) {
                throw new IllegalArgumentException(
                        "@ReactiveDetermination '" + id + "' on " + methodLocation
                                + " requires sourcePaths when triggerMode is " + annotation.triggerMode()
                );
            }
        } else {
            sourcePaths = List.of();
        }

        ReactiveDeterminationProvenance provenance = new ReactiveDeterminationProvenance(
                Objects.requireNonNull(annotation.provenanceKind(), "provenanceKind"),
                requireNonBlank(annotation.provenanceSource(), "provenanceSource on " + methodLocation),
                annotation.provenanceVersion() == null ? "" : annotation.provenanceVersion().trim()
        );

        return new ReactiveDeterminationDefinition(
                id,
                List.copyOf(scopes),
                determinationOperationId,
                annotation.triggerMode(),
                List.copyOf(sourcePaths),
                List.copyOf(inputs),
                List.copyOf(outputs),
                provenance
        );
    }

    private List<ReactiveDeterminationScope> resolveScopes(
            Class<?> controllerClass,
            ReactiveDetermination annotation,
            String id,
            String methodLocation
    ) {
        List<ReactiveDeterminationScope> scopes = new ArrayList<>();
        if (annotation.scopes().length > 0) {
            if (StringUtils.hasText(annotation.createOperationId()) || StringUtils.hasText(annotation.updateOperationId())) {
                throw new IllegalArgumentException(
                        "@ReactiveDetermination '" + id + "' on " + methodLocation
                                + " cannot combine scopes() with createOperationId/updateOperationId."
                );
            }
            for (DeterminationScope scope : annotation.scopes()) {
                String schemaOpId = requireNonBlank(
                        scope.schemaOperationId(),
                        "DeterminationScope.schemaOperationId on " + methodLocation
                );
                scopes.add(new ReactiveDeterminationScope(schemaOpId, Objects.requireNonNull(scope.formMode())));
            }
            return scopes;
        }

        String resolvedCreate = StringUtils.hasText(annotation.createOperationId())
                ? annotation.createOperationId().trim()
                : resolveControllerOperationId(controllerClass, true);
        String resolvedUpdate = StringUtils.hasText(annotation.updateOperationId())
                ? annotation.updateOperationId().trim()
                : resolveControllerOperationId(controllerClass, false);

        boolean hasCreate = StringUtils.hasText(resolvedCreate);
        boolean hasUpdate = StringUtils.hasText(resolvedUpdate);
        if (hasCreate) {
            scopes.add(new ReactiveDeterminationScope(resolvedCreate, ReactiveDeterminationFormMode.CREATE));
        }
        if (hasUpdate) {
            if (hasCreate && resolvedUpdate.equals(resolvedCreate)) {
                throw new IllegalArgumentException(
                        "@ReactiveDetermination '" + id + "' on " + methodLocation
                                + " must resolve distinct createOperationId and updateOperationId."
                );
            }
            scopes.add(new ReactiveDeterminationScope(resolvedUpdate, ReactiveDeterminationFormMode.EDIT));
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException(
                    "@ReactiveDetermination '" + id + "' on " + methodLocation
                            + " must declare createOperationId, updateOperationId, or at least one @DeterminationScope."
            );
        }
        return scopes;
    }

    private String resolveControllerOperationId(Class<?> controllerClass, boolean create) {
        Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(
                controllerClass,
                method -> !method.isBridge() && !method.isSynthetic()
        );
        for (Method candidate : methods) {
            if (AnnotatedElementUtils.hasAnnotation(candidate, ReactiveDetermination.class)
                    || AnnotatedElementUtils.hasAnnotation(candidate, org.praxisplatform.uischema.annotation.WorkflowAction.class)
                    || AnnotatedElementUtils.hasAnnotation(candidate, org.praxisplatform.uischema.annotation.UiSurface.class)) {
                continue;
            }
            if (create && isCanonicalCreateMethod(candidate)) {
                return extractOperationId(candidate);
            }
            if (!create && isCanonicalUpdateMethod(candidate)) {
                return extractOperationId(candidate);
            }
        }
        return null;
    }

    private static boolean isCanonicalCreateMethod(Method method) {
        var postMapping = AnnotatedElementUtils.findMergedAnnotation(
                method, org.springframework.web.bind.annotation.PostMapping.class);
        if (postMapping != null) {
            return hasRootSubPath(postMapping.value(), postMapping.path());
        }
        var requestMapping = AnnotatedElementUtils.findMergedAnnotation(
                method, org.springframework.web.bind.annotation.RequestMapping.class);
        if (requestMapping != null) {
            for (var rm : requestMapping.method()) {
                if (rm == org.springframework.web.bind.annotation.RequestMethod.POST) {
                    return hasRootSubPath(requestMapping.value(), requestMapping.path());
                }
            }
        }
        return false;
    }

    private static boolean isCanonicalUpdateMethod(Method method) {
        var putMapping = AnnotatedElementUtils.findMergedAnnotation(
                method, org.springframework.web.bind.annotation.PutMapping.class);
        if (putMapping != null) {
            return matchesUpdateSubPath(putMapping.value(), putMapping.path());
        }
        var requestMapping = AnnotatedElementUtils.findMergedAnnotation(
                method, org.springframework.web.bind.annotation.RequestMapping.class);
        if (requestMapping != null) {
            for (var rm : requestMapping.method()) {
                if (rm == org.springframework.web.bind.annotation.RequestMethod.PUT) {
                    return matchesUpdateSubPath(requestMapping.value(), requestMapping.path());
                }
            }
        }
        return false;
    }

    private static boolean hasRootSubPath(String[] values, String[] paths) {
        List<String> all = new ArrayList<>();
        if (values != null) Collections.addAll(all, values);
        if (paths != null) Collections.addAll(all, paths);
        return all.isEmpty() || all.stream().anyMatch(p -> !StringUtils.hasText(p) || "/".equals(p.trim()));
    }

    private static boolean matchesUpdateSubPath(String[] values, String[] paths) {
        List<String> all = new ArrayList<>();
        if (values != null) Collections.addAll(all, values);
        if (paths != null) Collections.addAll(all, paths);
        return all.stream().anyMatch(p -> p != null && ("/{id}".equals(p.trim()) || "{id}".equals(p.trim())));
    }

    private static String extractOperationId(Method method) {
        Operation op = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
        if (op != null && StringUtils.hasText(op.operationId())) {
            return op.operationId().trim();
        }
        return method.getName();
    }

    private static List<String> inferRequestBodyPropertyNames(Class<?> controllerClass, Method method) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            var mp = new org.springframework.core.MethodParameter(method, i).withContainingClass(controllerClass);
            boolean isBody = params[i].isAnnotationPresent(org.springframework.web.bind.annotation.RequestBody.class)
                    || mp.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
            if (!isBody) {
                continue;
            }
            Class<?> paramType = org.springframework.core.ResolvableType
                    .forMethodParameter(mp)
                    .resolve(mp.getParameterType());
            return collectIntrospectablePropertyNames(paramType);
        }
        return List.of();
    }

    private static List<String> inferResponseDtoPropertyNames(Class<?> controllerClass, Method method) {
        org.springframework.core.ResolvableType returnType =
                org.springframework.core.ResolvableType.forMethodReturnType(method, controllerClass);
        Class<?> raw = returnType.resolve();
        if (raw != null && org.springframework.http.ResponseEntity.class.isAssignableFrom(raw)) {
            returnType = returnType.getGeneric(0);
            raw = returnType.resolve();
        }
        if (raw != null && org.praxisplatform.uischema.rest.response.RestApiResponse.class.isAssignableFrom(raw)) {
            returnType = returnType.getGeneric(0);
            raw = returnType.resolve();
        }
        return collectIntrospectablePropertyNames(raw);
    }

    private static List<String> collectIntrospectablePropertyNames(Class<?> type) {
        if (!isIntrospectableDtoType(type)) {
            return List.of();
        }
        if (type.isRecord()) {
            List<String> names = new ArrayList<>();
            for (var rc : type.getRecordComponents()) {
                if (!rc.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                    names.add(rc.getName());
                }
            }
            return List.copyOf(names);
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> curr = type; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            hierarchy.add(curr);
        }
        Collections.reverse(hierarchy);
        for (Class<?> curr : hierarchy) {
            for (var field : curr.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || field.isSynthetic()
                        || field.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                    continue;
                }
                if (seen.add(field.getName())) {
                    names.add(field.getName());
                }
            }
        }
        return List.copyOf(names);
    }

    private static boolean isIntrospectableDtoType(Class<?> type) {
        if (type == null || type == Object.class || type == Void.class || type == void.class
                || type.isPrimitive() || type.isEnum() || type.isArray()
                || Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) {
            return false;
        }
        String pkg = type.getPackageName();
        return !(pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("jakarta."));
    }

    private static String toJsonPointer(String rawPath, String attrName, String id, String methodLocation) {
        String trimmed = requireNonBlank(
                rawPath,
                "@ReactiveDetermination '" + id + "' " + attrName + " on " + methodLocation
        );
        String pointer = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        if (pointer.length() < 2 || pointer.endsWith("/")) {
            throw new IllegalArgumentException(
                    "Invalid JSON Pointer '" + rawPath + "' for " + attrName + " in @ReactiveDetermination '"
                            + id + "' on " + methodLocation
            );
        }
        return pointer;
    }

    private static String requireNonBlank(String value, String context) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(context + " must not be blank.");
        }
        return value.trim();
    }
}
