package org.praxisplatform.uischema.e2e.fixture;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceEligibility;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.options.service.jpa.JpaOptionSourceQueryExecutor;
import org.praxisplatform.uischema.options.service.OptionSourceQueryExecutor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

@SpringBootApplication
@EntityScan(basePackageClasses = E2eFixtureApplication.class)
@ComponentScan(
        basePackageClasses = E2eFixtureApplication.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = E2eBootstrapFixtureApplication.class
        )
)
@EnableJpaRepositories(basePackageClasses = E2eFixtureApplication.class)
public class E2eFixtureApplication {

    @Bean
    OptionSourceQueryExecutor optionSourceQueryExecutor() {
        return new JpaOptionSourceQueryExecutor();
    }

    @Bean
    OptionSourceEligibility optionSourceEligibility() {
        return new OptionSourceEligibility();
    }

    @Bean
    OptionSourceRegistry optionSourceRegistry() {
        return OptionSourceRegistry.builder()
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
    }
}
