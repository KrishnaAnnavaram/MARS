package com.mars.harness.kernel.ports.lifecycle;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Platform support-lifecycle facts with explicit evidence quality. Backed by Bootshift's curated
 * {@code LifecycleSource}: a VERIFIED fact may eliminate an option, while ADVISORY and ESTIMATED
 * facts may only flag one.
 */
public interface LifecyclePort {

    record LineFacts(String framework, String line, String latestPatch, LocalDate generalAvailability,
                     LocalDate supportEnds, List<Integer> supportedJavaMajors, String quality, String source,
                     String asOf, boolean tableStale) {

        public boolean endOfLife(LocalDate today) {
            return supportEnds != null && supportEnds.isBefore(today);
        }

        public Long horizonMonths(LocalDate today) {
            return supportEnds == null ? null : java.time.temporal.ChronoUnit.MONTHS.between(today, supportEnds);
        }
    }

    Optional<LineFacts> line(String framework, String line, LocalDate today);

    /** Every line with known facts, oldest first. */
    List<LineFacts> lines(String framework, LocalDate today);
}
