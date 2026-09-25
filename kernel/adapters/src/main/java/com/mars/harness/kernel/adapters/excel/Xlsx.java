package com.mars.harness.kernel.adapters.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zero-dependency XLSX reading and writing, ported from VRH's
 * {@code 00-issue-register/scripts/lib/xlsx.js} ({@code readSheet} / {@code readTable} /
 * {@code writeSheet}).
 *
 * <p>The same regex-level XML handling is kept on purpose. The register contract was defined
 * against that reader, and the parity test compares this port with the JavaScript original on
 * the golden workbook.
 */
public final class Xlsx {

    private static final Pattern SI = Pattern.compile("<si>([\\s\\S]*?)</si>");
    private static final Pattern T = Pattern.compile("<t[^>]*>([\\s\\S]*?)</t>");
    private static final Pattern ROW = Pattern.compile("<row[^>]*>[\\s\\S]*?</row>|<row[^>]*/>");
    private static final Pattern ROW_NUM = Pattern.compile("<row[^>]*\\sr=\"(\\d+)\"");
    private static final Pattern CELL = Pattern.compile("<c[^>]*>[\\s\\S]*?</c>|<c[^>]*/>");
    private static final Pattern REF = Pattern.compile("\\sr=\"([A-Z]+\\d+)\"");
    private static final Pattern TYPE = Pattern.compile("\\st=\"([^\"]+)\"");
    private static final Pattern V = Pattern.compile("<v[^>]*>([\\s\\S]*?)</v>");

    public record Table(List<String> headers, List<Map<String, String>> rows) {
    }

    private Xlsx() {
    }

