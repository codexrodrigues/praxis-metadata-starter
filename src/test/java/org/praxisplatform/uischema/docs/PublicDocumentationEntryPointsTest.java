package org.praxisplatform.uischema.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicDocumentationEntryPointsTest {

    @Test
    void publicEntryPointsMentionSemanticDiscoverySurfaces() throws IOException {
        String readme = readNormalized("README.md");
        String changelog = readNormalized("CHANGELOG.md");
        String conformance = readNormalized("docs/spec/CONFORMANCE.md");

        assertAll(
                () -> assertTrue(
                        readme.contains("/schemas/surfaces"),
                        "README.md should mention /schemas/surfaces"
                ),
                () -> assertTrue(
                        readme.contains("/schemas/actions"),
                        "README.md should mention /schemas/actions"
                ),
                () -> assertTrue(
                        readme.contains("/schemas/domain"),
                        "README.md should mention /schemas/domain"
                ),
                () -> assertTrue(
                        changelog.contains("uisurface") || changelog.contains("/schemas/surfaces"),
                        "CHANGELOG.md should mention the semantic surface rollout"
                ),
                () -> assertTrue(
                        changelog.contains("workflowaction") || changelog.contains("/schemas/actions"),
                        "CHANGELOG.md should mention the workflow action rollout"
                ),
                () -> assertTrue(
                        conformance.contains("/schemas/surfaces"),
                        "CONFORMANCE.md should mention /schemas/surfaces"
                ),
                () -> assertTrue(
                        conformance.contains("/schemas/actions"),
                        "CONFORMANCE.md should mention /schemas/actions"
                ),
                () -> assertTrue(
                        conformance.contains("/schemas/domain"),
                        "CONFORMANCE.md should mention /schemas/domain"
                )
        );
    }

    private String readNormalized(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
    }
}
