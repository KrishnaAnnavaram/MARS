package com.bootshift.tests.security;

import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.adapters.http.HttpFetcher;
import com.bootshift.core.security.SensitiveValues;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary tests for process execution, network egress, keyed hashing and atomic artifact writes.
 *
 * <p>Each of these guards a control that was documented before it was true. The class exists because
 * "the fix is obvious" is not the same as "the fix is asserted", and a control with no test is one
 * refactor away from being a comment again.
 */
class ExecutionAndEgressBoundaryTest {

    // ---------------------------------------------------------------- command allowlist

    @Test
    @DisplayName("cmd.exe is not a launchable target")
    void shellIsNotOnTheAllowlist() {
        // shellWrap adds cmd.exe /c AFTER assertAllowed has checked the real target, so allowlisting
        // it bought nothing - and while it was listed, a caller could have passed
        // cmd.exe /c <anything> as its own command, which is the whole allowlist undone.
        assertThat(ProcessRunner.defaultAllowlist()).doesNotContain("cmd.exe");
        assertThatThrownBy(() -> new ProcessRunner().assertAllowed("cmd.exe"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not on the command allowlist");
    }

    @Test
    @DisplayName("an arbitrary executable is refused, and a build wrapper is permitted")
    void allowlistAdmitsOnlyBuildTooling() {
        ProcessRunner runner = new ProcessRunner();

        assertThatThrownBy(() -> runner.assertAllowed("/usr/bin/curl"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> runner.assertAllowed("powershell.exe"))
                .isInstanceOf(SecurityException.class);

        // A Windows wrapper is still allowed by name; the cmd.exe prefix is added afterwards.
        runner.assertAllowed("mvnw.cmd");
        runner.assertAllowed("git");
    }

    // ---------------------------------------------------------------- egress allowlist

    @Test
    @DisplayName("the egress allowlist matches whole hosts and their subdomains, not suffixes")
    void egressAllowlistIsNotSuffixMatching(@TempDir Path cache) {
        HttpFetcher fetcher = new HttpFetcher(cache, true);

        assertThat(fetcher.hostAllowed("https://repo1.maven.org/maven2/x.jar")).isTrue();
        assertThat(fetcher.hostAllowed("https://raw.githubusercontent.com/a/b")).isTrue();

        // "evil-github.com" ends with "github.com" as a string but is a different host.
        assertThat(fetcher.hostAllowed("https://evil-github.com/payload")).isFalse();
        assertThat(fetcher.hostAllowed("https://attacker.example/")).isFalse();
        assertThat(fetcher.hostAllowed("not a url")).isFalse();
    }

    // ---------------------------------------------------------------- keyed hashing

    @Test
    @DisplayName("the keyed hash is HMAC, not a prefixed digest")
    void keyedHashIsHmac() {
        String key = "deployment-local-key";
        String value = "hunter2";

        String hmac = Hashing.hmacSha256(key, value);

        // The old construction. Asserting they differ is what stops a well-meaning simplification
        // putting it back: sha256(key + value) is length-extension vulnerable, and these values are
        // often low entropy enough that the key is the only thing doing any work.
        assertThat(hmac).isNotEqualTo(Hashing.sha256(key + ":" + value));
        assertThat(hmac).hasSize(64);
        assertThat(hmac).isEqualTo(Hashing.hmacSha256(key, value));
        assertThat(hmac).isNotEqualTo(Hashing.hmacSha256("another-key", value));
    }

    @Test
    @DisplayName("a described secret carries metadata and its algorithm, never the value")
    void describedSecretNamesItsAlgorithm() {
        var node = SensitiveValues.describe("spring.data.mongodb.uri",
                "mongodb+srv://appuser:s3cr3tP4ss@cluster0.example.net/db", "PROPERTY_FILE",
                SensitiveValues.EvidencePolicy.KEYED_HASH_PERMITTED, "run-key");

        assertThat(node.toString()).doesNotContain("s3cr3tP4ss").doesNotContain("appuser");
        assertThat(node.get("value_hash_algorithm").asText()).isEqualTo("HmacSHA256");
        assertThat(node.get("evidence_policy").asText()).isEqualTo("KEYED_HASH_PERMITTED");
    }

    // ---------------------------------------------------------------- atomic artifact write

    @Test
    @DisplayName("an atomic write leaves no temporary file and no partial content")
    void atomicWriteLeavesNoDebris() throws IOException {
        Path dir = Files.createTempDirectory("bootshift-atomic");
        Path target = dir.resolve("latest.json");

        Json.writeAtomic(target, Map.of("directory", "20260910-071753-666"));
        assertThat(Json.read(target).path("directory").asText()).isEqualTo("20260910-071753-666");

        Json.writeAtomic(target, Map.of("directory", "20260910-073103-502"));
        assertThat(Json.read(target).path("directory").asText()).isEqualTo("20260910-073103-502");

        // latest.json is its own fallback: a reader that finds it truncated has nothing older to
        // fall back to, so a leftover .tmp or a half-written target is not an acceptable window.
        try (var stream = Files.list(dir)) {
            assertThat(stream.map(p -> p.getFileName().toString()).toList())
                    .containsExactly("latest.json");
        }
    }
}
