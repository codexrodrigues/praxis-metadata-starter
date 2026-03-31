package org.praxisplatform.uischema.surface;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController;
import org.praxisplatform.uischema.controller.base.AbstractResourceController;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.praxisplatform.uischema.validation.AnnotationConflictMode;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.method.HandlerMethod;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnotationDrivenSurfaceDefinitionRegistryTest {

    @Test
    void cachesDiscoveredDefinitionsAndIgnoresWorkflowLikeMappings() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        RegistryTestController controller = new RegistryTestController();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-resources/all").build(),
                new HandlerMethod(controller, AbstractResourceQueryController.class.getMethod("getAll"))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-resources/{id}/actions/approve").build(),
                new HandlerMethod(controller, RegistryTestController.class.getDeclaredMethod("approve", Long.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/registry-resources/{id}/profile").methods(org.springframework.web.bind.annotation.RequestMethod.PATCH).build(),
                new HandlerMethod(controller, RegistryTestController.class.getDeclaredMethod("updateProfile", Long.class))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);
        when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenAnswer(invocation -> {
                    RequestMappingInfo mappingInfo = invocation.getArgument(1);
                    String path = mappingInfo.getPatternValues().iterator().next();
                    String operationId = path.contains("/actions/")
                            ? "approveResource"
                            : path.contains("/profile")
                            ? "updateProfile"
                            : "listResources";
                    String method = path.contains("/actions/")
                            ? "POST"
                            : path.contains("/profile")
                            ? "PATCH"
                            : "GET";
                    return new CanonicalOperationRef("registry-group", operationId, path, method);
                });
        when(schemaResolver.resolve(any(), any(), any(), any(Boolean.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> new CanonicalSchemaRef(
                        "schema-" + invocation.getArgument(2, String.class),
                        invocation.getArgument(2, String.class),
                        "/schemas/filtered?path=" + invocation.getArgument(0, String.class)
                ));

        AnnotationDrivenSurfaceDefinitionRegistry registry = new AnnotationDrivenSurfaceDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.WARN
        );

        List<SurfaceDefinition> firstLookup = registry.findByResourceKey("registry.resources");
        List<SurfaceDefinition> secondLookup = registry.findByResourceKey("registry.resources");

        verify(handlerMapping, times(1)).getHandlerMethods();
        assertEquals(firstLookup, secondLookup);
        assertEquals(2, firstLookup.size());
        SurfaceDefinition listSurface = firstLookup.stream()
                .filter(surface -> "list".equals(surface.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("list", listSurface.id());
        SurfaceDefinition profileSurface = firstLookup.stream()
                .filter(surface -> "profile".equals(surface.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("employee:profile:update"), profileSurface.requiredAuthorities());
        assertEquals(List.of("ACTIVE"), profileSurface.allowedStates());
        assertNull(firstLookup.stream().filter(surface -> "approve".equals(surface.id())).findFirst().orElse(null));

        List<SurfaceDefinition> byGroup = registry.findByGroup("registry-group");
        assertEquals(2, byGroup.size());
        assertNotNull(byGroup.stream().filter(surface -> "list".equals(surface.id())).findFirst().orElse(null));
        assertNotNull(byGroup.stream().filter(surface -> "profile".equals(surface.id())).findFirst().orElse(null));
    }

    @Test
    void publishesAutomaticSurfacesForMutableAndReadOnlyControllersWithCanonicalSchemaTypes() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        MutableRegistryController mutableController = new MutableRegistryController();
        ReadOnlyRegistryController readOnlyController = new ReadOnlyRegistryController();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/mutable-resources/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(mutableController, AbstractResourceQueryController.class.getMethod("getAll"))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/mutable-resources/{id}").methods(RequestMethod.GET).build(),
                new HandlerMethod(mutableController, AbstractResourceQueryController.class.getMethod("getById", Object.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/mutable-resources").methods(RequestMethod.POST).build(),
                new HandlerMethod(mutableController, AbstractResourceController.class.getMethod("create", Object.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/mutable-resources/{id}").methods(RequestMethod.PUT).build(),
                new HandlerMethod(mutableController, AbstractResourceController.class.getMethod("update", Object.class, Object.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/read-only-resources/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(readOnlyController, AbstractResourceQueryController.class.getMethod("getAll"))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/read-only-resources/{id}").methods(RequestMethod.GET).build(),
                new HandlerMethod(readOnlyController, AbstractResourceQueryController.class.getMethod("getById", Object.class))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/read-only-resources/{id}:approve").methods(RequestMethod.POST).build(),
                new HandlerMethod(readOnlyController, ReadOnlyRegistryController.class.getDeclaredMethod("approveAlias", Long.class))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);
        when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenAnswer(invocation -> {
                    RequestMappingInfo mappingInfo = invocation.getArgument(1);
                    String path = mappingInfo.getPatternValues().iterator().next();
                    String method = mappingInfo.getMethodsCondition().getMethods().stream().findFirst().map(Enum::name).orElse("GET");
                    String operationId = switch (path) {
                        case "/mutable-resources/all" -> "listMutableResources";
                        case "/mutable-resources/{id}" -> "GET".equals(method) ? "getMutableResource" : "updateMutableResource";
                        case "/mutable-resources" -> "createMutableResource";
                        case "/read-only-resources/all" -> "listReadOnlyResources";
                        case "/read-only-resources/{id}" -> "getReadOnlyResource";
                        default -> "ignored";
                    };
                    return new CanonicalOperationRef("registry-group", operationId, path, method);
                });
        when(schemaResolver.resolve(any(), any(), any(), any(Boolean.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> new CanonicalSchemaRef(
                        "schema-" + invocation.getArgument(2, String.class) + "-" + invocation.getArgument(1, String.class).toString().toLowerCase(),
                        invocation.getArgument(2, String.class),
                        "/schemas/filtered?path=" + invocation.getArgument(0, String.class)
                ));

        AnnotationDrivenSurfaceDefinitionRegistry registry = new AnnotationDrivenSurfaceDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.WARN
        );

        List<SurfaceDefinition> mutableSurfaces = registry.findByResourceKey("registry.mutable");
        List<SurfaceDefinition> readOnlySurfaces = registry.findByResourceKey("registry.read-only");

        assertEquals(List.of("create", "list", "detail", "edit"), mutableSurfaces.stream().map(SurfaceDefinition::id).toList());
        assertEquals("request", mutableSurfaces.stream().filter(surface -> "create".equals(surface.id())).findFirst().orElseThrow().schemaType());
        assertEquals("request", mutableSurfaces.stream().filter(surface -> "edit".equals(surface.id())).findFirst().orElseThrow().schemaType());
        assertEquals("response", mutableSurfaces.stream().filter(surface -> "list".equals(surface.id())).findFirst().orElseThrow().schemaType());
        assertEquals("response", mutableSurfaces.stream().filter(surface -> "detail".equals(surface.id())).findFirst().orElseThrow().schemaType());

        assertEquals(List.of("list", "detail"), readOnlySurfaces.stream().map(SurfaceDefinition::id).toList());
        assertTrue(readOnlySurfaces.stream().noneMatch(surface -> "create".equals(surface.id()) || "edit".equals(surface.id()) || "approve".equals(surface.id())));
    }

    @Test
    void buildsCachedSnapshotOnlyOnceUnderConcurrentLookupLoad() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        MutableRegistryController mutableController = new MutableRegistryController();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/mutable-resources/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(mutableController, AbstractResourceQueryController.class.getMethod("getAll"))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/mutable-resources/{id}").methods(RequestMethod.GET).build(),
                new HandlerMethod(mutableController, AbstractResourceQueryController.class.getMethod("getById", Object.class))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);
        when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenAnswer(invocation -> {
                    RequestMappingInfo mappingInfo = invocation.getArgument(1);
                    String path = mappingInfo.getPatternValues().iterator().next();
                    return new CanonicalOperationRef("registry-group", path.contains("/all") ? "listMutableResources" : "getMutableResource", path, "GET");
                });
        when(schemaResolver.resolve(any(), any(), any(), any(Boolean.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> new CanonicalSchemaRef(
                        "schema-" + invocation.getArgument(2, String.class),
                        invocation.getArgument(2, String.class),
                        "/schemas/filtered?path=" + invocation.getArgument(0, String.class)
                ));

        AnnotationDrivenSurfaceDefinitionRegistry registry = new AnnotationDrivenSurfaceDefinitionRegistry(
                handlerMapping,
                applicationContext,
                operationResolver,
                schemaResolver,
                AnnotationConflictMode.WARN
        );

        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<List<SurfaceDefinition>>> tasks = List.of(
                    () -> awaitAndLookup(start, () -> registry.findByResourceKey("registry.mutable")),
                    () -> awaitAndLookup(start, () -> registry.findByGroup("registry-group")),
                    () -> awaitAndLookup(start, () -> registry.findByResourceKey("registry.mutable")),
                    () -> awaitAndLookup(start, () -> registry.findByGroup("registry-group")),
                    () -> awaitAndLookup(start, () -> registry.findByResourceKey("registry.mutable")),
                    () -> awaitAndLookup(start, () -> registry.findByGroup("registry-group"))
            );
            List<Future<List<SurfaceDefinition>>> futures = tasks.stream().map(executor::submit).toList();
            start.countDown();

            for (Future<List<SurfaceDefinition>> future : futures) {
                assertEquals(List.of("list", "detail"), future.get(5, TimeUnit.SECONDS).stream().map(SurfaceDefinition::id).toList());
            }
        } finally {
            executor.shutdownNow();
        }

        verify(handlerMapping, times(1)).getHandlerMethods();
    }

    private List<SurfaceDefinition> awaitAndLookup(CountDownLatch start, CheckedLookup<List<SurfaceDefinition>> lookup) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return lookup.run();
    }

    @FunctionalInterface
    private interface CheckedLookup<T> {
        T run() throws Exception;
    }

    @ApiResource(value = "/registry-resources", resourceKey = "registry.resources")
    @ApiGroup("registry-group")
    static class RegistryTestController extends AbstractResourceController<TestDto, Long, TestFilterDTO, TestCreateDTO, TestUpdateDTO> {

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
        @Operation(summary = "Aprovar recurso")
        @UiSurface(
                id = "approve",
                kind = SurfaceKind.PARTIAL_FORM,
                scope = SurfaceScope.ITEM,
                title = "Aprovar"
        )
        public void approve(@PathVariable Long id) {
        }

        @PatchMapping("/{id}/profile")
        @Operation(summary = "Atualizar perfil")
        @UiSurface(
                id = "profile",
                kind = SurfaceKind.PARTIAL_FORM,
                scope = SurfaceScope.ITEM,
                title = "Perfil",
                requiredAuthorities = {"employee:profile:update"},
                allowedStates = {"ACTIVE"}
        )
        public void updateProfile(@PathVariable Long id) {
        }
    }

    @ApiResource(value = "/mutable-resources", resourceKey = "registry.mutable")
    @ApiGroup("registry-group")
    static class MutableRegistryController extends AbstractResourceController<TestDto, Long, TestFilterDTO, TestCreateDTO, TestUpdateDTO> {

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
    }

    @ApiResource(value = "/read-only-resources", resourceKey = "registry.read-only")
    @ApiGroup("registry-group")
    static class ReadOnlyRegistryController extends AbstractReadOnlyResourceController<TestDto, Long, TestFilterDTO> {

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

        @PostMapping("/{id}:approve")
        @Operation(summary = "Aprovar via alias")
        @UiSurface(
                id = "approve",
                kind = SurfaceKind.PARTIAL_FORM,
                scope = SurfaceScope.ITEM,
                title = "Aprovar"
        )
        public void approveAlias(@PathVariable Long id) {
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
