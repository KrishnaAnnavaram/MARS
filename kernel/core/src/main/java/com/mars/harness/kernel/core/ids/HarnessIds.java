package com.mars.harness.kernel.core.ids;

import com.bootshift.core.util.Ids;

import java.util.regex.Pattern;

/**
 * Allocation of every unified identifier.
 *
 * <p>Identity is allocated, never derived (Bootshift ADR-001, extended by ADR-U002). Every ID is a
 * typed prefix plus a ULID from Bootshift's allocator. {@code FILE_ID} stays owned by Bootshift's
 * {@code FileRegistry} and {@code CHANGE_ID} by its {@code ChangeLedger}. Neither is re-implemented
 * here.
 */
public final class HarnessIds {

    public enum Kind {
        RUN("RUN"),
        REPOSITORY("REPO"),
        MODULE("MOD"),
        FILE("FILE"),
        PROGRAM_UNIT("PU"),
        SYMBOL("SYM"),
        STATEMENT("STMT"),
        FINDING("FINDING"),
        CHANGE("CHANGE"),
        PROPOSAL("PROP"),
        DECISION("DEC"),
        EVIDENCE("EVID"),
        EVENT("EVT"),
        CHECKPOINT("CKPT"),
        VALIDATION("VAL"),
        PLAN("PLAN"),
        ASSESSMENT("ASSESS");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    private static final Pattern ULID_ID = Pattern.compile("^[A-Z]+-[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern CHANGE_ID = Pattern.compile("^CHANGE-\\d{6}$");

    private HarnessIds() {
    }

    public static String allocate(Kind kind) {
        return switch (kind) {
            case RUN -> Ids.runId();
            case FILE -> Ids.fileId();
            case CHANGE -> throw new IllegalArgumentException(
                    "CHANGE_ID is allocated by the change ledger, never directly");
            default -> kind.prefix() + "-" + Ids.ulid();
        };
    }

    /** The kind of an identifier from its prefix, or null when it is not a harness identifier. */
    public static Kind kindOf(String id) {
        if (id == null) {
            return null;
        }
        if (CHANGE_ID.matcher(id).matches()) {
            return Kind.CHANGE;
        }
        if (!ULID_ID.matcher(id).matches()) {
            return null;
        }
        String prefix = id.substring(0, id.indexOf('-'));
        for (Kind kind : Kind.values()) {
            if (kind.prefix().equals(prefix)) {
                return kind;
            }
        }
        return null;
    }

    public static boolean isKind(String id, Kind kind) {
        return kindOf(id) == kind;
    }
}
