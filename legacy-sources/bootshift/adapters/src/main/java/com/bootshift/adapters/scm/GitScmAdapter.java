package com.bootshift.adapters.scm;

import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.core.util.Hashing;
import com.bootshift.ports.scm.ScmPort;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JGit-backed SCM adapter that supports both intake shapes (spec section 5).
 *
 * <p>A Git-backed input contributes real commit and tree identity. A plain directory such as
 * {@code ./src} contributes a deterministic content-manifest hash instead, and the adapter creates
 * an internal checkpoint repository <em>inside the external run workspace</em> so migration history
 * exists without ever writing to the user input path.
 */
public final class GitScmAdapter implements ScmPort {

    private static final Logger LOG = LoggerFactory.getLogger(GitScmAdapter.class);

    /**
     * Build output and tooling caches. The Maven and Gradle wrapper jars are deliberately NOT
     * excluded: they are part of the repository and the harness needs them to reproduce the build.
     */
    public static final List<String> DEFAULT_EXCLUDES = List.of(
            "/.git/", "/target/", "/build/", "/out/", "/bin/", "/.idea/", "/.gradle/",
            "/node_modules/");

    private final ProcessRunner runner = new ProcessRunner();

    @Override
    public boolean isGitBacked(Path root) {
        return Files.isDirectory(root.resolve(".git")) || Files.isRegularFile(root.resolve(".git"));
    }

