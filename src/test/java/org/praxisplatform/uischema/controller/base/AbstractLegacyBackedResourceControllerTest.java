package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.DuplicateDraftLegacyBackedResourceService;
import org.praxisplatform.uischema.service.base.LegacyBackedResourceService;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractLegacyBackedResourceControllerTest {

    @Test
    void legacyBackedControllerDoesNotPublishDuplicateDraft() {
        SimpleLegacyService service = mock(SimpleLegacyService.class);
        SimpleLegacyController controller = controllerWith(service);

        assertEquals(List.of("update", "delete"), controller.exposeEntityActionRels(10L));
    }

    @Test
    void duplicateDraftControllerPublishesDuplicateDraftOnlyWhenServiceSupportsIt() {
        SimpleDuplicateDraftLegacyService unsupported = mock(SimpleDuplicateDraftLegacyService.class);
        when(unsupported.supportsDuplicateDraft()).thenReturn(false);
        SimpleDuplicateDraftLegacyController unsupportedController = duplicateDraftControllerWith(unsupported);

        SimpleDuplicateDraftLegacyService supported = mock(SimpleDuplicateDraftLegacyService.class);
        when(supported.supportsDuplicateDraft()).thenReturn(true);
        SimpleDuplicateDraftLegacyController supportedController = duplicateDraftControllerWith(supported);

        assertEquals(List.of("update", "delete"), unsupportedController.exposeEntityActionRels(10L));
        assertEquals(List.of("update", "delete", "duplicate-draft"), supportedController.exposeEntityActionRels(10L));
    }

    @Test
    void duplicateDraftDelegatesToLegacyCommandPort() {
        SimpleDuplicateDraftLegacyService service = mock(SimpleDuplicateDraftLegacyService.class);
        when(service.getDatasetVersion()).thenReturn(java.util.Optional.of("legacy-1"));
        when(service.supportsDuplicateDraft()).thenReturn(true);
        when(service.duplicateDraft(7L)).thenReturn(new SimpleCreateDto("copied"));
        SimpleDuplicateDraftLegacyController controller = duplicateDraftControllerWith(service);

        var response = controller.duplicateDraft(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("legacy-1", response.getHeaders().getFirst("X-Data-Version"));
        assertEquals("copied", response.getBody().getData().getName());
    }

    @Test
    void duplicateDraftResponsePublishesRequestAndResponseSchemaLinks() {
        SimpleDuplicateDraftLegacyService service = mock(SimpleDuplicateDraftLegacyService.class);
        when(service.supportsDuplicateDraft()).thenReturn(true);
        when(service.duplicateDraft(7L)).thenReturn(new SimpleCreateDto("copied"));
        SimpleDuplicateDraftLegacyController controller = duplicateDraftControllerWith(service);

        var response = controller.duplicateDraft(7L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schemaLinks = (List<Map<String, Object>>) response.getBody()
                .getLinks()
                .asMap()
                .get("schema");
        List<String> hrefs = schemaLinks.stream()
                .map(link -> String.valueOf(link.get("href")))
                .toList();
        assertTrue(hrefs.stream().anyMatch(href ->
                href.contains("duplicate-draft")
                        && href.contains("schemaType=request")));
        assertTrue(hrefs.stream().anyMatch(href ->
                href.contains("duplicate-draft")
                        && href.contains("schemaType=response")));
    }

    @Test
    void duplicateDraftEndpointReturnsNotFoundWhenServiceDoesNotSupportCommand() {
        SimpleDuplicateDraftLegacyService service = mock(SimpleDuplicateDraftLegacyService.class);
        when(service.supportsDuplicateDraft()).thenReturn(false);
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

    private static SimpleDuplicateDraftLegacyController duplicateDraftControllerWith(SimpleDuplicateDraftLegacyService service) {
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

    interface SimpleDuplicateDraftLegacyService extends DuplicateDraftLegacyBackedResourceService<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto,
            SimpleCreateDto> {}

    static class SimpleResponseDto {
        private Long id;
        SimpleResponseDto() {}
        SimpleResponseDto(Long id) { this.id = id; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleCreateDto {
        private String name;
        SimpleCreateDto() {}
        SimpleCreateDto(String name) { this.name = name; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

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
            SimpleUpdateDto,
            SimpleCreateDto> {

        SimpleDuplicateDraftLegacyService service;

        @Override
        protected SimpleDuplicateDraftLegacyService getService() {
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
