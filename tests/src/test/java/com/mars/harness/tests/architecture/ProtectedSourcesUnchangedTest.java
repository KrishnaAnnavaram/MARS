package com.mars.harness.tests.architecture;

import com.bootshift.core.util.Hashing;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The protected legacy sources stay byte-for-byte what was exported from the three repositories
 * ({@code legacy-sources/SOURCES.json}). The manifest {@code protection/legacy-sources.sha256} was
 * taken from the export and cross-checked against the pristine clones; any edit, addition or
 * deletion under {@code legacy-sources/} fails this test.
 */
class ProtectedSourcesUnchangedTest {

    /**
     * Pre-existing side effect, not a harness change: Bootshift's own test suite regenerates this
     * report inside its source tree on every run (recorded in docs/current-system-analysis.md).
     */
    private static final Set<String> REGENERATED_BY_LEGACY_TESTS = Set.of("bootshift/reports/impact-accuracy.json");

    @Test
    void legacySourcesMatchTheExportManifest() throws IOException {
        Map<String, String> manifest = new LinkedHashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/protection/legacy-sources.sha256")) {
            assertThat(in).as("manifest resource").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    String hash = line.substring(0, 64);
                    String path = line.substring(64).trim().replaceFirst("^\\*", "");
                    manifest.put(path, hash);
                }
            }
        }
        assertThat(manifest).hasSize(1076);
        Path root = TestHarness.harnessRoot().resolve("legacy-sources");
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            Path file = root.resolve(e.getKey());
            if (!Files.isRegularFile(file)) {
                changed.add("MISSING " + e.getKey());
            } else if (!REGENERATED_BY_LEGACY_TESTS.contains(e.getKey()) && !Hashing.sha256File(file).equals(e.getValue())) {
                changed.add("MODIFIED " + e.getKey());
            }
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.equals("SOURCES.json") && !p.contains("/target/") && !p.contains("/node_modules/")
                            // gitignored Bootshift run planes, created only when Bootshift is driven directly
                            && !p.startsWith("bootshift/output/") && !p.startsWith("bootshift/bootshift-workspaces/"))
                    .filter(p -> !manifest.containsKey(p)).forEach(p -> changed.add("ADDED " + p));
        }
        assertThat(changed).as("protected legacy sources must stay unchanged").isEmpty();
    }
}
