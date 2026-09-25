package com.mars.harness.kernel.engine.report;

import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.ledger.ChangeLedger;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.engine.run.RunSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lineage queries (spec §19). Every allocated identity stays queryable after rename, delete and
 * merge: its record, versions, reattachment evidence, the change events that touched it, and the
 * lineage entries that say who proposed, why, and who approved.
 */
public final class LineageService {

    private final RunSession session;

    public LineageService(RunSession session) {
        this.session = session;
    }

    public Map<String, Object> lineage(String id) {
        HarnessIds.Kind kind = HarnessIds.kindOf(id);
        if (kind == null) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL, "Not a harness identifier: " + id);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("kind", kind.name());
        switch (kind) {
            case FILE -> {
                FileRecord record = session.fileRegistry.byId(id).orElseThrow(() -> missing(id));
                view.put("record", record);
                view.put("change_events", events(e -> id.equals(e.event().getFileId())));
                view.put("program_units", session.identity.programUnits.values().stream().filter(u -> id.equals(u.fileId))
                        .map(u -> u.programUnitId + " " + u.fqn + " [" + u.status + "]").toList());
            }
            case PROGRAM_UNIT -> view.put("record", session.identity.programUnit(id).orElseThrow(() -> missing(id)));
            case SYMBOL -> {
                view.put("record", session.identity.symbol(id).orElseThrow(() -> missing(id)));
                view.put("statements", session.identity.statements.values().stream().filter(s -> id.equals(s.parentSymbolId))
                        .map(s -> s.statementId + " [" + s.status + "] " + s.currentText).toList());
            }
            case STATEMENT -> view.put("record", session.identity.statement(id).orElseThrow(() -> missing(id)));
            case MODULE -> view.put("record", session.identity.module(id).orElseThrow(() -> missing(id)));
            case FINDING -> {
                Finding finding = session.findings.stream().filter(f -> f.findingId().equals(id)).findFirst()
                        .orElseThrow(() -> missing(id));
                view.put("record", finding);
                view.put("proposals", session.proposals.all().stream().filter(p -> p.findingRefs().contains(id))
                        .map(p -> p.proposalId() + " " + p.capability() + "/" + p.providerType() + " -> "
                                + session.record.proposalStatus.get(p.proposalId())).toList());
                view.put("decisions", session.approvals.all().stream().filter(d -> d.findingIds().contains(id)).toList());
                if (finding.statementId() != null) {
                    view.put("anchor_statement", session.identity.statement(finding.statementId()).orElse(null));
                }
            }
            case CHANGE -> {
                view.put("change_events", events(e -> id.equals(e.event().getChangeId())));
                Path patch = session.layout.patches().resolve(id + ".patch");
                try {
                    view.put("patch", Files.isRegularFile(patch) ? Files.readString(patch) : null);
                } catch (IOException e) {
                    view.put("patch", "unreadable: " + e.getMessage());
                }
            }
            case PROPOSAL -> {
                view.put("record", session.proposals.find(id).orElseThrow(() -> missing(id)));
                view.put("status", session.record.proposalStatus.get(id));
                view.put("decisions", session.approvals.all().stream().filter(d -> id.equals(d.proposalId())).toList());
            }
            case DECISION -> view.put("record", session.approvals.find(id).orElseThrow(() -> missing(id)));
            default -> view.put("record", null);
        }
        view.put("lineage_entries", session.lineage.mentioning(id));
        return view;
    }

    private List<ChangeLedger.Entry> events(java.util.function.Predicate<ChangeLedger.Entry> filter) {
        return session.ledger.entries().stream().filter(filter).toList();
    }

    private static HarnessOutcomeException missing(String id) {
        return new HarnessOutcomeException(OutcomeCategory.REFUSAL, "Unknown identity " + id + " in this run");
    }

    public String render(String id) {
        return KernelJson.pretty(lineage(id));
    }
}
