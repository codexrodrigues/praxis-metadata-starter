package org.praxisplatform.uischema.rest.exceptionhandler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class PersistenceConstraintHttpTest {
    @RestController
    static class Fixture {
        @PostMapping("/constraint")
        void constraint() {
            throw new org.hibernate.exception.ConstraintViolationException(
                    "private-row-and-value", new SQLException("private-driver-details", "23505"),
                    "insert into private_table", "private_constraint");
        }
        @PostMapping("/unexpected")
        void unexpected() { throw new IllegalStateException("private-unexpected-details"); }
    }

    @Test void nativeFlushConflictUsesExistingSanitizedContract() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Fixture())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/constraint"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("failure"))
                .andExpect(jsonPath("$.message").value("Request conflicts with existing data constraints."))
                .andExpect(jsonPath("$.errors[0].code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(content().string(not(containsString("private"))))
                .andExpect(content().string(not(containsString("23505"))));
        mvc.perform(post("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("private"))));
    }
}
