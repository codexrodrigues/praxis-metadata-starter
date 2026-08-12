package org.praxisplatform.uischema.formeffect;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import org.praxisplatform.uischema.OperationProperties;
import org.praxisplatform.uischema.annotation.FormDetermination;
import org.praxisplatform.uischema.annotation.FormEffect;
import org.praxisplatform.uischema.annotation.FormEffectInput;
import org.praxisplatform.uischema.annotation.FormEffectOutput;
import org.praxisplatform.uischema.annotation.FormEffectWritePolicy;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Publishes validated {@code x-ui.formEffects} on concrete OpenAPI operations. */
public final class FormEffectOpenApiCustomizer implements GlobalOpenApiCustomizer {

    private static final String X_UI = "x-ui";

    private final RequestMappingHandlerMapping handlerMapping;
    private final CanonicalOperationResolver operationResolver;
    private final SchemaReferenceResolver schemaReferenceResolver;

    public FormEffectOpenApiCustomizer(
            RequestMappingHandlerMapping handlerMapping,
            CanonicalOperationResolver operationResolver,
            SchemaReferenceResolver schemaReferenceResolver
    ) {
        this.handlerMapping = handlerMapping;
        this.operationResolver = operationResolver;
        this.schemaReferenceResolver = schemaReferenceResolver;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void customise(OpenAPI openApi) {
        if (openApi == null || openApi.getPaths() == null) {
            return;
        }

        Map<String, DeterminationOperation> determinations = collectDeterminations(openApi);
        Map<String, Set<String>> targetsBySourceOperation = new HashMap<>();
        Map<String, Set<String>> idsBySourceOperation = new HashMap<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Collection<FormEffect> annotations = AnnotatedElementUtils.getMergedRepeatableAnnotations(
                    entry.getValue().getMethod(), FormEffect.class);
            if (annotations.isEmpty()) {
                continue;
            }

            CanonicalOperationRef sourceRef = operationResolver.resolve(entry.getValue(), entry.getKey());
            Operation sourceOperation = findOperation(openApi, sourceRef);
            if (sourceOperation == null) {
                // Global customizers also receive every grouped OpenAPI document. An annotated
                // handler that belongs to another group is outside the current document's scope.
                continue;
            }
            Schema<?> sourceRequest = requestSchema(sourceOperation);
            require(sourceRequest != null, "Form effects require a typed request body: " + describe(sourceRef));

            List<Map<String, Object>> published = new ArrayList<>();
            for (FormEffect annotation : annotations) {
                published.add(mapAndValidate(
                        annotation,
                        sourceRef,
                        sourceRequest,
                        determinations,
                        openApi.getComponents(),
                        idsBySourceOperation.computeIfAbsent(describe(sourceRef), ignored -> new HashSet<>()),
                        targetsBySourceOperation.computeIfAbsent(describe(sourceRef), ignored -> new HashSet<>())
                ));
            }

            Set<String> allTriggers = new LinkedHashSet<>();
            for (Map<String, Object> effect : published) {
                Object triggerValue = effect.get("trigger");
                if (triggerValue instanceof Map<?, ?> trigger) {
                    Object fieldsValue = trigger.get("fields");
                    if (fieldsValue instanceof Collection<?> fields) {
                        fields.forEach(field -> allTriggers.add(String.valueOf(field)));
                    }
                }
            }
            Set<String> chainedFields = new LinkedHashSet<>(
                    targetsBySourceOperation.getOrDefault(describe(sourceRef), Set.of()));
            chainedFields.retainAll(allTriggers);
            require(chainedFields.isEmpty(),
                    "Form effect chaining is not supported on " + describe(sourceRef)
                            + "; fields are both targets and triggers: " + chainedFields);

            Map<String, Object> extensions = sourceOperation.getExtensions();
            if (extensions == null) {
                extensions = new LinkedHashMap<>();
                sourceOperation.setExtensions(extensions);
            }
            Map<String, Object> xUi = (Map<String, Object>) extensions.computeIfAbsent(
                    X_UI, ignored -> new LinkedHashMap<>());
            xUi.put(OperationProperties.FORM_EFFECTS, published);
        }
    }

