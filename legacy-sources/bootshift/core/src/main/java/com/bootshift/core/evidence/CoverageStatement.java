package com.bootshift.core.evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * The mandatory coverage statement that accompanies every evidence-level assertion (spec 38).
 *
 * <p>The harness refuses to render a dimension assertion without one, because "Security = E4" says
 * nothing about the endpoints that were never observed.
 */
public record CoverageStatement(String dimension,
                                int observed,
                                int total,
                                int unobservable,
                                List<String> gapIds,
                                String note) {

    public CoverageStatement {
        gapIds = gapIds == null ? List.of() : List.copyOf(gapIds);
    }

    public static CoverageStatement of(String dimension, int observed, int total) {
        return new CoverageStatement(dimension, observed, total, Math.max(0, total - observed),
                List.of(), null);
    }

    public static CoverageStatement none(String dimension, String reason) {
        return new CoverageStatement(dimension, 0, 0, 0, List.of(), reason);
    }

    public double ratio() {
        return total == 0 ? 0.0 : (double) observed / total;
    }

    public boolean complete() {
        return total > 0 && observed == total;
    }

    /** Human-facing rendering, e.g. "41/44 protected endpoints observed, 3 unobservable, GAP-021". */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(observed).append('/').append(total).append(' ').append(dimension).append(" observed");
        if (unobservable > 0) {
            sb.append(", ").append(unobservable).append(" unobservable");
        }
        if (!gapIds.isEmpty()) {
            sb.append(", ").append(String.join(", ", gapIds));
        }
        if (note != null && !note.isBlank()) {
            sb.append(" (").append(note).append(')');
        }
        return sb.toString();
    }

    public CoverageStatement withGaps(List<String> gaps) {
        List<String> merged = new ArrayList<>(gapIds);
        merged.addAll(gaps);
        return new CoverageStatement(dimension, observed, total, unobservable, merged, note);
    }
}
