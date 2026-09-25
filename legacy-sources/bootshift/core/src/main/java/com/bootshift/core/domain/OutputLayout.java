package com.bootshift.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pointer-after-write artifact layout (spec section 44).
 *
 * <pre>
 * output/&lt;stage&gt;/&lt;timestamp&gt;/    complete artifacts written here first
 * output/&lt;stage&gt;/latest.json      moved only after the stage succeeds
 * </pre>
 *
 * A failed stage leaves {@code latest.json} pointing at the previous good run. That property is
 * what makes the artifact plane, not the state machine, the source of truth (R23).
 */
public final class OutputLayout {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path outputRoot;

    public OutputLayout(Path outputRoot) {
        this.outputRoot = outputRoot;
    }

    public Path root() {
        return outputRoot;
    }

    public Path stageRoot(String stageDir) {
        return outputRoot.resolve(stageDir);
    }

    public Path latestPointer(String stageDir) {
        return stageRoot(stageDir).resolve("latest.json");
    }

    /**
     * Opens a new timestamped directory for a stage attempt.
     *
     * <p>The directory is guaranteed to be new. The timestamp has millisecond resolution, and two
     * attempts at the same stage can land in the same millisecond - a re-run, or a stage that fails
     * fast and is retried. When that happened, both attempts wrote into the same directory, so a
     * <em>failed</em> attempt overwrote the artifacts of an already-published one while the pointer
     * kept naming it. The published directory then held data that had never passed validation, which
     * defeats the point of publishing the pointer last.
     *
     * <p>A collision now takes the next free {@code -2}, {@code -3} suffix rather than reusing the
     * directory. Attempts remain immutable, and the pointer still names exactly one of them.
     */
    public StageWriter open(String stageDir) {
        String stamp = STAMP.format(Instant.now());
        Path dir = stageRoot(stageDir).resolve(stamp);
        int attempt = 1;
        while (Files.exists(dir)) {
            attempt++;
            dir = stageRoot(stageDir).resolve(stamp + "-" + attempt);
            if (attempt > 1000) {
                throw new IllegalStateException(
                        "Cannot allocate a fresh output directory for " + stageDir + " at " + stamp);
            }
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create stage output directory " + dir, e);
        }
        return new StageWriter(stageDir, dir);
    }

    /**
     * Resolves the directory that {@code latest.json} points at, or empty when the stage has never
     * successfully completed.
     */
    public Path resolveLatestDir(String stageDir) {
        Path pointer = latestPointer(stageDir);
        if (!Files.isRegularFile(pointer)) {
            return null;
        }
        JsonNode node = Json.read(pointer);
        String dir = node.path("directory").asText(null);
        if (dir == null) {
            return null;
        }
        Path resolved = stageRoot(stageDir).resolve(dir);
        return Files.isDirectory(resolved) ? resolved : null;
    }

    /** Reads a named artifact from the last successful run of a stage. */
    public JsonNode readLatest(String stageDir, String artifactName) {
        Path dir = resolveLatestDir(stageDir);
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve(artifactName);
        return Files.isRegularFile(file) ? Json.read(file) : null;
    }

    public Path latestArtifactPath(String stageDir, String artifactName) {
        Path dir = resolveLatestDir(stageDir);
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve(artifactName);
        return Files.isRegularFile(file) ? file : null;
    }

    /** Accumulates artifacts for one stage attempt and publishes the pointer only on success. */
    public final class StageWriter {

        private final String stageDir;
        private final Path dir;
        private final Map<String, String> written = new LinkedHashMap<>();
        private final List<String> validationErrors = new ArrayList<>();
        private boolean published;

        private StageWriter(String stageDir, Path dir) {
            this.stageDir = stageDir;
            this.dir = dir;
        }

        public Path dir() {
            return dir;
        }

        public Path write(String name, Object payload) {
            Path target = dir.resolve(name);
            Json.write(target, payload);
            try {
                written.put(name, Hashing.sha256File(target));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot hash artifact " + target, e);
            }
            return target;
        }

        public Path writeText(String name, String text) {
            Path target = dir.resolve(name);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, text);
                written.put(name, Hashing.sha256File(target));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write text artifact " + target, e);
            }
            return target;
        }

        public void validationError(String message) {
            validationErrors.add(message);
        }

        public List<String> validationErrors() {
            return validationErrors;
        }

        public Map<String, String> hashes() {
            return written;
        }

        public String hashOf(String name) {
            return written.get(name);
        }

        /**
         * Writes {@code manifest.json} and atomically advances {@code latest.json}. Refuses to
         * publish when artifact schema validation produced errors.
         */
        public Path publish(String runId) {
            if (!validationErrors.isEmpty()) {
                throw new IllegalStateException(
                        "Refusing to publish stage " + stageDir + " with schema validation errors: "
                                + validationErrors);
            }
            ObjectNode manifest = Json.obj();
            manifest.put("stage", stageDir);
            manifest.put("run_id", runId);
            manifest.put("generated_at", Instant.now().toString());
            manifest.set("artifacts", Json.toTree(written));
            manifest.put("artifact_set_hash", Hashing.manifestHash(
                    written.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).sorted().toList()));
            Path manifestPath = dir.resolve("manifest.json");
            Json.write(manifestPath, manifest);

            ObjectNode pointer = Json.obj();
            pointer.put("stage", stageDir);
            pointer.put("run_id", runId);
            pointer.put("directory", dir.getFileName().toString());
            pointer.put("published_at", Instant.now().toString());
            pointer.put("artifact_set_hash", manifest.path("artifact_set_hash").asText());
            // The pointer is the only thing that makes a stage visible, and it is its own
            // fallback: a reader that finds it truncated has nothing older to fall back to.
            Json.writeAtomic(latestPointer(stageDir), pointer);
            published = true;
            return manifestPath;
        }

        public boolean published() {
            return published;
        }
    }
}
