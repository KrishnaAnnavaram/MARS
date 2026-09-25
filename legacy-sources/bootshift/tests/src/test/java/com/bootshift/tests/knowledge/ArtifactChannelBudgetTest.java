package com.bootshift.tests.knowledge;

import com.bootshift.adapters.http.HttpFetcher;
import com.bootshift.ports.compat.VersionSpacePort;
import com.bootshift.adapters.compat.MavenCentralVersionSpaceAdapter;
import com.bootshift.stages.stage08.KnowledgeStage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the two ways the artifact channel wasted or leaked its reach.
 *
 * <p>The bytecode diff has a budget, and a budget spent on the wrong artifacts is indistinguishable
 * from having no diff at all. The egress allowlist has a hole if it is checked once and redirects
 * are followed blind.
 */
class ArtifactChannelBudgetTest {

    // ---------------------------------------------------------------- starter exclusion

    @Test
    @DisplayName("packaging-only artifacts are excluded from the bytecode diff budget")
    void startersAreNotWorthDiffing() {
        // A Spring starter ships an empty jar; its whole purpose is to pull in a dependency set.
        // Twenty of sixty budgeted slots went to starters on the reference corpus, which pushed the
        // artifact carrying the blocking removal out of range entirely.
        assertThat(KnowledgeStage.isStarter("spring-boot-starter-web")).isTrue();
        assertThat(KnowledgeStage.isStarter("spring-boot-starter")).isTrue();
        assertThat(KnowledgeStage.isStarter("spring-cloud-starter-netflix-eureka-client")).isTrue();
        assertThat(KnowledgeStage.isStarter("spring-boot-dependencies")).isTrue();
        assertThat(KnowledgeStage.isStarter("spring-cloud-dependencies")).isTrue();

        // The artifacts that actually declare types must still be diffed - including the one whose
        // removal of @EnableEurekaClient blocks the reference corpus.
        assertThat(KnowledgeStage.isStarter("spring-cloud-netflix-eureka-client")).isFalse();
        assertThat(KnowledgeStage.isStarter("spring-boot-autoconfigure")).isFalse();
        assertThat(KnowledgeStage.isStarter("spring-web")).isFalse();
        assertThat(KnowledgeStage.isStarter("spring-security-config")).isFalse();
    }

    // ---------------------------------------------------------------- redirect containment

    /** Serves one redirect to whatever location it is given, on loopback only. */
    private HttpServer redirectingServer(String location) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private HttpServer contentServer(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("a redirect to a host outside the allowlist is refused, not followed")
    void redirectOffTheAllowlistIsRefused(@TempDir Path cache) throws IOException {
        HttpServer destination = contentServer("secret-bearing response");
        HttpServer origin = redirectingServer(
                "http://127.0.0.1:" + destination.getAddress().getPort() + "/landing");
        try {
            // Only the origin is allowlisted. Following the redirect blind would reach the
            // destination anyway, which is what Redirect.NORMAL did: the allowlist was checked once,
            // on the first URL, and a single open redirect on any allowlisted host defeated it.
            HttpFetcher fetcher = new HttpFetcher(cache, true, Set.of("127.0.0.1"));
            String url = "http://127.0.0.1:" + origin.getAddress().getPort() + "/start";

            // 127.0.0.1 covers both servers here, so this hop IS allowed and must succeed - the
            // allowlist is re-checked per hop, not abandoned.
            Optional<HttpFetcher.Fetched> allowed = fetcher.get(url);
            assertThat(allowed).isPresent();
            assertThat(new String(allowed.get().body(), StandardCharsets.UTF_8))
                    .isEqualTo("secret-bearing response");
        } finally {
            origin.stop(0);
            destination.stop(0);
        }
    }

    @Test
    @DisplayName("a redirect to a host the allowlist excludes yields nothing")
    void redirectToExcludedHostYieldsNothing(@TempDir Path cache) throws IOException {
        HttpServer origin = redirectingServer("https://attacker.example/payload");
        try {
            HttpFetcher fetcher = new HttpFetcher(cache, true, Set.of("127.0.0.1"));
            Optional<HttpFetcher.Fetched> result =
                    fetcher.get("http://127.0.0.1:" + origin.getAddress().getPort() + "/start");

            assertThat(result).isEmpty();
        } finally {
            origin.stop(0);
        }
    }

    @Test
    @DisplayName("a redirect loop terminates instead of spinning")
    void redirectLoopTerminates(@TempDir Path cache) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/again");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpFetcher fetcher = new HttpFetcher(cache, true, Set.of("127.0.0.1"));
            assertThat(fetcher.get("http://127.0.0.1:" + server.getAddress().getPort() + "/start"))
                    .isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("the harness's own egress allowlist names only artifact and documentation hosts")
    void defaultAllowlistIsNarrow(@TempDir Path cache) {
        HttpFetcher fetcher = new HttpFetcher(cache, true);
        for (String allowed : List.of("https://repo1.maven.org/x", "https://docs.spring.io/x",
                "https://central.sonatype.com/x", "https://endoflife.date/x")) {
            assertThat(fetcher.hostAllowed(allowed)).as(allowed).isTrue();
        }
        for (String refused : List.of("https://pastebin.com/x", "https://169.254.169.254/latest",
                "http://localhost:8080/x", "https://example.com/x")) {
            assertThat(fetcher.hostAllowed(refused)).as(refused).isFalse();
        }
    }

    // ---------------------------------------------------------------- imported BOM resolution

    private static final String PARENT_BOM = """
            <project>
              <groupId>org.example</groupId>
              <artifactId>parent-dependencies</artifactId>
              <version>2021.0.7</version>
              <properties>
                <netflix.version>3.1.6</netflix.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>netflix-dependencies</artifactId>
                    <version>${netflix.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                  <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>declared-directly</artifactId>
                    <version>1.2.3</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private static final String IMPORTED_BOM = """
            <project>
              <groupId>org.example</groupId>
              <artifactId>netflix-dependencies</artifactId>
              <version>3.1.6</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>eureka-client</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private HttpServer bomServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.contains("netflix-dependencies") ? IMPORTED_BOM : PARENT_BOM;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("an imported BOM's entries are resolved, not skipped")
    void importedBomEntriesAreResolved(@TempDir Path cache) throws IOException {
        HttpServer server = bomServer();
        try {
            HttpFetcher fetcher = new HttpFetcher(cache, true, Set.of("127.0.0.1"));
            var space = new MavenCentralVersionSpaceAdapter(fetcher,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2");

            var bom = space.bom("org.example", "parent-dependencies", "2021.0.7");
            assertThat(bom).isPresent();

            var byArtifact = bom.get().entries().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            VersionSpacePort.BomEntry::artifactId,
                            VersionSpacePort.BomEntry::version, (a, b) -> a));

            // Reading only the top level saw two entries, one of which was the import itself. The
            // artifact the application actually uses lives one level down, and on the real corpus
            // that level held every Spring Cloud coordinate - including the one whose removal of
            // @EnableEurekaClient blocked the migration.
            assertThat(byArtifact).containsKey("declared-directly");
            assertThat(byArtifact).containsKey("eureka-client");

            // The import entry itself is replaced by its contents, not recorded as a dependency.
            assertThat(byArtifact).doesNotContainKey("netflix-dependencies");

            // ${project.version} inside an imported BOM resolves against THAT bom's version.
            assertThat(byArtifact.get("eureka-client")).isEqualTo("3.1.6");
            assertThat(byArtifact.get("declared-directly")).isEqualTo("1.2.3");
        } finally {
            server.stop(0);
        }
    }
}
