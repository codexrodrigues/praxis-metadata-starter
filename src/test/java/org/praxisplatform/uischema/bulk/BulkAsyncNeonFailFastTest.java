package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/** Real Jupiter lifecycle oracle; no database, launcher dependency or POM change. */
class BulkAsyncNeonFailFastTest {
    @Test void bodyFailureBlocksLaterBodyAndRunsCleanup() { verify(BodyFailure.class, 1, 1); }
    @Test void beforeEachFailureBlocksLaterBodyAndRunsCleanup() { verify(BeforeFailure.class, 0, 1); }
    @Test void afterEachFailureBlocksLaterBodyAndRunsCleanup() { verify(AfterFailure.class, 1, 1); }
    @Test void beforeAllFailurePreventsAllBodiesAndRunsCleanup() { verify(SetupFailure.class, 0, 0); }

    private static void verify(Class<? extends Scenario> type, int bodies, int aborted) {
        Scenario.bodies = 0; Scenario.cleanup = 0;
        var parameters = new ConfigurationParameters() {
            public Optional<String> get(String key) { return Optional.empty(); }
            public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
            public int size() { return 0; }
            public Set<String> keySet() { return Set.of(); }
        };
        var output = new org.junit.platform.engine.reporting.OutputDirectoryProvider() {
            public java.nio.file.Path getRootDirectory() { return java.nio.file.Path.of(System.getProperty("java.io.tmpdir")); }
            public java.nio.file.Path createOutputDirectory(TestDescriptor descriptor) {
                throw new AssertionError("These lifecycle probes do not publish files");
            }
        };
        var request = new EngineDiscoveryRequest() {
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> typeOfSelector) {
                var selector = DiscoverySelectors.selectClass(type);
                return typeOfSelector.isInstance(selector) ? List.of(typeOfSelector.cast(selector)) : List.of();
            }
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> filter) { return List.of(); }
            public ConfigurationParameters getConfigurationParameters() { return parameters; }
            public org.junit.platform.engine.reporting.OutputDirectoryProvider getOutputDirectoryProvider() { return output; }
        };
        var engine = new JupiterTestEngine();
        var root = engine.discover(request, UniqueId.forEngine("junit-jupiter"));
        var results = new ArrayList<TestExecutionResult>();
        engine.execute(ExecutionRequest.create(root, new EngineExecutionListener() {
            @Override public void executionFinished(TestDescriptor descriptor, TestExecutionResult result) {
                if (descriptor.isTest() || result.getStatus() == TestExecutionResult.Status.FAILED) results.add(result);
            }
        }, parameters, output));
        assertThat(Scenario.bodies).isEqualTo(bodies);
        assertThat(Scenario.cleanup).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getStatus() == TestExecutionResult.Status.FAILED)).hasSize(1);
        assertThat(results.stream().filter(r -> r.getStatus() == TestExecutionResult.Status.ABORTED)).hasSize(aborted);
        assertThat(results.stream().filter(r -> r.getStatus() == TestExecutionResult.Status.SUCCESSFUL)).isEmpty();
        assertThat(results.stream().filter(r -> r.getStatus() == TestExecutionResult.Status.FAILED)
                .findFirst().orElseThrow().getThrowable().orElseThrow()).hasMessage("PRIMARY_PHASE_FAILURE");
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Execution(ExecutionMode.SAME_THREAD)
    abstract static class Scenario {
        static int bodies, cleanup;
        @RegisterExtension final BulkAsyncPublicAdmissionNeonTest.FailFast gate = new BulkAsyncPublicAdmissionNeonTest.FailFast();
        @Test @Order(1) void first() { bodies++; firstAction(); }
        @Test @Order(2) void second() { bodies++; }
        void firstAction() { }
        @AfterAll void close() { cleanup++; }
    }
    static class BodyFailure extends Scenario {
        @Override void firstAction() { throw new IllegalStateException("PRIMARY_PHASE_FAILURE"); }
    }
    static class BeforeFailure extends Scenario {
        @BeforeEach void before() { throw new IllegalStateException("PRIMARY_PHASE_FAILURE"); }
    }
    static class AfterFailure extends Scenario {
        @AfterEach void after(TestInfo info) {
            if (info.getTestMethod().orElseThrow().getName().equals("first"))
                throw new IllegalStateException("PRIMARY_PHASE_FAILURE");
        }
    }
    static class SetupFailure extends Scenario {
        @BeforeAll void setup() { throw new IllegalStateException("PRIMARY_PHASE_FAILURE"); }
    }
}
