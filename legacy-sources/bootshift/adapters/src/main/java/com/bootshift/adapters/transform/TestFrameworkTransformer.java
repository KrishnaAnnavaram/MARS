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
 * Test-infrastructure transformations (spec section 22, "test-infrastructure migration").
 *
 * <p>These are modelled as their own PREPARATORY edge because pass/fail/skip semantics must be shown
 * to survive <em>before</em> the framework migration lands: otherwise a later regression cannot be
 * distinguished from a test-runner artefact.
 *
 * <p>Covers JUnit 4 to Jupiter for the mechanical constructs, and the Spring Boot 3.4 replacement of
 * {@code @MockBean}/{@code @SpyBean} with {@code @MockitoBean}/{@code @MockitoSpyBean}.
 */
public final class TestFrameworkTransformer implements TransformationPort {

    public static final String PROVIDER = "BOOTSHIFT_TEST_FRAMEWORK";
    public static final String RECIPE_JUNIT4_TO_JUPITER = "test.junit4-to-jupiter";
    public static final String RECIPE_MOCKBEAN = "test.mockbean-to-mockitobean";

    /** Ordered so longer, more specific imports are rewritten before their prefixes. */
    private static final Map<String, String> JUNIT_IMPORTS = new LinkedHashMap<>();
    private static final Map<String, String> JUNIT_ANNOTATIONS = new LinkedHashMap<>();
    private static final Map<String, String> MOCKBEAN = new LinkedHashMap<>();

