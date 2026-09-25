package com.mars.harness.kernel.adapters.excel;

import com.bootshift.core.util.Hashing;
import com.mars.harness.kernel.adapters.intake.FindingAnchoring;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.ports.evidence.EvidenceStore;
import com.mars.harness.kernel.ports.identity.IdentityView;
import com.mars.harness.kernel.ports.security.FindingNormalizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The VRH Excel issue register as a canonical finding source.
 *
 * <p>The workbook stays read-only input ("no agent or skill edits it"). Each row becomes a
 * {@link Finding}: the synthesized body is the description; CWEs are detected with VRH's
 * {@code detectCweMentions} rule; and the row is anchored to identity through its
 * affected_files, affected_symbols and data-flow locations.
 */
public final class IssueRegisterNormalizer implements FindingNormalizer {

    public static final String SOURCE = "EXCEL_ISSUE_REGISTER";

    private static final Pattern CWE = Pattern.compile("CWE-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CVE = Pattern.compile("CVE-\\d{4}-\\d+", Pattern.CASE_INSENSITIVE);

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public boolean accepts(Path input) {
        return input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    @Override
    public List<Finding> normalize(Path input, IdentityView identity, EvidenceStore evidence, String runId) {
        String sha;
        try {
            sha = Hashing.sha256File(input);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        List<Finding> findings = new ArrayList<>();
        for (IssueRegister.Issue issue : IssueRegister.list(input)) {
            List<String> cwes = detectCweMentions(issue.body());
            List<String> cves = matches(CVE, issue.body());
            FindingAnchoring.Anchor anchor = FindingAnchoring.anchor(identity, issue.files(), issue.symbols(),
                    List.of(section(issue.raw(), "data_flow"), section(issue.raw(), "detection_notes"),
                            section(issue.raw(), "summary")));
            EvidenceRecord ev = evidence.record(EvidenceRecord.EvidenceKind.IMPORTED_FINDING,
                    issue.id() + " imported from the issue register (" + issue.severity() + ", " + issue.type() + ")",
                    issue.title(), input.getFileName() + "#issues!row" + issue.rowNumber(),
                    anchorSubjects(anchor), "VRH issue-register column contract (00-issue-register/register.js)",
                    EvidenceRecord.Reliability.ASSERTED, issue.reportedOn(), input.toString(), sha, SOURCE);
            List<String> refs = new ArrayList<>(List.of(ev.evidenceId()));
            if (!anchor.unresolved().isEmpty()) {
                refs.add(evidence.record(EvidenceRecord.EvidenceKind.UNKNOWN,
                        issue.id() + ": some register references could not be resolved to identities",
                        String.join("; ", anchor.unresolved()), input.getFileName() + "#issues!row" + issue.rowNumber(),
                        List.of(), "identity resolution", EvidenceRecord.Reliability.UNKNOWN, null, null, null, SOURCE)
                        .evidenceId());
            }
            String rule = cwes.isEmpty() ? "REGISTER:" + issue.id() : cwes.get(0);
            findings.add(new Finding(HarnessIds.allocate(HarnessIds.Kind.FINDING), SOURCE, issue.id(), rule, cwes, cves,
                    Finding.Severity.parse(issue.severity()), status(issue.status()), issue.title(), issue.body(),
                    anchor.fileId(), anchor.programUnitId(), anchor.symbolId(), anchor.statementId(), anchor.location(),
                    List.of(), FindingAnchoring.fingerprint(rule, anchor, issue.id()), null, null, refs, runId, runId,
                    null, null, issue.symbols(), issue.files(), anchor.quality()));
        }
        return findings;
    }

    /** {@code detectCweMentions}: regex, uppercase, dedupe, keep first-appearance order. */
    public static List<String> detectCweMentions(String text) {
        return matches(CWE, text);
    }

    private static List<String> matches(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text == null ? "" : text);
        while (m.find()) {
            found.add(m.group().toUpperCase(Locale.ROOT));
        }
        return new ArrayList<>(found);
    }

    private static String section(java.util.Map<String, String> raw, String column) {
        return raw.getOrDefault(column, "");
    }

    private static List<String> anchorSubjects(FindingAnchoring.Anchor anchor) {
        List<String> subjects = new ArrayList<>(anchor.resolvedFileIds());
        subjects.addAll(anchor.resolvedSymbolIds());
        if (anchor.statementId() != null) {
            subjects.add(anchor.statementId());
        }
        return subjects;
    }

    static Finding.FindingStatus status(String raw) {
        if (raw == null) {
            return Finding.FindingStatus.UNKNOWN;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "open" -> Finding.FindingStatus.OPEN;
            case "in progress" -> Finding.FindingStatus.IN_PROGRESS;
            case "fixed" -> Finding.FindingStatus.FIXED;
            case "closed" -> Finding.FindingStatus.CLOSED;
            default -> Finding.FindingStatus.UNKNOWN;
        };
    }

    public static boolean looksLikeRegister(Path file) {
        try {
            return Files.isRegularFile(file) && IssueRegister.list(file) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
