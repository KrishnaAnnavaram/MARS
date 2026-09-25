package com.bootshift.stages.stage07;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.docs.HttpDocumentationAdapter;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.build.BuildModelCodec;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.docs.DocumentationPort;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 07 - Documentation Registry (spec section 18).
 *
 * <p>Runs after the path is frozen, because only then is it known which edge-specific documents
 * matter. Every retrieved document is pinned by content hash; the stored snapshot is the authority
 * and no summary may replace it.
 *
 * <p>Documents are recorded with an explicit trust level. A community source is admitted only as
 * ADVISORY and can never become the sole basis for a verified migration fact.
 */
public final class DocumentationStage implements Stage {

    public static final String OUTPUT_DIR = "07-documentation";

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
        return "Fetch and content-address the authoritative documents for the frozen migration path";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.TARGET_FROZEN);
    }

    @Override
    public RunState postcondition() {
        return RunState.DOCUMENTATION_RETRIEVED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("06-target/target-state.json", "06-target/migration-path.json",
                "02-build/build-model.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("document-registry.json", "document-coverage.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode target = StageSupport.requireUpstream(context, "06-target", "target-state.json",
                "Run: harness resolve-target --target auto");
        JsonNode path = StageSupport.requireUpstream(context, "06-target", "migration-path.json",
                "Run: harness resolve-target --target auto");
        JsonNode buildNode = StageSupport.optionalUpstream(context, "02-build", "build-model.json");
        JsonNode dependencyNode = StageSupport.optionalUpstream(context, "02-build",
                "dependency-model.json");

        // Which components this repository actually uses. Fetching only Spring Boot documentation
        // and calling the migration documented was the previous behaviour; a Boot major moves Spring
        // Framework, Security, Data, Hibernate, Jackson and the Cloud train underneath it at once.
        BuildSystemPort.BuildModel buildModel = buildNode == null ? null
                : BuildModelCodec.decode(buildNode, dependencyNode);
        ComponentDocumentationCatalog.ComponentDetection detection =
                ComponentDocumentationCatalog.detect(buildModel);
        Set<String> components = detection.components();
        List<String> unsupportedComponents =
                ComponentDocumentationCatalog.unsupportedComponents(components);

        HttpDocumentationAdapter documentation = new HttpDocumentationAdapter(context.http(),
                context.run().runWorkspace().resolve("documents"));

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        List<DocumentationPort.DocumentRef> retrieved = new ArrayList<>();
        List<ObjectNode> attempts = new ArrayList<>();
        // Retrieval is exact-edge aware: the edge a document was fetched for is recorded, so a Boot
        // 4 migration guide can never authorize a change on the earlier Boot 3 edge.
        Map<String, String> documentEdge = new LinkedHashMap<>();
        Map<String, String> documentEdgeClass = new LinkedHashMap<>();
        Set<String> retrievedUrls = new LinkedHashSet<>();

        for (JsonNode edge : path.path("edges")) {
            String from = edge.path("fromVersion").asText();
            String to = edge.path("toVersion").asText();
            String edgeId = edge.path("edgeId").asText();
            String edgeClass = edge.path("edgeClass").asText("MINOR");
            if (from.equals(to)) {
                continue;
            }
            String line = ComponentDocumentationCatalog.lineOf(to);
            String major = ComponentDocumentationCatalog.majorOf(to);

            List<ComponentDocumentationCatalog.Source> sources = new ArrayList<>();
            for (String component : components) {
                sources.addAll(ComponentDocumentationCatalog.sourcesFor(component,
                        ComponentDocumentationCatalog.Scope.PER_EDGE));
                if ("MAJOR_BOUNDARY".equals(edgeClass)) {
                    sources.addAll(ComponentDocumentationCatalog.sourcesFor(component,
                            ComponentDocumentationCatalog.Scope.PER_MAJOR_BOUNDARY));
                }
            }

            for (ComponentDocumentationCatalog.Source source : sources) {
                String url = source.url(line, major, to);
                // A component document with no edge-specific URL would otherwise be re-fetched for
                // every edge and recorded as if each retrieval were independent evidence.
                String dedupeKey = url + "|" + edgeId;
                if (!retrievedUrls.add(dedupeKey)) {
                    continue;
                }
                Optional<DocumentationPort.DocumentRef> ref = documentation.retrieve(url,
                        source.publisher(), source.component(), from, to, source.trustLevel());
                attempts.add(renderAttempt(url, source, from, to, edgeId, edgeClass, ref));
                ref.ifPresent(r -> {
                    retrieved.add(r);
                    documentEdge.put(r.documentId(), edgeId);
                    documentEdgeClass.put(r.documentId(), edgeClass);
                });
            }
        }

        for (String component : components) {
            for (ComponentDocumentationCatalog.Source source
                    : ComponentDocumentationCatalog.sourcesFor(component,
                            ComponentDocumentationCatalog.Scope.ONCE)) {
                String url = source.url(
                        ComponentDocumentationCatalog.lineOf(target.path("landing_version").asText()),
                        ComponentDocumentationCatalog.majorOf(target.path("landing_version").asText()),
                        target.path("landing_version").asText());
                if (!retrievedUrls.add(url + "|RUN")) {
                    continue;
                }
                Optional<DocumentationPort.DocumentRef> ref = documentation.retrieve(url,
                        source.publisher(), source.component(),
                        target.path("source_version").asText(),
                        target.path("landing_version").asText(), source.trustLevel());
                attempts.add(renderAttempt(url, source, target.path("source_version").asText(),
                        target.path("landing_version").asText(), null, "RUN_SCOPED", ref));
                ref.ifPresent(r -> {
                    retrieved.add(r);
                    documentEdgeClass.put(r.documentId(), "RUN_SCOPED");
                });
            }
        }

        ObjectNode registry = Json.obj();
        registry.put("online", documentation.online());
        registry.put("document_count", retrieved.size());
        registry.put("precedence", "1 exact migration guide, 2 release notes, 3 BOM and POM metadata, "
                + "4 API docs, 5 general docs, 6 upstream project docs, 7 community (advisory only)");
        ArrayNode documents = Json.arr();
        for (DocumentationPort.DocumentRef ref : retrieved) {
            ObjectNode node = Json.obj();
            node.put("document_id", ref.documentId());
            node.put("publisher", ref.publisher());
            node.put("component", ref.component());
            node.put("source_version", ref.sourceVersion());
            node.put("target_version", ref.targetVersion());
            node.put("url", ref.url());
            node.put("retrieved_at", ref.retrievedAt());
            node.put("content_hash", ref.contentHash());
            node.put("trust_level", ref.trustLevel().name());
            node.put("edge_id", documentEdge.get(ref.documentId()));
            node.put("edge_class", documentEdgeClass.getOrDefault(ref.documentId(), "RUN_SCOPED"));
            node.put("authorizes_only_this_edge", documentEdge.containsKey(ref.documentId()));
            node.put("advisory_only",
                    ref.trustLevel() == DocumentationPort.TrustLevel.COMMUNITY_ADVISORY);
            node.put("etag", ref.etag());
            node.put("last_modified", ref.lastModified());
            node.put("from_cache", ref.fromCache());
            node.put("size_bytes", ref.sizeBytes());
            node.put("extracted_text_length", ref.extractedTextLength());
            node.put("extraction_usable", ref.extractedTextLength() > 500);
            node.put("local_path", ref.localPath());
            documents.add(node);
            documentation.body(ref.documentId()).ifPresent(body ->
                    StageSupport.toEvidence(context, "document", body,
                            EvidenceManifest.Classification.PUBLIC, "SEALED_EVIDENCE", OUTPUT_DIR));
        }
        registry.set("documents", documents);
        registry.set("components_detected", Json.toTree(components));
        registry.set("components_without_authoritative_source", Json.toTree(unsupportedComponents));
        // Two different gaps, kept apart on purpose: one is a gap in this harness's catalogue of
        // documents, the other is a gap in what this harness can reach at all.
        registry.set("public_components_without_catalogued_document",
                Json.toTree(detection.publicWithoutCatalogue()));
        registry.set("possibly_internal_components", Json.toTree(detection.possiblyInternal()));
        registry.put("component_classification_rule", "A coordinate the build resolver fetched from "
                + "a public repository is a public open-source component, whatever its group is "
                + "named. Only a coordinate that did not resolve, or that resolved from a repository "
                + "not demonstrably public, is reported as possibly organization-internal - and only "
                + "as possibly, because a private mirror of a public library is indistinguishable "
                + "from here.");
        registry.put("exact_edge_rule", "A document is bound to the edge it was retrieved for. "
                + "A migration guide for a later boundary can never authorize a change on an "
                + "earlier edge.");
        writer.write("document-registry.json", StageSupport.compose(envelope, registry));

        ObjectNode coverage = Json.obj();
        long edgesNeedingDocs = 0;
        for (JsonNode edge : path.path("edges")) {
            if (!edge.path("fromVersion").asText().equals(edge.path("toVersion").asText())) {
                edgesNeedingDocs++;
            }
        }
        long edgesWithGuide = retrieved.stream()
                .filter(r -> r.trustLevel() == DocumentationPort.TrustLevel.OFFICIAL_MIGRATION_GUIDE)
                .map(DocumentationPort.DocumentRef::targetVersion)
                .distinct().count();
        long unusable = retrieved.stream().filter(r -> r.extractedTextLength() <= 500).count();
        coverage.put("documents_with_unusable_extraction", unusable);
        coverage.put("edges_requiring_documentation", edgesNeedingDocs);
        coverage.put("edges_with_official_migration_guide", edgesWithGuide);
        coverage.put("attempt_count", attempts.size());
        coverage.set("components_detected", Json.toTree(components));
        coverage.set("components_without_authoritative_source", Json.toTree(unsupportedComponents));
        coverage.put("public_components_without_catalogued_document",
                detection.publicWithoutCatalogue().size());
        coverage.put("possibly_internal_component_count", detection.possiblyInternal().size());
        Map<String, Integer> byComponent = new java.util.TreeMap<>();
        retrieved.forEach(r -> byComponent.merge(r.component(), 1, Integer::sum));
        coverage.set("documents_by_component", Json.toTree(byComponent));
        coverage.set("attempts", Json.toTree(attempts));
        writer.write("document-coverage.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), coverage));

        if (edgesWithGuide < edgesNeedingDocs) {
            envelope.gap(new Envelope.Gap("GAP-DOC-001", "DOCUMENTATION",
                    "Only " + edgesWithGuide + " of " + edgesNeedingDocs
                            + " edge(s) have an official migration guide pinned",
                    "Migration facts for the uncovered edges must come from the artifact channel alone"));
        }
        if (!unsupportedComponents.isEmpty()) {
            envelope.gap(new Envelope.Gap("GAP-DOC-003", "DOCUMENTATION",
                    "No authoritative documentation source is known for: " + unsupportedComponents,
                    "Migration facts for those components can only come from the artifact channel; "
                            + "changes they document are invisible to the documentation channel"));
        }
        if (unusable > 0) {
            envelope.gap(new Envelope.Gap("GAP-DOC-002", "DOCUMENTATION",
                    unusable + " pinned document(s) yielded no usable readable text",
                    "The documentation channel cannot extract candidate facts from those documents; "
                            + "only the artifact channel covers the edges they describe"));
        }
        if (!documentation.online()) {
            envelope.blindSpot(new Envelope.BlindSpot("BS-DOC-001", "DOCUMENTATION",
                    "Offline mode: no new documentation could be retrieved",
                    "Only previously pinned documents are available to the knowledge engine"));
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.DOCUMENTATION_RETRIEVED,
                retrieved.size() + " document(s) pinned");
        context.runStateStore().updateState(context.run().runId(), RunState.DOCUMENTATION_RETRIEVED,
                "documentation pinned");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        attempts.stream().filter(a -> !a.path("retrieved").asBoolean())
                .forEach(a -> messages.add("not retrieved: " + a.path("url").asText()));

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                retrieved.size() + " document(s) pinned for " + edgesNeedingDocs + " edge(s); "
                        + edgesWithGuide + " official migration guide(s)",
                messages, artifacts, hash);
    }

    private ObjectNode renderAttempt(String url, ComponentDocumentationCatalog.Source source,
                                     String from, String to, String edgeId, String edgeClass,
                                     Optional<DocumentationPort.DocumentRef> ref) {
        ObjectNode node = Json.obj();
        node.put("url", url);
        node.put("publisher", source.publisher());
        node.put("component", source.component());
        node.put("trust_level", source.trustLevel().name());
        node.put("scope", source.scope().name());
        node.put("source_version", from);
        node.put("target_version", to);
        node.put("edge_id", edgeId);
        node.put("edge_class", edgeClass);
        node.put("retrieved", ref.isPresent());
        node.put("document_id", ref.map(DocumentationPort.DocumentRef::documentId).orElse(null));
        node.put("description", source.description());
        return node;
    }

    private static String lineOf(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }
}
