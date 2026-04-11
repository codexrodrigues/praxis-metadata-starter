package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicSwaggerConfigTest {

    @Test
    void createDynamicGroupsRegistersIndividualAndAggregatedGroupsForCanonicalResourceController() throws Exception {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        DynamicSwaggerConfig config = new DynamicSwaggerConfig();

        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/api/test/products/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(new ApiResourceController(),
                        AbstractResourceQueryController.class.getMethod("getAll"))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);

        ReflectionTestUtils.setField(config, "beanFactory", beanFactory);
        ReflectionTestUtils.setField(config, "handlerMapping", handlerMapping);
        ReflectionTestUtils.setField(config, "apiResourceValidationMode", "IGNORE");
        ReflectionTestUtils.setField(config, "applicationContext", null);

        config.createDynamicGroups();

        assertTrue(beanFactory.containsSingleton("api_test_products_ApiGroup"));
        assertTrue(beanFactory.containsSingleton("catalog_ApiGroup"));

        GroupedOpenApi individual = (GroupedOpenApi) beanFactory.getSingleton("api_test_products_ApiGroup");
        GroupedOpenApi aggregated = (GroupedOpenApi) beanFactory.getSingleton("catalog_ApiGroup");

        assertEquals("api-test-products", individual.getGroup());
        assertEquals("/api/test/products/**", individual.getPathsToMatch().getFirst());
        assertEquals("catalog", aggregated.getGroup());
        assertEquals("/api/test/products/**", aggregated.getPathsToMatch().getFirst());
    }

    @Test
    void createDynamicGroupsFallsBackToRequestMappingBasePathForCanonicalController() throws Exception {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        DynamicSwaggerConfig config = new DynamicSwaggerConfig();

        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/api/fallback/reports/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(new RequestMappingOnlyController(),
                        AbstractResourceQueryController.class.getMethod("getAll"))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);

        ReflectionTestUtils.setField(config, "beanFactory", beanFactory);
        ReflectionTestUtils.setField(config, "handlerMapping", handlerMapping);
        ReflectionTestUtils.setField(config, "apiResourceValidationMode", "IGNORE");
        ReflectionTestUtils.setField(config, "applicationContext", null);

        config.createDynamicGroups();

        assertTrue(beanFactory.containsSingleton("api_fallback_reports_ApiGroup"));
        GroupedOpenApi group = (GroupedOpenApi) beanFactory.getSingleton("api_fallback_reports_ApiGroup");
        assertEquals("api-fallback-reports", group.getGroup());
        assertEquals("/api/fallback/reports/**", group.getPathsToMatch().getFirst());
    }

    @Test
    void createDynamicGroupsRegistersAggregatedGroupsWithExplicitPathsInsteadOfRootWildcard() throws Exception {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        DynamicSwaggerConfig config = new DynamicSwaggerConfig();

        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        handlerMethods.put(
                RequestMappingInfo.paths("/employees/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(new HumanResourcesEmployeeController(),
                        AbstractResourceQueryController.class.getMethod("getAll"))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/departments/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(new HumanResourcesDepartmentController(),
                        AbstractResourceQueryController.class.getMethod("getAll"))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/payroll-view/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(new HumanResourcesPayrollController(),
                        AbstractResourceQueryController.class.getMethod("getAll"))
        );
        handlerMethods.put(
                RequestMappingInfo.paths("/external-employees/all").methods(RequestMethod.GET).build(),
                new HandlerMethod(new ExternalCatalogController(),
                        AbstractResourceQueryController.class.getMethod("getAll"))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(handlerMethods);

        ReflectionTestUtils.setField(config, "beanFactory", beanFactory);
        ReflectionTestUtils.setField(config, "handlerMapping", handlerMapping);
        ReflectionTestUtils.setField(config, "apiResourceValidationMode", "IGNORE");
        ReflectionTestUtils.setField(config, "applicationContext", null);

        config.createDynamicGroups();

        assertTrue(beanFactory.containsSingleton("human_resources_ApiGroup"));
        GroupedOpenApi aggregated = (GroupedOpenApi) beanFactory.getSingleton("human_resources_ApiGroup");

        assertEquals("human-resources", aggregated.getGroup());
        assertEquals(List.of("/employees/**", "/departments/**", "/payroll-view/**"), aggregated.getPathsToMatch());
        assertFalse(aggregated.getPathsToMatch().contains("//**"));
        assertFalse(aggregated.getPathsToMatch().contains("/external-employees/**"));
    }

    @ApiResource(value = "/api/test/products", resourceKey = "test.products")
    @ApiGroup("catalog")
    @Profile("dynamic-swagger-config-test")
    static final class ApiResourceController extends BaseTestController {
    }

    @RequestMapping("/api/fallback/reports")
    @Profile("dynamic-swagger-config-test")
    static final class RequestMappingOnlyController extends BaseTestController {
    }

    @ApiResource(value = "/employees", resourceKey = "human-resources.employees")
    @ApiGroup("human-resources")
    @Profile("dynamic-swagger-config-test")
    static final class HumanResourcesEmployeeController extends BaseTestController {
    }

    @ApiResource(value = "/departments", resourceKey = "human-resources.departments")
    @ApiGroup("human-resources")
    @Profile("dynamic-swagger-config-test")
    static final class HumanResourcesDepartmentController extends BaseTestController {
    }

    @ApiResource(value = "/payroll-view", resourceKey = "human-resources.payroll-view")
    @ApiGroup("human-resources")
    @Profile("dynamic-swagger-config-test")
    static final class HumanResourcesPayrollController extends BaseTestController {
    }

    @ApiResource(value = "/external-employees", resourceKey = "external.employees")
    @ApiGroup("external")
    @Profile("dynamic-swagger-config-test")
    static final class ExternalCatalogController extends BaseTestController {
    }

    abstract static class BaseTestController extends AbstractResourceQueryController<TestDto, Long, TestFilterDTO> {

        BaseTestController() {
            ReflectionTestUtils.setField(this, "environment", new MockEnvironment());
        }

        @Override
        protected BaseResourceQueryService<TestDto, Long, TestFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(TestDto dto) {
            return dto.id();
        }
    }

    record TestDto(Long id) {
    }

    static final class TestFilterDTO implements GenericFilterDTO {
    }
}
