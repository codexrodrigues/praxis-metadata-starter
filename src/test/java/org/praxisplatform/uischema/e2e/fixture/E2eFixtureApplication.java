package org.praxisplatform.uischema.e2e.fixture;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// Schema/domain fixture only; real IAM is proved by BulkOpenApiPublicationRegistrationHttpTest.
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class)
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
}
