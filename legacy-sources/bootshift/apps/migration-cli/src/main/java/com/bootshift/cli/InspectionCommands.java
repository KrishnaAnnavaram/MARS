package com.bootshift.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.util.Json;
import com.bootshift.stages.EdgeSupport;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.stage20.ProvenanceStage;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Inspection commands (spec section 54).
 *
 * <p>Human-facing output stays concise while the artifacts behind it remain rich JSON. Every answer
 * here is read from a published artifact rather than recomputed, so the CLI and the report can never
 * disagree.
 */
final class InspectionCommands {

    private InspectionCommands() {
    }
}

@CommandLine.Command(name = "lineage", description =
        "Show the complete history of one FILE_ID from baseline to final")
final class LineageCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Parameters(index = "0", description = "FILE_ID or path")
    String fileRef;

    @Override
    public Integer call() {
        StageContext context = options.context();
        FileRegistry registry = EdgeSupport.loadRegistry(context);
        String fileId = GraphCommand.resolveFileId(registry, fileRef);
        FileRecord record = registry.byId(fileId).orElseThrow();

        System.out.println();
        System.out.println("  " + record.getFileId());
        System.out.println("  baseline path  " + record.getBaselinePath());
        System.out.println("  baseline sha   " + record.getBaselineSha256());
        System.out.println("  current path   " + record.getCurrentPath());
        System.out.println("  current sha    " + record.getCurrentSha256());
        System.out.println("  status         " + record.getStatus());
        System.out.println("  role           " + record.getRole());
        System.out.println("  module         " + record.getModule());
        if (record.getRenameSource() != com.bootshift.core.identity.RenameSource.NONE) {
            System.out.println("  rename source  " + record.getRenameSource()
                    + " (confidence " + record.getRenameConfidence() + ")");
            System.out.println("  previous path  " + record.getPreviousPath());
        }
        if (record.getSplitFrom() != null) {
            System.out.println("  split from     " + record.getSplitFrom());
        }
        if (record.getMergedInto() != null) {
            System.out.println("  merged into    " + record.getMergedInto());
        }

        System.out.println();
        System.out.println("  version history:");
        for (FileRecord.Version version : record.getVersions()) {
            System.out.println("    " + (version.changeId() == null ? "BASELINE  " : version.changeId())
                    + "  " + version.sha256().substring(0, 16) + "  " + version.path());
            if (version.reason() != null) {
                System.out.println("               " + version.reason());
            }
        }

        System.out.println();
        System.out.println("  symbols: " + record.getSymbolIds().size());
        System.out.println("  changes: " + record.getChangeIds().size());

        JsonNode differential = StageSupport.optionalUpstream(context, "17-differential",
                "differential-report.json");
        if (differential != null) {
            System.out.println("  differential classifications: "
                    + differential.path("classification_counts"));
        }
        System.out.println();
        return 0;
    }
}

@CommandLine.Command(name = "explain", description = "Explain an impact finding or a change event",
        subcommands = {ExplainCommand.Impact.class, ExplainCommand.Change.class})
final class ExplainCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("Use: bootshift explain impact <IMPACT_ID> | explain change <CHANGE_ID>");
        return 2;
    }

    @CommandLine.Command(name = "impact", description = "Explain why an impact finding exists")
    static final class Impact implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @CommandLine.Parameters(index = "0", description = "IMPACT_ID")
        String impactId;

        @Override
        public Integer call() {
            StageContext context = options.context();
            JsonNode report = StageSupport.requireUpstream(context, "09-impact", "impact-report.json",
                    "Run: bootshift impact");
            for (JsonNode finding : report.path("findings")) {
                if (!impactId.equals(finding.path("impact_id").asText())) {
                    continue;
                }
                System.out.println();
                System.out.println("  " + impactId + "  [" + finding.path("classification").asText() + "]");
                System.out.println("  subject        " + finding.path("subject").asText());
                System.out.println("  fact           " + finding.path("knowledge_id").asText()
                        + " (" + finding.path("fact_type").asText() + ")");
                System.out.println("  path           " + finding.path("path").asText("n/a"));
                System.out.println("  line           " + finding.path("line").asText("n/a"));
                System.out.println("  risk           " + finding.path("risk").asText());
                System.out.println("  confidence     " + finding.path("confidence").asDouble());
                System.out.println("  capped         " + finding.path("attribution_capped").asBoolean());
                System.out.println("  rationale      " + finding.path("rationale").asText());
                System.out.println("  blast radius   " + finding.path("blast_radius_size").asInt());
                System.out.println("  dimensions     " + finding.path("required_validation_dimensions"));
                System.out.println("  covering tests " + finding.path("covering_tests"));
                System.out.println();
                System.out.println("  graph path:");
                finding.path("graph_path").forEach(step -> System.out.println("    ["
                        + step.path("distance").asInt() + "] " + step.path("node").asText()
                        + "\n         " + step.path("explanation").asText()));
                finding.path("blind_spots").forEach(b ->
                        System.out.println("  blind spot     " + b.asText()));
                System.out.println();
                return 0;
            }
            System.out.println("  No impact finding with id " + impactId);
            return 2;
        }
    }

    @CommandLine.Command(name = "change", description = "Explain why a change event was applied")
    static final class Change implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @CommandLine.Parameters(index = "0", description = "CHANGE_ID")
        String changeId;

        @Override
        public Integer call() {
            StageContext context = options.context();
            var ledger = EdgeSupport.openLedger(context);
            for (var entry : ledger.entries()) {
                if (!changeId.equals(entry.event().getChangeId())) {
                    continue;
                }
                var event = entry.event();
                System.out.println();
                System.out.println("  " + changeId + "  [" + event.getStatus() + "]");
                System.out.println("  sequence       " + entry.sequence());
                System.out.println("  event hash     " + entry.eventHash());
                System.out.println("  previous hash  " + entry.previousHash());
                System.out.println("  edge           " + event.getEdgeId());
                System.out.println("  file           " + event.getFileId());
                System.out.println("  operation      " + event.getOperation());
                System.out.println("  path before    " + event.getPathBefore());
                System.out.println("  path after     " + event.getPathAfter());
                System.out.println("  sha before     " + event.getBeforeSha256());
                System.out.println("  sha after      " + event.getAfterSha256());
                System.out.println("  agent          " + event.getAgent());
                System.out.println("  provider       " + (event.getProvider() == null ? "n/a"
                        : event.getProvider().type() + " " + event.getProvider().name()));
                System.out.println("  recipe         " + event.getRecipeId());
                System.out.println("  knowledge refs " + event.getKnowledgeRefs());
                System.out.println("  impact refs    " + event.getImpactRefs());
                System.out.println("  patch          " + event.getPatchRef());
                if (event.getRejectionReason() != null) {
                    System.out.println("  reason         " + event.getRejectionReason());
                }
                if (event.getAi() != null) {
                    System.out.println("  AI model       " + event.getAi().modelIdentity());
                    System.out.println("  AI outcome     " + event.getAi().outcome());
                }
                System.out.println();
                return 0;
            }
            System.out.println("  No change event with id " + changeId);
            return 2;
        }
    }
}

