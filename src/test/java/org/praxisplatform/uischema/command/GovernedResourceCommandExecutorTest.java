package org.praxisplatform.uischema.command;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityContext;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedResourceCommandExecutorTest {

    @Test
    void executesProviderAndPreservesReadAfterWritePolicy() {
        ResourceCommandExecutionRequest request = ResourceCommandExecutionRequest.item(
                "example.people",
                "/api/example/people",
                "update",
                42L,
                Map.of("name", "Ada"),
                ResourceCommandResponsePolicy.READ_AFTER_WRITE
        );
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(providerRequest ->
                ResourceCommandExecutionResult.success(
                        providerRequest,
                        providerRequest.resourceId(),
                        Map.of("id", providerRequest.resourceId(), "name", "Ada"),
                        Map.of("smokeId", "command-smoke-1")
                )
        );

        ResourceCommandExecutionResult result = executor.execute(request);

        assertTrue(result.successful());
        assertEquals(ResourceCommandOutcome.SUCCESS, result.outcome());
        assertEquals(ResourceCommandResponsePolicy.READ_AFTER_WRITE, result.responsePolicy());
        assertEquals(42L, result.publicId());
        assertEquals(Map.of("id", 42L, "name", "Ada"), result.body());
        assertEquals("command-smoke-1", result.evidence().get("smokeId"));
    }

    @Test
    void deniesCommandWhenAvailabilityProviderDeniesAndDoesNotExecuteProvider() {
        AtomicBoolean providerExecuted = new AtomicBoolean(false);
        ResourceOperationAvailabilityProvider availabilityProvider = context ->
                AvailabilityDecision.deny("maintenance-window", Map.of(
                        "publicPolicy", "closed",
                        "sessionToken", "private"
                ));
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(
                request -> {
                    providerExecuted.set(true);
                    return ResourceCommandExecutionResult.success(request, request.resourceId(), null, Map.of());
                },
                availabilityProvider,
                ResourceCommandEvidenceSanitizer.defaults()
        );

        ResourceCommandExecutionResult result = executor.execute(ResourceCommandExecutionRequest.item(
                "example.people",
                "/api/example/people",
                "delete",
                42L,
                null,
                ResourceCommandResponsePolicy.NO_CONTENT
        ));

        assertFalse(providerExecuted.get());
        assertFalse(result.successful());
        assertEquals(ResourceCommandOutcome.PERMISSION_DENIED, result.outcome());
        assertEquals(ResourceCommandErrorCategory.PERMISSION, result.messages().getFirst().category());
        assertEquals("maintenance-window", result.messages().getFirst().message());
        assertEquals("closed", result.evidence().get("publicPolicy"));
        assertFalse(result.evidence().containsKey("sessionToken"));
    }

    @Test
    void sanitizesPrivateEvidenceReturnedByProvider() {
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(request ->
                ResourceCommandExecutionResult.success(
                        request,
                        "PUB-1",
                        Map.of("id", "PUB-1"),
                        Map.of(
                                "publicProbe", "passed",
                                "rowid", "AAABBB",
                                "sqlText", "select * from private_table",
                                "procedureName", "PRIVATE_PKG.RUN"
                        )
                )
        );

        ResourceCommandExecutionResult result = executor.execute(ResourceCommandExecutionRequest.collection(
                "example.people",
                "/api/example/people",
                "create",
                Map.of("name", "Ada"),
                ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT
        ));

        assertEquals("passed", result.evidence().get("publicProbe"));
        assertFalse(result.evidence().containsKey("rowid"));
        assertFalse(result.evidence().containsKey("sqlText"));
        assertFalse(result.evidence().containsKey("procedureName"));
    }

    @Test
    void mapsProviderPublicExceptionToStableOutcome() {
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(request -> {
            throw new ResourceCommandExecutionException(
                    ResourceCommandOutcome.CONFLICT_DUPLICATE,
                    "Duplicated public key.",
                    Map.of("constraint", "public-unique-key", "sqlState", "private")
            );
        });

        ResourceCommandExecutionResult result = executor.execute(ResourceCommandExecutionRequest.collection(
                "example.people",
                "/api/example/people",
                "create",
                Map.of("name", "Ada"),
                ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT
        ));

        assertFalse(result.successful());
        assertEquals(ResourceCommandOutcome.CONFLICT_DUPLICATE, result.outcome());
        assertEquals(ResourceCommandErrorCategory.CONFLICT_DUPLICATE, result.messages().getFirst().category());
        assertEquals("Duplicated public key.", result.messages().getFirst().message());
        assertEquals("public-unique-key", result.evidence().get("constraint"));
        assertFalse(result.evidence().containsKey("sqlState"));
    }

    @Test
    void mapsUnexpectedProviderExceptionToSanitizedUnexpectedOutcome() {
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(request -> {
            throw new IllegalStateException("private backend exploded");
        });

        ResourceCommandExecutionResult result = executor.execute(ResourceCommandExecutionRequest.item(
                "example.people",
                "/api/example/people",
                "update",
                42L,
                Map.of("name", "Ada"),
                ResourceCommandResponsePolicy.READ_AFTER_WRITE
        ));

        assertFalse(result.successful());
        assertEquals(ResourceCommandOutcome.UNEXPECTED_SANITIZED, result.outcome());
        assertEquals("Command execution failed.", result.messages().getFirst().message());
        assertEquals("IllegalStateException", result.evidence().get("exceptionType"));
    }

    @Test
    void mapsResponseStatusExceptionToGovernedPublicOutcome() {
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(request -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "State not allowed: DRAFT");
        });

        ResourceCommandExecutionResult result = executor.execute(ResourceCommandExecutionRequest.item(
                "example.people",
                "/api/example/people",
                "approve",
                42L,
                Map.of("reason", "manual"),
                ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT
        ));

        assertFalse(result.successful());
        assertEquals(ResourceCommandOutcome.CONFLICT_DEPENDENCY, result.outcome());
        assertEquals(ResourceCommandErrorCategory.CONFLICT_DEPENDENCY, result.messages().getFirst().category());
        assertEquals("State not allowed: DRAFT", result.messages().getFirst().message());
        assertEquals(409, result.evidence().get("status"));
    }

    @Test
    void validatesItemScopedRequestNeedsPublicId() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ResourceCommandExecutionRequest.item(
                        "example.people",
                        "/api/example/people",
                        "update",
                        null,
                        Map.of("name", "Ada"),
                        ResourceCommandResponsePolicy.READ_AFTER_WRITE
                ));

        assertEquals("resourceId is required for item-scoped commands", exception.getMessage());
    }

    @Test
    void passesPublicCommandContextToAvailabilityProvider() {
        final ResourceOperationAvailabilityContext[] captured = new ResourceOperationAvailabilityContext[1];
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(
                request -> ResourceCommandExecutionResult.accepted(request, "job-1", Map.of()),
                context -> {
                    captured[0] = context;
                    return AvailabilityDecision.allow(Map.of("publicDecision", "ok"));
                },
                ResourceCommandEvidenceSanitizer.defaults()
        );
        ResourceCommandExecutionRequest request = new ResourceCommandExecutionRequest(
                "example.people",
                "/api/example/people",
                "archive",
                ResourceCommandScope.ITEM,
                42L,
                null,
                ResourceCommandResponsePolicy.ACCEPTED_ASYNC,
                Map.of("reason", "manual-review")
        );

        ResourceCommandExecutionResult result = executor.execute(request);

        assertEquals(ResourceCommandOutcome.ACCEPTED, result.outcome());
        assertEquals("example.people", captured[0].resourceKey());
        assertEquals("/api/example/people", captured[0].resourcePath());
        assertEquals("archive", captured[0].operationId());
        assertEquals("ITEM", captured[0].scope());
        assertEquals(42L, captured[0].resourceId());
        assertEquals("manual-review", captured[0].metadata().get("reason"));
        assertNull(captured[0].resourceState());
    }
}
