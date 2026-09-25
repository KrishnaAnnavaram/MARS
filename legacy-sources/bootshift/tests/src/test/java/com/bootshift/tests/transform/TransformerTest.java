package com.bootshift.tests.transform;

import com.bootshift.adapters.transform.ConfigurationPropertyTransformer;
import com.bootshift.adapters.transform.JakartaNamespaceTransformer;
import com.bootshift.adapters.transform.RemovedAnnotationTransformer;
import com.bootshift.adapters.transform.MavenPomTransformer;
import com.bootshift.adapters.transform.OpenRewriteCoreProvider;
import com.bootshift.core.policy.LicensePolicy;
import com.bootshift.adapters.transform.TestFrameworkTransformer;
import com.bootshift.ports.transformation.TransformationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Deterministic transformer tests (spec section 57: recipe and idempotency tests).
 *
 * <p>The most important cases here are the negative ones. A blanket {@code javax -> jakarta} rewrite
 * or an over-eager JUnit conversion produces a repository that no longer compiles for reasons
 * unrelated to the migration, and the harness would then spend its repair budget undoing itself.
 */
class TransformerTest {

    // ------------------------------------------------------------------ Jakarta namespace

    @Test
    @DisplayName("relocated Jakarta EE packages are rewritten")
    void relocatedPackagesAreRewritten() {
        String source = """
                package com.example;
                import javax.persistence.Entity;
                import javax.persistence.Id;
                import javax.servlet.http.HttpServletRequest;
                import javax.validation.constraints.NotNull;
                @Entity
                public class Employee {
                    @Id private String id;
                }
                """;

        JakartaNamespaceTransformer.Rewrite rewrite = JakartaNamespaceTransformer.rewrite(source);

        assertThat(rewrite.changed()).isGreaterThan(0);
        assertThat(rewrite.content()).contains("import jakarta.persistence.Entity;");
        assertThat(rewrite.content()).contains("import jakarta.servlet.http.HttpServletRequest;");
        assertThat(rewrite.content()).contains("import jakarta.validation.constraints.NotNull;");
        assertThat(rewrite.content()).doesNotContain("javax.persistence");
    }

    @Test
    @DisplayName("javax packages that did not move are left alone")
    void preservedPackagesAreNotRewritten() {
        String source = """
                package com.example;
                import javax.sql.DataSource;
                import javax.net.ssl.SSLContext;
                import javax.crypto.Cipher;
                import javax.naming.InitialContext;
                import javax.xml.parsers.DocumentBuilder;
                import javax.management.MBeanServer;
                class Infrastructure {}
                """;

        JakartaNamespaceTransformer.Rewrite rewrite = JakartaNamespaceTransformer.rewrite(source);

        assertThat(rewrite.changed()).isZero();
        assertThat(rewrite.content()).isEqualTo(source);
        assertThat(rewrite.content()).contains("javax.sql.DataSource");
        assertThat(rewrite.content()).contains("javax.crypto.Cipher");
        assertThat(rewrite.content()).contains("javax.xml.parsers.DocumentBuilder");
    }

    @Test
    @DisplayName("javax.xml.bind moves while javax.xml.parsers stays")
    void xmlPackagesAreDistinguished() {
        String source = "import javax.xml.bind.JAXBContext;\nimport javax.xml.parsers.SAXParser;\n";

        JakartaNamespaceTransformer.Rewrite rewrite = JakartaNamespaceTransformer.rewrite(source);

        assertThat(rewrite.content()).contains("jakarta.xml.bind.JAXBContext");
        assertThat(rewrite.content()).contains("javax.xml.parsers.SAXParser");
    }

    @Test
    @DisplayName("the Jakarta rewrite is idempotent")
    void jakartaRewriteIsIdempotent() {
        String source = "import javax.persistence.Entity;\nimport javax.servlet.Filter;\n";

        JakartaNamespaceTransformer.Rewrite once = JakartaNamespaceTransformer.rewrite(source);
        JakartaNamespaceTransformer.Rewrite twice = JakartaNamespaceTransformer.rewrite(once.content());

        assertThat(twice.changed()).isZero();
        assertThat(twice.content()).isEqualTo(once.content());
    }

