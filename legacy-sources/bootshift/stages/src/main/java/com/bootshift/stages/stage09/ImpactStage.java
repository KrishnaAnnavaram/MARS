package com.bootshift.stages.stage09;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.adapters.transform.YamlPropertyModel;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Ids;
import com.bootshift.core.util.Json;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Agent 09 - Impact Analyzer (spec section 20).
 *
 * <p>The bridge between "these facts are true of the framework" and "these parts of <em>this</em>
 * repository are affected". Every finding carries the graph path that explains its inclusion, and no
 * finding ever claims universal safety: the strongest negative statement available is
 * UNAFFECTED_WITHIN_OBSERVED_COVERAGE.
 *
 * <p>Attribution cap: when the underlying relationship was not type-resolved, classification is
 * capped at POSSIBLY_AFFECTED regardless of how convincing the textual match looked.
 */
public final class ImpactStage implements Stage {

    public static final String OUTPUT_DIR = "09-impact";

    /** Confidence ordering used by the cap. */
    public enum Classification {
        DEFINITELY_AFFECTED(4),
        LIKELY_AFFECTED(3),
        POSSIBLY_AFFECTED(2),
        UNAFFECTED_WITHIN_OBSERVED_COVERAGE(1);

        private final int rank;

        Classification(int rank) {
            this.rank = rank;
        }

        public Classification capAt(Classification ceiling) {
            return this.rank > ceiling.rank ? ceiling : this;
        }
    }

    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String id() {
        return OUTPUT_DIR;
    }

    @Override
    public String outputDirectory() {
        return OUTPUT_DIR;
    }

