package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ContextScopeBinding;
import org.praxisplatform.uischema.annotation.DraftCleared;
import org.praxisplatform.uischema.annotation.LayoutMode;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceProgression;
import org.praxisplatform.uischema.annotation.ResourceSchedule;
import org.praxisplatform.uischema.annotation.ResourceState;
import org.praxisplatform.uischema.annotation.ResourceTimeline;
import org.praxisplatform.uischema.annotation.ScopeDimension;
import org.praxisplatform.uischema.annotation.TableView;
import org.praxisplatform.uischema.annotation.UiFormLayout;
import org.praxisplatform.uischema.annotation.UiSection;
import org.praxisplatform.uischema.authoring.SemanticMetadataIssueSeverity;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewIssue;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewReport;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewer;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.controller.base.AbstractDuplicateDraftResourceController;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.controller.docs.ApiDocsController;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.option.PraxisUiOptionEnum;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.service.base.DuplicateDraftResourceService;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhaseAErgonomicsOpenApiAndSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

    private Schema<?> resolveDtoSchema(Class<?> dtoClass, CustomOpenApiResolver resolver) {
        ModelConverters converters = new ModelConverters();
        converters.addConverter(resolver);
        var resolved = converters.resolveAsResolvedSchema(new AnnotatedType(dtoClass).resolveAsRef(false));
        assertNotNull(resolved.schema);
        return resolved.schema;
    }

    private Schema<?> resolveDtoSchema(Class<?> dtoClass) {
        return resolveDtoSchema(dtoClass, new CustomOpenApiResolver(mapper));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> xUi(Schema<?> schema) {
        assertNotNull(schema);
        assertNotNull(schema.getExtensions());
        Object raw = schema.getExtensions().get("x-ui");
        assertInstanceOf(Map.class, raw);
        return (Map<String, Object>) raw;
    }

    @Test
    void numericFormatAutoAllowsExplicitIntegerAndOmitsImplicitNumericFormat() {
        Schema<?> schema = resolveDtoSchema(NumericFormatAndPresetDto.class);
        Map<String, Object> plainUi = xUi(schema.getProperties().get("plainCount"));
        Map<String, Object> explicitUi = xUi(schema.getProperties().get("explicitCount"));

        assertFalse(plainUi.containsKey(FieldConfigProperties.NUMERIC_FORMAT.getValue()));
        assertEquals("integer", explicitUi.get(FieldConfigProperties.NUMERIC_FORMAT.getValue()));
    }

    @Test
    void uiSchemaPresetsApplyLocalDateDateTimeIntegerCounterAndEntityLookupWithExplicitOverrides() {
        Schema<?> schema = resolveDtoSchema(NumericFormatAndPresetDto.class);

        Map<String, Object> startDateUi = xUi(schema.getProperties().get("startDate"));
        assertEquals(FieldDataType.DATE.getValue(), startDateUi.get(FieldConfigProperties.TYPE.getValue()));
        assertEquals(FieldControlType.DATE_PICKER.getValue(), startDateUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("calendar_today", startDateUi.get(FieldConfigProperties.ICON.getValue()));

        Map<String, Object> updatedAtUi = xUi(schema.getProperties().get("updatedAt"));
        assertEquals(FieldDataType.DATE.getValue(), updatedAtUi.get(FieldConfigProperties.TYPE.getValue()));
        assertEquals(FieldControlType.DATE_TIME_PICKER.getValue(), updatedAtUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("schedule", updatedAtUi.get(FieldConfigProperties.ICON.getValue()));

        Map<String, Object> retryCountUi = xUi(schema.getProperties().get("retryCount"));
        assertEquals(FieldDataType.NUMBER.getValue(), retryCountUi.get(FieldConfigProperties.TYPE.getValue()));
        assertEquals(FieldControlType.NUMERIC_TEXT_BOX.getValue(), retryCountUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("integer", retryCountUi.get(FieldConfigProperties.NUMERIC_FORMAT.getValue()));
        assertEquals("tag", retryCountUi.get(FieldConfigProperties.ICON.getValue()));

        Map<String, Object> departmentUi = xUi(schema.getProperties().get("departmentId"));
        assertEquals(FieldDataType.NUMBER.getValue(), departmentUi.get(FieldConfigProperties.TYPE.getValue()));
        assertEquals(FieldControlType.ENTITY_LOOKUP.getValue(), departmentUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("id", departmentUi.get(FieldConfigProperties.VALUE_FIELD.getValue()));
        assertEquals("label", departmentUi.get(FieldConfigProperties.DISPLAY_FIELD.getValue()));
        assertEquals("apartment", departmentUi.get(FieldConfigProperties.ICON.getValue()), "explicit icon must override preset");
    }

    @Test
    void praxisUiOptionEnumPublishesOptionsAndPrepopulatesLookupDirectoryDescriptor() {
        Schema<?> schema = resolveDtoSchema(ContractDto.class);
        Map<String, Object> statusUi = xUi(schema.getProperties().get("status"));
        JsonNode options = mapper.valueToTree(statusUi.get("options"));

        assertEquals(3, options.size());
        assertEquals("DRAFT", options.get(0).path("value").asText());
        assertEquals("Rascunho", options.get(0).path("label").asText());
        assertEquals("edit_note", options.get(0).path("icon").asText());
        assertEquals("neutral", options.get(0).path("tone").asText());
        assertEquals("ACTIVE", options.get(1).path("value").asText());
        assertEquals("success", options.get(1).path("tone").asText());

        EntityLookupDescriptor descriptor = EntityLookupDescriptor
                .directoryBuilderFromDto("contracts", ContractDto.class)
                .build();
        assertEquals("status", descriptor.statusPropertyPath());
        assertEquals(Map.of("DRAFT", "Rascunho", "ACTIVE", "Ativo", "CANCELLED", "Cancelado"),
                descriptor.display().statusLabelMap());
        assertNotNull(descriptor.selectionPolicy());
        assertEquals("status", descriptor.selectionPolicy().statusPropertyPath());
        assertEquals(List.of("CANCELLED"), descriptor.selectionPolicy().blockedStatuses());

        SemanticMetadataReviewReport badEnumReport = reviewer.review(InvalidOptionEnumDto.class);
        assertTrue(badEnumReport.issues().stream()
                .anyMatch(i -> i.severity() == SemanticMetadataIssueSeverity.ERROR));
    }

    @Test
    void conditionalReadOnlyColSpanTableViewAndUiFormLayoutSerializeAndValidate() {
        Schema<?> schema = resolveDtoSchema(StructuredFormAndTableDto.class);
        Map<String, Object> rootUi = xUi(schema);
        JsonNode formLayout = mapper.valueToTree(rootUi.get(FieldConfigProperties.FORM_LAYOUT.getValue()));
        assertEquals("tabs", formLayout.path("mode").asText());
        assertEquals(2, formLayout.path("sections").size());
        assertEquals("general", formLayout.path("sections").get(0).path("id").asText());
        assertEquals("financial", formLayout.path("sections").get(1).path("id").asText());

        Map<String, Object> codeUi = xUi(schema.getProperties().get("code"));
        assertEquals(0, codeUi.get(FieldConfigProperties.TABLE_ORDER.getValue()));
        assertEquals("start", codeUi.get(FieldConfigProperties.PINNED.getValue()));
        assertEquals(4, codeUi.get(FieldConfigProperties.COL_SPAN.getValue()));

        Map<String, Object> statusUi = xUi(schema.getProperties().get("status"));
        assertEquals(1, statusUi.get(FieldConfigProperties.TABLE_ORDER.getValue()));
        assertEquals("end", statusUi.get(FieldConfigProperties.PINNED.getValue()));

        Map<String, Object> notesUi = xUi(schema.getProperties().get("internalNotes"));
        assertEquals(Boolean.TRUE, notesUi.get(FieldConfigProperties.TABLE_HIDDEN.getValue()));
        assertEquals(12, notesUi.get(FieldConfigProperties.COL_SPAN.getValue()));
        JsonNode conditionalReadOnly = mapper.valueToTree(notesUi.get(FieldConfigProperties.CONDITIONAL_READ_ONLY.getValue()));
        assertTrue(conditionalReadOnly.has("=="));

        SemanticMetadataReviewReport invalidColSpan = reviewer.review(InvalidColSpanAndConditionalDto.class);
        List<String> codes = invalidColSpan.issues().stream().map(SemanticMetadataReviewIssue::code).toList();
        assertTrue(codes.contains("uischema-colspan-invalid"), codes.toString());
        assertTrue(codes.contains("uischema-conditional-readonly-invalid"), codes.toString());
        assertTrue(codes.contains("tableview-column-unknown"), codes.toString());
        assertTrue(codes.contains("tableview-pinned-conflict"), codes.toString());
        assertTrue(codes.contains("form-layout-section-duplicate-id"), codes.toString());
        assertTrue(codes.contains("form-layout-section-icon-invalid"), codes.toString());
    }

    @Test
    void contextScopeBindingDraftClearedAndResourceStatePublishAndClearNonPrimitiveDraftFields() {
        Schema<?> schema = resolveDtoSchema(LifecycleAndContextDraftDto.class);
        Map<String, Object> rootUi = xUi(schema);
        JsonNode rootContextBinding = mapper.valueToTree(rootUi.get(FieldConfigProperties.CONTEXT_BINDING.getValue()));
        assertEquals("organization", rootContextBinding.path("dimension").asText());
        assertEquals("organizationId", rootContextBinding.path("targetField").asText());

        Map<String, Object> unitUi = xUi(schema.getProperties().get("unitId"));
        JsonNode unitBinding = mapper.valueToTree(unitUi.get(FieldConfigProperties.CONTEXT_BINDING.getValue()));
        assertEquals("unit", unitBinding.path("dimension").asText());
        assertFalse(unitBinding.path("lockWhenActive").asBoolean(true));

        Map<String, Object> codeUi = xUi(schema.getProperties().get("uniqueCode"));
        assertEquals(Boolean.TRUE, codeUi.get(FieldConfigProperties.DRAFT_CLEARED.getValue()));

        Map<String, Object> statusUi = xUi(schema.getProperties().get("status"));
        JsonNode resourceState = mapper.valueToTree(statusUi.get(FieldConfigProperties.RESOURCE_STATE.getValue()));
        assertEquals(List.of("DRAFT"), mapper.convertValue(resourceState.path("initialStates"), List.class));
        assertEquals(List.of("DRAFT", "SUSPENDED"), mapper.convertValue(resourceState.path("putMutableStates"), List.class));

        DraftFixtureService service = mock(DraftFixtureService.class);
        when(service.supportsDuplicateDraft()).thenReturn(true);
        when(service.duplicateDraft(9L)).thenReturn(new LifecycleAndContextDraftDto(77L, 5L, "CODE-99", 42, "ACTIVE"));
        DraftFixtureController controller = new DraftFixtureController(service);
        var capabilities = mock(org.praxisplatform.uischema.capability.CapabilityService.class);
        when(capabilities.collectionOperationAvailability(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.praxisplatform.uischema.capability.AvailabilityDecision.allowAll());
        when(capabilities.itemOperationAvailability(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.praxisplatform.uischema.capability.AvailabilityDecision.allowAll());
        ReflectionTestUtils.setField(controller, "capabilityService", capabilities);

        var response = controller.duplicateDraft(9L);
        assertEquals(200, response.getStatusCode().value());
        LifecycleAndContextDraftDto draft = response.getBody().getData();
        assertNull(draft.getUniqueCode(), "non-primitive @DraftCleared field must be cleared");
        assertEquals(42, draft.getPrimitiveCount(), "primitive field must not fail clearing and remains preserved");
        assertEquals("ACTIVE", draft.getStatus());
    }

    @Test
    void visualizersPublishOnlyOnResponseVariantAndReviewerValidatesUnknownFields() {
        Schema<?> schema = resolveDtoSchema(VisualizerDto.class);
        Map<String, Object> rawUi = xUi(schema);
        assertTrue(rawUi.containsKey(FieldConfigProperties.PROGRESSION.getValue()));
        assertTrue(rawUi.containsKey(FieldConfigProperties.TIMELINE.getValue()));
        assertTrue(rawUi.containsKey(FieldConfigProperties.SCHEDULE.getValue()));

        ApiDocsController docsController = new ApiDocsController();
        OpenApiDocsSupport docsSupport = new OpenApiDocsSupport();
        JsonNode dtoSchemaNode = Json.mapper().valueToTree(schema);
        OpenApiDocumentService stubDocService = new OpenApiDocumentService() {
            @Override
            public String resolveGroupFromPath(String path) {
                return "visualizers";
            }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                ObjectNode root = mapper.createObjectNode();
                ObjectNode paths = root.putObject("paths");
                paths.putObject("/api/workforce/visualizers/{id}")
                        .putObject("get")
                        .putObject("x-ui")
                        .put("responseSchema", "VisualizerDto");
                paths.putObject("/api/workforce/visualizers")
                        .putObject("post")
                        .putObject("requestBody")
                        .putObject("content")
                        .putObject("application/json")
                        .putObject("schema")
                        .put("$ref", "#/components/schemas/VisualizerDto");
                ObjectNode schemas = root.putObject("components").putObject("schemas");
                schemas.set("VisualizerDto", dtoSchemaNode.deepCopy());
                return root;
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return "hash-" + schemaId;
            }

            @Override
            public void clearCaches() {
            }
        };
        ReflectionTestUtils.setField(docsController, "objectMapper", mapper);
        ReflectionTestUtils.setField(docsController, "openApiDocsSupport", docsSupport);
        ReflectionTestUtils.setField(docsController, "openApiDocumentService", stubDocService);
        ReflectionTestUtils.setField(docsController, "canonicalOperationResolver", new OpenApiCanonicalOperationResolver(stubDocService, null));
        ReflectionTestUtils.setField(docsController, "schemaReferenceResolver", new FilteredSchemaReferenceResolver());
        ReflectionTestUtils.setField(docsController, "canonicalCapabilityResolver", new OpenApiCanonicalCapabilityResolver(stubDocService));
        StaticListableBeanFactory emptyBeans = new StaticListableBeanFactory();
        ReflectionTestUtils.setField(docsController, "optionSourceRegistryProvider", emptyBeans.getBeanProvider(OptionSourceRegistry.class));

        ResponseEntity<Map<String, Object>> responseVariant = docsController.getFilteredSchema(
                "/api/workforce/visualizers/{id}", "get", false, "response", null, false, null, null, Locale.ENGLISH);
        assertEquals(200, responseVariant.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> respXUi = (Map<String, Object>) responseVariant.getBody().get("x-ui");
        assertTrue(respXUi.containsKey("progression"));
        assertTrue(respXUi.containsKey("timeline"));
        assertTrue(respXUi.containsKey("schedule"));

        ResponseEntity<Map<String, Object>> requestVariant = docsController.getFilteredSchema(
                "/api/workforce/visualizers", "post", false, "request", null, false, null, null, Locale.ENGLISH);
        assertEquals(200, requestVariant.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> reqXUi = (Map<String, Object>) requestVariant.getBody().get("x-ui");
        assertFalse(reqXUi.containsKey("progression"));
        assertFalse(reqXUi.containsKey("timeline"));
        assertFalse(reqXUi.containsKey("schedule"));

        SemanticMetadataReviewReport badVisualizer = reviewer.review(InvalidVisualizerDto.class);
        assertTrue(badVisualizer.issues().stream()
                .anyMatch(i -> "resource-visualizer-field-unknown".equals(i.code())));
    }

    @Test
    void filterDtoInheritsOmittedUiMetadataPublishesPrimarySearchAndValidatesAgainstFieldSpec() throws Exception {
        CustomOpenApiResolver resolver = new CustomOpenApiResolver(mapper);
        resolver.registerFilterResponsePair(PairedFilterDto.class, PairedResponseDto.class);
        Schema<?> filterSchema = resolveDtoSchema(PairedFilterDto.class, resolver);

        Map<String, Object> searchUi = xUi(filterSchema.getProperties().get("searchTerm"));
        assertEquals(Boolean.TRUE, searchUi.get(FieldConfigProperties.PRIMARY_SEARCH.getValue()));

        Map<String, Object> categoryUi = xUi(filterSchema.getProperties().get("categoryId"));
        assertEquals("category", categoryUi.get(FieldConfigProperties.ICON.getValue()));
        assertEquals("/api/categories/options/filter", categoryUi.get(FieldConfigProperties.ENDPOINT.getValue()));
        assertEquals("id", categoryUi.get(FieldConfigProperties.VALUE_FIELD.getValue()));
        assertEquals("label", categoryUi.get(FieldConfigProperties.DISPLAY_FIELD.getValue()));

        Map<String, Object> statusUi = xUi(filterSchema.getProperties().get("status"));
        assertEquals("verified_user", statusUi.get(FieldConfigProperties.ICON.getValue()),
                "explicit FilterDTO icon must not be overwritten by ResponseDTO");
        assertNotNull(statusUi.get("options"), "omitted enum options should be inherited from ResponseDTO");

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class))
                .withBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
                .withBean(PairedController.class, PairedController::new)
                .run(context -> {
                    GlobalOpenApiCustomizer customizer = context.getBean(
                            "filterSchemaInheritanceOpenApiCustomizer", GlobalOpenApiCustomizer.class);
                    OpenAPI openAPI = new OpenAPI();
                    openAPI.schema(PairedResponseDto.class.getSimpleName(), resolveDtoSchema(PairedResponseDto.class));
                    Schema<?> bareFilterSchema = resolveDtoSchema(PairedFilterDto.class);
                    openAPI.schema(PairedFilterDto.class.getSimpleName(), bareFilterSchema);

                    customizer.customise(openAPI);
                    Schema<?> inheritedProp = (Schema<?>) openAPI.getComponents().getSchemas()
                            .get(PairedFilterDto.class.getSimpleName()).getProperties().get("categoryId");
                    Map<String, Object> inherited = xUi(inheritedProp);
                    assertEquals("/api/categories/options/filter", inherited.get("endpoint"));
                });

        JsonSchema fieldSpec = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(Path.of("docs/spec/x-ui-field.schema.json").toFile()));

        Schema<?> formTableSchema = resolveDtoSchema(StructuredFormAndTableDto.class);
        Schema<?> lifecycleSchema = resolveDtoSchema(LifecycleAndContextDraftDto.class);
        List<Map<String, Object>> payloadsToValidate = List.of(
                searchUi,
                categoryUi,
                xUi(formTableSchema.getProperties().get("code")),
                xUi(formTableSchema.getProperties().get("status")),
                xUi(formTableSchema.getProperties().get("internalNotes")),
                xUi(lifecycleSchema.getProperties().get("unitId")),
                xUi(lifecycleSchema.getProperties().get("uniqueCode")),
                xUi(lifecycleSchema.getProperties().get("status"))
        );
        for (Map<String, Object> payload : payloadsToValidate) {
            Set<ValidationMessage> errors = fieldSpec.validate(mapper.valueToTree(payload));
            assertTrue(errors.isEmpty(), "Schema validation failed for payload " + payload + ": " + errors);
        }
    }

    public static class NumericFormatAndPresetDto {
        @UISchema(label = "Plain count")
        private Integer plainCount;

        @UISchema(label = "Explicit count", numericFormat = NumericFormat.INTEGER)
        private Integer explicitCount;

        @UISchema(label = "Start date", preset = UISchemaPreset.LOCAL_DATE)
        private LocalDate startDate;

        @UISchema(label = "Updated at", preset = UISchemaPreset.LOCAL_DATE_TIME)
        private LocalDateTime updatedAt;

        @UISchema(label = "Retry count", preset = UISchemaPreset.INTEGER_COUNTER)
        private Integer retryCount;

        @UISchema(label = "Department", preset = UISchemaPreset.ENTITY_LOOKUP, icon = "apartment")
        private Long departmentId;

        public Integer getPlainCount() { return plainCount; }
        public void setPlainCount(Integer plainCount) { this.plainCount = plainCount; }
        public Integer getExplicitCount() { return explicitCount; }
        public void setExplicitCount(Integer explicitCount) { this.explicitCount = explicitCount; }
        public LocalDate getStartDate() { return startDate; }
        public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        public Integer getRetryCount() { return retryCount; }
        public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
        public Long getDepartmentId() { return departmentId; }
        public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    }

    public enum ContractState implements PraxisUiOptionEnum {
        DRAFT("Rascunho", "edit_note", QuickFilterTone.NEUTRAL, true, true),
        ACTIVE("Ativo", "check_circle", QuickFilterTone.SUCCESS, true, false),
        CANCELLED("Cancelado", "cancel", QuickFilterTone.DANGER, false, false);

        private final String label;
        private final String icon;
        private final QuickFilterTone tone;
        private final boolean selectableInLookup;
        private final boolean workflowInitial;

        ContractState(String label, String icon, QuickFilterTone tone, boolean selectableInLookup, boolean workflowInitial) {
            this.label = label;
            this.icon = icon;
            this.tone = tone;
            this.selectableInLookup = selectableInLookup;
            this.workflowInitial = workflowInitial;
        }

        @Override public String label() { return label; }
        @Override public String icon() { return icon; }
        @Override public QuickFilterTone tone() { return tone; }
        @Override public boolean selectableInLookup() { return selectableInLookup; }
        @Override public boolean workflowInitial() { return workflowInitial; }
    }

    @ResourceIdentity(keyField = "id", titleField = "name")
    public static class ContractDto {
        @UISchema(label = "ID")
        private Long id;
        @UISchema(label = "Code", preset = UISchemaPreset.ENTERPRISE_CODE)
        private String code;
        @UISchema(label = "Name")
        private String name;
        @ResourceState(initialStates = {"DRAFT"}, putMutableStates = {"DRAFT"})
        @UISchema(label = "Status", controlType = FieldControlType.SELECT, optionEnum = ContractState.class)
        private ContractState status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public ContractState getStatus() { return status; }
        public void setStatus(ContractState status) { this.status = status; }
    }

    public enum BrokenOptionEnum implements PraxisUiOptionEnum {
        ONE("", "BadIcon");
        private final String label;
        private final String icon;
        BrokenOptionEnum(String label, String icon) { this.label = label; this.icon = icon; }
        @Override public String label() { return label; }
        @Override public String icon() { return icon; }
    }

    public static class InvalidOptionEnumDto {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Estado com enum invalido para validacao de auditoria.")
        @UISchema(label = "Broken", optionEnum = BrokenOptionEnum.class)
        private BrokenOptionEnum status;

        public BrokenOptionEnum getStatus() { return status; }
        public void setStatus(BrokenOptionEnum status) { this.status = status; }
    }

    @TableView(columns = {"code", "status"}, pinnedStart = {"code"}, pinnedEnd = {"status"})
    @UiFormLayout(mode = LayoutMode.TABS, sections = {
            @UiSection(id = "financial", title = "Financeiro", icon = "payments", order = 2, groups = {"financial"}),
            @UiSection(id = "general", title = "Geral", icon = "badge", order = 1, groups = {"general"})
    })
    public static class StructuredFormAndTableDto {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Codigo identificador unico do contrato.")
        @UISchema(label = "Codigo", colSpan = 4, group = "general")
        private String code;

        @io.swagger.v3.oas.annotations.media.Schema(description = "Situacao corrente do ciclo de vida do contrato.")
        @UISchema(label = "Situacao", colSpan = 4, group = "general")
        private String status;

        @io.swagger.v3.oas.annotations.media.Schema(description = "Observacoes internas de acompanhamento contratual.")
        @UISchema(label = "Notas internas", colSpan = 12, group = "financial",
                conditionalReadOnly = "{\"==\":[{\"var\":\"status\"},\"CLOSED\"]}")
        private String internalNotes;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getInternalNotes() { return internalNotes; }
        public void setInternalNotes(String internalNotes) { this.internalNotes = internalNotes; }
    }

    @TableView(columns = {"missingCol"}, pinnedStart = {"code"}, pinnedEnd = {"code"})
    @UiFormLayout(mode = LayoutMode.FLAT, sections = {
            @UiSection(id = "dup", title = "Primeira", icon = "BadIcon"),
            @UiSection(id = "dup", title = "Segunda")
    })
    public static class InvalidColSpanAndConditionalDto {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Codigo principal para teste de validacao estatica.")
        @UISchema(label = "Code", colSpan = 99, conditionalReadOnly = "{not valid json")
        private String code;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }

    @ContextScopeBinding(dimension = ScopeDimension.ORGANIZATION, targetField = "organizationId")
    public static class LifecycleAndContextDraftDto {
        private Long organizationId;

        @ContextScopeBinding(dimension = ScopeDimension.UNIT, lockWhenActive = false)
        @UISchema(label = "Unidade")
        private Long unitId;

        @DraftCleared
        @UISchema(label = "Codigo unico")
        private String uniqueCode;

        @DraftCleared
        @UISchema(label = "Contagem")
        private int primitiveCount;

        @ResourceState(initialStates = {"DRAFT"}, putMutableStates = {"DRAFT", "SUSPENDED"})
        @UISchema(label = "Situacao")
        private String status;

        public LifecycleAndContextDraftDto() {}
        public LifecycleAndContextDraftDto(Long organizationId, Long unitId, String uniqueCode, int primitiveCount, String status) {
            this.organizationId = organizationId;
            this.unitId = unitId;
            this.uniqueCode = uniqueCode;
            this.primitiveCount = primitiveCount;
            this.status = status;
        }

        public Long getOrganizationId() { return organizationId; }
        public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
        public Long getUnitId() { return unitId; }
        public void setUnitId(Long unitId) { this.unitId = unitId; }
        public String getUniqueCode() { return uniqueCode; }
        public void setUniqueCode(String uniqueCode) { this.uniqueCode = uniqueCode; }
        public int getPrimitiveCount() { return primitiveCount; }
        public void setPrimitiveCount(int primitiveCount) { this.primitiveCount = primitiveCount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public interface DraftFixtureService extends DuplicateDraftResourceService<
            LifecycleAndContextDraftDto, Long, SimpleDraftFilterDto, LifecycleAndContextDraftDto, LifecycleAndContextDraftDto, LifecycleAndContextDraftDto> {
    }

    public static class SimpleDraftFilterDto implements GenericFilterDTO {}

    @ApiResource(value = "/api/workforce/drafts", resourceKey = "workforce.drafts")
    public static class DraftFixtureController extends AbstractDuplicateDraftResourceController<
            LifecycleAndContextDraftDto, Long, SimpleDraftFilterDto, LifecycleAndContextDraftDto, LifecycleAndContextDraftDto, LifecycleAndContextDraftDto> {
        private final DraftFixtureService service;
        DraftFixtureController(DraftFixtureService service) { this.service = service; }
        @Override protected DraftFixtureService getService() { return service; }
        @Override protected Long getResponseId(LifecycleAndContextDraftDto dto) { return dto.getOrganizationId(); }
        @Override protected String getIdFieldName() { return "organizationId"; }
        @Override protected String getBasePath() { return "/api/workforce/drafts"; }
    }

    @ResourceProgression(title = "Evolucao", currentLevelField = "level", maxLevelField = "maxLevel")
    @ResourceTimeline(title = "Linha do tempo", startDateField = "validFrom", endDateField = "validTo")
    @ResourceSchedule(title = "Escala", startTimeField = "shiftStart", shiftField = "shiftName")
    public static class VisualizerDto {
        private Integer level;
        private Integer maxLevel;
        private LocalDate validFrom;
        private LocalDate validTo;
        private String shiftStart;
        private String shiftName;

        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }
        public Integer getMaxLevel() { return maxLevel; }
        public void setMaxLevel(Integer maxLevel) { this.maxLevel = maxLevel; }
        public LocalDate getValidFrom() { return validFrom; }
        public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
        public LocalDate getValidTo() { return validTo; }
        public void setValidTo(LocalDate validTo) { this.validTo = validTo; }
        public String getShiftStart() { return shiftStart; }
        public void setShiftStart(String shiftStart) { this.shiftStart = shiftStart; }
        public String getShiftName() { return shiftName; }
        public void setShiftName(String shiftName) { this.shiftName = shiftName; }
    }

    @ResourceProgression(currentLevelField = "nonExistentLevel")
    public static class InvalidVisualizerDto {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Identificador da visualizacao invalida.")
        @UISchema(label = "ID")
        private Long id;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    public static class PairedResponseDto {
        @UISchema(label = "Nome")
        private String name;

        @UISchema(label = "Categoria", controlType = FieldControlType.ENTITY_LOOKUP, icon = "category",
                endpoint = "/api/categories/options/filter", valueField = "id", displayField = "label")
        private Long categoryId;

        @UISchema(label = "Status", controlType = FieldControlType.SELECT, icon = "flag", optionEnum = ContractState.class)
        private ContractState status;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
        public ContractState getStatus() { return status; }
        public void setStatus(ContractState status) { this.status = status; }
    }

    public static class PairedFilterDto implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.LIKE, relations = {"name", "category.name"}, primarySearch = true)
        @UISchema(label = "Buscar")
        private String searchTerm;

        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "Categoria")
        private Long categoryId;

        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "Status", icon = "verified_user")
        private ContractState status;

        public String getSearchTerm() { return searchTerm; }
        public void setSearchTerm(String searchTerm) { this.searchTerm = searchTerm; }
        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
        public ContractState getStatus() { return status; }
        public void setStatus(ContractState status) { this.status = status; }
    }

    @ApiResource(value = "/api/workforce/paired", resourceKey = "workforce.paired")
    public static class PairedController extends AbstractResourceQueryController<PairedResponseDto, Long, PairedFilterDto> {
        @Override protected BaseResourceQueryService<PairedResponseDto, Long, PairedFilterDto> getService() { return null; }
        @Override protected Long getResponseId(PairedResponseDto dto) { return null; }
    }
}
