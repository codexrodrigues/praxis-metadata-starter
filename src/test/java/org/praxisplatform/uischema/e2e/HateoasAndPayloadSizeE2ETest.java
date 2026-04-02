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

        JsonNode enabledCollectionJson = body(enabledCollectionResponse);
        JsonNode enabledItemJson = body(enabledItemResponse);
        JsonNode enabledCollectionRow = enabledCollectionJson.path("data").path(0);

        assertTrue(enabledCollectionJson.path("_links").isObject());
        assertTrue(enabledItemJson.path("_links").isObject());
        assertTrue(enabledCollectionRow.path("_links").isObject());
        assertTrue(enabledCollectionJson.path("_links").has("create"));
        assertTrue(enabledCollectionJson.path("_links").has("surfaces"));
        assertTrue(enabledCollectionJson.path("_links").has("actions"));
        assertTrue(enabledCollectionJson.path("_links").has("capabilities"));
        assertFalse(enabledCollectionRow.path("_links").has("create"));
        assertTrue(enabledCollectionRow.path("_links").has("update"));
        assertTrue(enabledCollectionRow.path("_links").has("delete"));
        assertTrue(enabledCollectionRow.path("_links").has("surfaces"));
        assertTrue(enabledCollectionRow.path("_links").has("actions"));
        assertTrue(enabledCollectionRow.path("_links").has("capabilities"));
        assertTrue(enabledItemJson.path("_links").has("self"));
        assertFalse(enabledItemJson.path("_links").has("create"));
        assertTrue(enabledItemJson.path("_links").has("surfaces"));
        assertTrue(enabledItemJson.path("_links").has("actions"));
        assertTrue(enabledItemJson.path("_links").has("capabilities"));
        assertTrue(enabledCollectionJson.path("links").isMissingNode() || enabledCollectionJson.path("links").isNull());
        assertTrue(enabledItemJson.path("links").isMissingNode() || enabledItemJson.path("links").isNull());
        assertTrue(enabledCollectionRow.path("links").isMissingNode() || enabledCollectionRow.path("links").isNull());

        setHateoasEnabled(false);
        ResponseEntity<String> disabledCollectionResponse = get("/employees/all");
        ResponseEntity<String> disabledItemResponse = get("/employees/" + state.employeeIdsByName().get("Alice"));
        assertEquals(200, disabledCollectionResponse.getStatusCode().value());
        assertEquals(200, disabledItemResponse.getStatusCode().value());

        String enabledCollectionBody = enabledCollectionResponse.getBody();
        String enabledItemBody = enabledItemResponse.getBody();
        String disabledCollectionBody = disabledCollectionResponse.getBody();
        String disabledItemBody = disabledItemResponse.getBody();
        assertFalse(disabledCollectionBody.contains("\"_links\""));
        assertFalse(disabledItemBody.contains("\"_links\""));
        assertFalse(disabledCollectionBody.contains("\"links\":"));
        assertFalse(disabledItemBody.contains("\"links\":"));
        assertFalse(disabledCollectionBody.contains("\"rel\":\"create\""));
        assertFalse(disabledCollectionBody.contains("\"rel\":\"update\""));
        assertFalse(disabledCollectionBody.contains("\"rel\":\"delete\""));
        assertFalse(disabledItemBody.contains("\"rel\":\"self\""));
        assertTrue(disabledCollectionBody.length() < enabledCollectionBody.length());
        assertTrue(disabledItemBody.length() < enabledItemBody.length());

        JsonNode disabledCollectionJson = body(disabledCollectionResponse);
        JsonNode disabledItemJson = body(disabledItemResponse);
        assertTrue(disabledCollectionJson.path("_links").isMissingNode() || disabledCollectionJson.path("_links").isNull());
        assertTrue(disabledItemJson.path("_links").isMissingNode() || disabledItemJson.path("_links").isNull());
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

    @Test
    void hateoasToggleAlsoRemovesReadOnlyDiscoveryRels() throws Exception {
        Long payrollId = state.payrollIdsByEmployee().get("Alice");

        setHateoasEnabled(true);
        JsonNode enabledCollectionJson = body(get("/payroll-view/all"));
        JsonNode enabledItemJson = body(get("/payroll-view/" + payrollId));
        JsonNode enabledCollectionRow = enabledCollectionJson.path("data").path(0);

        assertTrue(enabledCollectionJson.path("_links").has("surfaces"));
        assertTrue(enabledCollectionJson.path("_links").has("capabilities"));
        assertFalse(enabledCollectionJson.path("_links").has("actions"));
        assertTrue(enabledCollectionRow.path("_links").has("surfaces"));
        assertTrue(enabledCollectionRow.path("_links").has("capabilities"));
        assertFalse(enabledCollectionRow.path("_links").has("actions"));
        assertTrue(enabledItemJson.path("_links").has("surfaces"));
        assertTrue(enabledItemJson.path("_links").has("capabilities"));
        assertFalse(enabledItemJson.path("_links").has("actions"));

        setHateoasEnabled(false);
        JsonNode disabledCollectionJson = body(get("/payroll-view/all"));
        JsonNode disabledItemJson = body(get("/payroll-view/" + payrollId));

        assertTrue(disabledCollectionJson.path("_links").isMissingNode() || disabledCollectionJson.path("_links").isNull());
        assertTrue(disabledItemJson.path("_links").isMissingNode() || disabledItemJson.path("_links").isNull());
    }

    private void setHateoasEnabled(boolean enabled) {
        environment.getPropertySources().remove(OVERRIDE_SOURCE);
        environment.getPropertySources().addFirst(new MapPropertySource(
                OVERRIDE_SOURCE,
                Map.of("praxis.hateoas.enabled", Boolean.toString(enabled))
        ));
    }

}
