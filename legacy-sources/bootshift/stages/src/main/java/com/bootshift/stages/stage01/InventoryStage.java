package com.bootshift.stages.stage01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.identity.FileRole;
import com.bootshift.core.security.SensitiveValues;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Agent 01 - Inventory (spec section 10).
 *
 * <pre>
 * READ ONLY | DETERMINISTIC | ZERO LLM | FIRST STAGE
 * </pre>
 *
 * <p>Two responsibilities that no later stage may take over: discovering and classifying what
 * arrived, and allocating the permanent FILE_IDs everything downstream refers to (R2).
 *
 * <p>Migration-sensitive patterns are recorded as <em>signals</em>, never conclusions. "This file
 * imports javax.persistence" is a signal; "this file must be migrated" is a decision that belongs to
 * Agent 09 after verified knowledge exists.
 */
public final class InventoryStage implements Stage {

    public static final String OUTPUT_DIR = "01-inventory";

    /** Directory names that are build output or tooling rather than application source. */
    private static final List<String> EXCLUDED_DIRECTORIES = List.of(
            "target", "build", "out", "bin", ".git", ".idea", ".gradle", ".mvn/wrapper",
            "node_modules", ".settings", ".vscode");

    /** Signal definitions: a stable id, a matcher, and what the signal means for later stages. */
    public record SignalDefinition(String id, Pattern pattern, String category, String note) {
    }

    private static final List<SignalDefinition> SIGNALS = List.of(
            sig("JAVAX_NAMESPACE", "\\bjavax\\.(persistence|servlet|validation|annotation|transaction|ws\\.rs|xml\\.bind|inject|enterprise|interceptor|websocket)",
                    "NAMESPACE", "Jakarta EE relocation candidate"),
            sig("JAKARTA_NAMESPACE", "\\bjakarta\\.(persistence|servlet|validation|annotation)",
                    "NAMESPACE", "Already on the Jakarta namespace"),
            sig("SPRING_SECURITY_LEGACY", "WebSecurityConfigurerAdapter|authorizeRequests\\(|antMatchers\\(",
                    "SECURITY", "Removed in Spring Security 6"),
            sig("MOCK_BEAN", "@MockBean|@SpyBean", "TEST",
                    "Deprecated in Spring Boot 3.4 in favour of the bean-override annotations"),
            sig("JUNIT4", "org\\.junit\\.(Test|Before|After|Ignore|runner)|@RunWith", "TEST",
                    "JUnit 4 constructs; test-infrastructure migration edge"),
            sig("SPRING_CLOUD", "org\\.springframework\\.cloud", "CLOUD",
                    "Spring Cloud release train is version-locked to Spring Boot"),
            sig("CONFIG_SERVER", "@EnableConfigServer|spring\\.cloud\\.config", "CLOUD",
                    "External configuration source affects binding observations"),
            sig("EUREKA", "@EnableEurekaServer|@EnableEurekaClient|eureka\\.client", "CLOUD",
                    "Service discovery participation"),
            sig("BOOTSTRAP_PROPERTIES", "spring\\.cloud\\.config\\.uri", "CLOUD",
                    "Legacy bootstrap context; removed by default in Spring Cloud 2020+"),
            sig("UNDERTOW", "spring-boot-starter-undertow|io\\.undertow", "RUNTIME",
                    "Servlet container substitution"),
            sig("JACKSON_CUSTOM", "@JsonSerialize|@JsonDeserialize|SimpleModule|ObjectMapper\\(",
                    "SERIALIZATION", "Custom serialization is a differential dimension"),
            sig("HIBERNATE_JPA", "javax\\.persistence|jakarta\\.persistence|org\\.hibernate", "PERSISTENCE",
                    "ORM behaviour is a differential dimension"),
            sig("MONGODB", "org\\.springframework\\.data\\.mongodb|@Document|MongoRepository",
                    "PERSISTENCE", "Document store semantics"),
            sig("FLYWAY_LIQUIBASE", "flyway|liquibase", "PERSISTENCE", "Schema migration tooling"),
            sig("KAFKA_RABBIT", "org\\.springframework\\.kafka|spring-rabbit|amqp", "MESSAGING",
                    "Message broker participation"),
            sig("REDIS", "spring-boot-starter-data-redis|RedisTemplate", "CACHE", "Cache semantics"),
            sig("SPRING_BATCH", "org\\.springframework\\.batch", "BATCH", "Batch job semantics"),
            sig("SCHEDULING", "@Scheduled|@EnableScheduling", "SCHEDULING", "Scheduled behaviour"),
            sig("SPRING_FACTORIES", "spring\\.factories", "AUTOCONFIG",
                    "Replaced by AutoConfiguration.imports in Boot 3"),
            sig("AUTOCONFIGURATION_IMPORTS", "AutoConfiguration\\.imports", "AUTOCONFIG",
                    "Boot 2.7+ auto-configuration registration"),
            sig("REFLECTION", "Class\\.forName|getDeclaredMethod|newInstance\\(", "REFLECTION",
                    "Dynamic behaviour that static analysis cannot fully resolve"),
            sig("RESILIENCE4J", "io\\.github\\.resilience4j|@CircuitBreaker", "RESILIENCE",
                    "Circuit breaker configuration is version-sensitive"),
            sig("WEBFLUX", "org\\.springframework\\.web\\.reactive|WebClient", "RUNTIME",
                    "Reactive stack present"),
            sig("LOMBOK", "lombok", "BUILD", "Annotation processor with JDK-version coupling"),
            sig("WAR_PACKAGING", "<packaging>war</packaging>", "BUILD",
                    "WAR packaging changes the deployment and startup model"),
            sig("CUCUMBER", "io\\.cucumber|@CucumberOptions", "TEST", "BDD test infrastructure"),
            sig("INTERNAL_STARTER", "<groupId>(?!org\\.springframework|org\\.apache|com\\.fasterxml|io\\.|org\\.junit|org\\.projectlombok|org\\.mockito|org\\.assertj|jakarta\\.|javax\\.)[a-z][\\w.]*</groupId>",
                    "INTERNAL", "Possible organization-owned dependency requiring a compatibility profile"));

