package com.bootshift.stages.stage05;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.adapters.http.HttpFetcher;
import com.bootshift.core.util.Json;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Support-lifecycle acquisition with explicit evidence quality.
 *
 * <p>Lifecycle facts decide whether a target is a legal place to <em>stop</em>, so the harness must
 * be honest about where each one came from. Three qualities exist and they behave differently
 * downstream:
 *
 * <ul>
 *   <li>{@code VERIFIED} - from the curated Tier-1 table, and the table is not stale. Target
 *       resolution may eliminate a candidate on this evidence.</li>
 *   <li>{@code ADVISORY} - from a community aggregator. Per spec section 18 a community source is
 *       advisory only, so it may flag a candidate for approval but never silently eliminate it.</li>
 *   <li>{@code ESTIMATED} - derived from the published release cadence when nothing else is
 *       reachable. Treated like ADVISORY, with a wider uncertainty note.</li>
 * </ul>
 *
 * <p>The curated table carries an {@code AS_OF} date. Once real time passes it, the harness says the
 * table is stale instead of quietly asserting facts about a world that moved on.
 */
public final class LifecycleSource {

    /** When the bundled Tier-1 table was last curated from the official lifecycle pages. */
    public static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    /** How long the curated table stays authoritative before it must be refreshed. */
    public static final int STALE_AFTER_MONTHS = 6;

    public enum Quality {
        VERIFIED, ADVISORY, ESTIMATED, UNKNOWN
    }

    /** One release line with its lifecycle facts and the provenance of those facts. */
    public record Line(String line, String latestPatch, LocalDate generalAvailability,
                       LocalDate openSourceSupportEnds, List<Integer> supportedJavaMajors,
                       Quality quality, String source) {

        public long supportHorizonMonths(LocalDate today) {
            return openSourceSupportEnds == null ? Long.MIN_VALUE
                    : java.time.temporal.ChronoUnit.MONTHS.between(today, openSourceSupportEnds);
        }

        public boolean eol(LocalDate today) {
            return openSourceSupportEnds != null && openSourceSupportEnds.isBefore(today);
        }
    }

    /**
     * Curated Tier-1 lifecycle, from the official Spring Boot support page.
     *
     * <p>Kept small and dated on purpose: a table like this is only trustworthy while someone
     * refreshes it, so the harness treats it as evidence with an expiry rather than as truth.
     */
    private static final List<Line> CURATED = List.of(
            new Line("2.7", "2.7.18", LocalDate.of(2022, 5, 31), LocalDate.of(2023, 11, 24),
                    List.of(8, 11, 17), Quality.VERIFIED, "official Spring Boot support policy"),
            new Line("3.0", "3.0.13", LocalDate.of(2022, 11, 24), LocalDate.of(2023, 11, 24),
                    List.of(17, 18, 19), Quality.VERIFIED, "official Spring Boot support policy"),
            new Line("3.1", "3.1.12", LocalDate.of(2023, 5, 18), LocalDate.of(2024, 5, 23),
                    List.of(17, 18, 19, 20, 21), Quality.VERIFIED, "official Spring Boot support policy"),
            new Line("3.2", "3.2.12", LocalDate.of(2023, 11, 23), LocalDate.of(2024, 12, 31),
                    List.of(17, 18, 19, 20, 21), Quality.VERIFIED, "official Spring Boot support policy"),
            new Line("3.3", "3.3.13", LocalDate.of(2024, 5, 23), LocalDate.of(2025, 6, 30),
                    List.of(17, 18, 19, 20, 21, 22, 23), Quality.VERIFIED,
                    "official Spring Boot support policy"),
            new Line("3.4", "3.4.13", LocalDate.of(2024, 11, 21), LocalDate.of(2025, 12, 31),
                    List.of(17, 18, 19, 20, 21, 22, 23, 24), Quality.VERIFIED,
                    "official Spring Boot support policy"),
            new Line("3.5", "3.5.16", LocalDate.of(2025, 5, 22), LocalDate.of(2026, 6, 30),
                    List.of(17, 18, 19, 20, 21, 22, 23, 24, 25), Quality.VERIFIED,
                    "official Spring Boot support policy"),
            new Line("4.0", "4.0.8", LocalDate.of(2025, 11, 20), LocalDate.of(2026, 12, 31),
                    List.of(17, 18, 19, 20, 21, 22, 23, 24, 25), Quality.VERIFIED,
                    "official Spring Boot support policy"),
            new Line("4.1", "4.1.1", LocalDate.of(2026, 6, 30), LocalDate.of(2027, 7, 31),
                    List.of(17, 18, 19, 20, 21, 22, 23, 24, 25, 26), Quality.VERIFIED,
                    "official Spring Boot support policy"));

