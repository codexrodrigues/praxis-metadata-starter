package org.praxisplatform.uischema.concurrency;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceRepresentationResultTest {

    @Test
    void preservesBodyAndCapturedRevisionWithoutCopyingTheProjection() {
        Object body = new Object();
        var result = ResourceRepresentationResult.versioned(body, 0);

        assertSame(body, result.body());
        assertEquals(0, result.requirePersistedVersion());
    }

    @Test
    void permitsMissingRevisionOnlyWhenNoVersionIsRequired() {
        var result = ResourceRepresentationResult.unversioned("ordinary resource");

        assertTrue(result.persistedVersion().isEmpty());
        assertThrows(IllegalStateException.class, result::requirePersistedVersion);
    }

    @Test
    void rejectsNullBodyOrRevisionContainerAndNegativeRevisions() {
        assertThrows(NullPointerException.class,
                () -> new ResourceRepresentationResult<>(null, OptionalLong.empty()));
        assertThrows(NullPointerException.class,
                () -> new ResourceRepresentationResult<>("body", null));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceRepresentationResult.versioned("body", -1));
    }
}