    public static Table readTable(Path file) {
        try {
            return readTable(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read workbook " + file, e);
        }
    }

    public static Table readTable(byte[] bytes) {
        List<List<String>> rows = readSheet(bytes);
        if (rows.isEmpty()) {
            return new Table(List.of(), List.of());
        }
        List<String> headers = rows.get(0).stream().map(Xlsx::jsTrim).toList();
        List<Map<String, String>> out = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.stream().allMatch(c -> jsTrim(c).isEmpty())) {
                continue;
            }
            Map<String, String> obj = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                if (!headers.get(i).isEmpty()) {
                    obj.put(headers.get(i), i < row.size() ? row.get(i) : "");
                }
            }
            out.add(obj);
        }
        return new Table(headers, out);
    }

    static List<List<String>> readSheet(byte[] bytes) {
        Map<String, byte[]> files = unzip(bytes);
        String sheetKey = files.keySet().stream().filter(k -> k.matches("(?i)^xl/worksheets/sheet1\\.xml$")).findFirst()
                .orElse(files.keySet().stream().filter(k -> k.matches("(?i)^xl/worksheets/.*\\.xml$")).findFirst().orElse(null));
        if (sheetKey == null) {
            throw new IllegalArgumentException("No worksheet found inside the workbook.");
        }
        List<String> shared = new ArrayList<>();
        byte[] sharedXml = files.get("xl/sharedStrings.xml");
        if (sharedXml != null) {
            Matcher si = SI.matcher(new String(sharedXml, StandardCharsets.UTF_8));
            while (si.find()) {
                StringBuilder sb = new StringBuilder();
                Matcher t = T.matcher(si.group());
                while (t.find()) {
                    sb.append(decodeXml(t.group(1)));
                }
                shared.add(sb.toString());
            }
        }
        String xml = new String(files.get(sheetKey), StandardCharsets.UTF_8);
        Map<Integer, List<String>> rowsByIndex = new LinkedHashMap<>();
        int maxRow = -1;
        Matcher rowMatcher = ROW.matcher(xml);
        while (rowMatcher.find()) {
            String rowXml = rowMatcher.group();
            Matcher rn = ROW_NUM.matcher(rowXml);
            int rowIdx = rn.find() ? Integer.parseInt(rn.group(1)) - 1 : rowsByIndex.size();
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(rowXml);
            while (cell.find()) {
                String cellXml = cell.group();
                Matcher ref = REF.matcher(cellXml);
                int idx = ref.find() ? colIndex(ref.group(1)) : cells.size();
                Matcher type = TYPE.matcher(cellXml);
                String t = type.find() ? type.group(1) : "n";
                String value;
                if ("inlineStr".equals(t)) {
                    StringBuilder sb = new StringBuilder();
                    Matcher parts = T.matcher(cellXml);
                    while (parts.find()) {
                        sb.append(decodeXml(parts.group(1)));
                    }
                    value = sb.toString();
                } else {
                    Matcher v = V.matcher(cellXml);
                    String raw = v.find() ? decodeXml(v.group(1)) : "";
                    if ("s".equals(t)) {
                        int i = raw.isEmpty() ? -1 : Integer.parseInt(raw.trim());
                        value = i >= 0 && i < shared.size() ? shared.get(i) : "";
                    } else if ("b".equals(t)) {
                        value = "1".equals(raw) ? "TRUE" : "FALSE";
                    } else {
                        value = raw;
                    }
                }
                while (cells.size() <= idx) {
                    cells.add("");
                }
                cells.set(idx, value);
            }
            rowsByIndex.put(rowIdx, cells);
            maxRow = Math.max(maxRow, rowIdx);
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i <= maxRow; i++) {
            rows.add(rowsByIndex.getOrDefault(i, new ArrayList<>()));
        }
        while (!rows.isEmpty() && rows.get(rows.size() - 1).stream().allMatch(c -> jsTrim(c).isEmpty())) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    /** The characters JavaScript's {@code \s} and {@code String.prototype.trim} treat as white space. */
    static final String JS_SPACE = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

    private static final Pattern JS_TRIM = Pattern.compile("^[" + JS_SPACE + "]+|[" + JS_SPACE + "]+\\z");

    /**
     * {@code String.prototype.trim}, which the legacy reader and register use: unlike
     * {@link String#trim()} it also strips Unicode spaces such as the non-breaking space Excel
     * cells often carry.
     */
    static String jsTrim(String s) {
        return JS_TRIM.matcher(s).replaceAll("");
    }

    static int colIndex(String ref) {
        Matcher m = Pattern.compile("^([A-Z]+)").matcher(ref);
        if (!m.find()) {
            return 0;
        }
        int n = 0;
        for (char ch : m.group(1).toCharArray()) {
            n = n * 26 + (ch - 64);
        }
        return n - 1;
    }

    static String colName(int index) {
        int n = index;
        StringBuilder out = new StringBuilder();
        do {
            out.insert(0, (char) (65 + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return out.toString();
    }

    static String decodeXml(String s) {
        String out = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(s)
                .replaceAll(m -> Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(m.group(1), 16)))));
        out = Pattern.compile("&#(\\d+);").matcher(out)
                .replaceAll(m -> Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(m.group(1))))));
        return out.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    private static Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            long total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] data = readBounded(zip, 64L * 1024 * 1024 - total);
                total += data.length;
                files.put(entry.getName(), data);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a valid zip/xlsx file: " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Not a valid zip/xlsx file: no entries.");
        }
        return files;
    }

    /** Zip-bomb guard: a register larger than 64 MiB uncompressed is refused. */
    private static byte[] readBounded(InputStream in, long remaining) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > remaining) {
                throw new IOException("workbook exceeds the 64 MiB uncompressed limit");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ writer (fixtures, exports)

    /** Writes a single-sheet workbook with inline strings, like {@code writeSheet}. */
    public static void write(Path file, String sheetName, List<List<String>> rows) {
        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder body = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            body.append("<row r=\"").append(r + 1).append("\">");
            List<String> row = rows.get(r);
            for (int c = 0; c < colCount; c++) {
                String raw = c < row.size() ? row.get(c) : null;
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                body.append("<c r=\"").append(colName(c)).append(r + 1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escapeXml(raw)).append("</t></is></c>");
            }
            body.append("</row>");
        }
        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                + body + "</sheetData></worksheet>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>";
        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
        String workbookRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>";
        String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + escapeXml(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
                put(zip, "[Content_Types].xml", contentTypes);
                put(zip, "_rels/.rels", rootRels);
                put(zip, "xl/workbook.xml", workbook);
                put(zip, "xl/_rels/workbook.xml.rels", workbookRels);
                put(zip, "xl/worksheets/sheet1.xml", sheet);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write workbook " + file, e);
        }
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
