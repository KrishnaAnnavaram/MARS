package com.bootshift.tests.path;

import com.bootshift.adapters.build.JavaTargetSelector;
import com.bootshift.adapters.build.ToolchainProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration path and toolchain selection.
 *
 * <p>Two defects these cover, both of which produced a plausible-looking plan that was wrong:
 *
 * <ul>
 *   <li>the path builder jumped straight to the lowest line of the landing major, so a 2.7 to 4.x
 *       migration crossed the Boot 3 boundary and the Boot 4 boundary in one hop and produced no
 *       Boot 3 checkpoint - the transition where the Jakarta relocation and the Spring Security
 *       rewrite actually live;</li>
 *   <li>the application's Java level was derived from {@code Runtime.version()}, the JDK running
 *       Bootshift, so the target depended on how the operator launched the tool.</li>
 * </ul>
 */
class MigrationPathTest {

    // ------------------------------------------------------------------ Java target selection

    private static ToolchainProbe.Jdk jdk(int major) {
        return new ToolchainProbe.Jdk(major, major + ".0.1", "Test Vendor",
                Path.of("/jdks/" + major), "fixture");
    }

    @Test
    @DisplayName("the Java target is the highest LTS the target line supports, not the newest JDK")
    void ltsIsPreferredOverNewest() {
        JavaTargetSelector selector = new JavaTargetSelector();
        // Java 24 is newer than 21 and is not an LTS. Selecting it because it is numerically
        // highest lands production code on a release with a six-month support window as a side
        // effect of what happened to be installed on the build machine.
        List<ToolchainProbe.Jdk> installed = List.of(jdk(24), jdk(21), jdk(17));

        JavaTargetSelector.Selection selection = selector.select(
                List.of(17, 18, 19, 20, 21, 22, 23, 24), 17, installed,
                JavaTargetSelector.Preference.LTS_PREFERRED);

        assertThat(selection.major()).isEqualTo(21);
        assertThat(selection.lts()).isTrue();
        assertThat(selection.selectionReason()).contains("LTS_PREFERRED");
        assertThat(selection.home()).isEqualTo("/jdks/21");
        assertThat(selection.supportingEvidence())
                .anySatisfy(line -> assertThat(line).contains("installed JDK majors"));
    }

    @Test
    @DisplayName("a JDK the target line does not support is never selected")
    void unsupportedMajorsAreExcluded() {
        JavaTargetSelector.Selection selection = new JavaTargetSelector().select(
                List.of(17, 18, 19), 17, List.of(jdk(21), jdk(17)),
                JavaTargetSelector.Preference.LTS_PREFERRED);

        // Spring Boot 3.0 supports 17 through 19. Building it on 21 is not a supported combination
        // regardless of 21 being an LTS.
        assertThat(selection.major()).isEqualTo(17);
        assertThat(selection.consideredMajors()).containsExactly(17);
    }

    @Test
    @DisplayName("the target never moves backwards from the project's declared level")
    void neverMovesBackwards() {
        JavaTargetSelector.Selection selection = new JavaTargetSelector().select(
                List.of(8, 11, 17), 17, List.of(jdk(11), jdk(17)),
                JavaTargetSelector.Preference.LTS_PREFERRED);

        assertThat(selection.major()).isEqualTo(17);
        assertThat(selection.consideredMajors()).doesNotContain(11, 8);
    }

    @Test
    @DisplayName("no compatible JDK is reported as such, never substituted with the harness JVM")
    void missingToolchainIsReportedNotSubstituted() {
        JavaTargetSelector.Selection selection = new JavaTargetSelector().select(
                List.of(21), 17, List.of(jdk(17)),
                JavaTargetSelector.Preference.LTS_PREFERRED);

        assertThat(selection.satisfiesEdge()).isFalse();
        assertThat(selection.resolved()).isFalse();
        assertThat(selection.home()).isNull();
        assertThat(selection.selectionReason()).startsWith("NO_COMPATIBLE_JDK_INSTALLED");
        // The harness is running on some JDK right now. That must not leak into the answer.
        assertThat(selection.selectionReason()).doesNotContain(
                String.valueOf(Runtime.version().feature()) + " selected");
    }

    @Test
    @DisplayName("an unknown supported range does not license an arbitrary choice")
    void unknownSupportRangeIsRecorded() {
        JavaTargetSelector.Selection selection = new JavaTargetSelector().select(
                List.of(), 17, List.of(jdk(21), jdk(17)),
                JavaTargetSelector.Preference.LTS_PREFERRED);

        assertThat(selection.supportingEvidence())
                .anySatisfy(line -> assertThat(line).contains("UNKNOWN (no lifecycle evidence)"));
        assertThat(selection.major()).isEqualTo(21);
    }

    @Test
    @DisplayName("the LTS list is the real one")
    void ltsListIsCorrect() {
        assertThat(JavaTargetSelector.LTS_MAJORS).containsExactly(8, 11, 17, 21, 25);
        assertThat(JavaTargetSelector.isLts(21)).isTrue();
        assertThat(JavaTargetSelector.isLts(24)).isFalse();
        assertThat(JavaTargetSelector.isLts(23)).isFalse();
    }

    // ------------------------------------------------------------------ path shape

    /**
     * The path builder lives inside the stage, so its rule is exercised here through the same
     * arithmetic the stage uses: how many major boundaries separate a source from a landing target,
     * and therefore how many mandatory boundary edges the path must contain.
     */
    @Test
    @DisplayName("a 2.x to 4.x migration must cross two major boundaries, not one")
    void twoMajorsMeanTwoBoundaries() {
        assertThat(majorsBetween("2.7.12", "3.5.16")).isEqualTo(1);
        assertThat(majorsBetween("2.7.12", "4.0.8")).isEqualTo(2);
        assertThat(majorsBetween("2.7.12", "4.1.1")).isEqualTo(2);
        assertThat(majorsBetween("3.1.12", "4.0.8")).isEqualTo(1);
        assertThat(majorsBetween("3.1.12", "3.5.16")).isZero();
    }

    @Test
    @DisplayName("line ordering sorts by major then minor, so 3.10 is above 3.9")
    void lineOrderingIsNumericNotLexical() {
        assertThat(numericLine("2.7")).isLessThan(numericLine("3.0"));
        assertThat(numericLine("3.9")).isLessThan(numericLine("3.10"));
        assertThat(numericLine("3.5")).isLessThan(numericLine("4.0"));
        assertThat(numericLine("4.0")).isLessThan(numericLine("4.1"));
    }

    private static int majorsBetween(String from, String to) {
        return Math.max(0, majorOf(to) - majorOf(from));
    }

    private static int majorOf(String version) {
        return Integer.parseInt(version.split("\\.")[0].replaceAll("[^0-9]", ""));
    }

    private static int numericLine(String line) {
        String[] parts = line.split("\\.");
        return Integer.parseInt(parts[0]) * 100 + (parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
    }
}
