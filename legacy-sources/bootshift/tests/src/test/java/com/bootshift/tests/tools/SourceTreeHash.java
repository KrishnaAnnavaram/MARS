package com.bootshift.tests.tools;

import com.bootshift.adapters.scm.GitScmAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Computes the deterministic content hash of a source tree, using the harness's own algorithm.
 *
 * <p>Exists so the immutability check on {@code ./src} is the same computation the pipeline performs
 * internally rather than a second, independently written one. Two different hashes of the same tree
 * would prove nothing about each other.
 */
public final class SourceTreeHash {

    private SourceTreeHash() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: SourceTreeHash <tree> [outputFile]");
            System.exit(2);
        }
        Path tree = Path.of(args[0]).toAbsolutePath().normalize();
        String hash = GitScmAdapter.computeContentManifestHash(tree, GitScmAdapter.DEFAULT_EXCLUDES);

        long files;
        try (var stream = Files.walk(tree)) {
            files = stream.filter(Files::isRegularFile).count();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(hash).append("  ").append(tree.toString().replace((char) 92, '/'))
                .append(System.lineSeparator());
        sb.append("# algorithm: sha256 over sorted \"<relative-path>:<sha256-of-file>\" lines")
                .append(System.lineSeparator());
        sb.append("# excludes: ").append(GitScmAdapter.DEFAULT_EXCLUDES)
                .append(System.lineSeparator());
        sb.append("# files walked (before excludes): ").append(files).append(System.lineSeparator());
        sb.append("# computed at: ").append(java.time.Instant.now()).append(System.lineSeparator());

        System.out.print(sb);
        if (args.length > 1) {
            Path output = Path.of(args[1]);
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.writeString(output, sb.toString(), StandardCharsets.UTF_8);
        }
    }
}
