package com.bootshift.adapters.docs;

import com.bootshift.adapters.http.HttpFetcher;
import com.bootshift.core.util.Ids;
import com.bootshift.core.util.Json;
import com.bootshift.ports.docs.DocumentationPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Documentation registry adapter (Agent 07).
 *
 * <p>Retrieved documents are pinned by content hash into a local registry directory. The stored raw
 * body is the authority: no summary, LLM-produced or otherwise, may replace it.
 *
 * <p>A readable rendition is stored alongside the raw snapshot. Official Spring documentation is
 * served as HTML wiki pages, and asking a pattern extractor to read page chrome produces noise
 * rather than facts. Both files are retained so a reviewer can check the rendition against the
 * original.
 *
 * <p>When the network is off or a host is blocked, previously pinned documents still resolve from
 * the registry, so a run stays reproducible offline.
 */
public final class HttpDocumentationAdapter implements DocumentationPort {

    private final HttpFetcher fetcher;
    private final Path registryRoot;
    private final Map<String, DocumentRef> byId = new LinkedHashMap<>();
    private final Map<String, String> renditions = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public HttpDocumentationAdapter(HttpFetcher fetcher, Path registryRoot) {
        this.fetcher = fetcher;
        this.registryRoot = registryRoot;
        try {
            Files.createDirectories(registryRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create documentation registry at " + registryRoot, e);
        }
        loadExisting();
    }

    @Override
    public boolean online() {
        return fetcher.networkEnabled();
    }

    @Override
    public Optional<DocumentRef> retrieve(String url, String publisher, String component,
                                          String sourceVersion, String targetVersion,
                                          TrustLevel trustLevel) {
        Optional<HttpFetcher.Fetched> fetched = fetcher.get(url);
        if (fetched.isEmpty() || fetched.get().statusCode() != 200 || fetched.get().body().length == 0) {
            return Optional.empty();
        }
        HttpFetcher.Fetched result = fetched.get();
        String documentId = Ids.documentId(sequence.incrementAndGet());
        String body = new String(result.body(), StandardCharsets.UTF_8);
        String extracted = DocumentTextExtractor.extract(body);

        Path stored = registryRoot.resolve(documentId + ".body");
        Path rendition = registryRoot.resolve(documentId + ".text");
        try {
            Files.writeString(stored, body, StandardCharsets.UTF_8);
            Files.writeString(rendition, extracted, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot pin document " + url, e);
        }

        DocumentRef ref = new DocumentRef(documentId, publisher, component, sourceVersion, targetVersion,
                url, Instant.now().toString(), result.contentHash(), trustLevel, result.etag(),
                result.lastModified(), result.fromCache(), stored.toString().replace((char) 92, '/'),
                result.mediaType(), result.body().length, extracted.length());
        byId.put(documentId, ref);
        renditions.put(documentId, extracted);
        persistIndex();
        return Optional.of(ref);
    }

    /** Returns the readable rendition, falling back to the raw snapshot when none was produced. */
    @Override
    public Optional<String> body(String documentId) {
        String cached = renditions.get(documentId);
        if (cached != null) {
            return Optional.of(cached);
        }
        DocumentRef ref = byId.get(documentId);
        if (ref == null || ref.localPath() == null) {
            return Optional.empty();
        }
        try {
            Path rendition = registryRoot.resolve(documentId + ".text");
            Path source = Files.isRegularFile(rendition) ? rendition : Path.of(ref.localPath());
            return Optional.of(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Returns the raw snapshot, which is the authority for what the harness actually read. */
    public Optional<String> rawSnapshot(String documentId) {
        DocumentRef ref = byId.get(documentId);
        if (ref == null || ref.localPath() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(Path.of(ref.localPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<DocumentRef> cached() {
        return new ArrayList<>(byId.values());
    }

    private void persistIndex() {
        Json.write(registryRoot.resolve("document-index.json"), Json.toTree(byId.values()));
    }

    private void loadExisting() {
        Path index = registryRoot.resolve("document-index.json");
        if (!Files.isRegularFile(index)) {
            return;
        }
        for (com.fasterxml.jackson.databind.JsonNode node : Json.read(index)) {
            DocumentRef ref = Json.convert(node, DocumentRef.class);
            byId.put(ref.documentId(), ref);
            String numeric = ref.documentId().replaceAll("[^0-9]", "");
            if (!numeric.isEmpty()) {
                sequence.updateAndGet(current -> Math.max(current, Long.parseLong(numeric)));
            }
        }
    }
}
