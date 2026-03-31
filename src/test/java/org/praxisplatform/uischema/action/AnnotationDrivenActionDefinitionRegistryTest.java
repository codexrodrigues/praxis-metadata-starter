package org.praxisplatform.uischema.action;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController;
import org.praxisplatform.uischema.controller.base.AbstractResourceController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.praxisplatform.uischema.validation.AnnotationConflictMode;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnotationDrivenActionDefinitionRegistryTest {

    @Test
    void cachesDiscoveredDefinitionsAndIndexesByResourceAndGroup() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        RegistryActionController controller = new RegistryActionController();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-actions/{id}/actions/approve").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, RegistryActionController.class.getDeclaredMethod("approve", Long.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-actions/{id}/actions/reject").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, RegistryActionController.class.getDeclaredMethod("reject", Long.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-actions/actions/bulk-approve").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, RegistryActionController.class.getDeclaredMethod("bulkApprove"))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);
        when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenAnswer(invocation -> {
                    RequestMappingInfo mappingInfo = invocation.getArgument(1);
                    String path = mappingInfo.getPatternValues().iterator().next();
                    String operationId = path.contains("bulk-approve")
                            ? "bulkApproveResource"
                            : path.contains("approve") ? "approveResource" : "rejectResource";
                    return new CanonicalOperationRef("registry-group", operationId, path, "POST");
                });
        when(schemaResolver.resolve(
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                any(),
                any(),
                any(String.class),
                any(Boolean.class)
        ))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(0, String.class);
                    String schemaType = invocation.getArgument(2, String.class);
                    String idField = invocation.getArgument(6, String.class);
                    Boolean readOnly = invocation.getArgument(7, Boolean.class);
                    String operationId = path.contains("bulk-approve")
                            ? "bulkApproveResource"
                            : path.contains("approve") ? "approveResource" : "rejectResource";
                    return new CanonicalSchemaRef(
                            schemaType + "-" + operationId + "-" + idField + "-" + readOnly,
                            schemaType,
                            "/schemas/filtered?idField=" + idField + "&schemaType=" + schemaType
                    );
                });

        AnnotationDrivenActionDefinitionRegistry registry = new AnnotationDrivenActionDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.WARN,
                AnnotationConflictMode.WARN
        );

        List<ActionDefinition> firstLookup = registry.findByResourceKey("registry.actions");
        List<ActionDefinition> secondLookup = registry.findByResourceKey("registry.actions");

        verify(handlerMapping, times(1)).getHandlerMethods();
        assertEquals(firstLookup, secondLookup);
        assertEquals(3, firstLookup.size());
        assertEquals(List.of("approve", "bulk-approve", "reject"), firstLookup.stream().map(ActionDefinition::id).toList());
        assertEquals("request-approveResource-employeeId-false", firstLookup.get(0).requestSchema().schemaId());
        assertEquals("/schemas/filtered?idField=employeeId&schemaType=request", firstLookup.get(0).requestSchema().url());
        assertEquals(ActionScope.COLLECTION, firstLookup.stream()
                .filter(action -> "bulk-approve".equals(action.id()))
                .findFirst()
                .orElseThrow()
                .scope());
        assertNotNull(registry.findByGroup("registry-group").stream().filter(action -> "approve".equals(action.id())).findFirst().orElse(null));
        verify(schemaResolver, times(6)).resolve(any(String.class), eq("POST"), any(String.class), eq(false), eq(null), eq(null), eq("employeeId"), eq(false));
    }

    @Test
    void failsWhenWorkflowActionAndUiSurfaceAreCombinedUnderFailMode() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        ConflictingActionController controller = new ConflictingActionController();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/conflicting-actions/{id}/actions/approve").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, ConflictingActionController.class.getDeclaredMethod("approve", Long.class))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);

        AnnotationDrivenActionDefinitionRegistry registry = new AnnotationDrivenActionDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.FAIL,
                AnnotationConflictMode.WARN
        );

        assertThrows(IllegalStateException.class, () -> registry.findByResourceKey("conflicting.actions"));
    }

    @Test
    void failsWhenWorkflowActionUsesNonCommandHttpMethodUnderFailMode() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        InvalidMethodActionController controller = new InvalidMethodActionController();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/invalid-method-actions/{id}/actions/approve").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, InvalidMethodActionController.class.getDeclaredMethod("approve", Long.class))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);

        AnnotationDrivenActionDefinitionRegistry registry = new AnnotationDrivenActionDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.WARN,
                AnnotationConflictMode.FAIL
        );

        assertThrows(IllegalStateException.class, () -> registry.findByResourceKey("invalid.method.actions"));
    }

    @Test
    void failsWhenWorkflowActionPathIsNotWorkflowLikeUnderFailMode() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        InvalidPathActionController controller = new InvalidPathActionController();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/invalid-path-actions/{id}/approve").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, InvalidPathActionController.class.getDeclaredMethod("approve", Long.class))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);

        AnnotationDrivenActionDefinitionRegistry registry = new AnnotationDrivenActionDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.WARN,
                AnnotationConflictMode.FAIL
        );

        assertThrows(IllegalStateException.class, () -> registry.findByResourceKey("invalid.path.actions"));
    }

    @Test
    void buildsCachedSnapshotOnlyOnceUnderConcurrentLookupLoad() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        RegistryActionController controller = new RegistryActionController();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-actions/{id}/actions/approve").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, RegistryActionController.class.getDeclaredMethod("approve", Long.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-actions/actions/bulk-approve").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, RegistryActionController.class.getDeclaredMethod("bulkApprove"))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);
        when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenAnswer(invocation -> {
                    RequestMappingInfo mappingInfo = invocation.getArgument(1);
                    String path = mappingInfo.getPatternValues().iterator().next();
                    String operationId = path.contains("bulk-approve") ? "bulkApproveResource" : "approveResource";
                    return new CanonicalOperationRef("registry-group", operationId, path, "POST");
                });
        when(schemaResolver.resolve(any(String.class), any(String.class), any(String.class), anyBoolean(), any(), any(), any(String.class), any(Boolean.class)))
                .thenAnswer(invocation -> new CanonicalSchemaRef(
                        invocation.getArgument(2, String.class) + "-" + invocation.getArgument(0, String.class),
                        invocation.getArgument(2, String.class),
                        "/schemas/filtered?path=" + invocation.getArgument(0, String.class)
                ));

        AnnotationDrivenActionDefinitionRegistry registry = new AnnotationDrivenActionDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.WARN,
                AnnotationConflictMode.WARN
        );

        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<List<ActionDefinition>>> tasks = List.of(
                    () -> awaitAndLookup(start, () -> registry.findByResourceKey("registry.actions")),
                    () -> awaitAndLookup(start, () -> registry.findByGroup("registry-group")),
                    () -> awaitAndLookup(start, () -> registry.findByResourceKey("registry.actions")),
                    () -> awaitAndLookup(start, () -> registry.findByGroup("registry-group")),
                    () -> awaitAndLookup(start, () -> registry.findByResourceKey("registry.actions")),
                    () -> awaitAndLookup(start, () -> registry.findByGroup("registry-group"))
            );
            List<Future<List<ActionDefinition>>> futures = tasks.stream().map(executor::submit).toList();
            start.countDown();

            for (Future<List<ActionDefinition>> future : futures) {
                assertEquals(List.of("approve", "bulk-approve"), future.get(5, TimeUnit.SECONDS).stream().map(ActionDefinition::id).toList());
            }
        } finally {
            executor.shutdownNow();
        }

        verify(handlerMapping, times(1)).getHandlerMethods();
    }

    private List<ActionDefinition> awaitAndLookup(CountDownLatch start, CheckedLookup<List<ActionDefinition>> lookup) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return lookup.run();
    }

    @FunctionalInterface
    private interface CheckedLookup<T> {
        T run() throws Exception;
    }

    @ApiResource(value = "/registry-actions", resourceKey = "registry.actions")
    @ApiGroup("registry-group")
    static class RegistryActionController extends AbstractResourceController<TestDto, Long, TestFilterDTO, TestCreateDTO, TestUpdateDTO> {

        @Override
        protected BaseResourceService<TestDto, Long, TestFilterDTO, TestCreateDTO, TestUpdateDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(TestDto dto) {
            return dto.id();
        }

        @Override
        protected String getIdFieldName() {
            return "employeeId";
        }

        @PostMapping("/{id}/actions/approve")
        @Operation(summary = "Approve resource")
        @WorkflowAction(id = "approve", title = "Approve", scope = ActionScope.ITEM)
        public void approve(@PathVariable Long id) {
        }

        @PostMapping("/{id}/actions/reject")
        @Operation(summary = "Reject resource")
        @WorkflowAction(id = "reject", title = "Reject", scope = ActionScope.ITEM)
        public void reject(@PathVariable Long id) {
        }

        @PostMapping("/actions/bulk-approve")
        @Operation(summary = "Bulk approve resource")
        @WorkflowAction(id = "bulk-approve", title = "Bulk Approve", scope = ActionScope.COLLECTION)
        public void bulkApprove() {
        }
    }

    @ApiResource(value = "/invalid-method-actions", resourceKey = "invalid.method.actions")
    static class InvalidMethodActionController extends AbstractReadOnlyResourceController<TestDto, Long, TestFilterDTO> {

        @Override
        protected BaseResourceQueryService<TestDto, Long, TestFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(TestDto dto) {
            return dto.id();
        }

        @GetMapping("/{id}/actions/approve")
        @WorkflowAction(id = "approve", title = "Approve", scope = ActionScope.ITEM)
        public void approve(@PathVariable Long id) {
        }
    }

    @ApiResource(value = "/invalid-path-actions", resourceKey = "invalid.path.actions")
    static class InvalidPathActionController extends AbstractReadOnlyResourceController<TestDto, Long, TestFilterDTO> {

        @Override
        protected BaseResourceQueryService<TestDto, Long, TestFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(TestDto dto) {
            return dto.id();
        }

        @PostMapping("/{id}/approve")
        @WorkflowAction(id = "approve", title = "Approve", scope = ActionScope.ITEM)
        public void approve(@PathVariable Long id) {
        }
    }

    @ApiResource(value = "/conflicting-actions", resourceKey = "conflicting.actions")
    static class ConflictingActionController extends AbstractReadOnlyResourceController<TestDto, Long, TestFilterDTO> {

        @Override
        protected BaseResourceQueryService<TestDto, Long, TestFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(TestDto dto) {
            return dto.id();
        }

        @Override
        protected String getIdFieldName() {
            return "id";
        }

        @PostMapping("/{id}/actions/approve")
        @UiSurface(
                id = "approve",
                kind = SurfaceKind.PARTIAL_FORM,
                scope = SurfaceScope.ITEM,
                title = "Approve"
        )
        @WorkflowAction(id = "approve", title = "Approve", scope = ActionScope.ITEM)
        public void approve(@PathVariable Long id) {
        }
    }

    record TestDto(Long id) {
    }

    static final class TestCreateDTO {
    }

    static final class TestUpdateDTO {
    }

    static final class TestFilterDTO implements GenericFilterDTO {
    }
}
