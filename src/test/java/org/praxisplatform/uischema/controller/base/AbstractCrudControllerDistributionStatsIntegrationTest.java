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
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseCrudService;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AbstractCrudControllerDistributionStatsIntegrationTest.TestConfig.class,
        properties = {
                "spring.jpa.open-in-view=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:starter-distribution-it;DB_CLOSE_DELAY=-1",
                "spring.datasource.driverClassName=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "praxis.stats.enabled=true",
                "praxis.stats.max-buckets=10"
        }
)
@AutoConfigureMockMvc
class AbstractCrudControllerDistributionStatsIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DistributionEmployeeRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();

        repository.save(newEmployee("A", "OPEN", 10));
        repository.save(newEmployee("A", "OPEN", 12));
        repository.save(newEmployee("A", "CLOSED", 27));
        repository.save(newEmployee("B", "OPEN", 44));
    }

    @Test
    void returnsTermsBucketsForFilteredDataset() throws Exception {
        mockMvc.perform(post("/distribution-employees/stats/distribution")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filter": {
                                    "team": "A"
                                  },
                                  "field": "status",
                                  "mode": "TERMS",
                                  "metric": {
                                    "operation": "COUNT"
                                  },
                                  "orderBy": "VALUE_DESC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("status"))
                .andExpect(jsonPath("$.data.mode").value("TERMS"))
                .andExpect(jsonPath("$.data.buckets.length()").value(2))
                .andExpect(jsonPath("$.data.buckets[0].key").value("OPEN"))
                .andExpect(jsonPath("$.data.buckets[0].value").value(2))
                .andExpect(jsonPath("$.data.buckets[1].key").value("CLOSED"))
                .andExpect(jsonPath("$.data.buckets[1].value").value(1));
    }

    @Test
    void returnsTermsBucketsForSumMetric() throws Exception {
        mockMvc.perform(post("/distribution-employees/stats/distribution")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filter": {
                                    "team": "A"
                                  },
                                  "field": "status",
                                  "mode": "TERMS",
                                  "metric": {
                                    "operation": "SUM",
                                    "field": "salary"
                                  },
                                  "orderBy": "VALUE_DESC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("status"))
                .andExpect(jsonPath("$.data.mode").value("TERMS"))
                .andExpect(jsonPath("$.data.metric.operation").value("SUM"))
                .andExpect(jsonPath("$.data.metric.field").value("salary"))
                .andExpect(jsonPath("$.data.buckets.length()").value(2))
                .andExpect(jsonPath("$.data.buckets[0].key").value("CLOSED"))
                .andExpect(jsonPath("$.data.buckets[0].value").value(27.0))
                .andExpect(jsonPath("$.data.buckets[0].count").value(1))
                .andExpect(jsonPath("$.data.buckets[1].key").value("OPEN"))
                .andExpect(jsonPath("$.data.buckets[1].value").value(22.0))
                .andExpect(jsonPath("$.data.buckets[1].count").value(2));
    }

    @Test
    void returnsHistogramBucketsForFilteredDataset() throws Exception {
        mockMvc.perform(post("/distribution-employees/stats/distribution")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filter": {
                                    "team": "A"
                                  },
                                  "field": "salary",
                                  "mode": "HISTOGRAM",
                                  "metric": {
                                    "operation": "COUNT"
                                  },
                                  "bucketSize": 10,
                                  "orderBy": "KEY_ASC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("salary"))
                .andExpect(jsonPath("$.data.mode").value("HISTOGRAM"))
                .andExpect(jsonPath("$.data.buckets.length()").value(2))
                .andExpect(jsonPath("$.data.buckets[0].from").value(10.0))
                .andExpect(jsonPath("$.data.buckets[0].to").value(20.0))
                .andExpect(jsonPath("$.data.buckets[0].label").value("10 - 20"))
                .andExpect(jsonPath("$.data.buckets[0].value").value(2))
                .andExpect(jsonPath("$.data.buckets[1].from").value(20.0))
                .andExpect(jsonPath("$.data.buckets[1].to").value(30.0))
                .andExpect(jsonPath("$.data.buckets[1].label").value("20 - 30"))
                .andExpect(jsonPath("$.data.buckets[1].value").value(1));
    }

    private static DistributionEmployee newEmployee(String team, String status, Integer salary) {
        DistributionEmployee entity = new DistributionEmployee();
        entity.setTeam(team);
        entity.setStatus(status);
        entity.setSalary(salary);
        return entity;
    }

    @EnableAutoConfiguration
    @EnableJpaRepositories(considerNestedRepositories = true, basePackageClasses = AbstractCrudControllerDistributionStatsIntegrationTest.class)
    @Import({DistributionEmployeeController.class, DistributionEmployeeService.class})
    static class TestConfig {
    }
}

@Entity
@Table(name = "it_distribution_employees")
class DistributionEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String team;

    private String status;

    private Integer salary;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSalary() {
        return salary;
    }

    public void setSalary(Integer salary) {
        this.salary = salary;
    }
}

class DistributionEmployeeDto {
    private Long id;
    private String team;
    private String status;

    private Integer salary;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSalary() {
        return salary;
    }

    public void setSalary(Integer salary) {
        this.salary = salary;
    }
}

class DistributionEmployeeFilterDto implements GenericFilterDTO {

    @Filterable(operation = Filterable.FilterOperation.EQUAL)
    private String team;

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }
}

interface DistributionEmployeeRepository extends BaseCrudRepository<DistributionEmployee, Long> {
}

@Service
class DistributionEmployeeService extends AbstractBaseCrudService<DistributionEmployee, DistributionEmployeeDto, Long, DistributionEmployeeFilterDto> {

    DistributionEmployeeService(DistributionEmployeeRepository repository) {
        super(repository, DistributionEmployee.class);
    }

    @Override
    public StatsSupportMode getDistributionStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return StatsFieldRegistry.builder()
                .categoricalTermsBucket("status", "status")
                .numericHistogramMeasureField("salary", "salary")
                .build();
    }
}

@ApiResource("/distribution-employees")
@org.springframework.web.bind.annotation.RestController
class DistributionEmployeeController extends AbstractCrudController<DistributionEmployee, DistributionEmployeeDto, Long, DistributionEmployeeFilterDto> {

    private final DistributionEmployeeService service;

    DistributionEmployeeController(DistributionEmployeeService service) {
        this.service = service;
    }

    @Override
    protected DistributionEmployeeService getService() {
        return service;
    }

    @Override
    protected DistributionEmployeeDto toDto(DistributionEmployee entity) {
        DistributionEmployeeDto dto = new DistributionEmployeeDto();
        dto.setId(entity.getId());
        dto.setTeam(entity.getTeam());
        dto.setStatus(entity.getStatus());
        dto.setSalary(entity.getSalary());
        return dto;
    }

    @Override
    protected DistributionEmployee toEntity(DistributionEmployeeDto dto) {
        DistributionEmployee entity = new DistributionEmployee();
        entity.setId(dto.getId());
        entity.setTeam(dto.getTeam());
        entity.setStatus(dto.getStatus());
        entity.setSalary(dto.getSalary());
        return entity;
    }

    @Override
    protected Long getEntityId(DistributionEmployee entity) {
        return entity.getId();
    }

    @Override
    protected Long getDtoId(DistributionEmployeeDto dto) {
        return dto.getId();
    }
}
