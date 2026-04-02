package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
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
        when(service.findById(1L)).thenReturn(new SimpleDto(1L));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWith(service)).build();

        mockMvc.perform(get("/ro/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.create").doesNotExist())
                .andExpect(jsonPath("$._links.update").doesNotExist())
                .andExpect(jsonPath("$._links.delete").doesNotExist());
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
    }

    private static ReadOnlyController controllerWith(ReadOnlyService service) {
        ReadOnlyController controller = new ReadOnlyController();
        ReflectionTestUtils.setField(controller, "service", service);
        return controller;
    }
}
