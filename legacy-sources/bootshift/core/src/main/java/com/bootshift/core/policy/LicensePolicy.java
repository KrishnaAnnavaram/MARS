package com.bootshift.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Machine-enforced strict-OSS license gate (spec section 46, R8).
 *
 * <p>The default posture is deny: a component whose license the harness cannot positively identify
 * is {@code UNKNOWN}, and UNKNOWN is blocked, not waved through.
 */
public final class LicensePolicy {

    public enum Verdict {
        ALLOWED, BLOCKED, UNKNOWN
    }

    /** One evaluated component. */
    public record Finding(String component, String version, String declaredLicense, Verdict verdict,
                          String reason, String evidenceRef) {
    }

    private static final Set<String> DEFAULT_ALLOWLIST = new LinkedHashSet<>(List.of(
            "APACHE-2.0", "APACHE 2.0", "THE APACHE SOFTWARE LICENSE, VERSION 2.0",
            "MIT", "THE MIT LICENSE", "MIT LICENSE",
            "BSD-2-CLAUSE", "BSD-3-CLAUSE", "BSD LICENSE", "NEW BSD LICENSE",
            "EPL-2.0", "ECLIPSE PUBLIC LICENSE - V 2.0", "ECLIPSE PUBLIC LICENSE V2.0",
            "EDL-1.0", "ECLIPSE DISTRIBUTION LICENSE - V 1.0", "ECLIPSE DISTRIBUTION LICENSE (NEW BSD LICENSE)",
            "CDDL-1.1", "GPL-2.0-WITH-CLASSPATH-EXCEPTION",
            "PUBLIC DOMAIN", "CC0-1.0", "UNLICENSE"));

    private static final Set<String> DEFAULT_DENYLIST = new LinkedHashSet<>(List.of(
            "PROPRIETARY", "SOURCE-AVAILABLE", "MSAL", "MODERNE SOURCE AVAILABLE LICENSE",
            "COMMERCIAL", "COMMERCIAL-ONLY", "BUSL-1.1", "BUSINESS SOURCE LICENSE",
            "ELASTIC LICENSE", "SSPL-1.0", "CONFLUENT COMMUNITY LICENSE"));

    /** Artifact coordinates that are forbidden regardless of the license string they declare. */
    private static final Set<String> DEFAULT_FORBIDDEN_ARTIFACTS = new LinkedHashSet<>(List.of(
            "org.openrewrite.recipe:rewrite-spring",
            "org.openrewrite.recipe:rewrite-migrate-java-spring",
            "io.moderne:moderne-recipe",
            "io.moderne.recipe:rewrite-spring"));

    /**
     * Java package prefixes that must never be loadable at runtime under the strict-OSS profile.
     *
     * <p>This is the single source of truth. The OpenRewrite provider probes against it, the
     * architecture test forbids a compile-time dependency on it, and the dependency test checks the
     * coordinates that would introduce it. Three copies of this list is how one of them silently
     * stops matching the other two.
     */
    private static final List<String> FORBIDDEN_RECIPE_PACKAGES = List.of(
            "org.openrewrite.java.spring",
            "org.openrewrite.recipe.spring",
            "io.moderne");

    /**
     * Marker classes used to detect a forbidden estate on the classpath. Probing a package name is
     * not enough - a package with no loaded class is invisible to {@code Class.forName} - so each
     * estate contributes at least one class that only it ships.
     */
    private static final List<String> FORBIDDEN_RECIPE_MARKER_CLASSES = List.of(
            "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0",
            "org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_7",
            "org.openrewrite.java.spring.NoAutowiredOnConstructor",
            "io.moderne.recipe.ModerneRecipe");

    /** Package prefixes no Bootshift component may load. Never mutated by configuration. */
    public static List<String> forbiddenRecipePackages() {
        return FORBIDDEN_RECIPE_PACKAGES;
    }

    /** Classes whose presence proves a forbidden estate is on the classpath. */
    public static List<String> forbiddenRecipeMarkerClasses() {
        return FORBIDDEN_RECIPE_MARKER_CLASSES;
    }

