package org.praxisplatform.uischema.e2e.fixture;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIntent;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.filter.specification.GenericSpecification;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.praxisplatform.uischema.service.base.AbstractReadOnlyResourceService;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.hateoas.Links;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

@Component
@Primary
class EmployeeResourceMapper implements ResourceMapper<EmployeeEntity, EmployeeResponseDTO, CreateEmployeeDTO, UpdateEmployeeDTO, Long> {

    @Override
    public EmployeeResponseDTO toResponse(EmployeeEntity entity) {
        EmployeeResponseDTO dto = new EmployeeResponseDTO();
        dto.setId(entity.getId());
        dto.setNome(entity.getNome());
        dto.setMatricula(entity.getMatricula());
        dto.setStatus(entity.getStatus().name());
        dto.setSalario(entity.getSalario());
        dto.setAdmissionDate(entity.getAdmissionDate());
        dto.setDepartmentId(entity.getDepartment().getId());
        dto.setDepartmentNome(entity.getDepartment().getNome());

        DepartmentSummaryDTO department = new DepartmentSummaryDTO();
        department.setId(entity.getDepartment().getId());
        department.setNome(entity.getDepartment().getNome());
        dto.setDepartment(department);
        return dto;
    }

    @Override
    public EmployeeEntity newEntity(CreateEmployeeDTO dto) {
        EmployeeEntity entity = new EmployeeEntity();
        entity.setNome(dto.getNome());
        entity.setMatricula(dto.getMatricula());
        entity.setStatus(dto.getStatus());
        entity.setSalario(dto.getSalario());
        entity.setAdmissionDate(dto.getAdmissionDate());
        entity.setDepartment(referenceDepartment(dto.getDepartmentId()));
        return entity;
    }

    @Override
    public void applyUpdate(EmployeeEntity entity, UpdateEmployeeDTO dto) {
        entity.setNome(dto.getNome());
        entity.setMatricula(dto.getMatricula());
        entity.setStatus(dto.getStatus());
        entity.setSalario(dto.getSalario());
        entity.setAdmissionDate(dto.getAdmissionDate());
        entity.setDepartment(referenceDepartment(dto.getDepartmentId()));
    }

    @Override
    public Long extractId(EmployeeEntity entity) {
        return entity.getId();
    }

    private DepartmentEntity referenceDepartment(Long departmentId) {
        DepartmentEntity department = new DepartmentEntity();
        department.setId(departmentId);
        return department;
    }
}

@Component
class DepartmentResourceMapper implements ResourceMapper<DepartmentEntity, DepartmentResponseDTO, CreateDepartmentDTO, UpdateDepartmentDTO, Long> {

    @Override
    public DepartmentResponseDTO toResponse(DepartmentEntity entity) {
        DepartmentResponseDTO dto = new DepartmentResponseDTO();
        dto.setId(entity.getId());
        dto.setNome(entity.getNome());
        dto.setCode(entity.getCode());
        return dto;
    }

    @Override
    public DepartmentEntity newEntity(CreateDepartmentDTO dto) {
        DepartmentEntity entity = new DepartmentEntity();
        entity.setNome(dto.getNome());
        entity.setCode(dto.getCode());
        return entity;
    }

    @Override
    public void applyUpdate(DepartmentEntity entity, UpdateDepartmentDTO dto) {
        entity.setNome(dto.getNome());
        entity.setCode(dto.getCode());
    }

    @Override
    public Long extractId(DepartmentEntity entity) {
        return entity.getId();
    }
}

@Component
class PayrollViewResourceMapper implements ResourceMapper<PayrollViewEntity, PayrollViewResponseDTO, Void, Void, Long> {

    @Override
    public PayrollViewResponseDTO toResponse(PayrollViewEntity entity) {
        PayrollViewResponseDTO dto = new PayrollViewResponseDTO();
        dto.setId(entity.getId());
        dto.setEmployeeNome(entity.getEmployeeNome());
        dto.setDepartmentNome(entity.getDepartmentNome());
        dto.setNetAmount(entity.getNetAmount());
        dto.setPayrollStatus(entity.getPayrollStatus());
        return dto;
    }

    @Override
    public PayrollViewEntity newEntity(Void dto) {
        throw new UnsupportedOperationException("Read-only fixture");
    }

    @Override
    public void applyUpdate(PayrollViewEntity entity, Void dto) {
        throw new UnsupportedOperationException("Read-only fixture");
    }

