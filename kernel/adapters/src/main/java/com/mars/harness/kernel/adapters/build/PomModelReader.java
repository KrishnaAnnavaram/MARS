package com.mars.harness.kernel.adapters.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.ports.build.BuildModelView;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
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
 * Declared build model from Maven descriptors, merged with Bootshift's resolved model when one
 * exists.
 *
 * <p>The declared inventory follows the migration reference's {@code detect-baseline} rules: the
 * parent; the properties; the dependencies with {@code ${prop}} resolved, the declared version
 * kept, scope defaulting to compile, and {@code managed} true when no version is declared; and
 * the plugins. The Java level is taken from the first of {@code java.version},
 * {@code maven.compiler.release}, {@code maven.compiler.source}, the compiler plugin
 * {@code <release>}, then {@code <source>}.
 *
 * <p>The XML parser has DTDs and external entities disabled: the repository is untrusted input.
 */
public final class PomModelReader {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    public BuildModelView read(Path repositoryRoot, JsonNode bootshiftBuildModel) {
        final List<BuildModelView.ModuleBuild> collected = new ArrayList<>();
        Path rootPom = repositoryRoot.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            collect(repositoryRoot, rootPom, ".", collected, 0);
        } else {
            // per-service layout (as in the VRH and Bootshift sample apps): one pom per child directory
            try (var children = Files.list(repositoryRoot)) {
                children.filter(Files::isDirectory).sorted().forEach(dir -> {
                    Path pom = dir.resolve("pom.xml");
                    if (Files.isRegularFile(pom)) {
                        collect(repositoryRoot, pom, repositoryRoot.relativize(dir).toString().replace('\\', '/'),
                                collected, 0);
                    }
                });
            } catch (Exception e) {
                // an unreadable tree yields no modules; reported through degradedReason below
            }
        }
        List<BuildModelView.ModuleBuild> modules = collected;
        boolean authoritative = bootshiftBuildModel != null && bootshiftBuildModel.path("authoritative").asBoolean(false);
        String degraded = bootshiftBuildModel == null
                ? "No build-tool resolution available; model is descriptor-derived (declared versions only)"
                : bootshiftBuildModel.path("degraded_reason").asText(null);
        if (modules.isEmpty()) {
            degraded = "No Maven descriptor found" + (Files.isRegularFile(repositoryRoot.resolve("build.gradle"))
                    || Files.isRegularFile(repositoryRoot.resolve("build.gradle.kts"))
                    ? " (Gradle build present: declared-model extraction for Gradle is not implemented)" : "");
        }
        if (bootshiftBuildModel != null) {
            modules = mergeResolved(modules, bootshiftBuildModel);
        }
        String buildSystem = modules.isEmpty() ? "UNKNOWN" : "MAVEN";
        return new BuildModelView(buildSystem, authoritative && !modules.isEmpty(), degraded, modules);
    }

    private void collect(Path repositoryRoot, Path pom, String path, List<BuildModelView.ModuleBuild> modules, int depth) {
        if (depth > 6) {
            return;
        }
        String text;
        Document doc;
        try {
            text = Files.readString(pom, StandardCharsets.UTF_8);
            doc = parse(text);
        } catch (Exception e) {
            modules.add(new BuildModelView.ModuleBuild(path, path, relative(repositoryRoot, pom), null,
                    Map.of("__parse_error__", String.valueOf(e.getMessage())), null, null, List.of(), List.of(), Map.of()));
            return;
        }
        Element project = doc.getDocumentElement();
        Map<String, String> properties = new LinkedHashMap<>();
        Element props = child(project, "properties");
        if (props != null) {
            for (Element p : children(props)) {
                properties.put(p.getTagName(), p.getTextContent().trim());
            }
        }
        BuildModelView.Coordinate parent = null;
        Element parentEl = child(project, "parent");
        if (parentEl != null) {
            parent = new BuildModelView.Coordinate(text(parentEl, "groupId"), text(parentEl, "artifactId"),
                    text(parentEl, "version"));
            properties.putIfAbsent("project.parent.version", parent.version());
        }
        String projectGroup = text(project, "groupId") != null ? text(project, "groupId") : parent == null ? null : parent.groupId();
        if (projectGroup != null) {
            properties.putIfAbsent("project.groupId", projectGroup);
        }
        String projectVersion = text(project, "version");
        if (projectVersion != null) {
            properties.putIfAbsent("project.version", projectVersion);
        }

        List<BuildModelView.Dependency> dependencies = new ArrayList<>();
        Element deps = child(project, "dependencies");
        if (deps != null) {
            for (Element d : children(deps)) {
                if (!"dependency".equals(d.getTagName())) {
                    continue;
                }
                String groupId = resolve(text(d, "groupId"), properties);
                String artifactId = resolve(text(d, "artifactId"), properties);
                String declared = text(d, "version");
                String scope = text(d, "scope");
                dependencies.add(new BuildModelView.Dependency(groupId, artifactId, declared == null ? null
                        : resolve(declared, properties), null, scope == null ? "compile" : scope, declared == null,
                        lineOf(text, artifactId)));
            }
        }
        List<BuildModelView.Coordinate> plugins = new ArrayList<>();
        Map<String, String> compiler = new LinkedHashMap<>();
        Element build = child(project, "build");
        Element pluginsEl = build == null ? null : child(build, "plugins");
        if (pluginsEl != null) {
            for (Element p : children(pluginsEl)) {
                String artifactId = text(p, "artifactId");
                plugins.add(new BuildModelView.Coordinate(text(p, "groupId") == null ? "org.apache.maven.plugins"
                        : text(p, "groupId"), artifactId, resolve(text(p, "version"), properties)));
                if ("maven-compiler-plugin".equals(artifactId)) {
                    Element configuration = child(p, "configuration");
                    if (configuration != null) {
                        for (Element c : children(configuration)) {
                            compiler.put(c.getTagName(), resolve(c.getTextContent().trim(), properties));
                        }
                    }
                }
            }
        }
        String javaVersion = null;
        String source = null;
        for (String key : List.of("java.version", "maven.compiler.release", "maven.compiler.source")) {
            if (properties.get(key) != null) {
                javaVersion = properties.get(key);
                source = "property " + key;
                break;
            }
        }
        if (javaVersion == null && compiler.get("release") != null) {
            javaVersion = compiler.get("release");
            source = "maven-compiler-plugin <release>";
        } else if (javaVersion == null && compiler.get("source") != null) {
            javaVersion = compiler.get("source");
            source = "maven-compiler-plugin <source>";
        }
        String name = text(project, "artifactId");
        modules.add(new BuildModelView.ModuleBuild(name == null ? path : name, path, relative(repositoryRoot, pom),
                parent, properties, javaVersion, source, dependencies, plugins, compiler));

        Element modulesEl = child(project, "modules");
        if (modulesEl != null) {
            for (Element m : children(modulesEl)) {
                String module = m.getTextContent().trim();
                Path childPom = pom.getParent().resolve(module).resolve("pom.xml").normalize();
                if (Files.isRegularFile(childPom) && childPom.startsWith(repositoryRoot)) {
                    String childPath = ".".equals(path) ? module : path + "/" + module;
                    collect(repositoryRoot, childPom, childPath.replace('\\', '/'), modules, depth + 1);
                }
            }
        }
    }

    /** Overlays resolved versions from Bootshift's authoritative build model, where it has them. */
    private List<BuildModelView.ModuleBuild> mergeResolved(List<BuildModelView.ModuleBuild> modules, JsonNode model) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (JsonNode d : model.path("dependencies")) {
            if ("RESOLVED".equals(d.path("resolutionStatus").asText())) {
                resolved.putIfAbsent(d.path("groupId").asText() + ":" + d.path("artifactId").asText(),
                        d.path("version").asText(null));
            }
        }
        if (resolved.isEmpty()) {
            return modules;
        }
        List<BuildModelView.ModuleBuild> merged = new ArrayList<>();
        for (BuildModelView.ModuleBuild m : modules) {
            List<BuildModelView.Dependency> deps = m.dependencies().stream()
                    .map(d -> new BuildModelView.Dependency(d.groupId(), d.artifactId(), d.declaredVersion(),
                            resolved.get(d.ga()), d.scope(), d.managed(), d.line()))
                    .toList();
            merged.add(new BuildModelView.ModuleBuild(m.name(), m.path(), m.buildFile(), m.parent(), m.properties(),
                    m.javaVersion(), m.javaVersionSource(), deps, m.plugins(), m.compilerConfiguration()));
        }
        return merged;
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String resolve(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String current = value;
        for (int i = 0; i < 5; i++) {
            Matcher m = PROPERTY.matcher(current);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String replacement = properties.get(m.group(1));
                if (replacement != null) {
                    changed = true;
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                }
            }
            m.appendTail(sb);
            current = sb.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    private static int lineOf(String text, String artifactId) {
        if (artifactId == null) {
            return 0;
        }
        int index = text.indexOf("<artifactId>" + artifactId + "</artifactId>");
        if (index < 0) {
            return 0;
        }
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static Element child(Element parent, String name) {
        for (Element e : children(parent)) {
            if (e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e) {
                result.add(e);
            }
        }
        return result;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }
}
