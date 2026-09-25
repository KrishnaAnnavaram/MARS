package com.bootshift.tests.security;

import com.bootshift.core.security.SensitiveValues;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scans the published artifact tree for the corpus's own secrets.
 *
 * <p>Unit-testing the redaction function proves the function works. It does not prove that every
 * code path which writes an artifact goes through it, and that is the property that actually
 * matters. This test harvests the real credential components out of {@code ./src} and asserts that
 * none of them appears anywhere under {@code output/}.
 *
 * <p>It found a live leak. Redaction preserved the username so the URI stayed "inspectable", and the
 * corpus username was published in {@code inventory-issues.json} while the policy said no sensitive
 * value is ever stored. The password was correctly redacted throughout; the username was not
 * considered one. It is half of a credential pair, and it now goes too.
 *
 * <p>The test skips when no run has been published, rather than passing vacuously on an empty tree.
 */
class PublishedArtifactSecretScanTest {

    private static final Pattern CREDENTIALED_URI =
            Pattern.compile("([a-z+]+)://([^:/@\\s]+):([^@\\s]+)@");

    private static Path repositoryRoot() {
        return Path.of("").toAbsolutePath().getParent();
    }

    /** The real credential components in the corpus, both halves of every embedded pair. */
    private Set<String> harvestSecretsFromCorpus(Path source) throws IOException {
        Set<String> secrets = new LinkedHashSet<>();
        if (!Files.isDirectory(source)) {
            return secrets;
        }
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".properties") && !name.endsWith(".yml")
                        && !name.endsWith(".yaml") && !name.endsWith(".xml")
                        && !name.endsWith(".java")) {
                    continue;
                }
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                Matcher m = CREDENTIALED_URI.matcher(text);
                while (m.find()) {
                    for (String component : List.of(m.group(2), m.group(3))) {
                        if (component != null && component.length() >= 4
                                && !"REDACTED".equalsIgnoreCase(component)) {
                            secrets.add(component);
                        }
                    }
                }
            }
        }
        return secrets;
    }

    @Test
    @DisplayName("no corpus credential component appears in any published artifact")
    void publishedArtifactsCarryNoSecret() throws IOException {
        Path root = repositoryRoot();
        Path output = root.resolve("output");
        Assumptions.assumeTrue(Files.isDirectory(output),
                "no run has been published; nothing to scan");

        Set<String> secrets = harvestSecretsFromCorpus(root.resolve("src"));
        Assumptions.assumeFalse(secrets.isEmpty(),
                "the corpus contains no embedded credential to scan for");

        List<String> leaks = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(output)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                for (String secret : secrets) {
                    if (text.contains(secret)) {
                        leaks.add(root.relativize(file) + " contains a credential component");
                    }
                }
            }
        }

        assertThat(leaks).as("published artifacts must carry metadata about secrets, never a secret")
                .isEmpty();
    }

    @Test
    @DisplayName("redaction removes both halves of an embedded credential pair")
    void bothHalvesAreRedacted() {
        String uri = "mongodb+srv://appuser:s3cr3tP4ss@cluster0.example.mongodb.net/db";
        String redacted = SensitiveValues.redactUri(uri);

        assertThat(redacted).doesNotContain("appuser").doesNotContain("s3cr3tP4ss");
        // The shape a reviewer needs survives: what kind of thing it is, and where it points.
        assertThat(redacted).startsWith("mongodb+srv://");
        assertThat(redacted).contains("cluster0.example.mongodb.net");
    }
}
