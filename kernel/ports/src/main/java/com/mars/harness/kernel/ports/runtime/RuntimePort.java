package com.mars.harness.kernel.ports.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Starts the built application and replays behaviour probes (spec §27 {@code RuntimePort}).
 *
 * <p>Probe semantics are preserved from the migration reference ({@code probe-runtime.js}): a
 * readiness wait where any HTTP answer proves the server is up, requests replayed with Basic auth
 * unless {@code noAuth}, and a whitespace-normalised body hash.
 */
public interface RuntimePort {

    record ProbeRequest(String name, String method, String path, boolean noAuth, Map<String, String> headers,
                        String body) {
        public ProbeRequest {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    record ProbeSpec(String baseUrl, String authUser, String authPassword, String readinessPath,
                     int readinessTimeoutSeconds, List<ProbeRequest> requests) {
        public ProbeSpec {
            requests = requests == null ? List.of() : List.copyOf(requests);
        }
    }

    record ProbeObservation(String name, String method, String path, boolean authenticated, long durationMs,
                            boolean ok, Integer status, String contentType, int bodyLength, String bodyHash,
                            String bodyExcerpt, String error) {
    }

    /**
     * @param started false when the application never became ready. {@code failure} then says
     *                why, and every probe is absent rather than faked.
     */
    record RuntimeRun(String phase, boolean started, String failure, Double startupSeconds, String artifact,
                      String jdk, Integer readinessStatus, Long readyAfterMs, List<ProbeObservation> probes,
                      String appLogTail) {
        public RuntimeRun {
            probes = probes == null ? List.of() : List.copyOf(probes);
        }
    }

    RuntimeRun run(Path projectDir, ProbeSpec spec, String phase, Path logFile);
}
