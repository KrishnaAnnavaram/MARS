package com.bootshift.adapters.transform;

import com.bootshift.ports.transformation.TransformationPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harness-owned Maven descriptor transformer. Bootshift itself is MIT; that classification is kept
 * separate from OpenRewrite's Apache-2.0 and from the application's own dependency licenses.
 *
 * <p>Deliberately narrow: parent version changes, property changes, managed BOM version changes and
 * explicit dependency coordinate changes. These are the transformations that are cheap to implement,
 * trivial to verify and stable across Spring Boot generations, which is exactly where the strict-OSS
 * strategy says deterministic automation belongs (spec section 23).
 *
 * <p>Edits are surgical text replacements inside the located element so comments, formatting and
 * ordering survive; a whole-document DOM rewrite would produce a reformatting diff that buries the
 * real change.
 */
public final class MavenPomTransformer implements TransformationPort {

    public static final String PROVIDER = "BOOTSHIFT_MAVEN_POM";

    public static final String RECIPE_PARENT_VERSION = "maven.parent-version";
    public static final String RECIPE_PROPERTY = "maven.property";
    public static final String RECIPE_MANAGED_VERSION = "maven.managed-version";
    public static final String RECIPE_DEPENDENCY_COORDINATE = "maven.dependency-coordinate";
    public static final String RECIPE_ADD_DEPENDENCY = "maven.add-dependency";
    public static final String RECIPE_REMOVE_DEPENDENCY = "maven.remove-dependency";

    private static final List<String> RECIPES = List.of(
            RECIPE_PARENT_VERSION, RECIPE_PROPERTY, RECIPE_MANAGED_VERSION,
            RECIPE_DEPENDENCY_COORDINATE, RECIPE_ADD_DEPENDENCY, RECIPE_REMOVE_DEPENDENCY);

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public List<Capability> capabilities(String sourceVersion, String targetVersion) {
        List<Capability> capabilities = new ArrayList<>();
        capabilities.add(new Capability(
                "CAP-MAVEN-PARENT-VERSION", PROVIDER, "bootshift-maven-pom-transformer", "1.0.0",
                "MIT", "harness-owned", "*", "*",
                List.of("MANAGED_VERSION_CHANGED", "BASELINE_REQUIREMENT", "COMPATIBILITY_REQUIREMENT"),
                List.of(),
                true, true, "SINGLE_EDGE", 1.0, "AVAILABLE",
                "Changes the spring-boot-starter-parent version and related properties."));
        capabilities.add(new Capability(
                "CAP-MAVEN-DEPENDENCY", PROVIDER, "bootshift-maven-pom-transformer", "1.0.0",
                "MIT", "harness-owned", "*", "*",
                List.of("ARTIFACT_RELOCATED", "ARTIFACT_REMOVED"),
                List.of(),
                true, true, "SINGLE_EDGE", 1.0, "AVAILABLE",
                "Relocates, adds or removes explicit dependency coordinates."));
        return capabilities;
    }

    @Override
    public boolean handles(String recipeId) {
        return RECIPES.contains(recipeId);
    }

    @Override
    public java.util.Optional<Capability> capabilityFor(String recipeId, String sourceVersion,
                                                        String targetVersion) {
        if (!handles(recipeId)) {
            return java.util.Optional.empty();
        }
        String capabilityId = switch (recipeId) {
            case RECIPE_PARENT_VERSION, RECIPE_PROPERTY, RECIPE_MANAGED_VERSION ->
                    "CAP-MAVEN-PARENT-VERSION";
            case RECIPE_DEPENDENCY_COORDINATE, RECIPE_ADD_DEPENDENCY, RECIPE_REMOVE_DEPENDENCY ->
                    "CAP-MAVEN-DEPENDENCY";
            default -> null;
        };
        return capabilities(sourceVersion, targetVersion).stream()
                .filter(c -> c.capabilityId().equals(capabilityId))
                .findFirst();
    }

