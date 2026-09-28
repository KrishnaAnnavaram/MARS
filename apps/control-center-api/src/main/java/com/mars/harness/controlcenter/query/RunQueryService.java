package com.mars.harness.controlcenter.query;

import com.mars.harness.controlcenter.api.ApiException;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.controlcenter.events.EventIndex;
import com.mars.harness.controlcenter.run.RunCoordinator;
import com.mars.harness.kernel.engine.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Entry point of the read side: locates runs and builds read models from their artifacts. */
@Service
public class RunQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(RunQueryService.class);

    private final EngineConfig config;
    private final ControlCenterPaths paths;
    private final RunCoordinator coordinator;
    private final EventIndex events;

    public RunQueryService(EngineConfig config, ControlCenterPaths paths, RunCoordinator coordinator, EventIndex events) {
        this.config = config;
        this.paths = paths;
        this.coordinator = coordinator;
        this.events = events;
    }

    /** A reader for an existing run; RUN_NOT_FOUND otherwise (also for malformed IDs, which never touch the disk). */
    public RunReader reader(String runId) {
        if (!RunReader.exists(paths.runsRoot(), runId)) {
            throw ApiException.runNotFound(runId);
        }
        return new RunReader(paths.runsRoot(), runId, config.policy());
    }

    public boolean exists(String runId) {
        return RunReader.exists(paths.runsRoot(), runId);
    }

    public RunDtos.RunPage list(String status, String capability, String search, int offset, int limit) {
        List<RunDtos.RunSummary> all = new ArrayList<>();
        for (String runId : runIds()) {
            try {
                RunReader run = new RunReader(paths.runsRoot(), runId, config.policy());
                all.add(RunProjector.summary(run, coordinator, paths, events.all(runId)));
            } catch (UncheckedIOException | IllegalArgumentException e) {
                // a run directory the engine could not have written consistently: listed as unreadable, not hidden
                LOG.warn("Run {} could not be read: {}", runId, e.getMessage());
                all.add(new RunDtos.RunSummary(runId, null, null, null, null, "UNREADABLE", "Unreadable run state", false,
                        false, null, null, null, null, 0, 0, null, new RunDtos.Liveness("UNREADABLE",
                        paths.redact(e.getMessage()), null, null), null));
            }
        }
        List<RunDtos.RunSummary> filtered = all.stream().filter(r -> matches(r, status, capability, search))
                .sorted(Comparator.comparing((RunDtos.RunSummary r) -> r.createdAt() == null ? "" : r.createdAt()).reversed())
                .toList();
        int from = Math.min(Math.max(0, offset), filtered.size());
        int to = Math.min(filtered.size(), from + Math.max(1, Math.min(limit, 200)));
        return new RunDtos.RunPage(filtered.subList(from, to), filtered.size());
    }

    private List<String> runIds() {
        if (!Files.isDirectory(paths.runsRoot())) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(paths.runsRoot())) {
            return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(id -> RunReader.exists(paths.runsRoot(), id)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean matches(RunDtos.RunSummary r, String status, String capability, String search) {
        if (status != null && !status.isBlank()) {
            boolean ok = switch (status.toLowerCase(Locale.ROOT)) {
                case "active" -> !r.terminal() && !"FAILED".equals(r.phase());
                case "waiting" -> r.waitingForHuman();
                case "complete" -> "COMPLETE".equals(r.phase());
                case "failed" -> "FAILED".equals(r.phase()) || "UNREADABLE".equals(r.phase());
                case "needs_human", "needs-human" -> "NEEDS_HUMAN".equals(r.phase()) || "NEEDS_HUMAN".equals(r.verdict());
                default -> true;
            };
            if (!ok) {
                return false;
            }
        }
        if (capability != null && !capability.isBlank() && r.strategy() != null) {
            boolean migration = r.strategy().contains("MIGRAT");
            boolean security = r.strategy().contains("SECURITY") || r.strategy().equals("MIGRATE_FIRST");
            if (capability.equalsIgnoreCase("migration") && !migration || capability.equalsIgnoreCase("security") && !security) {
                return false;
            }
        }
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase(Locale.ROOT);
            return (r.runId() + " " + r.application() + " " + r.source()).toLowerCase(Locale.ROOT).contains(q);
        }
        return true;
    }

    /** Repositories under the configured roots (directories with a build file), with candidate inputs. */
    public List<RunDtos.RepositoryOption> repositories() {
        List<RunDtos.RepositoryOption> options = new ArrayList<>();
        for (Path root : paths.repositoryRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 3)) {
                for (Path dir : walk.filter(Files::isDirectory).sorted().toList()) {
                    if (!Files.isRegularFile(dir.resolve("pom.xml")) || Files.isRegularFile(dir.getParent().resolve("pom.xml"))
                            && !dir.equals(root)) {
                        continue;
                    }
                    List<String> inputs = new ArrayList<>();
                    Path siblingInputs = dir.getParent().resolve("inputs");
                    if (Files.isDirectory(siblingInputs)) {
                        try (Stream<Path> files = Files.list(siblingInputs)) {
                            files.filter(Files::isRegularFile).map(Path::toString)
                                    .filter(p -> p.endsWith(".xlsx") || p.endsWith(".sarif") || p.endsWith(".sarif.json")
                                            || p.endsWith(".advisories.json"))
                                    .sorted().forEach(p -> inputs.add(root.relativize(Path.of(p)).toString().replace('\\', '/')));
                        }
                    }
                    Path probes = dir.resolve("probes.json");
                    options.add(new RunDtos.RepositoryOption(root.relativize(dir).toString().replace('\\', '/'),
                            dir.getFileName().toString(), root.getFileName() == null ? root.toString()
                            : root.getFileName().toString(), inputs,
                            Files.isRegularFile(probes) ? root.relativize(probes).toString().replace('\\', '/') : null));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return options;
    }
}