    @Override
    public SourceProvenance captureProvenance(Path sourceRoot) {
        String manifestHash = computeContentManifestHash(sourceRoot, DEFAULT_EXCLUDES);
        if (!isGitBacked(sourceRoot)) {
            return new SourceProvenance("PLAIN_DIRECTORY", null, null, null, null, manifestHash,
                    Instant.now().toString());
        }
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(sourceRoot.resolve(".git").toFile())
                .readEnvironment()
                .build()) {
            ObjectId head = repository.resolve(Constants.HEAD);
            String commit = head == null ? null : head.getName();
            String tree = null;
            if (head != null) {
                try (RevWalk walk = new RevWalk(repository)) {
                    RevCommit revCommit = walk.parseCommit(head);
                    tree = revCommit.getTree().getName();
                }
            }
            String remote = repository.getConfig().getString("remote", "origin", "url");
            return new SourceProvenance("GIT", remote, repository.getBranch(), commit, tree,
                    manifestHash, Instant.now().toString());
        } catch (IOException e) {
            LOG.warn("Cannot read Git provenance from {}: {}", sourceRoot, e.getMessage());
            return new SourceProvenance("PLAIN_DIRECTORY", null, null, null, null, manifestHash,
                    Instant.now().toString());
        }
    }

    @Override
    public String snapshot(Path sourceRoot, Path destination, List<String> excludes) {
        List<String> effective = excludes == null || excludes.isEmpty() ? DEFAULT_EXCLUDES : excludes;
        try {
            Files.createDirectories(destination);
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (isExcluded(sourceRoot, dir, effective, true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(destination.resolve(sourceRoot.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        // Symlinks are not followed: they are an escape hatch out of the workspace.
                        return FileVisitResult.CONTINUE;
                    }
                    if (!isExcluded(sourceRoot, file, effective, false)) {
                        Path target = destination.resolve(sourceRoot.relativize(file).toString());
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        // A snapshot taken from a read-only source must itself be writable: the
                        // read-only marking belongs to the original workspace alone, and copying it
                        // forward would make build output unwritable in every derived workspace.
                        target.toFile().setWritable(true, true);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot snapshot " + sourceRoot + " to " + destination, e);
        }
        return computeContentManifestHash(destination, effective);
    }

    @Override
    public void initCheckpointRepository(Path workTree, Path gitDir, String message) {
        try {
            Files.createDirectories(gitDir.getParent() == null ? gitDir : gitDir.getParent());
            try (Git git = Git.init().setDirectory(workTree.toFile()).setGitDir(gitDir.toFile()).call()) {
                git.getRepository().getConfig().setString("user", null, "name", "bootshift");
                git.getRepository().getConfig().setString("user", null, "email",
                        "bootshift@localhost");
                git.getRepository().getConfig().setBoolean("core", null, "autocrlf", false);
                git.getRepository().getConfig().save();
                git.add().addFilepattern(".").call();
                git.commit().setMessage(message)
                        .setAuthor("bootshift", "bootshift@localhost")
                        .setSign(false)
                        .call();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize internal checkpoint repository at " + workTree, e);
        }
    }

    @Override
    public Checkpoint checkpoint(Path workTree, Path gitDir, String name, String message) {
        try (Git git = open(workTree, gitDir)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            RevCommit commit;
            if (git.status().call().isClean()) {
                try (RevWalk walk = new RevWalk(git.getRepository())) {
                    commit = walk.parseCommit(git.getRepository().resolve(Constants.HEAD));
                }
            } else {
                commit = git.commit().setMessage(message)
                        .setAuthor("bootshift", "bootshift@localhost")
                        .setSign(false)
                        .call();
            }
            git.tag().setName(name).setObjectId(commit).setForceUpdate(true).call();
            return new Checkpoint(name, commit.getName(), Instant.now().toString());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create checkpoint " + name, e);
        }
    }

    @Override
    public Optional<Checkpoint> findCheckpoint(Path workTree, Path gitDir, String name) {
        try (Git git = open(workTree, gitDir)) {
            ObjectId id = git.getRepository().resolve(name);
            if (id == null) {
                return Optional.empty();
            }
            try (RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit commit = walk.parseCommit(id);
                return Optional.of(new Checkpoint(name, commit.getName(),
                        Instant.ofEpochSecond(commit.getCommitTime()).toString()));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void rollbackTo(Path workTree, Path gitDir, String checkpointName) {
        try (Git git = open(workTree, gitDir)) {
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                    .setRef(checkpointName).call();
            git.clean().setCleanDirectories(true).setForce(true).call();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot roll back to " + checkpointName, e);
        }
    }

    @Override
    public List<RenameRecord> detectRenames(Path workTree, Path gitDir, String fromRef, String toRef) {
        List<RenameRecord> renames = new ArrayList<>();
        try (Git git = open(workTree, gitDir)) {
            Repository repository = git.getRepository();
            ObjectId from = repository.resolve(fromRef + "^{tree}");
            ObjectId to = repository.resolve(toRef + "^{tree}");
            if (from == null || to == null) {
                return renames;
            }
            try (var reader = repository.newObjectReader();
                 DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                CanonicalTreeParser fromTree = new CanonicalTreeParser();
                fromTree.reset(reader, from);
                CanonicalTreeParser toTree = new CanonicalTreeParser();
                toTree.reset(reader, to);
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);
                List<DiffEntry> entries = formatter.scan(fromTree, toTree);
                RenameDetector detector = new RenameDetector(repository);
                detector.addAll(entries);
                for (DiffEntry entry : detector.compute()) {
                    if (entry.getChangeType() == DiffEntry.ChangeType.RENAME
                            || entry.getChangeType() == DiffEntry.ChangeType.COPY) {
                        renames.add(new RenameRecord(entry.getOldPath(), entry.getNewPath(), entry.getScore()));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Rename detection failed between {} and {}: {}", fromRef, toRef, e.getMessage());
        }
        return renames;
    }

    @Override
    public String diff(Path workTree, Path gitDir, String fromRef, String toRef) {
        try (Git git = open(workTree, gitDir)) {
            Repository repository = git.getRepository();
            ObjectId from = repository.resolve(fromRef + "^{tree}");
            ObjectId to = repository.resolve(toRef + "^{tree}");
            if (from == null || to == null) {
                return "";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (var reader = repository.newObjectReader();
                 DiffFormatter formatter = new DiffFormatter(out)) {
                CanonicalTreeParser fromTree = new CanonicalTreeParser();
                fromTree.reset(reader, from);
                CanonicalTreeParser toTree = new CanonicalTreeParser();
                toTree.reset(reader, to);
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);
                formatter.format(fromTree, toTree);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute diff " + fromRef + ".." + toRef, e);
        }
    }

    @Override
    public List<String> formatPatchSeries(Path workTree, Path gitDir, String fromRef, String toRef) {
        List<String> patches = new ArrayList<>();
        try (Git git = open(workTree, gitDir)) {
            Repository repository = git.getRepository();
            ObjectId fromId = repository.resolve(fromRef);
            ObjectId toId = repository.resolve(toRef);
            if (fromId == null || toId == null) {
                return patches;
            }
            List<RevCommit> commits = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repository)) {
                walk.markStart(walk.parseCommit(toId));
                walk.markUninteresting(walk.parseCommit(fromId));
                walk.forEach(commits::add);
            }
            java.util.Collections.reverse(commits);
            for (RevCommit commit : commits) {
                if (commit.getParentCount() == 0) {
                    continue;
                }
                patches.add(diff(workTree, gitDir, commit.getParent(0).getName(), commit.getName()));
            }
        } catch (Exception e) {
            LOG.warn("Cannot format patch series: {}", e.getMessage());
        }
        return patches;
    }

    @Override
    public String currentTreeHash(Path workTree, Path gitDir) {
        try (Git git = open(workTree, gitDir)) {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            if (head == null) {
                return null;
            }
            try (RevWalk walk = new RevWalk(git.getRepository())) {
                return walk.parseCommit(head).getTree().getName();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private Git open(Path workTree, Path gitDir) throws IOException {
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setWorkTree(workTree.toFile())
                .build();
        return new Git(repository);
    }

    private static boolean isExcluded(Path root, Path candidate, List<String> excludes, boolean directory) {
        String relative = "/" + root.relativize(candidate).toString().replace((char) 92, '/');
        if (directory) {
            relative = relative + "/";
        }
        for (String exclude : excludes) {
            if (relative.contains(exclude)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deterministic content manifest hash over a directory tree. Two runs over identical bytes
     * produce identical hashes regardless of filesystem ordering or platform separators.
     */
    public static String computeContentManifestHash(Path root, List<String> excludes) {
        List<String> entries = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> !isExcluded(root, p, excludes, false))
                    .sorted()
                    .forEach(p -> {
                        try {
                            entries.add(root.relativize(p).toString().replace((char) 92, '/')
                                    + ":" + Hashing.sha256File(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot compute content manifest for " + root, e);
        }
        java.util.Collections.sort(entries);
        return Hashing.manifestHash(entries);
    }

    /** Native git availability, used to record which mechanism produced provenance. */
    public boolean nativeGitAvailable() {
        return ProcessRunner.which("git") != null;
    }
}
