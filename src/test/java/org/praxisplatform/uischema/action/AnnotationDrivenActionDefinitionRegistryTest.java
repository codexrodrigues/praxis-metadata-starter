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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
                RequestMappingInfo.paths("/registry-actions/{id}/actions/approve").build(),
                new HandlerMethod(controller, RegistryActionController.class.getDeclaredMethod("approve", Long.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-actions/{id}/actions/reject").build(),
                new HandlerMethod(controller, RegistryActionController.class.getDeclaredMethod("reject", Long.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-actions/actions/bulk-approve").build(),
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
        when(schemaResolver.resolve(any(CanonicalOperationRef.class), any(String.class)))
                .thenAnswer(invocation -> {
                    CanonicalOperationRef operationRef = invocation.getArgument(0, CanonicalOperationRef.class);
                    String schemaType = invocation.getArgument(1, String.class);
                    return new CanonicalSchemaRef(schemaType + "-" + operationRef.operationId(), schemaType, "/schemas/filtered");
                });

        AnnotationDrivenActionDefinitionRegistry registry = new AnnotationDrivenActionDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.WARN
        );

        List<ActionDefinition> firstLookup = registry.findByResourceKey("registry.actions");
        List<ActionDefinition> secondLookup = registry.findByResourceKey("registry.actions");

        verify(handlerMapping, times(1)).getHandlerMethods();
        assertEquals(firstLookup, secondLookup);
        assertEquals(3, firstLookup.size());
        assertEquals(List.of("approve", "bulk-approve", "reject"), firstLookup.stream().map(ActionDefinition::id).toList());
        assertEquals(ActionScope.COLLECTION, firstLookup.stream()
                .filter(action -> "bulk-approve".equals(action.id()))
                .findFirst()
                .orElseThrow()
                .scope());
        assertNotNull(registry.findByGroup("registry-group").stream().filter(action -> "approve".equals(action.id())).findFirst().orElse(null));
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
                RequestMappingInfo.paths("/conflicting-actions/{id}/actions/approve").build(),
                new HandlerMethod(controller, ConflictingActionController.class.getDeclaredMethod("approve", Long.class))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);

        AnnotationDrivenActionDefinitionRegistry registry = new AnnotationDrivenActionDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.FAIL
        );

        assertThrows(IllegalStateException.class, () -> registry.findByResourceKey("conflicting.actions"));
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
            return "id";
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
