package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CapabilityOperation;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkCrudOperationalCompositionTest {
    static final String UNIFORM_POINTER = "/properties/selection/properties/targets/items/properties/id";
    static final String ITEMS_POINTER = "/properties/items/items/properties/id";

    @Test
    void eachUpdateComposesItsExactModeSourceAndSortedAllowlists() {
        try (var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            for (var structure : structures(context)) {
                for (var role : structure.operations()) {
                    assertNotNull(role.filteredResponse(), role.reference().operationId());
                    if (role.requestSchema().isPresent()) assertNotNull(role.filteredRequest(), role.reference().operationId());
                }
                var provider = provider(structure.mode());
                var descriptor = BulkOperationalDescriptorComposer.compose(structure, provider);
                var contract = BulkExecutionContract.from(descriptor);
                assertEquals(structure.mode(), contract.mode());
                assertNull(contract.parametersPointer());
                assertEquals(BulkCrudStructuralCompilerTest.SOURCE, contract.editableFields().sourceOperation().operationId());
                assertEquals(structure.update().filteredRequest().reference(), contract.editableFields().requestSchema());
                assertEquals(structure.update().editableFields().writableFields(structure.mode()).stream().sorted().toList(),
                        contract.editableFields().writableFields());
                assertEquals(List.of("display_name"), contract.editableFields().clearableFields());
                assertThrows(UnsupportedOperationException.class, () -> contract.editableFields().writableFields().add("other"));
                assertEquals(BulkStructuralSegmentDigest.compute(structure), descriptor.structuralRevision());
                assertEquals(descriptor, BulkOperationalDescriptorComposer.compose(structure, provider));
                assertNotEquals(descriptor.descriptorFingerprint(), BulkOperationalDescriptorComposer.compose(structure,
                        provider(structure.mode(), "next", pointer(structure.mode()), structure.mode())).descriptorFingerprint());
            }
        }
    }

    @Test
    void modeAndIdentityPointerMustMatchTheCanonicalFamily() {
        assertThrows(IllegalArgumentException.class, () -> new BulkOperationalProfile(
                Set.of(BulkMode.UNIFORM_UPDATE, BulkMode.PER_ITEM_UPDATE), Set.of(BulkExecutionMode.SYNC),
                Set.of(BulkSelectionMode.EXPLICIT), 20, 1024, Duration.ofMinutes(2), Duration.ofSeconds(2)));
        try (var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            for (var structure : structures(context)) {
                BulkMode other = structure.mode() == BulkMode.UNIFORM_UPDATE ? BulkMode.PER_ITEM_UPDATE : BulkMode.UNIFORM_UPDATE;
                assertThrows(IllegalArgumentException.class, () -> BulkOperationalDescriptorComposer.compose(structure,
                        provider(structure.mode(), "r1", pointer(other), structure.mode())));
                assertThrows(IllegalArgumentException.class, () -> BulkOperationalDescriptorComposer.compose(structure,
                        provider(structure.mode(), "r1", pointer(structure.mode()), other)));
                var codecMismatch = spy(provider(structure.mode()));
                doReturn(BulkIdentityCodecs.longs()).when(codecMismatch).identityCodec();
                assertThrows(IllegalArgumentException.class, () -> BulkOperationalDescriptorComposer.compose(structure, codecMismatch));
            }
        }
    }

    @Test
    void aTransportResponseWithoutASelectableUiComponentStillCannotComposeReady() {
        try (var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var invalid = document();
            ((ObjectNode) invalid.at("/paths/~1crud-items~1bulk~1uniform~1evaluation/post/responses/200/content/application~1json"))
                    .set("schema", invalid.at("/components/schemas/CrudConfirmation").deepCopy());
            var structure = BulkCrudStructuralCompilerTest.compile(context, BulkCrudStructuralCompilerTest.mapper(),
                    new BulkCrudStructuralCompilerTest.Documents(invalid)).stream()
                    .filter(value -> value.mode() == BulkMode.UNIFORM_UPDATE).findFirst().orElseThrow();
            assertNotNull(structure.operation(BulkOperationStructuralDescriptor.Role.EVALUATION).responseSchema());
            assertNull(structure.operation(BulkOperationStructuralDescriptor.Role.EVALUATION).filteredResponse());
            var failure = assertThrows(IllegalStateException.class,
                    () -> BulkOperationalDescriptorComposer.compose(structure, provider(BulkMode.UNIFORM_UPDATE)));
            assertEquals("A bulk role has no materializable filtered schema projection", failure.getMessage());
        }
    }

    @Test
    void providerGettersAreCapturedOnceAndCapabilityCopiesKeepOnlyApplicableBulk() {
        try (var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class)) {
            var structure = structures(context).getFirst();
            var provider = spy(provider(structure.mode()));
            var descriptor = BulkOperationalDescriptorComposer.compose(structure, provider);
            verify(provider, times(1)).profile();
            verify(provider, times(1)).identitySchemaPointer();
            verify(provider, times(1)).identityCodec();
            verify(provider, times(1)).providerId();
            verify(provider, times(1)).providerRevision();
            verify(provider, times(1)).confirmationOperationId();
            verify(provider, times(1)).executionInfrastructure();
            var contract = BulkExecutionContract.from(descriptor);
            String id = structure.mode() == BulkMode.UNIFORM_UPDATE ? "bulk-update" : "bulk-update-items";
            var operation = new CapabilityOperation(id, true, "COLLECTION", "POST", id,
                    AvailabilityDecision.allowAll(), List.of(), List.of(), java.util.Map.of(), null, contract);
            assertSame(contract, operation.withAvailability(AvailabilityDecision.deny("denied", java.util.Map.of())).bulk());
            assertSame(contract, operation.withSupported(true).bulk());
            var service = new org.praxisplatform.uischema.capability.DefaultCapabilityService(null, null, null, null);
            var surface = mock(org.praxisplatform.uischema.surface.SurfaceCatalogItem.class,
                    withSettings().mockMaker(org.mockito.MockMakers.INLINE));
            when(surface.method()).thenReturn("POST");
            when(surface.availability()).thenReturn(AvailabilityDecision.allowAll());
            CapabilityOperation enriched = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    service, "enrichFromSurface", operation, surface);
            assertNotNull(enriched);
            assertSame(contract, enriched.bulk());

            assertNull(operation.withSupported(false).bulk());
            assertNull(operation.withSupported(false).preferredMethod());
            for (String wrongId : List.of("edit", "other")) assertThrows(IllegalArgumentException.class,
                    () -> new CapabilityOperation(wrongId, true, "COLLECTION", "POST", wrongId,
                            null, null, null, null, null, contract));
            assertThrows(IllegalArgumentException.class, () -> new CapabilityOperation(id, true, "ITEM", "POST", id,
                    null, null, null, null, null, contract));
            assertThrows(IllegalArgumentException.class, () -> new CapabilityOperation(id, true, "COLLECTION", "PUT", id,
                    null, null, null, null, null, contract));
        }
    }

    static List<BulkOperationStructuralDescriptor> structures(org.springframework.web.context.support.AnnotationConfigWebApplicationContext context) {
        return BulkCrudStructuralCompilerTest.compile(context, BulkCrudStructuralCompilerTest.mapper(),
                new BulkCrudStructuralCompilerTest.Documents(document()));
    }
    static ObjectNode document() {
        var document = BulkCrudStructuralCompilerTest.document();
        // The S1 document deliberately had transport-only inline responses. Operational UI
        // references need the same concrete Confirmation DTO exposed as a selectable component.
        document.putObject("components").putObject("schemas").putObject("CrudConfirmation")
                .put("type", "object").putObject("properties").putObject("proposalId").put("type", "string");
        document.path("paths").forEach(path -> path.forEach(operation ->
                ((ObjectNode) operation.at("/responses/200/content/application~1json"))
                        .set("schema", document.objectNode().put("$ref", "#/components/schemas/CrudConfirmation"))));
        for (String mode : List.of("uniform", "items")) {
            var schema = (ObjectNode) document.at("/paths/~1crud-items~1bulk~1" + mode + "~1evaluation/post/requestBody/content/application~1json/schema");
            var properties = schema.putObject("properties");
            properties.putObject("executionMode").put("type", "string");
            ObjectNode item;
            if (mode.equals("uniform")) {
                var selection = properties.putObject("selection").put("type", "object").putObject("properties");
                selection.putObject("mode").put("type", "string");
                item = selection.putObject("targets").put("type", "array").putObject("items").put("type", "object").putObject("properties");
            } else {
                item = properties.putObject("items").put("type", "array").putObject("items").put("type", "object").putObject("properties");
            }
            item.set("id", BulkIdentityCodecs.integers().canonicalWireSchema());
            item.putObject("expectedVersion").put("type", "string");
            var changes = (mode.equals("uniform") ? properties : item).putObject("changes").put("type", "array")
                    .putObject("items").put("type", "object").putObject("properties");
            changes.putObject("field").put("type", "string");
            changes.putObject("operator").put("type", "string");
            changes.putObject("value");
        }
        return document;
    }
    static String pointer(BulkMode mode) { return mode == BulkMode.UNIFORM_UPDATE ? UNIFORM_POINTER : ITEMS_POINTER; }
    static BulkOperationalProfile profile(BulkMode mode) {
        return new BulkOperationalProfile(Set.of(mode), Set.of(BulkExecutionMode.SYNC), Set.of(BulkSelectionMode.EXPLICIT),
                20, 65536, Duration.ofMinutes(2), Duration.ofSeconds(2));
    }
    static Provider provider(BulkMode mode) { return provider(mode, "r1", pointer(mode), mode); }
    static Provider provider(BulkMode mode, String revision, String pointer, BulkMode profileMode) {
        var runtime = mock(BulkExecutionInfrastructure.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(runtime.namespace()).thenReturn("crud-test");
        when(runtime.deploymentId()).thenReturn("deployment-test");
        return new Provider(mode == BulkMode.UNIFORM_UPDATE ? "crud.uniform" : "crud.items", revision, pointer, profileMode, runtime);
    }
    static class Provider implements BulkOperationDescriptorProvider {
        final String operationId;
        String revision;
        final String pointer;
        final BulkMode mode;
        final BulkExecutionInfrastructure runtime;
        Provider(String operationId, String revision, String pointer, BulkMode mode, BulkExecutionInfrastructure runtime) {
            this.operationId = operationId; this.revision = revision; this.pointer = pointer; this.mode = mode; this.runtime = runtime;
        }
        public String confirmationOperationId() { return operationId; }
        public String providerId() { return "crud-provider-" + mode; }
        public String providerRevision() { return revision; }
        public BulkIdentityCodec<?, ?> identityCodec() { return BulkIdentityCodecs.integers(); }
        public String identitySchemaPointer() { return pointer; }
        public BulkOperationalProfile profile() { return BulkCrudOperationalCompositionTest.profile(mode); }
        public BulkExecutionInfrastructure executionInfrastructure() { return runtime; }
    }
}
