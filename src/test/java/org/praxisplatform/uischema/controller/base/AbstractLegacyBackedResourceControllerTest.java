package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceCommandService;
import org.praxisplatform.uischema.service.base.LegacyBackedResourceService;
import org.springframework.hateoas.Link;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractLegacyBackedResourceControllerTest {

    @Test
    void legacyBackedControllerPublishesDuplicateDraftOnlyWhenServiceSupportsIt() {
        SimpleLegacyService unsupported = mock(SimpleLegacyService.class);
        SimpleLegacyController unsupportedController = controllerWith(unsupported);

        SimpleLegacyService supported = mock(SimpleLegacyService.class);
        when(supported.supportsDuplicateDraft()).thenReturn(true);
        SimpleLegacyController supportedController = controllerWith(supported);

        assertEquals(List.of("update", "delete"), unsupportedController.exposeEntityActionRels(10L));
        assertEquals(List.of("update", "delete", "duplicate-draft"), supportedController.exposeEntityActionRels(10L));
    }

    @Test
    void duplicateDraftDelegatesToLegacyCommandPort() {
        SimpleLegacyService service = mock(SimpleLegacyService.class);
        when(service.getDatasetVersion()).thenReturn(java.util.Optional.of("legacy-1"));
        when(service.duplicateDraft(7L))
                .thenReturn(new BaseResourceCommandService.SavedResult<>(17L, new SimpleResponseDto(17L)));
        SimpleLegacyController controller = controllerWith(service);

        var response = controller.duplicateDraft(7L);

        assertEquals(201, response.getStatusCode().value());
        assertEquals("legacy-1", response.getHeaders().getFirst("X-Data-Version"));
    }

    private static SimpleLegacyController controllerWith(SimpleLegacyService service) {
        SimpleLegacyController controller = new SimpleLegacyController();
        ReflectionTestUtils.setField(controller, "service", service);
        return controller;
    }

    interface SimpleLegacyService extends LegacyBackedResourceService<
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
    @org.springframework.web.bind.annotation.RequestMapping("/legacy-simple")
    static class SimpleLegacyController extends AbstractLegacyBackedResourceController<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {

        SimpleLegacyService service;

        @Override
        protected SimpleLegacyService getService() {
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
            return "/legacy-simple";
        }

        List<String> exposeEntityActionRels(Long id) {
            return buildEntityActionLinks(id).stream().map(Link::getRel).map(Object::toString).toList();
        }
    }
}
