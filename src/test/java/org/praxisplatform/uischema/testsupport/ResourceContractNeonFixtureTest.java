package org.praxisplatform.uischema.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline guard tests; these do not certify PostgreSQL or actual resource cleanup. */
class ResourceContractNeonFixtureTest {
    @TempDir Path directory;

    @Test void privateFilesRejectRelativePathsPermissionsAndSymlinks() throws Exception {
        Path file = directory.resolve("fixture.private");
        Files.writeString(file, "non-secret fixture value");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertDoesNotThrow(() -> ResourceContractNeonFixture.requirePrivate(file));
        assertThrows(IllegalStateException.class,
                () -> ResourceContractNeonFixture.requirePrivate(Path.of("relative.private")));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalStateException.class, () -> ResourceContractNeonFixture.requirePrivate(file));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Path link = directory.resolve("symlink.private");
        Files.createSymbolicLink(link, file);
        assertThrows(IllegalStateException.class, () -> ResourceContractNeonFixture.requirePrivate(link));
    }

    @Test void cleanupRequiresAnObservedUnchangedObjectOid() {
        assertDoesNotThrow(() -> ResourceContractNeonFixture.requireMatchingOid(12345, 12345));
        assertThrows(IllegalStateException.class, () -> ResourceContractNeonFixture.requireMatchingOid(12346, 12345));
        assertThrows(IllegalStateException.class, () -> ResourceContractNeonFixture.requireMatchingOid(0, 12345));
        assertThrows(IllegalStateException.class, () -> ResourceContractNeonFixture.requireMatchingOid(12345, 0));
        assertThrows(IllegalStateException.class, () -> ResourceContractNeonFixture.requireMatchingOid(0, 0));
    }

    @Test void cleanupRunsAfterSpringClosesItsContextAndDoesNotAccessAnInactiveContext() throws Exception {
        var listener = new ResourceContractNeonSpringCleanupListener();
        assertTrue(listener.getOrder() < new DirtiesContextTestExecutionListener().getOrder(),
                "Spring afterTestClass listeners run in reverse order");
        TestContext context = mock(TestContext.class);
        doReturn(ResourceContractNeonFixtureTest.class).when(context).getTestClass();
        listener.afterTestClass(context);
        verify(context, never()).getApplicationContext();
    }
}
