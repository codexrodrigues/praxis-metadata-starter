package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.springframework.hateoas.Link;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AbstractReadOnlyResourceControllerLinksTest {

    @Test
    void getAllOmitsWriteLinks() throws Exception {
        ReadOnlyService service = mock(ReadOnlyService.class);
        when(service.findAll()).thenReturn(List.of(new SimpleDto(1L)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWith(service)).build();

        mockMvc.perform(get("/ro/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.create").doesNotExist())
                .andExpect(jsonPath("$._links.update").doesNotExist())
                .andExpect(jsonPath("$._links.delete").doesNotExist())
                .andExpect(jsonPath("$.data[0]._links.create").doesNotExist())
                .andExpect(jsonPath("$.data[0]._links.update").doesNotExist())
                .andExpect(jsonPath("$.data[0]._links.delete").doesNotExist());
    }

    @Test
    void getByIdOmitsWriteLinks() throws Exception {
        ReadOnlyService service = mock(ReadOnlyService.class);
        when(service.findById(1L)).thenReturn(ResourceRepresentationResult.unversioned(new SimpleDto(1L)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWith(service)).build();

        mockMvc.perform(get("/ro/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.create").doesNotExist())
                .andExpect(jsonPath("$._links.update").doesNotExist())
                .andExpect(jsonPath("$._links.delete").doesNotExist());
    }

    @Test
    void getStatsCapabilitiesReturnsResourceStatsCapability() throws Exception {
        ReadOnlyService service = mock(ReadOnlyService.class);
        ReadOnlyController controller = controllerWith(service);
        CapabilityService capService = mock(CapabilityService.class);
        org.praxisplatform.uischema.stats.StatsFieldCapability fieldCap =
                new org.praxisplatform.uischema.stats.StatsFieldCapability(
                        "status", "Status", false, List.of("count"), List.of("group-by"),
                        true, false, false, false, false
                );
        org.praxisplatform.uischema.stats.StatsCapability statsCap =
                new org.praxisplatform.uischema.stats.StatsCapability(List.of(fieldCap));
        org.praxisplatform.uischema.capability.CapabilitySnapshot snapshot =
                new org.praxisplatform.uischema.capability.CapabilitySnapshot(
                        "test.ro", "/ro", "test.ro", null,
                        java.util.Map.of(), java.util.Map.of(), List.of(), List.of(),
                        statsCap
                );
        when(capService.collectionCapabilities("test.ro", "/ro")).thenReturn(snapshot);
        ReflectionTestUtils.setField(controller, "capabilityService", capService);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/ro/stats/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].field").value("status"))
                .andExpect(jsonPath("$.fields[0].groupByEligible").value(true));
    }

    @Test
    void writeOperationsAreNotExposedByTheReadOnlyBase() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWith(mock(ReadOnlyService.class))).build();

        mockMvc.perform(post("/ro").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/ro/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(status().reason(containsString("Method 'PUT' is not supported")));

        mockMvc.perform(delete("/ro/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(status().reason(containsString("Method 'DELETE' is not supported")));

        mockMvc.perform(delete("/ro/batch"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(status().reason(containsString("Method 'DELETE' is not supported")));
    }

    @Test
    void linkToUiSchemaMarksReadOnlyResources() {
        ReadOnlyController controller = new ReadOnlyController();

        Link link = controller.exposeLinkToUiSchema("/all", "get", "response");

        assertTrue(link.getHref().endsWith(
                "/schemas/filtered?path=/ro/all&operation=get&schemaType=response&idField=id&readOnly=true"
        ));
        assertEquals("schema", link.getRel().value());
    }

    @Test
    void readOnlyCollectionDiscoveryLinksExposeSurfacesAndCapabilitiesButNotActions() {
        ReadOnlyController controller = new ReadOnlyController();
        ReflectionTestUtils.setField(controller, "surfaceCatalogService", mock(SurfaceCatalogService.class));
        ReflectionTestUtils.setField(controller, "capabilityService", mock(CapabilityService.class));

        assertEquals(List.of("surfaces", "capabilities"), controller.exposeCollectionDiscoveryRels());
    }

    @Test
    void readOnlyCollectionDiscoveryLinksExposeStatsWhenResourceSupportsStats() {
        ReadOnlyController controller = new ReadOnlyController();
        ReadOnlyService service = mock(ReadOnlyService.class);
        controller.service = service;
        org.praxisplatform.uischema.stats.StatsFieldCapability fieldCap =
                new org.praxisplatform.uischema.stats.StatsFieldCapability(
                        "status", "Status", false, List.of("count"), List.of("group-by"),
                        true, false, false, false, false
                );
        org.praxisplatform.uischema.capability.ResourceStructuralCapabilities structural =
                new org.praxisplatform.uischema.capability.ResourceStructuralCapabilities(
                        true, true, true, false, false, false, false,
                        new org.praxisplatform.uischema.stats.StatsCapability(List.of(fieldCap)),
                        null
                );
        when(service.getStructuralCapabilities()).thenReturn(structural);
        ReflectionTestUtils.setField(controller, "surfaceCatalogService", mock(SurfaceCatalogService.class));
        ReflectionTestUtils.setField(controller, "capabilityService", mock(CapabilityService.class));

        assertEquals(List.of("surfaces", "capabilities", "stats"), controller.exposeCollectionDiscoveryRels());
    }

    @Test
    void readOnlyItemDiscoveryLinksExposeSurfacesAndCapabilitiesButNotActions() {
        ReadOnlyController controller = new ReadOnlyController();
        ReflectionTestUtils.setField(controller, "surfaceCatalogService", mock(SurfaceCatalogService.class));
        ReflectionTestUtils.setField(controller, "capabilityService", mock(CapabilityService.class));

        assertEquals(List.of("surfaces", "capabilities"), controller.exposeItemDiscoveryRels(10L));
    }

    interface ReadOnlyService extends BaseResourceQueryService<SimpleDto, Long, SimpleFilterDTO> {}

    static class SimpleDto {
        private Long id;
        public SimpleDto() {}
        public SimpleDto(Long id) { this.id = id; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleFilterDTO implements GenericFilterDTO {}

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/ro")
    @ApiResource(value = "/ro", resourceKey = "test.ro")
    static class ReadOnlyController extends AbstractReadOnlyResourceController<SimpleDto, Long, SimpleFilterDTO> {

        ReadOnlyService service;

        @Override
        protected ReadOnlyService getService() {
            return service;
        }

        @Override
        protected Long getResponseId(SimpleDto dto) {
            return dto.getId();
        }

        @Override
        protected String getIdFieldName() {
            return "id";
        }

        @Override
        protected String getBasePath() {
            return "/ro";
        }

        Link exposeLinkToUiSchema(String methodPath, String operation, String schemaType) {
            return linkToUiSchema(methodPath, operation, schemaType);
        }

        List<String> exposeCollectionDiscoveryRels() {
            return buildCollectionDiscoveryLinks().stream().map(link -> link.getRel().value()).toList();
        }

        List<String> exposeItemDiscoveryRels(Long id) {
            return buildItemDiscoveryLinks(id).stream().map(link -> link.getRel().value()).toList();
        }
    }

    private static ReadOnlyController controllerWith(ReadOnlyService service) {
        ReadOnlyController controller = new ReadOnlyController();
        ReflectionTestUtils.setField(controller, "service", service);
        return controller;
    }
}
