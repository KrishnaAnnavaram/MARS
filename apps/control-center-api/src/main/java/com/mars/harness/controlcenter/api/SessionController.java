package com.mars.harness.controlcenter.api;

import com.mars.harness.controlcenter.api.dto.SessionDtos;
import com.mars.harness.controlcenter.config.ControlCenterProperties;
import com.mars.harness.controlcenter.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Who the caller is. In dev mode it also signs development users in and out; in OIDC mode the
 * identity provider does that and this endpoint only reports the token's identity.
 */
@RestController
@RequestMapping("/api/v1/session")
@Tag(name = "Session", description = "The authentication boundary as the browser sees it")
public class SessionController {

    private static final Logger LOG = LoggerFactory.getLogger(SessionController.class);
    private static final String DEV_WARNING = "Development identity: not authentication. Decisions record "
            + "DEVELOPMENT_ASSERTED. Use OIDC mode for anything shared.";

    private final ControlCenterProperties properties;
    private final CurrentActor actor;
    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;

    public SessionController(ControlCenterProperties properties, CurrentActor actor, AuthenticationManager authentication,
                             SecurityContextRepository contexts) {
        this.properties = properties;
        this.actor = actor;
        this.authentication = authentication;
        this.contexts = contexts;
    }

    @GetMapping
    @Operation(summary = "The current session")
    public SessionDtos.SessionView session() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        String mode = properties.auth().mode();
        if (!signedIn) {
            return new SessionDtos.SessionView(false, null, null, List.of(), mode, null,
                    properties.auth().dev() ? DEV_WARNING : null, List.of());
        }
        List<String> permissions = new ArrayList<>(List.of("read"));
        if (actor.mayOperate()) {
            permissions.add("operate");
        }
        if (actor.mayResume()) {
            permissions.add("resume");
        }
        if (actor.mayDecide()) {
            permissions.add("decide");
        }
        String display = properties.auth().dev() && properties.auth().devUsers().containsKey(auth.getName())
                ? properties.auth().devUsers().get(auth.getName()).displayName() : actor.username();
        return new SessionDtos.SessionView(true, actor.username(), display, actor.roleNames(), mode,
                actor.authenticationStatement(), properties.auth().dev() ? DEV_WARNING : null, permissions);
    }

    @GetMapping("/dev-users")
    @Operation(summary = "Dev mode only: the configured development users (never their passwords)")
    public List<SessionDtos.DevUserOption> devUsers() {
        if (!properties.auth().dev()) {
            return List.of();
        }
        return properties.auth().devUsers().entrySet().stream().map(e -> new SessionDtos.DevUserOption(e.getKey(),
                e.getValue().displayName(), e.getValue().roles())).toList();
    }

    @PostMapping("/login")
    @Operation(summary = "Dev mode only: sign a development user in")
    public ResponseEntity<SessionDtos.SessionView> login(@RequestBody SessionDtos.LoginRequest request,
                                                         HttpServletRequest http, HttpServletResponse response) {
        if (!properties.auth().dev()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, null, "Sign in through the identity provider (OIDC mode)");
        }
        Authentication result;
        try {
            result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.username(),
                    request.password()));
        } catch (AuthenticationException e) {
            throw new ApiException(ApiErrorCode.UNAUTHORIZED, null, "Unknown user or wrong password");
        }
        http.getSession(true);
        http.changeSessionId(); // no session fixation
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, http, response);
        LOG.info("Development user {} signed in (DEVELOPMENT_ASSERTED)", result.getName());
        return ResponseEntity.ok(session());
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out")
    public SessionDtos.SessionView logout(HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return session();
    }
}
