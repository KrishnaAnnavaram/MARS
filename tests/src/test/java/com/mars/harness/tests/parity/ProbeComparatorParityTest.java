package com.mars.harness.tests.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.kernel.adapters.maven.BuildErrorClassifier;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.runtime.ProbeComparator;
import com.mars.harness.kernel.ports.runtime.RuntimePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.4, C7: the migration reference renderer's {@code compareProbes} and
 * {@code testComparison} (run unchanged) against {@link ProbeComparator#compare} and
 * {@link ProbeComparator#compareTests}, on the recorded {@code spring-boot-3-to-4} session
 * ({@code runtime/baseline.json}, {@code runtime/final.json}, {@code rounds/*.json}) and on
 * synthetic runtime records: identical, body-only change, status change, probe error, a probe
 * missing after, an extra probe after, no probes, and a missing side. Rows (name, request, before
 * and after status, hashes, same, verdict), counts and the verdict text must be identical.
 *
 * <p>Runtime records map onto {@link RuntimePort.RuntimeRun} field by field, as
 * {@code JarProbeRuntime} fills them; a missing record is {@code null}. For the tests comparison
 * the legacy function also chooses the before/after rounds; the port leaves that choice to its
 * caller, so it is given the "Tests run" summaries of the rounds the legacy function chose.
 *
 * <p>One difference is asserted rather than hidden: a record whose application did not start.
 * The legacy renderer only treats a <em>missing</em> record as unprobed and compares a
 * never-started record's (empty) probe list, while {@link ProbeComparator} treats a run that did
 * not start as not probed ({@code compared=false}). See {@link #aRunThatNeverStartedIsNotProbed}.
 */
class ProbeComparatorParityTest {

    private static final String SESSION = "spring-migration-reference/.github/.pipeline-context/version-migration/spring-boot-3-to-4";

    @Test
    void recordedSession() {
        JsonNode legacy = LegacyNode.run("probes.js", "--recorded", LegacyNode.legacy(SESSION).toString());
        JsonNode probes = legacy.path("probes").get(0);
        assertThat(probes.path("result").path("total").asInt()).isEqualTo(9);
        assertSameProbes(probes);
        assertSameTests(legacy.path("tests").get(0));
    }

    @Test
    void syntheticRuntimeRecords(@TempDir Path dir) throws Exception {
        ObjectNode input = KernelJson.obj();
        ArrayNode cases = input.putArray("probeCases");
        input.putArray("testCases");
        ObjectNode health = probe("health", "GET", "/actuator/health", 200, "aaa");
        ObjectNode list = probe("list", "GET", "/api/items", 200, "bbb");
        ObjectNode create = probe("create", "POST", "/api/items", 201, "ccc");
        ObjectNode denied = probe("denied", "GET", "/api/admin", 401, "ddd");

        add(cases, "identical", run(true, health, list, create), run(true, health, list, create));
        add(cases, "body-only", run(true, health, list), run(true, probe("health", "GET", "/actuator/health", 200, "zzz"), list));
        add(cases, "status-changed", run(true, health, denied, list),
                run(true, health, probe("denied", "GET", "/api/admin", 200, "ddd"), probe("list", "GET", "/api/items", 500, "bbb")));
        add(cases, "probe-error", run(true, health, list), run(true, health, error("list", "GET", "/api/items")));
        add(cases, "both-error", run(true, error("list", "GET", "/api/items")), run(true, error("list", "GET", "/api/items")));
        add(cases, "missing-after", run(true, health, list, create), run(true, create, health));
        add(cases, "extra-after", run(true, health), run(true, health, list));
        add(cases, "no-probes", run(true), run(true));
        add(cases, "only-before", run(true, health), null);
        add(cases, "only-after", null, run(true, health));
        add(cases, "neither", null, null);

        Path file = dir.resolve("cases.json");
        Files.writeString(file, KernelJson.pretty(input));
        JsonNode legacy = LegacyNode.run("probes.js", file.toString());
        assertThat(legacy.path("probes").size()).isEqualTo(cases.size());
        legacy.path("probes").forEach(ProbeComparatorParityTest::assertSameProbes);
    }

    @Test
    void syntheticTestComparisons(@TempDir Path dir) throws Exception {
        ObjectNode input = KernelJson.obj();
        input.putArray("probeCases");
        ArrayNode cases = input.putArray("testCases");
        String green = "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 1\n";
        String twoBad = "[INFO] Tests run: 3, Failures: 0, Errors: 0\n[ERROR] Tests run: 12, Failures: 1, Errors: 1, Skipped: 0\n";
        String fiveBad = "[ERROR] Tests run: 12, Failures: 4, Errors: 1\n";
        cases.addObject().put("name", "all-green").set("rounds", rounds(round(0, "package", green), round(1, "test-compile", ""), round(2, "test", green)));
        cases.addObject().put("name", "same-failures").set("rounds", rounds(round(0, "package", twoBad), round(1, "package", twoBad)));
        cases.addObject().put("name", "worse").set("rounds", rounds(round(0, "test", twoBad), round(1, "verify", fiveBad), round(2, "package-skip-tests", "")));
        cases.addObject().put("name", "fewer").set("rounds", rounds(round(0, "package", fiveBad), round(1, "package", green)));
        cases.addObject().put("name", "only-before").set("rounds", rounds(round(0, "package", twoBad), round(1, "test-compile", "")));
        cases.addObject().put("name", "neither").set("rounds", rounds(round(0, "test-compile", ""), round(1, "package-skip-tests", "")));

        Path file = dir.resolve("cases.json");
        Files.writeString(file, KernelJson.pretty(input));
        JsonNode legacy = LegacyNode.run("probes.js", file.toString());
        assertThat(legacy.path("tests").size()).isEqualTo(cases.size());
        legacy.path("tests").forEach(ProbeComparatorParityTest::assertSameTests);
    }

    /**
     * The documented difference. Legacy {@code compareProbes} compares whatever a record holds:
     * a baseline that never started (no probes) reads "No probes were defined", and a final run
     * that never started turns every baseline probe into a "not run" status change. The port
     * cannot tell a missing record from a failed start ({@code JarProbeRuntime} returns
     * {@code started=false} in both situations, where the legacy script writes no record at all
     * for some of them), so it reports either side not starting as "not probed", and the
     * validator judges the failed start through its runtime-startup dimension instead. Were it to
     * follow the legacy rows, a baseline that never started would compare as zero probes with no
     * differences, which the validator would read as behaviour preserved.
     */
    @Test
    void aRunThatNeverStartedIsNotProbed(@TempDir Path dir) throws Exception {
        ObjectNode input = KernelJson.obj();
        ArrayNode cases = input.putArray("probeCases");
        input.putArray("testCases");
        ObjectNode health = probe("health", "GET", "/actuator/health", 200, "aaa");
        add(cases, "final-never-started", run(true, health), run(false));
        add(cases, "baseline-never-started", run(false), run(true, health));
        Path file = dir.resolve("cases.json");
        Files.writeString(file, KernelJson.pretty(input));
        JsonNode legacy = LegacyNode.run("probes.js", file.toString());

        JsonNode finalNeverStarted = legacy.path("probes").get(0).path("result");
        assertThat(finalNeverStarted.path("verdictText").asText()).isEqualTo("🔴 1 of 1 probe(s) changed status");
        assertThat(finalNeverStarted.path("rows").get(0).path("afterStatus").asText()).isEqualTo("not run");
        JsonNode baselineNeverStarted = legacy.path("probes").get(1).path("result");
        assertThat(baselineNeverStarted.path("verdictText").asText()).isEqualTo("⚠️ No probes were defined");

        for (JsonNode c : legacy.path("probes")) {
            ProbeComparator.Comparison actual = ProbeComparator.compare(toRun(c.path("runtime").path("baseline")),
                    toRun(c.path("runtime").path("final")));
            assertThat(actual.compared()).isFalse();
            assertThat(actual.rows()).isEmpty();
            assertThat(actual.verdictText()).isEqualTo("⚠️ Only one side was probed — no comparison possible");
        }
    }

    private static void assertSameProbes(JsonNode c) {
        String name = c.path("name").asText();
        JsonNode expected = c.path("result");
        ProbeComparator.Comparison actual = ProbeComparator.compare(toRun(c.path("runtime").path("baseline")),
                toRun(c.path("runtime").path("final")));
        assertThat(actual.verdictText()).as(name).isEqualTo(expected.path("verdictText").asText());
        assertThat(actual.total()).as(name).isEqualTo(expected.path("total").asInt());
        assertThat(actual.matched()).as(name).isEqualTo(expected.path("matched").asInt());
        assertThat(actual.bodyOnly()).as(name).isEqualTo(expected.path("bodyOnly").asInt(0));
        assertThat(actual.statusChanged()).as(name).isEqualTo(expected.path("statusChanged").asInt(0));
        assertThat(actual.compared()).as(name).isEqualTo(expected.has("bodyOnly"));
        assertThat(actual.rows()).as(name).hasSameSizeAs(expected.path("rows"));
        for (int i = 0; i < actual.rows().size(); i++) {
            ProbeComparator.Row a = actual.rows().get(i);
            JsonNode e = expected.path("rows").get(i);
            String as = name + " " + a.name();
            assertThat(a.name()).as(as).isEqualTo(e.path("name").asText());
            assertThat(a.request()).as(as).isEqualTo(e.path("request").asText());
            assertThat(a.beforeStatus()).as(as).isEqualTo(e.path("beforeStatus").asText());
            assertThat(a.afterStatus()).as(as).isEqualTo(e.path("afterStatus").asText());
            assertThat(a.beforeHash()).as(as).isEqualTo(LegacyNode.text(e.path("beforeHash")));
            assertThat(a.afterHash()).as(as).isEqualTo(LegacyNode.text(e.path("afterHash")));
            assertThat(a.same()).as(as).isEqualTo(e.path("same").asBoolean());
            assertThat(a.verdict()).as(as).isEqualTo(e.path("verdict").asText());
        }
    }

    private static void assertSameTests(JsonNode c) {
        String name = c.path("name").asText();
        JsonNode expected = c.path("result");
        List<JsonNode> rounds = new ArrayList<>();
        c.path("rounds").forEach(rounds::add);
        BuildPort.TestSummary before = summaryOf(rounds, expected.path("before"));
        BuildPort.TestSummary after = summaryOf(rounds, expected.path("after"));
        ProbeComparator.TestVerdict actual = ProbeComparator.compareTests(before, after);
        if (expected.isNull()) {
            assertThat(actual.compared()).as(name).isFalse();
            assertThat(actual.before()).as(name).isNull();
            assertThat(actual.after()).as(name).isNull();
            return;
        }
        assertThat(actual.verdict()).as(name).isEqualTo(expected.path("verdict").asText());
        assertThat(actual.worse()).as(name).isEqualTo(expected.path("worse").asBoolean());
        assertThat(actual.compared()).as(name).isEqualTo(summaryOf(rounds, expected.path("before")) != null
                && summaryOf(rounds, expected.path("after")) != null);
    }

    /** The port's summary of the round the legacy comparison chose, checked against the legacy totals. */
    private static BuildPort.TestSummary summaryOf(List<JsonNode> rounds, JsonNode totals) {
        if (totals.isNull() || totals.isMissingNode()) {
            return null;
        }
        JsonNode round = rounds.stream().filter(r -> r.path("round").asInt() == totals.path("round").asInt()).findFirst().orElseThrow();
        BuildPort.TestSummary summary = BuildErrorClassifier.testSummary(round.path("log_tail").asText());
        assertThat(summary).isEqualTo(new BuildPort.TestSummary(totals.path("run").asInt(), totals.path("failures").asInt(),
                totals.path("errors").asInt(), totals.path("skipped").asInt()));
        return summary;
    }

    /** A legacy runtime record ({@code probe-runtime.js}) as the port's {@link RuntimePort.RuntimeRun}. */
    static RuntimePort.RuntimeRun toRun(JsonNode r) {
        if (r == null || r.isNull() || r.isMissingNode()) {
            return null;
        }
        List<RuntimePort.ProbeObservation> probes = new ArrayList<>();
        for (JsonNode p : r.path("probes")) {
            probes.add(new RuntimePort.ProbeObservation(p.path("name").asText(), p.path("method").asText(),
                    p.path("path").asText(), p.path("authenticated").asBoolean(), p.path("duration_ms").asLong(),
                    p.path("ok").asBoolean(), p.path("status").isNumber() ? p.path("status").asInt() : null,
                    LegacyNode.text(p.path("content_type")), p.path("body_length").asInt(), LegacyNode.text(p.path("body_hash")),
                    LegacyNode.text(p.path("body_excerpt")), LegacyNode.text(p.path("error"))));
        }
        return new RuntimePort.RuntimeRun(r.path("phase").asText(null), r.path("started").asBoolean(),
                LegacyNode.text(r.path("failure")), r.path("startup_seconds").isNumber() ? r.path("startup_seconds").asDouble() : null,
                LegacyNode.text(r.path("artifact")), LegacyNode.text(r.path("jdk").path("version")),
                r.path("readiness").path("status").isNumber() ? r.path("readiness").path("status").asInt() : null,
                r.path("readiness").path("ready_after_ms").isNumber() ? r.path("readiness").path("ready_after_ms").asLong() : null,
                probes, LegacyNode.text(r.path("app_log_tail")));
    }

    private static void add(ArrayNode cases, String name, ObjectNode baseline, ObjectNode fin) {
        ObjectNode runtime = cases.addObject().put("name", name).putObject("runtime");
        runtime.set("baseline", baseline);
        runtime.set("final", fin);
    }

    private static ObjectNode run(boolean started, ObjectNode... probes) {
        ObjectNode r = KernelJson.obj();
        r.put("phase", "baseline").put("started", started);
        r.putObject("jdk").put("major", 21).put("version", "21.0.4");
        if (!started) {
            r.put("failure", "packaging failed");
        }
        ArrayNode array = r.putArray("probes");
        for (ObjectNode p : probes) {
            array.add(p.deepCopy());
        }
        return r;
    }

    private static ObjectNode probe(String name, String method, String path, int status, String hash) {
        ObjectNode p = KernelJson.obj();
        p.put("name", name).put("method", method).put("path", path).put("authenticated", true).put("duration_ms", 5)
                .put("ok", true).put("status", status).put("content_type", "application/json").put("body_length", 10)
                .put("body_hash", hash).put("body_excerpt", "{}");
        return p;
    }

    /** probe-runtime.js {@code attempt()} on a transport failure: {@code { ok: false, status: null, error }}. */
    private static ObjectNode error(String name, String method, String path) {
        ObjectNode p = KernelJson.obj();
        p.put("name", name).put("method", method).put("path", path).put("authenticated", true).put("duration_ms", 5)
                .put("ok", false).putNull("status").put("error", "connect ECONNREFUSED");
        return p;
    }

    private static ArrayNode rounds(ObjectNode... rounds) {
        ArrayNode array = KernelJson.mapper().createArrayNode();
        for (ObjectNode r : rounds) {
            array.add(r);
        }
        return array;
    }

    private static ObjectNode round(int number, String intent, String log) {
        ObjectNode r = KernelJson.obj();
        r.put("round", number).put("baseline", number == 0).put("log_tail", log);
        r.putObject("build").put("intent", intent);
        return r;
    }
}
