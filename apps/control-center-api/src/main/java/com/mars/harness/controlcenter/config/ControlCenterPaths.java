package com.mars.harness.controlcenter.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The resolved filesystem boundary of the Control Center.
 *
 * <p>It also keeps absolute server paths out of API responses: {@link #redact} replaces the
 * harness and runs roots with placeholders so an error message can be shown to a browser.
 */
public record ControlCenterPaths(Path harnessRoot, Path runsRoot, List<Path> repositoryRoots) {

    /** The allowed root containing {@code candidate} (after normalization), if any. */
    public Optional<Path> allowedRootOf(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        return repositoryRoots.stream().filter(normalized::startsWith).findFirst();
    }

    /** {@code candidate} as a path relative to its allowed root, for display. */
    public String display(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        return allowedRootOf(normalized).map(root -> (root.getFileName() == null ? "" : root.getFileName() + "/")
                + root.relativize(normalized).toString().replace('\\', '/')).orElse(normalized.getFileName() == null ? ""
                : normalized.getFileName().toString());
    }

    public String redact(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Path root : repositoryRoots) {
            out = replace(out, root, "<repositories>");
        }
        out = replace(out, runsRoot, "<runs>");
        out = replace(out, harnessRoot, "<harness>");
        return out;
    }

    private static String replace(String text, Path root, String placeholder) {
        String a = root.toString();
        String b = a.replace('\\', '/');
        return text.replace(a, placeholder).replace(b, placeholder);
    }
}
