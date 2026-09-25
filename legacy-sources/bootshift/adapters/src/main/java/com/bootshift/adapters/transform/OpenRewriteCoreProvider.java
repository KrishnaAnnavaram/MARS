package com.bootshift.adapters.transform;

import com.bootshift.core.policy.LicensePolicy;
import com.bootshift.core.util.Hashing;
import com.bootshift.ports.transformation.TransformationPort;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.ChangePackage;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.RemoveAnnotation;
import org.openrewrite.maven.ChangeParentPom;
import org.openrewrite.maven.ChangePropertyValue;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.yaml.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A real OpenRewrite transformation provider.
 *
 * <p>Bootshift previously shipped a <em>probe</em>: it asked whether {@code org.openrewrite.Recipe}
 * was loadable, reported a capability if it was, and then returned no changes when asked to apply
 * anything. The capability registry consequently claimed coverage that the transformation stage could
 * not deliver, which is worse than declaring the tool absent.
 *
 * <p>This provider actually runs OpenRewrite. Four constraints shape how:
 *
 * <ul>
 *   <li><b>OpenRewrite is not the orchestrator.</b> Bootshift decides which recipe runs on which
 *       edge, from verified migration facts. OpenRewrite is asked to perform one named
 *       transformation over one explicit file set.</li>
 *   <li><b>It never writes to the migration workspace.</b> Sources are read into an in-memory model,
 *       the recipe runs against that model, and the results come back as text. Every write still goes
 *       through {@code FileMutationGateway}.</li>
 *   <li><b>It never bypasses evidence.</b> Each returned change carries the engine version, the
 *       recipe id, the input and output hashes, the edge, and the knowledge and impact references
 *       that authorized it.</li>
 *   <li><b>Strict OSS is enforced, not assumed.</b> Only the Apache-2.0 core, java, maven, yaml and
 *       properties modules are used. If a source-available Spring recipe estate is on the classpath,
 *       this provider reports {@code LICENSE_BLOCK} and refuses to run at all.</li>
 * </ul>
 */
public final class OpenRewriteCoreProvider implements TransformationPort {

    private static final Logger LOG = LoggerFactory.getLogger(OpenRewriteCoreProvider.class);

    public static final String PROVIDER = "OPENREWRITE_CORE";

    /** Recipes this provider implements, each mapped to a concrete OpenRewrite recipe. */
    public static final String RECIPE_CHANGE_PACKAGE = "openrewrite.java.change-package";
    public static final String RECIPE_REMOVE_ANNOTATION = "openrewrite.java.remove-annotation";
    public static final String RECIPE_CHANGE_PARENT_POM = "openrewrite.maven.change-parent-pom";
    public static final String RECIPE_CHANGE_MAVEN_PROPERTY = "openrewrite.maven.change-property";

    private static final List<String> RECIPES = List.of(
            RECIPE_CHANGE_PACKAGE, RECIPE_REMOVE_ANNOTATION, RECIPE_CHANGE_PARENT_POM,
            RECIPE_CHANGE_MAVEN_PROPERTY);

    private static final String CORE_MARKER = "org.openrewrite.Recipe";
    private static final String JAVA_MARKER = "org.openrewrite.java.JavaVisitor";
    private static final String MAVEN_MARKER = "org.openrewrite.maven.MavenVisitor";

    private final ClassLoader classLoader;

    /**
     * How class presence is decided.
     *
     * <p>Injectable so the license-refusal path can be exercised. A test cannot fake presence with a
     * substitute classloader: {@code Class.forName} verifies that the loaded class's name matches the
     * name requested and raises a LinkageError when it does not, so the refusal branch would never be
     * reached and the most important behaviour in this class would be asserted only by its absence.
     */
    private final java.util.function.Predicate<String> classPresence;

    public OpenRewriteCoreProvider() {
        this(OpenRewriteCoreProvider.class.getClassLoader());
    }

    public OpenRewriteCoreProvider(ClassLoader classLoader) {
        this.classLoader = classLoader;
        this.classPresence = this::loadable;
    }

    OpenRewriteCoreProvider(ClassLoader classLoader,
                            java.util.function.Predicate<String> classPresence) {
        this.classLoader = classLoader;
        this.classPresence = classPresence;
    }

