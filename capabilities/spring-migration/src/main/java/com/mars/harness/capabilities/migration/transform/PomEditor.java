package com.mars.harness.capabilities.migration.transform;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level Maven descriptor edits that preserve the author's formatting and comments.
 *
 * <p>Every method returns empty when there is nothing to change. A no-op is never presented as a
 * change.
 */
public final class PomEditor {

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(?:(?!</dependency>)[\\s\\S])*?</dependency>");

    private PomEditor() {
    }

    public static Optional<String> setParentVersion(String pom, String groupId, String artifactId, String version) {
        Matcher parent = Pattern.compile("<parent>[\\s\\S]*?</parent>").matcher(pom);
        if (!parent.find()) {
            return Optional.empty();
        }
        String block = parent.group();
        if (!block.contains("<groupId>" + groupId + "</groupId>") || !block.contains("<artifactId>" + artifactId + "</artifactId>")) {
            return Optional.empty();
        }
        Matcher v = Pattern.compile("<version>\\s*([^<]+?)\\s*</version>").matcher(block);
        if (!v.find() || v.group(1).equals(version)) {
            return Optional.empty();
        }
        String updated = block.substring(0, v.start(1)) + version + block.substring(v.end(1));
        return Optional.of(pom.substring(0, parent.start()) + updated + pom.substring(parent.end()));
    }

    /** §1.2: the java.version-style properties and any explicit compiler source/target become the target release. */
    public static Optional<String> setJavaLevel(String pom, String release) {
        String result = pom;
        for (String property : new String[]{"java.version", "maven.compiler.release", "maven.compiler.source",
                "maven.compiler.target"}) {
            result = result.replaceAll("<" + Pattern.quote(property) + ">\\s*[^<]+?\\s*</" + Pattern.quote(property) + ">",
                    "<" + property + ">" + release + "</" + property + ">");
        }
        Matcher compiler = Pattern.compile("<artifactId>maven-compiler-plugin</artifactId>[\\s\\S]*?</plugin>").matcher(result);
        if (compiler.find()) {
            String block = compiler.group();
            String updated = block;
            Matcher source = Pattern.compile("([ \\t]*)<source>\\s*[^<$]+?\\s*</source>").matcher(updated);
            if (source.find()) {
                String indent = source.group(1);
                updated = updated.substring(0, source.start()) + indent + "<release>" + release + "</release>"
                        + updated.substring(source.end());
                updated = updated.replaceFirst("[ \\t]*<target>\\s*[^<$]+?\\s*</target>", "");
            } else {
                updated = updated.replaceAll("<release>\\s*[^<$]+?\\s*</release>", "<release>" + release + "</release>");
            }
            result = result.substring(0, compiler.start()) + updated + result.substring(compiler.end());
        }
        return result.equals(pom) ? Optional.empty() : Optional.of(result);
    }

    public static Optional<String> renameDependency(String pom, String fromGa, String toGa) {
        String[] from = fromGa.split(":");
        String[] to = toGa.split(":");
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            String block = m.group();
            if (block.contains("<groupId>" + from[0] + "</groupId>") && block.contains("<artifactId>" + from[1] + "</artifactId>")) {
                String updated = block.replace("<groupId>" + from[0] + "</groupId>", "<groupId>" + to[0] + "</groupId>")
                        .replace("<artifactId>" + from[1] + "</artifactId>", "<artifactId>" + to[1] + "</artifactId>");
                return Optional.of(pom.substring(0, m.start()) + updated + pom.substring(m.end()));
            }
        }
        return Optional.empty();
    }

    public static boolean hasDependency(String pom, String ga) {
        String[] p = ga.split(":");
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            if (m.group().contains("<groupId>" + p[0] + "</groupId>") && m.group().contains("<artifactId>" + p[1] + "</artifactId>")) {
                return true;
            }
        }
        return false;
    }

    /** Explicit version of a dependency, if it declares one. */
    public static Optional<String> explicitVersion(String pom, String ga) {
        String[] p = ga.split(":");
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            String block = m.group();
            if (block.contains("<groupId>" + p[0] + "</groupId>") && block.contains("<artifactId>" + p[1] + "</artifactId>")) {
                Matcher v = Pattern.compile("<version>\\s*([^<]+?)\\s*</version>").matcher(block);
                return v.find() ? Optional.of(v.group(1)) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    public static Optional<String> setDependencyVersion(String pom, String ga, String version) {
        String[] p = ga.split(":");
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            String block = m.group();
            if (block.contains("<groupId>" + p[0] + "</groupId>") && block.contains("<artifactId>" + p[1] + "</artifactId>")) {
                Matcher v = Pattern.compile("<version>\\s*([^<]+?)\\s*</version>").matcher(block);
                if (!v.find() || v.group(1).equals(version) || v.group(1).startsWith("${")) {
                    return Optional.empty();
                }
                String updated = block.substring(0, v.start(1)) + version + block.substring(v.end(1));
                return Optional.of(pom.substring(0, m.start()) + updated + pom.substring(m.end()));
            }
        }
        return Optional.empty();
    }

    /** Adds a dependency to the project's own {@code <dependencies>} (not dependencyManagement). */
    public static Optional<String> addDependency(String pom, String ga, String scope) {
        if (hasDependency(pom, ga)) {
            return Optional.empty();
        }
        String[] p = ga.split(":");
        int management = pom.indexOf("<dependencyManagement>");
        int managementEnd = pom.indexOf("</dependencyManagement>");
        Matcher close = Pattern.compile("([ \\t]*)</dependencies>").matcher(pom);
        while (close.find()) {
            if (management >= 0 && close.start() > management && close.start() < managementEnd) {
                continue;
            }
            String indent = close.group(1);
            String inner = indent + "    ";
            String dep = "\n" + inner + "<dependency>\n" + inner + "    <groupId>" + p[0] + "</groupId>\n" + inner + "    <artifactId>"
                    + p[1] + "</artifactId>\n" + (scope == null ? "" : inner + "    <scope>" + scope + "</scope>\n") + inner
                    + "</dependency>\n";
            int insertAt = close.start();
            String before = pom.substring(0, insertAt).replaceAll("\\s+$", "");
            return Optional.of(before + dep + pom.substring(insertAt));
        }
        return Optional.empty();
    }

    public static Optional<String> replaceDependency(String pom, String fromGa, String toGa, String scope) {
        String[] from = fromGa.split(":");
        String[] to = toGa.split(":");
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            String block = m.group();
            if (block.contains("<groupId>" + from[0] + "</groupId>") && block.contains("<artifactId>" + from[1] + "</artifactId>")) {
                String updated = block.replace("<groupId>" + from[0] + "</groupId>", "<groupId>" + to[0] + "</groupId>")
                        .replace("<artifactId>" + from[1] + "</artifactId>", "<artifactId>" + to[1] + "</artifactId>")
                        .replaceAll("[ \\t]*<version>[^<]*</version>\\s*\\n", "");
                if (scope != null && !updated.contains("<scope>")) {
                    updated = updated.replace("</artifactId>", "</artifactId>\n            <scope>" + scope + "</scope>");
                }
                return Optional.of(pom.substring(0, m.start()) + updated + pom.substring(m.end()));
            }
        }
        return Optional.empty();
    }
}