@CommandLine.Command(name = "evidence", description =
        "Show the evidence supporting a claim, or every claim")
final class EvidenceCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Parameters(index = "0", arity = "0..1", description = "CLAIM_ID (optional)")
    String claimId;

    @Override
    public Integer call() {
        StageContext context = options.context();
        JsonNode claims = StageSupport.requireUpstream(context, "19-evidence", "claims.json",
                "Run: bootshift report");
        System.out.println();
        for (JsonNode claim : claims.path("claims")) {
            if (claimId != null && !claimId.equals(claim.path("claim_id").asText())) {
                continue;
            }
            System.out.println("  " + claim.path("claim_id").asText() + "  ["
                    + claim.path("dimension").asText() + " = "
                    + claim.path("evidence_level").asText() + "]");
            System.out.println("  " + claim.path("statement").asText());
            System.out.println("  required     " + claim.path("required_level").asText()
                    + "   meets: " + claim.path("meets_requirement").asBoolean());
            System.out.println("  coverage     " + claim.path("coverage").asText("MISSING"));
            System.out.println("  publishable  " + claim.path("publishable").asBoolean());
            System.out.println("  evidence:");
            claim.path("evidence_refs").forEach(r -> System.out.println("    " + r.asText()));
            if (claim.path("blind_spots").size() > 0) {
                System.out.println("  blind spots:");
                claim.path("blind_spots").forEach(b -> System.out.println("    " + b.asText()));
            }
            System.out.println();
        }
        return 0;
    }
}

@CommandLine.Command(name = "gaps", description =
        "List every partially observed dimension declared by the run")
final class GapsCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        StageContext context = options.context();
        var artifact = new ProvenanceStage().collect(context, "gaps");
        System.out.println();
        System.out.println("  " + artifact.path("count").asInt() + " gap(s)");
        System.out.println();
        artifact.path("items").forEach(gap -> {
            System.out.println("  " + gap.path("id").asText("GAP") + "  ["
                    + gap.path("dimension").asText() + "]  from " + gap.path("stage").asText());
            System.out.println("    " + gap.path("description").asText());
            System.out.println("    impact: " + gap.path("impact").asText());
            System.out.println();
        });
        return 0;
    }
}

@CommandLine.Command(name = "blind-spots", description =
        "List everything this run could not observe at all")
final class BlindSpotsCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        StageContext context = options.context();
        var artifact = new ProvenanceStage().collect(context, "blind_spots");
        System.out.println();
        System.out.println("  " + artifact.path("count").asInt() + " blind spot(s)");
        System.out.println();
        artifact.path("items").forEach(spot -> {
            System.out.println("  " + spot.path("id").asText("BS") + "  ["
                    + spot.path("dimension").asText() + "]  from " + spot.path("stage").asText());
            System.out.println("    " + spot.path("description").asText());
            System.out.println("    reason: " + spot.path("reason").asText());
            System.out.println();
        });
        System.out.println("  This harness asserts only what it observed. Everything above is "
                + "outside observed coverage.");
        System.out.println();
        return 0;
    }
}
