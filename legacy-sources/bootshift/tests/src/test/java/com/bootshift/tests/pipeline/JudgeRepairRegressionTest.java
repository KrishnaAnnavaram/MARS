package com.bootshift.tests.pipeline;

import com.bootshift.core.state.RunState;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.characterization.Scenario;
import com.bootshift.stages.StageExecutor;
import com.bootshift.stages.stage07.ComponentDocumentationCatalog;
import com.bootshift.stages.stage11.PlannerStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regressions for the defects the judge pass found in pipeline run 1.
 *
 * <p>Each of these was a silent failure rather than a crash, which is the kind this harness exists to
 * refuse: the run kept producing well-formed artifacts that said less than they appeared to. They are
 * pinned here because none of them would be noticed again by reading a green build.
 */
class JudgeRepairRegressionTest {

    @Nested
    @DisplayName("J1-001 preconditions are answered from the artifact plane")
    class Preconditions {

        private static final Predicate<String> EVERYTHING_PUBLISHED = artifact -> true;
        private static final Predicate<String> NOTHING_PUBLISHED = artifact -> false;

        @Test
        @DisplayName("a state proven by a published artifact stays proven once the edge loop begins")
        void planFrozenSurvivesTheEdgeLoop() {
            // The exact condition that refused every edge after the first in run 1: the cursor has
            // moved into the edge loop, and the plan that authorized that loop is on disk.
            assertThat(StageExecutor.analysisStateSatisfied(
                    RunState.PLAN_FROZEN, RunState.EDGE_COMPLETE, EVERYTHING_PUBLISHED)).isTrue();

            for (RunState cursor : List.of(RunState.EDGE_TRANSFORMED, RunState.EDGE_COMPILED,
                    RunState.EDGE_TESTED, RunState.EDGE_DIFFERENTIAL_VALIDATED,
                    RunState.EDGE_COMPLETE, RunState.FINAL_APPROVAL)) {
                assertThat(StageExecutor.analysisStateSatisfied(
                        RunState.PLAN_FROZEN, cursor, EVERYTHING_PUBLISHED))
                        .as("PLAN_FROZEN with the plan published, cursor at %s", cursor)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("every analysis-half state has an artifact that proves it")
        void everyAnalysisStateIsProvable() {
            for (RunState state : List.of(RunState.OSS_POLICY_VERIFIED, RunState.FILE_REGISTRY_SEALED,
                    RunState.BUILD_RESOLVED, RunState.GRAPH_VERIFIED, RunState.BASELINE_SEALED,
                    RunState.COMPATIBILITY_REGISTRY_READY, RunState.TARGET_FROZEN,
                    RunState.DOCUMENTATION_RETRIEVED, RunState.KNOWLEDGE_VERIFIED,
                    RunState.IMPACT_ANALYZED, RunState.CHARACTERIZATION_COMPLETE,
                    RunState.PLAN_FROZEN)) {
                assertThat(StageExecutor.proofArtifactFor(state))
                        .as("artifact proving %s", state)
                        .isNotNull()
                        .contains("/");
                assertThat(StageExecutor.analysisStateSatisfied(state, RunState.EDGE_COMPLETE,
                        EVERYTHING_PUBLISHED))
                        .as("%s once its artifact is published", state)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("an unpublished state is not satisfied by having moved past its ordinal")
        void anUnpublishedStateStillRefuses() {
            // The fallback must not become a way to skip a stage: with nothing on disk and the cursor
            // behind the requirement, the answer is still no.
            assertThat(StageExecutor.analysisStateSatisfied(
                    RunState.PLAN_FROZEN, RunState.BUILD_RESOLVED, NOTHING_PUBLISHED)).isFalse();
            assertThat(StageExecutor.analysisStateSatisfied(
                    RunState.BASELINE_SEALED, RunState.CREATED, NOTHING_PUBLISHED)).isFalse();
        }

        @Test
        @DisplayName("the cursor remains a fallback for a run whose artifacts are elsewhere")
        void cursorRemainsAFallback() {
            assertThat(StageExecutor.analysisStateSatisfied(
                    RunState.BUILD_RESOLVED, RunState.PLAN_FROZEN, NOTHING_PUBLISHED)).isTrue();
        }
    }

    @Nested
    @DisplayName("J1-002 facts are scoped to the edge they were attributed to")
    class FactScoping {

        private static PlannerStage.EdgeFact attributed(String... edges) {
            return new PlannerStage.EdgeFact("MK-00001", "API_REMOVED", "com.example.Removed",
                    "spring-boot", "2.7.12", "3.5.16", "ARTIFACT_VERSION_WINDOW", true,
                    List.of(edges));
        }

        @Test
        @DisplayName("declared attribution is evidence of absence as well as of presence")
        void attributionNarrows() {
            PlannerStage.EdgeFact fact = attributed("EDGE-3-MAJOR-3", "EDGE-4-MINOR");

            assertThat(fact.appliesTo("EDGE-3-MAJOR-3", "2.7.12", "3.0.13")).isTrue();
            assertThat(fact.appliesTo("EDGE-4-MINOR", "3.0.13", "3.1.12")).isTrue();

            // This is the assertion that failed in run 1: appliesTo returned true for everything,
            // so all eight edges reported an identical fact count and identical coverage.
            assertThat(fact.appliesTo("EDGE-1-PREP-TEST", "2.7.12", "2.7.12")).isFalse();
            assertThat(fact.appliesTo("EDGE-2-PATCH", "2.7.12", "2.7.18")).isFalse();
            assertThat(fact.appliesTo("EDGE-8-LANDING", "3.4.10", "3.5.16")).isFalse();
            assertThat(fact.appliesTo(null, "2.7.12", "3.5.16")).isFalse();

            assertThat(fact.scopingChannel()).isEqualTo("DECLARED_EDGE_ATTRIBUTION");
        }

        @Test
        @DisplayName("an unattributed fact falls back to intersecting its validity window")
        void intervalFallback() {
            PlannerStage.EdgeFact fact = new PlannerStage.EdgeFact("MK-00002", "PROPERTY_RENAMED",
                    "server.max-http-header-size", "spring-boot", "3.0.0", "3.5.16", "SPAN_ONLY",
                    true, List.of());

            assertThat(fact.scopingChannel()).isEqualTo("VALIDITY_INTERVAL_INTERSECTION");
            assertThat(fact.appliesTo("EDGE-3-MAJOR-3", "2.7.18", "3.0.13")).isTrue();
            // Entirely before the window opens.
            assertThat(fact.appliesTo("EDGE-2-PATCH", "2.7.12", "2.7.18")).isFalse();
        }

        @Test
        @DisplayName("a fact with no window and no attribution authorizes nothing")
        void unknownIsNotApplicable() {
            PlannerStage.EdgeFact fact = new PlannerStage.EdgeFact("MK-00003", "BEHAVIOUR_CHANGED",
                    "unknown", "spring-boot", null, null, "SPAN_ONLY", false, List.of());
            assertThat(fact.appliesTo("EDGE-3-MAJOR-3", "2.7.18", "3.0.13")).isFalse();
        }
    }

    @Nested
    @DisplayName("J1-004 components are classified by resolution evidence, not by group name")
    class ComponentClassification {

        private static BuildSystemPort.BuildModel modelWith(
                List<BuildSystemPort.ResolvedDependency> dependencies) {
            BuildSystemPort.ModuleModel module = new BuildSystemPort.ModuleModel(
                    "app", "/repo/app", "com.example", "app", "1.0", "jar", null, "17",
                    Map.of(), List.of(), List.of(), BuildSystemPort.Kind.MAVEN);
            return new BuildSystemPort.BuildModel(
                    BuildSystemPort.Kind.MAVEN, "MAVEN=3.9.9", "mvn -B", true, List.of(module),
                    dependencies, List.of(), List.of(),
                    List.of(new BuildSystemPort.RepositoryRef("central",
                            "https://repo1.maven.org/maven2", false, true)),
                    List.of(), Map.of("java.version", "21"), true, null);
        }

        private static BuildSystemPort.ResolvedDependency from(String group, String artifact,
                                                               String repository, String status) {
            return new BuildSystemPort.ResolvedDependency(group, artifact, "1.0", "jar", "compile",
                    "app", null, repository, true, false, null, status);
        }

        @Test
        @DisplayName("a public library is not an internal component because of its group name")
        void publicLibrariesAreNotInternal() {
            // Every one of these was reported as internal in run 1, purely because its group did not
            // begin with org., io., net., jakarta., javax., com.fasterxml or ch.qos.
            var detection = ComponentDocumentationCatalog.detect(modelWith(List.of(
                    from("com.google.guava", "guava", "central", "RESOLVED"),
                    from("com.google.code.gson", "gson", "central", "RESOLVED"),
                    from("commons-io", "commons-io", "central", "RESOLVED"),
                    from("joda-time", "joda-time", "central", "RESOLVED"),
                    from("antlr", "antlr", "central", "RESOLVED"))));

            assertThat(detection.possiblyInternal()).isEmpty();
            assertThat(detection.components()).noneMatch(c -> c.startsWith("internal:"));
            assertThat(detection.publicWithoutCatalogue())
                    .contains("com.google.guava:guava", "joda-time:joda-time");
        }

        @Test
        @DisplayName("a coordinate from a non-public repository is reported as possibly internal")
        void privateCoordinatesAreFlagged() {
            var detection = ComponentDocumentationCatalog.detect(modelWith(List.of(
                    from("com.acme.platform", "acme-core", "acme-nexus", "RESOLVED"),
                    from("com.google.guava", "guava", "central", "RESOLVED"))));

            assertThat(detection.possiblyInternal()).containsExactly("com.acme.platform:acme-core");
            assertThat(detection.components()).contains("internal:com.acme.platform");
            assertThat(detection.publicWithoutCatalogue()).containsExactly("com.google.guava:guava");
        }

        @Test
        @DisplayName("an unresolved coordinate is not assumed to be public")
        void unresolvedIsNotPublic() {
            var detection = ComponentDocumentationCatalog.detect(modelWith(List.of(
                    from("com.acme.platform", "acme-core", "central", "UNRESOLVED"))));

            assertThat(detection.possiblyInternal()).containsExactly("com.acme.platform:acme-core");
            assertThat(detection.publicWithoutCatalogue()).isEmpty();
        }

        @Test
        @DisplayName("spring-boot and jdk are always components; a catalogued library is recognised")
        void alwaysPresentComponents() {
            var detection = ComponentDocumentationCatalog.detect(modelWith(List.of(
                    from("org.springframework.security", "spring-security-core", "central",
                            "RESOLVED"))));
            Set<String> components = detection.components();
            assertThat(components).contains("spring-boot", "jdk", "maven");
            assertThat(detection.possiblyInternal()).isEmpty();
        }
    }

    @Nested
    @DisplayName("J1-010 an unobserved behaviour is a declared gap, not a pending one")
    class ScenarioAccounting {

        @Test
        @DisplayName("only an executed scenario is an oracle")
        void oracleRequiresExecution() {
            assertThat(Scenario.State.FROZEN.isOracle()).isTrue();
            assertThat(Scenario.State.MAPPED_TO_EXISTING_VERIFIED_TEST.isOracle()).isTrue();
            assertThat(Scenario.State.AWAITING_OLD_OBSERVATION.isOracle()).isFalse();
            assertThat(Scenario.State.UNOBSERVABLE_WITH_EXPLICIT_GAP.isOracle()).isFalse();
        }

        @Test
        @DisplayName("a declared gap is accounted for; a pending scenario is not")
        void gapsAreAccountedForAndPendingIsNot() {
            // This is why the repair matters. A scenario left AWAITING_OLD_OBSERVATION after its
            // execution failed protected nothing and was not counted as an admitted blind spot
            // either - the one combination the evidence rules do not allow.
            assertThat(Scenario.State.UNOBSERVABLE_WITH_EXPLICIT_GAP.accountedFor()).isTrue();
            assertThat(Scenario.State.AWAITING_OLD_OBSERVATION.accountedFor()).isFalse();
        }
    }
}
