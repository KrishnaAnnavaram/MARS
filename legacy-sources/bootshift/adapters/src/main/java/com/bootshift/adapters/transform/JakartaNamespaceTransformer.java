package com.bootshift.adapters.transform;

import com.bootshift.ports.transformation.TransformationPort;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * javax to jakarta namespace migration (Agent 12).
 *
 * <p>Scope is deliberately restricted to the Jakarta EE packages that actually moved. {@code javax}
 * packages that stayed - {@code javax.sql}, {@code javax.net}, {@code javax.crypto},
 * {@code javax.naming} and friends - are explicitly excluded, because a blanket
 * {@code javax -> jakarta} rewrite is the classic way to turn a working application into one that no
 * longer compiles for reasons unrelated to the migration.
 */
public final class JakartaNamespaceTransformer implements TransformationPort {

    public static final String PROVIDER = "BOOTSHIFT_JAKARTA";
    public static final String RECIPE = "jakarta.namespace";

    /** Packages that genuinely relocated from javax to jakarta in Jakarta EE 9. */
    public static final List<String> RELOCATED = List.of(
            "javax.persistence",
            "javax.servlet",
            "javax.validation",
            "javax.annotation.PostConstruct",
            "javax.annotation.PreDestroy",
            "javax.annotation.Resource",
            "javax.annotation.Priority",
            "javax.annotation.security",
            "javax.annotation.sql",
            "javax.transaction",
            "javax.ws.rs",
            "javax.json",
            "javax.jms",
            "javax.mail",
            "javax.enterprise",
            "javax.inject",
            "javax.interceptor",
            "javax.decorator",
            "javax.el",
            "javax.faces",
            "javax.batch",
            "javax.websocket",
            "javax.xml.bind",
            "javax.xml.soap",
            "javax.xml.ws",
            "javax.activation",
            "javax.security.enterprise",
            "javax.security.auth.message");

    /** Packages that must never be rewritten: they remain in the JDK or in unrelated specs. */
    public static final List<String> PRESERVED = List.of(
            "javax.sql", "javax.net", "javax.crypto", "javax.naming", "javax.management",
            "javax.imageio", "javax.print", "javax.script", "javax.sound", "javax.swing",
            "javax.tools", "javax.lang.model", "javax.accessibility", "javax.smartcardio",
            "javax.security.auth.Subject", "javax.security.auth.login", "javax.security.auth.callback",
            "javax.security.cert", "javax.xml.parsers", "javax.xml.transform", "javax.xml.stream",
            "javax.xml.xpath", "javax.xml.namespace", "javax.xml.validation", "javax.xml.catalog",
            "javax.xml.datatype");

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public List<Capability> capabilities(String sourceVersion, String targetVersion) {
        boolean relevant = majorOf(sourceVersion) < 3 && majorOf(targetVersion) >= 3;
        return List.of(new Capability(
                "CAP-JAKARTA-NAMESPACE", PROVIDER, "bootshift-jakarta-transformer", "1.0.0",
                "MIT", "harness-owned", "2.x", "3.x",
                List.of("API_RENAMED", "ARTIFACT_RELOCATED"),
                // "javax." covers both shapes the knowledge base produces: a per-package fact whose
                // subject is javax.persistence, and the structural boundary fact whose subject is
                // the literal string "javax.* to jakarta.*". Listing only the relocated packages
                // made coverage report the namespace migration as unhandled.
                java.util.stream.Stream.concat(RELOCATED.stream(), java.util.stream.Stream.of("javax."))
                        .toList(),
                true, true, "SINGLE_EDGE", relevant ? 1.0 : 0.0,
                relevant ? "AVAILABLE" : "NOT_APPLICABLE",
                "Rewrites only the " + RELOCATED.size() + " Jakarta EE packages that actually moved; "
                        + PRESERVED.size() + " javax packages are explicitly preserved."));
    }

    @Override
    public boolean handles(String recipeId) {
        return RECIPE.equals(recipeId);
    }

    @Override
    public TransformationOutcome apply(String recipeId, TransformationRequest request) {
        List<ProposedChange> changes = new ArrayList<>();
        List<String> messages = new ArrayList<>();

        for (String relativePath : request.targetPaths()) {
            if (!relativePath.endsWith(".java")) {
                continue;
            }
            Path file = request.workspaceRoot().resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String original = read(file);
            Rewrite rewrite = rewrite(original);
            if (rewrite.changed() > 0) {
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("transformer", PROVIDER);
                attributes.put("base_hash", com.bootshift.core.util.Hashing.sha256(original));
                attributes.put("replacements", String.valueOf(rewrite.changed()));
                attributes.put("packages", String.join(",", rewrite.packages()));
                changes.add(new ProposedChange(relativePath, null, "MODIFY", rewrite.content(),
                        "Rewrote " + rewrite.changed() + " relocated Jakarta EE reference(s): "
                                + String.join(", ", rewrite.packages()),
                        RECIPE, splitCsv(request.parameters().get("knowledge_refs")),
                        splitCsv(request.parameters().get("impact_refs")), attributes));
            }
        }
        if (changes.isEmpty()) {
            messages.add("No relocated Jakarta EE references found in the authorized scope");
        }
        return new TransformationOutcome(changes, messages, List.of(), true);
    }

    public record Rewrite(String content, int changed, List<String> packages) {
    }

    /** Pure function so the transformation is unit-testable and idempotent by construction. */
    public static Rewrite rewrite(String source) {
        String result = source;
        int changed = 0;
        List<String> touched = new ArrayList<>();
        for (String prefix : RELOCATED) {
            String replacement = "jakarta." + prefix.substring("javax.".length());
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(prefix) + "\\b");
            Matcher matcher = pattern.matcher(result);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            if (count > 0) {
                result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(replacement));
                changed += count;
                touched.add(prefix);
            }
        }
        return new Rewrite(result, changed, touched);
    }

    /** True when the reference is one this transformer must leave alone. */
    public static boolean isPreserved(String reference) {
        return PRESERVED.stream().anyMatch(reference::startsWith);
    }

    private static int majorOf(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        String head = version.split("\\.")[0].replaceAll("[^0-9]", "");
        return head.isEmpty() ? 0 : Integer.parseInt(head);
    }

    private static List<String> splitCsv(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
