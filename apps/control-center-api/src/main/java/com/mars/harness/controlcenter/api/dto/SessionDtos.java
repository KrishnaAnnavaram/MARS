package com.mars.harness.controlcenter.api.dto;

import java.util.List;

/** The caller's identity, as the authentication boundary established it. */
public final class SessionDtos {

    private SessionDtos() {
    }

    /**
     * @param authMode       dev or oidc
     * @param authentication the value decisions will record as {@code actor_authentication}
     * @param warning        set in dev mode: development identities are not authentication
     */
    public record SessionView(boolean authenticated, String username, String displayName, List<String> roles,
                              String authMode, String authentication, String warning, List<String> permissions) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record DevUserOption(String username, String displayName, List<String> roles) {
    }
}
