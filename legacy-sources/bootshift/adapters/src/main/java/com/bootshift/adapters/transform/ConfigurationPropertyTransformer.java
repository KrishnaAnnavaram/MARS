package com.bootshift.adapters.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.util.Json;
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

/**
 * Configuration property migration driven by generated rules (spec section 23).
 *
 * <p>The rules are not hand-written. They are generated from the source and target
 * {@code spring-configuration-metadata.json} deprecation entries, which is the only scalable way to
 * cover hundreds of property renames without inventing any of them.
 */
public final class ConfigurationPropertyTransformer implements TransformationPort {

    public static final String PROVIDER = "BOOTSHIFT_CONFIG_PROPERTY";
    public static final String RECIPE = "config.property-migration";

    /** One generated rule: rename, remove, or replace with a documented successor. */
    public record PropertyRule(String from, String to, String action, String reason, String evidenceRef) {
    }

    private final List<PropertyRule> rules;

    public ConfigurationPropertyTransformer(List<PropertyRule> rules) {
        this.rules = rules;
    }

    /** Loads generated rules from the migration-rules directory. */
    public static ConfigurationPropertyTransformer fromRuleFile(Path ruleFile) {
        List<PropertyRule> rules = new ArrayList<>();
        if (ruleFile != null && Files.isRegularFile(ruleFile)) {
            JsonNode node = Json.read(ruleFile);
            for (JsonNode rule : node.path("rules")) {
                rules.add(new PropertyRule(rule.path("from").asText(), rule.path("to").asText(null),
                        rule.path("action").asText("RENAME"), rule.path("reason").asText(null),
                        rule.path("evidence_ref").asText(null)));
            }
        }
        return new ConfigurationPropertyTransformer(rules);
    }

