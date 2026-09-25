package com.mars.harness.kernel.adapters.store;

import com.bootshift.core.util.Hashing;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.ports.evidence.ArtifactStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * Pointer-after-write artifact publication for kernel and capability artifacts.
 *
 * <p>Every write goes to a temporary sibling first and is then atomically moved into place, so a
 * crash never leaves a half-written artifact that looks current (the same rule as Bootshift's
 * OutputLayout). Areas that would reach tracked source ({@code original}, {@code migration},
 * {@code exec}, Bootshift's checkpoint repository) are refused. Artifacts are evidence, never
 * source.
 */
public final class FilesystemArtifactStore implements ArtifactStore {

    private static final Set<String> FORBIDDEN_AREAS = Set.of("original", "migration", "exec",
            "internal-checkpoint-git", "bootshift-output");

    private final RunLayout layout;

    public FilesystemArtifactStore(RunLayout layout) {
        this.layout = layout;
    }

    @Override
    public Path writeJson(String area, String name, Object payload) {
        return writeString(area, name, KernelJson.pretty(payload), false);
    }

    @Override
    public Path writeText(String area, String name, String text) {
        return writeString(area, name, text, false);
    }

    @Override
    public Path writeJsonOnce(String area, String name, Object payload) {
        return writeString(area, name, KernelJson.pretty(payload), true);
    }

    @Override
    public Path path(String area, String name) {
        return resolve(area, name);
    }

    @Override
    public Path importFile(String area, Path source) {
        if (!Files.isRegularFile(source)) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL, "Input file does not exist: " + source);
        }
        Path target = resolve(area, source.getFileName().toString());
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot import " + source, e);
        }
    }

    @Override
    public boolean exists(String area, String name) {
        return Files.isRegularFile(resolve(area, name));
    }

    @Override
    public String sha256(Path artifact) {
        try {
            return Hashing.sha256File(artifact);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path writeString(String area, String name, String text, boolean once) {
        Path target = resolve(area, name);
        if (once && Files.exists(target)) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL,
                    "Artifact " + area + "/" + name + " is write-once and already exists");
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot publish artifact " + target, e);
        }
    }

    private Path resolve(String area, String name) {
        String top = area.replace('\\', '/').split("/")[0];
        if (FORBIDDEN_AREAS.contains(top)) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL,
                    "Artifacts may not be written into '" + top + "': that area holds source, not evidence");
        }
        Path root = layout.runDir().normalize();
        Path target = root.resolve(area).resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL,
                    "Artifact path escapes the run directory: " + area + "/" + name);
        }
        return target;
    }
}