    // ------------------------------------------------------------------ Maven descriptor

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.7.12</version>
                </parent>
                <artifactId>employee-service</artifactId>
                <version>0.0.1-SNAPSHOT</version>
                <properties>
                    <java.version>17</java.version>
                    <spring-cloud.version>2021.0.7</spring-cloud.version>
                </properties>
                <dependencies>
                    <!-- keep this comment -->
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                </dependencies>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-dependencies</artifactId>
                            <version>2021.0.7</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

    @Test
    @DisplayName("the parent version changes without disturbing the rest of the descriptor")
    void parentVersionIsChangedSurgically() {
        String updated = MavenPomTransformer.replaceFirstTag(
                POM.substring(POM.indexOf("<parent>"), POM.indexOf("</parent>")),
                "version", "3.5.16");

        assertThat(updated).contains("<version>3.5.16</version>");
        assertThat(updated).contains("spring-boot-starter-parent");
    }

    @Test
    @DisplayName("only the first matching element is replaced")
    void replaceFirstTagIsPrecise() {
        String xml = "<a><version>1</version></a><b><version>2</version></b>";

        String updated = MavenPomTransformer.replaceFirstTag(xml, "version", "9");

        assertThat(updated).isEqualTo("<a><version>9</version></a><b><version>2</version></b>");
    }

    @Test
    @DisplayName("the Maven transformer advertises its capabilities with license evidence")
    void mavenCapabilitiesCarryLicenseEvidence() {
        List<TransformationPort.Capability> capabilities =
                new MavenPomTransformer().capabilities("2.7.12", "3.5.16");

        assertThat(capabilities).isNotEmpty();
        assertThat(capabilities).allSatisfy(capability -> {
            // Harness-owned code is MIT, matching the repository LICENSE. This deliberately differs
            // from OpenRewrite's Apache-2.0 and from every application dependency's own licence:
            // the four classifications are kept separate and are never harmonised.
            assertThat(capability.licenseSpdx()).isEqualTo("MIT");
            assertThat(capability.licenseEvidenceRef()).isEqualTo("harness-owned");
            assertThat(capability.deterministic()).isTrue();
            assertThat(capability.status()).isEqualTo("AVAILABLE");
        });
    }

    // ------------------------------------------------------------------ configuration properties

    @Test
    @DisplayName("a renamed property is rewritten and comments survive")
    void propertyRenameKeepsComments() {
        String properties = """
                # logging configuration
                logging.file=/var/log/app.log
                server.port=8080
                """;
        List<ConfigurationPropertyTransformer.PropertyRule> rules = List.of(
                new ConfigurationPropertyTransformer.PropertyRule("logging.file", "logging.file.name",
                        "RENAME", "renamed in Spring Boot 2.2", "metadata"));

        ConfigurationPropertyTransformer.Result result =
                ConfigurationPropertyTransformer.migrateProperties(properties, rules);

        assertThat(result.applied()).containsExactly("logging.file");
        assertThat(result.content()).contains("# logging configuration");
        assertThat(result.content()).contains("logging.file.name=/var/log/app.log");
        assertThat(result.content()).contains("server.port=8080");
    }

    @Test
    @DisplayName("a removed property is commented out with its reason, not deleted")
    void propertyRemovalIsReviewable() {
        String properties = "spring.gone.setting=true\n";
        List<ConfigurationPropertyTransformer.PropertyRule> rules = List.of(
                new ConfigurationPropertyTransformer.PropertyRule("spring.gone.setting", null,
                        "REMOVE", "no replacement in the target version", "metadata"));

        ConfigurationPropertyTransformer.Result result =
                ConfigurationPropertyTransformer.migrateProperties(properties, rules);

        assertThat(result.content()).contains("# [bootshift] removed property spring.gone.setting");
        assertThat(result.content()).contains("no replacement in the target version");
        assertThat(result.content()).contains("#spring.gone.setting=true");
    }

