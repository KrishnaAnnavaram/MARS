package com.bootshift.stages.stage17;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;
import com.bootshift.ports.differential.DifferentialPort;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Versioned, hashed normalization policy for differential comparison (spec section 33).
 *
 * <p>Normalization is the most dangerous part of a differential harness: quietly ignoring a field is
 * how a real regression disappears. Every rule here is explicit, carries a rationale, and is included
 * in the policy hash that goes into the evidence manifest. Nothing is dropped implicitly.
 */
public final class Normalizer {

    public static final String POLICY_VERSION = "1.0.0";

    private static final List<DifferentialPort.NormalizationRule> RULES = List.of(
            new DifferentialPort.NormalizationRule("NORM-001", "*", "$..timestamp", "DROP",
                    "Wall-clock timestamps differ between two runs by construction"),
            new DifferentialPort.NormalizationRule("NORM-002", "*", "$..date", "DROP",
                    "Response dates differ between two runs by construction"),
            new DifferentialPort.NormalizationRule("NORM-003", "HTTP_API", "$.headers.Date", "DROP",
                    "HTTP Date header is generated per response"),
            new DifferentialPort.NormalizationRule("NORM-004", "HTTP_API", "$.headers.Server", "DROP",
                    "Servlet container identity is EXPECTED_TO_DIFFER across the migration"),
            new DifferentialPort.NormalizationRule("NORM-005", "*", "$..traceId", "DROP",
                    "Trace identifiers are generated per request"),
            new DifferentialPort.NormalizationRule("NORM-006", "*", "$..spanId", "DROP",
                    "Span identifiers are generated per request"),
            new DifferentialPort.NormalizationRule("NORM-007", "*", "$..*.port", "NORMALIZE_PORT",
                    "The harness allocates a free port per side; the port itself is not behaviour"),
            new DifferentialPort.NormalizationRule("NORM-008", "*", "$..uptime", "DROP",
                    "Process uptime is not behaviour"),
            new DifferentialPort.NormalizationRule("NORM-009", "CONTEXT_CAPABILITY",
                    "$..springBootVersion", "DROP",
                    "The framework version is the thing being changed and is EXPECTED_TO_DIFFER"),
            new DifferentialPort.NormalizationRule("NORM-010", "*", "$..[?(@ =~ /^[0-9a-f]{32,64}$/)]",
                    "NORMALIZE_HASH",
                    "Content hashes of generated payloads differ when timestamps inside them differ"),
            new DifferentialPort.NormalizationRule("NORM-011", "PERSISTENCE_STATE", "$..generatedSql",
                    "DEMOTE_TO_DIAGNOSTIC",
                    "SQL text is diagnostic evidence; the contract compares business-relevant "
                            + "persistence semantics, not literal statements"));

    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{32,64}$");
    private static final Pattern PORT_URL = Pattern.compile("(:)(\\d{2,5})(/|$)");
    private static final List<String> VOLATILE_KEYS = List.of(
            "timestamp", "date", "traceid", "spanid", "uptime", "starttime", "duration",
            "server", "processid", "pid", "springbootversion");

    private Normalizer() {
    }

    public static DifferentialPort.NormalizationPolicy policy() {
        String hash = Json.canonicalHash(Json.toTree(List.of(POLICY_VERSION, RULES)));
        return new DifferentialPort.NormalizationPolicy(POLICY_VERSION, hash, RULES);
    }

    /** Applies the policy, recording exactly which rules fired rather than silently mutating. */
    public record Applied(JsonNode normalized, List<String> rulesApplied, List<String> demoted) {
    }

    public static Applied normalize(JsonNode raw, String dimension) {
        List<String> applied = new ArrayList<>();
        List<String> demoted = new ArrayList<>();
        JsonNode result = walk(raw, dimension, applied, demoted);
        return new Applied(result, applied, demoted);
    }

    private static JsonNode walk(JsonNode node, String dimension, List<String> applied,
                                 List<String> demoted) {
        if (node == null || node.isNull()) {
            return Json.mapper().nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = Json.obj();
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                String lower = key.toLowerCase(java.util.Locale.ROOT).replace("_", "");
                if (VOLATILE_KEYS.contains(lower)) {
                    out.put(key, "<normalized:" + lower + ">");
                    applied.add(ruleFor(lower));
                    return;
                }
                if ("generatedsql".equals(lower)) {
                    demoted.add("NORM-011: generatedSql demoted to diagnostic");
                    out.put(key, "<diagnostic-only>");
                    return;
                }
                out.set(key, walk(entry.getValue(), dimension, applied, demoted));
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.arr();
            node.forEach(child -> out.add(walk(child, dimension, applied, demoted)));
            return out;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            if (HEX.matcher(text).matches()) {
                applied.add("NORM-010");
                return Json.mapper().getNodeFactory().textNode("<normalized:hash>");
            }
            java.util.regex.Matcher port = PORT_URL.matcher(text);
            if (port.find()) {
                applied.add("NORM-007");
                return Json.mapper().getNodeFactory()
                        .textNode(port.replaceAll("$1<normalized:port>$3"));
            }
            return node;
        }
        return node;
    }

    private static String ruleFor(String key) {
        return switch (key) {
            case "timestamp" -> "NORM-001";
            case "date" -> "NORM-002";
            case "server" -> "NORM-004";
            case "traceid" -> "NORM-005";
            case "spanid" -> "NORM-006";
            case "uptime", "starttime", "duration" -> "NORM-008";
            case "springbootversion" -> "NORM-009";
            default -> "NORM-000";
        };
    }
}
