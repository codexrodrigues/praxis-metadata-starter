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
        for (var binding : bindings.bulkOperations()) {
            var handler = binding.evaluationHandler();
            var mapping = bindings.mappingFor(handler).orElseThrow();
            var path = document.getPaths().get(mapping.getPatternValues().iterator().next());
            if (path == null || path.getPost() == null) continue; // This controller is outside this exact group.
            var parameter = Arrays.stream(handler.getMethodParameters()).filter(p -> p.hasParameterAnnotation(RequestBody.class))
                    .findFirst().orElseThrow();
            var type = ResolvableType.forType(org.springframework.core.GenericTypeResolver.resolveType(
                    parameter.getGenericParameterType(), handler.getBeanType()));
            // Hand-authored protocol schemas remain valid inputs to the existing kernel.
            if (type.toClass() != BulkUniformEvaluationRequest.class && type.toClass() != BulkItemEvaluationRequest.class
                    && type.toClass() != BulkCommandEvaluationRequest.class) continue;
            Schema<?> id = BulkResourceTypes.identityCodec(BulkResourceTypes.identityType(handler.getBeanType())).wireSchema();
            String resource = handler.getBeanType().getSimpleName().replaceFirst("Controller$", "");
            String suffix = switch (binding.mode()) {
                case UNIFORM_UPDATE -> "BulkUniformEvaluationRequest";
                case PER_ITEM_UPDATE -> "BulkItemEvaluationRequest";
                case DOMAIN_COMMAND -> "BulkCommandEvaluationRequest";
            };
            String name = resource + suffix;
            if (binding.mode() == BulkMode.DOMAIN_COMMAND && bindings.bulkOperations().stream()
                    .filter(other -> other.mode() == BulkMode.DOMAIN_COMMAND
                            && other.confirmationHandler().getBeanType().equals(handler.getBeanType())).count() > 1)
                name = resource + type.getGeneric(0).toClass().getSimpleName() + suffix;
            ObjectSchema request = object("executionMode");
            request.addProperty("executionMode", enumeration("SYNC", "ASYNC"));
            if (binding.mode() == BulkMode.PER_ITEM_UPDATE) {
                request.addProperty("items", array(object("id", "expectedVersion", "changes")
                        .addProperty("id", id).addProperty("expectedVersion", version()).addProperty("changes", changes()), 1, 10000));
                request.addRequiredItem("items");
            } else {
                int filterIndex = binding.mode() == BulkMode.DOMAIN_COMMAND ? 2 : 1;
                Schema<?> filter = domainSchema(document, mapper, type.getGeneric(filterIndex));
                request.addProperty("selection", object("mode")
                        .addProperty("mode", enumeration("EXPLICIT", "QUERY"))
                        .addProperty("targets", array(object("id", "expectedVersion").addProperty("id", id)
                                .addProperty("expectedVersion", version()), 1, 10000))
                        .addProperty("filter", filter).addProperty("excludedIds", array(id, 0, 10000))
                        .description("EXPLICIT requires targets and forbids filter/excludedIds; QUERY requires filter and forbids targets. The strict protocol reader enforces this tagged selection."));
                request.addRequiredItem("selection");
                if (binding.mode() == BulkMode.DOMAIN_COMMAND) {
                    request.addProperty("parameters", domainSchema(document, mapper, type.getGeneric(0)));
                    request.addRequiredItem("parameters");
                } else {
                    request.addProperty("changes", changes()); request.addRequiredItem("changes");
                }
            }
            request.description("Typed two-phase bulk evaluation. This request never updates resource rows; confirmation requires the retained proposal and an Idempotency-Key.");
            Schema<?> existing = document.getComponents().getSchemas() == null ? null : document.getComponents().getSchemas().get(name);
            if (existing != null && !existing.equals(request)) {
                throw new IllegalStateException("Conflicting typed bulk schema: " + name);
            }
            document.getComponents().addSchemas(name, request);
            path.getPost().setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody().required(true)
                    .content(new Content().addMediaType("application/json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + name)))));
        }
    }

    private static Schema<?> domainSchema(OpenAPI document, ObjectMapper mapper, ResolvableType type) {
        if (type.resolve() == null || type.resolve() == Object.class) throw new IllegalStateException("Bulk domain DTO must be concrete");
        var resolved = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(mapper.constructType(type.getType())).resolveAsRef(true));
        resolved.referencedSchemas.forEach((name, schema) -> {
            // A DTO already produced/enriched by Springdoc remains the canonical component.
            if (document.getComponents().getSchemas() == null || !document.getComponents().getSchemas().containsKey(name))
                document.getComponents().addSchemas(name, schema);
        });
        return resolved.schema;
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
