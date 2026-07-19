package org.praxisplatform.uischema.rest.failure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceOperationFailureTest {

    @Test
    void shouldNormalizePublicValues() {
        ResourceOperationFailure failure = new ResourceOperationFailure(
                ResourceOperationFailureKind.INVALID_INPUT,
                " calendar.invalid ",
                " Invalid calendar. ",
                " evento "
        );

        assertEquals("calendar.invalid", failure.code());
        assertEquals("Invalid calendar.", failure.safeMessage());
        assertEquals("evento", failure.target());
    }

    @Test
    void shouldRejectControlCharactersInPublicCodeOrTarget() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceOperationFailure(
                ResourceOperationFailureKind.INVALID_INPUT,
                "calendar.invalid\nprivate",
                "Invalid calendar.",
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new ResourceOperationFailure(
                ResourceOperationFailureKind.INVALID_INPUT,
                "calendar.invalid",
                "Invalid calendar.",
                "evento\rprivate"
        ));
    }
}