    static {
        JUNIT_IMPORTS.put("org.junit.jupiter.api.Assertions", "org.junit.jupiter.api.Assertions");
        JUNIT_IMPORTS.put("org.junit.Assert", "org.junit.jupiter.api.Assertions");
        JUNIT_IMPORTS.put("org.junit.Before", "org.junit.jupiter.api.BeforeEach");
        JUNIT_IMPORTS.put("org.junit.After", "org.junit.jupiter.api.AfterEach");
        JUNIT_IMPORTS.put("org.junit.BeforeClass", "org.junit.jupiter.api.BeforeAll");
        JUNIT_IMPORTS.put("org.junit.AfterClass", "org.junit.jupiter.api.AfterAll");
        JUNIT_IMPORTS.put("org.junit.Ignore", "org.junit.jupiter.api.Disabled");
        JUNIT_IMPORTS.put("org.junit.Test", "org.junit.jupiter.api.Test");

        JUNIT_ANNOTATIONS.put("@BeforeClass", "@BeforeAll");
        JUNIT_ANNOTATIONS.put("@AfterClass", "@AfterAll");
        JUNIT_ANNOTATIONS.put("@Before", "@BeforeEach");
        JUNIT_ANNOTATIONS.put("@After", "@AfterEach");
        JUNIT_ANNOTATIONS.put("@Ignore", "@Disabled");

        MOCKBEAN.put("org.springframework.boot.test.mock.mockito.MockBean",
                "org.springframework.test.context.bean.override.mockito.MockitoBean");
        MOCKBEAN.put("org.springframework.boot.test.mock.mockito.SpyBean",
                "org.springframework.test.context.bean.override.mockito.MockitoSpyBean");
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public List<Capability> capabilities(String sourceVersion, String targetVersion) {
        List<Capability> capabilities = new ArrayList<>();
        capabilities.add(new Capability(
                "CAP-JUNIT4-JUPITER", PROVIDER, "bootshift-test-framework-transformer", "1.0.0",
                "MIT", "harness-owned", "*", "*",
                List.of("API_REMOVED", "API_RENAMED"),
                List.of("org.junit.", "junit."), true, true, "PREPARATORY_EDGE", 0.85,
                "AVAILABLE",
                "Mechanical JUnit 4 constructs only. Rules, runners and ExpectedException are reported "
                        + "as residual because they have no safe mechanical equivalent."));
        capabilities.add(new Capability(
                "CAP-MOCKBEAN-MOCKITOBEAN", PROVIDER, "bootshift-test-framework-transformer", "1.0.0",
                "MIT", "harness-owned", "3.0.0", "3.4.0",
                List.of("API_REMOVED", "API_RENAMED"),
                List.of("org.springframework.boot.test.mock.mockito."), true, true, "SINGLE_EDGE", 1.0, "AVAILABLE",
                "Replaces MockBean and SpyBean with the Spring Framework bean-override annotations."));
        return capabilities;
    }

    @Override
    public boolean handles(String recipeId) {
        return RECIPE_JUNIT4_TO_JUPITER.equals(recipeId) || RECIPE_MOCKBEAN.equals(recipeId);
    }

    @Override
    public java.util.Optional<Capability> capabilityFor(String recipeId, String sourceVersion,
                                                        String targetVersion) {
        String capabilityId = RECIPE_JUNIT4_TO_JUPITER.equals(recipeId) ? "CAP-JUNIT4-JUPITER"
                : RECIPE_MOCKBEAN.equals(recipeId) ? "CAP-MOCKBEAN-MOCKITOBEAN" : null;
        if (capabilityId == null) {
            return java.util.Optional.empty();
        }
        return capabilities(sourceVersion, targetVersion).stream()
                .filter(c -> c.capabilityId().equals(capabilityId))
                .findFirst();
    }

    @Override
    public TransformationOutcome apply(String recipeId, TransformationRequest request) {
        List<ProposedChange> changes = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        List<String> unhandled = new ArrayList<>();

        for (String relativePath : request.targetPaths()) {
            if (!relativePath.endsWith(".java")) {
                continue;
            }
            Path file = request.workspaceRoot().resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String original = read(file);
            Rewrite rewrite = RECIPE_JUNIT4_TO_JUPITER.equals(recipeId)
                    ? junit4ToJupiter(original)
                    : mockBeanToMockitoBean(original);
            if (!rewrite.residual().isEmpty()) {
                messages.add(relativePath + " retains unmigratable constructs: "
                        + String.join(", ", rewrite.residual()));
                unhandled.addAll(rewrite.residual());
            }
            if (rewrite.changed() > 0) {
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("transformer", PROVIDER);
                attributes.put("base_hash", com.bootshift.core.util.Hashing.sha256(original));
                attributes.put("replacements", String.valueOf(rewrite.changed()));
                changes.add(new ProposedChange(relativePath, null, "MODIFY", rewrite.content(),
                        "Applied " + rewrite.changed() + " test-infrastructure replacement(s)",
                        recipeId, splitCsv(request.parameters().get("knowledge_refs")),
                        splitCsv(request.parameters().get("impact_refs")), attributes));
            }
        }
        return new TransformationOutcome(changes, messages, unhandled, true);
    }

    public record Rewrite(String content, int changed, List<String> residual) {
    }

    /**
     * Mechanical JUnit 4 to Jupiter rewrite.
     *
     * <p>What is deliberately <em>not</em> rewritten: {@code @Rule}, {@code @ClassRule},
     * {@code @RunWith} with a custom runner, {@code ExpectedException}, and
     * {@code @Test(expected=...)} with a timeout. These change semantics and are returned as
     * residual so the planner raises validation depth instead of the transformer guessing.
     */
    public static Rewrite junit4ToJupiter(String source) {
        String result = source;
        int changed = 0;
        List<String> residual = new ArrayList<>();

        for (Map.Entry<String, String> entry : JUNIT_IMPORTS.entrySet()) {
            String from = "import " + entry.getKey() + ";";
            String to = "import " + entry.getValue() + ";";
            if (result.contains(from) && !from.equals(to)) {
                result = result.replace(from, to);
                changed++;
            }
        }
        if (result.contains("import org.junit.Assert.")) {
            result = result.replace("import org.junit.Assert.", "import org.junit.jupiter.api.Assertions.");
            changed++;
        }
        if (result.contains("import static org.junit.Assert.")) {
            result = result.replace("import static org.junit.Assert.",
                    "import static org.junit.jupiter.api.Assertions.");
            changed++;
        }
        for (Map.Entry<String, String> entry : JUNIT_ANNOTATIONS.entrySet()) {
            Pattern pattern = Pattern.compile(Pattern.quote(entry.getKey()) + "\\b");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll(Matcher.quoteReplacement(entry.getValue()));
                changed++;
            }
        }
        if (result.contains("@RunWith(SpringRunner.class)")) {
            result = result.replace("@RunWith(SpringRunner.class)", "@ExtendWith(SpringExtension.class)")
                    .replace("import org.springframework.test.context.junit4.SpringRunner;",
                            "import org.springframework.test.context.junit.jupiter.SpringExtension;")
                    .replace("import org.junit.runner.RunWith;",
                            "import org.junit.jupiter.api.extension.ExtendWith;");
            changed++;
        }
        if (result.contains("@RunWith(MockitoJUnitRunner.class)")) {
            result = result.replace("@RunWith(MockitoJUnitRunner.class)",
                            "@ExtendWith(MockitoExtension.class)")
                    .replace("import org.mockito.junit.MockitoJUnitRunner;",
                            "import org.mockito.junit.jupiter.MockitoExtension;")
                    .replace("import org.junit.runner.RunWith;",
                            "import org.junit.jupiter.api.extension.ExtendWith;");
            changed++;
        }

        if (result.contains("@Rule") || result.contains("@ClassRule")) {
            residual.add("junit4-rules");
        }
        if (result.contains("ExpectedException")) {
            residual.add("expected-exception-rule");
        }
        if (Pattern.compile("@Test\\s*\\(").matcher(result).find()) {
            residual.add("parameterised-test-attributes");
        }
        if (result.contains("@RunWith(")) {
            residual.add("custom-junit4-runner");
        }
        return new Rewrite(result, changed, residual);
    }

    /** MockBean and SpyBean to the Spring Framework bean-override annotations (Boot 3.4). */
    public static Rewrite mockBeanToMockitoBean(String source) {
        String result = source;
        int changed = 0;
        for (Map.Entry<String, String> entry : MOCKBEAN.entrySet()) {
            String fromImport = "import " + entry.getKey() + ";";
            String toImport = "import " + entry.getValue() + ";";
            if (result.contains(fromImport)) {
                result = result.replace(fromImport, toImport);
                changed++;
            }
        }
        if (result.contains("@MockBean")) {
            result = result.replaceAll("@MockBean\\b", "@MockitoBean");
            changed++;
        }
        if (result.contains("@SpyBean")) {
            result = result.replaceAll("@SpyBean\\b", "@MockitoSpyBean");
            changed++;
        }
        return new Rewrite(result, changed, List.of());
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