    private Map<String, DeterminationOperation> collectDeterminations(OpenAPI openApi) {
        Map<String, DeterminationOperation> result = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (AnnotationUtils.findAnnotation(handler.getMethod(), FormDetermination.class) == null) {
                continue;
            }
            require(AnnotationUtils.findAnnotation(handler.getMethod(), WorkflowAction.class) == null,
                    "@FormDetermination cannot also be @WorkflowAction: " + handler.getMethod());
            require(AnnotationUtils.findAnnotation(handler.getMethod(), UiSurface.class) == null,
                    "@FormDetermination cannot also be @UiSurface: " + handler.getMethod());

            CanonicalOperationRef ref = operationResolver.resolve(handler, entry.getKey());
            Operation operation = findOperation(openApi, ref);
            if (operation == null) {
                // Determinations from other OpenAPI groups are irrelevant until a source
                // operation in this document explicitly references one of them.
                continue;
            }
            require("post".equalsIgnoreCase(ref.method()),
                    "Form determinations must use POST: " + describe(ref));
            String operationId = text(operation.getOperationId())
                    ? operation.getOperationId().trim()
                    : ref.operationId();
            require(text(operationId), "Form determination requires an OpenAPI operationId: " + describe(ref));
            require(requestSchema(operation) != null,
                    "Form determination requires a typed request body: " + operationId);
            require(responseSchema(operation) != null,
                    "Form determination requires a typed success response: " + operationId);
            require(result.putIfAbsent(operationId, new DeterminationOperation(ref, operation)) == null,
                    "Duplicate form determination operationId: " + operationId);
        }
        return result;
    }

    private Map<String, Object> mapAndValidate(
            FormEffect annotation,
            CanonicalOperationRef sourceRef,
            Schema<?> sourceRequest,
            Map<String, DeterminationOperation> determinations,
            Components components,
            Set<String> sourceIds,
            Set<String> sourceTargets
    ) {
        String id = requiredText(annotation.id(), "Form effect id");
        require(sourceIds.add(id), "Duplicate form effect id '" + id + "' on " + describe(sourceRef));
        require(annotation.debounceMs() >= 0 && annotation.debounceMs() <= 60_000,
                "Form effect debounceMs must be between 0 and 60000: " + id);

        DeterminationOperation determination = determinations.get(requiredText(
                annotation.operationId(), "Form effect operationId"));
        require(determination != null,
                "Form effect '" + id + "' references an operation that is not @FormDetermination: "
                        + annotation.operationId());

        Set<String> formFields = schemaProperties(sourceRequest, components);
        Set<String> determinationInputs = schemaProperties(requestSchema(determination.operation()), components);
        Set<String> determinationOutputs = schemaProperties(responseSchema(determination.operation()), components);

        List<String> triggerFields = normalizedDistinct(annotation.triggerFields(), "triggerFields", id);
        require(!triggerFields.isEmpty(), "Form effect requires at least one trigger field: " + id);
        triggerFields.forEach(field -> require(formFields.contains(field),
                "Unknown trigger field '" + field + "' in form effect '" + id + "'"));

        List<Map<String, Object>> inputs = new ArrayList<>();
        require(annotation.inputs().length > 0, "Form effect requires at least one input binding: " + id);
        Set<String> boundOperationInputs = new LinkedHashSet<>();
        for (FormEffectInput input : annotation.inputs()) {
            String formField = requiredText(input.formField(), "input.formField");
            String operationField = requiredText(input.operationField(), "input.operationField");
            require(formFields.contains(formField),
                    "Unknown form input field '" + formField + "' in form effect '" + id + "'");
            require(determinationInputs.contains(operationField),
                    "Unknown determination request field '" + operationField + "' in form effect '" + id + "'");
            require(areCompatible(
                            findProperty(sourceRequest, formField, components, new HashSet<>()),
                            findProperty(requestSchema(determination.operation()), operationField, components, new HashSet<>()),
                            components),
                    "Incompatible input binding '" + formField + "' -> '" + operationField
                            + "' in form effect '" + id + "'");
            require(boundOperationInputs.add(operationField),
                    "Duplicate determination request binding '" + operationField + "' in form effect '" + id + "'");
            inputs.add(Map.of("formField", formField, "operationField", operationField));
        }

        List<Map<String, Object>> outputs = new ArrayList<>();
        require(annotation.outputs().length > 0, "Form effect requires at least one output binding: " + id);
        Set<String> effectTargets = new LinkedHashSet<>();
        for (FormEffectOutput output : annotation.outputs()) {
            String operationField = requiredText(output.operationField(), "output.operationField");
            String formField = requiredText(output.formField(), "output.formField");
            require(determinationOutputs.contains(operationField),
                    "Unknown determination response field '" + operationField + "' in form effect '" + id + "'");
            require(formFields.contains(formField),
                    "Unknown form output field '" + formField + "' in form effect '" + id + "'");
            require(areCompatible(
                            findProperty(responseSchema(determination.operation()), operationField, components, new HashSet<>()),
                            findProperty(sourceRequest, formField, components, new HashSet<>()),
                            components),
                    "Incompatible output binding '" + operationField + "' -> '" + formField
                            + "' in form effect '" + id + "'");
            require(effectTargets.add(formField),
                    "Duplicate output target '" + formField + "' in form effect '" + id + "'");
            require(sourceTargets.add(formField),
                    "Form output field '" + formField + "' is targeted by more than one effect on " + describe(sourceRef));
            if (output.writePolicy() == FormEffectWritePolicy.REPLACE) {
                require(isReadOnlyProperty(sourceRequest, formField, components),
                        "Write policy replace requires a read-only target field: " + formField);
            }
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("operationField", operationField);
            binding.put("formField", formField);
            binding.put("writePolicy", output.writePolicy().wireValue());
            outputs.add(binding);
        }

        CanonicalSchemaRef requestRef = schemaReferenceResolver.requestSchema(determination.ref());
        CanonicalSchemaRef responseRef = schemaReferenceResolver.responseSchema(determination.ref());
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", annotation.operationId().trim());
        operation.put("path", determination.ref().path());
        operation.put("method", determination.ref().method().toUpperCase(Locale.ROOT));
        operation.put("requestSchemaUrl", requestRef.url());
        operation.put("responseSchemaUrl", responseRef.url());

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("event", annotation.trigger().wireValue());
        trigger.put("fields", triggerFields);
        trigger.put("debounceMs", annotation.debounceMs());
        trigger.put("requiresValidSources", annotation.requiresValidSources());

        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("id", id);
        effect.put("trigger", trigger);
        effect.put("operation", operation);
        effect.put("inputs", inputs);
        effect.put("outputs", outputs);
        return effect;
    }

    private Schema<?> requestSchema(Operation operation) {
        if (operation == null || operation.getRequestBody() == null) {
            return null;
        }
        return preferredSchema(operation.getRequestBody().getContent());
    }

    private Schema<?> responseSchema(Operation operation) {
        if (operation == null || operation.getResponses() == null) {
            return null;
        }
        for (String status : List.of("200", "201", "202")) {
            if (operation.getResponses().get(status) != null) {
                Schema<?> schema = preferredSchema(operation.getResponses().get(status).getContent());
                if (schema != null) {
                    return schema;
                }
            }
        }
        return null;
    }

    private Schema<?> preferredSchema(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        MediaType mediaType = content.get("application/json");
        if (mediaType == null) {
            mediaType = content.values().iterator().next();
        }
        return mediaType == null ? null : mediaType.getSchema();
    }

    private Set<String> schemaProperties(Schema<?> schema, Components components) {
        Schema<?> resolved = resolveSchema(schema, components, new HashSet<>());
        Set<String> properties = new LinkedHashSet<>();
        collectProperties(resolved, components, properties, new HashSet<>());
        return properties;
    }

    private void collectProperties(
            Schema<?> schema,
            Components components,
            Set<String> properties,
            Set<String> visitedRefs
    ) {
        Schema<?> resolved = resolveSchema(schema, components, visitedRefs);
        if (resolved == null) {
            return;
        }
        if (resolved.getProperties() != null) {
            properties.addAll(resolved.getProperties().keySet());
            Object dataProperty = resolved.getProperties().get("data");
            if (dataProperty instanceof Schema<?> dataSchema) {
                collectProperties(dataSchema, components, properties, visitedRefs);
            }
        }
        if (resolved.getAllOf() != null) {
            resolved.getAllOf().forEach(item -> collectProperties(item, components, properties, visitedRefs));
        }
    }

    private boolean isReadOnlyProperty(Schema<?> schema, String field, Components components) {
        Schema<?> property = findProperty(schema, field, components, new HashSet<>());
        return property != null && Boolean.TRUE.equals(property.getReadOnly());
    }

    private Schema<?> findProperty(
            Schema<?> schema,
            String field,
            Components components,
            Set<String> visitedRefs
    ) {
        Schema<?> resolved = resolveSchema(schema, components, visitedRefs);
        if (resolved == null) {
            return null;
        }
        if (resolved.getProperties() != null) {
            Object property = resolved.getProperties().get(field);
            if (property instanceof Schema<?> propertySchema) {
                return propertySchema;
            }
            Object data = resolved.getProperties().get("data");
            if (data instanceof Schema<?> dataSchema) {
                Schema<?> nested = findProperty(dataSchema, field, components, visitedRefs);
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (resolved.getAllOf() != null) {
            for (Schema<?> item : resolved.getAllOf()) {
                Schema<?> nested = findProperty(item, field, components, visitedRefs);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private boolean areCompatible(Schema<?> source, Schema<?> target, Components components) {
        Schema<?> resolvedSource = resolveSchema(source, components, new HashSet<>());
        Schema<?> resolvedTarget = resolveSchema(target, components, new HashSet<>());
        if (resolvedSource == null || resolvedTarget == null) {
            return true;
        }
        String sourceType = resolvedSource.getType();
        String targetType = resolvedTarget.getType();
        if (text(sourceType) && text(targetType) && !sourceType.equals(targetType)) {
            boolean bothNumeric = Set.of("integer", "number").contains(sourceType)
                    && Set.of("integer", "number").contains(targetType);
            if (!bothNumeric) {
                return false;
            }
        }
        String sourceFormat = resolvedSource.getFormat();
        String targetFormat = resolvedTarget.getFormat();
        return !text(sourceFormat) || !text(targetFormat) || sourceFormat.equals(targetFormat);
    }

    private Schema<?> resolveSchema(Schema<?> schema, Components components, Set<String> visitedRefs) {
        if (schema == null || schema.get$ref() == null) {
            return schema;
        }
        String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        if (!visitedRefs.add(name) || components == null || components.getSchemas() == null) {
            return null;
        }
        return components.getSchemas().get(name);
    }

    private List<String> normalizedDistinct(String[] values, String label, String effectId) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                result.add(requiredText(value, label + " item in " + effectId));
            }
        }
        return List.copyOf(result);
    }

    private Operation findOperation(OpenAPI openApi, CanonicalOperationRef operationRef) {
        if (operationRef == null || operationRef.path() == null || operationRef.method() == null) {
            return null;
        }
        PathItem pathItem = openApi.getPaths().get(operationRef.path());
        if (pathItem == null) {
            return null;
        }
        return switch (operationRef.method().toLowerCase(Locale.ROOT)) {
            case "get" -> pathItem.getGet();
            case "post" -> pathItem.getPost();
            case "put" -> pathItem.getPut();
            case "patch" -> pathItem.getPatch();
            case "delete" -> pathItem.getDelete();
            case "head" -> pathItem.getHead();
            case "options" -> pathItem.getOptions();
            case "trace" -> pathItem.getTrace();
            default -> null;
        };
    }

    private String requiredText(String value, String label) {
        require(text(value), label + " must not be blank");
        return value.trim();
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private String describe(CanonicalOperationRef ref) {
        return ref == null ? "<unresolved>" : ref.method() + " " + ref.path();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record DeterminationOperation(CanonicalOperationRef ref, Operation operation) {
    }
}
