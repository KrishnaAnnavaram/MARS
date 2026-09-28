package com.mars.harness.controlcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.harness.controlcenter.api.dto.ApiError;
import com.mars.harness.controlcenter.config.ControlCenterProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Control Center's authentication and authorization boundary. Authorization is enforced
 * here, on the server; the browser only mirrors it to hide controls a user cannot use.
 *
 * <ul>
 *   <li><b>dev</b> mode: in-memory development users, a session cookie and CSRF protection.
 *       Decisions record {@code DEVELOPMENT_ASSERTED}. For local use only.</li>
 *   <li><b>oidc</b> mode: stateless JWT bearer tokens from a standards-based identity provider
 *       (Entra ID, Okta, Keycloak, …) configured with
 *       {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. Roles come from a claim and are
 *       mapped to MARS roles; decisions record {@code OIDC_AUTHENTICATED:<issuer>}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(MarsRole.hierarchy());
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain controlCenter(HttpSecurity http, ControlCenterProperties properties, ObjectMapper mapper,
                                             SecurityContextRepository contexts) throws Exception {
        ControlCenterProperties.Auth auth = properties.auth();
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/session", "/api/v1/session/dev-users").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/session/login", "/api/v1/session/logout").permitAll()
                .requestMatchers("/actuator/**").hasRole(MarsRole.ADMIN.name())
                .requestMatchers(HttpMethod.POST, "/api/v1/runs").hasRole(MarsRole.OPERATOR.name())
                // resuming authorizes nothing (it advances by the recorded decisions), so approvers may continue too
                .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/resume")
                .hasAnyRole(MarsRole.OPERATOR.name(), MarsRole.APPROVER.name())
                .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/decisions/**", "/api/v1/runs/*/proposals/*/decision")
                .hasRole(MarsRole.APPROVER.name())
                .requestMatchers("/api/**", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .hasRole(MarsRole.VIEWER.name())
                // the single-page application's static assets carry no data
                .anyRequest().permitAll());
        http.exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) -> error(mapper, response, 401, "UNAUTHORIZED",
                        "Sign in to continue"))
                .accessDeniedHandler((request, response, ex) -> error(mapper, response, 403, "FORBIDDEN",
                        "Your role does not permit this action")));
        http.httpBasic(b -> b.disable()).formLogin(f -> f.disable()).logout(l -> l.disable());
        http.headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; "
                        + "connect-src 'self'; worker-src 'self' blob:; frame-ancestors 'none'; object-src 'none'; "
                        + "base-uri 'self'; form-action 'self'"))
                .frameOptions(f -> f.deny()));
        if (auth.oidc()) {
            http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtRoles(auth))));
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            // bearer tokens are not sent automatically by browsers, so there is no ambient credential to forge
            http.csrf(c -> c.disable());
            LOG.info("Control Center authentication: OIDC bearer tokens (roles claim '{}')", auth.rolesClaim());
        } else if (auth.dev()) {
            http.securityContext(s -> s.securityContextRepository(contexts));
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
            http.csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()));
            LOG.warn("Control Center authentication: DEVELOPMENT identities. They are not authentication; decisions record "
                    + "DEVELOPMENT_ASSERTED. Never expose this mode beyond localhost.");
        } else {
            throw new IllegalStateException("mars.control-center.auth.mode must be 'dev' or 'oidc', not '" + auth.mode() + "'");
        }
        return http.build();
    }

    /** Development users from configuration. Absent in OIDC mode. */
    @Bean
    public UserDetailsService developmentUsers(ControlCenterProperties properties) {
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
        if (!properties.auth().dev()) {
            return users;
        }
        properties.auth().devUsers().forEach((name, user) -> {
            List<String> roles = user.roles().stream().map(r -> MarsRole.parse(r).orElseThrow(() ->
                    new IllegalStateException("Unknown MARS role '" + r + "' for dev user " + name)).name()).toList();
            users.createUser(User.withUsername(name).password("{noop}" + user.password()).roles(roles.toArray(String[]::new))
                    .build());
        });
        if (properties.auth().devUsers().isEmpty()) {
            LOG.warn("Dev mode with no mars.control-center.auth.dev-users: nobody can sign in");
        }
        return users;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(encoder);
        provider.setUserDetailsService(users);
        return new ProviderManager(provider);
    }

    static Converter<Jwt, AbstractAuthenticationToken> jwtRoles(ControlCenterProperties.Auth auth) {
        return jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            Object claim = jwt.getClaims().get(auth.rolesClaim());
            List<String> values = new ArrayList<>();
            if (claim instanceof Collection<?> c) {
                c.forEach(v -> values.add(String.valueOf(v)));
            } else if (claim instanceof String s) {
                for (String part : s.split("[ ,]")) {
                    if (!part.isBlank()) {
                        values.add(part);
                    }
                }
            }
            for (String value : values) {
                String mapped = auth.roleMapping().getOrDefault(value, value);
                MarsRole.parse(mapped).ifPresent(r -> authorities.add(new SimpleGrantedAuthority(r.authority())));
            }
            String name = jwt.getClaimAsString(auth.nameClaim());
            return new JwtAuthenticationToken(jwt, authorities, name == null || name.isBlank() ? jwt.getSubject() : name);
        };
    }

    private static void error(ObjectMapper mapper, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message, null, MDC.get("correlation_id"), Map.of()));
    }

    /** CSRF for a single-page app: the token is read from the XSRF-TOKEN cookie and sent back as a header. */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
            csrfToken.get(); // load the deferred token so the cookie is written on every response
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(header) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
