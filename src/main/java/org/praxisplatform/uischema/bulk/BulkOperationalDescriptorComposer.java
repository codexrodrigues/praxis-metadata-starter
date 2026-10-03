package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Objects;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;

/** Builds the server-side operational fingerprint from the verified structural segment and host binding. */
final class BulkOperationalDescriptorComposer {
    private static final SchemaCanonicalizer SCHEMAS = new SchemaCanonicalizer();

    private BulkOperationalDescriptorComposer() { }

    static BulkOperationalDescriptor compose(BulkOperationStructuralDescriptor structural,
            BulkOperationDescriptorProvider provider) {
        Objects.requireNonNull(structural, "structural");
        Objects.requireNonNull(provider, "provider");
        var confirmation = structural.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION).reference();
        // Take one immutable local snapshot. A host bean is user code; repeated getter calls must
        // never let its fingerprint and the value later stored for readiness disagree.
        String confirmationOperationId = provider.confirmationOperationId();
        String providerId = canonical(provider.providerId(), "providerId");
        String providerRevision = canonical(provider.providerRevision(), "providerRevision");
        var profile = Objects.requireNonNull(provider.profile(), "profile");
        var codec = Objects.requireNonNull(provider.identityCodec(), "identityCodec");
        var infrastructure = Objects.requireNonNull(provider.executionInfrastructure(), "executionInfrastructure");
        String codecId = canonical(codec.codecId(), "codecId");
        var wireSchema = codec.canonicalWireSchema();
        if (wireSchema == null) throw new IllegalArgumentException("Identity codec requires a wire schema");
        String identitySchemaPointer = canonicalPointer(provider.identitySchemaPointer());
        if (structural.mode() != BulkMode.DOMAIN_COMMAND) {
            String requiredPointer = structural.mode() == BulkMode.UNIFORM_UPDATE
                    ? "/properties/selection/properties/targets/items/properties/id"
                    : "/properties/items/items/properties/id";
            if (!requiredPointer.equals(identitySchemaPointer))
                throw new IllegalArgumentException("Update identity pointer must identify its canonical target ID");
        }
        var evaluationSchema = structural.operation(BulkOperationStructuralDescriptor.Role.EVALUATION)
                .requestSchema().orElseThrow(() -> new IllegalArgumentException("Evaluation request schema is required"))
                .schema();
        var codecSchema = canonicalIdentitySchema(wireSchema);
        var publishedIdentitySchema = canonicalIdentitySchema(evaluationSchema.at(identitySchemaPointer));
        if (!codecSchema.equals(publishedIdentitySchema))
            throw new IllegalArgumentException("Identity codec wire schema differs from the canonical evaluation request");
        if (!confirmation.operationId().equals(confirmationOperationId))
            throw new IllegalArgumentException("Provider is not bound to this canonical confirmation operation");
        if (!profile.operationModes().equals(java.util.Set.of(structural.mode())))
            throw new IllegalArgumentException("Provider profile does not allow this declared operation mode");
        if (structural.atomicity() == ActionCollectionAtomicity.ATOMIC) {
            if (structural.mode() == BulkMode.DOMAIN_COMMAND || profile.maxTargets() > 50
                    || profile.unitDeadline().compareTo(Duration.ofSeconds(5)) > 0)
                throw new IllegalArgumentException("ATOMIC composition requires a bounded CRUD update profile");
        } else if (structural.atomicity() != ActionCollectionAtomicity.PER_ITEM) {
            throw new IllegalArgumentException("Bulk composition requires supported atomicity");
        }
        Integer actionLimit = structural.mode() == BulkMode.DOMAIN_COMMAND
                ? structural.action().execution().selection().maxItems() : null;
        if (actionLimit != null && profile.maxTargets() > actionLimit)
            throw new IllegalArgumentException("Operational target limit exceeds the canonical action selection limit");
        if (!infrastructure.namespace().equals(infrastructure.namespace().strip()))
            throw new IllegalArgumentException("Runtime namespace is not canonical");

        String structuralRevision = BulkStructuralSegmentDigest.compute(structural);
        var root = JsonNodeFactory.instance.objectNode();
        root.put("operationalVersion", "praxis.bulk.operational/1");
        root.put("structuralRevision", structuralRevision);
        root.put("confirmationOperationId", confirmationOperationId);
        root.put("namespaceId", infrastructure.namespace());
        root.put("deploymentId", infrastructure.deploymentId());
        root.put("providerId", providerId);
        root.put("providerRevision", providerRevision);
        var codecNode = root.putObject("identityCodec");
        codecNode.put("id", codecId);
        codecNode.put("evaluationIdentityPointer", identitySchemaPointer);
        codecNode.set("wireSchema", codecSchema.deepCopy());
        var profileNode = root.putObject("profile");
        profileNode.set("operationModes", JsonNodeFactory.instance.arrayNode().addAll(profile
                .operationModes().stream().map(Enum::name).sorted().map(JsonNodeFactory.instance::textNode).toList()));
        profileNode.set("executionModes", JsonNodeFactory.instance.arrayNode().addAll(profile
                .executionModes().stream().map(Enum::name).sorted().map(JsonNodeFactory.instance::textNode).toList()));
        profileNode.set("selectionModes", JsonNodeFactory.instance.arrayNode().addAll(profile
                .selectionModes().stream().map(Enum::name).sorted().map(JsonNodeFactory.instance::textNode).toList()));
        profileNode.put("maxTargets", profile.maxTargets());
        profileNode.put("maxRequestBytes", profile.maxRequestBytes());
        profileNode.put("proposalLifetimeMillis", profile.proposalLifetime().toMillis());
        profileNode.put("unitDeadlineMillis", profile.unitDeadline().toMillis());

        var descriptor = new BulkOperationalDescriptor(
                new BulkOperationControlIdentity(infrastructure.namespace(), confirmation.operationId()),
                structuralRevision,
                BulkCanonicalJson.operationalDescriptorDigest(root),
                providerId,
                providerRevision,
                profile, structural, infrastructure);
        // Readiness includes the usable evaluation/confirmation UI protocol. A transport-only
        // descriptor must never publish READY and expose a direct workflow without that protocol.
        BulkExecutionContract.from(descriptor);
        return descriptor;
    }

    /** OpenAPI defines int32 as a signed 32-bit integer even when SpringDoc omits redundant bounds. */
    private static com.fasterxml.jackson.databind.JsonNode canonicalIdentitySchema(
            com.fasterxml.jackson.databind.JsonNode schema) {
        var canonical = SCHEMAS.canonicalize(schema);
        if (canonical != null && canonical.isObject()
                && "integer".equals(canonical.path("type").asText())
                && "int32".equals(canonical.path("format").asText())) {
            ObjectNode normalized = (ObjectNode) canonical;
            if (!normalized.has("minimum")) normalized.put("minimum", Integer.MIN_VALUE);
            if (!normalized.has("maximum")) normalized.put("maximum", Integer.MAX_VALUE);
            return SCHEMAS.canonicalize(normalized);
        }
        return canonical;
    }

    private static String canonical(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 200 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(name + " must be canonical nonblank text");
        return value;
    }

    private static String canonicalPointer(String value) {
        String pointer = canonical(value, "identitySchemaPointer");
        if (!pointer.startsWith("/")) throw new IllegalArgumentException("identitySchemaPointer must be a JSON Pointer");
        return pointer;
    }
}