    public List<PropertyRule> rules() {
        return rules;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public List<Capability> capabilities(String sourceVersion, String targetVersion) {
        return List.of(new Capability(
                "CAP-CONFIG-PROPERTY", PROVIDER, "bootshift-config-property-transformer", "1.0.0",
                "MIT", "harness-owned", sourceVersion, targetVersion,
                List.of("PROPERTY_RENAMED", "PROPERTY_REMOVED", "PROPERTY_SILENTLY_IGNORED"),
                List.of(),
                true, true, "SINGLE_EDGE", rules.isEmpty() ? 0.0 : 1.0,
                rules.isEmpty() ? "NO_RULES_GENERATED" : "AVAILABLE",
                rules.size() + " rule(s) generated from official configuration metadata deprecations."));
    }

    @Override
    public boolean handles(String recipeId) {
        return RECIPE.equals(recipeId);
    }

    @Override
    public TransformationOutcome apply(String recipeId, TransformationRequest request) {
        List<ProposedChange> changes = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        if (rules.isEmpty()) {
            messages.add("No property migration rules were generated for this edge; "
                    + "property migration is recorded as residual rather than silently skipped.");
            return new TransformationOutcome(changes, messages,
                    List.of("PROPERTY_RENAMED", "PROPERTY_REMOVED"), true);
        }

        for (String relativePath : request.targetPaths()) {
            if (!relativePath.endsWith(".properties") && !relativePath.endsWith(".yml")
                    && !relativePath.endsWith(".yaml")) {
                continue;
            }
            Path file = request.workspaceRoot().resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String original = read(file);
            Result result = relativePath.endsWith(".properties")
                    ? migrateProperties(original, rules)
                    : migrateYaml(original, rules);
            if (result.applied().isEmpty()) {
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("transformer", PROVIDER);
            attributes.put("base_hash", com.bootshift.core.util.Hashing.sha256(original));
            attributes.put("rules_applied", String.join(",", result.applied()));
            changes.add(new ProposedChange(relativePath, null, "MODIFY", result.content(),
                    "Applied " + result.applied().size() + " generated property rule(s)",
                    RECIPE, splitCsv(request.parameters().get("knowledge_refs")),
                    splitCsv(request.parameters().get("impact_refs")), attributes));
        }
        return new TransformationOutcome(changes, messages, List.of(), true);
    }

    public record Result(String content, List<String> applied) {
    }

    /**
     * Migrates a .properties file. Comments and blank lines are preserved verbatim; a removed
     * property is commented out with the reason rather than deleted, so the change stays reviewable.
     */
    public static Result migrateProperties(String content, List<PropertyRule> rules) {
        List<String> applied = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            String rewritten = line;
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
                int separator = indexOfSeparator(trimmed);
                if (separator > 0) {
                    String key = trimmed.substring(0, separator).trim();
                    String value = trimmed.substring(separator + 1);
                    for (PropertyRule rule : rules) {
                        if (!key.equals(rule.from())) {
                            continue;
                        }
                        if ("REMOVE".equals(rule.action()) || rule.to() == null || rule.to().isBlank()) {
                            rewritten = "# [bootshift] removed property " + key + ": "
                                    + (rule.reason() == null ? "no replacement in target version" : rule.reason())
                                    + "\n#" + line;
                        } else {
                            rewritten = line.replace(key, rule.to());
                        }
                        applied.add(rule.from());
                        break;
                    }
                }
            }
            out.append(rewritten);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return new Result(out.toString(), applied);
    }

    /**
     * Migrates YAML using a real structural parse rather than an indentation assumption.
     *
     * <p>The previous implementation computed nesting as {@code indent / 2}. YAML does not require
     * two-space indentation, so a four-space file produced dotted keys at the wrong depth and every
     * rule silently matched nothing while the run reported the properties as migrated. Nested
     * sequences, quoted keys, values containing colons and multi-document profile files all made it
     * worse.
     *
     * <p>The structure now comes from a YAML parser, which also reports the line each key occupies.
     * Only those lines are edited, so comments, blank lines and formatting elsewhere survive.
     *
     * <p>A file that does not parse is left completely alone and reported. Falling back to line
     * scanning would mean editing a file whose structure the harness does not actually understand.
     */
    public static Result migrateYaml(String content, List<PropertyRule> rules) {
        List<String> applied = new ArrayList<>();
        YamlPropertyModel model = YamlPropertyModel.parse(content);
        if (!model.parsed()) {
            return new Result(content, applied);
        }

        Map<String, PropertyRule> byKey = new LinkedHashMap<>();
        rules.forEach(rule -> byKey.put(rule.from(), rule));

        String[] lines = content.split("\n", -1);
        // Edits keyed by line so two rules cannot both claim the same physical line.
        Map<Integer, String> replacements = new LinkedHashMap<>();

        for (YamlPropertyModel.Leaf leaf : model.leaves()) {
            PropertyRule rule = byKey.get(leaf.dottedKey());
            if (rule == null || leaf.keyLine() < 0 || leaf.keyLine() >= lines.length) {
                continue;
            }
            if (replacements.containsKey(leaf.keyLine())) {
                continue;
            }
            String line = lines[leaf.keyLine()];
            String indent = " ".repeat(Math.max(0, leaf.indent()));

            if ("REMOVE".equals(rule.action()) || rule.to() == null || rule.to().isBlank()) {
                // Removing a bound property is a behavioural event, not a formatting change: the
                // application stops seeing a value it was configured with. Commenting it out is the
                // most conservative edit available, and it is flagged so the runtime comparison
                // treats it as something to check rather than something already known safe.
                if (leaf.block() || leaf.sequence() || leaf.valueEndLine() != leaf.keyLine()) {
                    // A multi-line value cannot be commented out by touching one line without
                    // risking a malformed document, so it is left and reported as residual.
                    continue;
                }
                replacements.put(leaf.keyLine(), indent + "# [bootshift] removed "
                        + leaf.dottedKey() + ": "
                        + (rule.reason() == null ? "no documented replacement" : rule.reason())
                        + System.lineSeparator() + indent + "# " + line.trim());
                applied.add(rule.from());
                continue;
            }

            // Rename. The replacement is written as a dotted key at the original indentation, which
            // Spring binds identically to the nested form and which cannot collide with a sibling
            // branch the way re-nesting could.
            String rewritten = YamlPropertyModel.rewriteKeyLine(line, leaf.dottedKey(), rule.to());
            if (!rewritten.equals(line)) {
                replacements.put(leaf.keyLine(), rewritten);
                applied.add(rule.from());
            }
        }

        if (replacements.isEmpty()) {
            return new Result(content, applied);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(replacements.getOrDefault(i, lines[i]));
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return new Result(out.toString(), applied);
    }

    private static int indexOfSeparator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0) {
            return colon;
        }
        if (colon < 0) {
            return equals;
        }
        return Math.min(equals, colon);
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
