package com.mars.harness.controlcenter.query;

import com.mars.harness.controlcenter.api.dto.EvidenceDtos;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The evidence plane: hash-chained, append-only, read here in chain order and never modified. */
public final class EvidenceProjector {

    private EvidenceProjector() {
    }

    public record Filter(String kind, String producer, String subject, String phaseGroup, String search) {
    }

    public static EvidenceDtos.EvidencePage page(RunReader run, Filter filter, int offset, int limit) {
        List<EvidenceRecord> all = run.evidence();
        List<EvidenceDtos.EvidenceView> matched = new ArrayList<>();
        Map<String, Integer> byKind = new TreeMap<>();
        Map<String, Integer> byProducer = new TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            EvidenceRecord r = all.get(i);
            byKind.merge(r.kind().name(), 1, Integer::sum);
            byProducer.merge(String.valueOf(r.producer()), 1, Integer::sum);
            EvidenceDtos.EvidenceView view = view(r, i + 1);
            if (matches(view, filter)) {
                matched.add(view);
            }
        }
        int from = Math.min(Math.max(0, offset), matched.size());
        int to = Math.min(matched.size(), from + Math.max(1, Math.min(limit, 500)));
        List<String> violations = run.evidenceChainViolations();
        return new EvidenceDtos.EvidencePage(matched.subList(from, to), matched.size(), from, to - from, violations.isEmpty(),
                violations, byKind, byProducer);
    }

    public static EvidenceDtos.EvidenceView view(EvidenceRecord r, int index) {
        return new EvidenceDtos.EvidenceView(r.evidenceId(), index, r.kind().name(), r.summary(),
                SecretRedactor.redact(r.observed()), r.where(), r.subjects(), r.basis(),
                r.reliability() == null ? null : r.reliability().name(), r.observedAt(), r.asOf(), r.artifactRef(),
                r.artifactSha256(), r.producer(), phaseGroup(r.producer()));
    }

    /** The lifecycle area a producer belongs to, for filtering. */
    static String phaseGroup(String producer) {
        if (producer == null) {
            return "OTHER";
        }
        String p = producer.toLowerCase(Locale.ROOT);
        if (p.equals("human")) {
            return "DECISION";
        }
        if (p.startsWith("security")) {
            return "SECURITY";
        }
        if (p.startsWith("migration")) {
            return "MIGRATION";
        }
        if (p.contains("mutation")) {
            return "MUTATION";
        }
        if (p.contains("validation") || p.contains("verify")) {
            return "VALIDATION";
        }
        if (p.contains("baseline")) {
            return "BASELINE";
        }
        return "KERNEL";
    }

    private static boolean matches(EvidenceDtos.EvidenceView v, Filter f) {
        if (f.kind() != null && !f.kind().isBlank() && !f.kind().equalsIgnoreCase(v.kind())) {
            return false;
        }
        if (f.producer() != null && !f.producer().isBlank() && !f.producer().equals(v.producer())) {
            return false;
        }
        if (f.phaseGroup() != null && !f.phaseGroup().isBlank() && !f.phaseGroup().equalsIgnoreCase(v.phaseGroup())) {
            return false;
        }
        if (f.subject() != null && !f.subject().isBlank() && !v.subjects().contains(f.subject())
                && !String.valueOf(v.where()).contains(f.subject())) {
            return false;
        }
        if (f.search() != null && !f.search().isBlank()) {
            String q = f.search().toLowerCase(Locale.ROOT);
            return (v.summary() + " " + v.observed() + " " + v.basis() + " " + v.evidenceId() + " " + v.where())
                    .toLowerCase(Locale.ROOT).contains(q);
        }
        return true;
    }
}