    /** True when the class name belongs to a forbidden recipe estate. */
    public static boolean isForbiddenRecipeClass(String className) {
        if (className == null) {
            return false;
        }
        return FORBIDDEN_RECIPE_PACKAGES.stream().anyMatch(p -> className.startsWith(p + "."));
    }

    private final Set<String> allowlist;
    private final Set<String> denylist;
    private final Set<String> forbiddenArtifacts;

    public LicensePolicy() {
        this(DEFAULT_ALLOWLIST, DEFAULT_DENYLIST, DEFAULT_FORBIDDEN_ARTIFACTS);
    }

    public LicensePolicy(Set<String> allowlist, Set<String> denylist, Set<String> forbiddenArtifacts) {
        this.allowlist = allowlist;
        this.denylist = denylist;
        this.forbiddenArtifacts = forbiddenArtifacts;
    }

    public static LicensePolicy load(Path file) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            return new LicensePolicy();
        }
        JsonNode node = Json.read(file);
        Set<String> allow = new LinkedHashSet<>();
        node.path("allowlist").forEach(n -> allow.add(n.asText().toUpperCase(Locale.ROOT)));
        Set<String> deny = new LinkedHashSet<>();
        node.path("denylist").forEach(n -> deny.add(n.asText().toUpperCase(Locale.ROOT)));
        Set<String> forbidden = new LinkedHashSet<>();
        node.path("forbidden_artifacts").forEach(n -> forbidden.add(n.asText()));
        return new LicensePolicy(
                allow.isEmpty() ? DEFAULT_ALLOWLIST : allow,
                deny.isEmpty() ? DEFAULT_DENYLIST : deny,
                forbidden.isEmpty() ? DEFAULT_FORBIDDEN_ARTIFACTS : forbidden);
    }

    public Set<String> allowlist() {
        return allowlist;
    }

    public Set<String> denylist() {
        return denylist;
    }

    public Set<String> forbiddenArtifacts() {
        return forbiddenArtifacts;
    }

    public Finding evaluate(String groupArtifact, String version, String declaredLicense, String evidenceRef) {
        if (forbiddenArtifacts.stream().anyMatch(groupArtifact::startsWith)) {
            return new Finding(groupArtifact, version, declaredLicense, Verdict.BLOCKED,
                    "Artifact is on the strict-OSS forbidden list (source-available or proprietary recipe estate)",
                    evidenceRef);
        }
        if (declaredLicense == null || declaredLicense.isBlank()) {
            return new Finding(groupArtifact, version, null, Verdict.UNKNOWN,
                    "No license metadata could be resolved; unknown is blocked until verified", evidenceRef);
        }
        String normalized = declaredLicense.trim().toUpperCase(Locale.ROOT);
        for (String denied : denylist) {
            if (normalized.contains(denied)) {
                return new Finding(groupArtifact, version, declaredLicense, Verdict.BLOCKED,
                        "License matches denylist entry " + denied, evidenceRef);
            }
        }
        for (String allowed : allowlist) {
            if (normalized.contains(allowed)) {
                return new Finding(groupArtifact, version, declaredLicense, Verdict.ALLOWED,
                        "License matches allowlist entry " + allowed, evidenceRef);
            }
        }
        return new Finding(groupArtifact, version, declaredLicense, Verdict.UNKNOWN,
                "License string not recognised by policy; requires explicit review", evidenceRef);
    }

    /** Overall gate: any BLOCKED or UNKNOWN finding fails the gate. */
    public static boolean gatePasses(List<Finding> findings) {
        return findings.stream().allMatch(f -> f.verdict() == Verdict.ALLOWED);
    }

    public static List<Finding> blocking(List<Finding> findings) {
        List<Finding> result = new ArrayList<>();
        findings.stream().filter(f -> f.verdict() != Verdict.ALLOWED).forEach(result::add);
        return result;
    }
}
