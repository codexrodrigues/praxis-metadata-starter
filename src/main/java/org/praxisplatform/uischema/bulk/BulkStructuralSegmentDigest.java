package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.Objects;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.openapi.CanonicalRequestSchema;
import org.praxisplatform.uischema.openapi.CanonicalResponseSchema;

/**
 * Internal deterministic segment digest of the validated seven-operation structure.
 *
 * <p>This is structural evidence only. It is not an operational descriptor fingerprint, a
 * control-plane value, a readiness decision, a capability, or authorization to execute.</p>
 */
final class BulkStructuralSegmentDigest {
    private static final SchemaCanonicalizer SCHEMA_CANONICALIZER = new SchemaCanonicalizer();

    private BulkStructuralSegmentDigest() { }

    static String compute(BulkOperationStructuralDescriptor descriptor) {
        JsonNode content = canonicalContent(descriptor);
        return BulkCanonicalJson.structuralDescriptorDigest(content);
    }

    static JsonNode canonicalContent(BulkOperationStructuralDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        var factory = JsonNodeFactory.instance;
        var root = factory.objectNode();
        root.put("structureVersion", "praxis.bulk.structure/2");
        root.put("resourceKey", descriptor.resourceKey());
        root.put("openApiGroup", descriptor.openApiGroup());
        root.put("mode", descriptor.mode().name());
        root.put("atomicity", descriptor.atomicity().name());
        root.set("action", action(descriptor.action()));
        var operations = factory.arrayNode();
        for (var operation : descriptor.operations()) {
            var item = factory.objectNode();
            item.put("role", operation.role().name());
            item.set("reference", reference(operation.reference()));
            operation.requestJavaType().ifPresent(value -> item.put("requestJavaType", value));
            item.put("responseJavaType", operation.responseJavaType());
            operation.requestSchema().ifPresent(schema -> item.set("requestSchema", requestSchema(schema)));
            item.set("responseSchema", responseSchema(operation.responseSchema()));
            operations.add(item);
        }
        root.set("operations", operations);
        return root;
    }

    private static ObjectNode reference(org.praxisplatform.uischema.openapi.CanonicalOperationRef reference) {
        var result = JsonNodeFactory.instance.objectNode();
        putNullable(result, "group", reference.group());
        putNullable(result, "operationId", reference.operationId());
        putNullable(result, "path", reference.path());
        putNullable(result, "method", reference.method());
        return result;
    }

    private static ObjectNode action(BulkOperationStructuralDescriptor.Action action) {
        var result = JsonNodeFactory.instance.objectNode();
        result.put("id", action.id());
        result.put("resourceKey", action.resourceKey());
        result.put("resourcePath", action.resourcePath());
        result.put("group", action.group());
        result.put("scope", action.scope().name());
        result.put("title", action.title());
        result.put("description", action.description());
        result.set("operation", reference(action.operation()));
        result.set("requestSchemaReference", schemaReference(action.requestSchema()));
        result.set("responseSchemaReference", schemaReference(action.responseSchema()));
        result.put("order", action.order());
        result.put("successMessage", action.successMessage());
        result.set("requiredAuthorities", strings(action.requiredAuthorities()));
        result.set("allowedStates", strings(action.allowedStates()));
        result.set("tags", strings(action.tags()));
        result.set("execution", execution(action.execution()));
        return result;
    }

    private static ObjectNode schemaReference(org.praxisplatform.uischema.schema.CanonicalSchemaRef reference) {
        var result = JsonNodeFactory.instance.objectNode();
        result.put("schemaId", reference.schemaId());
        result.put("schemaType", reference.schemaType());
        result.put("url", reference.url());
        return result;
    }

    private static ObjectNode execution(org.praxisplatform.uischema.action.ActionExecutionContract execution) {
        var result = JsonNodeFactory.instance.objectNode();
        var interaction = JsonNodeFactory.instance.objectNode();
        interaction.put("mode", execution.interaction().mode().name());
        interaction.put("riskLevel", execution.interaction().riskLevel().name());
        interaction.put("confirmationRequired", execution.interaction().confirmationRequired());
        interaction.put("reversible", execution.interaction().reversible());
        result.set("interaction", interaction);

        var preconditions = JsonNodeFactory.instance.objectNode();
        preconditions.put("idempotencyKey", execution.preconditions().idempotencyKey().name());
        preconditions.put("correlationId", execution.preconditions().correlationId().name());
        preconditions.put("resourceVersion", execution.preconditions().resourceVersion().name());
        preconditions.put("resourceVersionTransport", execution.preconditions().resourceVersionTransport().name());
        putNullable(preconditions, "resourceVersionField", execution.preconditions().resourceVersionField());
        putNullable(preconditions, "resourceVersionTargetResourceKey", execution.preconditions().resourceVersionTargetResourceKey());
        putNullable(preconditions, "resourceVersionTargetIdField", execution.preconditions().resourceVersionTargetIdField());
        result.set("preconditions", preconditions);

        var selection = JsonNodeFactory.instance.objectNode();
        putNullable(selection, "idsField", execution.selection().idsField());
        putNullable(selection, "versionsField", execution.selection().versionsField());
        if (execution.selection().maxItems() == null) selection.putNull("maxItems");
        else selection.put("maxItems", execution.selection().maxItems());
        result.set("selection", selection);

        var outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("mode", execution.outcome().mode().name());
        outcome.put("atomicity", execution.outcome().atomicity().name());
        result.set("outcome", outcome);

        var refresh = JsonNodeFactory.instance.objectNode();
        refresh.put("item", execution.refresh().item());
        refresh.put("collection", execution.refresh().collection());
        refresh.put("actions", execution.refresh().actions());
        refresh.put("capabilities", execution.refresh().capabilities());
        refresh.set("resourceKeys", strings(execution.refresh().resourceKeys()));
        result.set("refresh", refresh);
        return result;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode strings(java.util.List<String> values) {
        var result = JsonNodeFactory.instance.arrayNode();
        values.forEach(result::add);
        return result;
    }

    private static void putNullable(ObjectNode target, String name, String value) {
        if (value == null) target.putNull(name);
        else target.put(name, value);
    }

    private static ObjectNode requestSchema(CanonicalRequestSchema schema) {
        var result = JsonNodeFactory.instance.objectNode();
        result.set("operation", reference(schema.operation()));
        result.put("mediaType", schema.mediaType());
        result.put("specVersion", schema.specVersion().name());
        result.set("schema", SCHEMA_CANONICALIZER.canonicalize(schema.schema()));
        return result;
    }

    private static ObjectNode responseSchema(CanonicalResponseSchema schema) {
        var result = JsonNodeFactory.instance.objectNode();
        result.set("operation", reference(schema.operation()));
        result.put("specVersion", schema.specVersion().name());
        result.set("schema", SCHEMA_CANONICALIZER.canonicalize(schema.schema()));
        var variants = JsonNodeFactory.instance.arrayNode();
        schema.variants().stream().sorted(Comparator.comparingInt(CanonicalResponseSchema.Variant::status)
                .thenComparing(CanonicalResponseSchema.Variant::mediaType)).forEach(variant -> {
                    var item = JsonNodeFactory.instance.objectNode();
                    item.put("status", variant.status());
                    item.put("mediaType", variant.mediaType());
                    variants.add(item);
                });
        result.set("variants", variants);
        return result;
    }
}