    @Override
    public Long extractId(PayrollViewEntity entity) {
        return entity.getId();
    }
}

@Service
class EmployeeService extends AbstractBaseResourceService<
        EmployeeEntity,
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    private static final OptionSourceRegistry EMPLOYEE_OPTION_SOURCES = OptionSourceRegistry.builder()
            .add(EmployeeEntity.class, new OptionSourceDescriptor(
                    "payrollProfile",
                    OptionSourceType.DISTINCT_DIMENSION,
                    "/employees",
                    "payrollProfile",
                    "payrollProfile",
                    "payrollProfileLabel",
                    "payrollProfile",
                    List.of("departmentId"),
                    new OptionSourcePolicy(true, true, "contains", 0, 25, 100, true, false, "label")
            ))
            .add(EmployeeEntity.class, new OptionSourceDescriptor(
                    "legacyDepartmentLookup",
                    OptionSourceType.RESOURCE_ENTITY,
                    "/employees",
                    null,
                    null,
                    null,
                    null,
                    List.of("departmentId"),
                    OptionSourcePolicy.defaults()
            ))
            .build();

    private static final StatsFieldRegistry EMPLOYEE_STATS_FIELDS = StatsFieldRegistry.builder()
            .categoricalGroupByBucket("status", "status")
            .temporalTimeSeriesField("admissionDate", "admissionDate")
            .numericHistogramMeasureField("salario", "salario")
            .build();

    private final EmployeeResourceMapper mapper;

    EmployeeService(EmployeeRepository repository, EmployeeResourceMapper mapper) {
        super(repository, EmployeeEntity.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<EmployeeEntity, EmployeeResponseDTO, CreateEmployeeDTO, UpdateEmployeeDTO, Long> getResourceMapper() {
        return mapper;
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.of("e2e");
    }

    @Override
    public StatsSupportMode getGroupByStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getTimeSeriesStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getDistributionStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return EMPLOYEE_STATS_FIELDS;
    }

    @Override
    public OptionSourceRegistry getOptionSourceRegistry() {
        return EMPLOYEE_OPTION_SOURCES;
    }

    @Override
    protected CursorPage<EmployeeEntity> filterEntitiesByCursor(
            EmployeeFilterDTO filter,
            Sort sort,
            String after,
            String before,
            int size
    ) {
        int requestedSize = Math.max(size, 1);
        List<EmployeeEntity> ordered = findFilteredEmployees(filter, effectiveSort(sort));
        if (ordered.isEmpty()) {
            return new CursorPage<>(List.of(), null, null, requestedSize);
        }

        int start = 0;
        if (after != null && !after.isBlank()) {
            start = decodeCursor(after) + 1;
        } else if (before != null && !before.isBlank()) {
            start = Math.max(0, decodeCursor(before) - requestedSize);
        }

        start = Math.min(Math.max(start, 0), ordered.size());
        int end = Math.min(start + requestedSize, ordered.size());

        String next = end < ordered.size() ? encodeCursor(end - 1) : null;
        String prev = start > 0 ? encodeCursor(start) : null;

        return new CursorPage<>(ordered.subList(start, end), next, prev, requestedSize);
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalLong locate(EmployeeFilterDTO filter, Sort sort, Long id) {
        List<EmployeeEntity> ordered = findFilteredEmployees(filter, effectiveSort(sort));
        for (int index = 0; index < ordered.size(); index++) {
            if (id.equals(ordered.get(index).getId())) {
                return OptionalLong.of(index);
            }
        }
        throw getNotFoundException();
    }

    @Transactional
    public EmployeeResponseDTO updateProfile(Long id, UpdateEmployeeProfileDTO dto) {
        EmployeeEntity existing = findEntityById(id);
        existing.setNome(dto.getNome());
        EmployeeEntity saved = getRepository().save(existing);
        if (getEntityManager() != null) {
            getEntityManager().flush();
            EmployeeEntity managed = getEntityManager().contains(saved) ? saved : getEntityManager().merge(saved);
            getEntityManager().refresh(managed);
            return mapper.toResponse(managed);
        }
        return mapper.toResponse(saved);
    }

    @Transactional
    public EmployeeResponseDTO approve(Long id, ApproveEmployeeDTO dto) {
        EmployeeEntity existing = findEntityById(id);
        existing.setStatus(EmployeeStatus.ACTIVE);
        EmployeeEntity saved = getRepository().save(existing);
        if (getEntityManager() != null) {
            getEntityManager().flush();
            EmployeeEntity managed = getEntityManager().contains(saved) ? saved : getEntityManager().merge(saved);
            getEntityManager().refresh(managed);
            return mapper.toResponse(managed);
        }
        return mapper.toResponse(saved);
    }

    @Transactional
    public BulkApproveEmployeesResultDTO bulkApprove(BulkApproveEmployeesDTO dto) {
        List<Long> requestedIds = dto.getEmployeeIds() == null ? List.of() : List.copyOf(dto.getEmployeeIds());
        List<EmployeeEntity> found = getRepository().findAllById(requestedIds);
        if (found.size() != requestedIds.size()) {
            throw getNotFoundException();
        }

        Map<Long, EmployeeEntity> byId = new LinkedHashMap<>();
        for (EmployeeEntity employee : found) {
            byId.put(employee.getId(), employee);
        }

        List<Long> approvedIds = requestedIds.stream()
                .map(byId::get)
                .peek(employee -> employee.setStatus(EmployeeStatus.ACTIVE))
                .map(EmployeeEntity::getId)
                .toList();

        getRepository().saveAll(byId.values());
        if (getEntityManager() != null) {
            getEntityManager().flush();
        }

        BulkApproveEmployeesResultDTO result = new BulkApproveEmployeesResultDTO();
        result.setApprovedCount(approvedIds.size());
        result.setApprovedEmployeeIds(approvedIds);
        return result;
    }

    private List<EmployeeEntity> findFilteredEmployees(EmployeeFilterDTO filter, Sort sort) {
        GenericSpecification<EmployeeEntity> specification = getSpecificationsBuilder().buildSpecification(
                filter,
                PageRequest.of(0, 1, sort)
        );
        return getRepository().findAll(specification.spec(), specification.pageable().getSort());
    }

    private Sort effectiveSort(Sort requestedSort) {
        if (requestedSort != null && requestedSort.isSorted()) {
            return requestedSort;
        }
        return getDefaultSort();
    }

    private String encodeCursor(int position) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("offset:" + position).getBytes(StandardCharsets.UTF_8));
    }

    private int decodeCursor(String cursor) {
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        if (!decoded.startsWith("offset:")) {
            throw new IllegalArgumentException("Unsupported cursor format");
        }
        return Integer.parseInt(decoded.substring("offset:".length()));
    }
}

