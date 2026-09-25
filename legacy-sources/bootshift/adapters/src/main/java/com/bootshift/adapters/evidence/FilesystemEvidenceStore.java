package com.bootshift.adapters.evidence;

import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.util.Hashing;
import com.bootshift.ports.evidence.EvidenceObjectStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Content-addressed filesystem evidence store.
 *
 * <p>Layout is {@code objects/<first two hex>/<full hash>}, so identical bytes are stored once and a
 * manifest sealed here stays verifiable against any other store holding the same content.
 */
public final class FilesystemEvidenceStore implements EvidenceObjectStore {

    private final Path root;

    public FilesystemEvidenceStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root.resolve("objects"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create evidence store at " + root, e);
        }
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public StoredObject put(String kind, String suggestedName, byte[] content,
                            EvidenceManifest.Classification classification, String retentionClass,
                            String producedBy) {
        String hash = Hashing.sha256(content);
        Path target = objectPath(hash);
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.write(target, content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store evidence object " + hash, e);
        }
        return new StoredObject(evidenceId(kind, hash), hash, content.length, relative(target));
    }

    @Override
    public StoredObject putFile(String kind, Path file, EvidenceManifest.Classification classification,
                                String retentionClass, String producedBy) {
        try {
            String hash = Hashing.sha256File(file);
            Path target = objectPath(hash);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredObject(evidenceId(kind, hash), hash, Files.size(target), relative(target));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store evidence file " + file, e);
        }
    }

    @Override
    public Optional<byte[]> get(String evidenceId) {
        String hash = hashOf(evidenceId);
        Path target = objectPath(hash);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read evidence object " + evidenceId, e);
        }
    }

    @Override
    public boolean exists(String evidenceId) {
        return Files.isRegularFile(objectPath(hashOf(evidenceId)));
    }

    private Path objectPath(String hash) {
        return root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
    }

    private String relative(Path target) {
        return root.relativize(target).toString().replace((char) 92, '/');
    }

    /**
     * Evidence ids embed the kind for readability and carry the full content hash, so resolution is
     * a direct path lookup rather than a scan.
     */
    public static String evidenceId(String kind, String hash) {
        return "EV-" + kind.toUpperCase(java.util.Locale.ROOT).replace('_', '-') + "-" + hash;
    }

    private String hashOf(String evidenceId) {
        int last = evidenceId.lastIndexOf('-');
        return last < 0 ? evidenceId : evidenceId.substring(last + 1);
    }
}
