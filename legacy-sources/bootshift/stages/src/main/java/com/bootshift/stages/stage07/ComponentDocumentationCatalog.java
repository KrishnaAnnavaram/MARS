package com.bootshift.stages.stage07;

import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.docs.DocumentationPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which authoritative documents exist for the components this repository actually uses.
 *
 * <p>Stage 07 previously fetched Spring Boot release notes and nothing else. A Spring Boot major
 * upgrade moves Spring Framework, Spring Security, Spring Data, Hibernate, Jackson and the Spring
 * Cloud train underneath the application at the same time, and the breaking changes that stop a
 * migration are as often in those as in Boot itself. Retrieving only Boot documentation and then
 * reporting "documentation retrieved" overstates what was read.
 *
 * <p>Components are derived from the resolved build model, never guessed from file names: a document
 * is fetched because the application demonstrably depends on the thing it describes.
 */
public final class ComponentDocumentationCatalog {

    /**
     * A document the harness knows how to locate.
     *
     * <p>{@code urlTemplate} placeholders: {@code {line}} the target minor line (e.g. 3.2),
     * {@code {major}} the target major (e.g. 3), {@code {version}} the exact target version.
     */
    public record Source(String component, String urlTemplate, String publisher,
                         DocumentationPort.TrustLevel trustLevel, String description,
                         Scope scope) {

        public String url(String line, String major, String version) {
            return urlTemplate
                    .replace("{line}", line == null ? "" : line)
                    .replace("{major}", major == null ? "" : major)
                    .replace("{version}", version == null ? "" : version);
        }
    }

    /** When a source is worth retrieving. */
    public enum Scope {
        /** Once per edge - the document is specific to that hop. */
        PER_EDGE,
        /** Once per major boundary - the document describes crossing a major. */
        PER_MAJOR_BOUNDARY,
        /** Once per run - background material that is not edge-specific. */
        ONCE
    }

    /** Component detection: coordinate prefix to component name. */
    private static final Map<String, String> COORDINATE_COMPONENTS = new LinkedHashMap<>();

    static {
        COORDINATE_COMPONENTS.put("org.springframework.boot:", "spring-boot");
        COORDINATE_COMPONENTS.put("org.springframework.security:", "spring-security");
        COORDINATE_COMPONENTS.put("org.springframework.data:", "spring-data");
        COORDINATE_COMPONENTS.put("org.springframework.cloud:", "spring-cloud");
        COORDINATE_COMPONENTS.put("org.springframework.batch:", "spring-batch");
        COORDINATE_COMPONENTS.put("org.springframework.kafka:", "spring-kafka");
        COORDINATE_COMPONENTS.put("org.springframework.amqp:", "spring-amqp");
        COORDINATE_COMPONENTS.put("org.springframework:", "spring-framework");
        COORDINATE_COMPONENTS.put("org.hibernate:", "hibernate");
        COORDINATE_COMPONENTS.put("org.hibernate.orm:", "hibernate");
        COORDINATE_COMPONENTS.put("org.hibernate.validator:", "hibernate-validator");
        COORDINATE_COMPONENTS.put("com.fasterxml.jackson", "jackson");
        COORDINATE_COMPONENTS.put("jakarta.", "jakarta");
        COORDINATE_COMPONENTS.put("javax.", "jakarta");
        COORDINATE_COMPONENTS.put("org.apache.tomcat", "tomcat");
        COORDINATE_COMPONENTS.put("org.projectlombok:", "lombok");
        COORDINATE_COMPONENTS.put("io.cucumber:", "cucumber");
        COORDINATE_COMPONENTS.put("org.junit", "junit");
        COORDINATE_COMPONENTS.put("org.mockito:", "mockito");
        COORDINATE_COMPONENTS.put("org.apache.poi:", "apache-poi");
        COORDINATE_COMPONENTS.put("io.github.resilience4j:", "resilience4j");
    }

