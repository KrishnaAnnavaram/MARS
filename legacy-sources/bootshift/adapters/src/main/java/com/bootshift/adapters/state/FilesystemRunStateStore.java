package com.bootshift.adapters.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.state.RunStateStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic filesystem run-state store.
 *
 * <p>Sufficient for local development. The port exists so a PostgreSQL-backed implementation can be
 * dropped in for distributed execution without touching the core domain.
 */
public final class FilesystemRunStateStore implements RunStateStore {

    private final Path root;

    public FilesystemRunStateStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create run state directory " + root, e);
        }
    }

    private Path fileFor(String runId) {
        return root.resolve(runId + ".json");
    }

    @Override
    public void createRun(String runId, Map<String, String> attributes) {
        ObjectNode node = Json.obj();
        node.put("run_id", runId);
        node.put("state", RunState.CREATED.name());
        node.put("started_at", Instant.now().toString());
        node.put("updated_at", Instant.now().toString());
        node.set("attributes", Json.toTree(attributes));
        node.set("transitions", Json.arr());
        Json.write(fileFor(runId), node);
    }

    @Override
    public void updateState(String runId, RunState state, String reason) {
        ObjectNode node = load(runId).map(r -> (ObjectNode) Json.read(fileFor(runId)))
                .orElseGet(() -> {
                    createRun(runId, Map.of());
                    return (ObjectNode) Json.read(fileFor(runId));
                });
        ObjectNode transition = Json.obj();
        transition.put("from", node.path("state").asText());
        transition.put("to", state.name());
        transition.put("reason", reason);
        transition.put("at", Instant.now().toString());
        ((com.fasterxml.jackson.databind.node.ArrayNode) node.withArray("transitions")).add(transition);
        node.put("state", state.name());
        node.put("updated_at", Instant.now().toString());
        Json.write(fileFor(runId), node);
    }

    @Override
    public Optional<RunRecord> load(String runId) {
        Path file = fileFor(runId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonNode node = Json.read(file);
        Map<String, String> attributes = new LinkedHashMap<>();
        node.path("attributes").fields()
                .forEachRemaining(e -> attributes.put(e.getKey(), e.getValue().asText()));
        return Optional.of(new RunRecord(node.path("run_id").asText(),
                RunState.valueOf(node.path("state").asText()),
                node.path("started_at").asText(), node.path("updated_at").asText(), attributes));
    }

    @Override
    public List<RunRecord> list() {
        List<RunRecord> records = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                String runId = p.getFileName().toString().replace(".json", "");
                load(runId).ifPresent(records::add);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list runs in " + root, e);
        }
        return records;
    }

    @Override
    public void putAttribute(String runId, String key, String value) {
        Path file = fileFor(runId);
        if (!Files.isRegularFile(file)) {
            createRun(runId, Map.of());
        }
        ObjectNode node = (ObjectNode) Json.read(file);
        ((ObjectNode) node.with("attributes")).put(key, value);
        node.put("updated_at", Instant.now().toString());
        Json.write(file, node);
    }
}
