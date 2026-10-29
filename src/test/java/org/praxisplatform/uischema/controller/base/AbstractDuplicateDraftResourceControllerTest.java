package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.DuplicateDraftResourceService;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.praxisplatform.uischema.service.base.DuplicateDraftResourceCommandService;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.core.ResolvableType;
import org.springframework.core.GenericTypeResolver;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.lang.reflect.ParameterizedType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AbstractDuplicateDraftResourceControllerTest {

    @Test
    void ordinaryControllerDoesNotPublishDuplicateDraft() {
        SimpleDirectService service = mock(SimpleDirectService.class);
        SimpleDirectController controller = controllerWith(service);

        assertEquals(List.of("update", "delete"), controller.exposeEntityActionRels(10L));
        assertFalse(java.util.Arrays.stream(SimpleDirectController.class.getMethods())
                .anyMatch(method -> method.getName().equals("duplicateDraft")));
    }

    @Test
    void duplicateDraftControllerPublishesDuplicateDraftOnlyWhenServiceSupportsIt() {
        SimpleDuplicateDraftService unsupported = mock(SimpleDuplicateDraftService.class);
        when(unsupported.supportsDuplicateDraft()).thenReturn(false);
        SimpleDuplicateDraftController unsupportedController = duplicateDraftControllerWith(unsupported);

        SimpleDuplicateDraftService supported = mock(SimpleDuplicateDraftService.class);
        when(supported.supportsDuplicateDraft()).thenReturn(true);
        SimpleDuplicateDraftController supportedController = duplicateDraftControllerWith(supported);

        assertEquals(List.of("update", "delete"), unsupportedController.exposeEntityActionRels(10L));
        assertEquals(List.of("update", "delete", "duplicate-draft"), supportedController.exposeEntityActionRels(10L));
        assertFalse(unsupportedController.buildItemActionLinks(10L).stream()
                .anyMatch(link -> link.getRel().value().equals("duplicate-draft")));
        assertTrue(supportedController.buildItemActionLinks(10L).stream()
                .anyMatch(link -> link.getRel().value().equals("duplicate-draft")));
    }

    @Test
    void duplicateDraftDelegatesToDirectCommandPort() {
        SimpleDuplicateDraftService service = mock(SimpleDuplicateDraftService.class);
        when(service.getDatasetVersion()).thenReturn(java.util.Optional.of("dataset-1"));
        when(service.supportsDuplicateDraft()).thenReturn(true);
        when(service.duplicateDraft(7L)).thenReturn(new SimpleCreateDto("copied"));
        SimpleDuplicateDraftController controller = duplicateDraftControllerWith(service);

        var response = controller.duplicateDraft(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("dataset-1", response.getHeaders().getFirst("X-Data-Version"));
        assertEquals("copied", response.getBody().getData().getName());
    }

    @Test
    void duplicateDraftResponsePublishesRequestAndResponseSchemaLinks() {
        SimpleDuplicateDraftService service = mock(SimpleDuplicateDraftService.class);
        when(service.supportsDuplicateDraft()).thenReturn(true);
        when(service.duplicateDraft(7L)).thenReturn(new SimpleCreateDto("copied"));
        SimpleDuplicateDraftController controller = duplicateDraftControllerWith(service);

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
        SimpleDuplicateDraftService service = mock(SimpleDuplicateDraftService.class);
        when(service.supportsDuplicateDraft()).thenReturn(false);
        SimpleDuplicateDraftController controller = duplicateDraftControllerWith(service);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.duplicateDraft(7L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(service, never()).duplicateDraft(any());
    }

    @Test
    void commandPortDefaultsToSupportedWithoutJpaInheritance() {
        DuplicateDraftResourceCommandService<Long, SimpleCreateDto> command =
                sourceId -> new SimpleCreateDto("copy-" + sourceId);

        assertTrue(command.supportsDuplicateDraft());
        assertEquals("copy-7", command.duplicateDraft(7L).getName());
        assertFalse(AbstractBaseResourceService.class.isAssignableFrom(SimpleDuplicateDraftService.class));
    }

    @Test
    void duplicateDraftContractAlsoComposesWithJpaBase() {
        JpaDuplicateDraftService service = mock(JpaDuplicateDraftService.class, CALLS_REAL_METHODS);

        assertTrue(service.supportsDuplicateDraft());
        assertEquals("copy-7", service.duplicateDraft(7L).getName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inheritedResponseSchemaResolvesDraftInsteadOfPersistedResponse(boolean openApi31) throws Exception {
        var method = SimpleDuplicateDraftController.class.getMethod("duplicateDraft", Object.class);
        var returnType = ResolvableType.forMethodReturnType(method, SimpleDuplicateDraftController.class);

        assertEquals(SimpleCreateDto.class, returnType.getGeneric(0, 0).resolve());
        assertEquals(SimpleResponseDto.class, ResolvableType.forClass(SimpleDuplicateDraftController.class)
                .as(AbstractResourceController.class).getGeneric(0).resolve());

        var resolvedReturn = (ParameterizedType) GenericTypeResolver.resolveType(
                method.getGenericReturnType(), SimpleDuplicateDraftController.class);
        var envelopeType = resolvedReturn.getActualTypeArguments()[0];
        var schema = new ModelConverters(openApi31).resolveAsResolvedSchema(
                new AnnotatedType(envelopeType).resolveAsRef(false));
        Schema<?> envelopeSchema = schema.schema;
        var dataReference = envelopeSchema.getProperties().get("data").get$ref();
        var draftSchema = schema.referencedSchemas.get(dataReference.substring("#/components/schemas/".length()));
        assertTrue(draftSchema.getProperties().containsKey("name"));
        assertFalse(draftSchema.getProperties().containsKey("id"));
    }

    @Test
    void httpEndpointPreservesEnvelopeDraftPayloadAndLinksWithoutCrudWrites() throws Exception {
        SimpleDuplicateDraftService service = mock(SimpleDuplicateDraftService.class);
        when(service.supportsDuplicateDraft()).thenReturn(true);
        when(service.duplicateDraft(7L)).thenReturn(new SimpleCreateDto("copied"));

        standaloneSetup(duplicateDraftControllerWith(service)).build()
                .perform(post("/duplicate-draft-simple/7/duplicate-draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("copied"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$._links.self.href").value("/duplicate-draft-simple/7"))
                .andExpect(jsonPath("$._links.create.href").value("/duplicate-draft-simple"))
                .andExpect(jsonPath("$.links").doesNotExist());

        verify(service).duplicateDraft(7L);
        verify(service, never()).create(any());
        verify(service, never()).update(any(), any());
        verify(service, never()).deleteById(any());
    }

    private static SimpleDirectController controllerWith(SimpleDirectService service) {
        SimpleDirectController controller = new SimpleDirectController();
        ReflectionTestUtils.setField(controller, "service", service);
        return controller;
    }

    private static SimpleDuplicateDraftController duplicateDraftControllerWith(SimpleDuplicateDraftService service) {
        SimpleDuplicateDraftController controller = new SimpleDuplicateDraftController();
        ReflectionTestUtils.setField(controller, "service", service);
        // Controller unit fixture supplies affirmative decisions; the integration suite uses
        // the real operation/action composition and separately proves missing/null denial.
        var capabilities = mock(org.praxisplatform.uischema.capability.CapabilityService.class);
        when(capabilities.collectionOperationAvailability(any(), any(), any()))
                .thenReturn(org.praxisplatform.uischema.capability.AvailabilityDecision.allowAll());
        when(capabilities.itemOperationAvailability(any(), any(), any(), any()))
                .thenReturn(org.praxisplatform.uischema.capability.AvailabilityDecision.allowAll());
        ReflectionTestUtils.setField(controller, "capabilityService", capabilities);
        return controller;
    }

    interface SimpleDirectService extends BaseResourceService<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {}

    interface SimpleDuplicateDraftService extends DuplicateDraftResourceService<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto,
            SimpleCreateDto> {}

    // Compile-time JPA composition proof; this fixture does not exercise persistence.
    abstract static class JpaDuplicateDraftService extends AbstractBaseResourceService<
            SimpleResponseDto, SimpleResponseDto, Long, SimpleFilterDTO, SimpleCreateDto, SimpleUpdateDto>
            implements SimpleDuplicateDraftService {
        JpaDuplicateDraftService(BaseCrudRepository<SimpleResponseDto, Long> repository) {
            super(repository, SimpleResponseDto.class);
        }

        @Override
        public SimpleCreateDto duplicateDraft(Long sourceId) {
            return new SimpleCreateDto("copy-" + sourceId);
        }
    }

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
    @org.springframework.web.bind.annotation.RequestMapping("/direct-simple")
    static class SimpleDirectController extends AbstractResourceController<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {

        SimpleDirectService service;

        @Override
        protected SimpleDirectService getService() {
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
            return "/direct-simple";
        }

        List<String> exposeEntityActionRels(Long id) {
            return buildEntityActionLinks(id).stream().map(Link::getRel).map(Object::toString).toList();
        }
    }

    @org.springframework.web.bind.annotation.RestController
    @org.praxisplatform.uischema.annotation.ApiResource(value = "/duplicate-draft-simple", resourceKey = "test.duplicate-draft-simple")
    static class SimpleDuplicateDraftController extends AbstractDuplicateDraftResourceController<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto,
            SimpleCreateDto> {

        SimpleDuplicateDraftService service;

        @Override
        protected SimpleDuplicateDraftService getService() {
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
            return "/duplicate-draft-simple";
        }

        List<String> exposeEntityActionRels(Long id) {
            return buildEntityActionLinks(id).stream().map(Link::getRel).map(Object::toString).toList();
        }
    }
}
