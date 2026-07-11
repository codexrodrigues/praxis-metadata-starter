package org.praxisplatform.uischema.concurrency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceVersionEtagServiceTest {

    private final ResourceVersionEtagService etags = new ResourceVersionEtagService("test-secret");

    @Test
    void bindsTagToResourceIdentityAndVersion() {
        String tag = etags.create("human-resources.funcionarios", 42, 3);

        assertEquals(tag, etags.create("human-resources.funcionarios", 42, 3));
        assertFalse(etags.matches(tag, "human-resources.funcionarios", 42, 4));
        assertFalse(etags.matches(tag, "human-resources.eventos-folha", 42, 3));
    }

    @Test
    void rejectsMissingWildcardMalformedAndStalePreconditions() {
        String tag = etags.create("human-resources.funcionarios", 42, 3);

        assertEquals("RESOURCE_VERSION_REQUIRED", assertThrows(ResourceVersionPreconditionException.class,
                () -> ResourceVersionPreconditions.requireMatch(etags, null, "human-resources.funcionarios", 42, 3)).code());
        assertEquals("INVALID_RESOURCE_VERSION", assertThrows(ResourceVersionPreconditionException.class,
                () -> ResourceVersionPreconditions.requireMatch(etags, "*", "human-resources.funcionarios", 42, 3)).code());
        assertEquals("INVALID_RESOURCE_VERSION", assertThrows(ResourceVersionPreconditionException.class,
                () -> ResourceVersionPreconditions.requireMatch(etags, "invalid", "human-resources.funcionarios", 42, 3)).code());
        assertEquals("STALE_RESOURCE_VERSION", assertThrows(ResourceVersionPreconditionException.class,
                () -> ResourceVersionPreconditions.requireMatch(etags, tag, "human-resources.funcionarios", 42, 4)).code());
        assertDoesNotThrow(() -> ResourceVersionPreconditions.requireMatch(
                etags, tag, "human-resources.funcionarios", 42, 3
        ));
    }
}
