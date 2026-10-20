package org.praxisplatform.uischema.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicDocumentationEntryPointsTest {

    @Test
    void publicEntryPointsMentionSemanticDiscoverySurfaces() throws IOException {
        String readme = readNormalized("README.md");
        String docsIndex = readNormalized("docs/index.md");
        String changelog = readNormalized("CHANGELOG.md");
        String conformance = readNormalized("docs/spec/CONFORMANCE.md");
        String guia01 = readNormalized("docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md");
        String javadocOverview = readNormalized("src/main/javadoc/overview.html");

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
                        readme.contains("/praxis/cockpit"),
                        "README.md should highlight the embedded /praxis/cockpit"
                ),
                () -> assertTrue(
                        readme.contains("crud-studio-zero-deploy-demo.gif") && readme.contains("crud-studio-zero-deploy-demo.mp4"),
                        "README.md should embed the CRUD Studio Zero-Deploy video demonstration"
                ),
                () -> assertTrue(
                        docsIndex.contains("crud-studio-zero-deploy-demo.mp4"),
                        "docs/index.md should embed the CRUD Studio Zero-Deploy video demonstration"
                ),
                () -> assertTrue(
                        readme.contains("quickstart") && readme.contains("docs/technical/bulk-and-release-candidates-status.md"),
                        "README.md should include the didactic quickstart and reference the consolidated technical status doc"
                ),
                () -> assertTrue(
                        readme.indexOf("o que é o `praxis-metadata-starter`") < readme.indexOf("docs/technical/bulk-and-release-candidates-status.md"),
                        "README.md must introduce the project value proposition before technical release candidate logs"
                ),
                () -> assertTrue(
                        docsIndex.contains("comece por objetivo") && docsIndex.contains("technical/bulk-and-release-candidates-status.html"),
                        "docs/index.md should organize entry points by goal and link to the consolidated technical status page"
                ),
                () -> assertFalse(
                        guia01.contains("<version>5.0.0-"),
                        "GUIA-01 must not reference outdated 5.0.0-* Maven versions"
                ),
                () -> assertFalse(
                        javadocOverview.contains("5.0.0-"),
                        "Javadoc overview.html must not reference outdated 5.0.0-* Maven versions"
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
