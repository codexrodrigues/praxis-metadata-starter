package org.praxisplatform.uischema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
        basePackages = "org.praxisplatform.uischema.e2e",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "org\\.praxisplatform\\.uischema\\.e2e\\.fixture\\..*"
        )
)
public class TestApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
