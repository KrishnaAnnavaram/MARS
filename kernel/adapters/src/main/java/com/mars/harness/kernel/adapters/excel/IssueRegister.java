package com.mars.harness.kernel.adapters.excel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * VRH issue-register contract, ported from {@code 00-issue-register/scripts/lib/register.js}.
 *
 * <ul>
 *   <li>Column names are the contract. Order does not matter and unknown columns are ignored.</li>
 *   <li>Rows with a blank {@code issue_id} are skipped. Rows are sorted by id with numeric-aware
 *       comparison.</li>
 *   <li>List cells split on newline or comma, with a leading {@code - * •} bullet stripped.</li>
 *   <li>The markdown body is synthesized with the exact headings and blank-line placement the
 *       downstream extractors match on.</li>
 * </ul>
 */
public final class IssueRegister {

    public static final Map<String, String> SCALARS = linked(
            "issue_id", "id", "title", "title", "type", "type", "severity", "severity", "status", "status",
            "reported_on", "reportedOn", "reported_by", "reportedBy");

    public static final Map<String, String> LISTS = linked(
            "affected_services", "services", "affected_symbols", "symbols", "affected_files", "files",
            "entry_points", "entryPoints");

    public static final List<String[]> SECTIONS = List.of(
            new String[]{"summary", "Summary"},
            new String[]{"affected_area", "Affected Area"},
            new String[]{"data_flow", "Data Flow (source → sink)"},
            new String[]{"observed_behavior", "Observed Behavior"},
            new String[]{"expected_behavior", "Expected Behavior"},
            new String[]{"steps_to_reproduce", "Steps to Reproduce"},
            new String[]{"impact", "Impact"},
            new String[]{"detection_notes", "Detection Notes"});

    /** register.js {@code splitList}'s leading-bullet pattern, with JavaScript's white space for {@code \s}. */
    private static final Pattern BULLET = Pattern.compile("^[-*•][" + Xlsx.JS_SPACE + "]*");

    /** One register row, as the VRH skills see it. */
    public record Issue(int rowNumber, String id, String title, String type, String severity, String status,
                        String reportedOn, String reportedBy, List<String> services, List<String> symbols,
                        List<String> files, List<String> entryPoints, String body, Map<String, String> raw) {
    }

    private IssueRegister() {
    }

    public static List<Issue> list(Path workbook) {
        Xlsx.Table table = Xlsx.readTable(workbook);
        if (!table.headers().contains("issue_id")) {
            throw new IllegalArgumentException(workbook + " has no \"issue_id\" column. Expected columns: "
                    + String.join(", ", columns()) + ".");
        }
        List<Issue> issues = new ArrayList<>();
        int rowNumber = 1;
        for (Map<String, String> row : table.rows()) {
            rowNumber++;
            if (Xlsx.jsTrim(row.getOrDefault("issue_id", "")).isEmpty()) {
                continue;
            }
            issues.add(toIssue(row, rowNumber));
        }
        issues.sort((a, b) -> naturalCompare(a.id(), b.id()));
        return issues;
    }

    static Issue toIssue(Map<String, String> row, int rowNumber) {
        String id = scalar(row, "issue_id");
        String title = scalar(row, "title");
        title = title == null ? "(untitled)" : title;
        return new Issue(rowNumber, id, title, scalar(row, "type"), scalar(row, "severity"), scalar(row, "status"),
                scalar(row, "reported_on"), scalar(row, "reported_by"), splitList(row.get("affected_services")),
                splitList(row.get("affected_symbols")), splitList(row.get("affected_files")),
                splitList(row.get("entry_points")), synthesizeBody(row, id, title), Map.copyOf(row));
    }

    private static String scalar(Map<String, String> row, String column) {
        String value = Xlsx.jsTrim(row.getOrDefault(column, ""));
        return value.isEmpty() ? null : value;
    }

    public static List<String> splitList(String cell) {
        if (cell == null || cell.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(cell.split("\\r?\\n|,"))
                .map(v -> BULLET.matcher(Xlsx.jsTrim(v)).replaceFirst(""))
                .filter(v -> !v.isEmpty())
                .toList();
    }

    public static String synthesizeBody(Map<String, String> row, String id, String title) {
        List<String> out = new ArrayList<>(List.of("# " + id + " — " + title, ""));
        for (String[] section : SECTIONS) {
            String text = Xlsx.jsTrim(row.getOrDefault(section[0], ""));
            if (text.isEmpty()) {
                continue;
            }
            out.addAll(List.of("## " + section[1], "", text, ""));
        }
        return String.join("\n", out);
    }

    public static List<String> columns() {
        List<String> all = new ArrayList<>(SCALARS.keySet());
        all.addAll(LISTS.keySet());
        SECTIONS.forEach(s -> all.add(s[0]));
        return all;
    }

    /** {@code localeCompare(b, undefined, {numeric: true})}: digit runs compare by value. */
    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                int cmp = new java.math.BigInteger(a.substring(si, i)).compareTo(new java.math.BigInteger(b.substring(sj, j)));
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
