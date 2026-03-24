package org.praxisplatform.uischema.controller.base;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.options.OptionSourceEligibility;
import org.praxisplatform.uischema.options.service.jpa.JpaOptionSourceQueryExecutor;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseCrudService;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AbstractCrudControllerOptionSourceIntegrationTest.TestConfig.class,
        properties = {
                "spring.jpa.open-in-view=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:starter-optionsource-it;DB_CLOSE_DELAY=-1",
                "spring.datasource.driverClassName=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
@AutoConfigureMockMvc
class AbstractCrudControllerOptionSourceIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    OptionEmployeeRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.save(newEmployee("A", "Executive", "EXEC", "0-10%"));
        repository.save(newEmployee("A", "Specialist", "SPEC", "10-20%"));
        repository.save(newEmployee("A", "Executive", "EXEC", "20%+"));
        repository.save(newEmployee("A", null, null, null));
        repository.save(newEmployee("B", "Operational", "OPS", "0-10%"));
    }

    @Test
    void returnsDistinctOptionsAndExcludesSelfFilter() throws Exception {
        mockMvc.perform(post("/option-employees/option-sources/payrollProfile/options/filter")
                        .contentType("application/json")
                        .content("""
                                {
                                  "team": "A",
                                  "selectedPayrollProfile": "EXEC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value("EXEC"))
                .andExpect(jsonPath("$.content[0].label").value("Executive"))
                .andExpect(jsonPath("$.content[1].id").value("SPEC"))
                .andExpect(jsonPath("$.content[1].label").value("Specialist"));
    }

    @Test
    void returnsBucketOptionsByIdsPreservingOrder() throws Exception {
        mockMvc.perform(get("/option-employees/option-sources/faixaPctDesconto/options/by-ids")
                        .param("ids", "20%+", "0-10%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("20%+"))
                .andExpect(jsonPath("$[1].id").value("0-10%"));
    }

    private static OptionEmployee newEmployee(String team, String payrollProfileLabel, String payrollProfile, String faixaPctDesconto) {
        OptionEmployee entity = new OptionEmployee();
        entity.setTeam(team);
        entity.setPayrollProfileLabel(payrollProfileLabel);
        entity.setPayrollProfile(payrollProfile);
        entity.setFaixaPctDesconto(faixaPctDesconto);
        return entity;
    }

    @EnableAutoConfiguration
    @EnableJpaRepositories(considerNestedRepositories = true, basePackageClasses = AbstractCrudControllerOptionSourceIntegrationTest.class)
    @Import({OptionEmployeeController.class, OptionEmployeeService.class, JpaOptionSourceQueryExecutor.class, OptionSourceEligibility.class})
    static class TestConfig {
    }
}

@Entity
@Table(name = "it_option_employees")
class OptionEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String team;

    private String payrollProfileLabel;

    private String payrollProfile;

    private String faixaPctDesconto;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getPayrollProfileLabel() {
        return payrollProfileLabel;
    }

    public void setPayrollProfileLabel(String payrollProfileLabel) {
        this.payrollProfileLabel = payrollProfileLabel;
    }

    public String getPayrollProfile() {
        return payrollProfile;
    }

    public void setPayrollProfile(String payrollProfile) {
        this.payrollProfile = payrollProfile;
    }

    public String getFaixaPctDesconto() {
        return faixaPctDesconto;
    }

    public void setFaixaPctDesconto(String faixaPctDesconto) {
        this.faixaPctDesconto = faixaPctDesconto;
    }
}

class OptionEmployeeDto {
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}

class OptionEmployeeFilterDTO implements GenericFilterDTO {

    @Filterable(operation = Filterable.FilterOperation.EQUAL)
    private String team;

    @Filterable(operation = Filterable.FilterOperation.EQUAL)
    private String selectedPayrollProfile;

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getSelectedPayrollProfile() {
        return selectedPayrollProfile;
    }

    public void setSelectedPayrollProfile(String selectedPayrollProfile) {
        this.selectedPayrollProfile = selectedPayrollProfile;
    }
}

interface OptionEmployeeRepository extends BaseCrudRepository<OptionEmployee, Long> {
}

@Service
class OptionEmployeeService extends AbstractBaseCrudService<OptionEmployee, OptionEmployeeDto, Long, OptionEmployeeFilterDTO> {

    protected OptionEmployeeService(OptionEmployeeRepository repository) {
        super(repository, OptionEmployee.class);
    }

    @Override
    public OptionSourceRegistry getOptionSourceRegistry() {
        return OptionSourceRegistry.builder()
                .add(OptionEmployee.class, new OptionSourceDescriptor(
                        "payrollProfile",
                        OptionSourceType.DISTINCT_DIMENSION,
                        "/option-employees",
                        "selectedPayrollProfile",
                        null,
                        "payrollProfileLabel",
                        "payrollProfile",
                        List.of("team"),
                        new OptionSourcePolicy(true, true, "contains", 0, 25, 100, true, false, "label")
                ))
                .add(OptionEmployee.class, new OptionSourceDescriptor(
                        "faixaPctDesconto",
                        OptionSourceType.CATEGORICAL_BUCKET,
                        "/option-employees",
                        null,
                        null,
                        null,
                        null,
                        List.of("team"),
                        OptionSourcePolicy.defaults()
                ))
                .build();
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return StatsFieldRegistry.builder()
                .categoricalGroupByBucket("payrollProfile", "payrollProfileLabel")
                .categoricalTermsBucket("faixaPctDesconto", "faixaPctDesconto")
                .build();
    }
}

@ApiResource("/option-employees")
class OptionEmployeeController extends AbstractCrudController<OptionEmployee, OptionEmployeeDto, Long, OptionEmployeeFilterDTO> {

    @Autowired
    OptionEmployeeService service;

    @Override
    protected OptionEmployeeService getService() {
        return service;
    }

    @Override
    protected OptionEmployeeDto toDto(OptionEmployee entity) {
        OptionEmployeeDto dto = new OptionEmployeeDto();
        dto.setId(entity.getId());
        return dto;
    }

    @Override
    protected OptionEmployee toEntity(OptionEmployeeDto dto) {
        OptionEmployee entity = new OptionEmployee();
        entity.setId(dto.getId());
        return entity;
    }

    @Override
    protected Long getEntityId(OptionEmployee entity) {
        return entity.getId();
    }

    @Override
    protected Long getDtoId(OptionEmployeeDto dto) {
        return dto.getId();
    }

    @Override
    protected String getBasePath() {
        return "/option-employees";
    }
}
