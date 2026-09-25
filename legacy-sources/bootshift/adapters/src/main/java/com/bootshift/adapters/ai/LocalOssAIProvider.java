package com.bootshift.adapters.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.policy.LicensePolicy;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;
import com.bootshift.ports.ai.AIProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local, OSS-only inference provider (spec section 29).
 *
 * <p>Disabled by default. When enabled it talks to a local OSS runtime over its HTTP API; no
 * proprietary hosted API is reachable from this class by construction, because the endpoint must be
 * a loopback address.
 *
 * <p>The runtime license and the <em>model</em> license are checked separately: an Apache-2.0
 * inference runtime says nothing about the weights loaded into it, and treating it as if it did is
 * exactly the mistake the strict-OSS policy exists to prevent.
 */
public final class LocalOssAIProvider implements AIProvider {

    private static final Logger LOG = LoggerFactory.getLogger(LocalOssAIProvider.class);

    private final boolean enabled;
    private final String endpoint;
    private final String modelName;
    private final String modelLicense;
    private final boolean modelLicenseVerified;
    private final String runtime;
    private final HttpClient client;
    private final AtomicLong sequence = new AtomicLong();

    public LocalOssAIProvider(boolean enabled, String endpoint, String runtime, String modelName,
                              String modelLicense, LicensePolicy licensePolicy) {
        this.enabled = enabled && isLoopback(endpoint);
        this.endpoint = endpoint;
        this.runtime = runtime;
        this.modelName = modelName;
        this.modelLicense = modelLicense;
        LicensePolicy.Finding finding = licensePolicy.evaluate(
                "model:" + modelName, null, modelLicense, "operator-declaration");
        this.modelLicenseVerified = finding.verdict() == LicensePolicy.Verdict.ALLOWED;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        if (enabled && !isLoopback(endpoint)) {
            LOG.warn("AI provider disabled: endpoint {} is not a loopback address, and strict-OSS mode "
                    + "forbids remote inference endpoints", endpoint);
        }
        if (this.enabled && !modelLicenseVerified) {
            LOG.warn("AI provider enabled but model license {} did not pass the gate: {}",
                    modelLicense, finding.reason());
        }
    }

    /** The default construction used by the CLI: AI off. */
    public static LocalOssAIProvider disabled() {
        return new LocalOssAIProvider(false, "http://127.0.0.1:11434", "none", "none", null,
                new LicensePolicy());
    }

    @Override
    public boolean enabled() {
        return enabled && modelLicenseVerified;
    }

    @Override
    public ModelIdentity identity() {
        return new ModelIdentity(runtime, probeRuntimeVersion(), modelName, probeModelVersion(),
                modelDigest(), modelLicense, modelLicenseVerified);
    }

    @Override
    public Optional<Proposal> propose(Task task, String prompt, Map<String, String> context,
                                      List<String> priorFailures) {
        if (!enabled()) {
            return Optional.empty();
        }
        String contextJson = Json.canonical(context);
        String promptHash = Hashing.sha256(prompt);
        String contextHash = Hashing.sha256(contextJson);

        StringBuilder full = new StringBuilder();
        full.append(prompt).append("\n\nCONTEXT:\n").append(contextJson);
        if (!priorFailures.isEmpty()) {
            full.append("\n\nPREVIOUS ATTEMPTS THAT FAILED VERIFICATION:\n");
            priorFailures.forEach(f -> full.append("- ").append(f).append('\n'));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("prompt", full.toString());
        body.put("stream", false);
        body.put("options", Map.of("temperature", 0.0, "seed", 7));

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/generate"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.canonical(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("Local inference runtime returned {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode node = Json.parse(response.body());
            String content = node.path("response").asText("");
            if (content.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Proposal("AIP-" + sequence.incrementAndGet(), task, content,
                    promptHash, contextHash, Hashing.sha256(content), identity(),
                    Map.of("endpoint", endpoint, "task", task.name())));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Local inference call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String probeRuntimeVersion() {
        if (!enabled) {
            return "n/a";
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/version"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                    ? Json.parse(response.body()).path("version").asText("unknown") : "unknown";
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }

    private String probeModelVersion() {
        return enabled ? modelName : "n/a";
    }

    private String modelDigest() {
        return enabled ? Hashing.sha256(runtime + ":" + modelName) : null;
    }

    private static boolean isLoopback(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        try {
            String host = URI.create(endpoint).getHost();
            return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
