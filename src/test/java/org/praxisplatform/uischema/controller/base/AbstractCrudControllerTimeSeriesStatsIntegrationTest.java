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

import java.time.LocalDate;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AbstractCrudControllerTimeSeriesStatsIntegrationTest.TestConfig.class,
        properties = {
                "spring.jpa.open-in-view=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:starter-timeseries-it;DB_CLOSE_DELAY=-1",
                "spring.datasource.driverClassName=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "praxis.stats.enabled=true",
                "praxis.stats.max-series-points=10"
        }
)
@AutoConfigureMockMvc
class AbstractCrudControllerTimeSeriesStatsIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TimeSeriesEmployeeRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();

        repository.save(newEmployee("A", LocalDate.parse("2026-03-01"), 10));
        repository.save(newEmployee("A", LocalDate.parse("2026-03-01"), 15));
        repository.save(newEmployee("A", LocalDate.parse("2026-03-03"), 7));
        repository.save(newEmployee("B", LocalDate.parse("2026-03-02"), 20));
    }

    @Test
    void returnsDailyBucketsAndFillsGapsForFilteredDataset() throws Exception {
        mockMvc.perform(post("/timeseries-employees/stats/timeseries")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filter": {
                                    "team": "A"
                                  },
                                  "field": "createdOn",
                                  "granularity": "DAY",
                                  "metric": {
                                    "operation": "COUNT"
                                  },
                                  "from": "2026-03-01",
                                  "to": "2026-03-03",
                                  "fillGaps": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("createdOn"))
                .andExpect(jsonPath("$.data.points.length()").value(3))
                .andExpect(jsonPath("$.data.points[0].start").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].end").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].label").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].value").value(2))
                .andExpect(jsonPath("$.data.points[1].start").value("2026-03-02"))
                .andExpect(jsonPath("$.data.points[1].end").value("2026-03-02"))
                .andExpect(jsonPath("$.data.points[1].label").value("2026-03-02"))
                .andExpect(jsonPath("$.data.points[1].value").value(0))
                .andExpect(jsonPath("$.data.points[2].start").value("2026-03-03"))
                .andExpect(jsonPath("$.data.points[2].end").value("2026-03-03"))
                .andExpect(jsonPath("$.data.points[2].label").value("2026-03-03"))
                .andExpect(jsonPath("$.data.points[2].value").value(1));
    }

    @Test
    void returnsDailySumBucketsForFilteredDataset() throws Exception {
        mockMvc.perform(post("/timeseries-employees/stats/timeseries")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filter": {
                                    "team": "A"
                                  },
                                  "field": "createdOn",
                                  "granularity": "DAY",
                                  "metric": {
                                    "operation": "SUM",
                                    "field": "salary"
                                  },
                                  "from": "2026-03-01",
                                  "to": "2026-03-03",
                                  "fillGaps": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("createdOn"))
                .andExpect(jsonPath("$.data.metric.operation").value("SUM"))
                .andExpect(jsonPath("$.data.metric.field").value("salary"))
                .andExpect(jsonPath("$.data.points.length()").value(3))
                .andExpect(jsonPath("$.data.points[0].start").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].end").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].label").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].value").value(25.0))
                .andExpect(jsonPath("$.data.points[0].count").value(2))
                .andExpect(jsonPath("$.data.points[1].start").value("2026-03-02"))
                .andExpect(jsonPath("$.data.points[1].end").value("2026-03-02"))
                .andExpect(jsonPath("$.data.points[1].label").value("2026-03-02"))
                .andExpect(jsonPath("$.data.points[1].value").value(0.0))
                .andExpect(jsonPath("$.data.points[1].count").value(0))
                .andExpect(jsonPath("$.data.points[2].start").value("2026-03-03"))
                .andExpect(jsonPath("$.data.points[2].end").value("2026-03-03"))
                .andExpect(jsonPath("$.data.points[2].label").value("2026-03-03"))
                .andExpect(jsonPath("$.data.points[2].value").value(7.0))
                .andExpect(jsonPath("$.data.points[2].count").value(1));
    }

    @Test
    void returnsDailyMultiMetricBucketsForFilteredDataset() throws Exception {
        mockMvc.perform(post("/timeseries-employees/stats/timeseries")
                        .contentType("application/json")
                        .content("""
                                {
                                  "filter": {
                                    "team": "A"
                                  },
                                  "field": "createdOn",
                                  "granularity": "DAY",
                                  "metrics": [
                                    {
                                      "operation": "COUNT",
                                      "alias": "total"
                                    },
                                    {
                                      "operation": "SUM",
                                      "field": "salary",
                                      "alias": "salary"
                                    }
                                  ],
                                  "from": "2026-03-01",
                                  "to": "2026-03-03",
                                  "fillGaps": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metric.operation").value("COUNT"))
                .andExpect(jsonPath("$.data.metric.alias").value("total"))
                .andExpect(jsonPath("$.data.metrics.length()").value(2))
                .andExpect(jsonPath("$.data.points.length()").value(3))
                .andExpect(jsonPath("$.data.points[0].value").value(2))
                .andExpect(jsonPath("$.data.points[0].values.total").value(2))
                .andExpect(jsonPath("$.data.points[0].values.salary").value(25.0))
                .andExpect(jsonPath("$.data.points[1].value").value(0))
                .andExpect(jsonPath("$.data.points[1].values.total").value(0))
                .andExpect(jsonPath("$.data.points[1].values.salary").value(0.0))
                .andExpect(jsonPath("$.data.points[2].value").value(1))
                .andExpect(jsonPath("$.data.points[2].values.total").value(1))
                .andExpect(jsonPath("$.data.points[2].values.salary").value(7.0));
    }

    private static TimeSeriesEmployee newEmployee(String team, LocalDate createdOn, Integer salary) {
        TimeSeriesEmployee entity = new TimeSeriesEmployee();
        entity.setTeam(team);
        entity.setCreatedOn(createdOn);
        entity.setSalary(salary);
        return entity;
    }

    @EnableAutoConfiguration
    @EnableJpaRepositories(considerNestedRepositories = true, basePackageClasses = AbstractCrudControllerTimeSeriesStatsIntegrationTest.class)
    @Import({TimeSeriesEmployeeController.class, TimeSeriesEmployeeService.class})
    static class TestConfig {
    }
}

@Entity
@Table(name = "it_timeseries_employees")
class TimeSeriesEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String team;

    private LocalDate createdOn;

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

    public LocalDate getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(LocalDate createdOn) {
        this.createdOn = createdOn;
    }

    public Integer getSalary() {
        return salary;
    }

    public void setSalary(Integer salary) {
        this.salary = salary;
    }
}

class TimeSeriesEmployeeDto {
    private Long id;
    private String team;
    private LocalDate createdOn;

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

    public LocalDate getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(LocalDate createdOn) {
        this.createdOn = createdOn;
    }

    public Integer getSalary() {
        return salary;
    }

    public void setSalary(Integer salary) {
        this.salary = salary;
    }
}

class TimeSeriesEmployeeFilterDto implements GenericFilterDTO {

    @Filterable(operation = Filterable.FilterOperation.EQUAL)
    private String team;

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }
}

interface TimeSeriesEmployeeRepository extends BaseCrudRepository<TimeSeriesEmployee, Long> {
}

@Service
class TimeSeriesEmployeeService extends AbstractBaseCrudService<TimeSeriesEmployee, TimeSeriesEmployeeDto, Long, TimeSeriesEmployeeFilterDto> {

    TimeSeriesEmployeeService(TimeSeriesEmployeeRepository repository) {
        super(repository, TimeSeriesEmployee.class);
    }

    @Override
    public StatsSupportMode getTimeSeriesStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return StatsFieldRegistry.builder()
                .temporalTimeSeriesField("createdOn", "createdOn")
                .numericMeasureField("salary", "salary")
                .build();
    }
}

@ApiResource(value = "/timeseries-employees", resourceKey = "timeseries.employees")
@org.springframework.web.bind.annotation.RestController
class TimeSeriesEmployeeController extends AbstractCrudController<TimeSeriesEmployee, TimeSeriesEmployeeDto, Long, TimeSeriesEmployeeFilterDto> {

    private final TimeSeriesEmployeeService service;

    TimeSeriesEmployeeController(TimeSeriesEmployeeService service) {
        this.service = service;
    }

    @Override
    protected TimeSeriesEmployeeService getService() {
        return service;
    }

    @Override
    protected TimeSeriesEmployeeDto toDto(TimeSeriesEmployee entity) {
        TimeSeriesEmployeeDto dto = new TimeSeriesEmployeeDto();
        dto.setId(entity.getId());
        dto.setTeam(entity.getTeam());
        dto.setCreatedOn(entity.getCreatedOn());
        dto.setSalary(entity.getSalary());
        return dto;
    }

    @Override
    protected TimeSeriesEmployee toEntity(TimeSeriesEmployeeDto dto) {
        TimeSeriesEmployee entity = new TimeSeriesEmployee();
        entity.setId(dto.getId());
        entity.setTeam(dto.getTeam());
        entity.setCreatedOn(dto.getCreatedOn());
        entity.setSalary(dto.getSalary());
        return entity;
    }

    @Override
    protected Long getEntityId(TimeSeriesEmployee entity) {
        return entity.getId();
    }

    @Override
    protected Long getDtoId(TimeSeriesEmployeeDto dto) {
        return dto.getId();
    }
}