    @Override
    public TransformationOutcome apply(String recipeId, TransformationRequest request) {
        List<ProposedChange> changes = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        Map<String, String> parameters = request.parameters();

        for (String relativePath : request.targetPaths()) {
            if (!relativePath.endsWith("pom.xml")) {
                continue;
            }
            Path file = request.workspaceRoot().resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                messages.add("Skipped missing descriptor " + relativePath);
                continue;
            }
            String original = read(file);
            String updated = switch (recipeId) {
                case RECIPE_PARENT_VERSION -> changeParentVersion(original, parameters);
                case RECIPE_PROPERTY -> changeProperty(original, parameters);
                case RECIPE_MANAGED_VERSION -> changeManagedVersion(original, parameters);
                case RECIPE_DEPENDENCY_COORDINATE -> changeDependencyCoordinate(original, parameters);
                case RECIPE_ADD_DEPENDENCY -> addDependency(original, parameters);
                case RECIPE_REMOVE_DEPENDENCY -> removeDependency(original, parameters);
                default -> original;
            };
            if (updated != null && !updated.equals(original)) {
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("transformer", PROVIDER);
                // The content this proposal was derived from. The gateway refuses to apply a
                // proposal whose base no longer matches the tree, so a second recipe touching
                // the same file can never silently overwrite the first one's work.
                attributes.put("base_hash", com.bootshift.core.util.Hashing.sha256(original));
                changes.add(new ProposedChange(relativePath, null, "MODIFY", updated,
                        rationale(recipeId, parameters), recipeId,
                        splitCsv(parameters.get("knowledge_refs")),
                        splitCsv(parameters.get("impact_refs")), attributes));
            }
        }
        return new TransformationOutcome(changes, messages, List.of(), true);
    }

    private String changeParentVersion(String pom, Map<String, String> parameters) {
        String newVersion = parameters.get("version");
        if (newVersion == null) {
            return pom;
        }
        int parentStart = pom.indexOf("<parent>");
        int parentEnd = pom.indexOf("</parent>");
        if (parentStart < 0 || parentEnd < 0) {
            return pom;
        }
        String parentBlock = pom.substring(parentStart, parentEnd);
        String expectedArtifact = parameters.get("artifactId");
        if (expectedArtifact != null && !parentBlock.contains("<artifactId>" + expectedArtifact + "</artifactId>")) {
            return pom;
        }
        String replaced = replaceFirstTag(parentBlock, "version", newVersion);
        return pom.substring(0, parentStart) + replaced + pom.substring(parentEnd);
    }

    private String changeProperty(String pom, Map<String, String> parameters) {
        String key = parameters.get("key");
        String value = parameters.get("value");
        if (key == null || value == null) {
            return pom;
        }
        if (pom.contains("<" + key + ">")) {
            return replaceFirstTag(pom, key, value);
        }
        int propertiesStart = pom.indexOf("<properties>");
        if (propertiesStart < 0) {
            return pom;
        }
        int insertAt = propertiesStart + "<properties>".length();
        return pom.substring(0, insertAt) + "\n\t\t<" + key + ">" + value + "</" + key + ">"
                + pom.substring(insertAt);
    }

    private String changeManagedVersion(String pom, Map<String, String> parameters) {
        String groupId = parameters.get("groupId");
        String artifactId = parameters.get("artifactId");
        String version = parameters.get("version");
        if (groupId == null || artifactId == null || version == null) {
            return pom;
        }
        int managementStart = pom.indexOf("<dependencyManagement>");
        int managementEnd = pom.indexOf("</dependencyManagement>");
        if (managementStart < 0 || managementEnd < 0) {
            return pom;
        }
        String block = pom.substring(managementStart, managementEnd);
        String updated = replaceDependencyVersion(block, groupId, artifactId, version);
        return pom.substring(0, managementStart) + updated + pom.substring(managementEnd);
    }

    private String changeDependencyCoordinate(String pom, Map<String, String> parameters) {
        String fromGroup = parameters.get("fromGroupId");
        String fromArtifact = parameters.get("fromArtifactId");
        String toGroup = parameters.getOrDefault("toGroupId", fromGroup);
        String toArtifact = parameters.getOrDefault("toArtifactId", fromArtifact);
        String version = parameters.get("version");
        if (fromGroup == null || fromArtifact == null) {
            return pom;
        }
        String result = pom;
        for (int[] bounds : dependencyBounds(result)) {
            String block = result.substring(bounds[0], bounds[1]);
            if (!matches(block, fromGroup, fromArtifact)) {
                continue;
            }
            String updated = replaceFirstTag(block, "groupId", toGroup);
            updated = replaceFirstTag(updated, "artifactId", toArtifact);
            if (version != null) {
                updated = updated.contains("<version>")
                        ? replaceFirstTag(updated, "version", version)
                        : updated.replace("</artifactId>", "</artifactId>\n\t\t\t<version>" + version + "</version>");
            }
            result = result.substring(0, bounds[0]) + updated + result.substring(bounds[1]);
            break;
        }
        return result;
    }

    private String addDependency(String pom, Map<String, String> parameters) {
        String groupId = parameters.get("groupId");
        String artifactId = parameters.get("artifactId");
        if (groupId == null || artifactId == null) {
            return pom;
        }
        if (pom.contains("<artifactId>" + artifactId + "</artifactId>")) {
            return pom;
        }
        int end = pom.indexOf("</dependencies>");
        if (end < 0) {
            return pom;
        }
        StringBuilder dependency = new StringBuilder("\t\t<dependency>\n")
                .append("\t\t\t<groupId>").append(groupId).append("</groupId>\n")
                .append("\t\t\t<artifactId>").append(artifactId).append("</artifactId>\n");
        if (parameters.get("version") != null) {
            dependency.append("\t\t\t<version>").append(parameters.get("version")).append("</version>\n");
        }
        if (parameters.get("scope") != null) {
            dependency.append("\t\t\t<scope>").append(parameters.get("scope")).append("</scope>\n");
        }
        dependency.append("\t\t</dependency>\n\t");
        return pom.substring(0, end) + dependency + pom.substring(end);
    }

    private String removeDependency(String pom, Map<String, String> parameters) {
        String groupId = parameters.get("groupId");
        String artifactId = parameters.get("artifactId");
        if (groupId == null || artifactId == null) {
            return pom;
        }
        for (int[] bounds : dependencyBounds(pom)) {
            String block = pom.substring(bounds[0], bounds[1]);
            if (matches(block, groupId, artifactId)) {
                int lineStart = pom.lastIndexOf('\n', bounds[0]);
                int lineEnd = pom.indexOf('\n', bounds[1]);
                return pom.substring(0, lineStart < 0 ? bounds[0] : lineStart)
                        + (lineEnd < 0 ? "" : pom.substring(lineEnd));
            }
        }
        return pom;
    }

    // ------------------------------------------------------------------ helpers

    private List<int[]> dependencyBounds(String pom) {
        List<int[]> bounds = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = pom.indexOf("<dependency>", cursor);
            if (start < 0) {
                break;
            }
            int end = pom.indexOf("</dependency>", start);
            if (end < 0) {
                break;
            }
            bounds.add(new int[]{start, end + "</dependency>".length()});
            cursor = end + 1;
        }
        return bounds;
    }

    private boolean matches(String block, String groupId, String artifactId) {
        return block.contains("<groupId>" + groupId + "</groupId>")
                && block.contains("<artifactId>" + artifactId + "</artifactId>");
    }

    private String replaceDependencyVersion(String block, String groupId, String artifactId, String version) {
        for (int[] bounds : dependencyBounds(block)) {
            String dependency = block.substring(bounds[0], bounds[1]);
            if (matches(dependency, groupId, artifactId)) {
                String updated = replaceFirstTag(dependency, "version", version);
                return block.substring(0, bounds[0]) + updated + block.substring(bounds[1]);
            }
        }
        return block;
    }

    /** Replaces the first occurrence of a simple element value without touching anything else. */
    public static String replaceFirstTag(String xml, String tag, String value) {
        int open = xml.indexOf("<" + tag + ">");
        if (open < 0) {
            return xml;
        }
        int close = xml.indexOf("</" + tag + ">", open);
        if (close < 0) {
            return xml;
        }
        return xml.substring(0, open + tag.length() + 2) + value + xml.substring(close);
    }

    private static String rationale(String recipeId, Map<String, String> parameters) {
        return "Deterministic Maven descriptor change (" + recipeId + ") with parameters " + parameters;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot read " + file, e);
        }
    }
}
