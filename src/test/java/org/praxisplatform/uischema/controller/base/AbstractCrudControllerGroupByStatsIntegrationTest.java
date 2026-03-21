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
        classes = AbstractCrudControllerGroupByStatsIntegrationTest.TestConfig.class,
        properties = {
                "spring.jpa.open-in-view=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:starter-groupby-it;DB_CLOSE_DELAY=-1",
                "spring.datasource.driverClassName=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "praxis.stats.enabled=true",
                "praxis.stats.max-buckets=10"
        }
)
@AutoConfigureMockMvc
class AbstractCrudControllerGroupByStatsIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    GroupByEmployeeRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();

        repository.save(newEmployee("A", "OPEN", 10));
        repository.save(newEmployee("A", "OPEN", 20));
        repository.save(newEmployee("A", "CLOSED", 5));
        repository.save(newEmployee("B", "OPEN", 30));
    }

    @Test
    void returnsBucketsForFilteredDataset() throws Exception {
        mockMvc.perform(post("/groupby-employees/stats/group-by")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filter": {
                                    "team": "A"
                                  },
                                  "field": "status",
                                  "metric": {
                                    "operation": "COUNT"
                                  },
                                  "orderBy": "VALUE_DESC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("status"))
                .andExpect(jsonPath("$.data.buckets.length()").value(2))
                .andExpect(jsonPath("$.data.buckets[0].key").value("OPEN"))
                .andExpect(jsonPath("$.data.buckets[0].value").value(2))
                .andExpect(jsonPath("$.data.buckets[1].key").value("CLOSED"))
                .andExpect(jsonPath("$.data.buckets[1].value").value(1));
    }

    @Test
    void returnsSumMetricBucketsForFilteredDataset() throws Exception {
        mockMvc.perform(post("/groupby-employees/stats/group-by")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filter": {
                                    "team": "A"
                                  },
                                  "field": "status",
                                  "metric": {
                                    "operation": "SUM",
                                    "field": "salary"
                                  },
                                  "orderBy": "VALUE_DESC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("status"))
                .andExpect(jsonPath("$.data.metric.operation").value("SUM"))
                .andExpect(jsonPath("$.data.metric.field").value("salary"))
                .andExpect(jsonPath("$.data.buckets.length()").value(2))
                .andExpect(jsonPath("$.data.buckets[0].key").value("OPEN"))
                .andExpect(jsonPath("$.data.buckets[0].value").value(30.0))
                .andExpect(jsonPath("$.data.buckets[0].count").value(2))
                .andExpect(jsonPath("$.data.buckets[1].key").value("CLOSED"))
                .andExpect(jsonPath("$.data.buckets[1].value").value(5.0))
                .andExpect(jsonPath("$.data.buckets[1].count").value(1));
    }

    private static GroupByEmployee newEmployee(String team, String status, Integer salary) {
        GroupByEmployee entity = new GroupByEmployee();
        entity.setTeam(team);
        entity.setStatus(status);
        entity.setSalary(salary);
        return entity;
    }

    @EnableAutoConfiguration
    @EnableJpaRepositories(considerNestedRepositories = true, basePackageClasses = AbstractCrudControllerGroupByStatsIntegrationTest.class)
    @Import({GroupByEmployeeController.class, GroupByEmployeeService.class})
    static class TestConfig {
    }
}

@Entity
@Table(name = "it_groupby_employees")
class GroupByEmployee {

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

class GroupByEmployeeDto {
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

class GroupByEmployeeFilterDto implements GenericFilterDTO {

    @Filterable(operation = Filterable.FilterOperation.EQUAL)
    private String team;

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }
}

interface GroupByEmployeeRepository extends BaseCrudRepository<GroupByEmployee, Long> {
}

@Service
class GroupByEmployeeService extends AbstractBaseCrudService<GroupByEmployee, GroupByEmployeeDto, Long, GroupByEmployeeFilterDto> {

    GroupByEmployeeService(GroupByEmployeeRepository repository) {
        super(repository, GroupByEmployee.class);
    }

    @Override
    public StatsSupportMode getGroupByStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return StatsFieldRegistry.builder()
                .categoricalGroupByBucket("status", "status")
                .numericMeasureField("salary", "salary")
                .build();
    }
}

@ApiResource("/groupby-employees")
@org.springframework.web.bind.annotation.RestController
class GroupByEmployeeController extends AbstractCrudController<GroupByEmployee, GroupByEmployeeDto, Long, GroupByEmployeeFilterDto> {

    private final GroupByEmployeeService service;

    GroupByEmployeeController(GroupByEmployeeService service) {
        this.service = service;
    }

    @Override
    protected GroupByEmployeeService getService() {
        return service;
    }

    @Override
    protected GroupByEmployeeDto toDto(GroupByEmployee entity) {
        GroupByEmployeeDto dto = new GroupByEmployeeDto();
        dto.setId(entity.getId());
        dto.setTeam(entity.getTeam());
        dto.setStatus(entity.getStatus());
        dto.setSalary(entity.getSalary());
        return dto;
    }

    @Override
    protected GroupByEmployee toEntity(GroupByEmployeeDto dto) {
        GroupByEmployee entity = new GroupByEmployee();
        entity.setId(dto.getId());
        entity.setTeam(dto.getTeam());
        entity.setStatus(dto.getStatus());
        entity.setSalary(dto.getSalary());
        return entity;
    }

    @Override
    protected Long getEntityId(GroupByEmployee entity) {
        return entity.getId();
    }

    @Override
    protected Long getDtoId(GroupByEmployeeDto dto) {
        return dto.getId();
    }
}
