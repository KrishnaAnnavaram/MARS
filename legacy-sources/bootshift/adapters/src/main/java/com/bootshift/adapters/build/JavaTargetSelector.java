package com.bootshift.adapters.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the Java toolchain an individual migration edge will actually build and run on.
 *
 * <p>The rule this replaces derived the application's Java level from {@code Runtime.version()} - the
 * JDK running Bootshift. That is a fact about the harness process, not about the application: it made
 * the target depend on how the operator happened to launch the tool, and on a machine with a newer
 * JDK it silently raised the application's compiler target on an edge that did not carry that change.
 *
 * <p>Selection here is: intersect the JDKs actually installed with the majors the edge's Spring Boot
 * line supports, discard anything below the project's current level, then apply the policy's
 * preference. The default preference is the highest <em>LTS</em> rather than the highest number,
 * because a non-LTS JDK is not a defensible production landing target and picking one silently is
 * how a migration acquires a second problem.
 */
public final class JavaTargetSelector {

    /** Long-term-support majors, newest last. */
    public static final List<Integer> LTS_MAJORS = List.of(8, 11, 17, 21, 25);

    /** How to choose among the admissible majors. */
    public enum Preference {
        /** Highest LTS that is admissible; a non-LTS is used only when no LTS is. */
        LTS_PREFERRED,
        /** Highest admissible major regardless of support model. */
        HIGHEST_SUPPORTED,
        /** Lowest admissible major - the smallest step that satisfies the edge. */
        LOWEST_SUPPORTED
    }

    /** The selected toolchain plus the reasoning and evidence behind it. */
    public record Selection(int major, String version, String vendor, String home,
                            String selectionReason, List<String> supportingEvidence,
                            boolean lts, boolean satisfiesEdge, List<Integer> consideredMajors) {

        public boolean resolved() {
            return home != null;
        }
    }

    private final ToolchainProbe probe;

    public JavaTargetSelector() {
        this(new ToolchainProbe());
    }

    public JavaTargetSelector(ToolchainProbe probe) {
        this.probe = probe;
    }

    public static boolean isLts(int major) {
        return LTS_MAJORS.contains(major);
    }

    /**
     * Selects a toolchain for one edge.
     *
     * @param supportedByTargetLine majors the edge's target Spring Boot line supports; empty means
     *                              the lifecycle registry had no evidence, which is not a licence to
     *                              pick anything - it is recorded in the reason
     * @param currentProjectJava    the level the project declares today; never move backwards
     * @param installed             discovered JDKs
     */
    public Selection select(List<Integer> supportedByTargetLine, int currentProjectJava,
                            List<ToolchainProbe.Jdk> installed, Preference preference) {
        List<String> evidence = new ArrayList<>();
        Set<Integer> installedMajors = new LinkedHashSet<>();
        installed.forEach(jdk -> installedMajors.add(jdk.major()));
        evidence.add("installed JDK majors: " + installedMajors);
        evidence.add("target line supports: "
                + (supportedByTargetLine == null || supportedByTargetLine.isEmpty()
                ? "UNKNOWN (no lifecycle evidence)" : supportedByTargetLine.toString()));
        evidence.add("project declares Java " + currentProjectJava);

        List<Integer> admissible = new ArrayList<>();
        for (Integer major : installedMajors) {
            boolean supported = supportedByTargetLine == null || supportedByTargetLine.isEmpty()
                    || supportedByTargetLine.contains(major);
            if (supported && major >= currentProjectJava) {
                admissible.add(major);
            }
        }
        admissible.sort(Integer::compareTo);

        if (admissible.isEmpty()) {
            // Nothing installed satisfies the edge. Say so; do not fall back to the harness JVM.
            String reason = "NO_COMPATIBLE_JDK_INSTALLED: no installed JDK is both supported by the "
                    + "target Spring Boot line and at or above the project level Java "
                    + currentProjectJava;
            return new Selection(currentProjectJava, null, null, null, reason, evidence, false,
                    false, admissible);
        }

        int chosen = switch (preference) {
            case HIGHEST_SUPPORTED -> admissible.get(admissible.size() - 1);
            case LOWEST_SUPPORTED -> admissible.get(0);
            case LTS_PREFERRED -> admissible.stream().filter(JavaTargetSelector::isLts)
                    .max(Integer::compareTo)
                    .orElse(admissible.get(admissible.size() - 1));
        };

        ToolchainProbe.Jdk jdk = installed.stream()
                .filter(j -> j.major() == chosen)
                .findFirst()
                .orElse(null);

        String reason;
        if (preference == Preference.LTS_PREFERRED && isLts(chosen)) {
            reason = "LTS_PREFERRED: Java " + chosen + " is the highest long-term-support release "
                    + "that the target Spring Boot line supports and that an installed JDK provides";
        } else if (preference == Preference.LTS_PREFERRED) {
            reason = "NO_ADMISSIBLE_LTS: no long-term-support JDK satisfied the edge, so Java "
                    + chosen + " was selected and is flagged as a non-LTS landing toolchain";
        } else {
            reason = preference.name() + ": Java " + chosen + " selected from " + admissible;
        }

        return new Selection(chosen,
                jdk == null ? null : jdk.version(),
                jdk == null ? null : jdk.vendor(),
                jdk == null ? null : normalize(jdk.home()),
                reason, evidence, isLts(chosen), true, admissible);
    }

    /** Convenience: discover, then select. */
    public Selection select(List<Integer> supportedByTargetLine, int currentProjectJava,
                            Preference preference) {
        return select(supportedByTargetLine, currentProjectJava, probe.discover(), preference);
    }

    /** Resolves the home of a specific major among installed JDKs, or empty. */
    public Optional<ToolchainProbe.Jdk> homeFor(int major, List<ToolchainProbe.Jdk> installed) {
        return installed.stream().filter(j -> j.major() == major).findFirst();
    }

    /**
     * The java executable for a selected home. Callers pass this explicitly rather than resolving
     * {@code java} from the harness process later on, which is how a run ends up executing on a JDK
     * nobody selected.
     */
    public static Optional<Path> javaExecutable(String home) {
        if (home == null || home.isBlank()) {
            return Optional.empty();
        }
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        Path candidate = Path.of(home).resolve("bin").resolve(windows ? "java.exe" : "java");
        return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    private static String normalize(Path path) {
        return path == null ? null : path.toString().replace((char) 92, '/');
    }
}
