package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResource;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnotationDrivenResourceRepresentationMaterializerTest {

    @Test
    void materializesThroughTheCanonicalControllerSelectedByResourceKey() throws Exception {
        SimpleController controller = configured(new SimpleController());
        ResourceRepresentationMaterializer materializer = materializer(Map.of(
                RequestMappingInfo.paths("/simple").build(), handler(controller)
        ));

        RestApiResource<SimpleResponseDto> resource = materializer.materialize(
                "test.simple",
                new SimpleResponseDto(7L)
        );

        assertEquals(7L, resource.getContent().getId());
        assertEquals(List.of("self", "update", "delete"), resource.getLinks().asMap().keySet().stream().toList());
    }

    @Test
    void materializesCollectionsWithoutChangingTheirOrder() throws Exception {
        SimpleController controller = configured(new SimpleController());
        RequestMappingHandlerMapping handlerMapping = handlerMapping(Map.of(
                RequestMappingInfo.paths("/simple").build(), handler(controller)
        ));
        ResourceRepresentationMaterializer materializer = materializer(handlerMapping);

        List<RestApiResource<SimpleResponseDto>> resources = materializer.materializeAll(
                "test.simple",
                List.of(new SimpleResponseDto(7L), new SimpleResponseDto(9L))
        );

        assertEquals(List.of(7L, 9L), resources.stream().map(item -> item.getContent().getId()).toList());
        verify(handlerMapping).getHandlerMethods();
    }

    @Test
    void rejectsBlankAndUnknownResourceKeys() {
        ResourceRepresentationMaterializer materializer = materializer(Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize(" ", new SimpleResponseDto(7L)));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize("test.unknown", new SimpleResponseDto(7L)));
        assertTrue(unknown.getMessage().contains("test.unknown"));
    }

    @Test
    void rejectsDuplicateCanonicalControllers() throws Exception {
        SimpleController first = configured(new SimpleController());
        DuplicateSimpleController second = configured(new DuplicateSimpleController());
        ResourceRepresentationMaterializer materializer = materializer(Map.of(
                RequestMappingInfo.paths("/simple").build(), handler(first),
                RequestMappingInfo.paths("/other-simple").build(), handler(second)
        ));

        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> materializer.materialize("test.simple", new SimpleResponseDto(7L)));
        assertTrue(duplicate.getMessage().contains("Multiple canonical resource controllers"));
    }

    @Test
    void rejectsDtosThatDoNotBelongToTheCanonicalResource() throws Exception {
        SimpleController controller = configured(new SimpleController());
        ResourceRepresentationMaterializer materializer = materializer(Map.of(
                RequestMappingInfo.paths("/simple").build(), handler(controller)
        ));

        IllegalArgumentException incompatible = assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize("test.simple", new OtherResponseDto(7L)));
        assertTrue(incompatible.getMessage().contains(OtherResponseDto.class.getName()));
        assertTrue(incompatible.getMessage().contains(SimpleResponseDto.class.getName()));
    }

    private ResourceRepresentationMaterializer materializer(Map<RequestMappingInfo, HandlerMethod> handlers) {
        return materializer(handlerMapping(handlers));
    }

    private ResourceRepresentationMaterializer materializer(RequestMappingHandlerMapping handlerMapping) {
        return new AnnotationDrivenResourceRepresentationMaterializer(handlerMapping, mock(ApplicationContext.class));
    }

    private RequestMappingHandlerMapping handlerMapping(Map<RequestMappingInfo, HandlerMethod> handlers) {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(handlers);
        return handlerMapping;
    }

    private HandlerMethod handler(Object controller) throws NoSuchMethodException {
        return new HandlerMethod(controller, controller.getClass().getMethod("fixtureEndpoint"));
    }

    private <T extends SimpleController> T configured(T controller) {
        ReflectionTestUtils.setField(controller, "environment",
                new MockEnvironment().withProperty("praxis.hateoas.enabled", "true"));
        return controller;
    }

    interface SimpleService extends BaseResourceService<
            SimpleResponseDto, Long, SimpleFilterDto, SimpleCreateDto, SimpleUpdateDto> {
    }

    static class SimpleResponseDto {
        private final Long id;

        SimpleResponseDto(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }
    }

    record OtherResponseDto(Long id) {
    }

    static class SimpleCreateDto {
    }

    static class SimpleUpdateDto {
    }

    static class SimpleFilterDto implements GenericFilterDTO {
    }

    @ApiResource(value = "/simple", resourceKey = "test.simple")
    static class SimpleController extends AbstractResourceController<
            SimpleResponseDto, Long, SimpleFilterDto, SimpleCreateDto, SimpleUpdateDto> {

        public void fixtureEndpoint() {
        }

        @Override
        protected SimpleService getService() {
            return null;
        }

        @Override
        protected Long getResponseId(SimpleResponseDto dto) {
            return dto.getId();
        }

        @Override
        protected String getBasePath() {
            return "/simple";
        }
    }

    @ApiResource(value = "/other-simple", resourceKey = "test.simple")
    static final class DuplicateSimpleController extends SimpleController {
        @Override
        protected String getBasePath() {
            return "/other-simple";
        }
    }
}
