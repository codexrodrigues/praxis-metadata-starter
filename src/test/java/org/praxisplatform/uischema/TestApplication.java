package org.praxisplatform.uischema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication(scanBasePackages = {
        "org.praxisplatform.uischema.controller",
        "org.praxisplatform.uischema.configuration",
        "org.praxisplatform.uischema.extension",
        "org.praxisplatform.uischema.util",
        "org.praxisplatform.uischema.e2e"
})
public class TestApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