    private static final List<Source> SOURCES = List.of(
            // ---- Spring Boot: the edge documents, one per hop ------------------------------------
            new Source("spring-boot",
                    "https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-{line}-Release-Notes",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_RELEASE_NOTES,
                    "Official release notes for the target line", Scope.PER_EDGE),
            new Source("spring-boot",
                    "https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-{line}-Migration-Guide",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_MIGRATION_GUIDE,
                    "Official migration guide for the target line", Scope.PER_EDGE),

            // ---- components that move with a Boot major boundary ---------------------------------
            new Source("spring-framework",
                    "https://github.com/spring-projects/spring-framework/wiki/Upgrading-to-Spring-Framework-{major}.x",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_MIGRATION_GUIDE,
                    "Spring Framework upgrade guide for the target generation",
                    Scope.PER_MAJOR_BOUNDARY),
            new Source("spring-security",
                    "https://docs.spring.io/spring-security/reference/migration/index.html",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_MIGRATION_GUIDE,
                    "Spring Security migration guide", Scope.PER_MAJOR_BOUNDARY),
            new Source("spring-security",
                    "https://github.com/spring-projects/spring-security/releases",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_RELEASE_NOTES,
                    "Spring Security release notes index", Scope.PER_MAJOR_BOUNDARY),
            new Source("spring-data",
                    "https://github.com/spring-projects/spring-data-commons/wiki",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_GENERAL_DOC,
                    "Spring Data release train notes index", Scope.PER_MAJOR_BOUNDARY),
            new Source("hibernate",
                    "https://raw.githubusercontent.com/hibernate/hibernate-orm/main/migration-guide.adoc",
                    "Hibernate", DocumentationPort.TrustLevel.UPSTREAM_PROJECT_DOC,
                    "Hibernate ORM migration guide", Scope.PER_MAJOR_BOUNDARY),
            new Source("jackson",
                    "https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.15",
                    "FasterXML", DocumentationPort.TrustLevel.UPSTREAM_PROJECT_DOC,
                    "Jackson release notes", Scope.PER_MAJOR_BOUNDARY),
            new Source("jakarta",
                    "https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_MIGRATION_GUIDE,
                    "Jakarta EE namespace relocation, as documented by the Boot 3.0 migration guide",
                    Scope.PER_MAJOR_BOUNDARY),

            // ---- the release train, which is version-locked to the Boot line ---------------------
            new Source("spring-cloud",
                    "https://github.com/spring-cloud/spring-cloud-release/wiki",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_GENERAL_DOC,
                    "Spring Cloud release train index", Scope.PER_MAJOR_BOUNDARY),

            // ---- background, retrieved once ------------------------------------------------------
            new Source("spring-boot",
                    "https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.adoc",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_GENERAL_DOC,
                    "Project overview and system requirements", Scope.ONCE),
            new Source("jdk",
                    "https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.adoc",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_GENERAL_DOC,
                    "Declared JDK baseline for the Spring Boot generation", Scope.ONCE),
            new Source("maven",
                    "https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.adoc",
                    "Spring", DocumentationPort.TrustLevel.OFFICIAL_GENERAL_DOC,
                    "Declared build tool baseline", Scope.ONCE));

    private ComponentDocumentationCatalog() {
    }

    /**
     * Repository ids and URL hosts that serve public open-source artifacts.
     *
     * <p>Used to answer "did this come from somewhere anyone can reach" with the resolver's own
     * answer rather than with a guess about the group name.
     */
    private static final Set<String> PUBLIC_REPOSITORY_IDS = Set.of(
            "central", "maven-central", "mavenCentral", "public", "jcenter", "google",
            "gradle-plugin-portal", "spring-releases", "spring-milestones", "sonatype",
            "sonatype-releases", "jitpack.io", "apache-releases");

    private static final List<String> PUBLIC_REPOSITORY_HOSTS = List.of(
            "repo.maven.apache.org", "repo1.maven.org", "repo.maven.org", "central.sonatype.com",
            "oss.sonatype.org", "s01.oss.sonatype.org", "plugins.gradle.org", "jitpack.io",
            "repo.spring.io", "maven.google.com", "dl.google.com", "jcenter.bintray.com",
            "repository.apache.org");

    /**
     * How the documentation channel sees the components this repository uses.
     *
     * @param components components with a catalogued documentation source, plus the always-present
     *        {@code spring-boot}, {@code jdk} and build-system entries
     * @param publicWithoutCatalogue coordinates that demonstrably resolved from a public repository
     *        but for which this harness catalogues no authoritative document - a gap in the
     *        <em>catalogue</em>
     * @param possiblyInternal coordinates that did not resolve, or resolved from a repository that
     *        is not demonstrably public - a gap in what the harness can <em>reach</em>
     */
    public record ComponentDetection(Set<String> components, List<String> publicWithoutCatalogue,
                                     List<String> possiblyInternal) {
    }

    /**
     * Components this repository demonstrably uses, from the resolved dependency graph.
     *
     * <p>Always includes {@code spring-boot} and {@code jdk}: every migration crosses both.
     */
    public static Set<String> detectComponents(BuildSystemPort.BuildModel model) {
        return detect(model).components();
    }

