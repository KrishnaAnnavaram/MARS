package com.bootshift.adapters.apidiff;

import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.ports.apidiff.ApiDiffPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Artifact-channel API comparison using JDK tooling only (spec sections 19 and 47).
 *
 * <p>Uses {@code javap} against both jars to extract public and protected signatures, then diffs the
 * signature sets. This is deliberately JDK-native: it introduces no additional dependency, it works
 * offline once the jars are fetched, and its output is exactly reproducible.
 *
 * <p>The class set is bounded by the caller so a whole framework is never decompiled when only the
 * types the application actually references matter.
 */
public final class JavapApiDiffAdapter implements ApiDiffPort {

    private static final Logger LOG = LoggerFactory.getLogger(JavapApiDiffAdapter.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(6);
    private static final int MAX_CLASSES_PER_INVOCATION = 120;

    private final ProcessRunner runner;
    private final Set<String> classFilter;
    private final int maxClasses;

    public JavapApiDiffAdapter() {
        this(new ProcessRunner(), Set.of(), 800);
    }

    /**
     * @param classFilter fully-qualified prefixes to restrict the comparison to; empty means compare
     *                    every public type up to {@code maxClasses}
     */
    public JavapApiDiffAdapter(ProcessRunner runner, Set<String> classFilter, int maxClasses) {
        this.runner = runner;
        this.classFilter = classFilter;
        this.maxClasses = maxClasses;
    }

    @Override
    public String name() {
        return "javap-api-diff";
    }

    @Override
    public boolean available() {
        return ProcessRunner.jdkTool("javap") != null;
    }

    @Override
    public DiffResult compare(Path oldJar, Path newJar, String groupId, String artifactId,
                              String oldVersion, String newVersion) {
        String javap = ProcessRunner.jdkTool("javap");
        if (javap == null) {
            return new DiffResult(groupId, artifactId, oldVersion, newVersion, List.of(),
                    name(), "unavailable", false, "javap is not available in this JDK installation");
        }
        Set<String> oldClasses = listClasses(oldJar);
        Set<String> newClasses = listClasses(newJar);
        Set<String> union = new LinkedHashSet<>(oldClasses);
        union.addAll(newClasses);
        boolean truncated = union.size() > maxClasses;
        List<String> considered = union.stream().limit(maxClasses).toList();

        Map<String, Set<String>> oldSignatures = signatures(javap, oldJar, considered, oldClasses);
        Map<String, Set<String>> newSignatures = signatures(javap, newJar, considered, newClasses);

        List<ApiChange> changes = new ArrayList<>();
        for (String type : considered) {
            boolean inOld = oldClasses.contains(type);
            boolean inNew = newClasses.contains(type);
            if (inOld && !inNew) {
                changes.add(new ApiChange(ChangeKind.TYPE_REMOVED, type, null, null, null,
                        "Type present in " + oldVersion + " and absent in " + newVersion));
                continue;
            }
            if (!inOld && inNew) {
                changes.add(new ApiChange(ChangeKind.TYPE_ADDED, type, null, null, null,
                        "Type introduced in " + newVersion));
                continue;
            }
            Set<String> before = oldSignatures.getOrDefault(type, Set.of());
            Set<String> after = newSignatures.getOrDefault(type, Set.of());
            for (String signature : before) {
                if (!after.contains(signature)) {
                    String memberName = memberNameOf(signature);
                    boolean renamedOrChanged = after.stream().anyMatch(s -> memberNameOf(s).equals(memberName));
                    changes.add(new ApiChange(
                            renamedOrChanged ? ChangeKind.METHOD_SIGNATURE_CHANGED : ChangeKind.METHOD_REMOVED,
                            type, memberName, signature, null,
                            renamedOrChanged ? "Signature changed between versions" : "Member removed"));
                }
            }
            for (String signature : after) {
                if (!before.contains(signature)) {
                    changes.add(new ApiChange(ChangeKind.METHOD_ADDED, type, memberNameOf(signature),
                            null, signature, "Member introduced in " + newVersion));
                }
            }
        }

        return new DiffResult(groupId, artifactId, oldVersion, newVersion, changes, name(),
                System.getProperty("java.version"), !truncated,
                truncated ? "Comparison truncated at " + maxClasses + " types of " + union.size() : null);
    }

    private Set<String> listClasses(Path jar) {
        Set<String> classes = new LinkedHashSet<>();
        if (jar == null || !java.nio.file.Files.isRegularFile(jar)) {
            return classes;
        }
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.contains("$") || name.startsWith("META-INF/")) {
                    continue;
                }
                String fqn = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                if (classFilter.isEmpty() || classFilter.stream().anyMatch(fqn::startsWith)) {
                    classes.add(fqn);
                }
            }
        } catch (IOException e) {
            LOG.warn("Cannot enumerate classes in {}: {}", jar, e.getMessage());
        }
        return classes;
    }

    private Map<String, Set<String>> signatures(String javap, Path jar, List<String> types,
                                                Set<String> present) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        List<String> targets = types.stream().filter(present::contains).toList();
        for (int offset = 0; offset < targets.size(); offset += MAX_CLASSES_PER_INVOCATION) {
            List<String> batch = targets.subList(offset,
                    Math.min(targets.size(), offset + MAX_CLASSES_PER_INVOCATION));
            List<String> command = new ArrayList<>();
            command.add(javap);
            command.add("-protected");
            command.add("-classpath");
            command.add(jar.toAbsolutePath().toString());
            command.addAll(batch);
            ProcessRunner.Result run = runner.run(command, jar.getParent() == null
                    ? Path.of(".") : jar.getParent(), TIMEOUT, Map.of());
            parseJavap(run.stdout(), result);
        }
        return result;
    }

    /** Parses javap output into a type-to-signature-set map. */
    static void parseJavap(List<String> lines, Map<String, Set<String>> sink) {
        String currentType = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("Compiled from")) {
                continue;
            }
            if (line.endsWith("{")) {
                int nameStart = line.lastIndexOf(' ', Math.max(0, line.indexOf('{') - 2));
                String header = line.substring(0, line.length() - 1).trim();
                currentType = extractTypeName(header);
                if (currentType != null) {
                    sink.computeIfAbsent(currentType, k -> new LinkedHashSet<>());
                }
                continue;
            }
            if ("}".equals(line) || currentType == null) {
                continue;
            }
            sink.computeIfAbsent(currentType, k -> new LinkedHashSet<>()).add(line);
        }
    }

    private static String extractTypeName(String header) {
        String[] tokens = header.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (("class".equals(tokens[i]) || "interface".equals(tokens[i]) || "enum".equals(tokens[i])
                    || "record".equals(tokens[i])) && i + 1 < tokens.length) {
                String name = tokens[i + 1];
                int generic = name.indexOf('<');
                return generic < 0 ? name : name.substring(0, generic);
            }
        }
        return null;
    }

    private static String memberNameOf(String signature) {
        int paren = signature.indexOf('(');
        String head = paren < 0 ? signature : signature.substring(0, paren);
        String[] tokens = head.trim().split("\\s+");
        String last = tokens[tokens.length - 1];
        return last.endsWith(";") ? last.substring(0, last.length() - 1) : last;
    }
}
