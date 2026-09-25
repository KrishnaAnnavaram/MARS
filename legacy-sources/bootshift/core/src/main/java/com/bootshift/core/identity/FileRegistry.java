package com.bootshift.core.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Ids;
import com.bootshift.core.util.Json;
import com.bootshift.core.util.Similarity;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The File Registry owns file identity for the whole run (R2).
 *
 * <p>Reattachment order is fixed by spec 11.2 and implemented literally in
 * {@link #reattach(ObservedFile, RenameHint)}:
 *
 * <ol>
 *   <li>exact current path</li>
 *   <li>provider-reported rename mapping</li>
 *   <li>exact content hash</li>
 *   <li>similarity at or above the configured threshold</li>
 *   <li>otherwise allocate a new FILE_ID</li>
 * </ol>
 *
 * <p>Because identity is allocated rather than derived, losing this registry loses lineage. That
 * trade-off is documented in ADR-001.
 */
public final class FileRegistry {

    /** A file as seen on disk during a scan, before identity is decided. */
    public record ObservedFile(String module, String path, String sha256, FileRole role,
                               long sizeBytes, String content) {
    }

    /** A rename mapping asserted by a transformation provider or by Git. */
    public record RenameHint(String fromPath, String toPath, RenameSource source, double confidence) {
    }

    /** How one observed file acquired its identity. */
    public record Attachment(String fileId, String path, String previousPath, RenameSource source,
                             double confidence, boolean newlyAllocated, String rule) {
    }

    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.72;

    private static final char BACKSLASH = (char) 92;

    private final Map<String, FileRecord> byId = new LinkedHashMap<>();
    private final Map<String, String> pathToId = new LinkedHashMap<>();
    private final Map<String, List<String>> hashToIds = new LinkedHashMap<>();
    private final Map<String, String> contentCache = new LinkedHashMap<>();
    private final double similarityThreshold;
    private String registrySealHash;

    public FileRegistry() {
        this(DEFAULT_SIMILARITY_THRESHOLD);
    }

    public FileRegistry(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public double similarityThreshold() {
        return similarityThreshold;
    }

    public int size() {
        return byId.size();
    }

    public Optional<FileRecord> byId(String fileId) {
        return Optional.ofNullable(byId.get(fileId));
    }

    public Optional<FileRecord> byPath(String path) {
        String id = pathToId.get(normalize(path));
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public List<FileRecord> all() {
        return new ArrayList<>(byId.values());
    }

    public List<FileRecord> active() {
        return byId.values().stream().filter(r -> r.getStatus() == FileStatus.ACTIVE).toList();
    }

    /** First-scan allocation: every observed file gets a fresh identity. */
    public Attachment allocate(ObservedFile observed) {
        FileRecord record = new FileRecord(Ids.fileId(), observed.module(), normalize(observed.path()),
                observed.sha256(), observed.role(), observed.sizeBytes());
        record.setLanguage(languageOf(observed.role()));
        record.getVersions().add(new FileRecord.Version(null, record.getCurrentPath(),
                observed.sha256(), Instant.now().toString(), "INVENTORY_BASELINE"));
        index(record, observed.content());
        return new Attachment(record.getFileId(), record.getCurrentPath(), null, RenameSource.NONE,
                1.0, true, "ALLOCATE_NEW");
    }

    /**
     * Resolves an observed file against existing identities. The {@code hint} is consulted only at
     * step 2 and only when the provider or Git actually asserted the rename.
     */
    public Attachment reattach(ObservedFile observed, RenameHint hint) {
        String path = normalize(observed.path());

        // 1. exact current path
        String existingId = pathToId.get(path);
        if (existingId != null) {
            FileRecord record = byId.get(existingId);
            updateContent(record, observed);
            return new Attachment(record.getFileId(), path, null, RenameSource.NONE, 1.0, false,
                    "EXACT_PATH");
        }

        // 2. provider or Git reported rename
        if (hint != null && normalize(hint.toPath()).equals(path)) {
            String fromId = pathToId.get(normalize(hint.fromPath()));
            if (fromId != null) {
                FileRecord record = byId.get(fromId);
                String previous = record.getCurrentPath();
                repath(record, path);
                record.setPreviousPath(previous);
                record.setRenameSource(hint.source());
                record.setRenameConfidence(hint.confidence());
                updateContent(record, observed);
                return new Attachment(record.getFileId(), path, previous, hint.source(),
                        hint.confidence(), false, "PROVIDER_RENAME");
            }
        }

        // 3. exact content hash among files whose old path no longer exists
        List<String> sameHash = new ArrayList<>(hashToIds.getOrDefault(observed.sha256(), List.of()));
        for (String candidateId : sameHash) {
            FileRecord candidate = byId.get(candidateId);
            if (candidate != null && candidate.getStatus() == FileStatus.ACTIVE
                    && !candidate.getCurrentPath().equals(path)
                    && !pathStillObserved(candidate.getCurrentPath())) {
                String previous = candidate.getCurrentPath();
                repath(candidate, path);
                candidate.setPreviousPath(previous);
                candidate.setRenameSource(RenameSource.SIMILARITY);
                candidate.setRenameConfidence(1.0);
                updateContent(candidate, observed);
                return new Attachment(candidate.getFileId(), path, previous, RenameSource.SIMILARITY,
                        1.0, false, "EXACT_CONTENT_HASH");
            }
        }

        // 4. similarity fallback
        if (observed.content() != null) {
            Optional<Map.Entry<String, Double>> best = byId.values().stream()
                    .filter(r -> r.getStatus() == FileStatus.ACTIVE)
                    .filter(r -> r.getRole() == observed.role())
                    .filter(r -> !observedPaths.contains(r.getCurrentPath()))
                    .map(r -> Map.entry(r.getFileId(),
                            Similarity.compare(contentCache.getOrDefault(r.getFileId(), ""), observed.content())))
                    .filter(e -> e.getValue() >= similarityThreshold)
                    .max(Comparator.comparingDouble(Map.Entry::getValue));
            if (best.isPresent()) {
                FileRecord candidate = byId.get(best.get().getKey());
                String previous = candidate.getCurrentPath();
                repath(candidate, path);
                candidate.setPreviousPath(previous);
                candidate.setRenameSource(RenameSource.SIMILARITY);
                candidate.setRenameConfidence(best.get().getValue());
                updateContent(candidate, observed);
                return new Attachment(candidate.getFileId(), path, previous, RenameSource.SIMILARITY,
                        best.get().getValue(), false, "SIMILARITY");
            }
        }

        // 5. new identity
        return allocate(observed);
    }

    /**
     * Paths confirmed present in the scan currently in progress. A candidate whose old path is still
     * on disk cannot have been renamed away, so it is not a reattachment target.
     */
    private final java.util.Set<String> observedPaths = new java.util.LinkedHashSet<>();

    /** Declares which paths the in-progress scan actually saw, before reattaching any of them. */
    public void beginScan(List<String> currentPaths) {
        observedPaths.clear();
        currentPaths.forEach(p -> observedPaths.add(normalize(p)));
    }

    private boolean pathStillObserved(String path) {
        return observedPaths.contains(path);
    }

    /** Records a split: the highest-similarity descendant keeps the identity (spec 11.4). */
    public Attachment recordSplit(String originalFileId, ObservedFile sibling, String changeId) {
        Attachment attachment = allocate(sibling);
        FileRecord record = byId.get(attachment.fileId());
        record.setSplitFrom(originalFileId);
        record.setCreatedByChange(changeId);
        record.getChangeIds().add(changeId);
        return attachment;
    }

    /** Records a merge: sources keep queryable identity but stop being active (spec 11.5). */
    public void recordMerge(String sourceFileId, String targetFileId, String changeId) {
        FileRecord source = byId.get(sourceFileId);
        if (source == null) {
            throw new IllegalArgumentException("Unknown merge source " + sourceFileId);
        }
        source.setMergedInto(targetFileId);
        source.setStatus(FileStatus.MERGED_AWAY);
        source.getChangeIds().add(changeId);
        pathToId.remove(source.getCurrentPath());
    }

    /** Deletion never erases identity (spec 11.6). */
    public void recordDelete(String fileId, String changeId) {
        FileRecord record = byId.get(fileId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown file " + fileId);
        }
        record.setStatus(FileStatus.DELETED);
        record.setDeletedByChange(changeId);
        record.getChangeIds().add(changeId);
        pathToId.remove(record.getCurrentPath());
    }

    /** Appends a lineage version after a mutation is applied. */
    public void appendVersion(String fileId, String changeId, String path, String sha256, String reason) {
        FileRecord record = byId.get(fileId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown file " + fileId);
        }
        String normalized = normalize(path);
        if (!record.getCurrentPath().equals(normalized)) {
            String previous = record.getCurrentPath();
            repath(record, normalized);
            record.setPreviousPath(previous);
        }
        reindexHash(record, sha256);
        record.setCurrentSha256(sha256);
        record.getChangeIds().add(changeId);
        record.getVersions().add(new FileRecord.Version(changeId, normalized, sha256,
                Instant.now().toString(), reason));
    }

    public void attachSymbol(String fileId, String symbolId) {
        FileRecord record = byId.get(fileId);
        if (record != null) {
            record.getSymbolIds().add(symbolId);
        }
    }

    public void clearSymbols() {
        byId.values().forEach(r -> r.getSymbolIds().clear());
    }

    private void index(FileRecord record, String content) {
        byId.put(record.getFileId(), record);
        pathToId.put(record.getCurrentPath(), record.getFileId());
        hashToIds.computeIfAbsent(record.getCurrentSha256(), k -> new ArrayList<>()).add(record.getFileId());
        if (content != null) {
            contentCache.put(record.getFileId(), content);
        }
    }

    private void repath(FileRecord record, String newPath) {
        pathToId.remove(record.getCurrentPath());
        record.setCurrentPath(newPath);
        pathToId.put(newPath, record.getFileId());
    }

    private void updateContent(FileRecord record, ObservedFile observed) {
        if (!observed.sha256().equals(record.getCurrentSha256())) {
            reindexHash(record, observed.sha256());
            record.setCurrentSha256(observed.sha256());
        }
        record.setSizeBytes(observed.sizeBytes());
        if (observed.content() != null) {
            contentCache.put(record.getFileId(), observed.content());
        }
    }

    private void reindexHash(FileRecord record, String newHash) {
        List<String> old = hashToIds.get(record.getCurrentSha256());
        if (old != null) {
            old.remove(record.getFileId());
        }
        hashToIds.computeIfAbsent(newHash, k -> new ArrayList<>()).add(record.getFileId());
    }

    private static String languageOf(FileRole role) {
        return switch (role) {
            case JAVA_MAIN, JAVA_TEST -> "java";
            case KOTLIN -> "kotlin";
            case GROOVY -> "groovy";
            case MAVEN_BUILD, CONFIG_XML -> "xml";
            case CONFIG_YAML, K8S, CI_PIPELINE -> "yaml";
            case CONFIG_PROPERTIES -> "properties";
            case SQL_MIGRATION -> "sql";
            case GRADLE_BUILD, SETTINGS -> "gradle";
            default -> "text";
        };
    }

    /** Registry paths are always forward-slash normalized so Windows and POSIX runs agree. */
    public static String normalize(String path) {
        return path.replace(BACKSLASH, '/');
    }

    /** Seals the registry: a stable hash over identity-to-baseline bindings. */
    /**
     * Seals the registry and returns its run-scoped seal hash.
     *
     * <p>The manifest includes FILE_ID, so this value is stable within a run and different between
     * runs even over byte-identical input. That is what makes it useful for R23: any change to an
     * identity after the seal is detectable. To compare two runs, use {@link #contentManifestHash()}.
     */
    public String seal() {
        List<String> entries = byId.values().stream()
                .map(r -> r.getFileId() + ":" + r.getBaselinePath() + ":" + r.getBaselineSha256())
                .sorted()
                .toList();
        this.registrySealHash = Hashing.manifestHash(entries);
        return registrySealHash;
    }

    /**
     * Hash of what the registry describes, with the run-scoped identities removed: path and content
     * only. Two runs over the same repository produce the same value, so a difference here means the
     * repository changed rather than that a new run allocated new identifiers.
     */
    public String contentManifestHash() {
        List<String> entries = byId.values().stream()
                .map(r -> r.getBaselinePath() + ":" + r.getBaselineSha256())
                .sorted()
                .toList();
        return Hashing.manifestHash(entries);
    }

    public String sealHash() {
        return registrySealHash;
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("similarity_threshold", similarityThreshold);
        node.put("seal_hash", registrySealHash);
        node.put("content_manifest_hash", contentManifestHash());
        node.put("count", byId.size());
        node.set("files", Json.toTree(byId.values()));
        return node;
    }

    public static FileRegistry fromNode(JsonNode node) {
        FileRegistry registry = new FileRegistry(
                node.path("similarity_threshold").asDouble(DEFAULT_SIMILARITY_THRESHOLD));
        for (JsonNode fileNode : node.path("files")) {
            FileRecord record = Json.convert(fileNode, FileRecord.class);
            registry.byId.put(record.getFileId(), record);
            if (record.getStatus() == FileStatus.ACTIVE) {
                registry.pathToId.put(record.getCurrentPath(), record.getFileId());
            }
            registry.hashToIds.computeIfAbsent(record.getCurrentSha256(), k -> new ArrayList<>())
                    .add(record.getFileId());
        }
        registry.registrySealHash = node.path("seal_hash").asText(null);
        return registry;
    }

    public static FileRegistry load(Path path) {
        return fromNode(Json.read(path));
    }

    /** Rehydrates the content cache so similarity reattachment works on a resumed run. */
    public void primeContent(String fileId, String content) {
        contentCache.put(fileId, content);
    }
}
