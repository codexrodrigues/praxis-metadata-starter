package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractResourceQueryControllerBasePathDetectionTest {

    @Test
    void initializeBasePathPrefersApiResourceAnnotation() {
        ApiResourceController controller = new ApiResourceController();

        controller.initialize();

        assertEquals("/api/example/employees", controller.exposeBasePath());
    }

    @Test
    void initializeBasePathFallsBackToRequestMapping() {
        RequestMappingController controller = new RequestMappingController();

        controller.initialize();

        assertEquals("/fallback/reports", controller.exposeBasePath());
    }

    abstract static class BaseTestController extends AbstractResourceQueryController<TestDto, Long, TestFilterDTO> {

        void initialize() {
            initializeBasePath();
        }

        String exposeBasePath() {
            return getBasePath();
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

    @ApiResource(value = "/api/example/employees", resourceKey = "example.employees")
    static final class ApiResourceController extends BaseTestController {
    }

    @RequestMapping("/fallback/reports")
    static final class RequestMappingController extends BaseTestController {
    }

    record TestDto(Long id) {
    }

    static final class TestFilterDTO implements GenericFilterDTO {
    }
}
