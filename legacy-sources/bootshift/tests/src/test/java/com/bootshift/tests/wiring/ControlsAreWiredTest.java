package com.bootshift.tests.wiring;

import com.fasterxml.jackson.databind.node.MissingNode;
import com.bootshift.adapters.transform.JakartaNamespaceTransformer;
import com.bootshift.adapters.transform.MavenPomTransformer;
import com.bootshift.adapters.transform.OpenRewriteCoreProvider;
import com.bootshift.adapters.transform.RemovedAnnotationTransformer;
import com.bootshift.adapters.transform.TestFrameworkTransformer;
import com.bootshift.ports.transformation.TransformationPort;
import com.bootshift.stages.stage11.PlannerStage;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the harness's controls are actually invoked by the pipeline.
 *
 * <p>Two defects of exactly this shape were found in review, and neither was catchable by any test
 * that existed at the time:
 *
 * <ul>
 *   <li>{@code JavapApiDiffAdapter} was written to spec, unit-testable, and called by nothing. The
 *       artifact channel diffed BOMs and read configuration metadata but never looked inside a jar,
 *       which is the only place a removed type is visible. An edge reached the compiler with 33
 *       errors against a knowledge base that reported 99.83% deterministic coverage.</li>
 *   <li>{@code detectBypass()} had three passing unit tests and no caller. The single-writer rule
 *       was enforced statically by ArchUnit and verified at runtime by nothing, while the README
 *       described a runtime manifest re-hash and a counter that "must be zero".</li>
 * </ul>
 *
 * <p>A control with no caller is documentation. These tests assert the call sites exist.
 */
class ControlsAreWiredTest {

    private static final Path STAGES = Path.of("").toAbsolutePath().getParent()
            .resolve("stages/src/main/java/com/bootshift/stages");

    private static String sourcesUnder(Path directory) {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + directory, e);
        }
        return all.toString();
    }

    @Test
    @DisplayName("the mutation stage calls detectBypass, so the single-writer rule is checked at runtime")
    void bypassDetectionIsCalled() {
        assertThat(sourcesUnder(STAGES.resolve("stage12"))).contains("detectBypass()");
    }

    @Test
    @DisplayName("the knowledge stage runs the published-bytecode diff")
    void apiDiffIsCalled() {
        String source = sourcesUnder(STAGES.resolve("stage08"));
        assertThat(source).contains("JavapApiDiffAdapter");
        assertThat(source).contains(".compare(");
    }

    @Test
    @DisplayName("the accuracy harness is invoked by the impact stage")
    void accuracyHarnessIsCalled() {
        assertThat(sourcesUnder(STAGES.resolve("stage09"))).contains("new AccuracyHarness().evaluate(");
    }

    @Test
    @DisplayName("every stage package the orchestrator lists has a stage class")
    void everyStagePackageIsPopulated() throws IOException {
        for (int i = 1; i <= 20; i++) {
            Path pkg = STAGES.resolve(String.format("stage%02d", i));
            assertThat(Files.isDirectory(pkg)).as("package stage%02d exists", i).isTrue();
            try (Stream<Path> stream = Files.list(pkg)) {
                List<Path> stages = stream.filter(p -> p.getFileName().toString().endsWith("Stage.java"))
                        .toList();
                assertThat(stages).as("stage%02d declares a Stage class", i).isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("every recipe the planner can schedule has a transformer that handles it")
    void everyScheduledRecipeHasAProvider() {
        // The planner decides the recipe order; the transformation stage maps recipes to providers.
        // When those two lists were maintained separately, a recipe could be planned, implemented and
        // constructed and still never run: the stage recorded NO_PROVIDER, reported SUCCESS, and the
        // edge failed to compile on the exact construct the transformer was written to handle.
        List<TransformationPort> candidates = List.of(
                new MavenPomTransformer(),
                new JakartaNamespaceTransformer(),
                new TestFrameworkTransformer(),
                new RemovedAnnotationTransformer(),
                new OpenRewriteCoreProvider());

        Set<String> planned = new LinkedHashSet<>();
        for (String edgeClass : List.of("PREPARATORY", "MAJOR_BOUNDARY", "PATCH", "MINOR", "LANDING")) {
            planned.addAll(PlannerStage.recipesFor(edgeClass, MissingNode.getInstance()));
            // Both schedules: with OpenRewrite available and without. A recipe that only appears on
            // one of those paths still has to have a provider, or the path that schedules it records
            // NO_PROVIDER and reports success while changing nothing.
            for (boolean openRewrite : List.of(true, false)) {
                PlannerStage.scheduleFor(edgeClass, MissingNode.getInstance(), openRewrite)
                        .forEach(scheduled -> planned.add(scheduled.recipeId()));
            }
        }
        assertThat(planned).contains("java.remove-annotation", "jakarta.namespace");

        List<String> unhandled = new ArrayList<>();
        for (String recipeId : planned) {
            boolean handled = candidates.stream().anyMatch(c -> c.handles(recipeId))
                    // ConfigurationPropertyTransformer needs a rule file to construct, so it is
                    // matched by its recipe id rather than instantiated here.
                    || "config.property-migration".equals(recipeId);
            if (!handled) {
                unhandled.add(recipeId);
            }
        }

        assertThat(unhandled)
                .as("a planned recipe with no transformer is silently never applied")
                .isEmpty();
    }
}
