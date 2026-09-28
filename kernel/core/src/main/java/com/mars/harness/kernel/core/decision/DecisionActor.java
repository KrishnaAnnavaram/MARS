package com.mars.harness.kernel.core.decision;

/**
 * Who records a decision, and how that identity was established.
 *
 * <p>{@code authentication} is recorded verbatim in the decision and covered by its integrity
 * hash. It is a statement about how the <em>entry point</em> established the actor, never a
 * signature. The vocabulary is closed:
 *
 * <ul>
 *   <li>{@link #LOCALLY_ASSERTED}: the actor typed their own name (the CLI). Nothing verified it.</li>
 *   <li>{@link #DEVELOPMENT_ASSERTED}: a development-only identity provider (for example the
 *       Control Center's dev login). Not authentication; never acceptable in production.</li>
 *   <li>{@code OIDC_AUTHENTICATED:<issuer>}: the entry point validated a token from that issuer.</li>
 * </ul>
 *
 * @param authentication null means {@link #LOCALLY_ASSERTED}
 */
public record DecisionActor(String name, String role, String authentication) {

    public static final String LOCALLY_ASSERTED = "LOCALLY_ASSERTED";
    public static final String DEVELOPMENT_ASSERTED = "DEVELOPMENT_ASSERTED";
    public static final String OIDC_PREFIX = "OIDC_AUTHENTICATED:";

    /** The CLI's actor: a name and role the person asserted themselves. */
    public static DecisionActor asserted(String name, String role) {
        return new DecisionActor(name, role, null);
    }

    public static boolean validAuthentication(String value) {
        return LOCALLY_ASSERTED.equals(value) || DEVELOPMENT_ASSERTED.equals(value)
                || value != null && value.startsWith(OIDC_PREFIX) && value.length() > OIDC_PREFIX.length()
                && !value.substring(OIDC_PREFIX.length()).isBlank();
    }
}
