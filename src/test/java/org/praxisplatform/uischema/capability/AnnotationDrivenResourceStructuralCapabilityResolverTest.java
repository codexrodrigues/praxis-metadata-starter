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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnotationDrivenResourceStructuralCapabilityResolverTest {

    @Test
    void resolvesTheExecutableServiceBoundToTheCanonicalResourcePath() throws Exception {
        ResourceStructuralCapabilities expected = new ResourceStructuralCapabilities(
                true, false, false, false, false, false, false, false,
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
        assertTrue(resolver.resolve("//employees//").options());
        verify(handlerMapping, times(1)).getHandlerMethods();

        assertFalse(resolver.resolve("/unknown").options());
        assertFalse(resolver.resolve("unknown/").options());
        verify(handlerMapping, times(2)).getHandlerMethods();
    }

    @Test
    void deduplicatesMatchedControllerBeansBeforeGetBeanAndAvoidsInstantiatingUnrelatedLazyControllers() throws Exception {
        ResourceStructuralCapabilities expected = new ResourceStructuralCapabilities(
                true, false, false, false, false, false, false, false,
                StatsCapability.empty(), null
        );
        @SuppressWarnings("unchecked")
        BaseResourceQueryService<TestDto, Long, TestFilter> service = mock(BaseResourceQueryService.class);
        when(service.getStructuralCapabilities()).thenReturn(expected);

        try (var ctx = new org.springframework.context.support.GenericApplicationContext()) {
            var empDef = new org.springframework.beans.factory.support.RootBeanDefinition(
                    TestController.class, () -> new TestController(service));
            empDef.setLazyInit(true);
            ctx.registerBeanDefinition("employeesController", empDef);

            var unrelatedDef = new org.springframework.beans.factory.support.RootBeanDefinition(
                    UnrelatedLazyController.class, () -> new UnrelatedLazyController(service));
            unrelatedDef.setLazyInit(true);
            ctx.registerBeanDefinition("unrelatedLazyController", unrelatedDef);
            ctx.refresh();

            ApplicationContext spyCtx = org.mockito.Mockito.spy(ctx);
            Method m1 = AbstractReadOnlyResourceController.class.getMethod("getCollectionCapabilities");
            Method m2 = AbstractReadOnlyResourceController.class.getMethod("getAll");
            HandlerMethod hm1 = new HandlerMethod("employeesController", ctx.getBeanFactory(), m1);
            HandlerMethod hm2 = new HandlerMethod("employeesController", ctx.getBeanFactory(), m2);
            HandlerMethod unrelatedHm = new HandlerMethod("unrelatedLazyController", ctx.getBeanFactory(), m1);

            RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
            when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                    RequestMappingInfo.paths("/employees/capabilities").build(), hm1,
                    RequestMappingInfo.paths("/employees/all").build(), hm2,
                    RequestMappingInfo.paths("/unrelated/capabilities").build(), unrelatedHm
            ));

            ResourceStructuralCapabilityResolver resolver =
                    new AnnotationDrivenResourceStructuralCapabilityResolver(handlerMapping, spyCtx);

            assertTrue(resolver.resolve("/employees").options());
            verify(spyCtx, times(1)).getBean("employeesController");
            verify(spyCtx, org.mockito.Mockito.never()).getBean("unrelatedLazyController");
            assertFalse(ctx.getBeanFactory().containsSingleton("unrelatedLazyController"));
        }
    }

    @ApiResource(value = "/unrelated", resourceKey = "test.unrelated")
    private static class UnrelatedLazyController
            extends AbstractReadOnlyResourceController<TestDto, Long, TestFilter> {
        private final BaseResourceQueryService<TestDto, Long, TestFilter> service;

        private UnrelatedLazyController(BaseResourceQueryService<TestDto, Long, TestFilter> service) {
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
