package com.mars.harness.tests.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.tests.support.TestHarness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs a parity driver ({@code tests/src/test/resources/parity/*.js}) with Node. Each driver
 * {@code require()}s the unchanged legacy module from {@code legacy-sources/} and prints one JSON
 * document on stdout. When Node is not runnable the calling test is skipped, never passed.
 */
final class LegacyNode {

    private static Boolean available;

    private LegacyNode() {
    }

    static Path root() {
        return TestHarness.harnessRoot();
    }

    static Path legacy(String relative) {
        return root().resolve("legacy-sources").resolve(relative);
    }

    /** Skips the calling test when {@code node} cannot be run. */
    static void assumeNode() {
        if (available == null) {
            try {
                Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                available = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (IOException e) {
                available = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                available = false;
            }
        }
        assumeTrue(available, "node is not runnable; legacy parity cannot be checked");
    }

    static JsonNode run(String driver, String... args) {
        return run(driver, Map.of(), args);
    }

    static JsonNode run(String driver, Map<String, String> env, String... args) {
        assumeNode();
        Path script = root().resolve("tests/src/test/resources/parity").resolve(driver);
        List<String> command = new ArrayList<>(List.of("node", script.toString()));
        command.addAll(List.of(args));
        try {
            Path err = Files.createTempFile("parity-node", ".err");
            ProcessBuilder pb = new ProcessBuilder(command).directory(root().toFile()).redirectError(err.toFile());
            pb.environment().put("HARNESS_ROOT", root().toString());
            pb.environment().putAll(env);
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new AssertionError("legacy driver " + driver + " failed: " + Files.readString(err));
            }
            process.getInputStream().close();
            process.getErrorStream().close();
            deleteQuietly(err);
            return KernelJson.parse(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Windows may hold the redirect file briefly after the child exits; a leftover temp file is harmless. */
    private static void deleteQuietly(Path file) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        file.toFile().deleteOnExit();
    }

    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    /** A JSON string or null, as Java sees it. */
    static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }
}
