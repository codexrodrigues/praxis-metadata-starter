package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.hateoas.Link;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AbstractResourceControllerLinksTest.SimpleController.class)
@Import(AbstractResourceControllerLinksTest.SimpleController.class)
class AbstractResourceControllerLinksTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void getAllIncludesSchemaTypeResponse() throws Exception {
        when(service.findAll()).thenReturn(List.of(new SimpleResponseDto(1L)));

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
    static class SimpleController extends AbstractResourceController<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {

        @Autowired
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
    }
}
