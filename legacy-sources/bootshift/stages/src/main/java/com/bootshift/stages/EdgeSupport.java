package com.bootshift.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.adapters.mutation.FileMutationGateway;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.core.util.Json;
import com.bootshift.ports.scm.ScmPort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared state for the per-edge loop (Agents 12 to 17).
 *
 * <p>The registry and the ledger are run-scoped rather than stage-scoped: every edge appends to the
 * same hash chain and mutates the same identity registry, so they live in the run workspace and are
 * reopened by each stage rather than rebuilt.
 */
public final class EdgeSupport {

    public static final String LEDGER_FILE = "change-ledger.jsonl";
    public static final String LEDGER_HEAD_FILE = "change-ledger-head.json";
    public static final String LIVE_REGISTRY_FILE = "file-registry-live.json";

    private EdgeSupport() {
    }

    /** The mutable, run-scoped file registry. Seeded from the graph stage on first use. */
    public static FileRegistry loadRegistry(StageContext context) {
        Path live = context.run().runWorkspace().resolve(LIVE_REGISTRY_FILE);
        if (Files.isRegularFile(live)) {
            FileRegistry registry = FileRegistry.load(live);
            primeContent(context, registry);
            return registry;
        }
        JsonNode node = context.run().output().readLatest("03-graph", "file-registry.json");
        if (node == null) {
            node = context.run().output().readLatest("01-inventory", "file-registry.json");
        }
        if (node == null) {
            throw HarnessException.refusal(
                    "No file registry exists. Run: bootshift inventory --repo <path>");
        }
        FileRegistry registry = FileRegistry.fromNode(node);
        persistRegistry(context, registry);
        primeContent(context, registry);
        return registry;
    }

    public static void persistRegistry(StageContext context, FileRegistry registry) {
        Json.write(context.run().runWorkspace().resolve(LIVE_REGISTRY_FILE), registry.toNode());
    }

    /**
     * Rehydrates the similarity cache from the migration workspace so identity reattachment still
     * works on a resumed run.
     */
    private static void primeContent(StageContext context, FileRegistry registry) {
        Path workspace = context.run().migrationWorkspace();
        registry.active().forEach(record -> {
            Path file = workspace.resolve(record.getCurrentPath());
            if (Files.isRegularFile(file)) {
                try {
                    registry.primeContent(record.getFileId(),
                            Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    // A file that cannot be read simply loses similarity matching, which degrades
                    // rename detection rather than breaking identity.
                }
            }
        });
    }

    /** The run-scoped tamper-evident ledger. Reopened so the chain continues across stages. */
    public static ChangeLedger openLedger(StageContext context) {
        return ChangeLedger.reopen(context.run().runWorkspace().resolve(LEDGER_FILE),
                context.run().runWorkspace().resolve(LEDGER_HEAD_FILE));
    }

    public static ChangeLedger.Verification verifyLedger(StageContext context) {
        return ChangeLedger.verify(context.run().runWorkspace().resolve(LEDGER_FILE),
                context.run().runWorkspace().resolve(LEDGER_HEAD_FILE));
    }

    /** Builds the mutation gateway bound to this run's registry, ledger and baseline seal. */
    public static FileMutationGateway gateway(StageContext context, FileRegistry registry,
                                              ChangeLedger ledger) {
        return new FileMutationGateway(context.run().runId(), context.run().migrationWorkspace(),
                context.run().checkpointGit(), context.run().runWorkspace().resolve("patches"),
                registry, ledger, context.scm(), new FileMutationGateway.BaselineSealVerifier() {
            @Override
            public boolean sealed() {
                return context.stateMachine().baselineSealed();
            }

            @Override
            public String sealHash() {
                return context.stateMachine().baselineSealHash();
            }
        });
    }

    /** Creates a named checkpoint in the internal migration history. */
    public static ScmPort.Checkpoint checkpoint(StageContext context, String edgeId, String phase,
                                                String message) {
        String name = "mig/" + context.run().runId() + "/" + safe(edgeId) + "/" + phase;
        return context.scm().checkpoint(context.run().migrationWorkspace(),
                context.run().checkpointGit(), name, message);
    }

    public static String checkpointName(StageContext context, String edgeId, String phase) {
        return "mig/" + context.run().runId() + "/" + safe(edgeId) + "/" + phase;
    }

    /** Locates one edge in the frozen plan, refusing when the edge is unknown. */
    public static JsonNode findEdge(JsonNode edgePlanArtifact, String edgeId) {
        List<String> known = new ArrayList<>();
        for (JsonNode edge : edgePlanArtifact.path("edges")) {
            known.add(edge.path("edge_id").asText());
            if (edge.path("edge_id").asText().equals(edgeId)) {
                return edge;
            }
        }
        throw HarnessException.refusal("Edge " + edgeId + " is not in the frozen plan. Known edges: "
                + known);
    }

    /** All edge ids in plan order. */
    public static List<String> edgeIds(JsonNode edgePlanArtifact) {
        List<String> ids = new ArrayList<>();
        edgePlanArtifact.path("edges").forEach(e -> ids.add(e.path("edge_id").asText()));
        return ids;
    }

    /** The last known-good graph, used as the left side of every Graph Diff. */
    public static ApplicationGraph lastGoodGraph(StageContext context) {
        JsonNode node = context.run().output().readLatest("14-graph-diff", "last-good-graph.json");
        if (node == null) {
            node = context.run().output().readLatest("03-graph", "application-graph.json");
        }
        if (node == null) {
            throw HarnessException.refusal("No baseline graph exists. Run: bootshift graph");
        }
        return ApplicationGraph.fromNode(node);
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