    @Test
    @DisplayName("commented-out properties are left alone")
    void commentedPropertiesAreUntouched() {
        String properties = "#logging.file=/var/log/app.log\n";
        List<ConfigurationPropertyTransformer.PropertyRule> rules = List.of(
                new ConfigurationPropertyTransformer.PropertyRule("logging.file", "logging.file.name",
                        "RENAME", null, "metadata"));

        ConfigurationPropertyTransformer.Result result =
                ConfigurationPropertyTransformer.migrateProperties(properties, rules);

        assertThat(result.applied()).isEmpty();
        assertThat(result.content()).isEqualTo(properties);
    }

    @Test
    @DisplayName("nested YAML keys are matched by their full dotted path")
    void yamlNestedKeysAreMigrated() {
        String yaml = """
                logging:
                  file: /var/log/app.log
                server:
                  port: 8080
                """;
        List<ConfigurationPropertyTransformer.PropertyRule> rules = List.of(
                new ConfigurationPropertyTransformer.PropertyRule("logging.file", "logging.file.name",
                        "RENAME", null, "metadata"));

        ConfigurationPropertyTransformer.Result result =
                ConfigurationPropertyTransformer.migrateYaml(yaml, rules);

        assertThat(result.applied()).containsExactly("logging.file");
        assertThat(result.content()).contains("logging.file.name: /var/log/app.log");
        assertThat(result.content()).contains("port: 8080");
    }

    @Test
    @DisplayName("with no generated rules the transformer reports residual instead of pretending")
    void noRulesMeansResidual() {
        ConfigurationPropertyTransformer transformer = new ConfigurationPropertyTransformer(List.of());

        List<TransformationPort.Capability> capabilities = transformer.capabilities("2.7.12", "3.5.16");

        assertThat(capabilities).hasSize(1);
        assertThat(capabilities.get(0).status()).isEqualTo("NO_RULES_GENERATED");
        assertThat(capabilities.get(0).confidence()).isZero();
    }

    // ------------------------------------------------------------------ test infrastructure

    @Test
    @DisplayName("mechanical JUnit 4 constructs are converted")
    void junit4MechanicalConversion() {
        String source = """
                package com.example;
                import org.junit.Test;
                import org.junit.Before;
                import org.junit.After;
                import static org.junit.Assert.assertEquals;
                public class ServiceTest {
                    @Before public void setUp() {}
                    @After public void tearDown() {}
                    @Test public void works() { assertEquals(1, 1); }
                }
                """;

        TestFrameworkTransformer.Rewrite rewrite = TestFrameworkTransformer.junit4ToJupiter(source);

        assertThat(rewrite.content()).contains("import org.junit.jupiter.api.Test;");
        assertThat(rewrite.content()).contains("@BeforeEach");
        assertThat(rewrite.content()).contains("@AfterEach");
        assertThat(rewrite.content()).contains("import static org.junit.jupiter.api.Assertions.");
        assertThat(rewrite.residual()).isEmpty();
    }

    @Test
    @DisplayName("constructs with no safe mechanical equivalent are reported as residual")
    void junit4ResidualIsReported() {
        String source = """
                package com.example;
                import org.junit.Rule;
                import org.junit.rules.ExpectedException;
                public class RuleTest {
                    @Rule public ExpectedException thrown = ExpectedException.none();
                }
                """;

        TestFrameworkTransformer.Rewrite rewrite = TestFrameworkTransformer.junit4ToJupiter(source);

        assertThat(rewrite.residual()).contains("junit4-rules", "expected-exception-rule");
    }