    @Override
    public String purpose() {
        return "Determine which parts of this repository the verified migration facts affect";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.KNOWLEDGE_VERIFIED);
    }

    @Override
    public RunState postcondition() {
        return RunState.IMPACT_ANALYZED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("08-knowledge/migration-knowledge.json", "03-graph/application-graph.json",
                "03-graph/file-registry.json", "01-inventory/inventory-signals.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("impact-report.json", "impact-summary.json", "blast-radius.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode knowledge = StageSupport.requireUpstream(context, "08-knowledge",
                "migration-knowledge.json", "Run: harness knowledge");
        JsonNode graphNode = StageSupport.requireUpstream(context, "03-graph", "application-graph.json",
                "Run: harness graph --repo <path>");
        JsonNode registryNode = StageSupport.requireUpstream(context, "03-graph", "file-registry.json",
                "Run: harness graph --repo <path>");
        JsonNode signals = StageSupport.optionalUpstream(context, "01-inventory", "inventory-signals.json");
        JsonNode baselineRuntime = StageSupport.optionalUpstream(context, "04-baseline",
                "baseline-runtime.json");

        ApplicationGraph graph = ApplicationGraph.fromNode(graphNode);
        FileRegistry registry = FileRegistry.fromNode(registryNode);

        Path root = context.run().originalWorkspace();
        if (!Files.isDirectory(root)) {
            root = context.run().sourceRoot();
        }
        Map<String, String> sourceCache = new LinkedHashMap<>();

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        List<ObjectNode> findings = new ArrayList<>();
        Map<String, Integer> byClassification = new java.util.TreeMap<>();
        Set<String> allAffectedFiles = new LinkedHashSet<>();

        for (JsonNode fact : knowledge.path("facts")) {
            if (!fact.path("authorizes_transformation").asBoolean(false)) {
                continue;
            }
            List<Match> matches = locate(fact, graph, registry, root, sourceCache);
            for (Match match : matches) {
                ObjectNode finding = renderFinding(context, fact, match, graph, registry, baselineRuntime);
                findings.add(finding);
                byClassification.merge(finding.path("classification").asText(), 1, Integer::sum);
                if (match.fileId() != null) {
                    allAffectedFiles.add(match.fileId());
                }
            }
            if (matches.isEmpty()) {
                ObjectNode finding = Json.obj();
                finding.put("impact_id", Ids.impactId(sequence.incrementAndGet()));
                finding.put("knowledge_id", fact.path("knowledge_id").asText());
                finding.put("fact_type", fact.path("type").asText());
                finding.put("subject", fact.path("subject").asText());
                finding.put("classification", Classification.UNAFFECTED_WITHIN_OBSERVED_COVERAGE.name());
                finding.put("rationale", "No node, symbol or configuration key in the observed graph "
                        + "matched this fact. This is not a claim of universal safety: reflection, "
                        + "dynamic configuration and unresolved types are outside observed coverage.");
                finding.put("confidence", 0.6);
                findings.add(finding);
                byClassification.merge(
                        Classification.UNAFFECTED_WITHIN_OBSERVED_COVERAGE.name(), 1, Integer::sum);
            }
        }

        // Accuracy is measured, not assumed (spec section 20).
        AccuracyHarness.Result accuracy = new AccuracyHarness().evaluate(context.harnessRoot());
        envelope.stat("impact_precision", accuracy.precision())
                .stat("impact_recall", accuracy.recall())
                .stat("fixtures_evaluated", accuracy.fixtures());

        boolean recallBelowFloor = accuracy.evaluated()
                && accuracy.recall() < context.policy().impactRecallFloor();
        if (recallBelowFloor) {
            envelope.gap(new Envelope.Gap("GAP-IMPACT-001", "IMPACT_ACCURACY",
                    "Measured impact recall " + accuracy.recall() + " is below the policy floor "
                            + context.policy().impactRecallFloor(),
                    "Validation breadth is escalated for every edge to compensate"));
        }
        if (!accuracy.evaluated()) {
            envelope.gap(new Envelope.Gap("GAP-IMPACT-002", "IMPACT_ACCURACY",
                    "No held-out impact fixtures were available, so precision and recall are unmeasured",
                    "Impact findings should be treated as unvalidated until the evaluation corpus exists"));
        }

        ObjectNode report = Json.obj();
        report.put("finding_count", findings.size());
        report.put("affected_file_count", allAffectedFiles.size());
        report.put("attribution_cap_rule",
                "A finding derived from an unresolved or ambiguous relationship is capped at "
                        + "POSSIBLY_AFFECTED");
        report.set("accuracy", accuracy.toNode());
        report.set("findings", Json.toTree(findings));
        ObjectNode reportArtifact = StageSupport.compose(envelope, report);
        StageSupport.validate(context, writer, "impact/impact-report.schema.json",
                "impact-report.json", reportArtifact);
        writer.write("impact-report.json", reportArtifact);

        ObjectNode summary = Json.obj();
        summary.set("by_classification", Json.toTree(byClassification));
        Map<String, Integer> byModule = new java.util.TreeMap<>();
        allAffectedFiles.forEach(fileId -> registry.byId(fileId)
                .ifPresent(r -> byModule.merge(r.getModule(), 1, Integer::sum)));
        summary.set("affected_files_by_module", Json.toTree(byModule));
        Map<String, Integer> byDimension = new java.util.TreeMap<>();
        findings.forEach(f -> f.path("required_validation_dimensions")
                .forEach(d -> byDimension.merge(d.asText(), 1, Integer::sum)));
        summary.set("required_validation_dimensions", Json.toTree(byDimension));
        writer.write("impact-summary.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), summary));

        ObjectNode blastRadius = Json.obj();
        ArrayNode radii = Json.arr();
        for (String fileId : allAffectedFiles) {
            ObjectNode node = Json.obj();
            node.put("file_id", fileId);
            registry.byId(fileId).ifPresent(r -> node.put("path", r.getCurrentPath()));
            Set<String> reached = new LinkedHashSet<>();
            ArrayNode paths = Json.arr();
            for (GraphNode start : graph.nodesForFile(fileId)) {
                for (ApplicationGraph.Reached hit : graph.blastRadius(start.getId(), 4)) {
                    if (reached.add(hit.nodeId()) && paths.size() < 25) {
                        ObjectNode pathNode = Json.obj();
                        pathNode.put("node", hit.nodeId());
                        pathNode.put("distance", hit.distance());
                        pathNode.put("explanation", renderPath(hit.path()));
                        paths.add(pathNode);
                    }
                }
            }
            node.put("reached_node_count", reached.size());
            node.set("sample_paths", paths);
            radii.add(node);
        }
        blastRadius.put("file_count", allAffectedFiles.size());
        blastRadius.set("radii", radii);
        writer.write("blast-radius.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), blastRadius));

        StageSupport.toEvidence(context, "impact-report", reportArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Impact artifacts failed schema validation", writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.IMPACT_ANALYZED, findings.size() + " finding(s)");
        context.runStateStore().updateState(context.run().runId(), RunState.IMPACT_ANALYZED,
                "impact analyzed");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        if (recallBelowFloor) {
            messages.add("Measured impact recall " + accuracy.recall() + " is below the floor "
                    + context.policy().impactRecallFloor() + "; validation breadth is escalated");
        }

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                findings.size() + " impact finding(s) across " + allAffectedFiles.size()
                        + " file(s); " + byClassification,
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ location

    /** One place a fact touches the repository, with the evidence that located it. */
    record Match(String fileId, String symbolId, String nodeId, Classification classification,
                 String rationale, double confidence, Integer line, boolean typeResolved) {
    }

    /**
     * The matcher, reachable by the accuracy harness so that measured precision and recall come
     * from running THIS code over a fixture, not from a number written next to the ground truth.
     */
    static List<Match> locateFor(ImpactStage stage, JsonNode fact, ApplicationGraph graph,
                                 FileRegistry registry, Path root,
                                 Map<String, String> sourceCache) {
        return stage.locate(fact, graph, registry, root, sourceCache);
    }

    private List<Match> locate(JsonNode fact, ApplicationGraph graph, FileRegistry registry,
                               Path root, Map<String, String> sourceCache) {
        String type = fact.path("type").asText();
        String subject = fact.path("subject").asText();
        String from = fact.path("from").asText(null);

        return switch (type) {
            case "API_REMOVED", "API_RENAMED", "API_SIGNATURE_CHANGED" ->
                    locateTypeReference(subject, from, graph, registry, root, sourceCache);
            case "PROPERTY_REMOVED", "PROPERTY_RENAMED", "PROPERTY_SILENTLY_IGNORED" ->
                    locateProperty(subject, graph, registry, root, sourceCache);
            case "ARTIFACT_REMOVED", "ARTIFACT_RELOCATED", "MANAGED_VERSION_CHANGED" ->
                    locateLibrary(subject, graph, registry, root, sourceCache);
            case "BASELINE_REQUIREMENT", "COMPATIBILITY_REQUIREMENT" ->
                    locateBuildDescriptors(registry);
            case "BEHAVIOR_CHANGED_NO_API_CHANGE", "DEFAULT_CHANGED" ->
                    locateResourceOrType(subject, from, graph, registry, root, sourceCache);
            default -> List.of();
        };
    }

    private List<Match> locateTypeReference(String subject, String from, ApplicationGraph graph,
                                            FileRegistry registry, Path root,
                                            Map<String, String> sourceCache) {
        List<Match> matches = new ArrayList<>();
        String simpleName = subject.contains(".")
                ? subject.substring(subject.lastIndexOf('.') + 1) : subject;
        String searchToken = from != null && !from.isBlank() ? from : subject;

        // 1. graph-resolved references are the strongest signal.
        for (GraphNode node : graph.nodes()) {
            boolean referenced = subject.equals(node.getFqn())
                    || node.getAnnotations().stream().anyMatch(a -> a.startsWith(simpleName));
            if (!referenced) {
                continue;
            }
            boolean resolved = node.getAttribution() == GraphNode.Attribution.RESOLVED;
            Classification classification = (resolved ? Classification.DEFINITELY_AFFECTED
                    : Classification.POSSIBLY_AFFECTED)
                    .capAt(resolved ? Classification.DEFINITELY_AFFECTED : Classification.POSSIBLY_AFFECTED);
            matches.add(new Match(node.getFileId(), node.getSymbolId(), node.getId(), classification,
                    "Graph node " + node.getId() + " references " + subject
                            + " with attribution " + node.getAttribution(),
                    resolved ? 0.95 : 0.5, node.getLineStart(), resolved));
        }

        // 2. Declaration match: a file whose package plus type declaration IS the subject.
        //
        // A fully qualified subject rarely appears verbatim in the file that declares it - the
        // package is on one line and the type name on another - so a literal scan misses the
        // declaring file entirely while a simple-name scan hits every same-named type in the
        // repository. Matching the pair distinguishes com.example.billing.Order from
        // com.example.catalogue.Order.
        if (subject.contains(".")) {
            String packageName = subject.substring(0, subject.lastIndexOf('.'));
            for (FileRecord record : registry.active()) {
                if (!record.getRole().isJavaSource()) {
                    continue;
                }
                String content = readSource(root, record, sourceCache);
                if (content == null || !declaresType(content, packageName, simpleName)) {
                    continue;
                }
                if (matches.stream().anyMatch(m -> record.getFileId().equals(m.fileId()))) {
                    continue;
                }
                matches.add(new Match(record.getFileId(), null, "FILE:" + record.getFileId(),
                        Classification.DEFINITELY_AFFECTED,
                        record.getCurrentPath() + " declares " + subject
                                + " (package declaration plus type declaration); this is the "
                                + "fully qualified type the fact names, not another type sharing "
                                + "its simple name",
                        0.9, lineOf(content, Math.max(0, content.indexOf(simpleName))), true));
            }
        }

        // 3. Textual scan for namespace-level facts the graph cannot carry as a single node.
        //
        // Two rules make this usable rather than noisy. Comments and string literals are removed
        // first, because a file that merely mentions a removed API in prose is not affected by its
        // removal. And the match must sit on Java identifier boundaries, because
        // "EnableEurekaClientMetrics" contains "EnableEurekaClient" and is a different symbol.
        if (searchToken.length() >= 4) {
            Pattern pattern = Pattern.compile(Pattern.quote(searchToken));
            for (FileRecord record : registry.active()) {
                if (!record.getRole().isJavaSource()) {
                    continue;
                }
                String raw = readSource(root, record, sourceCache);
                if (raw == null) {
                    continue;
                }
                String content = stripCommentsAndLiterals(raw);
                var matcher = pattern.matcher(content);
                Integer hit = null;
                while (matcher.find()) {
                    if (isIdentifierBoundedMatch(content, matcher.start(), matcher.end())) {
                        hit = matcher.start();
                        break;
                    }
                }
                boolean reflective = false;
                if (hit == null) {
                    // Not a code reference. It may still be a reflective one: a type named only
                    // inside Class.forName or getBean is genuinely used, and dropping it because
                    // literals were stripped would trade a false positive for a false negative.
                    Integer reflectiveHit = findReflectiveLiteral(raw, searchToken);
                    if (reflectiveHit == null) {
                        continue;
                    }
                    hit = reflectiveHit;
                    reflective = true;
                }
                boolean alreadyMatched = matches.stream()
                        .anyMatch(m -> record.getFileId().equals(m.fileId()));
                if (alreadyMatched) {
                    continue;
                }
                if (reflective) {
                    // POSSIBLE, never definite: static analysis cannot prove the branch executes,
                    // and it cannot see a name assembled at runtime either.
                    matches.add(new Match(record.getFileId(), null, "FILE:" + record.getFileId(),
                            Classification.POSSIBLY_AFFECTED,
                            record.getCurrentPath() + " names " + searchToken + " inside a string "
                                    + "literal passed to a reflective API. Static analysis cannot "
                                    + "prove this executes, and cannot see names assembled at "
                                    + "runtime at all, so this is POSSIBLE rather than definite.",
                            0.45, lineOf(raw, hit), false));
                    continue;
                }
                // A textual match is not a type-resolved fact, so it is capped.
                matches.add(new Match(record.getFileId(), null, "FILE:" + record.getFileId(),
                        Classification.LIKELY_AFFECTED.capAt(Classification.POSSIBLY_AFFECTED),
                        "Source of " + record.getCurrentPath() + " references the token "
                                + searchToken + " outside comments and string literals; this is a "
                                + "textual match, not a type-resolved reference",
                        0.55, lineOf(content, hit), false));
            }
        }
        return matches;
    }

    private List<Match> locateProperty(String subject, ApplicationGraph graph,
                                       FileRegistry registry, Path root,
                                       Map<String, String> sourceCache) {
        List<Match> matches = new ArrayList<>();
        for (GraphNode node : graph.nodesOfType(NodeType.CONFIG_PROPERTY)) {
            String key = node.getFqn() == null ? node.getName() : node.getFqn();
            if (key == null) {
                continue;
            }
            boolean exact = key.equals(subject);
            boolean prefix = key.startsWith(subject + ".") || subject.startsWith(key + ".");
            if (!exact && !prefix) {
                continue;
            }
            String fileId = null;
            for (var edge : graph.incoming(node.getId())) {
                if (edge.getType() == EdgeType.CONFIGURES) {
                    fileId = graph.node(edge.getFrom()).map(GraphNode::getFileId).orElse(null);
                    break;
                }
            }
            matches.add(new Match(fileId, node.getSymbolId(), node.getId(),
                    exact ? Classification.DEFINITELY_AFFECTED : Classification.LIKELY_AFFECTED,
                    exact ? "Configuration key " + key + " is exactly the affected property"
                            : "Configuration key " + key + " shares a prefix with " + subject,
                    exact ? 0.98 : 0.7, null, true));
        }

        // The configuration view of the graph is not always available: it is absent whenever the
        // graph is PARTIAL, which is precisely when an edge failed to compile and the operator most
        // needs to know which files a property migration touches. Falling back to the configuration
        // files themselves keeps the finding, at a confidence that says where it came from.
        Set<String> alreadyMatched = new LinkedHashSet<>();
        matches.forEach(m -> alreadyMatched.add(m.fileId()));
        for (FileRecord record : registry.active()) {
            if (!record.getRole().isConfiguration() || alreadyMatched.contains(record.getFileId())) {
                continue;
            }
            String content = readSource(root, record, sourceCache);
            if (content == null) {
                continue;
            }
            boolean yaml = record.getCurrentPath().endsWith(".yml")
                    || record.getCurrentPath().endsWith(".yaml");
            Integer line = findPropertyLine(content, subject, yaml);
            if (line == null) {
                continue;
            }
            matches.add(new Match(record.getFileId(), null, "FILE:" + record.getFileId(),
                    Classification.DEFINITELY_AFFECTED,
                    record.getCurrentPath() + " sets " + subject
                            + " (found by configuration-file scan; the graph's configuration view "
                            + "did not carry this key)",
                    0.85, line, false));
        }
        return matches;
    }

    private List<Match> locateLibrary(String subject, ApplicationGraph graph,
                                      FileRegistry registry, Path root,
                                      Map<String, String> sourceCache) {
        List<Match> matches = new ArrayList<>();
        String libraryId = "LIB:" + subject;
        graph.node(libraryId).ifPresent(node -> {
            for (var edge : graph.incoming(libraryId)) {
                if (edge.getType() != EdgeType.DEPENDS_ON_LIBRARY) {
                    continue;
                }
                String moduleId = edge.getFrom().replace("MODULE:", "");
                registry.active().stream()
                        .filter(r -> r.getModule().equals(moduleId)
                                && r.getRole() == com.bootshift.core.identity.FileRole.MAVEN_BUILD)
                        .findFirst()
                        .ifPresent(descriptor -> matches.add(new Match(descriptor.getFileId(), null,
                                libraryId, Classification.DEFINITELY_AFFECTED,
                                "Module " + moduleId + " resolves " + subject
                                        + " through its build descriptor",
                                0.95, null, true)));
            }
        });

        // Same reasoning as locateProperty: without the dependency view there is no LIB node, and
        // the report would say a managed-version change affects no file at all.
        if (matches.isEmpty()) {
            String artifactId = subject.contains(":")
                    ? subject.substring(subject.indexOf(':') + 1) : subject;
            String declaration = "<artifactId>" + artifactId + "</artifactId>";
            for (FileRecord record : registry.active()) {
                if (!record.getRole().isBuildDescriptor()) {
                    continue;
                }
                String content = readSource(root, record, sourceCache);
                if (content == null || !content.contains(declaration)) {
                    continue;
                }
                matches.add(new Match(record.getFileId(), null, "FILE:" + record.getFileId(),
                        Classification.DEFINITELY_AFFECTED,
                        record.getCurrentPath() + " declares " + artifactId
                                + " (found by build-descriptor scan; the graph's dependency view "
                                + "did not carry this library)",
                        0.85, null, false));
            }
        }
        return matches;
    }

    /**
     * The line on which a configuration file sets a key.
     *
     * <p>Matches a key at the start of a line in {@code .properties} form and as a dotted path in
     * flow-style YAML. Nested YAML is left to the graph's configuration view, and a miss here is a
     * miss, not a guess.
     */
    /**
     * Finds the line binding a property.
     *
     * <p>YAML nests: {@code server.max-http-header-size} is written as {@code max-http-header-size}
     * indented under {@code server}, so a flat comparison of "the text before the separator" against
     * the dotted key never matches and the property looks unused. The dotted key is resolved
     * structurally for YAML and compared directly for properties files.
     */
    static Integer findPropertyLine(String content, String key, boolean yaml) {
        if (yaml) {
            YamlPropertyModel model = YamlPropertyModel.parse(content);
            if (model.parsed()) {
                for (YamlPropertyModel.Leaf leaf : model.leaves()) {
                    if (leaf.dottedKey().equals(key) || leaf.dottedKey().startsWith(key + ".")) {
                        return leaf.keyLine() + 1;
                    }
                }
                return null;
            }
            // Unparseable YAML: fall through to the flat scan and let the confidence say so.
        }
        return findPropertyLine(content, key);
    }

    private static Integer findPropertyLine(String content, String key) {
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                separator = trimmed.indexOf(':');
            }
            if (separator <= 0) {
                continue;
            }
            String candidate = trimmed.substring(0, separator).strip();
            if (candidate.equals(key) || candidate.startsWith(key + ".")) {
                return i + 1;
            }
        }
        return null;
    }

    private List<Match> locateBuildDescriptors(FileRegistry registry) {
        List<Match> matches = new ArrayList<>();
        registry.active().stream()
                .filter(r -> r.getRole().isBuildDescriptor())
                .forEach(r -> matches.add(new Match(r.getFileId(), null, "FILE:" + r.getFileId(),
                        Classification.DEFINITELY_AFFECTED,
                        "Build descriptor " + r.getCurrentPath() + " declares the affected baseline",
                        0.95, null, true)));
        return matches;
    }

    private List<Match> locateResourceOrType(String subject, String from, ApplicationGraph graph,
                                             FileRegistry registry, Path root,
                                             Map<String, String> sourceCache) {
        List<Match> matches = new ArrayList<>();
        String needle = from != null && !from.isBlank() ? from : subject;
        String fileName = needle.contains("/") ? needle.substring(needle.lastIndexOf('/') + 1) : needle;
        for (FileRecord record : registry.active()) {
            if (record.getCurrentPath().endsWith(fileName)) {
                matches.add(new Match(record.getFileId(), null, "FILE:" + record.getFileId(),
                        Classification.DEFINITELY_AFFECTED,
                        "Resource " + record.getCurrentPath() + " is the artifact the fact concerns",
                        0.9, null, true));
            }
        }
        if (matches.isEmpty()) {
            matches.addAll(locateTypeReference(subject, from, graph, registry, root, sourceCache));
        }
        return matches;
    }

    // ------------------------------------------------------------------ rendering

    private ObjectNode renderFinding(StageContext context, JsonNode fact, Match match,
                                     ApplicationGraph graph, FileRegistry registry,
                                     JsonNode baselineRuntime) {
        ObjectNode finding = Json.obj();
        String impactId = Ids.impactId(sequence.incrementAndGet());
        finding.put("impact_id", impactId);
        finding.put("knowledge_id", fact.path("knowledge_id").asText());
        finding.put("fact_type", fact.path("type").asText());
        finding.put("subject", fact.path("subject").asText());
        finding.put("file_id", match.fileId());
        finding.put("symbol_id", match.symbolId());
        finding.put("node_id", match.nodeId());
        finding.put("line", match.line());
        registry.byId(match.fileId() == null ? "" : match.fileId())
                .ifPresent(r -> {
                    finding.put("path", r.getCurrentPath());
                    finding.put("module", r.getModule());
                });

        Classification classification = match.typeResolved
                ? match.classification() : match.classification().capAt(Classification.POSSIBLY_AFFECTED);
        finding.put("classification", classification.name());
        finding.put("direct", match.symbolId() != null || match.nodeId().startsWith("FILE:"));
        finding.put("confidence", match.confidence());
        finding.put("rationale", match.rationale());
        finding.put("attribution_capped", !match.typeResolved);

        // Graph path that explains the finding.
        ArrayNode paths = Json.arr();
        Set<String> reached = new LinkedHashSet<>();
        graph.node(match.nodeId()).ifPresent(node -> {
            for (ApplicationGraph.Reached hit : graph.blastRadius(node.getId(), 3)) {
                if (paths.size() >= 10) {
                    break;
                }
                if (reached.add(hit.nodeId())) {
                    ObjectNode step = Json.obj();
                    step.put("node", hit.nodeId());
                    step.put("distance", hit.distance());
                    step.put("explanation", renderPath(hit.path()));
                    paths.add(step);
                }
            }
        });
        finding.set("graph_path", paths);
        finding.put("blast_radius_size", reached.size());
        finding.put("risk", riskOf(classification, reached.size(), fact.path("type").asText()));

        // Tests that already protect it.
        ArrayNode tests = Json.arr();
        graph.node(match.nodeId()).ifPresent(node ->
                graph.testsCovering(node.getId()).forEach(t -> tests.add(t.getFqn())));
        finding.set("covering_tests", tests);
        finding.put("protected_by_existing_tests", tests.size() > 0);

        // Required validation dimensions.
        finding.set("required_validation_dimensions",
                Json.toTree(dimensionsFor(fact.path("type").asText(), graph, match)));

        // Blind spots this finding inherits.
        ArrayNode blindSpots = Json.arr();
        if (!match.typeResolved) {
            blindSpots.add("TYPE_ATTRIBUTION: located textually, not by type resolution");
        }
        if (baselineRuntime != null && baselineRuntime.path("modules_started").asInt() == 0) {
            blindSpots.add("RUNTIME: no baseline runtime observation exists for this module");
        }
        finding.set("blind_spots", blindSpots);
        return finding;
    }

    private static String riskOf(Classification classification, int blastRadius, String factType) {
        boolean sensitive = factType.startsWith("API_") || "BEHAVIOR_CHANGED_NO_API_CHANGE".equals(factType);
        if (classification == Classification.DEFINITELY_AFFECTED && (blastRadius > 10 || sensitive)) {
            return "HIGH";
        }
        if (classification == Classification.DEFINITELY_AFFECTED
                || classification == Classification.LIKELY_AFFECTED) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /** Maps a fact type onto the differential dimensions that must observe it. */
    static List<String> dimensionsFor(String factType, ApplicationGraph graph, Match match) {
        Set<String> dimensions = new LinkedHashSet<>();
        switch (factType) {
            case "PROPERTY_REMOVED", "PROPERTY_RENAMED", "PROPERTY_SILENTLY_IGNORED" ->
                    dimensions.add("CONFIGURATION_BINDING");
            case "API_REMOVED", "API_RENAMED", "API_SIGNATURE_CHANGED" ->
                    dimensions.add("CONTEXT_CAPABILITY");
            case "DEFAULT_CHANGED", "BEHAVIOR_CHANGED_NO_API_CHANGE" -> {
                dimensions.add("CONTEXT_CAPABILITY");
                dimensions.add("HTTP_API");
            }
            case "MANAGED_VERSION_CHANGED", "ARTIFACT_REMOVED", "ARTIFACT_RELOCATED" ->
                    dimensions.add("CONTEXT_CAPABILITY");
            default -> dimensions.add("CONTEXT_CAPABILITY");
        }
        graph.node(match.nodeId()).ifPresent(node -> {
            switch (node.getType()) {
                case CONTROLLER, ENDPOINT -> {
                    dimensions.add("HTTP_API");
                    dimensions.add("SERIALIZATION");
                }
                case REPOSITORY, ENTITY, MONGODB_DOCUMENT -> {
                    dimensions.add("PERSISTENCE_STATE");
                    dimensions.add("QUERY_RESULT");
                }
                case SERVICE -> dimensions.add("BUSINESS_RULE_OUTCOME");
                case MESSAGE_DESTINATION -> dimensions.add("EVENT_MESSAGE");
                case EXTERNAL_SYSTEM, DISCOVERY_SERVER, CONFIG_SERVER ->
                        dimensions.add("EXTERNAL_INTEGRATION");
                default -> {
                    // no extra dimension implied by the node kind
                }
            }
        });
        if ("org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter"
                .equals(match.nodeId()) || dimensions.contains("HTTP_API")) {
            dimensions.add("SECURITY_AUTHORIZATION");
        }
        return new ArrayList<>(dimensions);
    }

    static String renderPath(List<ApplicationGraph.PathStep> path) {
        StringBuilder sb = new StringBuilder();
        for (ApplicationGraph.PathStep step : path) {
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(step.fromId()).append(" -[").append(step.edge()).append("]-> ").append(step.toId());
        }
        return sb.length() == 0 ? "(origin)" : sb.toString();
    }

    private String readSource(Path root, FileRecord record, Map<String, String> cache) {
        return cache.computeIfAbsent(record.getFileId(), id -> {
            Path file = root.resolve(record.getCurrentPath());
            if (!Files.isRegularFile(file)) {
                return "";
            }
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String normalize(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ textual matching helpers

    /**
     * True when the file declares exactly this fully qualified type.
     *
     * <p>Both halves are required. The package alone matches every type in it; the simple name alone
     * matches every same-named type anywhere in the repository, which is how an ambiguous simple
     * name turns one real finding into several wrong ones.
     */
    static boolean declaresType(String content, String packageName, String simpleName) {
        String stripped = stripCommentsAndLiterals(content);
        java.util.regex.Matcher packageMatcher = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+" + Pattern.quote(packageName) + "\\s*;")
                .matcher(stripped);
        if (!packageMatcher.find()) {
            return false;
        }
        return java.util.regex.Pattern
                .compile("\\b(class|interface|enum|record|@interface)\\s+"
                        + Pattern.quote(simpleName) + "\\b")
                .matcher(stripped)
                .find();
    }

    /**
     * True when a match sits on Java identifier boundaries.
     *
     * <p>Without this, a search for {@code EnableEurekaClient} matches inside
     * {@code EnableEurekaClientMetrics}, which is a different symbol that the removal does not
     * affect. A leading {@code .} is allowed because a qualified reference legitimately has one.
     */
    static boolean isIdentifierBoundedMatch(String content, int start, int end) {
        if (start > 0) {
            char before = content.charAt(start - 1);
            if (Character.isJavaIdentifierPart(before)) {
                return false;
            }
        }
        if (end < content.length()) {
            char after = content.charAt(end);
            if (Character.isJavaIdentifierPart(after)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Blanks out comments and string literals, preserving offsets and line structure.
     *
     * <p>Offsets are preserved by replacing each removed character with a space rather than deleting
     * it, so a reported line number still points at the right line. A file that mentions a removed
     * API only in prose is not affected by its removal, and reporting it as affected sends a
     * reviewer to read a comment.
     */
    static String stripCommentsAndLiterals(String content) {
        char[] out = content.toCharArray();
        int i = 0;
        int n = out.length;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                        if (i < n && out[i] != '\n') {
                            out[i++] = ' ';
                        }
                        continue;
                    }
                    if (out[i] == '\n') {
                        break;
                    }
                    out[i++] = ' ';
                }
                if (i < n && out[i] == quote) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /**
     * Markers that make a string literal a type reference rather than prose.
     *
     * <p>{@code Class.forName("com.example.Removed")} uses the type. {@code String NOTE =
     * "com.example.Removed"} mentions it. The difference is the call the literal is an argument to,
     * so that is what is looked for.
     */
    private static final List<String> REFLECTIVE_MARKERS = List.of(
            "Class.forName", "forName", "loadClass", "getBean", "getDeclaredMethod", "getMethod",
            "getDeclaredField", "getField", "newInstance", "ConditionalOnClass",
            "ConditionalOnMissingBean", "beanClassName", "setBeanClassName", "registerBeanDefinition",
            "resolveClassName", "getConstructor", "getDeclaredConstructor", "MethodHandles",
            "ServiceLoader.load", "Proxy.newProxyInstance");

    /**
     * Finds the token inside a string literal that is an argument to a reflective API.
     *
     * <p>The window looks backwards from the literal rather than forwards, because the call name
     * precedes its argument, and it is bounded so an unrelated call earlier in the file cannot
     * lend its meaning to a literal far below it.
     */
    static Integer findReflectiveLiteral(String content, String token) {
        java.util.regex.Matcher literal = java.util.regex.Pattern
                .compile("\"(?:[^\"\\\\]|\\\\.)*\"")
                .matcher(content);
        while (literal.find()) {
            String value = literal.group();
            if (!value.contains(token)) {
                continue;
            }
            int windowStart = Math.max(0, literal.start() - 120);
            String window = content.substring(windowStart, literal.start());
            for (String marker : REFLECTIVE_MARKERS) {
                if (window.contains(marker)) {
                    return literal.start();
                }
            }
        }
        return null;
    }
}
