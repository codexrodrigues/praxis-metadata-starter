package org.praxisplatform.uischema.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.FormLayout;
import org.praxisplatform.uischema.annotation.FormLayoutMode;
import org.praxisplatform.uischema.annotation.FormLayoutSection;
import org.praxisplatform.uischema.annotation.FormLayoutStep;
import org.praxisplatform.uischema.annotation.FormLayoutStepperReadMode;
import org.praxisplatform.uischema.annotation.FormLayoutTabsAppearance;
import org.praxisplatform.uischema.annotation.UiBoolean;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.controller.docs.ApiDocsController;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.praxisplatform.uischema.schema.FormLayoutMetadataResolver;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Controller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class FormLayoutMetadataPlatformTest {

    static class PlainCommandDTO {
        @UISchema(label = "Nome", group = "identificacao")
        public String name;
    }

    @FormLayout(FormLayoutMode.STEPPER)
    static class CompactStepperDTO {
        @UISchema(label = "Nome", group = "identificacao")
        public String name;
        @UISchema(label = "E-mail", group = "contato")
        public String email;
    }

    @FormLayout(FormLayoutMode.VERTICAL)
    static class VerticalAliasDTO {
        @UISchema(label = "Código", group = "geral")
        public String code;
    }

    @FormLayout(
            mode = FormLayoutMode.STEPPER,
            defaultTabId = "sec-contato",
            tabsAppearance = FormLayoutTabsAppearance.UNDERLINE,
            accordionMulti = UiBoolean.TRUE,
            hideSectionHeaderInTabs = UiBoolean.TRUE,
            hideSectionHeaderInStepper = UiBoolean.FALSE,
            stepperInReadMode = FormLayoutStepperReadMode.PRESENTATION,
            stepperReviewStep = UiBoolean.TRUE,
            draftPersistence = UiBoolean.TRUE,
            sections = {
                    @FormLayoutSection(
                            id = "sec-remun",
                            title = "Remuneração",
                            subtitle = "Salário base e benefícios",
                            icon = "payments",
                            order = 3,
                            optional = UiBoolean.TRUE,
                            groups = {"remuneracao"}
                    ),
                    @FormLayoutSection(
                            id = "sec-id",
                            title = "Identificação",
                            subtitle = "Dados pessoais",
                            icon = "badge",
                            order = 1,
                            groups = {"identificacao"}
                    ),
                    @FormLayoutSection(
                            id = "sec-contato",
                            title = "Contato",
                            icon = "mail",
                            order = 2,
                            groups = {"contato"}
                    )
            },
            steps = {
                    @FormLayoutStep(
                            id = "step-cadastro-base",
                            label = "Cadastro Base",
                            description = "Identificação e canais corporativos",
                            icon = "person",
                            sectionIds = {"sec-id", "sec-contato"}
                    ),
                    @FormLayoutStep(
                            id = "step-pacote-opcional",
                            label = "Pacote Salarial",
                            icon = "payments",
                            sectionIds = {"sec-remun"},
                            optional = UiBoolean.TRUE
                    )
            }
    )
    static class FullPhase2EmployeeDTO {
        @UISchema(label = "Nome completo", group = "identificacao", order = 1)
        public String nome;
        @UISchema(label = "E-mail corporativo", group = "contato", order = 1)
        public String email;
        @UISchema(label = "Salário base", group = "remuneracao", order = 1)
        public BigDecimal salario;
    }

    @FormLayout(
            mode = FormLayoutMode.STEPPER,
            sections = {
                    @FormLayoutSection(id = "sec-a", title = "Seção A", order = 1, groups = {"grupoFantasma"}),
                    @FormLayoutSection(id = "sec-b", title = "Seção B", order = 2, groups = {"contato"})
            }
    )
    static class UnknownGroupFormLayoutDTO {
        @UISchema(label = "E-mail", group = "contato")
        public String email;
    }

    @FormLayout(
            mode = FormLayoutMode.TABS,
            sections = {
                    @FormLayoutSection(id = "dup", title = "Primeira"),
                    @FormLayoutSection(id = "dup", title = "Segunda")
            }
    )
    static class DuplicateSectionIdDTO {
        public String field;
    }

    @FormLayout(
            mode = FormLayoutMode.TABS,
            sections = {
                    @FormLayoutSection(id = " ", title = "Sem ID")
            }
    )
    static class BlankSectionIdDTO {
        public String field;
    }

    @FormLayout(
            mode = FormLayoutMode.TABS,
            defaultTabId = "aba-inexistente",
            sections = {
                    @FormLayoutSection(id = "sec-1", title = "Um")
            }
    )
    static class MismatchedDefaultTabDTO {
        public String field;
    }

    @FormLayout(
            mode = FormLayoutMode.STEPPER,
            sections = {
                    @FormLayoutSection(id = "sec-1", title = "Um")
            },
            steps = {
                    @FormLayoutStep(id = "step-1", label = "Etapa 1", sectionIds = {"sec-inexistente"})
            }
    )
    static class UnknownStepSectionIdDTO {
        public String field;
    }

    @FormLayout(
            mode = FormLayoutMode.STEPPER,
            sections = {
                    @FormLayoutSection(id = "sec-1", title = "Um")
            },
            steps = {
                    @FormLayoutStep(id = "step-dup", label = "Etapa 1", sectionIds = {"sec-1"}),
                    @FormLayoutStep(id = "step-dup", label = "Etapa 2", sectionIds = {"sec-1"})
            }
    )
    static class DuplicateStepIdDTO {
        public String field;
    }

    @FormLayout(
            mode = FormLayoutMode.STEPPER,
            sections = {
                    @FormLayoutSection(id = "sec-1", title = "Um", groups = {"identificacao"}),
                    @FormLayoutSection(id = "sec-2", title = "Dois", groups = {"contato"})
            }
    )
    static class EmployeeFilterDTO implements GenericFilterDTO {
        @UISchema(label = "Nome", group = "identificacao")
        public String nome;
        @UISchema(label = "E-mail", group = "contato")
        public String email;
    }

    @RestController
    static class EmployeeTestController {
        @PostMapping("/api/test/employees")
        public ResponseEntity<RestApiResponse<PlainCommandDTO>> create(@RequestBody FullPhase2EmployeeDTO dto) {
            return ResponseEntity.ok().build();
        }

        @PutMapping("/api/test/employees/{id}")
        @FormLayout(
                mode = FormLayoutMode.ACCORDION,
                accordionMulti = UiBoolean.TRUE,
                sections = {
                        @FormLayoutSection(id = "sec-id", title = "Identificação", order = 1, groups = {"identificacao"}),
                        @FormLayoutSection(id = "sec-contato", title = "Contato", order = 2, groups = {"contato"})
                }
        )
        public ResponseEntity<RestApiResponse<FullPhase2EmployeeDTO>> update(
                @PathVariable Long id,
                @RequestBody FullPhase2EmployeeDTO dto
        ) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/api/test/employees/{id}")
        public ResponseEntity<RestApiResponse<FullPhase2EmployeeDTO>> findById(@PathVariable Long id) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/api/test/employees")
        public ResponseEntity<RestApiResponse<List<FullPhase2EmployeeDTO>>> listAll() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/test/employees/filter")
        public ResponseEntity<RestApiResponse<List<FullPhase2EmployeeDTO>>> filter(@RequestBody EmployeeFilterDTO filter) {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/test/employees/stats/group-by")
        public ResponseEntity<RestApiResponse<Map<String, Object>>> statsGroupBy(@RequestBody FullPhase2EmployeeDTO statsReq) {
            return ResponseEntity.ok().build();
        }
    }

    static class NestedAddressDTO {
        @UISchema(label = "CEP", group = "endereco")
        public String cep;
        @UISchema(label = "Cidade", group = "endereco")
        public String cidade;
    }

    @FormLayout(
            mode = FormLayoutMode.STEPPER,
            sections = {
                    @FormLayoutSection(id = "sec-id", title = "Identificação", order = 1, groups = {"identificacao"}),
                    @FormLayoutSection(id = "sec-end", title = "Endereço", order = 2, groups = {"endereco"})
            }
    )
    static class NestedGroupEmployeeDTO {
        @UISchema(label = "Nome", group = "identificacao")
        public String nome;
        public NestedAddressDTO endereco;
    }

    @Controller
    @RequestMapping
    public interface ProxiedEmployeeContract {
        @PutMapping("/api/test/proxied/{id}")
        ResponseEntity<RestApiResponse<FullPhase2EmployeeDTO>> updateProxied(
                @PathVariable Long id,
                @RequestBody CompactStepperDTO dto
        );

        @PatchMapping("/api/test/proxied/{id}/contact")
        ResponseEntity<RestApiResponse<PlainCommandDTO>> patchDynamicMap(
                @PathVariable Long id,
                @RequestBody Map<String, Object> patchPayload
        );
    }

    @RestController
    static class ProxiedEmployeeController implements ProxiedEmployeeContract {
        @Override
        @PutMapping("/api/test/proxied/{id}")
        @FormLayout(mode = FormLayoutMode.TABS, tabsAppearance = FormLayoutTabsAppearance.BUTTONS)
        public ResponseEntity<RestApiResponse<FullPhase2EmployeeDTO>> updateProxied(
                @PathVariable Long id,
                @RequestBody CompactStepperDTO dto
        ) {
            return ResponseEntity.ok().build();
        }

        @Override
        @PatchMapping("/api/test/proxied/{id}/contact")
        public ResponseEntity<RestApiResponse<PlainCommandDTO>> patchDynamicMap(
                @PathVariable Long id,
                @RequestBody Map<String, Object> patchPayload
        ) {
            return ResponseEntity.ok().build();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsOptInFormLayoutOnDtoSchemasWithoutPollutingPlainDtos() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(mapper));

        ResolvedSchema plain = converters.resolveAsResolvedSchema(
                new AnnotatedType(PlainCommandDTO.class).resolveAsRef(false)
        );
        Map<String, Object> plainUi = plain.schema.getExtensions() == null
                ? null
                : (Map<String, Object>) plain.schema.getExtensions().get("x-ui");
        assertTrue(plainUi == null || !plainUi.containsKey("formLayout"), "Plain DTO must never emit x-ui.formLayout");

        ResolvedSchema compact = converters.resolveAsResolvedSchema(
                new AnnotatedType(CompactStepperDTO.class).resolveAsRef(false)
        );
        Map<String, Object> compactLayout = (Map<String, Object>) ((Map<String, Object>) compact.schema
                .getExtensions().get("x-ui")).get("formLayout");
        assertEquals(Map.of("mode", "stepper"), compactLayout);

        ResolvedSchema verticalAlias = converters.resolveAsResolvedSchema(
                new AnnotatedType(VerticalAliasDTO.class).resolveAsRef(false)
        );
        Map<String, Object> verticalLayout = (Map<String, Object>) ((Map<String, Object>) verticalAlias.schema
                .getExtensions().get("x-ui")).get("formLayout");
        assertEquals(Map.of("mode", "flat"), verticalLayout);

        ResolvedSchema full = converters.resolveAsResolvedSchema(
                new AnnotatedType(FullPhase2EmployeeDTO.class).resolveAsRef(false)
        );
        Map<String, Object> fullLayout = (Map<String, Object>) ((Map<String, Object>) full.schema
                .getExtensions().get("x-ui")).get("formLayout");
        assertEquals("stepper", fullLayout.get("mode"));
        assertEquals("sec-contato", fullLayout.get("defaultTabId"));
        assertEquals("underline", fullLayout.get("tabsAppearance"));
        assertEquals(Boolean.TRUE, fullLayout.get("accordionMulti"));
        assertEquals(Boolean.TRUE, fullLayout.get("hideSectionHeaderInTabs"));
        assertEquals(Boolean.FALSE, fullLayout.get("hideSectionHeaderInStepper"));
        assertEquals("presentation", fullLayout.get("stepperInReadMode"));
        assertEquals(Boolean.TRUE, fullLayout.get("stepperReviewStep"));

        List<Map<String, Object>> sections = (List<Map<String, Object>>) fullLayout.get("sections");
        assertEquals(List.of("sec-id", "sec-contato", "sec-remun"), sections.stream().map(s -> s.get("id")).toList());
        assertEquals(List.of("identificacao"), sections.get(0).get("groups"));
        assertEquals("Dados pessoais", sections.get(0).get("subtitle"));
        assertEquals("badge", sections.get(0).get("icon"));
        assertFalse(sections.get(0).containsKey("optional"));
        assertFalse(sections.get(1).containsKey("subtitle"));
        assertEquals(Boolean.TRUE, sections.get(2).get("optional"));

        List<Map<String, Object>> steps = (List<Map<String, Object>>) fullLayout.get("steps");
        assertEquals(2, steps.size());
        assertEquals("step-cadastro-base", steps.get(0).get("id"));
        assertEquals("Cadastro Base", steps.get(0).get("label"));
        assertEquals("Identificação e canais corporativos", steps.get(0).get("description"));
        assertEquals("person", steps.get(0).get("icon"));
        assertEquals(List.of("sec-id", "sec-contato"), steps.get(0).get("sectionIds"));
        assertFalse(steps.get(0).containsKey("optional"));
        assertEquals("step-pacote-opcional", steps.get(1).get("id"));
        assertEquals(List.of("sec-remun"), steps.get(1).get("sectionIds"));
        assertEquals(Boolean.TRUE, steps.get(1).get("optional"));

        PraxisExpressionStaticValidator validator = new PraxisExpressionStaticValidator();
        assertTrue(validator.validateDtoClass(FullPhase2EmployeeDTO.class, true).isEmpty());
        assertTrue(validator.validateDtoClass(CompactStepperDTO.class, true).isEmpty());
    }

    @Test
    void validatesUnknownGroupsDuplicateSectionIdsBlankSectionsAndDefaultTabIdStrictly() {
        PraxisExpressionStaticValidator validator = new PraxisExpressionStaticValidator();
        InvalidDomainExpressionException groupErr = assertThrows(
                InvalidDomainExpressionException.class,
                () -> validator.validateDtoClass(UnknownGroupFormLayoutDTO.class, true)
        );
        assertTrue(groupErr.getMessage().contains("grupoFantasma"));

        IllegalArgumentException dupErr = assertThrows(
                IllegalArgumentException.class,
                () -> FormLayoutMetadataResolver.resolve(DuplicateSectionIdDTO.class)
        );
        assertTrue(dupErr.getMessage().contains("duplicate id"));

        assertThrows(
                IllegalArgumentException.class,
                () -> FormLayoutMetadataResolver.resolve(BlankSectionIdDTO.class)
        );

        IllegalArgumentException defaultTabErr = assertThrows(
                IllegalArgumentException.class,
                () -> FormLayoutMetadataResolver.resolve(MismatchedDefaultTabDTO.class)
        );
        assertTrue(defaultTabErr.getMessage().contains("defaultTabId"));

        IllegalArgumentException unknownStepSecErr = assertThrows(
                IllegalArgumentException.class,
                () -> FormLayoutMetadataResolver.resolve(UnknownStepSectionIdDTO.class)
        );
        assertTrue(unknownStepSecErr.getMessage().contains("sec-inexistente"));

        IllegalArgumentException dupStepErr = assertThrows(
                IllegalArgumentException.class,
                () -> FormLayoutMetadataResolver.resolve(DuplicateStepIdDTO.class)
        );
        assertTrue(dupStepErr.getMessage().contains("duplicate id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filteredSchemasHonorsMethodPrecedenceStripsFilterAndCollectionAndTracksHashChanges() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OpenApiDocsSupport docsSupport = new OpenApiDocsSupport();
        Map<String, JsonNode> docs = new ConcurrentHashMap<>();
        SchemaCanonicalizer canonicalizer = new SchemaCanonicalizer();
        OpenApiDocumentService docService = new OpenApiDocumentService() {
            @Override
            public String resolveGroupFromPath(String path) {
                return docsSupport.resolveGroupFromPath(path);
            }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                return docs.get(groupName);
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return SchemaHashUtil.sha256Hex(canonicalizer.canonicalize(payloadSupplier.get()));
            }

            @Override
            public void clearCaches() {
                docs.clear();
            }
        };

        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(mapper));
        ObjectNode plainSchema = toObjectNode(converters.resolveAsResolvedSchema(
                new AnnotatedType(PlainCommandDTO.class).resolveAsRef(false)).schema);
        ObjectNode fullSchema = toObjectNode(converters.resolveAsResolvedSchema(
                new AnnotatedType(FullPhase2EmployeeDTO.class).resolveAsRef(false)).schema);
        ObjectNode filterSchema = toObjectNode(converters.resolveAsResolvedSchema(
                new AnnotatedType(EmployeeFilterDTO.class).resolveAsRef(false)).schema);

        StaticApplicationContext appCtx = new StaticApplicationContext();
        appCtx.registerSingleton("employeeTestController", EmployeeTestController.class);
        appCtx.refresh();
        RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
        handlerMapping.setApplicationContext(appCtx);
        handlerMapping.afterPropertiesSet();

        ApiResourceIdentityResolver identityResolver = new ApiResourceIdentityResolver(handlerMapping);

        String group = docsSupport.resolveGroupFromPath("/api/test/employees");
        docs.put(group, buildEmployeeOpenApiDoc(mapper, plainSchema, fullSchema, filterSchema));

        ApiDocsController controller = new ApiDocsController();
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "openApiDocsSupport", docsSupport);
        ReflectionTestUtils.setField(controller, "openApiDocumentService", docService);
        ReflectionTestUtils.setField(controller, "canonicalOperationResolver", new OpenApiCanonicalOperationResolver(docService, handlerMapping));
        ReflectionTestUtils.setField(controller, "schemaReferenceResolver", new FilteredSchemaReferenceResolver());
        ReflectionTestUtils.setField(controller, "canonicalCapabilityResolver", new OpenApiCanonicalCapabilityResolver(docService));
        ReflectionTestUtils.setField(controller, "apiResourceIdentityResolver", identityResolver);

        // 1. POST command request receives DTO @FormLayout
        ResponseEntity<Map<String, Object>> postRequest = controller.getFilteredSchema(
                "/api/test/employees", "post", true, "request", null, null, Locale.ROOT
        );
        Map<String, Object> postReqUi = (Map<String, Object>) postRequest.getBody().get("x-ui");
        Map<String, Object> postReqLayout = (Map<String, Object>) postReqUi.get("formLayout");
        assertNotNull(postReqLayout);
        assertEquals("stepper", postReqLayout.get("mode"));
        assertEquals(Boolean.FALSE, postReqLayout.get("hideSectionHeaderInStepper"));
        String postHashInitial = postRequest.getHeaders().getFirst("X-Schema-Hash");
        assertNotNull(postHashInitial);

        // 2. PUT command request applies controller method @FormLayout override over DTO @FormLayout
        ResponseEntity<Map<String, Object>> putRequest = controller.getFilteredSchema(
                "/api/test/employees/{id}", "put", true, "request", null, null, Locale.ROOT
        );
        Map<String, Object> putReqLayout = (Map<String, Object>) ((Map<String, Object>) putRequest.getBody().get("x-ui"))
                .get("formLayout");
        assertEquals("accordion", putReqLayout.get("mode"));
        assertEquals(Boolean.TRUE, putReqLayout.get("accordionMulti"));
        List<Map<String, Object>> putSections = (List<Map<String, Object>>) putReqLayout.get("sections");
        assertEquals(2, putSections.size());

        // 3. PUT response stays independent from method @FormLayout override (uses DTO response @FormLayout)
        ResponseEntity<Map<String, Object>> putResponse = controller.getFilteredSchema(
                "/api/test/employees/{id}", "put", true, "response", null, null, Locale.ROOT
        );
        Map<String, Object> putResLayout = (Map<String, Object>) ((Map<String, Object>) putResponse.getBody().get("x-ui"))
                .get("formLayout");
        assertEquals("stepper", putResLayout.get("mode"));

        // 4. Single GET /{id} detail response preserves DTO @FormLayout
        ResponseEntity<Map<String, Object>> getDetail = controller.getFilteredSchema(
                "/api/test/employees/{id}", "get", true, "response", null, null, Locale.ROOT
        );
        Map<String, Object> detailLayout = (Map<String, Object>) ((Map<String, Object>) getDetail.getBody().get("x-ui"))
                .get("formLayout");
        assertEquals("stepper", detailLayout.get("mode"));

        // 5. Filter request and collection responses NEVER emit x-ui.formLayout even when annotated
        ResponseEntity<Map<String, Object>> filterRequest = controller.getFilteredSchema(
                "/api/test/employees/filter", "post", true, "request", null, null, Locale.ROOT
        );
        assertFalse(((Map<String, Object>) filterRequest.getBody().get("x-ui")).containsKey("formLayout"),
                "Filter request schema must strip x-ui.formLayout");

        ResponseEntity<Map<String, Object>> filterResponse = controller.getFilteredSchema(
                "/api/test/employees/filter", "post", true, "response", null, null, Locale.ROOT
        );
        assertFalse(((Map<String, Object>) filterResponse.getBody().get("x-ui")).containsKey("formLayout"),
                "Collection filter response must strip x-ui.formLayout");

        ResponseEntity<Map<String, Object>> listResponse = controller.getFilteredSchema(
                "/api/test/employees", "get", true, "response", null, null, Locale.ROOT
        );
        assertFalse(((Map<String, Object>) listResponse.getBody().get("x-ui")).containsKey("formLayout"),
                "Collection GET response must strip x-ui.formLayout");

        ResponseEntity<Map<String, Object>> statsRequest = controller.getFilteredSchema(
                "/api/test/employees/stats/group-by", "post", true, "request", null, null, Locale.ROOT
        );
        assertFalse(((Map<String, Object>) statsRequest.getBody().get("x-ui")).containsKey("formLayout"),
                "Analytical /stats/group-by request must strip x-ui.formLayout");

        // 6. X-Schema-Hash changes when formLayout mutates
        ObjectNode mutatedFull = fullSchema.deepCopy();
        ((ObjectNode) mutatedFull.path("x-ui").path("formLayout")).put("hideSectionHeaderInStepper", true);
        docs.put(group, buildEmployeeOpenApiDoc(mapper, plainSchema, mutatedFull, filterSchema));

        ResponseEntity<Map<String, Object>> postRequestMutated = controller.getFilteredSchema(
                "/api/test/employees", "post", true, "request", null, null, Locale.ROOT
        );
        String postHashMutated = postRequestMutated.getHeaders().getFirst("X-Schema-Hash");
        assertNotEquals(postHashInitial, postHashMutated, "X-Schema-Hash/ETag must track x-ui.formLayout mutations");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesFormLayoutAcrossJdkAndCglibProxiesAndIgnoresMapPayloads() {
        for (boolean cglib : new boolean[]{false, true}) {
            ProxyFactory factory = new ProxyFactory(new ProxiedEmployeeController());
            factory.addInterface(ProxiedEmployeeContract.class);
            factory.setProxyTargetClass(cglib);
            Object proxyBean = factory.getProxy();

            StaticApplicationContext appCtx = new StaticApplicationContext();
            appCtx.getBeanFactory().registerSingleton("proxiedEmployeeController", proxyBean);
            appCtx.refresh();

            RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
            handlerMapping.setApplicationContext(appCtx);
            handlerMapping.afterPropertiesSet();

            ApiResourceIdentityResolver identityResolver = new ApiResourceIdentityResolver(handlerMapping);

            Map<String, Object> methodLayout = identityResolver
                    .resolveMethodFormLayout("/api/test/proxied/{id}", "put", "request")
                    .orElseThrow(() -> new AssertionError("Expected @FormLayout on concrete proxied method (cglib=" + cglib + ")"));
            assertEquals("tabs", methodLayout.get("mode"));
            assertEquals("buttons", methodLayout.get("tabsAppearance"));

            Map<String, Object> dtoLayout = identityResolver
                    .resolveDtoFormLayout("/api/test/proxied/{id}", "put", "request")
                    .orElseThrow(() -> new AssertionError("Expected DTO @FormLayout on proxied request body (cglib=" + cglib + ")"));
            assertEquals("stepper", dtoLayout.get("mode"));

            assertTrue(
                    identityResolver.resolveDtoFormLayout("/api/test/proxied/{id}/contact", "patch", "request").isEmpty(),
                    "Dynamic Map<String, Object> PATCH request body must not resolve DTO @FormLayout"
            );
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void validatesNestedDtoGroupsAcrossRefAndOpenApiCustomizerAndRejectsInvalidRawModeOrTabsAppearance() {
        PraxisExpressionStaticValidator validator = new PraxisExpressionStaticValidator();
        assertTrue(validator.validateDtoClass(NestedGroupEmployeeDTO.class, true).isEmpty(),
                "Groups declared inside nested @Valid/$ref DTOs must satisfy @FormLayoutSection.groups");

        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(new ObjectMapper().findAndRegisterModules()));
        ResolvedSchema resolved = converters.resolveAsResolvedSchema(
                new AnnotatedType(NestedGroupEmployeeDTO.class).resolveAsRef(false)
        );
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("NestedGroupEmployeeDTO", resolved.schema));
        if (resolved.referencedSchemas != null) {
            resolved.referencedSchemas.forEach(openApi.getComponents()::addSchemas);
        }
        PraxisExpressionValidationOpenApiCustomizer customizer = new PraxisExpressionValidationOpenApiCustomizer(
                ExpressionValidationProperties.defaults(),
                new MockEnvironment().withProperty("spring.profiles.active", "test"),
                validator
        );
        assertDoesNotThrow(() -> customizer.customise(openApi));

        Schema<?> invalidRawSchema = new Schema<>();
        invalidRawSchema.addProperty("email", new StringSchema());
        Map<String, Object> badLayout = new LinkedHashMap<>();
        badLayout.put("mode", "wizard");
        badLayout.put("tabsAppearance", "boxed");
        badLayout.put("stepperInReadMode", "always");
        badLayout.put("sections", List.of(Map.of("id", "sec-1", "title", "Um")));
        badLayout.put("steps", List.of(Map.of("id", "step-1", "sectionIds", List.of("sec-unknown"))));
        Map<String, Object> rootUi = new LinkedHashMap<>();
        rootUi.put("formLayout", badLayout);
        invalidRawSchema.addExtension("x-ui", rootUi);

        InvalidDomainExpressionException ex = assertThrows(
                InvalidDomainExpressionException.class,
                () -> validator.validateSchemaExpressions("CustomRawDTO", invalidRawSchema, true)
        );
        assertTrue(ex.getMessage().contains("wizard"));
        assertTrue(ex.getMessage().contains("boxed"));
        assertTrue(ex.getMessage().contains("always"));
        assertTrue(ex.getMessage().contains("sec-unknown"));
    }

    @FormLayout(FormLayoutMode.AUTO)
    static class AutoStepperEnterpriseDTO {
        @UISchema(label = "Nome", group = "identificacao")
        public String nome;
        @UISchema(label = "E-mail", group = "contato")
        public String email;
        @UISchema(label = "Cargo", group = "carreira")
        public String cargo;
        @UISchema(label = "Salário", group = "remuneracao")
        public BigDecimal salario;
    }

    @FormLayout(mode = FormLayoutMode.AUTO, stepperReviewStep = UiBoolean.FALSE)
    static class AutoStepperNoReviewDTO extends AutoStepperEnterpriseDTO {
    }

    @FormLayout(FormLayoutMode.AUTO)
    static class AutoTabsDTO {
        @UISchema(label = "Campo 1", group = "g1")
        public String f1;
        @UISchema(label = "Campo 2", group = "g1")
        public String f2;
        @UISchema(label = "Campo 3", group = "g1")
        public String f3;
        @UISchema(label = "Campo 4", group = "g2")
        public String f4;
        @UISchema(label = "Campo 5", group = "g2")
        public String f5;
        @UISchema(label = "Campo 6", group = "g2")
        public String f6;
    }

    @FormLayout(FormLayoutMode.AUTO)
    static class AutoFlatDTO {
        @UISchema(label = "Código", group = "geral")
        public String codigo;
        @UISchema(label = "Descrição", group = "geral")
        public String descricao;
    }

    @Test
    void formLayoutModeAutoHeuristicResolvesStepperWithReviewStepTabsAndFlat() throws Exception {
        Map<String, Object> stepperLayout = FormLayoutMetadataResolver.resolve(AutoStepperEnterpriseDTO.class);
        assertNotNull(stepperLayout);
        assertEquals("stepper", stepperLayout.get("mode"));
        assertEquals(Boolean.TRUE, stepperLayout.get("stepperReviewStep"));
        assertEquals(Boolean.TRUE, stepperLayout.get("draftPersistence"));

        Map<String, Object> stepperNoReviewLayout = FormLayoutMetadataResolver.resolve(AutoStepperNoReviewDTO.class);
        assertNotNull(stepperNoReviewLayout);
        assertEquals("stepper", stepperNoReviewLayout.get("mode"));
        assertEquals(Boolean.FALSE, stepperNoReviewLayout.get("stepperReviewStep"));
        assertEquals(Boolean.TRUE, stepperNoReviewLayout.get("draftPersistence"));

        Map<String, Object> tabsLayout = FormLayoutMetadataResolver.resolve(AutoTabsDTO.class);
        assertNotNull(tabsLayout);
        assertEquals("tabs", tabsLayout.get("mode"));
        assertNull(tabsLayout.get("stepperReviewStep"));
        assertNull(tabsLayout.get("draftPersistence"));

        Map<String, Object> flatLayout = FormLayoutMetadataResolver.resolve(AutoFlatDTO.class);
        assertNotNull(flatLayout);
        assertEquals("flat", flatLayout.get("mode"));
        assertNull(flatLayout.get("stepperReviewStep"));
        assertNull(flatLayout.get("draftPersistence"));

        var method = AutoControllerMethodSample.class.getDeclaredMethod("submitAuto", AutoStepperEnterpriseDTO.class);
        Map<String, Object> methodAutoLayout = FormLayoutMetadataResolver.resolve(method);
        assertNotNull(methodAutoLayout);
        assertEquals("stepper", methodAutoLayout.get("mode"));
        assertEquals(Boolean.TRUE, methodAutoLayout.get("stepperReviewStep"));
        assertEquals(Boolean.TRUE, methodAutoLayout.get("draftPersistence"));
    }

    static class AutoControllerMethodSample {
        @PostMapping("/api/test/auto")
        @FormLayout(FormLayoutMode.AUTO)
        public void submitAuto(@RequestBody AutoStepperEnterpriseDTO body) {
        }
    }

    private static ObjectNode toObjectNode(io.swagger.v3.oas.models.media.Schema<?> schema) {
        return (ObjectNode) Json.mapper().valueToTree(schema);
    }

    private static ObjectNode buildEmployeeOpenApiDoc(
            ObjectMapper mapper,
            ObjectNode plainSchema,
            ObjectNode fullSchema,
            ObjectNode filterSchema
    ) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode paths = root.putObject("paths");

        // /api/test/employees
        ObjectNode collectionPath = paths.putObject("/api/test/employees");
        ObjectNode getListOp = collectionPath.putObject("get");
        getListOp.putObject("x-ui").put("responseSchema", "FullPhase2EmployeeDTO");
        getListOp.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/FullPhase2EmployeeDTO");

        ObjectNode postOp = collectionPath.putObject("post");
        postOp.putObject("x-ui").put("requestSchema", "FullPhase2EmployeeDTO").put("responseSchema", "PlainCommandDTO");
        postOp.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/FullPhase2EmployeeDTO");
        postOp.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/PlainCommandDTO");

        // /api/test/employees/{id}
        ObjectNode itemPath = paths.putObject("/api/test/employees/{id}");
        ObjectNode getDetailOp = itemPath.putObject("get");
        getDetailOp.putObject("x-ui").put("responseSchema", "FullPhase2EmployeeDTO");
        getDetailOp.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/FullPhase2EmployeeDTO");

        ObjectNode putOp = itemPath.putObject("put");
        putOp.putObject("x-ui").put("requestSchema", "FullPhase2EmployeeDTO").put("responseSchema", "FullPhase2EmployeeDTO");
        putOp.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/FullPhase2EmployeeDTO");
        putOp.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/FullPhase2EmployeeDTO");

        // /api/test/employees/filter
        ObjectNode filterOp = paths.putObject("/api/test/employees/filter").putObject("post");
        filterOp.putObject("x-ui")
                .put("requestSchema", "EmployeeFilterDTO")
                .put("responseSchema", "FullPhase2EmployeeDTO");
        filterOp.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/EmployeeFilterDTO");
        filterOp.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/FullPhase2EmployeeDTO");

        // /api/test/employees/stats/group-by
        ObjectNode statsOp = paths.putObject("/api/test/employees/stats/group-by").putObject("post");
        statsOp.putObject("x-ui")
                .put("requestSchema", "FullPhase2EmployeeDTO")
                .put("responseSchema", "PlainCommandDTO");
        statsOp.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/FullPhase2EmployeeDTO");
        statsOp.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/PlainCommandDTO");

        ObjectNode schemas = root.putObject("components").putObject("schemas");
        schemas.set("PlainCommandDTO", plainSchema);
        schemas.set("FullPhase2EmployeeDTO", fullSchema);
        schemas.set("EmployeeFilterDTO", filterSchema);
        return root;
    }
}
