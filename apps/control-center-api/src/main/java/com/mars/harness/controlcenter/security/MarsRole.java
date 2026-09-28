package com.mars.harness.controlcenter.security;

import java.util.Locale;
import java.util.Optional;

/**
 * Control Center roles, enforced server-side.
 *
 * <ul>
 *   <li>VIEWER: read runs, events, evidence</li>
 *   <li>OPERATOR: VIEWER + start runs and resume them (no decisions)</li>
 *   <li>APPROVER: VIEWER + record human decisions (Gate A, A2, B, plan approval)</li>
 *   <li>ADMIN: everything</li>
 * </ul>
 *
 * <p>Operating and approving are deliberately separate: starting or resuming a run authorizes
 * nothing, and approving does not require the right to start runs.
 */
public enum MarsRole {
    VIEWER, OPERATOR, APPROVER, ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<MarsRole> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.startsWith("ROLE_")) {
            v = v.substring(5);
        }
        for (MarsRole role : values()) {
            if (role.name().equals(v)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    /** The role hierarchy, in Spring Security's notation. */
    public static String hierarchy() {
        return "ROLE_ADMIN > ROLE_APPROVER\nROLE_ADMIN > ROLE_OPERATOR\nROLE_APPROVER > ROLE_VIEWER\nROLE_OPERATOR > ROLE_VIEWER";
    }
}