@Service
class DepartmentService extends AbstractBaseResourceService<
        DepartmentEntity,
        DepartmentResponseDTO,
        Long,
        DepartmentFilterDTO,
        CreateDepartmentDTO,
        UpdateDepartmentDTO> {

    private final DepartmentResourceMapper mapper;

    DepartmentService(DepartmentRepository repository, DepartmentResourceMapper mapper) {
        super(repository, DepartmentEntity.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<DepartmentEntity, DepartmentResponseDTO, CreateDepartmentDTO, UpdateDepartmentDTO, Long> getResourceMapper() {
        return mapper;
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.of("e2e");
    }
}

@Service
class PayrollViewService extends AbstractReadOnlyResourceService<
        PayrollViewEntity,
        PayrollViewResponseDTO,
        Long,
        PayrollViewFilterDTO> {

    private final PayrollViewResourceMapper mapper;

    PayrollViewService(PayrollViewRepository repository, PayrollViewResourceMapper mapper) {
        super(repository, PayrollViewEntity.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<PayrollViewEntity, PayrollViewResponseDTO, Void, Void, Long> getResourceMapper() {
        return mapper;
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.of("e2e");
    }
}

@org.springframework.web.bind.annotation.RestController
@ApiResource(value = "/employees", resourceKey = "human-resources.employees")
@ApiGroup("human-resources")
class EmployeeController extends org.praxisplatform.uischema.controller.base.AbstractResourceController<
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    private final EmployeeService service;

    EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @Override
    protected EmployeeService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(EmployeeResponseDTO dto) {
        return dto.getId();
    }

    @PatchMapping("/{id}/profile")
    @Operation(summary = "Atualizar perfil do funcionario")
    @UiSurface(
            id = "profile",
            kind = SurfaceKind.PARTIAL_FORM,
            scope = SurfaceScope.ITEM,
            title = "Editar perfil",
            description = "Atualiza apenas os dados de perfil do funcionario",
            intent = "profile",
            order = 50,
            requiredAuthorities = {"employee:profile:update"},
            allowedStates = {"ACTIVE"},
            tags = {"profile"}
    )
    @ResourceIntent(id = "employee-profile", title = "Editar perfil", description = "Atualiza apenas os dados de perfil do funcionario")
    public ResponseEntity<RestApiResponse<EmployeeResponseDTO>> updateProfile(
            @PathVariable Long id,
            @Valid @RequestBody UpdateEmployeeProfileDTO dto
    ) {
        EmployeeResponseDTO updated = service.updateProfile(id, dto);
        Links links = Links.of(
                linkToSelf(id),
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToUpdate(id),
                linkToDelete(id),
                linkToUiSchema("/{id}/profile", "patch", "request")
        );
        return withVersion(ResponseEntity.ok(), RestApiResponse.success(updated, hateoasOrNull(links)));
    }

    @PostMapping("/{id}/actions/approve")
    @Operation(summary = "Aprovar funcionario")
    @WorkflowAction(
            id = "approve",
            title = "Aprovar funcionario",
            description = "Executa a aprovacao final do funcionario",
            scope = ActionScope.ITEM,
            order = 100,
            successMessage = "Funcionario aprovado",
            requiredAuthorities = {"employee:approve"},
            allowedStates = {"INACTIVE"},
            tags = {"workflow", "approval"}
    )
    public ResponseEntity<RestApiResponse<EmployeeResponseDTO>> approve(
            @PathVariable Long id,
            @Valid @RequestBody ApproveEmployeeDTO dto
    ) {
        EmployeeResponseDTO approved = service.approve(id, dto);
        Links links = Links.of(
                linkToSelf(id),
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToUiSchema("/{id}/actions/approve", "post", "request"),
                linkToUiSchema("/{id}/actions/approve", "post", "response")
        );
        return withVersion(ResponseEntity.ok(), RestApiResponse.success(approved, hateoasOrNull(links)));
    }

    @PostMapping("/actions/bulk-approve")
    @Operation(summary = "Aprovar funcionarios em lote")
    @WorkflowAction(
            id = "bulk-approve",
            title = "Aprovar funcionarios em lote",
            description = "Executa a aprovacao em lote de funcionarios selecionados",
            scope = ActionScope.COLLECTION,
            order = 90,
            successMessage = "Funcionarios aprovados",
            requiredAuthorities = {"employee:bulk-approve"},
            tags = {"workflow", "bulk-approval"}
    )
    public ResponseEntity<RestApiResponse<BulkApproveEmployeesResultDTO>> bulkApprove(
            @Valid @RequestBody BulkApproveEmployeesDTO dto
    ) {
        BulkApproveEmployeesResultDTO result = service.bulkApprove(dto);
        Links links = Links.of(
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToUiSchema("/actions/bulk-approve", "post", "request"),
                linkToUiSchema("/actions/bulk-approve", "post", "response")
        );
        return withVersion(ResponseEntity.ok(), RestApiResponse.success(result, hateoasOrNull(links)));
    }
}

@org.springframework.web.bind.annotation.RestController
@ApiResource(value = "/departments", resourceKey = "human-resources.departments")
@ApiGroup("human-resources")
class DepartmentController extends org.praxisplatform.uischema.controller.base.AbstractResourceController<
        DepartmentResponseDTO,
        Long,
        DepartmentFilterDTO,
        CreateDepartmentDTO,
        UpdateDepartmentDTO> {

    private final DepartmentService service;

    DepartmentController(DepartmentService service) {
        this.service = service;
    }

    @Override
    protected DepartmentService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(DepartmentResponseDTO dto) {
        return dto.getId();
    }
}

@org.springframework.web.bind.annotation.RestController
@ApiResource(value = "/payroll-view", resourceKey = "human-resources.payroll-view")
@ApiGroup("human-resources")
class PayrollViewController extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
        PayrollViewResponseDTO,
        Long,
        PayrollViewFilterDTO> {

    private final PayrollViewService service;

    PayrollViewController(PayrollViewService service) {
        this.service = service;
    }

    @Override
    protected PayrollViewService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(PayrollViewResponseDTO dto) {
        return dto.getId();
    }
}

