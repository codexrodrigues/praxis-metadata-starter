package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.RequestEntity;
import org.springframework.lang.Nullable;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalRequestBodyBindingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OpenApiDocumentService DOCUMENTS = new FixedGroupOpenApiDocumentService("bulk-operations");

    @Test
    void resolvesTheDirectRecordBodyFromTheSameStrictOperation() {
        try (AnnotationConfigWebApplicationContext context = context(DirectRecordController.class)) {
            CanonicalRequestBodyBinding binding = resolver(context).requireResourceRequestBody(
                    "direct.record", "direct.record.evaluate", "POST", JSON.getTypeFactory()
            );

            assertEquals("direct.record.evaluate", binding.operation().operationId());
            assertEquals("/direct-record/evaluate", binding.operation().path());
            assertEquals(DirectRecord.class, binding.bodyType().getRawClass());
        }
    }

    @Test
    void resolvesAnInheritedInterfaceGenericAndPreservesNestedGenericArguments() {
        try (AnnotationConfigWebApplicationContext context = context(InheritedGenericController.class)) {
            JavaType body = resolver(context).requireResourceRequestBody(
                    "inherited.generic", "inherited.generic.evaluate", "POST", JSON.getTypeFactory()
            ).bodyType();

            assertEquals(Body.class, body.getRawClass());
            assertEquals(List.class, body.containedTypeOrUnknown(0).getRawClass());
            assertEquals(String.class, body.containedTypeOrUnknown(0).containedTypeOrUnknown(0).getRawClass());
        }
    }

    @Test
    void preservesClosedDtoArrayArguments() {
        try (AnnotationConfigWebApplicationContext context = context(ArrayBodyController.class)) {
            JavaType body = resolver(context).requireResourceRequestBody(
                    "array.body", "array.body.evaluate", "POST", JSON.getTypeFactory()
            ).bodyType();
            assertEquals(Body.class, body.getRawClass());
            assertEquals(DirectRecord[].class, body.containedTypeOrUnknown(0).getRawClass());
            assertEquals(DirectRecord.class, body.containedTypeOrUnknown(0).getContentType().getRawClass());
        }
    }

    @Test
    void rejectsMissingMultipleAndOptionalRequestBodies() {
        assertRejected(MissingBodyController.class, "missing.body", "missing.body.evaluate");
        assertRejected(TwoBodiesController.class, "two.bodies", "two.bodies.evaluate");
        assertRejected(OptionalBodyController.class, "optional.body", "optional.body.evaluate");
        assertRejected(NullableBodyController.class, "nullable.body", "nullable.body.evaluate");
    }

    @Test
    void rejectsTransportAndOptionalWrappersEvenWhenTheyDeclareRequestBody() {
        assertRejected(OptionalWrapperController.class, "optional.wrapper", "optional.wrapper.evaluate");
        assertRejected(HttpEntityController.class, "http.entity", "http.entity.evaluate");
        assertRejected(RequestEntityController.class, "request.entity", "request.entity.evaluate");
    }

    @Test
    void rejectsContainersScalarsAndNonConcreteRootTypes() {
        assertRejected(ListController.class, "root.list", "root.list.evaluate");
        assertRejected(StringController.class, "root.string", "root.string.evaluate");
        assertRejected(UuidController.class, "root.uuid", "root.uuid.evaluate");
        assertRejected(LocalDateController.class, "root.local-date", "root.local-date.evaluate");
        assertRejected(ObjectController.class, "root.object", "root.object.evaluate");
        assertRejected(JsonNodeController.class, "root.json", "root.json.evaluate");
        assertRejected(AbstractController.class, "root.abstract", "root.abstract.evaluate");
        assertRejected(InterfaceController.class, "root.interface", "root.interface.evaluate");
        assertRejected(NonStaticMemberController.class, "root.member", "root.member.evaluate");
    }

    @Test
    void rejectsRawUnboundAndWildcardGenericDeclarations() {
        assertRejected(RawGenericDtoController.class, "raw.dto", "raw.dto.evaluate");
        assertRejected(RawGenericController.class, "raw.controller", "raw.controller.evaluate");
        assertRejected(BoundedMethodVariableController.class, "bounded.variable", "bounded.variable.evaluate");
        assertRejected(WildcardNestedController.class, "wildcard.nested", "wildcard.nested.evaluate");
        assertRejected(ParameterizedRawAncestorController.class, "raw.ancestor", "raw.ancestor.evaluate");
    }

    @Test
    void rejectsBoundedWildcardsHiddenInTheDtoSuperclass() {
        assertRejected(WildcardAncestorController.class, "wildcard.ancestor", "wildcard.ancestor.evaluate");
        assertRejected(NestedWildcardAncestorController.class, "nested.wildcard.ancestor", "nested.wildcard.ancestor.evaluate");
        assertRejected(ArrayWildcardAncestorController.class, "array.wildcard.ancestor", "array.wildcard.ancestor.evaluate");
    }

    @Test
    void acceptsAConcreteDtoThatBindsItsGenericSuperclass() {
        try (AnnotationConfigWebApplicationContext context = context(ConcreteGenericChildController.class)) {
            JavaType body = resolver(context).requireResourceRequestBody(
                    "concrete.child", "concrete.child.evaluate", "POST", JSON.getTypeFactory()
            ).bodyType();

            assertEquals(ConcreteGenericChild.class, body.getRawClass());
            assertEquals(String.class, body.getSuperClass().containedTypeOrUnknown(0).getRawClass());
        }
    }

    @Test
    void keepsTheExistingResourceAndMethodProofForTheRequestBodyBinding() {
        try (AnnotationConfigWebApplicationContext context = context(DirectRecordController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalStateException.class, () -> resolver.requireResourceRequestBody(
                    "other.resource", "direct.record.evaluate", "POST", JSON.getTypeFactory()
            ));
            assertThrows(IllegalStateException.class, () -> resolver.requireResourceRequestBody(
                    "direct.record", "direct.record.evaluate", "PUT", JSON.getTypeFactory()
            ));
            assertThrows(IllegalArgumentException.class, () -> resolver.requireResourceRequestBody(
                    "direct.record", "direct.record.evaluate", "POST", null
            ));
        }
    }

    @Test
    void requiresSubstituteResolversToOptIntoTheStrictBodyGuarantee() {
        CanonicalOperationResolver substitute = new CanonicalOperationResolver() {
            @Override
            public String resolveGroup(String path) {
                return "test";
            }

            @Override
            public CanonicalOperationRef resolve(String path, String method) {
                return new CanonicalOperationRef("test", null, path, method);
            }

            @Override
            public CanonicalOperationRef resolve(
                    org.springframework.web.method.HandlerMethod handlerMethod,
                    RequestMappingInfo mappingInfo
            ) {
                return new CanonicalOperationRef("test", "test.operation", "/test", "POST");
            }

            @Override
            public Optional<CanonicalOperationRef> resolveByOperationId(String operationId) {
                return Optional.empty();
            }
        };

        assertThrows(UnsupportedOperationException.class, () -> substitute.requireResourceRequestBody(
                "test.resource", "test.operation", "POST", JSON.getTypeFactory()
        ));
    }

    private void assertRejected(Class<?> controller, String resourceKey, String operationId) {
        try (AnnotationConfigWebApplicationContext context = context(controller)) {
            assertThrows(IllegalStateException.class, () -> resolver(context).requireResourceRequestBody(
                    resourceKey, operationId, "POST", JSON.getTypeFactory()
            ));
        }
    }

    private CanonicalOperationResolver resolver(AnnotationConfigWebApplicationContext context) {
        return new OpenApiCanonicalOperationResolver(
                DOCUMENTS,
                context.getBean(RequestMappingHandlerMapping.class)
        );
    }

    private AnnotationConfigWebApplicationContext context(Class<?> controller) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebMvcConfiguration.class, controller);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    static class WebMvcConfiguration {
    }

    @ApiResource(value = "/direct-record", resourceKey = "direct.record")
    static class DirectRecordController {
        @PostMapping("/evaluate")
        @Operation(operationId = "direct.record.evaluate")
        public void evaluate(@RequestBody DirectRecord body) {
        }
    }

    record DirectRecord(String name) {
    }

    interface GenericEndpoint<T> {
        @PostMapping("/evaluate")
        @Operation(operationId = "inherited.generic.evaluate")
        void evaluate(@RequestBody T body);
    }

    static class GenericEndpointBase<T> implements GenericEndpoint<T> {
        @Override
        public void evaluate(T body) {
        }
    }

    @ApiResource(value = "/inherited-generic", resourceKey = "inherited.generic")
    static class InheritedGenericController extends GenericEndpointBase<Body<List<String>>> {
    }

    static class Ancestor<T> { }
    static class WildcardAncestorBody extends Ancestor<List<? extends CharSequence>> { }
    @ApiResource(value = "/wildcard-ancestor", resourceKey = "wildcard.ancestor")
    static class WildcardAncestorController {
        @PostMapping("/evaluate")
        @Operation(operationId = "wildcard.ancestor.evaluate")
        public void evaluate(@RequestBody WildcardAncestorBody body) { }
    }

    @ApiResource(value = "/nested-wildcard-ancestor", resourceKey = "nested.wildcard.ancestor")
    static class NestedWildcardAncestorController {
        @PostMapping("/evaluate")
        @Operation(operationId = "nested.wildcard.ancestor.evaluate")
        public void evaluate(@RequestBody Body<WildcardAncestorBody> body) { }
    }

    @ApiResource(value = "/array-wildcard-ancestor", resourceKey = "array.wildcard.ancestor")
    static class ArrayWildcardAncestorController {
        @PostMapping("/evaluate")
        @Operation(operationId = "array.wildcard.ancestor.evaluate")
        public void evaluate(@RequestBody Body<WildcardAncestorBody[]> body) { }
    }

    @ApiResource(value = "/array-body", resourceKey = "array.body")
    static class ArrayBodyController {
        @PostMapping("/evaluate")
        @Operation(operationId = "array.body.evaluate")
        public void evaluate(@RequestBody Body<DirectRecord[]> body) { }
    }

    record Body<T>(T value) {
    }

    @ApiResource(value = "/missing-body", resourceKey = "missing.body")
    static class MissingBodyController {
        @PostMapping("/evaluate")
        @Operation(operationId = "missing.body.evaluate")
        public void evaluate(DirectRecord body) {
        }
    }

    @ApiResource(value = "/two-bodies", resourceKey = "two.bodies")
    static class TwoBodiesController {
        @PostMapping("/evaluate")
        @Operation(operationId = "two.bodies.evaluate")
        public void evaluate(@RequestBody DirectRecord first, @RequestBody DirectRecord second) {
        }
    }

    @ApiResource(value = "/optional-body", resourceKey = "optional.body")
    static class OptionalBodyController {
        @PostMapping("/evaluate")
        @Operation(operationId = "optional.body.evaluate")
        public void evaluate(@RequestBody(required = false) DirectRecord body) {
        }
    }

    @ApiResource(value = "/nullable-body", resourceKey = "nullable.body")
    static class NullableBodyController {
        @PostMapping("/evaluate")
        @Operation(operationId = "nullable.body.evaluate")
        public void evaluate(@RequestBody @Nullable DirectRecord body) {
        }
    }

    @ApiResource(value = "/optional-wrapper", resourceKey = "optional.wrapper")
    static class OptionalWrapperController {
        @PostMapping("/evaluate")
        @Operation(operationId = "optional.wrapper.evaluate")
        public void evaluate(@RequestBody Optional<DirectRecord> body) {
        }
    }

    @ApiResource(value = "/http-entity", resourceKey = "http.entity")
    static class HttpEntityController {
        @PostMapping("/evaluate")
        @Operation(operationId = "http.entity.evaluate")
        public void evaluate(@RequestBody HttpEntity<DirectRecord> body) {
        }
    }

    @ApiResource(value = "/request-entity", resourceKey = "request.entity")
    static class RequestEntityController {
        @PostMapping("/evaluate")
        @Operation(operationId = "request.entity.evaluate")
        public void evaluate(@RequestBody RequestEntity<DirectRecord> body) {
        }
    }

    @ApiResource(value = "/root-list", resourceKey = "root.list")
    static class ListController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.list.evaluate")
        public void evaluate(@RequestBody List<DirectRecord> body) {
        }
    }

    @ApiResource(value = "/root-string", resourceKey = "root.string")
    static class StringController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.string.evaluate")
        public void evaluate(@RequestBody String body) {
        }
    }

    @ApiResource(value = "/root-uuid", resourceKey = "root.uuid")
    static class UuidController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.uuid.evaluate")
        public void evaluate(@RequestBody UUID body) {
        }
    }

    @ApiResource(value = "/root-local-date", resourceKey = "root.local-date")
    static class LocalDateController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.local-date.evaluate")
        public void evaluate(@RequestBody LocalDate body) {
        }
    }

    @ApiResource(value = "/root-object", resourceKey = "root.object")
    static class ObjectController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.object.evaluate")
        public void evaluate(@RequestBody Object body) {
        }
    }

    @ApiResource(value = "/root-json", resourceKey = "root.json")
    static class JsonNodeController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.json.evaluate")
        public void evaluate(@RequestBody JsonNode body) {
        }
    }

    @ApiResource(value = "/root-abstract", resourceKey = "root.abstract")
    static class AbstractController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.abstract.evaluate")
        public void evaluate(@RequestBody AbstractBody body) {
        }
    }

    abstract static class AbstractBody {
    }

    @ApiResource(value = "/root-interface", resourceKey = "root.interface")
    static class InterfaceController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.interface.evaluate")
        public void evaluate(@RequestBody BodyInterface body) {
        }
    }

    interface BodyInterface {
    }

    static class MemberBodyOwner {
        class MemberBody {
        }
    }

    @ApiResource(value = "/root-member", resourceKey = "root.member")
    static class NonStaticMemberController {
        @PostMapping("/evaluate")
        @Operation(operationId = "root.member.evaluate")
        public void evaluate(@RequestBody MemberBodyOwner.MemberBody body) {
        }
    }

    @ApiResource(value = "/raw-dto", resourceKey = "raw.dto")
    static class RawGenericDtoController {
        @PostMapping("/evaluate")
        @Operation(operationId = "raw.dto.evaluate")
        @SuppressWarnings("rawtypes")
        public void evaluate(@RequestBody GenericBody body) {
        }
    }

    static class GenericBody<T> {
    }

    static class GenericParent<T> {
    }

    static class ConcreteGenericChild extends GenericParent<String> {
    }

    @ApiResource(value = "/concrete-child", resourceKey = "concrete.child")
    static class ConcreteGenericChildController {
        @PostMapping("/evaluate")
        @Operation(operationId = "concrete.child.evaluate")
        public void evaluate(@RequestBody ConcreteGenericChild body) {
        }
    }

    static class GenericController<T> {
        @PostMapping("/evaluate")
        @Operation(operationId = "raw.controller.evaluate")
        public void evaluate(@RequestBody T body) {
        }
    }

    @ApiResource(value = "/raw-controller", resourceKey = "raw.controller")
    @SuppressWarnings("rawtypes")
    static class RawGenericController extends GenericController {
    }

    @ApiResource(value = "/bounded-variable", resourceKey = "bounded.variable")
    static class BoundedMethodVariableController {
        @PostMapping("/evaluate")
        @Operation(operationId = "bounded.variable.evaluate")
        public <T extends DirectRecord> void evaluate(@RequestBody T body) {
        }
    }

    @ApiResource(value = "/wildcard-nested", resourceKey = "wildcard.nested")
    static class WildcardNestedController {
        @PostMapping("/evaluate")
        @Operation(operationId = "wildcard.nested.evaluate")
        public void evaluate(@RequestBody Body<List<? extends String>> body) {
        }
    }

    static class RawAncestor<T> {
    }

    @SuppressWarnings("rawtypes")
    static class ParameterizedRawAncestorBody<T> extends RawAncestor {
    }

    @ApiResource(value = "/raw-ancestor", resourceKey = "raw.ancestor")
    static class ParameterizedRawAncestorController {
        @PostMapping("/evaluate")
        @Operation(operationId = "raw.ancestor.evaluate")
        public void evaluate(@RequestBody ParameterizedRawAncestorBody<String> body) {
        }
    }

    private record FixedGroupOpenApiDocumentService(String group) implements OpenApiDocumentService {
        @Override
        public String resolveGroupFromPath(String path) {
            return group;
        }

        @Override
        public JsonNode getDocumentForGroup(String groupName) {
            throw new UnsupportedOperationException("Schema content is outside this binding test.");
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
