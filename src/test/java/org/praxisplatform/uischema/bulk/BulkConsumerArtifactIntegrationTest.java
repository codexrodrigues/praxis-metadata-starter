package org.praxisplatform.uischema.bulk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a Java host can consume the current Metadata candidate as a real Maven artifact.
 *
 * <p>The test deliberately performs two ordered Maven builds in a new local repository. It first
 * archives and installs only the committed candidate's production sources under a Git-addressed version;
 * only after the JAR and POM hashes are known does it build the independent consumer fixture. The
 * fixture is not a reactor module and receives no starter source or {@code target/classes} path.</p>
 */
class BulkConsumerArtifactIntegrationTest {

    private static final String GROUP_PATH = "io/github/codexrodrigues";
    private static final String ARTIFACT_ID = "praxis-metadata-starter";
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration CONSUMER_TIMEOUT = Duration.ofMinutes(15);

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsFreshCandidateBeforeRunningIndependentPostgresHttpConsumer() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(project.resolve("pom.xml")), "test must run from the starter root");

        requireCommittedProduction(project);

        String gitRevision = capture(project, "git", "rev-parse", "HEAD").strip();
        assertTrue(gitRevision.matches("[0-9a-f]{40}"), "expected a full Git revision");
        String srcMainGitTree = capture(project, "git", "rev-parse", "HEAD:src/main").strip();
        String pomGitBlob = capture(project, "git", "rev-parse", "HEAD:pom.xml").strip();
        assertTrue(srcMainGitTree.matches("[0-9a-f]{40}"), "expected the committed src/main tree identity");
        assertTrue(pomGitBlob.matches("[0-9a-f]{40}"), "expected the committed pom.xml blob identity");

        Path isolatedRepository = temporaryDirectory.resolve("maven-repository");
        Files.createDirectory(isolatedRepository);
        try (Stream<Path> entries = Files.list(isolatedRepository)) {
            assertFalse(entries.findAny().isPresent(), "the Maven repository must start empty");
        }

        Path candidateProject = temporaryDirectory.resolve("candidate");
        archiveCommittedCandidate(project, candidateProject);
        String sourceTreeSha256 = sourceTreeSha256(candidateProject);
        String baseVersion = directProjectVersion(candidateProject.resolve("pom.xml"));
        String candidateVersion = baseVersion + "-b1f."
                + gitRevision.substring(0, 12) + "." + srcMainGitTree.substring(0, 12);
        rewriteDirectProjectVersion(candidateProject.resolve("pom.xml"), candidateVersion);

        Path settings = temporaryDirectory.resolve("central-only-settings.xml");
        Files.writeString(settings, centralOnlySettings(), StandardCharsets.UTF_8);

        Path target = project.resolve("target");
        Files.createDirectories(target);
        Path candidateLog = target.resolve("b1f-candidate-build.log");
        runMaven(project, candidateProject, settings, isolatedRepository, candidateLog, BUILD_TIMEOUT,
                "-Dmaven.test.skip=true", "-Dmaven.javadoc.skip=true", "-Dgpg.skip=true", "install");

        Path artifactDirectory = isolatedRepository.resolve(GROUP_PATH).resolve(ARTIFACT_ID)
                .resolve(candidateVersion);
        Path candidateJar = artifactDirectory.resolve(ARTIFACT_ID + "-" + candidateVersion + ".jar");
        Path candidatePom = artifactDirectory.resolve(ARTIFACT_ID + "-" + candidateVersion + ".pom");
        assertTrue(Files.isRegularFile(candidateJar), "fresh candidate JAR was not installed");
        assertTrue(Files.isRegularFile(candidatePom), "fresh candidate POM was not installed");
        String jarSha256 = sha256(candidateJar);
        String pomSha256 = sha256(candidatePom);

        Path consumerProject = temporaryDirectory.resolve("consumer");
        Path fixtureSource = project.resolve("src/test/fixtures/bulk-consumer-artifact");
        String fixtureSha256 = sha256Tree(fixtureSource);
        copyTree(fixtureSource, consumerProject);
        Path consumerEvidence = temporaryDirectory.resolve("consumer-evidence.properties");
        Path consumerLog = target.resolve("b1f-consumer-build.log");
        runMaven(project, consumerProject, settings, isolatedRepository, consumerLog, CONSUMER_TIMEOUT,
                "-Dpraxis.metadata.version=" + candidateVersion,
                "-Dcandidate.jar=" + candidateJar.toRealPath(),
                "-Dcandidate.jar.sha256=" + jarSha256,
                "-Dcandidate.source.root=" + candidateProject.toRealPath(),
                "-Dconsumer.fixture.sha256=" + fixtureSha256,
                "-Dconsumer.evidence.file=" + consumerEvidence.toAbsolutePath(),
                "test");

        assertTrue(Files.isRegularFile(consumerEvidence), "consumer did not emit its evidence record");
        Properties fixture = loadProperties(consumerEvidence);
        assertEquals(candidateVersion, fixture.getProperty("candidate.version"));
        assertEquals(jarSha256, fixture.getProperty("candidate.jar.sha256"));
        assertEquals(candidateJar.toRealPath().toString(), fixture.getProperty("candidate.codeSource"));
        assertEquals("true", fixture.getProperty("postgres.migrationsOptIn"));
        assertEquals("true", fixture.getProperty("http.bulkLifecycle"));
        assertEquals("true", fixture.getProperty("bulk.readyPublished"));
        assertEquals("true", fixture.getProperty("http.bulkActionRouteDispatch"));
        assertEquals("true", fixture.getProperty("http.openApi"));
        assertEquals("true", fixture.getProperty("http.filteredSchema"));
        assertEquals("true", fixture.getProperty("http.actionCatalog"));
        assertEquals(fixtureSha256, fixture.getProperty("fixture.source.sha256"));
        assertTrue(fixture.getProperty("http.openApiReserializedJsonUtf8Sha256").matches("[0-9a-f]{64}"));
        assertTrue(fixture.getProperty("http.filteredSchemaResponseUtf8Sha256").matches("[0-9a-f]{64}"));

        Properties evidence = new Properties();
        evidence.setProperty("candidate.gitRevision", gitRevision);
        evidence.setProperty("candidate.gitSrcMainTree", srcMainGitTree);
        evidence.setProperty("candidate.gitPomBlob", pomGitBlob);
        evidence.setProperty("candidate.sourceTree.sha256", sourceTreeSha256);
        evidence.setProperty("candidate.version", candidateVersion);
        evidence.setProperty("candidate.jar", candidateJar.toRealPath().toString());
        evidence.setProperty("candidate.jar.sha256", jarSha256);
        evidence.setProperty("candidate.pom", candidatePom.toRealPath().toString());
        evidence.setProperty("candidate.pom.sha256", pomSha256);
        evidence.setProperty("consumer.fixture.sha256", fixtureSha256);
        fixture.forEach((key, value) -> evidence.setProperty("consumer." + key, value.toString()));
        try (var output = Files.newOutputStream(target.resolve("b1f-consumer-artifact-evidence.properties"))) {
            evidence.store(output, "B1-F/T11 isolated Maven consumer evidence");
        }
    }

    private static String sourceTreeSha256(Path project) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> inputs = new ArrayList<>();
        inputs.add(project.resolve("pom.xml"));
        try (Stream<Path> paths = Files.walk(project.resolve("src/main"))) {
            paths.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).forEach(inputs::add);
        }
        for (Path input : inputs) {
            String relative = project.relativize(input).toString().replace(input.getFileSystem().getSeparator(), "/");
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream stream = Files.newInputStream(input)) {
                stream.transferTo(new java.security.DigestOutputStream(OutputStreamSink.INSTANCE, digest));
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            stream.transferTo(new java.security.DigestOutputStream(OutputStreamSink.INSTANCE, digest));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256Tree(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList()) {
                digest.update(root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/")
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream stream = Files.newInputStream(file)) {
                    stream.transferTo(new java.security.DigestOutputStream(OutputStreamSink.INSTANCE, digest));
                }
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String directProjectVersion(Path pom) throws Exception {
        var document = parsePom(pom);
        return directChild(document.getDocumentElement(), "version").getTextContent().strip();
    }

    private static void rewriteDirectProjectVersion(Path pom, String version) throws Exception {
        var document = parsePom(pom);
        directChild(document.getDocumentElement(), "version").setTextContent(version);
        TransformerFactory transformers = TransformerFactory.newInstance();
        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var transformer = transformers.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(pom.toFile()));
    }

    private static org.w3c.dom.Document parsePom(Path pom) throws Exception {
        DocumentBuilderFactory parsers = DocumentBuilderFactory.newInstance();
        parsers.setNamespaceAware(true);
        parsers.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parsers.setFeature("http://xml.org/sax/features/external-general-entities", false);
        parsers.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        parsers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parsers.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return parsers.newDocumentBuilder().parse(pom.toFile());
    }

    private static Element directChild(Element parent, String localName) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) return element;
        }
        throw new IllegalStateException("Missing direct <" + localName + "> in " + parent.getTagName());
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    @Test
    void rejectsUncommittedProductionBeforeArchivingAnOlderCandidate() throws Exception {
        Path project = temporaryDirectory.resolve("source-provenance");
        Files.createDirectories(project.resolve("src/main"));
        Path pom = project.resolve("pom.xml");
        Path source = project.resolve("src/main/fixture.txt");
        Files.writeString(pom, "committed-pom\n", StandardCharsets.UTF_8);
        Files.writeString(source, "committed-production\n", StandardCharsets.UTF_8);
        capture(project, "git", "init", "--quiet");
        capture(project, "git", "add", "pom.xml", "src/main");
        capture(project, "git", "-c", "user.name=Praxis Test", "-c", "user.email=praxis-test@example.invalid",
                "commit", "--quiet", "-m", "provenance baseline");
        requireCommittedProduction(project);
        Files.writeString(source, "uncommitted-production\n", StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> requireCommittedProduction(project));
        Files.writeString(source, "committed-production\r\n", StandardCharsets.UTF_8);
        requireCommittedProduction(project); // Checkout EOL alone does not change the candidate semantics.
        Files.writeString(pom, "uncommitted-pom\n", StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> requireCommittedProduction(project));
        Files.writeString(pom, "committed-pom\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("src/main/untracked.txt"), "new production\n", StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> requireCommittedProduction(project));
    }

    private static void requireCommittedProduction(Path project) throws Exception {
        // This consumer builds HEAD, so a green result must not silently certify older production.
        // Ignore only line endings/trailing whitespace for portable Git checkouts.
        assertTrue(capture(project, "git", "-c", "core.safecrlf=false", "diff", "--no-ext-diff", "--ignore-space-at-eol", "HEAD",
                "--", "pom.xml", "src/main").isBlank(),
                "Commit the intended production sources and POM before the independent artifact proof");
        assertTrue(capture(project, "git", "ls-files", "--others", "--exclude-standard",
                "--", "src/main").isBlank(),
                "Untracked production sources are absent from the committed candidate archive");

    }

    private static void archiveCommittedCandidate(Path project, Path destination) throws Exception {
        Path archive = destination.getParent().resolve("candidate.zip");
        capture(project, "git", "archive", "--format=zip", "--output=" + archive, "HEAD", "pom.xml", "src/main");
        Files.createDirectories(destination);
        try (var raw = Files.newInputStream(archive); var zip = new ZipInputStream(raw)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path extracted = destination.resolve(entry.getName()).normalize();
                if (!extracted.startsWith(destination)) throw new IOException("Unsafe Git archive entry");
                if (entry.isDirectory()) Files.createDirectories(extracted);
                else {
                    Files.createDirectories(extracted.getParent());
                    Files.copy(zip, extracted, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static void runMaven(Path wrapperProject, Path project, Path settings, Path repository,
            Path log, Duration timeout, String... goals) throws Exception {
        List<String> command = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (windows) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
            command.add("call \"" + wrapperProject.resolve("mvnw.cmd").toAbsolutePath() + "\"");
        } else {
            command.add("sh");
            command.add(wrapperProject.resolve("mvnw").toString());
        }
        command.add("-B");
        command.add("-ntp");
        command.add("-s");
        command.add(settings.toString());
        command.add("-f");
        command.add(project.resolve("pom.xml").toString());
        command.add("-Dmaven.repo.local=" + repository);
        command.add("-Dstyle.color=never");
        command.addAll(List.of(goals));

        ProcessBuilder builder = new ProcessBuilder(command);
        // Keep the wrapper's own .mvn directory available while Maven targets an archived POM.
        builder.directory(wrapperProject.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        builder.environment().put("MAVEN_SKIP_RC", "true");
        builder.environment().remove("MAVEN_ARGS");
        Process process = builder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("Timed out running Maven; log: " + log);
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("Maven exited " + process.exitValue() + "; log: " + log
                    + System.lineSeparator() + tail(log, 80));
        }
    }

    private static String capture(Path directory, String... command) throws Exception {
        Path output = Files.createTempFile("praxis-b1f-command-", ".log");
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("Command timed out: " + String.join(" ", command));
            }
            String content = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError("Command failed: " + String.join(" ", command)
                        + System.lineSeparator() + content);
            }
            return content;
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static String tail(Path file, int lines) throws IOException {
        List<String> content = Files.readAllLines(file, StandardCharsets.UTF_8);
        return String.join(System.lineSeparator(), content.subList(Math.max(0, content.size() - lines), content.size()));
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static String centralOnlySettings() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
                  <mirrors>
                    <mirror>
                      <id>central-only</id>
                      <mirrorOf>*</mirrorOf>
                      <url>https://repo.maven.apache.org/maven2</url>
                    </mirror>
                  </mirrors>
                </settings>
                """;
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();
        @Override public void write(int ignored) { }
        @Override public void write(byte[] bytes, int offset, int length) { }
    }
}
