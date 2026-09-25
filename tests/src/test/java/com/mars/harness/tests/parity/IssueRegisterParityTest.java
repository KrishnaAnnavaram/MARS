package com.mars.harness.tests.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.adapters.excel.IssueRegister;
import com.mars.harness.kernel.adapters.excel.Xlsx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.4, B1: the legacy VRH {@code register.js}/{@code xlsx.js} and {@link IssueRegister}
 * read the same workbook into the same issues: same ids in the same order, the same scalar
 * fields, the same split lists, the same synthesized markdown body and the same raw row. The
 * workbooks are the composite fixture, both legacy registers, and an edge-case workbook written
 * by the legacy {@code writeSheet} (blank ids, spacer rows, numeric-aware sort, bullets, CRLF,
 * empty list items, untitled rows, escaped XML, non-breaking spaces).
 */
class IssueRegisterParityTest {

    @Test
    void compositeFixtureRegister() {
        assertSameIssues(LegacyNode.root().resolve("fixtures/composite/inputs"));
    }

    @Test
    void legacyVulnerabilityHarnessRegister() {
        assertSameIssues(LegacyNode.legacy("vulnerability-remediation-harness/docs/agent_output/00-issues"));
    }

    @Test
    void legacyMigrationReferenceRegister() {
        assertSameIssues(LegacyNode.legacy("spring-migration-reference/docs/agent_output/00-issues"));
    }

    @Test
    void edgeCaseWorkbookWrittenByTheLegacyWriter(@TempDir Path dir) {
        JsonNode legacy = LegacyNode.run("issue-register.js", "synthetic", dir.toString());
        assertThat(legacy.path("issues").size()).isEqualTo(6);
        compare(legacy, dir.resolve("issue-register.xlsx"));
    }

    private static void assertSameIssues(Path issuesDir) {
        JsonNode legacy = LegacyNode.run("issue-register.js", "list", issuesDir.toString());
        assertThat(legacy.path("issues").size()).isPositive();
        compare(legacy, issuesDir.resolve("issue-register.xlsx"));
    }

    private static void compare(JsonNode legacy, Path workbook) {
        Xlsx.Table table = Xlsx.readTable(workbook);
        assertThat(table.headers()).isEqualTo(LegacyNode.strings(legacy.path("table").path("headers")));
        assertThat(table.rows()).hasSameSizeAs(legacy.path("table").path("rows"));
        for (int i = 0; i < table.rows().size(); i++) {
            assertThat(table.rows().get(i)).as("row %d", i).isEqualTo(stringMap(legacy.path("table").path("rows").get(i)));
        }

        List<IssueRegister.Issue> issues = IssueRegister.list(workbook);
        JsonNode expected = legacy.path("issues");
        List<String> expectedIds = new ArrayList<>();
        expected.forEach(e -> expectedIds.add(e.path("id").asText()));
        assertThat(issues.stream().map(IssueRegister.Issue::id).toList()).containsExactlyElementsOf(expectedIds);
        for (int i = 0; i < issues.size(); i++) {
            IssueRegister.Issue actual = issues.get(i);
            JsonNode e = expected.get(i);
            String where = actual.id();
            assertThat(actual.id()).as(where).isEqualTo(LegacyNode.text(e.path("id")));
            assertThat(actual.title()).as(where).isEqualTo(LegacyNode.text(e.path("title")));
            assertThat(actual.type()).as(where).isEqualTo(LegacyNode.text(e.path("type")));
            assertThat(actual.severity()).as(where).isEqualTo(LegacyNode.text(e.path("severity")));
            assertThat(actual.status()).as(where).isEqualTo(LegacyNode.text(e.path("status")));
            assertThat(actual.reportedOn()).as(where).isEqualTo(LegacyNode.text(e.path("reportedOn")));
            assertThat(actual.reportedBy()).as(where).isEqualTo(LegacyNode.text(e.path("reportedBy")));
            assertThat(actual.services()).as(where).isEqualTo(LegacyNode.strings(e.path("services")));
            assertThat(actual.symbols()).as(where).isEqualTo(LegacyNode.strings(e.path("symbols")));
            assertThat(actual.files()).as(where).isEqualTo(LegacyNode.strings(e.path("files")));
            assertThat(actual.entryPoints()).as(where).isEqualTo(LegacyNode.strings(e.path("entryPoints")));
            assertThat(actual.body()).as(where).isEqualTo(e.path("body").asText());
            assertThat(actual.raw()).as(where).isEqualTo(stringMap(e.path("raw")));
        }
    }

    private static Map<String, String> stringMap(JsonNode object) {
        Map<String, String> map = new LinkedHashMap<>();
        object.fields().forEachRemaining(f -> map.put(f.getKey(), f.getValue().asText()));
        return map;
    }
}
