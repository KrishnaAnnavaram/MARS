package com.mars.harness.kernel.engine.exec;

import com.bootshift.core.util.Hashing;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.ports.execution.ExecutionSandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

/**
 * Disposable execution copies of the workspace (ADR-U007).
 *
 * <p>{@link #prepare} makes {@code exec/<label>} content-identical to the tracked workspace,
 * except for build output ({@code target/}, {@code build/}). Nothing is ever copied back, so a
 * build tool or a running application cannot mutate tracked source, and its outputs cannot pass
 * for unauthorised mutations of the workspace.
 */
public final class WorkspaceSandbox implements ExecutionSandbox {

    private static final Set<String> SKIP = Set.of(".git", "target", "build", ".gradle", "node_modules", ".idea");

    private final RunLayout layout;

    public WorkspaceSandbox(RunLayout layout) {
        this.layout = layout;
    }

    @Override
    public Path prepare(String label) {
        Path source = layout.workspace();
        Path target = layout.exec(label);
        try {
            Files.createDirectories(target);
            Set<Path> expected = new HashSet<>();
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(source) && SKIP.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = source.relativize(file);
                    Path destination = target.resolve(relative.toString());
                    expected.add(destination.normalize());
                    if (!Files.isRegularFile(destination) || Files.size(destination) != attrs.size()
                            || !Hashing.sha256File(destination).equals(Hashing.sha256File(file))) {
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                        destination.toFile().setWritable(true);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            // remove files deleted from the workspace (build output directories are kept)
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(target) && SKIP.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!expected.contains(file.normalize())) {
                        Files.deleteIfExists(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare execution copy " + target, e);
        }
    }

    @Override
    public Path snapshot() {
        return layout.sourceSnapshot();
    }
}
