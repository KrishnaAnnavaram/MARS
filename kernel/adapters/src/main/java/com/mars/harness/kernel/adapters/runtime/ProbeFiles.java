package com.mars.harness.kernel.adapters.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.ports.runtime.RuntimePort;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads behaviour probe files in the migration reference's {@code probes.json} format, unchanged:
 * {@code {base_url, auth{type,username,password}, readiness{path,timeout_seconds},
 * requests[{name,method,path,no_auth,headers,body}]}}. A file already in the kernel's
 * {@code ProbeSpec} shape is accepted as-is.
 *
 * <p>{@code base_url}'s port is informational. The runtime picks a free loopback port, so two
 * probe runs never collide.
 */
public final class ProbeFiles {

    private ProbeFiles() {
    }

    public static RuntimePort.ProbeSpec read(Path file) {
        JsonNode node = KernelJson.read(file);
        if (node.has("readiness_path") || node.has("auth_user")) {
            return KernelJson.convert(node, RuntimePort.ProbeSpec.class);
        }
        JsonNode auth = node.path("auth");
        boolean basic = "basic".equalsIgnoreCase(auth.path("type").asText(""));
        List<RuntimePort.ProbeRequest> requests = new ArrayList<>();
        for (JsonNode r : node.path("requests")) {
            Map<String, String> headers = new LinkedHashMap<>();
            r.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
            JsonNode body = r.path("body");
            requests.add(new RuntimePort.ProbeRequest(r.path("name").asText(), r.path("method").asText("GET"),
                    r.path("path").asText("/"), r.path("no_auth").asBoolean(false), headers,
                    body.isMissingNode() || body.isNull() ? null : body.isTextual() ? body.asText() : body.toString()));
        }
        return new RuntimePort.ProbeSpec(node.path("base_url").asText(null), basic ? auth.path("username").asText(null) : null,
                basic ? auth.path("password").asText(null) : null, node.path("readiness").path("path").asText("/actuator/health"),
                node.path("readiness").path("timeout_seconds").asInt(120), requests);
    }
}