    private static SignalDefinition sig(String id, String regex, String category, String note) {
        return new SignalDefinition(id, Pattern.compile(regex), category, note);
    }

    @Override
    public String id() {
        return OUTPUT_DIR;
    }

    @Override
    public String outputDirectory() {
        return OUTPUT_DIR;
    }

    @Override
    public String purpose() {
        return "Discover what repository was received and allocate permanent file identities";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.OSS_POLICY_VERIFIED);
    }

    @Override
    public RunState postcondition() {
        return RunState.FILE_REGISTRY_SEALED;
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("inventory-artifact.json", "file-registry.json", "inventory-signals.json",
                "inventory-issues.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        Path candidate = context.run().originalWorkspace();
        final Path root = Files.isDirectory(candidate) ? candidate : context.run().sourceRoot();

        FileRegistry registry = loadOrCreateRegistry(context);
        boolean rescan = registry.size() > 0;

        List<Path> files = enumerate(root);
        List<String> relativePaths = files.stream()
                .map(p -> FileRegistry.normalize(root.relativize(p).toString()))
                .toList();
        registry.beginScan(relativePaths);

        Map<String, ModuleAccumulator> modules = new TreeMap<>();
        List<ObjectNode> signals = new ArrayList<>();
        List<ObjectNode> issues = new ArrayList<>();
        Map<String, Integer> roleCounts = new TreeMap<>();
        List<ObjectNode> attachments = new ArrayList<>();

        for (Path file : files) {
            String relative = FileRegistry.normalize(root.relativize(file).toString());
            FileRole role = classify(relative);
            long size;
            String sha;
            String content = null;
            try {
                size = Files.size(file);
                sha = Hashing.sha256File(file);
                if (isTextual(role) && size < 2_000_000) {
                    content = Files.readString(file, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                issues.add(issue("UNREADABLE", relative, e.getMessage(),
                        "File could not be hashed; it is excluded from the registry and recorded as a gap"));
                continue;
            }

            String module = moduleOf(relative);
            FileRegistry.ObservedFile observed =
                    new FileRegistry.ObservedFile(module, relative, sha, role, size, content);
            FileRegistry.Attachment attachment = rescan
                    ? registry.reattach(observed, null)
                    : registry.allocate(observed);
            attachments.add(attachment(attachment));

            roleCounts.merge(role.name(), 1, Integer::sum);
            modules.computeIfAbsent(module, ModuleAccumulator::new).record(role, size);

            if (content != null) {
                collectSignals(attachment.fileId(), relative, module, content, signals);
                collectSecretExposure(attachment.fileId(), relative, content, issues);
            }
            if (role == FileRole.UNKNOWN && !relative.contains("/.")) {
                issues.add(issue("UNCLASSIFIED", relative,
                        "No role matched this file extension",
                        "Recorded as UNKNOWN; downstream stages treat it as opaque content"));
            }
        }

        String sealHash = registry.seal();

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR)
                .repoState(repoState(context, root))
                .stat("files_discovered", files.size())
                .stat("modules_discovered", modules.size())
                .stat("signals_recorded", signals.size())
                .stat("rescan", rescan)
                .stat("file_registry_seal", sealHash);
        if (issues.stream().anyMatch(i -> "UNREADABLE".equals(i.path("kind").asText()))) {
            envelope.gap(new Envelope.Gap("GAP-INV-001", "INVENTORY",
                    "One or more files could not be read during inventory",
                    "Those files have no identity and are invisible to every later stage"));
        }

        ObjectNode inventory = Json.obj();
        inventory.put("root", root.toString().replace((char) 92, '/'));
        inventory.put("file_count", files.size());
        inventory.set("role_counts", Json.toTree(roleCounts));
        ArrayNode moduleArray = Json.arr();
        modules.values().forEach(m -> moduleArray.add(m.toNode()));
        inventory.set("modules", moduleArray);
        inventory.set("excluded_directories", Json.toTree(EXCLUDED_DIRECTORIES));
        inventory.set("attachments", Json.toTree(attachments));
        ObjectNode inventoryArtifact = StageSupport.compose(envelope, inventory);
        StageSupport.validate(context, writer, "inventory/inventory-artifact.schema.json",
                "inventory-artifact.json", inventoryArtifact);
        writer.write("inventory-artifact.json", inventoryArtifact);

        ObjectNode registryNode = StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR).stat("seal_hash", sealHash),
                registry.toNode());
        StageSupport.validate(context, writer, "file-registry/file-registry.schema.json",
                "file-registry.json", registryNode);
        writer.write("file-registry.json", registryNode);

        ObjectNode signalNode = Json.obj();
        signalNode.put("signal_count", signals.size());
        signalNode.set("definitions", Json.toTree(SIGNALS.stream()
                .map(s -> Map.of("id", s.id(), "category", s.category(), "note", s.note()))
                .toList()));
        signalNode.set("signals", Json.toTree(signals));
        writer.write("inventory-signals.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), signalNode));

        ObjectNode issueNode = Json.obj();
        issueNode.put("issue_count", issues.size());
        issueNode.set("issues", Json.toTree(issues));
        writer.write("inventory-issues.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), issueNode));

        StageSupport.toEvidence(context, "inventory", inventoryArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Inventory artifacts failed schema validation; latest.json was not advanced",
                    writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.INVENTORY_COMPLETE,
                files.size() + " files inventoried");
        context.stateMachine().transition(RunState.FILE_REGISTRY_SEALED, "registry seal " + sealHash);
        context.runStateStore().updateState(context.run().runId(), RunState.FILE_REGISTRY_SEALED,
                "inventory complete");
        context.runStateStore().putAttribute(context.run().runId(), "file_registry_seal", sealHash);

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));
        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                files.size() + " files across " + modules.size() + " module(s); "
                        + signals.size() + " migration signal(s); registry sealed as " + sealHash
                        + " (content " + registry.contentManifestHash().substring(0, 16) + ")",
                List.of(), artifacts, hash);
    }

    // ------------------------------------------------------------------ internals

    private FileRegistry loadOrCreateRegistry(StageContext context) {
        JsonNode existing = StageSupport.optionalUpstream(context, OUTPUT_DIR, "file-registry.json");
        if (existing == null) {
            return new FileRegistry(context.policy().similarityThreshold());
        }
        return FileRegistry.fromNode(existing);
    }

    private List<Path> enumerate(Path root) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> !isExcluded(root, p))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot enumerate " + root, e);
        }
        return files;
    }

    private boolean isExcluded(Path root, Path candidate) {
        String relative = "/" + FileRegistry.normalize(root.relativize(candidate).toString());
        for (String excluded : EXCLUDED_DIRECTORIES) {
            if (relative.contains("/" + excluded + "/")) {
                return true;
            }
        }
        return relative.endsWith(".jar") || relative.endsWith(".class") || relative.endsWith(".war");
    }

    /** Deterministic role classification. Extension plus location, never content guessing. */
    public static FileRole classify(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);

        if (lower.contains("/target/generated-sources/") || lower.contains("/build/generated/")) {
            return FileRole.GENERATED;
        }
        if (fileName.equals("pom.xml")) {
            return FileRole.MAVEN_BUILD;
        }
        if (fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts")) {
            return fileName.startsWith("settings") ? FileRole.SETTINGS : FileRole.GRADLE_BUILD;
        }
        if (fileName.equals("mvnw") || fileName.equals("mvnw.cmd") || fileName.equals("gradlew")
                || fileName.equals("gradlew.bat") || fileName.equals("maven-wrapper.properties")
                || fileName.equals("gradle-wrapper.properties")) {
            return FileRole.SETTINGS;
        }
        if (fileName.endsWith(".java")) {
            return lower.contains("/src/test/") ? FileRole.JAVA_TEST : FileRole.JAVA_MAIN;
        }
        if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
            return FileRole.KOTLIN;
        }
        if (fileName.endsWith(".groovy")) {
            return FileRole.GROOVY;
        }
        if (fileName.endsWith(".properties")) {
            return FileRole.CONFIG_PROPERTIES;
        }
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            if (lower.contains("/.github/") || lower.contains("/.gitlab-ci")
                    || fileName.contains("pipeline") || fileName.contains("workflow")) {
                return FileRole.CI_PIPELINE;
            }
            if (lower.contains("/k8s/") || lower.contains("/kubernetes/")
                    || lower.contains("/charts/") || lower.contains("/manifests/")) {
                return FileRole.K8S;
            }
            return FileRole.CONFIG_YAML;
        }
        if (fileName.endsWith(".xml")) {
            return FileRole.CONFIG_XML;
        }
        if (fileName.endsWith(".sql")) {
            return lower.contains("/db/migration/") || lower.contains("/changelog/")
                    || fileName.matches("v\\d+.*") ? FileRole.SQL_MIGRATION : FileRole.RESOURCE;
        }
        if (fileName.equals("dockerfile") || fileName.startsWith("dockerfile.")
                || fileName.equals("containerfile")) {
            return FileRole.DOCKERFILE;
        }
        if (fileName.endsWith(".feature") || fileName.endsWith(".json") || fileName.endsWith(".txt")
                || fileName.endsWith(".md") || fileName.endsWith(".sh") || fileName.endsWith(".cmd")
                || fileName.endsWith(".factories") || fileName.endsWith(".imports")
                || fileName.startsWith(".git")) {
            return FileRole.RESOURCE;
        }
        return FileRole.UNKNOWN;
    }

    private static boolean isTextual(FileRole role) {
        return role != FileRole.UNKNOWN && role != FileRole.GENERATED;
    }

    private static String moduleOf(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? "." : relativePath.substring(0, slash);
    }

    private void collectSignals(String fileId, String path, String module, String content,
                                List<ObjectNode> signals) {
        for (SignalDefinition definition : SIGNALS) {
            var matcher = definition.pattern().matcher(content);
            int occurrences = 0;
            int firstLine = -1;
            while (matcher.find()) {
                occurrences++;
                if (firstLine < 0) {
                    firstLine = lineOf(content, matcher.start());
                }
                if (occurrences >= 200) {
                    break;
                }
            }
            if (occurrences > 0) {
                ObjectNode signal = Json.obj();
                signal.put("signal_id", definition.id());
                signal.put("category", definition.category());
                signal.put("note", definition.note());
                signal.put("file_id", fileId);
                signal.put("path", path);
                signal.put("module", module);
                signal.put("occurrences", occurrences);
                signal.put("first_line", firstLine);
                signal.put("classification", "SIGNAL_NOT_CONCLUSION");
                signals.add(signal);
            }
        }
    }

    /**
     * Records credential exposure found during inventory, without ever copying the value.
     * The corpus under analysis may legitimately contain such strings; the harness must notice them
     * and must not propagate them into artifacts (spec section 49).
     */
    private void collectSecretExposure(String fileId, String path, String content,
                                       List<ObjectNode> issues) {
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (SensitiveValues.looksSensitive(line)) {
                ObjectNode issue = issue("CREDENTIAL_IN_SOURCE", path,
                        "Line " + (i + 1) + " contains what looks like an embedded credential",
                        "Value is never stored in artifacts; only its presence and location are recorded");
                issue.put("file_id", fileId);
                issue.put("line", i + 1);
                issue.put("redacted_sample", SensitiveValues.redactLine(null, line.trim()));
                issue.put("evidence_policy", "NEVER_STORE_PLAINTEXT");
                issues.add(issue);
            }
        }
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static ObjectNode issue(String kind, String path, String detail, String handling) {
        ObjectNode node = Json.obj();
        node.put("kind", kind);
        node.put("path", path);
        node.put("detail", detail);
        node.put("handling", handling);
        return node;
    }

    private static ObjectNode attachment(FileRegistry.Attachment attachment) {
        ObjectNode node = Json.obj();
        node.put("file_id", attachment.fileId());
        node.put("path", attachment.path());
        node.put("previous_path", attachment.previousPath());
        node.put("rename_source", attachment.source().name());
        node.put("confidence", attachment.confidence());
        node.put("newly_allocated", attachment.newlyAllocated());
        node.put("rule", attachment.rule());
        return node;
    }

    private Envelope.RepoState repoState(StageContext context, Path root) {
        JsonNode bootstrap = StageSupport.optionalUpstream(context, "00-bootstrap", "bootstrap.json");
        if (bootstrap == null) {
            return new Envelope.RepoState("PLAIN_DIRECTORY", root.toString().replace((char) 92, '/'),
                    null, null, null, null);
        }
        JsonNode provenance = bootstrap.path("source_provenance");
        return new Envelope.RepoState(provenance.path("kind").asText("PLAIN_DIRECTORY"),
                root.toString().replace((char) 92, '/'),
                provenance.path("commitSha").asText(null), provenance.path("treeSha").asText(null),
                provenance.path("branch").asText(null),
                bootstrap.path("original_snapshot_hash").asText(null));
    }

    /** Per-module accumulation so the inventory artifact can answer module-level questions. */
    private static final class ModuleAccumulator {

        private final String moduleId;
        private final Map<String, Integer> roleCounts = new TreeMap<>();
        private int fileCount;
        private long totalBytes;

        private ModuleAccumulator(String moduleId) {
            this.moduleId = moduleId;
        }

        private void record(FileRole role, long size) {
            fileCount++;
            totalBytes += size;
            roleCounts.merge(role.name(), 1, Integer::sum);
        }

        private ObjectNode toNode() {
            ObjectNode node = Json.obj();
            node.put("module_id", moduleId);
            node.put("file_count", fileCount);
            node.put("total_bytes", totalBytes);
            node.put("java_main", roleCounts.getOrDefault(FileRole.JAVA_MAIN.name(), 0));
            node.put("java_test", roleCounts.getOrDefault(FileRole.JAVA_TEST.name(), 0));
            node.put("build_system", roleCounts.containsKey(FileRole.MAVEN_BUILD.name()) ? "MAVEN"
                    : roleCounts.containsKey(FileRole.GRADLE_BUILD.name()) ? "GRADLE" : "UNKNOWN");
            node.set("role_counts", Json.toTree(roleCounts));
            return node;
        }
    }
}