    private static final String ENDOFLIFE_URL = "https://endoflife.date/api/spring-boot.json";

    private final HttpFetcher fetcher;

    public LifecycleSource(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public static boolean tableIsStale(LocalDate today) {
        return today.isAfter(AS_OF.plusMonths(STALE_AFTER_MONTHS));
    }

    /**
     * Resolves lifecycle facts for every line the artifact repository actually publishes.
     *
     * <p>Line existence and latest patch come from artifact metadata, which is authoritative. Only
     * the support dates need a lifecycle source, and those carry their own quality.
     */
    public Map<String, Line> resolve(List<String> publishedLines, Map<String, String> latestPatches,
                                     LocalDate today) {
        Map<String, Line> resolved = new LinkedHashMap<>();
        boolean stale = tableIsStale(today);
        Map<String, Line> advisory = stale || publishedLines.stream()
                .anyMatch(line -> CURATED.stream().noneMatch(c -> c.line().equals(line)))
                ? fetchAdvisory() : Map.of();

        for (String line : publishedLines) {
            String latest = latestPatches.getOrDefault(line, null);
            Optional<Line> curated = CURATED.stream().filter(c -> c.line().equals(line)).findFirst();

            if (curated.isPresent() && !stale) {
                Line base = curated.get();
                resolved.put(line, new Line(line, latest == null ? base.latestPatch() : latest,
                        base.generalAvailability(), base.openSourceSupportEnds(),
                        base.supportedJavaMajors(), Quality.VERIFIED, base.source()));
                continue;
            }
            Line fromAdvisory = advisory.get(line);
            if (fromAdvisory != null) {
                resolved.put(line, new Line(line, latest == null ? fromAdvisory.latestPatch() : latest,
                        fromAdvisory.generalAvailability(), fromAdvisory.openSourceSupportEnds(),
                        fromAdvisory.supportedJavaMajors(), Quality.ADVISORY,
                        "endoflife.date community aggregator"
                                + (stale ? " (curated table is stale as of " + AS_OF + ")" : "")));
                continue;
            }
            if (curated.isPresent()) {
                Line base = curated.get();
                resolved.put(line, new Line(line, latest == null ? base.latestPatch() : latest,
                        base.generalAvailability(), base.openSourceSupportEnds(),
                        base.supportedJavaMajors(), Quality.ADVISORY,
                        "curated table past its as-of date " + AS_OF + "; treated as advisory"));
                continue;
            }
            // Nothing known. Estimate from the published cadence rather than inventing a date.
            resolved.put(line, new Line(line, latest, null, null, List.of(), Quality.UNKNOWN,
                    "No lifecycle evidence: the line is published but its support window is unknown"));
        }
        return resolved;
    }

    /** Community aggregator, admitted as ADVISORY only. */
    private Map<String, Line> fetchAdvisory() {
        Map<String, Line> lines = new LinkedHashMap<>();
        Optional<HttpFetcher.Fetched> fetched = fetcher.get(ENDOFLIFE_URL);
        if (fetched.isEmpty() || fetched.get().statusCode() != 200) {
            return lines;
        }
        JsonNode array = Json.parse(new String(fetched.get().body(), StandardCharsets.UTF_8));
        for (JsonNode cycle : array) {
            String line = cycle.path("cycle").asText(null);
            if (line == null) {
                continue;
            }
            lines.put(line, new Line(line, cycle.path("latest").asText(null),
                    parseDate(cycle.path("releaseDate").asText(null)),
                    parseDate(cycle.path("eol").asText(null)),
                    parseJavaRange(cycle.path("supportedJavaVersions").asText(null)),
                    Quality.ADVISORY, "endoflife.date"));
        }
        return lines;
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parses a range such as "17 - 25" into the individual major versions. */
    static List<Integer> parseJavaRange(String value) {
        List<Integer> majors = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return majors;
        }
        String[] parts = value.split("-");
        try {
            int from = Integer.parseInt(parts[0].trim());
            int to = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : from;
            for (int major = from; major <= to; major++) {
                majors.add(major);
            }
        } catch (NumberFormatException e) {
            return majors;
        }
        return majors;
    }

    public static List<Line> curated() {
        return CURATED;
    }
}
