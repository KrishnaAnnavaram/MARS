package com.mars.harness.kernel.core.identity;

import java.util.ArrayList;
import java.util.List;

/**
 * What one reattachment pass did, entity by entity. Published as evidence after every mutation
 * batch.
 */
public final class ReattachmentReport {

    /** One identity decision. {@code identityId} is the attached or newly allocated ID. */
    public record Entry(String level, String identityId, String outcome, ReattachmentMethod method,
                        double confidence, Certainty certainty, String evidence, Location oldLocation,
                        Location newLocation, List<String> candidates) {
    }

    public String changeId;
    public List<Entry> entries = new ArrayList<>();
    public List<String> ignoredHints = new ArrayList<>();

    public void add(Entry entry) {
        entries.add(entry);
    }

    public long count(String level, String outcome) {
        return entries.stream().filter(e -> e.level().equals(level) && e.outcome().equals(outcome)).count();
    }

    public List<Entry> uncertain() {
        return entries.stream().filter(e -> e.certainty() == Certainty.LOW).toList();
    }

    public List<Entry> forLevel(String level) {
        return entries.stream().filter(e -> e.level().equals(level)).toList();
    }

    /** Outcomes recorded in entries. */
    public static final String REATTACHED = "REATTACHED";
    public static final String ALLOCATED = "ALLOCATED";
    public static final String DELETED = "DELETED";
    public static final String MERGED_AWAY = "MERGED_AWAY";
    public static final String UNCHANGED = "UNCHANGED";
}
