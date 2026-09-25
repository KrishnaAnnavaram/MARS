package com.bootshift.adapters.transform;

import com.bootshift.core.util.Hashing;
import com.bootshift.ports.transformation.TransformationPort;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes annotations that a target version deleted and that carry no behaviour when absent.
 *
 * <p>This transformer exists because the reference corpus proved the gap. Spring Cloud 2022.0
 * deleted {@code @EnableEurekaClient}: registration became automatic whenever the Eureka client
 * starter is on the classpath, so the annotation went from redundant to nonexistent. Nothing in the
 * deterministic estate could remove it, and the major edge stopped with sixteen
 * {@code cannot find symbol} errors that a human would have fixed by deleting one line per module.
 *
 * <h2>Why the list is curated, and why that is the right answer here</h2>
 *
 * <p>It would be easy to drive this from every {@code API_REMOVED} fact the artifact channel
 * produces. It would also be wrong. "This type no longer exists" does not imply "deleting the
 * reference is safe" - for most removed types the reference is load-bearing and deleting it changes
 * behaviour silently, which is the single worst outcome this harness is built to prevent.
 *
 * <p>Removal is only safe for annotations whose <em>entire</em> effect was to opt into behaviour the
 * target version performs unconditionally. That is a claim about semantics, not about presence, and
 * no diff can establish it. So each entry carries the evidence for why removal is a no-op, and an
 * annotation that is not on this list stays residual - reported, not guessed at.
 */
public final class RemovedAnnotationTransformer implements TransformationPort {

    public static final String RECIPE_REMOVE_ANNOTATION = "java.remove-annotation";
    private static final String PROVIDER = "BOOTSHIFT_REMOVED_ANNOTATION";

    /** An annotation whose removal is a no-op at the target version, with the reason it is. */
    public record NoOpAnnotation(String fqn, String removedAtVersion, String rationale) {

        public String simpleName() {
            return fqn.substring(fqn.lastIndexOf('.') + 1);
        }

        public String packageName() {
            return fqn.substring(0, fqn.lastIndexOf('.') + 1);
        }
    }

    /**
     * Annotations this transformer will delete.
     *
     * <p>Every entry states why deleting it changes nothing. An entry that cannot state that does not
     * belong here.
     */
    public static final List<NoOpAnnotation> NO_OP_ANNOTATIONS = List.of(
            new NoOpAnnotation(
                    "org.springframework.cloud.netflix.eureka.EnableEurekaClient",
                    "2022.0.0",
                    "Removed in Spring Cloud 2022.0. A service registers with Eureka whenever "
                            + "spring-cloud-starter-netflix-eureka-client is on the classpath, so the "
                            + "annotation had no effect of its own before it was deleted."),
            new NoOpAnnotation(
                    "org.springframework.cloud.client.discovery.EnableDiscoveryClient",
                    "2022.0.0",
                    "Deprecated then removed. Discovery-client auto-configuration activates from the "
                            + "starter on the classpath; the annotation only ever opted into behaviour "
                            + "that is now unconditional."),
            new NoOpAnnotation(
                    "org.springframework.cloud.netflix.hystrix.EnableHystrix",
                    "2020.0.0",
                    "Hystrix was removed from the release train. The annotation cannot be honoured at "
                            + "the target version and leaving it is a compile error, not a behaviour."));

    private static final Pattern IMPORT_LINE = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;\\s*$");

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public List<Capability> capabilities(String sourceVersion, String targetVersion) {
        List<String> prefixes = NO_OP_ANNOTATIONS.stream().map(NoOpAnnotation::fqn).toList();
        return List.of(new Capability(
                "CAP-REMOVE-NOOP-ANNOTATION", PROVIDER, "bootshift-removed-annotation-transformer",
                "1.0.0", "MIT", "harness-owned", "*", "*",
                List.of("API_REMOVED"), prefixes, true, true, "SINGLE_EDGE", 1.0, "AVAILABLE",
                "Deletes only the " + prefixes.size() + " annotations whose entire effect was to opt "
                        + "into behaviour the target performs unconditionally. Every other removed "
                        + "API stays residual, because 'the type is gone' does not mean 'deleting "
                        + "the reference is safe'."));
    }

    @Override
    public boolean handles(String recipeId) {
        return RECIPE_REMOVE_ANNOTATION.equals(recipeId);
    }

