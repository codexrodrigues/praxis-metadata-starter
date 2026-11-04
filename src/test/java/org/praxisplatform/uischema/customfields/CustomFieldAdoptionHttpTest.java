package org.praxisplatform.uischema.customfields;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP serialization/advice and real resource lifecycle, with a unit repository boundary. */
class CustomFieldAdoptionHttpTest {
    @Test void omittedOptionalConstraintDoesNotBecomeHttp500() throws Exception {
        var fixture = new CustomFieldAdoptionRegressionTest.Fixture(CustomFieldAdoptionRegressionTest.amount(
                Map.of(">=",List.of(Map.of("var","value"),4))));
        var mvc = MockMvcBuilders.standaloneSetup(new Controller(fixture.service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/custom-field-adoption").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"accepted\",\"customFields\":{}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.note").value("accepted"));
        verify(fixture.repository).save(any());
    }
    @Test void falseRuleIsAFieldErrorAndCannotSave() throws Exception {
        var fixture = new CustomFieldAdoptionRegressionTest.Fixture(CustomFieldAdoptionRegressionTest.amount(
                Map.of(">=",List.of(Map.of("var","value"),4))));
        var mvc = MockMvcBuilders.standaloneSetup(new Controller(fixture.service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/custom-field-adoption").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"rejected\",\"customFields\":{\"amount\":0}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].target").value("customFields.amount"));
        verify(fixture.repository,never()).save(any());
    }
    @Test void theHydratedDraftReachesJsonLogicBeforeBusinessEffects() throws Exception {
        var fixture = new CustomFieldAdoptionRegressionTest.Fixture(CustomFieldAdoptionRegressionTest.amount(
                Map.of("==",List.of(Map.of("var","draft.owner"),7))));
        var mvc = MockMvcBuilders.standaloneSetup(new Controller(fixture.service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/custom-field-adoption").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"hydrated\",\"customFields\":{\"amount\":8}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.owner").value(7));
        assertEquals(1,fixture.definitionReads.get());assertEquals(1,fixture.service.hooks);
        verify(fixture.repository).save(any());
    }
    @RestController
    static class Controller {
        private final CustomFieldAdoptionRegressionTest.Service service;
        Controller(CustomFieldAdoptionRegressionTest.Service service) { this.service=service; }
        @PostMapping("/custom-field-adoption")
        ResponseEntity<?> create(@RequestBody CustomFieldAdoptionRegressionTest.Write dto) {
            var saved=service.create(dto);
            return ResponseEntity.created(URI.create("/custom-field-adoption/"+saved.id()))
                    .body(RestApiResponse.success(saved.body(),null));
        }
    }
}
