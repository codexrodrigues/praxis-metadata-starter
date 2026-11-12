package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.*;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.*;

/** Generates typed protocol schemas in the canonical OpenAPI document, before its publication fence. */
public final class BulkEvaluationSchemaSynthesizer {
    private BulkEvaluationSchemaSynthesizer() { }

    public static void synthesize(OpenAPI document, BulkResourceOperationBindings bindings, ObjectMapper mapper) {
        if (document.getPaths() == null) return;
        if (document.getComponents() == null) document.setComponents(new Components());
        Map<String, List<Evaluation>> groups = new LinkedHashMap<>();
        for (var binding : bindings.bulkOperations()) {
            Evaluation evaluation = evaluation(document, bindings, binding);
            if (evaluation != null)
                groups.computeIfAbsent(evaluation.baseName(), ignored -> new ArrayList<>()).add(evaluation);
        }
        Map<String, Schema> generatedDomainSchemas = new HashMap<>();
        Set<String> generatedRootNames = new HashSet<>();
        groups.forEach((name, evaluations) ->
                publishGroup(document, mapper, name, evaluations, generatedDomainSchemas, generatedRootNames));
    }

    private static Evaluation evaluation(OpenAPI document, BulkResourceOperationBindings bindings,
            BulkOperationBinding binding) {
        var handler = binding.evaluationHandler();
        var mapping = bindings.mappingFor(handler).orElseThrow();
        var path = document.getPaths().get(mapping.getPatternValues().iterator().next());
        var parameter = Arrays.stream(handler.getMethodParameters()).filter(p -> p.hasParameterAnnotation(RequestBody.class))
                .findFirst().orElseThrow();
        var type = ResolvableType.forType(org.springframework.core.GenericTypeResolver.resolveType(
                parameter.getGenericParameterType(), handler.getBeanType()));
        // Hand-authored protocol schemas remain valid inputs to the existing kernel.
        if (type.toClass() != BulkUniformEvaluationRequest.class && type.toClass() != BulkItemEvaluationRequest.class
                && type.toClass() != BulkCommandEvaluationRequest.class) return null;
        String resource = handler.getBeanType().getSimpleName().replaceFirst("Controller$", "");
        String suffix = switch (binding.mode()) {
            case UNIFORM_UPDATE -> "BulkUniformEvaluationRequest";
            case PER_ITEM_UPDATE -> "BulkItemEvaluationRequest";
            case DOMAIN_COMMAND -> "BulkCommandEvaluationRequest";
        };
        if (binding.mode() == BulkMode.DOMAIN_COMMAND && bindings.bulkOperations().stream()
                .filter(other -> other.mode() == BulkMode.DOMAIN_COMMAND
                        && other.confirmationHandler().getBeanType().equals(handler.getBeanType())).count() > 1)
            resource += type.getGeneric(0).toClass().getSimpleName();
        return new Evaluation(binding, path == null ? null : path.getPost(), type, resource + suffix);
    }