    @Test
    @DisplayName("MockBean and SpyBean move to the bean-override annotations")
    void mockBeanIsMigrated() {
        String source = """
                package com.example;
                import org.springframework.boot.test.mock.mockito.MockBean;
                import org.springframework.boot.test.mock.mockito.SpyBean;
                class ControllerTest {
                    @MockBean EmployeeService service;
                    @SpyBean AuditService audit;
                }
                """;

        TestFrameworkTransformer.Rewrite rewrite = TestFrameworkTransformer.mockBeanToMockitoBean(source);

        assertThat(rewrite.content())
                .contains("import org.springframework.test.context.bean.override.mockito.MockitoBean;");
        assertThat(rewrite.content()).contains("@MockitoBean");
        assertThat(rewrite.content()).contains("@MockitoSpyBean");
        assertThat(rewrite.content()).doesNotContain("@MockBean ");
    }

    // ------------------------------------------------------------------ capability discovery

    @Test
    @DisplayName("OpenRewrite capability is discovered, not assumed")
    void openRewriteCapabilityReflectsReality() {
        OpenRewriteCoreProvider provider = new OpenRewriteCoreProvider();
        List<TransformationPort.Capability> capabilities = provider.capabilities("2.7.12", "3.5.16");

        assertThat(capabilities).isNotEmpty();
        for (TransformationPort.Capability capability : capabilities) {
            assertThat(capability.status()).isIn("AVAILABLE", "UNAVAILABLE", "LICENSE_BLOCK");
        }
        if (!provider.coreAvailable()) {
            assertThat(capabilities).hasSize(1);
            assertThat(capabilities.get(0).status()).isEqualTo("UNAVAILABLE");
            assertThat(capabilities.get(0).confidence()).isZero();
        }
    }