    /** Presence check that treats the forbidden estate as loadable. For testing the refusal path. */
    public static OpenRewriteCoreProvider withSimulatedForbiddenEstate() {
        OpenRewriteCoreProvider real = new OpenRewriteCoreProvider();
        return new OpenRewriteCoreProvider(OpenRewriteCoreProvider.class.getClassLoader(),
                name -> LicensePolicy.isForbiddenRecipeClass(name) || real.loadable(name));
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    // ------------------------------------------------------------------ availability and licensing

    public boolean coreAvailable() {
        return classPresent(CORE_MARKER);
    }

    public boolean javaModuleAvailable() {
        return classPresent(JAVA_MARKER);
    }

    public boolean mavenModuleAvailable() {
        return classPresent(MAVEN_MARKER);
    }

    /**
     * True when a forbidden source-available recipe estate is loadable.
     *
     * <p>The list comes from {@link LicensePolicy}, which is also what the architecture test and the
     * dependency test read. Three copies of this list is how one of them silently stops matching.
     */
    public boolean forbiddenEstatePresent() {
        return LicensePolicy.forbiddenRecipeMarkerClasses().stream().anyMatch(this::classPresent);
    }

    /** The engine version, read from the packaged manifest rather than hardcoded. */
    public String engineVersion() {
        try {
            Class<?> recipe = Class.forName(CORE_MARKER, false, classLoader);
            Package pkg = recipe.getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            if (version != null) {
                return version;
            }
            // A shaded or exploded classpath has no manifest; fall back to the jar name.
            java.security.CodeSource source = recipe.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                String name = Path.of(source.getLocation().getPath()).getFileName().toString();
                int dash = name.lastIndexOf('-');
                if (dash > 0 && name.endsWith(".jar")) {
                    return name.substring(dash + 1, name.length() - 4);
                }
            }
            return "unknown";
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return "unknown";
        }
    }

