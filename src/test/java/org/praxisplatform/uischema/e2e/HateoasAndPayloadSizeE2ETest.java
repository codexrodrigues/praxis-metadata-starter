package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HateoasAndPayloadSizeE2ETest extends AbstractE2eH2Test {

    private static final String OVERRIDE_SOURCE = "e2e-hateoas-override";

    @Autowired
    private ConfigurableEnvironment environment;

    @AfterEach
    void clearOverride() {
        environment.getPropertySources().remove(OVERRIDE_SOURCE);
    }

    @Test
    void hateoasToggleChangesTopLevelAndItemPayloadShape() throws Exception {
        setHateoasEnabled(true);
        ResponseEntity<String> enabledCollectionResponse = get("/employees/all");
        ResponseEntity<String> enabledItemResponse = get("/employees/" + state.employeeIdsByName().get("Alice"));
        assertEquals(200, enabledCollectionResponse.getStatusCode().value());
        assertEquals(200, enabledItemResponse.getStatusCode().value());

        String enabledCollectionBody = enabledCollectionResponse.getBody();
        String enabledItemBody = enabledItemResponse.getBody();
        assertTrue(enabledCollectionBody.contains("\"rel\":\"create\""));
        assertTrue(enabledCollectionBody.contains("\"rel\":\"update\""));
        assertTrue(enabledCollectionBody.contains("\"rel\":\"delete\""));
        assertTrue(enabledItemBody.contains("\"rel\":\"self\""));
        assertEquals(1, countOccurrences(enabledCollectionBody, "\"rel\":\"create\""));

        setHateoasEnabled(false);
        ResponseEntity<String> disabledCollectionResponse = get("/employees/all");
        ResponseEntity<String> disabledItemResponse = get("/employees/" + state.employeeIdsByName().get("Alice"));
        assertEquals(200, disabledCollectionResponse.getStatusCode().value());
        assertEquals(200, disabledItemResponse.getStatusCode().value());

        String disabledCollectionBody = disabledCollectionResponse.getBody();
        String disabledItemBody = disabledItemResponse.getBody();
        assertFalse(disabledCollectionBody.contains("\"rel\":\"create\""));
        assertFalse(disabledCollectionBody.contains("\"rel\":\"update\""));
        assertFalse(disabledCollectionBody.contains("\"rel\":\"delete\""));
        assertFalse(disabledItemBody.contains("\"rel\":\"self\""));
        assertTrue(disabledCollectionBody.length() < enabledCollectionBody.length());
        assertTrue(disabledItemBody.length() < enabledItemBody.length());

        JsonNode disabledCollectionJson = body(disabledCollectionResponse);
        JsonNode disabledItemJson = body(disabledItemResponse);
        assertTrue(disabledCollectionJson.path("links").isMissingNode() || disabledCollectionJson.path("links").isNull());
        assertTrue(disabledItemJson.path("links").isMissingNode() || disabledItemJson.path("links").isNull());
    }

    @Test
    void readOnlyResourcesNeverExposeWriteLinksEvenWhenHateoasIsEnabled() throws Exception {
        setHateoasEnabled(true);

        ResponseEntity<String> collectionResponse = get("/payroll-view/all");
        ResponseEntity<String> itemResponse = get("/payroll-view/" + state.payrollIdsByEmployee().get("Alice"));
        assertEquals(200, collectionResponse.getStatusCode().value());
        assertEquals(200, itemResponse.getStatusCode().value());

        String collectionBody = collectionResponse.getBody();
        String itemBody = itemResponse.getBody();
        assertFalse(collectionBody.contains("\"rel\":\"create\""));
        assertFalse(collectionBody.contains("\"rel\":\"update\""));
        assertFalse(collectionBody.contains("\"rel\":\"delete\""));
        assertFalse(itemBody.contains("\"rel\":\"create\""));
        assertFalse(itemBody.contains("\"rel\":\"update\""));
        assertFalse(itemBody.contains("\"rel\":\"delete\""));
    }

    private void setHateoasEnabled(boolean enabled) {
        environment.getPropertySources().remove(OVERRIDE_SOURCE);
        environment.getPropertySources().addFirst(new MapPropertySource(
                OVERRIDE_SOURCE,
                Map.of("praxis.hateoas.enabled", Boolean.toString(enabled))
        ));
    }

    private int countOccurrences(String body, String token) {
        int count = 0;
        int index = 0;
        while ((index = body.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
