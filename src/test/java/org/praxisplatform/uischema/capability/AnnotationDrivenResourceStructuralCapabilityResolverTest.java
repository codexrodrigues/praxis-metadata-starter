package org.praxisplatform.uischema.capability;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.StatsCapability;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnnotationDrivenResourceStructuralCapabilityResolverTest {

    @Test
    void resolvesTheExecutableServiceBoundToTheCanonicalResourcePath() throws Exception {
        ResourceStructuralCapabilities expected = new ResourceStructuralCapabilities(
                true, false, false, false, false, false, false,
                StatsCapability.empty(), null
        );
        @SuppressWarnings("unchecked")
        BaseResourceQueryService<TestDto, Long, TestFilter> service = mock(BaseResourceQueryService.class);
        when(service.getStructuralCapabilities()).thenReturn(expected);
        TestController controller = new TestController(service);
        Method capabilitiesMethod = AbstractReadOnlyResourceController.class.getMethod("getCollectionCapabilities");
        HandlerMethod handlerMethod = new HandlerMethod(controller, capabilitiesMethod);
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/employees/capabilities").build(),
                handlerMethod
        ));

        ResourceStructuralCapabilityResolver resolver =
                new AnnotationDrivenResourceStructuralCapabilityResolver(
                        handlerMapping,
                        mock(ApplicationContext.class)
                );

        assertTrue(resolver.resolve("employees/").options());
        assertFalse(resolver.resolve("/employees").optionSources());
        assertFalse(resolver.resolve("/unknown").options());
    }

    @ApiResource(value = "/employees", resourceKey = "test.employees")
    private static final class TestController
            extends AbstractReadOnlyResourceController<TestDto, Long, TestFilter> {

        private final BaseResourceQueryService<TestDto, Long, TestFilter> service;

        private TestController(BaseResourceQueryService<TestDto, Long, TestFilter> service) {
            this.service = service;
        }

        @Override
        protected BaseResourceQueryService<TestDto, Long, TestFilter> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(TestDto dto) {
            return dto.id();
        }
    }

    private record TestDto(Long id) {
    }

    private static final class TestFilter implements GenericFilterDTO {
    }
}