    @Test
    @DisplayName("a declared AVAILABLE OpenRewrite capability can actually apply its recipe")
    void openRewriteCapabilityIsBackedByAnImplementation() {
        // The predecessor of this provider declared capabilities and then returned no changes when
        // asked to apply anything, so the registry claimed coverage the transformation stage could
        // not deliver. A capability that says AVAILABLE has to correspond to a recipe that runs.
        OpenRewriteCoreProvider provider = new OpenRewriteCoreProvider();
        for (TransformationPort.Capability capability : provider.capabilities("2.7.12", "3.5.16")) {
            if (!"AVAILABLE".equals(capability.status())) {
                continue;
            }
            boolean backed = List.of(
                    OpenRewriteCoreProvider.RECIPE_CHANGE_PACKAGE,
                    OpenRewriteCoreProvider.RECIPE_REMOVE_ANNOTATION,
                    OpenRewriteCoreProvider.RECIPE_CHANGE_PARENT_POM,
                    OpenRewriteCoreProvider.RECIPE_CHANGE_MAVEN_PROPERTY)
                    .stream()
                    .anyMatch(recipe -> provider.capabilityFor(recipe, "2.7.12", "3.5.16")
                            .map(c -> c.capabilityId().equals(capability.capabilityId()))
                            .orElse(false));
            assertThat(backed)
                    .as("capability %s claims AVAILABLE but no recipe maps to it",
                            capability.capabilityId())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("OpenRewrite rewrites the jakarta namespace without touching comments or literals")
    void openRewriteChangePackageIsSemantic(@TempDir Path workspace) throws Exception {
        OpenRewriteCoreProvider provider = new OpenRewriteCoreProvider();
        assumeTrue(provider.coreAvailable() && provider.javaModuleAvailable(),
                "OpenRewrite java module is not on the classpath");

        Path source = workspace.resolve("src/main/java/com/example/Order.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                import javax.persistence.Entity;
                import javax.sql.DataSource;

                // This comment mentions javax.persistence and must not be rewritten.
                @Entity
                public class Order {
                    String note = "javax.persistence stays inside this literal";
                    DataSource dataSource;
                }
                """);

        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put("oldPackageName", "javax.persistence");
        parameters.put("newPackageName", "jakarta.persistence");
        parameters.put("recursive", "true");
        TransformationPort.TransformationOutcome outcome = provider.apply(
                OpenRewriteCoreProvider.RECIPE_CHANGE_PACKAGE,
                new TransformationPort.TransformationRequest(workspace, "EDGE-TEST", "2.7.18",
                        "3.0.13", List.of("src/main/java/com/example/Order.java"), parameters));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.changes()).hasSize(1);
        String rewritten = outcome.changes().get(0).newContent();

        assertThat(rewritten).contains("import jakarta.persistence.Entity;");
        // javax.sql is a JDK package that never relocated. Rewriting it is the classic way to turn a
        // working application into one that does not compile for reasons unrelated to the migration.
        assertThat(rewritten).contains("import javax.sql.DataSource;");
        // A textual rewrite would have hit both of these.
        assertThat(rewritten).contains("comment mentions javax.persistence");
        assertThat(rewritten).contains("\"javax.persistence stays inside this literal\"");

        // Provenance: engine, recipe and both hashes, so the change is traceable to the tool.
        Map<String, String> attributes = outcome.changes().get(0).attributes();
        assertThat(attributes).containsKeys("engine", "engine_version", "recipe_class",
                "license", "input_hash", "output_hash", "edge_id");
        assertThat(attributes.get("engine")).isEqualTo("openrewrite");
        assertThat(attributes.get("license")).isEqualTo("Apache-2.0");
        assertThat(attributes.get("edge_id")).isEqualTo("EDGE-TEST");

        // And it wrote nothing: the gateway is still the only writer.
        assertThat(Files.readString(source)).contains("import javax.persistence.Entity;");
    }

    @Test
    @DisplayName("OpenRewrite refuses to run when a forbidden recipe estate is present")
    void openRewriteRefusesUnderLicenseBlock() {
        // The presence check is injected rather than faked with a substitute classloader: a
        // classloader cannot return a class under a different name, so the refusal branch would
        // never be reached and this behaviour would be asserted only by its own absence.
        OpenRewriteCoreProvider blocked = OpenRewriteCoreProvider.withSimulatedForbiddenEstate();

        assertThat(blocked.forbiddenEstatePresent()).isTrue();
        assertThat(blocked.capabilities("2.7.12", "3.5.16").get(0).status()).isEqualTo("LICENSE_BLOCK");
        assertThat(blocked.handles(OpenRewriteCoreProvider.RECIPE_CHANGE_PACKAGE)).isFalse();

        TransformationPort.TransformationOutcome outcome = blocked.apply(
                OpenRewriteCoreProvider.RECIPE_CHANGE_PACKAGE,
                new TransformationPort.TransformationRequest(Path.of("."), "EDGE-TEST", "2.7.18",
                        "3.0.13", List.of(), Map.of()));
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.changes()).isEmpty();
        assertThat(outcome.messages().toString()).contains("LICENSE_BLOCK");
    }

    @Test
    @DisplayName("no source-available Spring recipe estate is on the real classpath")
    void forbiddenEstateIsAbsent() {
        assertThat(new OpenRewriteCoreProvider().forbiddenEstatePresent()).isFalse();
        // The list is owned by LicensePolicy so the provider, the architecture test and the
        // dependency test cannot drift into three different answers.
        assertThat(LicensePolicy.forbiddenRecipePackages()).contains("org.openrewrite.java.spring");
    }

    @Test
    @DisplayName("the relocated and preserved lists are the sizes the documentation states")
    void relocationTableSizesArePinned() {
        // These two numbers appear in the README, in the skills catalog and on the fixture. Pinning
        // them here means an edit to either list fails the build instead of quietly making three
        // documents wrong - which is how the counts came to read 26 and 25 for lists of 28 and 26.
        assertThat(JakartaNamespaceTransformer.RELOCATED).hasSize(28);
        assertThat(JakartaNamespaceTransformer.PRESERVED).hasSize(26);
        assertThat(JakartaNamespaceTransformer.RELOCATED)
                .doesNotContainAnyElementsOf(JakartaNamespaceTransformer.PRESERVED);
    }

    // ---------------------------------------------------------------- removed no-op annotations

    private static final RemovedAnnotationTransformer.NoOpAnnotation EUREKA =
            RemovedAnnotationTransformer.NO_OP_ANNOTATIONS.stream()
                    .filter(a -> a.simpleName().equals("EnableEurekaClient"))
                    .findFirst()
                    .orElseThrow();

    @Test
    @DisplayName("a removed no-op annotation and its import both go")
    void removedAnnotationAndImportAreDeleted() {
        String source = """
                package com.example;

                import org.springframework.boot.SpringApplication;
                import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                @EnableEurekaClient
                public class App {

                    public static void main(String[] args) {
                        SpringApplication.run(App.class, args);
                    }
                }
                """;

        String result = RemovedAnnotationTransformer.remove(source, EUREKA);

        assertThat(result).doesNotContain("EnableEurekaClient");
        // Deleting the usage and leaving the import turns "cannot find symbol" into
        // "package does not exist", which is not progress.
        assertThat(result).doesNotContain("import org.springframework.cloud.netflix.eureka");
        // Everything else survives, including the annotation that must stay.
        assertThat(result).contains("@SpringBootApplication");
        assertThat(result).contains("import org.springframework.boot.SpringApplication;");
        assertThat(result).contains("SpringApplication.run(App.class, args);");
        // The line the annotation occupied is gone, not left blank.
        assertThat(result).doesNotContain(System.lineSeparator() + System.lineSeparator() + "public class App");
    }

    @Test
    @DisplayName("a file that never referenced the annotation is left byte-identical")
    void unrelatedFileIsUntouched() {
        String source = """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service
                public class EmployeeService {
                }
                """;

        assertThat(RemovedAnnotationTransformer.remove(source, EUREKA)).isEqualTo(source);
    }

    @Test
    @DisplayName("a same-named annotation from another package is not touched")
    void sameSimpleNameFromAnotherPackageSurvives() {
        // Deleting on simple name alone would remove this, silently disabling whatever it enables.
        String source = """
                package com.example;

                import com.acme.internal.EnableEurekaClient;

                @EnableEurekaClient
                public class App {
                }
                """;

        assertThat(RemovedAnnotationTransformer.remove(source, EUREKA)).isEqualTo(source);
    }

    @Test
    @DisplayName("every listed annotation states why removing it changes nothing")
    void everyEntryCarriesItsJustification() {
        // The list is curated on purpose: "the type is gone" does not imply "deleting the reference
        // is safe". An entry that cannot justify itself does not belong.
        assertThat(RemovedAnnotationTransformer.NO_OP_ANNOTATIONS).isNotEmpty();
        for (var annotation : RemovedAnnotationTransformer.NO_OP_ANNOTATIONS) {
            assertThat(annotation.rationale()).isNotBlank();
            assertThat(annotation.rationale().length()).isGreaterThan(40);
            assertThat(annotation.removedAtVersion()).isNotBlank();
            assertThat(annotation.fqn()).contains(".");
        }
    }

    @Test
    @DisplayName("the capability claims only the annotations it actually knows")
    void capabilityDoesNotOverclaim() {
        var capability = new RemovedAnnotationTransformer()
                .capabilities("2.7.18", "3.0.13").get(0);

        assertThat(capability.handledFactTypes()).containsExactly("API_REMOVED");
        assertThat(capability.handledSubjectPrefixes()).isNotEmpty();
        assertThat(capability.covers("API_REMOVED", EUREKA.fqn())).isTrue();
        // The defect this guards: declaring the API_REMOVED *type* made every removed API in the
        // run count as deterministically covered, including ones nothing could rewrite.
        assertThat(capability.covers("API_REMOVED",
                "org.springframework.security.config.annotation.web.configuration."
                        + "WebSecurityConfigurerAdapter")).isFalse();
    }
}
