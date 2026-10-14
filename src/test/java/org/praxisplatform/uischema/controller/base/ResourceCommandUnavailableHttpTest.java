package org.praxisplatform.uischema.controller.base;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.command.ResourceCommandExecutionResult;
import org.praxisplatform.uischema.command.ResourceCommandResponsePolicy;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC helper/executor/adapter serialization; no database or host security-chain claim. */
class ResourceCommandUnavailableHttpTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockMvc mvc;

    @BeforeEach void setup() {
        var controller = new CommandController();
        controller.initializeBasePath();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    @Test
    void unavailableIs503WithOneFlatSafeProblemAndGenericFailureStays500() throws Exception {
        String raw = mvc.perform(post("/commands/unavailable")).andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        try (var parser = json.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode body = json.readTree(parser);
            assertEquals("failure", body.path("status").asText());
            assertEquals("Command is temporarily unavailable.", body.path("message").asText());
            assertEquals(1, body.path("errors").size());
            var error = body.path("errors").get(0);
            assertEquals(503, error.path("status").asInt());
            assertEquals("SYSTEM", error.path("category").asText());
            assertEquals("UNAVAILABLE", error.path("code").asText());
            assertEquals("UNAVAILABLE", error.path("outcome").asText());
            assertEquals("Command is temporarily unavailable.", error.path("message").asText());
            assertFalse(error.has("properties"));
        }
        assertFalse(raw.contains("private")); assertFalse(raw.contains("postgresql"));
        String unexpected = mvc.perform(post("/commands/unexpected")).andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        assertFalse(unexpected.contains("private"));
        assertEquals("UNEXPECTED_SANITIZED", json.readTree(unexpected).path("errors").get(0).path("outcome").asText());
    }

    @Test
    void legitimateCommandStillReturnsItsSuccessfulEnvelope() throws Exception {
        String raw = mvc.perform(post("/commands/success")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var body = json.readTree(raw);
        assertEquals("success", body.path("status").asText());
        assertEquals("APPROVED", body.path("data").path("state").asText());
    }

    @RestController
    @RequestMapping("/commands")
    @ApiResource(value = "/commands", resourceKey = "example.commands")
    static class CommandController extends AbstractCollectionCommandResourceController {
        @PostMapping("/unavailable")
        public org.springframework.http.ResponseEntity<?> unavailable() {
            return executeCollectionCommand("approve", null, ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT,
                    request -> { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "postgresql://runtime:private-password@config/private-table", new IllegalStateException("private-cause")); });
        }

        @PostMapping("/unexpected")
        public org.springframework.http.ResponseEntity<?> unexpected() {
            return executeCollectionCommand("approve", null, ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT,
                    request -> { throw new IllegalStateException("private-error"); });
        }

        @PostMapping("/success")
        public org.springframework.http.ResponseEntity<?> success() {
            return executeCollectionCommand("approve", null, ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT,
                    request -> ResourceCommandExecutionResult.success(request, 1, Map.of("state", "APPROVED"), Map.of()));
        }
    }
}
