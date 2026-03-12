package org.praxisplatform.uischema.controller.base;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseCrudService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AbstractCrudControllerJpaWriteIntegrationTest.TestConfig.class,
        properties = {
                "spring.jpa.open-in-view=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:starter-write-it;DB_CLOSE_DELAY=-1",
                "spring.datasource.driverClassName=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
@AutoConfigureMockMvc
class AbstractCrudControllerJpaWriteIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DepartmentRepository departmentRepository;

    @Autowired
    EmployeeRepository employeeRepository;

    private Long hrId;
    private Long opsId;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
        departmentRepository.deleteAll();

        Department hr = new Department();
        hr.setNome("Human Resources");
        hrId = departmentRepository.save(hr).getId();

        Department ops = new Department();
        ops.setNome("Operations");
        opsId = departmentRepository.save(ops).getId();
    }

    @Test
    void createReturnsCanonicalDtoWithLazyAssociationResolved() throws Exception {
        MvcResult result = mockMvc.perform(post("/integration-employees")
                        .contentType("application/json")
                        .content("""
                                {
                                  "nome": "Alice",
                                  "departmentId": %d
                                }
                                """.formatted(hrId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nome").value("Alice"))
                .andExpect(jsonPath("$.data.departmentId").value(hrId))
                .andExpect(jsonPath("$.data.departmentNome").value("Human Resources"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Response body does not contain employee id: " + body);
        }
        String employeeId = matcher.group(1);
        String location = result.getResponse().getHeader("Location");
        if (!("http://localhost/integration-employees/" + employeeId).equals(location)) {
            throw new AssertionError("Location header does not match returned id. location=" + location + ", id=" + employeeId);
        }
    }

    @Test
    void updateReturnsCanonicalDtoWithLazyAssociationResolved() throws Exception {
        Employee employee = new Employee();
        employee.setNome("Alice");
        employee.setDepartment(departmentRepository.findById(hrId).orElseThrow());
        Long employeeId = employeeRepository.save(employee).getId();

        mockMvc.perform(put("/integration-employees/{id}", employeeId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "id": %d,
                                  "nome": "Alice Updated",
                                  "departmentId": %d
                                }
                                """.formatted(employeeId, opsId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(employeeId))
                .andExpect(jsonPath("$.data.nome").value("Alice Updated"))
                .andExpect(jsonPath("$.data.departmentId").value(opsId))
                .andExpect(jsonPath("$.data.departmentNome").value("Operations"));
    }

    @EnableAutoConfiguration
    @EnableJpaRepositories(considerNestedRepositories = true, basePackageClasses = AbstractCrudControllerJpaWriteIntegrationTest.class)
    @Import({EmployeeController.class, EmployeeService.class, EmployeeMapper.class})
    static class TestConfig {
    }
}

@Entity
@Table(name = "it_departments")
class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nome;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }
}

@Entity
@Table(name = "it_employees")
class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }
}

class EmployeeDto {

    private Long id;
    private String nome;
    private Long departmentId;
    private String departmentNome;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public String getDepartmentNome() {
        return departmentNome;
    }

    public void setDepartmentNome(String departmentNome) {
        this.departmentNome = departmentNome;
    }
}

class EmployeeFilterDto implements GenericFilterDTO {
}

@org.springframework.stereotype.Component
class EmployeeMapper {

    EmployeeDto toDto(Employee entity) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(entity.getId());
        dto.setNome(entity.getNome());
        dto.setDepartmentId(entity.getDepartment().getId());
        dto.setDepartmentNome(entity.getDepartment().getNome());
        return dto;
    }

    Employee toEntity(EmployeeDto dto) {
        Employee entity = new Employee();
        entity.setId(dto.getId());
        entity.setNome(dto.getNome());
        entity.setDepartment(departmentFromId(dto.getDepartmentId()));
        return entity;
    }

    void updateEntity(Employee source, Employee target) {
        target.setNome(source.getNome());
        target.setDepartment(source.getDepartment());
    }

    private Department departmentFromId(Long id) {
        if (id == null) {
            return null;
        }
        Department department = new Department();
        department.setId(id);
        return department;
    }
}

interface DepartmentRepository extends BaseCrudRepository<Department, Long> {
}

interface EmployeeRepository extends BaseCrudRepository<Employee, Long> {
}

@org.springframework.stereotype.Service
class EmployeeService extends AbstractBaseCrudService<Employee, EmployeeDto, Long, EmployeeFilterDto> {

    private final EmployeeMapper mapper;

    EmployeeService(EmployeeRepository repository, EmployeeMapper mapper) {
        super(repository, Employee.class);
        this.mapper = mapper;
    }

    @Override
    public Employee mergeUpdate(Employee existing, Employee update) {
        mapper.updateEntity(update, existing);
        return existing;
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.of("it");
    }

    @Override
    public Long extractId(Employee entity) {
        return entity.getId();
    }
}

@ApiResource("/integration-employees")
class EmployeeController extends AbstractCrudController<Employee, EmployeeDto, Long, EmployeeFilterDto> {

    private final EmployeeService service;
    private final EmployeeMapper mapper;

    EmployeeController(EmployeeService service, EmployeeMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    protected EmployeeService getService() {
        return service;
    }

    @Override
    protected EmployeeDto toDto(Employee entity) {
        return mapper.toDto(entity);
    }

    @Override
    protected Employee toEntity(EmployeeDto dto) {
        return mapper.toEntity(dto);
    }

    @Override
    protected Long getEntityId(Employee entity) {
        return entity.getId();
    }

    @Override
    protected Long getDtoId(EmployeeDto dto) {
        return dto.getId();
    }
}