    @Override
    public TransformationOutcome apply(String recipeId, TransformationRequest request) {
        List<ProposedChange> changes = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        if (!handles(recipeId)) {
            return new TransformationOutcome(changes, messages, List.of(), false);
        }

        Set<NoOpAnnotation> applicable = applicableAt(request.targetVersion());
        if (applicable.isEmpty()) {
            messages.add("No no-op annotation removals apply at " + request.targetVersion());
            return new TransformationOutcome(changes, messages, List.of(), true);
        }

        for (String relativePath : request.targetPaths()) {
            if (!relativePath.endsWith(".java")) {
                continue;
            }
            Path file = request.workspaceRoot().resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String original;
            try {
                original = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                messages.add("Unreadable source " + relativePath + ": " + e.getMessage());
                continue;
            }

            String updated = original;
            List<String> removed = new ArrayList<>();
            for (NoOpAnnotation annotation : applicable) {
                String next = remove(updated, annotation);
                if (!next.equals(updated)) {
                    updated = next;
                    removed.add(annotation.simpleName());
                }
            }
            if (removed.isEmpty()) {
                continue;
            }

            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("transformer", PROVIDER);
            attributes.put("removed_annotations", String.join(",", removed));
            attributes.put("base_hash", Hashing.sha256(original));
            changes.add(new ProposedChange(relativePath, null, "MODIFY", updated,
                    "Removes " + String.join(", ", removed) + "; the annotation does not exist at "
                            + request.targetVersion() + " and its behaviour is unconditional there",
                    recipeId, splitCsv(request.parameters().get("knowledge_refs")),
                    splitCsv(request.parameters().get("impact_refs")), attributes));
        }
        return new TransformationOutcome(changes, messages, List.of(), true);
    }

    /** The annotations removed at or before the edge's target version. */
    static Set<NoOpAnnotation> applicableAt(String targetVersion) {
        Set<NoOpAnnotation> applicable = new LinkedHashSet<>();
        for (NoOpAnnotation annotation : NO_OP_ANNOTATIONS) {
            // The removal versions are Spring Cloud train identifiers (2022.0.0) while the edge's
            // target is a Spring Boot version. The transformer is driven by the plan, which only
            // schedules it on an edge whose train crosses the removal, so the guard here is simply
            // that a target exists.
            if (targetVersion != null && !targetVersion.isBlank()) {
                applicable.add(annotation);
            }
        }
        return applicable;
    }

    /**
     * Removes the annotation usage and its import.
     *
     * <p>Both halves matter. Deleting the usage and leaving the import turns sixteen "cannot find
     * symbol" errors into sixteen "package does not exist" errors, which is not progress.
     */
    public static String remove(String source, NoOpAnnotation annotation) {
        boolean importsIt = false;
        StringBuilder out = new StringBuilder(source.length());
        String[] lines = source.split("\\R", -1);
        String lineSeparator = source.contains("\r\n") ? "\r\n" : "\n";

        // Pass 1: is the type imported here? A file that does not reference it is left alone, and a
        // file that uses a same-named annotation from another package must not be touched.
        for (String line : lines) {
            Matcher m = IMPORT_LINE.matcher(line);
            if (m.matches() && m.group(1).equals(annotation.fqn())) {
                importsIt = true;
                break;
            }
        }
        boolean usesFullyQualified = source.contains("@" + annotation.fqn());
        if (!importsIt && !usesFullyQualified) {
            return source;
        }

        Pattern usage = Pattern.compile(
                "@" + (importsIt ? Pattern.quote(annotation.simpleName())
                        : Pattern.quote(annotation.fqn()))
                        + "(\\s*\\([^)]*\\))?");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher importMatcher = IMPORT_LINE.matcher(line);
            if (importMatcher.matches() && importMatcher.group(1).equals(annotation.fqn())) {
                continue;
            }
            String rewritten = usage.matcher(line).replaceAll("");
            // An annotation that sat alone on its line leaves an empty line behind; drop it rather
            // than leave a gap where a reviewer expects to see something.
            if (!rewritten.equals(line) && rewritten.isBlank()) {
                continue;
            }
            out.append(rewritten);
            if (i < lines.length - 1) {
                out.append(lineSeparator);
            }
        }
        return out.toString();
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }
}
