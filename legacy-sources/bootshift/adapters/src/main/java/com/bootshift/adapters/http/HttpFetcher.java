package com.bootshift.adapters.http;

import com.bootshift.core.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Content-addressed HTTP fetch with an egress allowlist.
 *
 * <p>Network egress is restricted to hosts the harness is explicitly allowed to talk to (spec 48).
 * Every response is cached on disk under its content hash, so a run is reproducible offline and a
 * later reviewer can see exactly what was retrieved.
 */
public final class HttpFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(HttpFetcher.class);

    public record Fetched(String url, int statusCode, byte[] body, String contentHash, String etag,
                          String lastModified, String mediaType, boolean fromCache, String cachePath) {
    }

    private static final Set<String> DEFAULT_ALLOWED_HOSTS = new LinkedHashSet<>(List.of(
            "repo1.maven.org",
            "repo.maven.apache.org",
            "search.maven.org",
            "docs.spring.io",
            "spring.io",
            "github.com",
            "raw.githubusercontent.com",
            "api.github.com",
            "endoflife.date",
            "central.sonatype.com"));

    private final HttpClient client;
    private final Path cacheRoot;
    /** Redirect hops permitted before a chain is treated as hostile or broken. */
    private static final int MAX_REDIRECTS = 5;

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private final Set<String> allowedHosts;
    private final boolean networkEnabled;

    public HttpFetcher(Path cacheRoot, boolean networkEnabled) {
        this(cacheRoot, networkEnabled, DEFAULT_ALLOWED_HOSTS);
    }

    public HttpFetcher(Path cacheRoot, boolean networkEnabled, Set<String> allowedHosts) {
        this.cacheRoot = cacheRoot;
        this.networkEnabled = networkEnabled;
        this.allowedHosts = allowedHosts;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                // NEVER, not NORMAL. An automatically followed redirect is never checked against
                // the allowlist, so a single open redirect on an allowlisted host - github.com, say -
                // turns the egress control into a suggestion. Redirects are followed by hand below,
                // re-checking the allowlist on every hop.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create HTTP cache at " + cacheRoot, e);
        }
    }

    public static Set<String> defaultAllowedHosts() {
        return DEFAULT_ALLOWED_HOSTS;
    }

    public boolean networkEnabled() {
        return networkEnabled;
    }

    public boolean hostAllowed(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return allowedHosts.stream()
                    .anyMatch(allowed -> normalized.equals(allowed) || normalized.endsWith("." + allowed));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Returns the cached copy first; only contacts the network when the cache misses. */
    public Optional<Fetched> get(String url) {
        Path cacheFile = cacheFileFor(url);
        Path metaFile = Path.of(cacheFile + ".meta");
        if (Files.isRegularFile(cacheFile)) {
            try {
                byte[] body = Files.readAllBytes(cacheFile);
                String meta = Files.isRegularFile(metaFile)
                        ? Files.readString(metaFile, StandardCharsets.UTF_8) : "";
                return Optional.of(new Fetched(url, 200, body, Hashing.sha256(body),
                        metaValue(meta, "etag"), metaValue(meta, "last-modified"),
                        metaValue(meta, "content-type"), true, cacheFile.toString()));
            } catch (IOException e) {
                LOG.warn("Cannot read HTTP cache entry {}: {}", cacheFile, e.getMessage());
            }
        }
        if (!networkEnabled) {
            return Optional.empty();
        }
        if (!hostAllowed(url)) {
            LOG.warn("Egress refused for {} - host is not on the allowlist", url);
            return Optional.empty();
        }
        try {
            String currentUrl = url;
            HttpResponse<byte[]> response = null;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(currentUrl))
                        .timeout(Duration.ofSeconds(45))
                        .header("User-Agent", "bootshift/1.0 (migration harness)")
                        .GET()
                        .build();
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (!isRedirect(response.statusCode())) {
                    break;
                }
                String location = response.headers().firstValue("location").orElse(null);
                if (location == null) {
                    LOG.warn("Redirect from {} carried no Location header", currentUrl);
                    return Optional.empty();
                }
                String next = URI.create(currentUrl).resolve(location).toString();
                if (!hostAllowed(next)) {
                    LOG.warn("Egress refused: {} redirected to {}, which is not on the allowlist",
                            currentUrl, next);
                    return Optional.empty();
                }
                currentUrl = next;
                if (hop == MAX_REDIRECTS) {
                    LOG.warn("Too many redirects starting at {}", url);
                    return Optional.empty();
                }
            }
            if (response.statusCode() >= 400) {
                LOG.debug("Fetch {} returned {}", url, response.statusCode());
                return Optional.of(new Fetched(url, response.statusCode(), new byte[0], null,
                        null, null, null, false, null));
            }
            byte[] body = response.body();
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, body);
            String etag = response.headers().firstValue("etag").orElse(null);
            String lastModified = response.headers().firstValue("last-modified").orElse(null);
            String mediaType = response.headers().firstValue("content-type").orElse(null);
            Files.writeString(metaFile,
                    "url=" + url + "\netag=" + etag + "\nlast-modified=" + lastModified
                            + "\ncontent-type=" + mediaType + "\n", StandardCharsets.UTF_8);
            return Optional.of(new Fetched(url, response.statusCode(), body, Hashing.sha256(body),
                    etag, lastModified, mediaType, false, cacheFile.toString()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("Fetch failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private Path cacheFileFor(String url) {
        String key = Hashing.sha256(url);
        return cacheRoot.resolve(key.substring(0, 2)).resolve(key);
    }

    private static String metaValue(String meta, String key) {
        for (String line : meta.split("\n")) {
            if (line.startsWith(key + "=")) {
                String value = line.substring(key.length() + 1).trim();
                return "null".equals(value) || value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
