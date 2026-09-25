package com.bootshift.adapters.compat;

import com.bootshift.adapters.http.HttpFetcher;
import com.bootshift.core.util.Hashing;
import com.bootshift.ports.compat.VersionSpacePort;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version-space acquisition from a Maven repository.
 *
 * <p>Uses {@code maven-metadata.xml} for published versions and a HEAD-equivalent GET for
 * artifact-existence probes. Every answer records whether it came from the network or the cache, so
 * an offline run never silently degrades into an assumption.
 */
public final class MavenCentralVersionSpaceAdapter implements VersionSpacePort {

    private static final String CENTRAL = "https://repo1.maven.org/maven2";

    private static final Pattern VERSION_TAG = Pattern.compile("<version>\\s*([^<]+?)\\s*</version>");
    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    private final HttpFetcher fetcher;
    private final String baseUrl;

    public MavenCentralVersionSpaceAdapter(HttpFetcher fetcher) {
        this(fetcher, CENTRAL);
    }

    public MavenCentralVersionSpaceAdapter(HttpFetcher fetcher, String baseUrl) {
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean online() {
        return fetcher.networkEnabled();
    }

    @Override
    public Optional<ArtifactVersions> versions(String groupId, String artifactId) {
        String url = baseUrl + "/" + groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
        Optional<HttpFetcher.Fetched> fetched = fetcher.get(url);
        if (fetched.isEmpty() || fetched.get().statusCode() >= 400) {
            return Optional.empty();
        }
        String body = new String(fetched.get().body(), StandardCharsets.UTF_8);
        List<String> versions = new ArrayList<>();
        String versioningBlock = between(body, "<versions>", "</versions>");
        Matcher matcher = VERSION_TAG.matcher(versioningBlock == null ? body : versioningBlock);
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return Optional.of(new ArtifactVersions(groupId, artifactId, versions, baseUrl,
                Instant.now().toString(), fetched.get().contentHash(), !fetched.get().fromCache()));
    }

    @Override
    public ArtifactExistence exists(String groupId, String artifactId, String version) {
        String url = pomUrl(groupId, artifactId, version);
        Optional<HttpFetcher.Fetched> fetched = fetcher.get(url);
        if (fetched.isEmpty()) {
            return new ArtifactExistence(groupId, artifactId, version, false, baseUrl,
                    "Unreachable or blocked; existence is UNKNOWN, not false");
        }
        boolean exists = fetched.get().statusCode() == 200 && fetched.get().body().length > 0;
        return new ArtifactExistence(groupId, artifactId, version, exists, baseUrl,
                exists ? "POM present at " + url : "HTTP " + fetched.get().statusCode());
    }

    @Override
    public Optional<BomSnapshot> bom(String groupId, String artifactId, String version) {
        Optional<HttpFetcher.Fetched> fetched = fetcher.get(pomUrl(groupId, artifactId, version));
        if (fetched.isEmpty() || fetched.get().statusCode() != 200) {
            return Optional.empty();
        }
        List<BomEntry> entries = new ArrayList<>();
        collectManagedEntries(groupId, artifactId, version,
                new String(fetched.get().body(), StandardCharsets.UTF_8),
                entries, new LinkedHashSet<>(), 0);
        return Optional.of(new BomSnapshot(groupId, artifactId, version, entries,
                fetched.get().contentHash(), Instant.now().toString(), !fetched.get().fromCache()));
    }

    /** How deep a chain of imported BOMs is followed before the traversal is abandoned. */
    private static final int MAX_BOM_IMPORT_DEPTH = 4;

    /**
     * Collects managed versions from a BOM, following {@code <scope>import</scope>} entries.
     *
     * <p>Reading only a BOM's own entries is enough for {@code spring-boot-dependencies}, which
     * manages most of its estate directly. {@code spring-cloud-dependencies} is the opposite: it is
     * almost entirely imports, so a reader that stops at the first level sees none of the Spring
     * Cloud artifacts at all — and a migration harness that cannot see them cannot see that the
     * train removed a type the application uses.
     *
     * <p>An import that cannot be fetched is skipped rather than failing the snapshot: a partial
     * BOM is still useful, and the coordinates it does not cover surface downstream as "not managed
     * by both BOMs" rather than as a silently wrong version.
     */
    private void collectManagedEntries(String groupId, String artifactId, String version,
                                       String body, List<BomEntry> entries,
                                       java.util.Set<String> visited, int depth) {
        if (!visited.add(groupId + ":" + artifactId + ":" + version) || depth > MAX_BOM_IMPORT_DEPTH) {
            return;
        }
        java.util.Map<String, String> properties = new java.util.LinkedHashMap<>(parseProperties(body));
        // A sub-BOM versions its own modules as ${project.version}. Maven resolves that against the
        // POM being read, so the recursion has to carry it down - otherwise every artifact inside an
        // imported BOM comes back with a literal "${project.version}" and matches nothing.
        properties.putIfAbsent("project.version", version);
        properties.putIfAbsent("pom.version", version);
        properties.putIfAbsent("project.groupId", groupId);
        String management = between(body, "<dependencyManagement>", "</dependencyManagement>");
        if (management == null) {
            return;
        }
        Matcher blocks = DEPENDENCY_BLOCK.matcher(management);
        List<String[]> imports = new ArrayList<>();
        while (blocks.find()) {
            String block = blocks.group(1);
            String group = tag(block, "groupId");
            String artifact = tag(block, "artifactId");
            String depVersion = resolve(tag(block, "version"), properties);
            if (group == null || artifact == null) {
                continue;
            }
            String resolvedGroup = resolve(group, properties);
            if ("import".equals(tag(block, "scope")) && depVersion != null) {
                imports.add(new String[] {resolvedGroup, artifact, depVersion});
                continue;
            }
            entries.add(new BomEntry(resolvedGroup, artifact, depVersion));
        }
        for (String[] imported : imports) {
            Optional<HttpFetcher.Fetched> nested =
                    fetcher.get(pomUrl(imported[0], imported[1], imported[2]));
            if (nested.isEmpty() || nested.get().statusCode() != 200) {
                continue;
            }
            collectManagedEntries(imported[0], imported[1], imported[2],
                    new String(nested.get().body(), StandardCharsets.UTF_8),
                    entries, visited, depth + 1);
        }
    }

    @Override
    public Optional<byte[]> fetchArtifactFile(String groupId, String artifactId, String version,
                                              String classifier, String extension) {
        StringBuilder url = new StringBuilder(baseUrl)
                .append('/').append(groupId.replace('.', '/'))
                .append('/').append(artifactId)
                .append('/').append(version)
                .append('/').append(artifactId).append('-').append(version);
        if (classifier != null && !classifier.isBlank()) {
            url.append('-').append(classifier);
        }
        url.append('.').append(extension);
        return fetcher.get(url.toString())
                .filter(f -> f.statusCode() == 200 && f.body().length > 0)
                .map(HttpFetcher.Fetched::body);
    }

    private String pomUrl(String groupId, String artifactId, String version) {
        return baseUrl + "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version
                + "/" + artifactId + "-" + version + ".pom";
    }

    private java.util.Map<String, String> parseProperties(String pom) {
        java.util.Map<String, String> properties = new java.util.LinkedHashMap<>();
        String block = between(pom, "<properties>", "</properties>");
        if (block == null) {
            return properties;
        }
        Matcher matcher = Pattern.compile("<([\\w.\\-]+)>\\s*([^<]*?)\\s*</\\1>").matcher(block);
        while (matcher.find()) {
            properties.put(matcher.group(1), matcher.group(2));
        }
        return properties;
    }

    /** Resolves one level of property indirection; unresolved references are returned verbatim. */
    private String resolve(String value, java.util.Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        Matcher matcher = PROPERTY_REF.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        String key = matcher.group(1);
        return properties.getOrDefault(key, value);
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(close, start);
        return end < 0 ? null : text.substring(start + open.length(), end);
    }

    private static String tag(String text, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Stable hash of a BOM snapshot, used as evidence provenance in the compatibility registry. */
    public static String snapshotHash(BomSnapshot snapshot) {
        List<String> lines = snapshot.entries().stream()
                .map(e -> e.groupId() + ":" + e.artifactId() + ":" + e.version())
                .sorted()
                .toList();
        return Hashing.manifestHash(lines);
    }
}