    /**
     * Classifies every resolved coordinate against the documentation catalogue.
     *
     * <p>The distinction that matters is between two very different gaps, and the previous rule
     * collapsed them. It labelled a coordinate {@code internal:} whenever its group did not begin
     * with one of a handful of prefixes, so Guava, Gson, {@code commons-*}, Joda-Time, XStream and
     * ANTLR - all of them public, all of them resolved from Maven Central by the build itself - were
     * reported as organization-internal components with no authoritative source. That list exists to
     * show a reviewer where the documentation channel is blind; padded with three dozen well-known
     * libraries it buries the handful of entries that genuinely deserve attention.
     *
     * <p>The question is now answered from resolution evidence. A coordinate the resolver fetched
     * from a public repository is public, whatever its group is called. Only a coordinate that did
     * not resolve, or that came from a repository not demonstrably public, can be internal - and even
     * then it is reported as <em>possibly</em> internal, because a private mirror of a public library
     * looks identical from here.
     */
    public static ComponentDetection detect(BuildSystemPort.BuildModel model) {
        Set<String> components = new LinkedHashSet<>();
        components.add("spring-boot");
        components.add("jdk");
        if (model == null) {
            return new ComponentDetection(components, List.of(), List.of());
        }
        components.add(model.kind() == BuildSystemPort.Kind.GRADLE ? "gradle" : "maven");

        Set<String> publicRepositoryIds = new LinkedHashSet<>(PUBLIC_REPOSITORY_IDS);
        for (BuildSystemPort.RepositoryRef repository : model.repositories()) {
            String url = repository.url() == null ? "" : repository.url().toLowerCase(Locale.ROOT);
            if (PUBLIC_REPOSITORY_HOSTS.stream().anyMatch(url::contains)) {
                publicRepositoryIds.add(repository.id());
            }
        }

        Set<String> publicWithoutCatalogue = new java.util.TreeSet<>();
        Set<String> possiblyInternal = new java.util.TreeSet<>();

        for (BuildSystemPort.ResolvedDependency dependency : model.dependencies()) {
            String coordinate = dependency.ga();
            boolean catalogued = false;
            for (Map.Entry<String, String> entry : COORDINATE_COMPONENTS.entrySet()) {
                if (coordinate.startsWith(entry.getKey())) {
                    components.add(entry.getValue());
                    catalogued = true;
                    break;
                }
            }
            if (catalogued) {
                continue;
            }
            if (resolvedFromPublicRepository(dependency, publicRepositoryIds)) {
                publicWithoutCatalogue.add(coordinate);
            } else {
                possiblyInternal.add(coordinate);
                components.add("internal:" + dependency.groupId());
            }
        }
        return new ComponentDetection(components, List.copyOf(publicWithoutCatalogue),
                List.copyOf(possiblyInternal));
    }

    /** True when the resolver actually fetched this coordinate from a repository anyone can reach. */
    private static boolean resolvedFromPublicRepository(BuildSystemPort.ResolvedDependency dependency,
                                                        Set<String> publicRepositoryIds) {
        String status = dependency.resolutionStatus();
        if (status != null && !"RESOLVED".equalsIgnoreCase(status)) {
            // Unresolved means unknown, and unknown is not "public".
            return false;
        }
        String repository = dependency.repository();
        if (repository == null || repository.isBlank()) {
            return false;
        }
        if (publicRepositoryIds.contains(repository)) {
            return true;
        }
        String lower = repository.toLowerCase(Locale.ROOT);
        return PUBLIC_REPOSITORY_HOSTS.stream().anyMatch(lower::contains);
    }

    /** Sources for a component at a given scope, or empty when the harness knows of none. */
    public static List<Source> sourcesFor(String component, Scope scope) {
        List<Source> found = new ArrayList<>();
        for (Source source : SOURCES) {
            if (source.component().equals(component) && source.scope() == scope) {
                found.add(source);
            }
        }
        return found;
    }

    /** Every component the catalog can retrieve anything for. */
    public static Set<String> knownComponents() {
        Set<String> known = new LinkedHashSet<>();
        SOURCES.forEach(s -> known.add(s.component()));
        return known;
    }

    /** Components the repository uses that the catalog has no authoritative source for. */
    public static List<String> unsupportedComponents(Set<String> detected) {
        List<String> unsupported = new ArrayList<>();
        Set<String> known = knownComponents();
        for (String component : detected) {
            if (!known.contains(component)) {
                unsupported.add(component);
            }
        }
        unsupported.sort(String::compareTo);
        return unsupported;
    }

    public static String lineOf(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    public static String majorOf(String version) {
        if (version == null) {
            return null;
        }
        return version.split("\\.")[0].replaceAll("[^0-9]", "");
    }

    public static String normalizeComponent(String raw) {
        return raw == null ? "unknown" : raw.toLowerCase(Locale.ROOT);
    }
}
