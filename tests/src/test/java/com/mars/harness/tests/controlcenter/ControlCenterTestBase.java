package com.mars.harness.tests.controlcenter;

import com.mars.harness.cli.HarnessFactory;
import com.mars.harness.controlcenter.ControlCenterApplication;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.tests.support.SimulatedToolchain;
import com.mars.harness.tests.support.TestHarness;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Boots the real Control Center (security, controllers, coordinator, SSE) over the production
 * engine wiring, with only the build and runtime tools replaced by {@link SimulatedToolchain},
 * exactly as the engine's own end-to-end tests do. Each test class gets its own runs root and a
 * copy of the fixtures as its only repository root.
 */
@SpringBootTest(classes = {ControlCenterApplication.class, ControlCenterTestBase.SimulatedEngine.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "mars.control-center.max-concurrent-runs=2",
                "management.otlp.tracing.export.enabled=false"})
abstract class ControlCenterTestBase {

    static final Path ROOT = createRoot();
    static final Path REPOS = ROOT.resolve("repos");
    static final Path RUNS = ROOT.resolve("runs");
    static final SimulatedToolchain TOOLS = new SimulatedToolchain();

    @LocalServerPort
    int port;

    private static Path createRoot() {
        try {
            Path root = Files.createTempDirectory("mars-control-center-it");
            TestHarness.copyTree(TestHarness.fixture("composite"), root.resolve("repos").resolve("composite"));
            TestHarness.copyTree(TestHarness.fixture("migration"), root.resolve("repos").resolve("migration"));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mars.control-center.harness-root", () -> TestHarness.harnessRoot().toString());
        registry.add("mars.control-center.runs-root", RUNS::toString);
        registry.add("mars.control-center.repository-roots[0]", REPOS::toString);
    }

    ControlCenterClient client(String user) {
        return new ControlCenterClient(port).login(user);
    }

    /** The production composition root with the simulated build and runtime tools. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SimulatedEngine {
        @Bean
        @Primary
        EngineConfig simulatedEngineConfig() {
            return HarnessFactory.config(TestHarness.harnessRoot(), RUNS, null, TestHarness.TODAY, true, false)
                    .withRuntime(TOOLS, TOOLS);
        }
    }
}
