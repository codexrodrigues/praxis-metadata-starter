package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceCommandService;
import org.praxisplatform.uischema.service.base.LegacyBackedResourceService;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractLegacyBackedResourceControllerTest {

    @Test
    void legacyBackedControllerDoesNotPublishDuplicateDraftEvenWhenServiceSupportsIt() {
        SimpleLegacyService service = mock(SimpleLegacyService.class);
        when(service.supportsDuplicateDraft()).thenReturn(true);
        SimpleLegacyController controller = controllerWith(service);

        assertEquals(List.of("update", "delete"), controller.exposeEntityActionRels(10L));
    }

    @Test
    void duplicateDraftControllerPublishesDuplicateDraftOnlyWhenServiceSupportsIt() {
        SimpleLegacyService unsupported = mock(SimpleLegacyService.class);
        SimpleDuplicateDraftLegacyController unsupportedController = duplicateDraftControllerWith(unsupported);

        SimpleLegacyService supported = mock(SimpleLegacyService.class);
        when(supported.supportsDuplicateDraft()).thenReturn(true);
        SimpleDuplicateDraftLegacyController supportedController = duplicateDraftControllerWith(supported);

        assertEquals(List.of("update", "delete"), unsupportedController.exposeEntityActionRels(10L));
        assertEquals(List.of("update", "delete", "duplicate-draft"), supportedController.exposeEntityActionRels(10L));
    }

    @Test
    void duplicateDraftDelegatesToLegacyCommandPort() {
        SimpleLegacyService service = mock(SimpleLegacyService.class);
        when(service.getDatasetVersion()).thenReturn(java.util.Optional.of("legacy-1"));
        when(service.supportsDuplicateDraft()).thenReturn(true);
        when(service.duplicateDraft(7L))
                .thenReturn(new BaseResourceCommandService.SavedResult<>(17L, new SimpleResponseDto(17L)));
        SimpleDuplicateDraftLegacyController controller = duplicateDraftControllerWith(service);

        var response = controller.duplicateDraft(7L);

        assertEquals(201, response.getStatusCode().value());
        assertEquals("legacy-1", response.getHeaders().getFirst("X-Data-Version"));
    }

    @Test
    void duplicateDraftEndpointReturnsNotFoundWhenServiceDoesNotSupportCommand() {
        SimpleLegacyService service = mock(SimpleLegacyService.class);
        SimpleDuplicateDraftLegacyController controller = duplicateDraftControllerWith(service);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.duplicateDraft(7L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    private static SimpleLegacyController controllerWith(SimpleLegacyService service) {
        SimpleLegacyController controller = new SimpleLegacyController();
        ReflectionTestUtils.setField(controller, "service", service);
        return controller;
    }

    private static SimpleDuplicateDraftLegacyController duplicateDraftControllerWith(SimpleLegacyService service) {
        SimpleDuplicateDraftLegacyController controller = new SimpleDuplicateDraftLegacyController();
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

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/duplicate-draft-legacy-simple")
    static class SimpleDuplicateDraftLegacyController extends AbstractDuplicateDraftLegacyBackedResourceController<
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
            return "/duplicate-draft-legacy-simple";
        }

        List<String> exposeEntityActionRels(Long id) {
            return buildEntityActionLinks(id).stream().map(Link::getRel).map(Object::toString).toList();
        }
    }
}
