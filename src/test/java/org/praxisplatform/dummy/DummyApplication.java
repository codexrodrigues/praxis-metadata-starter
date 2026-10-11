package org.praxisplatform.dummy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Marca como Spring Boot Application
@SpringBootApplication(scanBasePackages = {"org.praxisplatform"})
public class DummyApplication {
    public static void main(String[] args) {
        SpringApplication.run(DummyApplication.class, args);
    }

    // Exemplo simples de endpoint
    @RestController
    public static class TestController {
        @GetMapping("/hello")
        public String hello() {
            return "hello world";
        }
    }
}
