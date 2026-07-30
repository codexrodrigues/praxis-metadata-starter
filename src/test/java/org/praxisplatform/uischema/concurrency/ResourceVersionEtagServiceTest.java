package org.praxisplatform.uischema.concurrency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceVersionEtagServiceTest {

    private final ResourceVersionEtagService etags = new ResourceVersionEtagService("test-secret");

    @Test
    void bindsTagToTrustedIsolationScope() {
        ResourceVersionScope tenantA = new ResourceVersionScope("tenant-a|prod");
        ResourceVersionScope tenantB = new ResourceVersionScope("tenant-b|prod");

        String tenantATag = etags.create(tenantA, "human-resources.funcionarios", 42, 3);

        assertTrue(etags.matches(tenantATag, tenantA, "human-resources.funcionarios", 42, 3));
        assertFalse(etags.matches(tenantATag, tenantB, "human-resources.funcionarios", 42, 3));
        assertNotEquals(tenantATag, etags.create(tenantB, "human-resources.funcionarios", 42, 3));
    }

    @Test
    void rejectsUnsafeScopeKeys() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceVersionScope("tenant-a\nprod"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceVersionScope("  "));
    }

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
