package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DraftCleared;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceReference;
import org.praxisplatform.uischema.annotation.ResourceState;
import org.praxisplatform.uischema.annotation.UiOption;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionScopeProvider;
import org.praxisplatform.uischema.controller.base.AbstractResourceController;
import org.praxisplatform.uischema.dto.PartialUpdateInput;
import org.praxisplatform.uischema.exporting.CollectionExportField;
import org.praxisplatform.uischema.exporting.CollectionExportFormat;
import org.praxisplatform.uischema.exporting.CollectionExportLocalization;
import org.praxisplatform.uischema.exporting.CollectionExportRequest;
import org.praxisplatform.uischema.exporting.CollectionExportScope;
import org.praxisplatform.uischema.exporting.CsvCollectionExportEngine;
import org.praxisplatform.uischema.exporting.DefaultCollectionExportExecutor;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.id.CompositeResourceId;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.option.PraxisUiOptionEnum;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhaseBServiceLifecycleAndExportTest {

    @Test
    void resourceStateEnforcesInitialAndPutMutableStatesWith409AndAllowsPartialPatchBypass() {
        @SuppressWarnings("unchecked")
        BaseCrudRepository<ContractEntity, Long> repo = mock(BaseCrudRepository.class);
        when(repo.save(any(ContractEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ContractEntity approved = new ContractEntity(1L, "APPROVED", "Contract A", 0L, null, new EmbedAddress("SP"));
        ContractEntity draft = new ContractEntity(2L, "DRAFT", "Contract B", 0L, null, new EmbedAddress("RJ"));
        when(repo.findById(1L)).thenReturn(Optional.of(approved));
        when(repo.findById(2L)).thenReturn(Optional.of(draft));

        ContractService service = new ContractService(repo);
        ActionDefinitionRegistry actionRegistry = new ActionDefinitionRegistry() {
            @Override
            public List<ActionDefinition> findByResourceKey(String resourceKey) {
                return List.of(
                        new ActionDefinition(
                                "approve", resourceKey, "/api/workforce/contracts", "workforce",
                                ActionScope.ITEM, "Approve", "", null, null, null, 1, "",
                                List.of(), List.of("DRAFT"), List.of(), null,
                                "CONTRACT_EXECUTION", "Art. 7 V", null, null, List.of(), "must-be-draft"
                        )
                );
            }

            @Override
            public List<ActionDefinition> findByGroup(String group) {
                return List.of();
            }
        };
        ReflectionTestUtils.setField(service, "actionDefinitionRegistryProvider", providerOf(actionRegistry));

        // 1. Initial state validation (409 on non-DRAFT)
        assertThatThrownBy(() -> service.create(new ContractCreateDto("ACTIVE", "Bad", "REF-1", new EmbedAddressUpdate("SP"))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(service.create(new ContractCreateDto("DRAFT", "Good", "REF-2", new EmbedAddressUpdate("SP"))).body().title)
                .isEqualTo("Good");

        // 2. PUT mutable state enforcement (409 on APPROVED, succeeds on DRAFT)
        assertThatThrownBy(() -> service.update(1L, new ContractUpdateDto("APPROVED", "Attempt", new EmbedAddressUpdate("SP"), "ignored")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(service.update(2L, new ContractUpdateDto("DRAFT", "Draft Updated", new EmbedAddressUpdate("MG"), "ignored")).title)
                .isEqualTo("Draft Updated");

        // 3. applyVersionedPatch bypasses putMutableStates check
        ReflectionTestUtils.setField(service, "resourceObjectMapper", new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        ContractPatch patch = new ContractPatch();
        patch.setTitle("Patched While Approved");
        ResourceVersionEtagService directEtags = new ResourceVersionEtagService("phase-b-test-secret-0123456789abcdef");
        var precondition = new org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition<>(
                directEtags,
                directEtags.create(org.praxisplatform.uischema.concurrency.ResourceVersionScope.GLOBAL, "workforce.contracts", 1L, 0L),
                org.praxisplatform.uischema.concurrency.ResourceVersionScope.GLOBAL,
                "workforce.contracts",
                1L
        );
        assertThat(service.applyVersionedPatch(1L, patch, precondition).body().title)
                .isEqualTo("Patched While Approved");

        // 4. Auto-read @ResourceState & ActionDefinitionRegistry metadata population via putIfAbsent
        ResourceStateSnapshot snapshot = service.findResourceStateSnapshot(1L).orElseThrow();
        assertThat(snapshot.state()).isEqualTo("APPROVED");
        assertThat(snapshot.metadata())
                .containsEntry("action.approve.allowed", false)
                .containsEntry("action.approve.reason", "must-be-draft")
                .containsEntry("action.approve.legalBasis", "Art. 7 V");
    }

    @Test
    void duplicateDraftClearedFieldsComputeOptionLabelRequireScopedReferenceAndSafeSnapshotWork() {
        @SuppressWarnings("unchecked")
        BaseCrudRepository<ContractEntity, Long> repo = mock(BaseCrudRepository.class);
        LazyDepartmentProxy uninitializedParent = new LazyDepartmentProxy();
        ContractEntity existing = new ContractEntity(10L, "DRAFT", "Master Agreement", 0L, uninitializedParent, new EmbedAddress("Curitiba"));
        existing.tags.add("urgent");
        existing.attributes.put("tier", "gold");
        when(repo.findById(10L)).thenReturn(Optional.of(existing));

        ContractService service = new ContractService(repo);

        // 1. duplicateDraftFromEntity clears @DraftCleared on both POJO and record without saving
        ContractCreateDto pojoDraft = service.duplicateDraftFromEntity(10L);
        assertThat(pojoDraft.title).isEqualTo("Master Agreement");
        assertThat(pojoDraft.externalRef).isNull();
        DraftRecord recordDraft = service.duplicateDraftFromEntity(10L, DraftRecord.class);
        assertThat(recordDraft.title()).isEqualTo("Master Agreement");
        assertThat(recordDraft.externalRef()).isNull();
        verify(repo, never()).save(any());

        // 2. computeOptionLabel uses @ResourceIdentity.titleField() before legacy getters
        assertThat(service.computeOptionLabel(existing)).isEqualTo("Master Agreement");

        // 3. snapshotScalarAndEmbeddedState skips lazy @ManyToOne proxy and deep-copies containers & @Embedded
        ContractEntity snap = service.snapshotBeforeMutation(existing);
        assertThat(snap.parent).isNull();
        existing.tags.add("mutated");
        existing.attributes.put("tier", "silver");
        existing.address.city = "Mutated";
        assertThat(snap.tags).containsExactly("urgent");
        assertThat(snap.attributes).containsEntry("tier", "gold");
        assertThat(snap.address.city).isEqualTo("Curitiba");

        // 4. toBulkUpdate ignores nested ResponseDTO-only read-only fields under FAIL_ON_UNKNOWN_PROPERTIES=true
        ObjectMapper strictMapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var updateType = strictMapper.constructType(ContractUpdateDto.class);
        ContractUpdateDto projected = service.toBulkUpdate(existing, strictMapper, updateType);
        assertThat(projected.address.city).isEqualTo("Mutated");
        assertThat(projected.serverReadOnly).isNull();

        // 5. requireScopedReference enforces target scope & fails closed with 400 when missing/inaccessible
        @SuppressWarnings("unchecked")
        BaseCrudRepository<DeptEntity, Long> deptRepo = mock(BaseCrudRepository.class);
        DeptService deptService = new DeptService(deptRepo);
        DeptEntity dept = new DeptEntity(7L, "Engineering");
        when(deptRepo.findOne(any(Specification.class))).thenReturn(Optional.of(dept)).thenReturn(Optional.empty());

        assertThat(service.lookupDept(deptService, 7L)).isSameAs(dept);
        assertThatThrownBy(() -> service.lookupDept(deptService, 999L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.lookupDept(deptService, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void scopedBatchReferenceHydratorResolvesCompositeForeignKeysInSingleBatch() {
        @SuppressWarnings("unchecked")
        AbstractBaseQueryResourceService<ContractEntity, ContractResponseDto, CompositeAssignmentId, GenericFilterDTO> assignmentService =
                mock(AbstractBaseQueryResourceService.class);
        CompositeAssignmentId compOne = new CompositeAssignmentId(10L, 1);
        CompositeAssignmentId compTwo = new CompositeAssignmentId(10L, 2);
        when(assignmentService.resolveScopedTitlesByIds(any())).thenReturn(Map.of(compOne, "Assignment One"));

        ScopedBatchReferenceHydrator hydrator = new ScopedBatchReferenceHydrator(
                key -> "workforce.assignments".equals(key) ? Optional.of(assignmentService) : Optional.empty()
        );
        AssignmentHolderDto row1 = new AssignmentHolderDto("10:1");
        AssignmentHolderDto row2 = new AssignmentHolderDto("10:2");
        hydrator.hydrate(List.of(row1, row2));

        assertThat(row1.assignmentTitle).isEqualTo("Assignment One");
        assertThat(row2.assignmentTitle).isNull();
        verify(assignmentService, times(1)).resolveScopedTitlesByIds(any());
    }

    @Test
    void executeVersionedItemCommandDirectAndPatchDirectReturnUnwrappedBodyWithEtagAndMapRejections() {
        ResourceVersionEtagService etags = new ResourceVersionEtagService("direct-command-secret-0123456789abc");
        CapabilityService capabilityService = mock(CapabilityService.class);
        when(capabilityService.itemOperationAvailability("workforce.contracts", "/api/workforce/contracts", "approve", 1L))
                .thenReturn(AvailabilityDecision.allowAll());
        when(capabilityService.itemOperationAvailability("workforce.contracts", "/api/workforce/contracts", "blocked", 1L))
                .thenReturn(AvailabilityDecision.deny("locked-by-policy", Map.of()));
        when(capabilityService.itemOperationAvailability("workforce.contracts", "/api/workforce/contracts", "quickPatch", 1L))
                .thenReturn(AvailabilityDecision.allowAll());

        DirectTestController controller = new DirectTestController();
        ReflectionTestUtils.setField(controller, "resourceVersionEtagService", etags);
        ReflectionTestUtils.setField(controller, "resourceVersionScopeProvider", (ResourceVersionScopeProvider) () -> org.praxisplatform.uischema.concurrency.ResourceVersionScope.GLOBAL);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilityService);

        String ifMatch = etags.create(org.praxisplatform.uischema.concurrency.ResourceVersionScope.GLOBAL, "workforce.contracts", 1L, 0L);

        ResponseEntity<CustomCommandResult> directCommand = controller.invokeCommand(1L, ifMatch);
        assertThat(directCommand.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(directCommand.getBody()).isEqualTo(new CustomCommandResult("OK", 5L));
        assertThat(directCommand.getHeaders().getETag()).isNotBlank();

        ResponseEntity<ContractResponseDto> directPatch = controller.invokePatch(1L, ifMatch);
        assertThat(directPatch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(directPatch.getBody().title).isEqualTo("Direct Patched");
        assertThat(directPatch.getHeaders().getETag()).isNotBlank();

        assertThatThrownBy(() -> controller.invokeBlocked(1L, ifMatch))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("locked-by-policy");
    }

    @Test
    void collectionExportSupportsNumberPercentEnumDefaultsAndTableHiddenToggle() {
        List<CollectionExportField> allFields = ResourceExportSupport.allExportableFields(ExportMetricsDto.class);
        CollectionExportField totalField = field(allFields, "totalCount");
        CollectionExportField rateField = field(allFields, "completionRate");
        CollectionExportField modeField = field(allFields, "mode");
        CollectionExportField tierField = field(allFields, "tier");
        CollectionExportField activeField = field(allFields, "active");
        CollectionExportField hiddenNotesField = field(allFields, "internalNotes");

        assertThat(totalField.presentation().semanticType()).isEqualTo("number");
        assertThat(rateField.presentation().semanticType()).isEqualTo("percent");
        assertThat(modeField.presentation().semanticType()).isEqualTo("enum");
        assertThat(modeField.presentation().valueLabels()).containsEntry("AUTO", "Automatico");
        assertThat(tierField.presentation().valueLabels()).containsEntry("BASIC", "Basico");
        assertThat(activeField.presentation().trueLabel()).isEqualTo("Sim");
        assertThat(activeField.presentation().falseLabel()).isEqualTo("N\u00E3o");
        assertThat(activeField.presentation().locale()).isEqualTo("pt-BR");
        assertThat(activeField.presentation().timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(hiddenNotesField.visible()).isFalse();
        assertThat(hiddenNotesField.exportable()).isTrue();

        DefaultCollectionExportExecutor executor = new DefaultCollectionExportExecutor(
                List.of(new CsvCollectionExportEngine()));
        var row = new ExportMetricsDto(1250, new BigDecimal("12.5"), ModeOption.AUTO, "BASIC", false, "Note 1");
        var request = new CollectionExportRequest<GenericFilterDTO>(
                "table", "metrics", "/api/workforce/metrics",
                CollectionExportFormat.CSV, CollectionExportScope.ALL, null,
                allFields.stream()
                        .map(f -> new CollectionExportField(f.key(), f.label(), true, true, f.type(), f.valuePath(), f.format(), f.presentation()))
                        .toList(),
                null, null, null, Map.of(), true, true, 10, null, null,
                new CollectionExportLocalization("pt-BR", "America/Sao_Paulo"), Map.of()
        );
        var csvResult = executor.export(request, List.of(row), allFields,
                (r, f) -> ResourceKeysetSupport.read(r, f.key()), Map.of());
        String csv = new String(csvResult.content(), StandardCharsets.UTF_8);
        assertThat(csv)
                .contains("1.250")
                .contains("12,5%")
                .contains("Automatico")
                .contains("Basico")
                .contains("N\u00E3o")
                .contains("Note 1");

        // Toggle includeTableHiddenExportFieldsByDefault=false drops tableHidden from governedExportFields()
        @SuppressWarnings("unchecked")
        BaseCrudRepository<ContractEntity, Long> repo = mock(BaseCrudRepository.class);
        ContractService contractService = new ContractService(repo);
        assertThat(contractService.governedExportFields()).extracting(CollectionExportField::key).contains("secondaryNote");
        contractService.setIncludeTableHiddenExportFieldsByDefault(false);
        assertThat(contractService.governedExportFields()).extracting(CollectionExportField::key).doesNotContain("secondaryNote");
    }

    private static CollectionExportField field(List<CollectionExportField> fields, String key) {
        return fields.stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return instance; }
            @Override public T getIfAvailable() { return instance; }
            @Override public T getIfUnique() { return instance; }
            @Override public T getObject() { return instance; }
        };
    }

    public static class LazyDepartmentProxy extends DeptEntity implements HibernateProxy {
        private final LazyInitializer initializer = mock(LazyInitializer.class);

        public LazyDepartmentProxy() {
            when(initializer.isUninitialized()).thenReturn(true);
        }

        @Override public Object writeReplace() { return this; }
        @Override public LazyInitializer getHibernateLazyInitializer() { return initializer; }
    }

    @Embeddable
    public static class EmbedAddress {
        public String city;

        public EmbedAddress() {}
        public EmbedAddress(String city) { this.city = city; }
    }

    public static class EmbedAddressResponse {
        public String city;
        public String readOnlyDisplay = "City-ReadOnly";

        public EmbedAddressResponse() {}
        public EmbedAddressResponse(String city) { this.city = city; }
    }

    public static class EmbedAddressUpdate {
        public String city;

        public EmbedAddressUpdate() {}
        public EmbedAddressUpdate(String city) { this.city = city; }
    }

    public static class DeptEntity {
        @Id public Long id;
        public String name;
        public DeptEntity() {}
        public DeptEntity(Long id, String name) { this.id = id; this.name = name; }
    }

    @ResourceIdentity(keyField = "id", titleField = "title")
    public static class ContractEntity {
        @Id public Long id;
        @ResourceState(initialStates = {"DRAFT"}, putMutableStates = {"DRAFT", "SUSPENDED"})
        public String status;
        public String title;
        @Version public Long revision;
        @ManyToOne(fetch = FetchType.LAZY) public DeptEntity parent;
        @Embedded public EmbedAddress address;
        public List<String> tags = new ArrayList<>();
        public Map<String, String> attributes = new LinkedHashMap<>();

        public ContractEntity() {}
        public ContractEntity(Long id, String status, String title, Long revision, DeptEntity parent, EmbedAddress address) {
            this.id = id; this.status = status; this.title = title; this.revision = revision; this.parent = parent; this.address = address;
        }
    }

    public static class ContractResponseDto {
        @UISchema(label = "ID", order = 1) public Long id;
        @UISchema(label = "Status", order = 2) public String status;
        @UISchema(label = "Title", order = 3) public String title;
        public EmbedAddressResponse address;
        @UISchema(label = "Secondary", order = 4, tableHidden = true) public String secondaryNote = "sec";

        public ContractResponseDto() {}
        public ContractResponseDto(Long id, String status, String title, EmbedAddressResponse address) {
            this.id = id; this.status = status; this.title = title; this.address = address;
        }
    }

    public static class ContractCreateDto {
        public String status;
        public String title;
        @DraftCleared public String externalRef = "DEFAULT-SHOULD-BE-CLEARED";
        public EmbedAddressUpdate address;

        public ContractCreateDto() {}
        public ContractCreateDto(String status, String title, String externalRef, EmbedAddressUpdate address) {
            this.status = status; this.title = title; this.externalRef = externalRef; this.address = address;
        }
    }

    public record DraftRecord(String status, String title, @DraftCleared String externalRef) {}

    public static class ContractUpdateDto {
        public String status;
        public String title;
        public EmbedAddressUpdate address;
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) public String serverReadOnly;

        public ContractUpdateDto() {}
        public ContractUpdateDto(String status, String title, EmbedAddressUpdate address, String serverReadOnly) {
            this.status = status; this.title = title; this.address = address; this.serverReadOnly = serverReadOnly;
        }
    }

    public static class ContractPatch extends PartialUpdateInput {
        private String title;
        public String getTitle() { return title; }
        public void setTitle(String title) { supplied("title"); this.title = title; }
    }

    static class DeptService extends AbstractBaseQueryResourceService<DeptEntity, ContractResponseDto, Long, GenericFilterDTO> {
        DeptService(BaseCrudRepository<DeptEntity, Long> repo) {
            super(repo, DeptEntity.class);
        }

        @Override
        protected ResourceMapper<DeptEntity, ContractResponseDto, ?, ?, Long> getResourceMapper() {
            return null;
        }
    }

    @org.springframework.context.annotation.Profile("phase-b-unit-only")
    @ApiResource(value = "/api/workforce/contracts", resourceKey = "workforce.contracts")
    static class ContractService extends AbstractBaseResourceService<ContractEntity, ContractResponseDto, Long, GenericFilterDTO, ContractCreateDto, ContractUpdateDto> {
        private final ResourceMapper<ContractEntity, ContractResponseDto, ContractCreateDto, ContractUpdateDto, Long> mapper = new ResourceMapper<>() {
            @Override
            public ContractResponseDto toResponse(ContractEntity e) {
                return new ContractResponseDto(e.id, e.status, e.title,
                        e.address != null ? new EmbedAddressResponse(e.address.city) : null);
            }

            @Override
            public ContractEntity newEntity(ContractCreateDto dto) {
                return new ContractEntity(50L, dto.status, dto.title, 0L, null,
                        dto.address != null ? new EmbedAddress(dto.address.city) : null);
            }

            @Override
            public void applyUpdate(ContractEntity e, ContractUpdateDto dto) {
                e.status = dto.status;
                e.title = dto.title;
                if (dto.address != null) {
                    e.address = new EmbedAddress(dto.address.city);
                }
            }

            @Override
            public Long extractId(ContractEntity e) {
                return e.id;
            }
        };

        ContractService(BaseCrudRepository<ContractEntity, Long> repo) {
            super(repo, ContractEntity.class, "id");
        }

        DeptEntity lookupDept(DeptService deptService, Long deptId) {
            return requireScopedReference(deptService, deptId, "departmentId");
        }

        @Override
        protected ResourceMapper<ContractEntity, ContractResponseDto, ContractCreateDto, ContractUpdateDto, Long> getResourceMapper() {
            return mapper;
        }
    }

    public static final class CompositeAssignmentId implements CompositeResourceId {
        public Long companyId;
        public Integer seq;

        public CompositeAssignmentId() {}
        public CompositeAssignmentId(Long companyId, Integer seq) { this.companyId = companyId; this.seq = seq; }

        @Override
        public boolean equals(Object o) {
            return o instanceof CompositeAssignmentId other && Objects.equals(companyId, other.companyId) && Objects.equals(seq, other.seq);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyId, seq);
        }
    }

    public static class AssignmentHolderDto {
        public String assignmentCode;
        @ResourceReference(resourceKey = "workforce.assignments", valueField = "assignmentCode")
        public String assignmentTitle;

        public AssignmentHolderDto(String assignmentCode) { this.assignmentCode = assignmentCode; }
    }

    public record CustomCommandResult(String status, long updatedCount) {}

    @org.springframework.context.annotation.Profile("phase-b-unit-only")
    @ApiResource(value = "/api/workforce/contracts", resourceKey = "workforce.contracts")
    static class DirectTestController extends AbstractResourceController<ContractResponseDto, Long, GenericFilterDTO, ContractCreateDto, ContractUpdateDto> {
        static { CompositeIdSupport.registerCompositeIdType(CompositeAssignmentId.class); }

        @SuppressWarnings("unchecked")
        private final BaseResourceService<ContractResponseDto, Long, GenericFilterDTO, ContractCreateDto, ContractUpdateDto> service =
                mock(BaseResourceService.class);

        {
            when(service.getDatasetVersion()).thenReturn(Optional.empty());
        }

        @Override
        protected String getBasePath() {
            return "/api/workforce/contracts";
        }

        @Override
        protected BaseResourceService<ContractResponseDto, Long, GenericFilterDTO, ContractCreateDto, ContractUpdateDto> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(ContractResponseDto dto) {
            return dto.id;
        }

        ResponseEntity<CustomCommandResult> invokeCommand(Long id, String ifMatch) {
            return executeVersionedItemCommandDirect("approve", id, ifMatch, null, null, CustomCommandResult.class,
                    pre -> new ResourceRepresentationResult<>(new CustomCommandResult("OK", 5L), OptionalLong.of(1L)));
        }

        ResponseEntity<ContractResponseDto> invokePatch(Long id, String ifMatch) {
            return executeVersionedItemPatchDirect("quickPatch", id, ifMatch,
                    pre -> new ResourceRepresentationResult<>(
                            new ContractResponseDto(id, "APPROVED", "Direct Patched", null), OptionalLong.of(1L)));
        }

        ResponseEntity<CustomCommandResult> invokeBlocked(Long id, String ifMatch) {
            return executeVersionedItemCommandDirect("blocked", id, ifMatch, null, null, CustomCommandResult.class,
                    pre -> new ResourceRepresentationResult<>(new CustomCommandResult("NEVER", 0L), OptionalLong.of(1L)));
        }
    }

    public enum ModeOption implements PraxisUiOptionEnum {
        AUTO("Automatico"), MANUAL("Manual");
        private final String label;
        ModeOption(String label) { this.label = label; }
        @Override public String label() { return label; }
    }

    public static class ExportMetricsDto {
        @UISchema(label = "Total", numericFormat = NumericFormat.INTEGER, order = 1)
        public Integer totalCount;
        @UISchema(label = "Rate", numericFormat = NumericFormat.PERCENT, order = 2)
        public BigDecimal completionRate;
        @UISchema(label = "Mode", optionEnum = ModeOption.class, order = 3)
        public ModeOption mode;
        @UISchema(label = "Tier", optionItems = {@UiOption(value = "BASIC", label = "Basico")}, order = 4)
        public String tier;
        @UISchema(label = "Active", order = 5)
        public boolean active;
        @UISchema(label = "Internal Notes", order = 6, tableHidden = true)
        public String internalNotes;

        public ExportMetricsDto(Integer totalCount, BigDecimal completionRate, ModeOption mode,
                                String tier, boolean active, String internalNotes) {
            this.totalCount = totalCount; this.completionRate = completionRate; this.mode = mode;
            this.tier = tier; this.active = active; this.internalNotes = internalNotes;
        }
    }
}
