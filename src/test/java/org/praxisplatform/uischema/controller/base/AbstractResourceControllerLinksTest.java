package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.springframework.hateoas.Link;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AbstractResourceControllerLinksTest {

    @Test
    void getAllReturnsOk() throws Exception {
        SimpleService service = mock(SimpleService.class);
        when(service.findAll()).thenReturn(List.of(new SimpleResponseDto(1L)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWith(service)).build();

        mockMvc.perform(get("/simple/all"))
                .andExpect(status().isOk());
    }

    @Test
    void linkToUiSchemaUsesEnglishValidationMessages() {
        SimpleController controller = new SimpleController();

        IllegalArgumentException methodPathException = assertThrows(
                IllegalArgumentException.class,
                () -> controller.exposeLinkToUiSchema(null, "get", "response")
        );
        assertEquals("Parameter 'methodPath' must not be null or blank.", methodPathException.getMessage());

        IllegalArgumentException operationException = assertThrows(
                IllegalArgumentException.class,
                () -> controller.exposeLinkToUiSchema("/all", "  ", "response")
        );
        assertEquals("Parameter 'operation' must not be null or blank.", operationException.getMessage());
    }

    @Test
    void linkToUiSchemaBuildsExpectedSchemaLink() {
        SimpleController controller = new SimpleController();

        Link link = controller.exposeLinkToUiSchema("/filter", "post", "request");

        assertTrue(link.getHref().endsWith(
                "/schemas/filtered?path=/simple/filter&operation=post&schemaType=request&idField=id&readOnly=false"
        ));
        assertEquals("schema", link.getRel().value());
    }

    @Test
    void linkToUiSchemaNormalizesCreateRootPathWithoutTrailingSlash() {
        SimpleController controller = new SimpleController();

        Link link = controller.exposeLinkToUiSchema("/", "post", "request");

        assertTrue(link.getHref().endsWith(
                "/schemas/filtered?path=/simple&operation=post&schemaType=request&idField=id&readOnly=false"
        ));
    }

    @Test
    void entityActionLinksOmitCreateAndCollectionCarriesCreate() {
        SimpleController controller = new SimpleController();

        assertEquals(List.of("update", "delete"), controller.exposeEntityActionRels(10L));
        assertEquals(List.of("create"), controller.exposeCollectionActionRels());
    }

    @Test
    void collectionDiscoveryLinksExposeSurfacesCapabilitiesAndScopedActions() {
        SimpleController controller = new SimpleController();
        ReflectionTestUtils.setField(controller, "surfaceCatalogService", mock(SurfaceCatalogService.class));
        ReflectionTestUtils.setField(controller, "capabilityService", mock(CapabilityService.class));
        ReflectionTestUtils.setField(controller, "actionCatalogService", mock(org.praxisplatform.uischema.action.ActionCatalogService.class));
        ActionDefinitionRegistry registry = mock(ActionDefinitionRegistry.class);
        ReflectionTestUtils.setField(controller, "actionDefinitionRegistry", registry);
        when(registry.findByResourceKey("test.simple")).thenReturn(List.of(collectionAction()));

        assertEquals(List.of("surfaces", "actions", "capabilities"), controller.exposeCollectionDiscoveryRels());
    }

    @Test
    void itemDiscoveryLinksOmitActionsWhenNoItemWorkflowExists() {
        SimpleController controller = new SimpleController();
        ReflectionTestUtils.setField(controller, "surfaceCatalogService", mock(SurfaceCatalogService.class));
        ReflectionTestUtils.setField(controller, "capabilityService", mock(CapabilityService.class));
        ReflectionTestUtils.setField(controller, "actionCatalogService", mock(org.praxisplatform.uischema.action.ActionCatalogService.class));
        ActionDefinitionRegistry registry = mock(ActionDefinitionRegistry.class);
        ReflectionTestUtils.setField(controller, "actionDefinitionRegistry", registry);
        when(registry.findByResourceKey("test.simple")).thenReturn(List.of(collectionAction()));

        assertEquals(List.of("surfaces", "capabilities"), controller.exposeItemDiscoveryRels(10L));
    }

    private static ActionDefinition collectionAction() {
        return new ActionDefinition(
                "bulk-approve",
                "test.simple",
                "/simple",
                "simple",
                ActionScope.COLLECTION,
                "Bulk approve",
                "",
                null,
                null,
                null,
                0,
                null,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static SimpleController controllerWith(SimpleService service) {
        SimpleController controller = new SimpleController();
        ReflectionTestUtils.setField(controller, "service", service);
        return controller;
    }

    interface SimpleService extends BaseResourceService<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {}

    static class SimpleResponseDto {
        private Long id;
        SimpleResponseDto() {}
        SimpleResponseDto(Long id) { this.id = id; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleCreateDto {}

    static class SimpleUpdateDto {}

    static class SimpleFilterDTO implements GenericFilterDTO {}

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/simple")
    @ApiResource(value = "/simple", resourceKey = "test.simple")
    static class SimpleController extends AbstractResourceController<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {

        SimpleService service;

        @Override
        protected SimpleService getService() {
            return service;
        }

        @Override
        protected Long getResponseId(SimpleResponseDto dto) {
            return dto.getId();
        }

        @Override
        protected String getIdFieldName() {
            return "id";
        }

        @Override
        protected String getBasePath() {
            return "/simple";
        }

        Link exposeLinkToUiSchema(String methodPath, String operation, String schemaType) {
            return linkToUiSchema(methodPath, operation, schemaType);
        }

        List<String> exposeEntityActionRels(Long id) {
            return buildEntityActionLinks(id).stream().map(link -> link.getRel().value()).toList();
        }

        List<String> exposeCollectionActionRels() {
            return buildCollectionActionLinks().stream().map(link -> link.getRel().value()).toList();
        }

        List<String> exposeCollectionDiscoveryRels() {
            return buildCollectionDiscoveryLinks().stream().map(link -> link.getRel().value()).toList();
        }

        List<String> exposeItemDiscoveryRels(Long id) {
            return buildItemDiscoveryLinks(id).stream().map(link -> link.getRel().value()).toList();
        }
    }
}
