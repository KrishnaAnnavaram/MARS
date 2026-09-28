package com.mars.harness.controlcenter.security;

import com.mars.harness.controlcenter.api.ApiErrorCode;
import com.mars.harness.controlcenter.api.ApiException;
import com.mars.harness.controlcenter.config.ControlCenterProperties;
import com.mars.harness.kernel.core.decision.DecisionActor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the authenticated principal into the actor a decision records.
 *
 * <p>The actor is <em>never</em> taken from a request body. Its name comes from the
 * authentication, its role from the granted MARS role, and its {@code actor_authentication} says
 * truthfully how it was established: {@code DEVELOPMENT_ASSERTED} for dev users,
 * {@code OIDC_AUTHENTICATED:<issuer>} for a validated token.
 */
@Component
public class CurrentActor {

    private final ControlCenterProperties properties;

    public CurrentActor(ControlCenterProperties properties) {
        this.properties = properties;
    }

    public Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new ApiException(ApiErrorCode.UNAUTHORIZED, null, "Sign in to continue");
        }
        return auth;
    }

    public String username() {
        Authentication auth = authentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString(properties.auth().nameClaim());
            return claim == null || claim.isBlank() ? jwt.getToken().getSubject() : claim;
        }
        return auth.getName();
    }

    public Set<MarsRole> roles() {
        Set<MarsRole> roles = EnumSet.noneOf(MarsRole.class);
        for (GrantedAuthority a : authentication().getAuthorities()) {
            MarsRole.parse(a.getAuthority()).ifPresent(roles::add);
        }
        return roles;
    }

    public boolean mayDecide() {
        Set<MarsRole> roles = safeRoles();
        return roles.contains(MarsRole.APPROVER) || roles.contains(MarsRole.ADMIN);
    }

    public boolean mayOperate() {
        Set<MarsRole> roles = safeRoles();
        return roles.contains(MarsRole.OPERATOR) || roles.contains(MarsRole.ADMIN);
    }

    private Set<MarsRole> safeRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            return Set.of();
        }
        return roles();
    }

    /** How this caller's identity was established, in the decision vocabulary. */
    public String authenticationStatement() {
        Authentication auth = authentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Jwt token = jwt.getToken();
            String issuer = token.getIssuer() == null ? "unknown-issuer" : token.getIssuer().toString();
            return DecisionActor.OIDC_PREFIX + issuer;
        }
        return DecisionActor.DEVELOPMENT_ASSERTED;
    }

    /** The actor for a decision: authenticated name, the MARS role that permits deciding, honest authentication. */
    public DecisionActor decisionActor() {
        Set<MarsRole> roles = roles();
        String role = roles.contains(MarsRole.APPROVER) ? MarsRole.APPROVER.name()
                : roles.contains(MarsRole.ADMIN) ? MarsRole.ADMIN.name() : null;
        if (role == null) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, null, "Recording a decision requires the APPROVER or ADMIN role");
        }
        return new DecisionActor(username(), role, authenticationStatement());
    }

    public List<String> roleNames() {
        return safeRoles().stream().map(Enum::name).sorted().toList();
    }
}
