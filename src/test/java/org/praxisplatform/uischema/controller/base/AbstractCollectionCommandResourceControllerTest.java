package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.capability.CapabilitySnapshot;
import org.praxisplatform.uischema.command.ResourceCommandExecutionResult;
import org.praxisplatform.uischema.command.ResourceCommandResponsePolicy;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractCollectionCommandResourceControllerTest {

    private TestController controller;
    private ActionCatalogService actionCatalogService;
    private CapabilityService capabilityService;

    @BeforeEach
    void setUp() {
        controller = new TestController();
        actionCatalogService = mock(ActionCatalogService.class);
        capabilityService = mock(CapabilityService.class);
        ReflectionTestUtils.setField(controller, "actionCatalogService", actionCatalogService);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilityService);
        controller.initialize();
    }

    @Test
    void delegatesCollectionActionDiscoveryUsingCanonicalResourceKey() {
        ActionCatalogResponse catalog = new ActionCatalogResponse(
                "example.command-requests", "/api/example/command-requests", "example", null, List.of()
        );
        when(actionCatalogService.findCollectionActions("example.command-requests")).thenReturn(catalog);

        ResponseEntity<ActionCatalogResponse> response = controller.getCollectionActions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(catalog, response.getBody());
        verify(actionCatalogService).findCollectionActions("example.command-requests");
    }

    @Test
    void delegatesCapabilitiesWithoutInventingQuerySupport() {
        CapabilitySnapshot snapshot = new CapabilitySnapshot(
                "example.command-requests", "/api/example/command-requests", "example", null,
                Map.of(), Map.of(), List.of(), List.of(), null
        );
        when(capabilityService.collectionCapabilities(
                "example.command-requests", "/api/example/command-requests"
        )).thenReturn(snapshot);

        ResponseEntity<CapabilitySnapshot> response = controller.getCollectionCapabilities();

        assertEquals(snapshot, response.getBody());
        assertTrue(response.getBody().canonicalOperations().isEmpty());
        verify(capabilityService).collectionCapabilities(
                "example.command-requests", "/api/example/command-requests"
        );
    }

    @Test
    void executesGovernedCommandAndPublishesOnlyCommandDiscoveryLinks() {
        when(capabilityService.collectionOperationAvailability(
                "example.command-requests", "/api/example/command-requests", "evaluate"
        )).thenReturn(AvailabilityDecision.allowAll());

        ResponseEntity<?> response = controller.evaluate(Map.of("amount", 100));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        RestApiResponse<?> body = assertInstanceOf(RestApiResponse.class, response.getBody());
        assertEquals(Map.of("decision", "ALLOW"), body.getData());
        assertTrue(body.getLinks().asMap().containsKey("actions"));
        assertTrue(body.getLinks().asMap().containsKey("capabilities"));
        assertTrue(body.getLinks().asMap().containsKey("schema"));
        assertEquals(3, body.getLinks().asMap().size());
    }

    @Test
    void deniesUnavailableCommandBeforeInvokingProvider() {
        AtomicBoolean invoked = new AtomicBoolean();
        when(capabilityService.collectionOperationAvailability(
                "example.command-requests", "/api/example/command-requests", "evaluate"
        )).thenReturn(AvailabilityDecision.deny("snapshot-not-ready", Map.of("snapshotReady", false)));

        ResponseEntity<?> response = controller.evaluate(invoked);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertFalse(invoked.get());
        RestApiResponse<?> body = assertInstanceOf(RestApiResponse.class, response.getBody());
        assertEquals("failure", body.getStatus());
        assertEquals("snapshot-not-ready", body.getMessage());
    }

    @ApiResource(value = "/api/example/command-requests", resourceKey = "example.command-requests")
    static final class TestController extends AbstractCollectionCommandResourceController {

        void initialize() {
            initializeBasePath();
        }

        ResponseEntity<?> evaluate(Object payload) {
            return executeCollectionCommand(
                    "evaluate",
                    payload,
                    ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT,
                    request -> {
                        if (payload instanceof AtomicBoolean invoked) {
                            invoked.set(true);
                        }
                        return ResourceCommandExecutionResult.success(
                                request,
                                null,
                                Map.of("decision", "ALLOW"),
                                Map.of("snapshotKey", "active")
                        );
                    }
            );
        }
    }
}
