package com.mars.harness.tests.support;

import com.bootshift.core.util.Hashing;
import com.mars.harness.cli.HarnessFactory;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.run.RunSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Wires the production composition root ({@link HarnessFactory}) with the simulated build and
 * runtime tools, over a copy of a fixture. Everything else (Bootshift stages, identity, graph,
 * both capability packs, the Mutation Gateway, stores, policy) is the production code.
 */
public final class TestHarness {

    public static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    public final Path root;
    public final Path runs;
    public final Path repository;
    public final SimulatedToolchain tools;
    public final EngineConfig config;
    public final HarnessEngine engine;

    public final LocalDate today;

    private TestHarness(Path root, Path runs, Path repository, SimulatedToolchain tools, LocalDate today) {
        this.root = root;
        this.runs = runs;
        this.repository = repository;
        this.tools = tools;
        this.today = today;
        this.config = HarnessFactory.config(root, runs, null, today, true, false).withRuntime(tools, tools);
        this.engine = new HarnessEngine(config);
    }

    /** The harness installation root (the repository), from the {@code harness.root} system property. */
    public static Path harnessRoot() {
        String property = System.getProperty("harness.root");
        Path root = property != null ? Path.of(property) : Path.of("..");
        return root.toAbsolutePath().normalize();
    }

    public static Path fixture(String relative) {
        return harnessRoot().resolve("fixtures").resolve(relative);
    }

    /** Copies {@code fixtures/<fixture>} into {@code temp/repo} and wires a harness over it. */
    public static TestHarness over(Path temp, String fixture) {
        return over(temp, fixture, TODAY);
    }

    /** As {@link #over(Path, String)}, with a different evaluation date for lifecycle facts. */
    public static TestHarness over(Path temp, String fixture, LocalDate today) {
        Path repo = temp.resolve("repo");
        copyTree(fixture(fixture), repo);
        return new TestHarness(harnessRoot(), temp.resolve("runs"), repo, new SimulatedToolchain(), today);
    }

    /** A second engine over the same runs root: a fresh process resuming the run. */
    public TestHarness restart() {
        return new TestHarness(root, runs, repository, tools, today);
    }

    public HarnessEngine.RunSummary analyzeComposite(boolean withResearch) {
        Path inputs = fixture("composite/inputs");
        return engine.analyze(new HarnessEngine.AnalyzeRequest(repository,
                List.of(inputs.resolve("issue-register.xlsx"), inputs.resolve("inventory.advisories.json")),
                withResearch ? Map.of("INV-103", inputs.resolve("INV-103.analysis.json")) : Map.of(),
                repository.resolve("probes.json"), false));
    }

    public HarnessEngine.RunSummary analyzeEmployee() {
        return engine.analyze(new HarnessEngine.AnalyzeRequest(repository, List.of(), Map.of(),
                repository.resolve("probes.json"), false));
    }

    /** The two environment-dependent failures the recorded reference run's round 0 observed. */
    public void employeeEnvironment() {
        tools.preExistingFailures.add("ActuatorEndpointsTest.testActuatorHealth_Public");
        tools.preExistingFailures.add("ActuatorEndpointsTest.testActuatorInfo_Public");
    }

    public RunSession session(String runId) {
        return engine.load(runId);
    }

    public Finding finding(String runId, String sourceId) {
        return session(runId).findings.stream().filter(f -> sourceId.equals(f.sourceFindingId())).findFirst()
                .orElseThrow(() -> new AssertionError("no finding " + sourceId));
    }

    public List<ChangeProposal> proposalsFor(String runId, String findingId) {
        return session(runId).proposals.all().stream().filter(p -> p.findingRefs().contains(findingId)).toList();
    }

    public ChangeProposal proposalFor(String runId, String sourceId) {
        String findingId = finding(runId, sourceId).findingId();
        List<ChangeProposal> proposals = proposalsFor(runId, findingId);
        if (proposals.isEmpty()) {
            throw new AssertionError("no proposal for " + sourceId);
        }
        return proposals.get(0);
    }

    public void approve(String runId, ChangeProposal proposal) {
        engine.decideProposal(runId, proposal.proposalId(), "APPROVED", "dev.lead", "owner", "Reviewed the plan and the diff");
    }

    public Path runDir(String runId) {
        return runs.resolve(runId);
    }

    public com.fasterxml.jackson.databind.JsonNode json(String runId, String relative) {
        return KernelJson.read(runDir(runId).resolve(relative));
    }

    /** Content hash of every file under {@code dir}: the "was this directory touched" witness. */
    public static String treeHash(Path dir) {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile).sorted().forEach(p -> {
                try {
                    entries.add(dir.relativize(p).toString().replace('\\', '/') + ":" + Hashing.sha256File(p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Hashing.manifestHash(entries);
    }

    public static void copyTree(Path from, Path to) {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : files.sorted().toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
