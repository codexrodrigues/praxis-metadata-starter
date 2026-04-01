package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.springframework.hateoas.EntityModel;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractResourceQueryControllerHateoasTest {

    @Test
    void toEntityModelOmitsItemLinksWhenHateoasIsDisabled() {
        SimpleController controller = new SimpleController();
        ReflectionTestUtils.setField(controller, "environment",
                new MockEnvironment().withProperty("praxis.hateoas.enabled", "false"));

        EntityModel<SimpleResponseDto> model = controller.exposeToEntityModel(new SimpleResponseDto(7L));

        assertTrue(model.getLinks().isEmpty());
    }

    @Test
    void toEntityModelKeepsItemLinksWhenHateoasIsEnabled() {
        SimpleController controller = new SimpleController();
        ReflectionTestUtils.setField(controller, "environment",
                new MockEnvironment().withProperty("praxis.hateoas.enabled", "true"));

        EntityModel<SimpleResponseDto> model = controller.exposeToEntityModel(new SimpleResponseDto(7L));

        assertEquals(List.of("self", "update", "delete"), model.getLinks().stream()
                .map(link -> link.getRel().value())
                .toList());
    }

    @Test
    void toEntityModelAddsDiscoveryLinksWhenServicesAndItemActionsExist() {
        SimpleController controller = new SimpleController();
        ReflectionTestUtils.setField(controller, "environment",
                new MockEnvironment().withProperty("praxis.hateoas.enabled", "true"));
        ReflectionTestUtils.setField(controller, "surfaceCatalogService", mock(SurfaceCatalogService.class));
        ReflectionTestUtils.setField(controller, "capabilityService", mock(CapabilityService.class));
        ReflectionTestUtils.setField(controller, "actionCatalogService", mock(org.praxisplatform.uischema.action.ActionCatalogService.class));
        ActionDefinitionRegistry registry = mock(ActionDefinitionRegistry.class);
        ReflectionTestUtils.setField(controller, "actionDefinitionRegistry", registry);
        when(registry.findByResourceKey("test.simple")).thenReturn(List.of(
                new ActionDefinition(
                        "approve",
                        "test.simple",
                        "/simple",
                        "simple",
                        ActionScope.ITEM,
                        "Approve",
                        "",
                        null,
                        null,
                        null,
                        0,
                        null,
                        List.of(),
                        List.of(),
                        List.of()
                )
        ));

        EntityModel<SimpleResponseDto> model = controller.exposeToEntityModel(new SimpleResponseDto(7L));

        assertEquals(List.of("self", "update", "delete", "surfaces", "actions", "capabilities"), model.getLinks().stream()
                .map(link -> link.getRel().value())
                .toList());
    }

    interface SimpleService extends BaseResourceService<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {}

    static class SimpleResponseDto {
        private Long id;

        SimpleResponseDto() {
        }

        SimpleResponseDto(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }

    static class SimpleCreateDto {
    }

    static class SimpleUpdateDto {
    }

    static class SimpleFilterDTO implements GenericFilterDTO {
    }

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/simple")
    @ApiResource(value = "/simple", resourceKey = "test.simple")
    static class SimpleController extends AbstractResourceController<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {

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

        EntityModel<SimpleResponseDto> exposeToEntityModel(SimpleResponseDto dto) {
            return toEntityModel(dto);
        }
    }
}