    private static void publishGroup(OpenAPI document, ObjectMapper mapper, String baseName,
            List<Evaluation> evaluations, Map<String, Schema> generatedDomainSchemas, Set<String> generatedRootNames) {
        if (evaluations.stream().noneMatch(evaluation -> evaluation.operation() != null)) return;
        // Use the full binding group for naming so an operation keeps the same reference in exact groups.
        var requests = evaluations.stream()
                .sorted(Comparator.comparing(evaluation -> evaluation.binding().evaluationOperationId()))
                .map(evaluation -> request(mapper, evaluation))
                .toList();
        Map<Schema<?>, String> operationByShape = new LinkedHashMap<>();
        Map<String, Schema> groupDomainSchemas = new HashMap<>();
        for (var request : requests) {
            if (request.components().getSchemas() != null)
                request.components().getSchemas().forEach((name, schema) -> registerDomainSchema(groupDomainSchemas, name, schema));
            operationByShape.putIfAbsent(request.schema(), request.evaluation().binding().evaluationOperationId());
        }
        var existingBase = schema(document, baseName);
        if (existingBase != null && !operationByShape.containsKey(existingBase))
            throw new IllegalStateException("Conflicting typed bulk schema: " + baseName);

        for (var request : requests) {
            var operation = request.evaluation().operation();
            if (operation == null) continue;
            String name = operationByShape.size() == 1 ? baseName
                    : baseName + "." + componentKey(operationByShape.get(request.schema()));
            var existing = schema(document, name);
            if (generatedDomainSchemas.containsKey(name) || existing != null && !existing.equals(request.schema()))
                throw new IllegalStateException("Conflicting typed bulk schema: " + name);
            generatedRootNames.add(name);
            if (request.components().getSchemas() != null)
                request.components().getSchemas().forEach((domainName, domainSchema) -> {
                    if (generatedRootNames.contains(domainName))
                        throw new IllegalStateException("Conflicting typed bulk schema: " + domainName);
                    registerDomainSchema(generatedDomainSchemas, domainName, domainSchema);
                    // Springdoc may already have enriched this canonical DTO component.
                    if (schema(document, domainName) == null) document.getComponents().addSchemas(domainName, domainSchema);
                });
            document.getComponents().addSchemas(name, request.schema());
            operation.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody().required(true)
                    .content(new Content().addMediaType("application/json", new MediaType().schema(
                            new Schema<>().$ref("#/components/schemas/" + name)))));
        }
    }

    private static String componentKey(String operationId) {
        var key = new StringBuilder();
        operationId.codePoints().forEach(character -> {
            if (character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9' || character == '.' || character == '-') {
                key.appendCodePoint(character);
            } else if (character == '_') {
                key.append("__");
            } else {
                key.append('_').append(Integer.toHexString(character)).append('_');
            }
        });
        return key.toString();
    }

    private static Schema<?> schema(OpenAPI document, String name) {
        var schemas = document.getComponents().getSchemas();
        return schemas == null ? null : schemas.get(name);
    }

    private static RequestShape request(ObjectMapper mapper, Evaluation evaluation) {
        var binding = evaluation.binding();
        var type = evaluation.type();
        var components = new Components();
        Schema<?> id = BulkResourceTypes.identityCodec(
                BulkResourceTypes.identityType(binding.evaluationHandler().getBeanType())).wireSchema();
        ObjectSchema request = object("executionMode");
        request.addProperty("executionMode", enumeration("SYNC", "ASYNC"));
        if (binding.mode() == BulkMode.PER_ITEM_UPDATE) {
            request.addProperty("items", array(object("id", "expectedVersion", "changes")
                    .addProperty("id", id).addProperty("expectedVersion", version()).addProperty("changes", changes()), 1, 10000));
            request.addRequiredItem("items");
        } else {
            int filterIndex = binding.mode() == BulkMode.DOMAIN_COMMAND ? 2 : 1;
            var filter = type.getGeneric(filterIndex);
            boolean explicitOnly = filter.resolve() == Void.class;
            ObjectSchema selection = explicitOnly ? object("mode", "targets") : object("mode");
            selection.addProperty("mode", explicitOnly ? enumeration("EXPLICIT") : enumeration("EXPLICIT", "QUERY"));
            selection.addProperty("targets", array(object("id", "expectedVersion").addProperty("id", id)
                    .addProperty("expectedVersion", version()), 1, 10000));
            if (!explicitOnly) {
                selection.addProperty("filter", domainSchema(components, mapper, filter));
                selection.addProperty("excludedIds", array(id, 0, 10000));
                selection.description("EXPLICIT requires targets and forbids filter/excludedIds; QUERY requires filter and forbids targets. The strict protocol reader enforces this tagged selection.");
            }
            request.addProperty("selection", selection);
            request.addRequiredItem("selection");
            if (binding.mode() == BulkMode.DOMAIN_COMMAND) {
                request.addProperty("parameters", domainSchema(components, mapper, type.getGeneric(0)));
                request.addRequiredItem("parameters");
            } else {
                request.addProperty("changes", changes());
                request.addRequiredItem("changes");
            }
        }
        request.description("Typed two-phase bulk evaluation. This request never updates resource rows; confirmation requires the retained proposal and an Idempotency-Key.");
        return new RequestShape(evaluation, request, components);
    }

    private record Evaluation(BulkOperationBinding binding, Operation operation, ResolvableType type, String baseName) { }

    private record RequestShape(Evaluation evaluation, ObjectSchema schema, Components components) { }

    private static Schema<?> domainSchema(Components components, ObjectMapper mapper, ResolvableType type) {
        if (type.resolve() == null || type.resolve() == Object.class) throw new IllegalStateException("Bulk domain DTO must be concrete");
        var resolved = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(mapper.constructType(type.getType())).resolveAsRef(true));
        if (components.getSchemas() == null) components.setSchemas(new LinkedHashMap<>());
        resolved.referencedSchemas.forEach((name, schema) -> registerDomainSchema(components.getSchemas(), name, schema));
        return resolved.schema;
    }

    private static void registerDomainSchema(Map<String, Schema> schemas, String name, Schema<?> schema) {
        var existing = schemas.putIfAbsent(name, schema);
        if (existing != null && !existing.equals(schema))
            throw new IllegalStateException("Conflicting typed bulk domain schema: " + name);
    }
    private static ObjectSchema object(String... required) {
        var schema = new ObjectSchema(); schema.setAdditionalProperties(false); schema.setRequired(List.of(required)); return schema;
    }
    private static StringSchema enumeration(String... values) { var schema = new StringSchema(); schema.setEnum(List.of(values)); return schema; }
    private static Schema<?> version() { return new StringSchema().minLength(1).description("Strong item ETag observed when selecting this target"); }
    private static Schema<?> array(Schema<?> item, int min, int max) { return new ArraySchema().items(item).minItems(min).maxItems(max); }
    private static Schema<?> changes() {
        return array(object("field", "operator").addProperty("field", new StringSchema().minLength(1))
                .addProperty("operator", enumeration("SET", "CLEAR")).addProperty("value", new Schema<>())
                .description("SET requires a non-null value; CLEAR forbids value. Field eligibility and nullability are compiled from the unit update DTO."), 1, 50);
    }
}
