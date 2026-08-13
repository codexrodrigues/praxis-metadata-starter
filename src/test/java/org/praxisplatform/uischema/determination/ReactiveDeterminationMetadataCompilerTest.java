package org.praxisplatform.uischema.determination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveDeterminationMetadataCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode document;
    private StubOperationResolver operationResolver;

    @BeforeEach
    void setUp() throws Exception {
        document = mapper.readTree("""
                {
                  "paths": {
                    "/api/customers": {
                      "post": {
                        "operationId": "createCustomer",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/CustomerDraft"}}}}
                      }
                    },
                    "/api/customers/determinations/address": {
                      "post": {
                        "operationId": "determineCustomerAddress",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/AddressInput"}}}},
                        "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/AddressOutput"}}}}}
                      }
                    },
                    "/api/customers/determinations/unsafe": {
                      "get": {
                        "operationId": "unsafeGetDetermination",
                        "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/AddressOutput"}}}}}
                      }
                    }
                  },
                  "components": {"schemas": {
                    "CustomerDraft": {
                      "type": "object",
                      "properties": {
                        "postalCode": {"type": "string"},
                        "country": {"type": "string"},
                        "address": {"type": "object", "properties": {
                          "city": {"type": "string"},
                          "state": {"type": "string"}
                        }}
                      }
                    },
                    "AddressInput": {"type": "object", "properties": {
                      "postalCode": {"type": "string"},
                      "country": {"type": "string"}
                    }},
                    "AddressOutput": {"type": "object", "properties": {
                      "city": {"type": "string"},
                      "state": {"type": "string"}
                    }}
                  }}
                }
                """);
        operationResolver = new StubOperationResolver(Map.of(
                "createCustomer", new CanonicalOperationRef("customers", "createCustomer", "/api/customers", "POST"),
                "determineCustomerAddress", new CanonicalOperationRef(
                        "customers",
                        "determineCustomerAddress",
                        "/api/customers/determinations/address",
                        "POST"
                ),
                "unsafeGetDetermination", new CanonicalOperationRef(
                        "customers",
                        "unsafeGetDetermination",
                        "/api/customers/determinations/unsafe",
                        "GET"
                )
        ));
    }

    @Test
    void compilesClosedMetadataFromCanonicalOperationAndSchemas() {
        ReactiveDeterminationMetadataCompiler compiler = compiler(List.of(addressDefinition()));

        List<Map<String, Object>> metadata = compiler.compile(
                operationResolver.resolveByOperationId("createCustomer").orElseThrow(),
                "request",
                document,
                document.path("components").path("schemas").path("CustomerDraft")
        );

        assertEquals(1, metadata.size());
        Map<String, Object> determination = metadata.getFirst();
        assertEquals("address.by-postal-code", determination.get("id"));
        Map<?, ?> capability = (Map<?, ?>) determination.get("capability");
        assertEquals("POST", capability.get("method"));
        assertEquals("/api/customers/determinations/address", capability.get("href"));
        assertTrue(String.valueOf(capability.get("requestSchemaUrl")).contains("schemaType=request"));
        assertTrue(String.valueOf(capability.get("responseSchemaUrl")).contains("schemaType=response"));
        assertFalse(capability.containsKey("path"));
        assertFalse(determination.containsKey("tenant"));
        assertFalse(determination.containsKey("values"));
    }

    @Test
    void publishesOnlyOnTheExactRequestSchema() {
        ReactiveDeterminationMetadataCompiler compiler = compiler(List.of(addressDefinition()));
        CanonicalOperationRef schemaOperation = operationResolver.resolveByOperationId("createCustomer").orElseThrow();

        assertTrue(compiler.compile(schemaOperation, "response", document, document).isEmpty());
        assertTrue(compiler.compile(
                new CanonicalOperationRef("customers", "anotherOperation", "/api/customers", "POST"),
                "request",
                document,
                document
        ).isEmpty());
    }

    @Test
    void rejectsNonPostDeterminationOperations() {
        ReactiveDeterminationDefinition invalid = new ReactiveDeterminationDefinition(
                "unsafe.get",
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE)),
                "unsafeGetDetermination",
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/postalCode"),
                List.of(new ReactiveDeterminationInputBinding("/postalCode", "/postalCode")),
                List.of(new ReactiveDeterminationOutputBinding("/city", "/address/city")),
                provenance()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> compile(invalid)
        );
        assertTrue(error.getMessage().contains("must use POST"));
    }

    @Test
    void rejectsBindingsMissingFromCanonicalSchemas() {
        ReactiveDeterminationDefinition invalid = new ReactiveDeterminationDefinition(
                "address.invalid-target",
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE)),
                "determineCustomerAddress",
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/postalCode"),
                List.of(new ReactiveDeterminationInputBinding("/postalCode", "/missingRequestField")),
                List.of(new ReactiveDeterminationOutputBinding("/city", "/address/missing")),
                provenance()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> compile(invalid)
        );
        assertTrue(error.getMessage().contains("canonical schema"));
    }

    @Test
    void rejectsMoreThanTheSharedTotalBindingLimit() {
        ReactiveDeterminationInputBinding input =
                new ReactiveDeterminationInputBinding("/postalCode", "/postalCode");
        ReactiveDeterminationOutputBinding output =
                new ReactiveDeterminationOutputBinding("/city", "/address/city");
        ReactiveDeterminationDefinition invalid = new ReactiveDeterminationDefinition(
                "address.too-many-bindings",
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE)),
                "determineCustomerAddress",
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/postalCode"),
                java.util.Collections.nCopies(64, input),
                List.of(output),
                provenance()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> compile(invalid)
        );
        assertTrue(error.getMessage().contains("64 input and output bindings"));
    }

    @Test
    void rejectsOverlappingInputAndOutputFields() {
        ReactiveDeterminationDefinition invalid = new ReactiveDeterminationDefinition(
                "address.overlapping-read-write",
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE)),
                "determineCustomerAddress",
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/postalCode"),
                List.of(
                        new ReactiveDeterminationInputBinding("/postalCode", "/postalCode"),
                        new ReactiveDeterminationInputBinding("/address/city", "/country")
                ),
                List.of(new ReactiveDeterminationOutputBinding("/city", "/address/city")),
                provenance()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> compile(invalid)
        );
        assertTrue(error.getMessage().contains("input and output fieldPath values must not overlap"));
    }

    @Test
    void rejectsHierarchicallyOverlappingBindingsWithinAContractSide() {
        ReactiveDeterminationDefinition invalid = new ReactiveDeterminationDefinition(
                "address.overlapping-request",
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE)),
                "determineCustomerAddress",
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/postalCode"),
                List.of(
                        new ReactiveDeterminationInputBinding("/postalCode", "/postalCode"),
                        new ReactiveDeterminationInputBinding("/country", "/postalCode/value")
                ),
                List.of(new ReactiveDeterminationOutputBinding("/city", "/address/city")),
                provenance()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> compile(invalid)
        );
        assertTrue(error.getMessage().contains("input requestPath values must not overlap"));
    }

    @Test
    void rejectsMultipleWritersBeforePublication() {
        ReactiveDeterminationDefinition first = addressDefinition();
        ReactiveDeterminationDefinition second = new ReactiveDeterminationDefinition(
                "address.second-writer",
                first.scopes(),
                first.operationId(),
                first.triggerMode(),
                List.of("/country"),
                List.of(new ReactiveDeterminationInputBinding("/country", "/country")),
                List.of(new ReactiveDeterminationOutputBinding("/state", "/address")),
                provenance()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> compiler(List.of(first, second)).compile(
                        operationResolver.resolveByOperationId("createCustomer").orElseThrow(),
                        "request",
                        document,
                        document.path("components").path("schemas").path("CustomerDraft")
                )
        );
        assertTrue(error.getMessage().contains("overlapping targets"));
    }

    @Test
    void rejectsCyclesBeforePublication() {
        ReactiveDeterminationDefinition cityFromPostalCode = new ReactiveDeterminationDefinition(
                "city.from-postal-code",
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE)),
                "determineCustomerAddress",
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/postalCode"),
                List.of(new ReactiveDeterminationInputBinding("/postalCode", "/postalCode")),
                List.of(new ReactiveDeterminationOutputBinding("/city", "/address/city")),
                provenance()
        );
        ReactiveDeterminationDefinition postalCodeFromCity = new ReactiveDeterminationDefinition(
                "postal-code.from-city",
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE)),
                "determineCustomerAddress",
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/address/city"),
                List.of(new ReactiveDeterminationInputBinding("/address/city", "/country")),
                List.of(new ReactiveDeterminationOutputBinding("/state", "/postalCode")),
                provenance()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> compiler(List.of(cityFromPostalCode, postalCodeFromCity)).compile(
                        operationResolver.resolveByOperationId("createCustomer").orElseThrow(),
                        "request",
                        document,
                        document.path("components").path("schemas").path("CustomerDraft")
                )
        );
        assertTrue(error.getMessage().contains("cycle detected"));
    }

    @Test
    void registryCapturesProvidersOnlyOnceAndRejectsDuplicateIds() {
        AtomicInteger calls = new AtomicInteger();
        ReactiveDeterminationDefinitionProvider provider = () -> {
            calls.incrementAndGet();
            return List.of(addressDefinition());
        };
        DefaultReactiveDeterminationDefinitionRegistry registry =
                new DefaultReactiveDeterminationDefinitionRegistry(List.of(provider));

        registry.findBySchemaOperationId("createCustomer");
        registry.findBySchemaOperationId("createCustomer");
        assertEquals(1, calls.get());

        assertThrows(
                IllegalArgumentException.class,
                () -> new DefaultReactiveDeterminationDefinitionRegistry(List.of(
                        () -> List.of(addressDefinition()),
                        () -> List.of(addressDefinition())
                ))
        );
    }

    private List<Map<String, Object>> compile(ReactiveDeterminationDefinition definition) {
        return compiler(List.of(definition)).compile(
                operationResolver.resolveByOperationId("createCustomer").orElseThrow(),
                "request",
                document,
                document.path("components").path("schemas").path("CustomerDraft")
        );
    }

    private ReactiveDeterminationMetadataCompiler compiler(List<ReactiveDeterminationDefinition> definitions) {
        DefaultReactiveDeterminationDefinitionRegistry registry =
                new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> definitions));
        return new ReactiveDeterminationMetadataCompiler(
                registry,
                operationResolver,
                new FilteredSchemaReferenceResolver(),
                new StaticOpenApiDocumentService(document)
        );
    }

    private ReactiveDeterminationDefinition addressDefinition() {
        return new ReactiveDeterminationDefinition(
                "address.by-postal-code",
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE)),
                "determineCustomerAddress",
                ReactiveDeterminationTriggerMode.ON_CHANGE,
                List.of("/postalCode"),
                List.of(new ReactiveDeterminationInputBinding("/postalCode", "/postalCode")),
                List.of(
                        new ReactiveDeterminationOutputBinding("/city", "/address/city"),
                        new ReactiveDeterminationOutputBinding("/state", "/address/state")
                ),
                provenance()
        );
    }

    private ReactiveDeterminationProvenance provenance() {
        return new ReactiveDeterminationProvenance(
                ReactiveDeterminationProvenanceKind.HOST,
                "customer-determinations",
                "1"
        );
    }

    private record StubOperationResolver(Map<String, CanonicalOperationRef> operations)
            implements CanonicalOperationResolver {

        @Override
        public String resolveGroup(String path) {
            return "customers";
        }

        @Override
        public CanonicalOperationRef resolve(String path, String method) {
            return operations.values().stream()
                    .filter(operation -> operation.path().equals(path) && operation.method().equalsIgnoreCase(method))
                    .findFirst()
                    .orElse(new CanonicalOperationRef("customers", null, path, method.toUpperCase()));
        }

        @Override
        public CanonicalOperationRef resolve(HandlerMethod handlerMethod, RequestMappingInfo mappingInfo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CanonicalOperationRef> resolveByOperationId(String operationId) {
            return Optional.ofNullable(operations.get(operationId));
        }
    }

    private record StaticOpenApiDocumentService(JsonNode document) implements OpenApiDocumentService {

        @Override
        public String resolveGroupFromPath(String path) {
            return "customers";
        }

        @Override
        public JsonNode getDocumentForGroup(String groupName) {
            return document;
        }

        @Override
        public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
            return "unused";
        }

        @Override
        public void clearCaches() {
        }
    }
}
