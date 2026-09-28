package com.mars.harness.controlcenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code mars.control-center.*}.
 *
 * @param harnessRoot     the MARS installation (policies, legacy sources); auto-detected like the CLI when unset
 * @param runsRoot        where run artifact planes live; defaults to {@code <harnessRoot>/runs}, as the CLI
 * @param repositoryRoots directories under which a run's repository and inputs may be chosen. A path
 *                        outside every root is refused: the browser never names arbitrary server paths.
 * @param maxConcurrentRuns how many runs may advance at once (each may run builds)
 * @param mavenOffline    run Maven with {@code -o}, as the CLI's {@code --maven-offline}
 * @param network         allow Bootshift's allow-listed egress, as the CLI's {@code --network}
 * @param today           fixed evaluation date for lifecycle facts; defaults to the current date
 * @param uiDir           optional directory holding a built UI to serve (instead of classpath {@code static/})
 * @param auth            the authentication boundary
 */
@ConfigurationProperties(prefix = "mars.control-center")
public record ControlCenterProperties(Path harnessRoot, Path runsRoot, List<Path> repositoryRoots, Integer maxConcurrentRuns,
                                      boolean mavenOffline, boolean network, LocalDate today, Path uiDir, Auth auth) {

    public ControlCenterProperties {
        repositoryRoots = repositoryRoots == null ? List.of() : List.copyOf(repositoryRoots);
        maxConcurrentRuns = maxConcurrentRuns == null || maxConcurrentRuns < 1 ? 2 : maxConcurrentRuns;
        auth = auth == null ? new Auth(null, null, null, null, null) : auth;
    }

    /**
     * @param mode       {@code dev} (in-memory development users; DEVELOPMENT_ASSERTED decisions) or {@code oidc}
     *                   (JWT bearer tokens from a standards-based identity provider)
     * @param devUsers   dev mode only: username to user
     * @param rolesClaim oidc mode: the claim holding the user's groups or roles
     * @param roleMapping oidc mode: claim value to MARS role (VIEWER, OPERATOR, APPROVER, ADMIN)
     * @param nameClaim  oidc mode: the claim naming the human recorded as a decision's actor
     */
    public record Auth(String mode, Map<String, DevUser> devUsers, String rolesClaim, Map<String, String> roleMapping,
                       String nameClaim) {
        public Auth {
            mode = mode == null || mode.isBlank() ? "dev" : mode.trim().toLowerCase(java.util.Locale.ROOT);
            devUsers = devUsers == null ? Map.of() : Map.copyOf(devUsers);
            rolesClaim = rolesClaim == null || rolesClaim.isBlank() ? "roles" : rolesClaim;
            roleMapping = roleMapping == null ? Map.of() : Map.copyOf(roleMapping);
            nameClaim = nameClaim == null || nameClaim.isBlank() ? "preferred_username" : nameClaim;
        }

        public boolean dev() {
            return "dev".equals(mode);
        }

        public boolean oidc() {
            return "oidc".equals(mode);
        }
    }

    /** A development-only user. The password is compared as configured; never use dev mode in production. */
    public record DevUser(String password, String displayName, List<String> roles) {
        public DevUser {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
