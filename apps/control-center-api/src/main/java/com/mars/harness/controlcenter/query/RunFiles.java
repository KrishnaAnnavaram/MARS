package com.mars.harness.controlcenter.query;

import com.mars.harness.controlcenter.api.ApiErrorCode;
import com.mars.harness.controlcenter.api.ApiException;
import com.mars.harness.controlcenter.api.dto.EvidenceDtos;
import com.mars.harness.controlcenter.config.ControlCenterPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only access to a run's evidence artifacts and tool logs.
 *
 * <p>Only evidence areas are served. The areas that hold customer source ({@code original},
 * {@code migration}), the disposable build copies' contents and Bootshift's checkpoint repository
 * are never listed or served, and the decision integrity key is never served.
 *
 * <p>Served text is for display: credential-like literals are masked and absolute server paths
 * are replaced by placeholders. The files themselves are never changed.
 */
public final class RunFiles {

    static final Set<String> ARTIFACT_AREAS = Set.of("manifest", "inventory", "identity", "graph", "baseline", "discovery",
            "decisions", "plans", "proposals", "mutations", "checkpoints", "validation", "findings", "reports", "provenance",
            "ledger", "state", "events", "logs");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("json", "jsonl", "md", "patch", "txt", "log", "sha256", "diff");
    private static final long MAX_SERVED_BYTES = 8L * 1024 * 1024;
    private static final Pattern LEVEL = Pattern.compile("^\\s*\\[(INFO|WARNING|WARN|ERROR|DEBUG)]|\\b(ERROR|WARN|INFO)\\b");

    private RunFiles() {
    }

    public static List<EvidenceDtos.ArtifactEntry> artifacts(RunReader run) {
        Path root = run.layout().runDir();
        List<EvidenceDtos.ArtifactEntry> entries = new ArrayList<>();
        for (String area : ARTIFACT_AREAS.stream().sorted().toList()) {
            Path dir = root.resolve(area);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir, 4)) {
                files.filter(Files::isRegularFile).filter(p -> servable(root, p)).sorted().forEach(p -> {
                    try {
                        entries.add(new EvidenceDtos.ArtifactEntry(root.relativize(p).toString().replace('\\', '/'),
                                Files.size(p), Files.getLastModifiedTime(p).toInstant().toString(), extension(p)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return entries;
    }

    /** The text of one artifact, if it is in an evidence area and small enough to serve. */
    public static String artifact(RunReader run, ControlCenterPaths paths, String relative) {
        Path root = run.layout().runDir().toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || !servable(root, file)) {
            throw new ApiException(ApiErrorCode.ARTIFACT_NOT_FOUND, run.runId(), "No servable artifact " + relative);
        }
        try {
            if (Files.size(file) > MAX_SERVED_BYTES) {
                throw new ApiException(ApiErrorCode.ARTIFACT_NOT_FOUND, run.runId(), "Artifact " + relative
                        + " is larger than " + MAX_SERVED_BYTES + " bytes; read it from the run directory");
            }
            return paths.redact(SecretRedactor.redact(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<EvidenceDtos.LogFile> logs(RunReader run) {
        Path root = run.layout().runDir();
        List<EvidenceDtos.LogFile> logs = new ArrayList<>();
        for (String area : List.of("logs", "exec")) {
            Path dir = root.resolve(area);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            // exec/: only the round and verify logs next to the disposable copies, never the copies themselves
            try (Stream<Path> files = Files.walk(dir, area.equals("exec") ? 1 : 3)) {
                files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".log")).sorted()
                        .forEach(p -> {
                            try {
                                logs.add(new EvidenceDtos.LogFile(root.relativize(p).toString().replace('\\', '/'),
                                        Files.size(p), Files.getLastModifiedTime(p).toInstant().toString(), area));
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return logs;
    }

    public static EvidenceDtos.LogChunk log(RunReader run, ControlCenterPaths paths, String relative, int fromLine,
                                            int maxLines, String level, String search) {
        Path root = run.layout().runDir().toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        String rel = root.relativize(file).toString().replace('\\', '/');
        boolean allowed = file.startsWith(root) && Files.isRegularFile(file) && file.getFileName().toString().endsWith(".log")
                && (rel.startsWith("logs/") || rel.startsWith("exec/") && rel.indexOf('/', 5) < 0);
        if (!allowed) {
            throw new ApiException(ApiErrorCode.ARTIFACT_NOT_FOUND, run.runId(), "No log " + relative);
        }
        List<EvidenceDtos.LogLine> lines = new ArrayList<>();
        int total = 0;
        boolean truncated = false;
        String q = search == null || search.isBlank() ? null : search.toLowerCase(Locale.ROOT);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                total++;
                if (total < fromLine) {
                    continue;
                }
                String lvl = level(line);
                if (level != null && !level.isBlank() && !level.equalsIgnoreCase(lvl)) {
                    continue;
                }
                if (q != null && !line.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                if (lines.size() >= maxLines) {
                    truncated = true;
                    continue;
                }
                lines.add(new EvidenceDtos.LogLine(total, lvl, paths.redact(SecretRedactor.redact(line))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new EvidenceDtos.LogChunk(rel, fromLine, total, lines, truncated);
    }

    static String level(String line) {
        Matcher m = LEVEL.matcher(line);
        if (!m.find()) {
            return null;
        }
        String level = m.group(1) != null ? m.group(1) : m.group(2);
        return "WARNING".equals(level) ? "WARN" : level;
    }

    private static boolean servable(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        String area = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        String name = file.getFileName().toString();
        return ARTIFACT_AREAS.contains(area) && !name.startsWith(".") && !name.contains(".tmp")
                && TEXT_EXTENSIONS.contains(extension(file));
    }

    private static String extension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
