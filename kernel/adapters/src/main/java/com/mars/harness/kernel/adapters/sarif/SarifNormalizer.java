package com.mars.harness.kernel.adapters.sarif;

import com.bootshift.core.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.adapters.intake.FindingAnchoring;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.identity.Location;
import com.mars.harness.kernel.ports.evidence.EvidenceStore;
import com.mars.harness.kernel.ports.identity.IdentityView;
import com.mars.harness.kernel.ports.security.FindingNormalizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SARIF 2.1.0 intake (CodeQL, Semgrep, and other scanners that emit SARIF).
 *
 * <p>Following GitHub code scanning, a result's identity is not its line. The finding is anchored
 * to the STATEMENT_ID at its primary location, and the tool's {@code partialFingerprints} are kept
 * as additional evidence. CWE comes from rule tags ({@code external/cwe/cwe-89}), rule properties
 * or the message. Severity comes from {@code security-severity} (CVSS-like score), then
 * {@code level}.
 */
public final class SarifNormalizer implements FindingNormalizer {

    public static final String SOURCE = "SARIF";
    private static final Pattern CWE_TAG = Pattern.compile("cwe[-/](\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public boolean accepts(Path input) {
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sarif") || name.endsWith(".sarif.json");
    }

    @Override
    public List<Finding> normalize(Path input, IdentityView identity, EvidenceStore evidence, String runId) {
        JsonNode root = KernelJson.read(input);
        String sha;
        try {
            sha = Hashing.sha256File(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Finding> findings = new ArrayList<>();
        int runIndex = 0;
        for (JsonNode run : root.path("runs")) {
            String tool = run.path("tool").path("driver").path("name").asText("sarif-tool");
            Map<String, JsonNode> rules = new HashMap<>();
            for (JsonNode rule : run.path("tool").path("driver").path("rules")) {
                rules.put(rule.path("id").asText(), rule);
            }
            int resultIndex = 0;
            for (JsonNode result : run.path("results")) {
                String ruleId = result.path("ruleId").asText(result.path("rule").path("id").asText("unknown"));
                JsonNode rule = rules.getOrDefault(ruleId, KernelJson.obj());
                List<String> cwes = cwes(rule, result);
                JsonNode physical = result.path("locations").path(0).path("physicalLocation");
                String uri = physical.path("artifactLocation").path("uri").asText(null);
                int line = physical.path("region").path("startLine").asInt(0);
                String message = result.path("message").path("text").asText("");
                FindingAnchoring.Anchor anchor = FindingAnchoring.anchor(identity,
                        uri == null ? List.of() : List.of(uri), List.of(),
                        uri == null || line == 0 ? List.of() : List.of(fileName(uri) + ":" + line));
                List<Location> flow = new ArrayList<>();
                for (JsonNode step : result.path("codeFlows").path(0).path("threadFlows").path(0).path("locations")) {
                    JsonNode loc = step.path("location").path("physicalLocation");
                    flow.add(Location.of(loc.path("artifactLocation").path("uri").asText(),
                            loc.path("region").path("startLine").asInt(0), loc.path("region").path("endLine")
                                    .asInt(loc.path("region").path("startLine").asInt(0))));
                }
                String sourceId = tool + ":" + ruleId + ":" + runIndex + "/" + resultIndex;
                EvidenceRecord ev = evidence.record(EvidenceRecord.EvidenceKind.IMPORTED_FINDING,
                        tool + " reported " + ruleId + (cwes.isEmpty() ? "" : " (" + String.join(",", cwes) + ")"),
                        message, uri + ":" + line, anchor.statementId() == null ? List.of() : List.of(anchor.statementId()),
                        "SARIF 2.1.0 result; partialFingerprints=" + result.path("partialFingerprints"),
                        EvidenceRecord.Reliability.ASSERTED, null, input.toString(), sha, SOURCE);
                String ruleKey = cwes.isEmpty() ? ruleId : cwes.get(0);
                findings.add(new Finding(HarnessIds.allocate(HarnessIds.Kind.FINDING), SOURCE + ":" + tool, sourceId,
                        ruleId, cwes, List.of(), severity(rule, result), Finding.FindingStatus.OPEN,
                        rule.path("shortDescription").path("text").asText(message), message, anchor.fileId(),
                        anchor.programUnitId(), anchor.symbolId(), anchor.statementId(), anchor.location(), flow,
                        FindingAnchoring.fingerprint(ruleKey, anchor, sourceId), null, null, List.of(ev.evidenceId()),
                        runId, runId, null, null, List.of(), uri == null ? List.of() : List.of(uri), anchor.quality()));
                resultIndex++;
            }
            runIndex++;
        }
        return findings;
    }

    private static List<String> cwes(JsonNode rule, JsonNode result) {
        Set<String> found = new LinkedHashSet<>();
        for (JsonNode tag : rule.path("properties").path("tags")) {
            Matcher m = CWE_TAG.matcher(tag.asText());
            if (m.find()) {
                found.add("CWE-" + Integer.parseInt(m.group(1)));
            }
        }
        for (JsonNode tag : result.path("properties").path("tags")) {
            Matcher m = CWE_TAG.matcher(tag.asText());
            if (m.find()) {
                found.add("CWE-" + Integer.parseInt(m.group(1)));
            }
        }
        Matcher m = Pattern.compile("CWE-(\\d+)", Pattern.CASE_INSENSITIVE).matcher(result.path("message").path("text").asText(""));
        while (m.find()) {
            found.add("CWE-" + Integer.parseInt(m.group(1)));
        }
        return new ArrayList<>(found);
    }

    static Finding.Severity severity(JsonNode rule, JsonNode result) {
        String score = rule.path("properties").path("security-severity").asText(
                result.path("properties").path("security-severity").asText(""));
        if (!score.isBlank()) {
            try {
                double s = Double.parseDouble(score);
                return s >= 9.0 ? Finding.Severity.CRITICAL : s >= 7.0 ? Finding.Severity.HIGH
                        : s >= 4.0 ? Finding.Severity.MEDIUM : s > 0 ? Finding.Severity.LOW : Finding.Severity.INFO;
            } catch (NumberFormatException ignored) {
                // fall through to level
            }
        }
        String level = result.path("level").asText(rule.path("defaultConfiguration").path("level").asText(""));
        return level.isBlank() ? Finding.Severity.UNKNOWN : Finding.Severity.parse(level);
    }

    private static String fileName(String uri) {
        String normalized = uri.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }
}
