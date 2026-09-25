package com.mars.harness.kernel.ports.runtime;

import com.mars.harness.kernel.ports.build.BuildPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Old-versus-new behaviour comparison, ported from the migration reference renderer
 * ({@code compareProbes} and {@code testComparison} in {@code render-migration-report.js}).
 *
 * <p>Rows are matched by probe name, iterating over the baseline's probes. The per-row verdict is
 * {@code identical} (same status and body hash), {@code body-differs} (same status and ok flag,
 * different hash) or {@code status-differs}. A missing after-row is "not run".
 *
 * <p>"A changed status is a contract break; a changed body at the same status is usually an
 * envelope, a timestamp or an ordering." The comparator reports which; it never rounds either
 * down to unchanged.
 *
 * <p>Deviation: the renderer treats only a missing record as unprobed and still compares a record
 * whose application never started. Here a run that did not start counts as not probed, because
 * {@link RuntimePort.RuntimeRun} has no separate "no record" state. Comparing it would make a
 * baseline that never started look like zero probes with no differences.
 */
public final class ProbeComparator {

    public record Row(String name, String request, String beforeStatus, String afterStatus, String beforeHash,
                      String afterHash, boolean same, String verdict) {
    }

    public record Comparison(List<Row> rows, int matched, int bodyOnly, int statusChanged, int total,
                             String verdictText, boolean compared) {
    }

    private ProbeComparator() {
    }

    public static Comparison compare(RuntimePort.RuntimeRun before, RuntimePort.RuntimeRun after) {
        boolean beforeProbed = before != null && before.started();
        boolean afterProbed = after != null && after.started();
        if (!beforeProbed || !afterProbed) {
            return new Comparison(List.of(), 0, 0, 0, 0, beforeProbed || afterProbed
                    ? "⚠️ Only one side was probed — no comparison possible" : "⚠️ Not probed", false);
        }
        List<Row> rows = new ArrayList<>();
        for (RuntimePort.ProbeObservation b : before.probes()) {
            Optional<RuntimePort.ProbeObservation> a = after.probes().stream().filter(p -> p.name().equals(b.name())).findFirst();
            boolean same = a.isPresent() && eq(a.get().status(), b.status()) && eq(a.get().bodyHash(), b.bodyHash());
            boolean statusSame = a.isPresent() && eq(a.get().status(), b.status()) && a.get().ok() == b.ok();
            rows.add(new Row(b.name(), b.method() + " " + b.path(), b.ok() ? String.valueOf(b.status()) : "error",
                    a.map(p -> p.ok() ? String.valueOf(p.status()) : "error").orElse("not run"), b.bodyHash(),
                    a.map(RuntimePort.ProbeObservation::bodyHash).orElse(null), same,
                    same ? "identical" : statusSame ? "body-differs" : "status-differs"));
        }
        int matched = (int) rows.stream().filter(Row::same).count();
        int bodyOnly = (int) rows.stream().filter(r -> r.verdict().equals("body-differs")).count();
        int statusChanged = (int) rows.stream().filter(r -> r.verdict().equals("status-differs")).count();
        String text = "⚠️ No probes were defined";
        if (!rows.isEmpty()) {
            if (matched == rows.size()) {
                text = "🟢 identical on all " + rows.size() + " probe(s)";
            } else if (statusChanged == 0) {
                text = "🟡 same status on all " + rows.size() + ", body differs on " + bodyOnly;
            } else {
                text = "🔴 " + statusChanged + " of " + rows.size() + " probe(s) changed status";
            }
        }
        return new Comparison(rows, matched, bodyOnly, statusChanged, rows.size(), text, true);
    }

    /** {@code testComparison}: the verdict strings are the reference renderer's, verbatim. */
    public record TestVerdict(BuildPort.TestSummary before, BuildPort.TestSummary after, String verdict, boolean worse,
                              boolean compared) {
    }

    public static TestVerdict compareTests(BuildPort.TestSummary before, BuildPort.TestSummary after) {
        if (before == null && after == null) {
            return new TestVerdict(null, null, "⚠️ neither side ran the test suite", false, false);
        }
        if (before == null || after == null) {
            return new TestVerdict(before, after, "⚠️ only one side ran the test suite", false, false);
        }
        int badBefore = before.failures() + before.errors();
        int badAfter = after.failures() + after.errors();
        boolean worse = badAfter > badBefore;
        String verdict = badAfter == badBefore
                ? (badBefore == 0 ? "🟢 all green, before and after"
                : "🟡 the same " + badBefore + " pre-existing failure(s) — none introduced")
                : (worse ? "🔴 " + (badAfter - badBefore) + " new failure(s) introduced"
                : "🟢 " + (badBefore - badAfter) + " fewer failure(s) than before");
        return new TestVerdict(before, after, verdict, worse, true);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