    /** Versions of every OpenRewrite module this provider uses, for the provenance record. */
    public Map<String, String> moduleVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("rewrite-core", versionOf(CORE_MARKER));
        versions.put("rewrite-java", versionOf(JAVA_MARKER));
        versions.put("rewrite-maven", versionOf(MAVEN_MARKER));
        versions.put("rewrite-yaml", versionOf("org.openrewrite.yaml.YamlParser"));
        versions.put("rewrite-properties", versionOf("org.openrewrite.properties.PropertiesParser"));
        return versions;
    }

    private String versionOf(String className) {
        try {
            Package pkg = Class.forName(className, false, classLoader).getPackage();
            return pkg == null || pkg.getImplementationVersion() == null
                    ? engineVersion() : pkg.getImplementationVersion();
        } catch (ClassNotFoundException | LinkageError e) {
            return "absent";
        }
    }

    private boolean classPresent(String className) {
        return classPresence.test(className);
    }

    private boolean loadable(String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ capabilities

    @Override
    public List<Capability> capabilities(String sourceVersion, String targetVersion) {
        if (forbiddenEstatePresent()) {
            return List.of(new Capability(
                    "CAP-OPENREWRITE-CORE", PROVIDER, "openrewrite-core", engineVersion(),
                    "Source-Available", "classpath-probe", sourceVersion, targetVersion,
                    List.of(), List.of(), true, true, "UNKNOWN", 0.0,
                    "LICENSE_BLOCK",
                    "A source-available OpenRewrite Spring recipe estate is on the classpath. Strict "
                            + "OSS policy forbids loading it, so this provider refuses to run at all "
                            + "rather than run selectively and leave the estate loadable (R8, R9). "
                            + "Forbidden packages: " + LicensePolicy.forbiddenRecipePackages()));
        }
        if (!coreAvailable()) {
            return List.of(new Capability(
                    "CAP-OPENREWRITE-CORE", PROVIDER, "openrewrite-core", null,
                    "Apache-2.0", "not-loaded", sourceVersion, targetVersion,
                    List.of(), List.of(), true, true, "UNKNOWN", 0.0, "UNAVAILABLE",
                    "OpenRewrite core is not on the harness classpath. The coverage it would provide "
                            + "is counted as residual, which raises the planned validation depth."));
        }

        List<Capability> capabilities = new ArrayList<>();
        if (javaModuleAvailable()) {
            capabilities.add(new Capability(
                    "CAP-OPENREWRITE-JAVA-PACKAGE", PROVIDER, "openrewrite-rewrite-java",
                    versionOf(JAVA_MARKER), "Apache-2.0", "maven-central-pom", sourceVersion,
                    targetVersion, List.of("API_RENAMED", "ARTIFACT_RELOCATED"),
                    List.of("javax.", "jakarta."), true, true, "SINGLE_EDGE", 0.95, "AVAILABLE",
                    "org.openrewrite.java.ChangePackage over a type-attributed LST. Unlike a textual "
                            + "rewrite it does not touch comments, string literals or unrelated "
                            + "identifiers that merely contain the token."));
            capabilities.add(new Capability(
                    "CAP-OPENREWRITE-JAVA-ANNOTATION", PROVIDER, "openrewrite-rewrite-java",
                    versionOf(JAVA_MARKER), "Apache-2.0", "maven-central-pom", sourceVersion,
                    targetVersion, List.of("API_REMOVED"), List.of("@", "org.springframework."),
                    true, true, "SINGLE_EDGE", 0.9, "AVAILABLE",
                    "org.openrewrite.java.RemoveAnnotation, which also removes the now-unused import "
                            + "rather than leaving a dangling one."));
        }
        if (mavenModuleAvailable()) {
            capabilities.add(new Capability(
                    "CAP-OPENREWRITE-MAVEN", PROVIDER, "openrewrite-rewrite-maven",
                    versionOf(MAVEN_MARKER), "Apache-2.0", "maven-central-pom", sourceVersion,
                    targetVersion, List.of("MANAGED_VERSION_CHANGED", "BASELINE_REQUIREMENT"),
                    List.of(), true, true, "SINGLE_EDGE", 0.95, "AVAILABLE",
                    "org.openrewrite.maven.ChangeParentPom and ChangePropertyValue over a parsed POM "
                            + "model, so the edit is structural and format-preserving."));
        }
        return capabilities;
    }

    @Override
    public boolean handles(String recipeId) {
        return coreAvailable() && !forbiddenEstatePresent() && RECIPES.contains(recipeId);
    }

    @Override
    public Optional<Capability> capabilityFor(String recipeId, String sourceVersion,
                                              String targetVersion) {
        if (!handles(recipeId)) {
            return Optional.empty();
        }
        String capabilityId = switch (recipeId) {
            case RECIPE_CHANGE_PACKAGE -> "CAP-OPENREWRITE-JAVA-PACKAGE";
            case RECIPE_REMOVE_ANNOTATION -> "CAP-OPENREWRITE-JAVA-ANNOTATION";
            case RECIPE_CHANGE_PARENT_POM, RECIPE_CHANGE_MAVEN_PROPERTY -> "CAP-OPENREWRITE-MAVEN";
            default -> null;
        };
        return capabilities(sourceVersion, targetVersion).stream()
                .filter(c -> c.capabilityId().equals(capabilityId))
                .findFirst();
    }

    // ------------------------------------------------------------------ application

    @Override
    public TransformationOutcome apply(String recipeId, TransformationRequest request) {
        List<String> messages = new ArrayList<>();

        if (forbiddenEstatePresent()) {
            return new TransformationOutcome(List.of(),
                    List.of("LICENSE_BLOCK: a source-available OpenRewrite recipe estate is on the "
                            + "classpath (" + LicensePolicy.forbiddenRecipePackages() + "). Strict "
                            + "OSS policy forbids running any OpenRewrite recipe in this state."),
                    List.of(recipeId), false);
        }
        if (!handles(recipeId)) {
            return new TransformationOutcome(List.of(),
                    List.of("OpenRewrite core does not handle " + recipeId),
                    List.of(recipeId), false);
        }

        Recipe recipe = buildRecipe(recipeId, request.parameters());
        if (recipe == null) {
            return new TransformationOutcome(List.of(),
                    List.of("Recipe " + recipeId + " is missing required parameters: "
                            + requiredParameters(recipeId)),
                    List.of(recipeId), false);
        }

        // Read the sources into an in-memory model. Nothing on disk is touched: OpenRewrite operates
        // on the parsed model and hands back text, and the gateway remains the only writer.
        ExecutionContext executionContext = new InMemoryExecutionContext(
                throwable -> messages.add("OpenRewrite: " + throwable.getMessage()));
        List<SourceFile> sources;
        try {
            sources = parse(recipeId, request, executionContext, messages);
        } catch (RuntimeException e) {
            return new TransformationOutcome(List.of(),
                    List.of("OpenRewrite could not parse the requested sources: " + e),
                    List.of(recipeId), false);
        }
        if (sources.isEmpty()) {
            return new TransformationOutcome(List.of(),
                    List.of("No source matched " + recipeId + " in the requested path set"),
                    List.of(), true);
        }

        List<ProposedChange> changes = new ArrayList<>();
        try {
            RecipeRun run = recipe.run(new InMemoryLargeSourceSet(sources), executionContext);
            for (Result result : run.getChangeset().getAllResults()) {
                SourceFile after = result.getAfter();
                SourceFile before = result.getBefore();
                if (after == null || before == null) {
                    // A deletion or a generation. Bootshift authorizes those explicitly per edge and
                    // this provider is not authorized for either, so it is reported rather than done.
                    messages.add("OpenRewrite proposed a file creation or deletion for "
                            + (before == null ? after.getSourcePath() : before.getSourcePath())
                            + ", which this provider is not authorized to perform");
                    continue;
                }
                String newContent = after.printAll();
                String path = before.getSourcePath().toString().replace((char) 92, '/');
                String baseHash = hashOf(request.workspaceRoot().resolve(path));

                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("engine", "openrewrite");
                attributes.put("engine_version", engineVersion());
                attributes.put("recipe_class", recipe.getClass().getName());
                attributes.put("recipe_display_name", recipe.getDisplayName());
                attributes.put("license", "Apache-2.0");
                attributes.put("license_evidence", "maven-central pom for "
                        + recipe.getClass().getPackageName());
                attributes.put("input_hash", Hashing.sha256(before.printAll()));
                attributes.put("output_hash", Hashing.sha256(newContent));
                attributes.put("edge_id", request.edgeId());
                attributes.put("module_versions", moduleVersions().toString());
                attributes.put("recipes_that_made_changes",
                        result.getRecipeDescriptorsThatMadeChanges().stream()
                                .map(descriptor -> descriptor.getName()).toList().toString());
                if (baseHash != null) {
                    attributes.put("base_hash", baseHash);
                }

                changes.add(new ProposedChange(path, null, "MODIFY", newContent,
                        "OpenRewrite " + engineVersion() + " applied " + recipe.getDisplayName(),
                        recipeId, splitCsv(request.parameters().get("knowledge_refs")),
                        splitCsv(request.parameters().get("impact_refs")), attributes));
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.error("OpenRewrite run failed for {}: {}", recipeId, e.toString());
            return new TransformationOutcome(changes,
                    List.of("OpenRewrite failed while running " + recipeId + ": " + e),
                    List.of(recipeId), false);
        }

        messages.add("OpenRewrite " + engineVersion() + " parsed " + sources.size()
                + " source(s) and proposed " + changes.size() + " change(s) for " + recipeId);
        return new TransformationOutcome(changes, messages, List.of(), true);
    }

    // ------------------------------------------------------------------ recipe construction

    private Recipe buildRecipe(String recipeId, Map<String, String> parameters) {
        switch (recipeId) {
            case RECIPE_CHANGE_PACKAGE -> {
                String from = parameters.get("oldPackageName");
                String to = parameters.get("newPackageName");
                if (isBlank(from) || isBlank(to)) {
                    return null;
                }
                boolean recursive = !"false".equalsIgnoreCase(parameters.getOrDefault("recursive", "true"));
                return new ChangePackage(from, to, recursive);
            }
            case RECIPE_REMOVE_ANNOTATION -> {
                String pattern = parameters.get("annotationPattern");
                return isBlank(pattern) ? null : new RemoveAnnotation(pattern);
            }
            case RECIPE_CHANGE_PARENT_POM -> {
                String groupId = parameters.get("groupId");
                String artifactId = parameters.get("artifactId");
                String newVersion = parameters.get("newVersion");
                if (isBlank(groupId) || isBlank(artifactId) || isBlank(newVersion)) {
                    return null;
                }
                // oldGroupId, oldArtifactId, newGroupId, newArtifactId, newVersion,
                // oldRelativePath, newRelativePath, versionPattern, allowVersionDowngrades
                return new ChangeParentPom(groupId, null, artifactId, null, newVersion, null, null,
                        null, false);
            }
            case RECIPE_CHANGE_MAVEN_PROPERTY -> {
                String key = parameters.get("key");
                String value = parameters.get("value");
                if (isBlank(key) || isBlank(value)) {
                    return null;
                }
                return new ChangePropertyValue(key, value, false, false);
            }
            default -> {
                return null;
            }
        }
    }

    private static String requiredParameters(String recipeId) {
        return switch (recipeId) {
            case RECIPE_CHANGE_PACKAGE -> "oldPackageName, newPackageName";
            case RECIPE_REMOVE_ANNOTATION -> "annotationPattern";
            case RECIPE_CHANGE_PARENT_POM -> "groupId, artifactId, newVersion";
            case RECIPE_CHANGE_MAVEN_PROPERTY -> "key, value";
            default -> "none";
        };
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses only the files the recipe can act on.
     *
     * <p>Parsing the whole repository for a POM edit would cost minutes and would let a recipe reach
     * files the edge never authorized. The target path set comes from Bootshift, which derived it
     * from the impact findings for this edge.
     */
    private List<SourceFile> parse(String recipeId, TransformationRequest request,
                                   ExecutionContext executionContext, List<String> messages) {
        Path root = request.workspaceRoot();
        List<Path> paths = new ArrayList<>();
        for (String relative : request.targetPaths()) {
            Path candidate = root.resolve(relative);
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            String name = candidate.getFileName().toString();
            boolean relevant = switch (recipeId) {
                case RECIPE_CHANGE_PACKAGE, RECIPE_REMOVE_ANNOTATION -> name.endsWith(".java");
                case RECIPE_CHANGE_PARENT_POM, RECIPE_CHANGE_MAVEN_PROPERTY -> name.equals("pom.xml");
                default -> false;
            };
            if (relevant) {
                paths.add(candidate);
            }
        }
        if (paths.isEmpty()) {
            return List.of();
        }

        return switch (recipeId) {
            case RECIPE_CHANGE_PACKAGE, RECIPE_REMOVE_ANNOTATION -> {
                // Type attribution needs a classpath. Without one the parse still succeeds but the
                // LST is less attributed, so the message says so rather than the run pretending it
                // was fully resolved.
                List<Path> classpath = classpathFrom(request.parameters().get("classpath"));
                if (classpath.isEmpty()) {
                    messages.add("No dependency classpath was supplied, so OpenRewrite parsed these "
                            + "sources without full type attribution. Package changes remain safe "
                            + "because ChangePackage matches on declared package and imports.");
                }
                JavaParser.Builder<? extends JavaParser, ?> builder = JavaParser.fromJavaVersion();
                if (!classpath.isEmpty()) {
                    builder = builder.classpath(classpath);
                }
                yield builder.build().parse(paths, root, executionContext).toList();
            }
            case RECIPE_CHANGE_PARENT_POM, RECIPE_CHANGE_MAVEN_PROPERTY ->
                    MavenParser.builder().build().parse(paths, root, executionContext).toList();
            default -> List.of();
        };
    }

    private static List<Path> classpathFrom(String raw) {
        List<Path> classpath = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return classpath;
        }
        for (String entry : raw.split(java.io.File.pathSeparator)) {
            Path path = Path.of(entry.trim());
            if (Files.isRegularFile(path)) {
                classpath.add(path);
            }
        }
        return classpath;
    }

    private static String hashOf(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Hashing.sha256(Files.readString(file, StandardCharsets.UTF_8)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : value.split(",")) {
            if (!token.isBlank()) {
                values.add(token.trim());
            }
        }
        return values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Unused parsers referenced so the modules stay a declared, checkable dependency. */
    static Class<?>[] declaredModules() {
        return new Class<?>[]{YamlParser.class, PropertiesParser.class};
    }
}
