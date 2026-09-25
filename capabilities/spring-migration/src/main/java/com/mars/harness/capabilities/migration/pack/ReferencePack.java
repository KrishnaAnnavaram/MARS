package com.mars.harness.capabilities.migration.pack;

import com.bootshift.core.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.ports.build.BuildModelView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A migration reference pack: the Markdown pack (authoritative knowledge, read in place from
 * {@code legacy-sources}) plus its machine-readable rules file.
 *
 * <p>Matching follows the reference {@code detect-baseline.js}. A {@code detect} entry
 * {@code groupId:artifactId[:versionPrefix]} matches the parent, any dependency or any plugin.
 * "No matching reference pack is a stop condition, not a licence to improvise."
 */
public final class ReferencePack {

    public record FrontMatter(String id, String title, String from, String to, String languageFrom, String languageTo,
                              List<String> detect) {
    }

    private final FrontMatter frontMatter;
    private final JsonNode rules;
    private final Path packFile;
    private final String packSha256;
    private final boolean rulesCurrent;

    private ReferencePack(FrontMatter frontMatter, JsonNode rules, Path packFile, String packSha256) {
        this.frontMatter = frontMatter;
        this.rules = rules;
        this.packFile = packFile;
        this.packSha256 = packSha256;
        this.rulesCurrent = packSha256.equals(rules.path("pack_sha256").asText());
    }

    /** Loads every {@code *.rules.json} under {@code capabilities/spring-migration/reference-packs}. */
    public static List<ReferencePack> loadAll(Path harnessRoot) {
        Path dir = harnessRoot.resolve("capabilities").resolve("spring-migration").resolve("reference-packs");
        List<ReferencePack> packs = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return packs;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path rulesFile : files.filter(p -> p.toString().endsWith(".rules.json")).sorted().toList()) {
                JsonNode rules = KernelJson.read(rulesFile);
                Path pack = harnessRoot.resolve(rules.path("pack_file").asText());
                if (!Files.isRegularFile(pack)) {
                    continue;
                }
                String text = Files.readString(pack, StandardCharsets.UTF_8);
                packs.add(new ReferencePack(frontMatter(text), rules, pack, Hashing.sha256File(pack)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load reference packs from " + dir, e);
        }
        return packs;
    }

    /** Minimal YAML front-matter reader for the pack contract fields. */
    static FrontMatter frontMatter(String text) {
        Map<String, String> scalars = new LinkedHashMap<>();
        List<String> detect = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            throw new IllegalArgumentException("Reference pack has no front matter");
        }
        boolean inDetect = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().equals("---")) {
                break;
            }
            if (line.startsWith("  - ") && inDetect) {
                detect.add(line.substring(4).trim());
                continue;
            }
            inDetect = false;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim().replaceAll("^\"|\"$", "");
                if (key.equals("detect") && value.isEmpty()) {
                    inDetect = true;
                } else {
                    scalars.put(key, value);
                }
            }
        }
        return new FrontMatter(scalars.get("id"), scalars.get("title"), scalars.get("from"), scalars.get("to"),
                scalars.get("language_from"), scalars.get("language_to"), detect);
    }

    /** The first {@code detect} entry this build model matches, or empty. */
    public Optional<String> matchedOn(BuildModelView model) {
        if (model == null) {
            return Optional.empty();
        }
        for (String entry : frontMatter.detect()) {
            String[] parts = entry.split(":");
            if (parts.length < 2) {
                continue;
            }
            String prefix = parts.length > 2 ? parts[2] : null;
            for (BuildModelView.ModuleBuild module : model.modules()) {
                List<BuildModelView.Coordinate> coordinates = new ArrayList<>();
                if (module.parent() != null) {
                    coordinates.add(module.parent());
                }
                module.dependencies().forEach(d -> coordinates.add(new BuildModelView.Coordinate(d.groupId(), d.artifactId(),
                        d.effectiveVersion())));
                coordinates.addAll(module.plugins());
                for (BuildModelView.Coordinate c : coordinates) {
                    if (parts[0].equals(c.groupId()) && parts[1].equals(c.artifactId())
                            && (prefix == null || c.version() != null && c.version().startsWith(prefix))) {
                        return Optional.of(entry + " (" + module.buildFile() + ")");
                    }
                }
            }
        }
        return Optional.empty();
    }

    public FrontMatter frontMatter() {
        return frontMatter;
    }

    public JsonNode rules() {
        return rules;
    }

    public Path packFile() {
        return packFile;
    }

    public String packSha256() {
        return packSha256;
    }

    /** False when the Markdown pack changed after its rules were derived. Execution then refuses. */
    public boolean rulesCurrent() {
        return rulesCurrent;
    }

    public List<JsonNode> buildFileRules() {
        List<JsonNode> list = new ArrayList<>();
        rules.path("build_file_rules").forEach(list::add);
        return list;
    }

    public List<JsonNode> symptomRules() {
        List<JsonNode> list = new ArrayList<>();
        rules.path("symptom_rules").forEach(list::add);
        return list;
    }

    public String relativePackPath() {
        return rules.path("pack_file").asText();
    }
}
