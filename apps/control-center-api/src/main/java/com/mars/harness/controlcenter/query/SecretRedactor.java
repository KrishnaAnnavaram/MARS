package com.mars.harness.controlcenter.query;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort masking of credential-like literals in source text shown to a browser (source
 * context around a finding, proposal diffs). A hard-coded secret is often the very thing a finding
 * is about; the Control Center shows where it is, not what it is.
 *
 * <p>This is a display control only. It does not change artifacts, and it is not a guarantee: a
 * secret that does not look like one is shown as it is. The run directory itself stays the
 * sensitive store it always was.
 */
public final class SecretRedactor {

    private static final Pattern ASSIGNED_LITERAL = Pattern.compile(
            "(?i)((?:password|passwd|pwd|secret|token|api[_-]?key|apikey|private[_-]?key|access[_-]?key|credential|"
                    + "client[_-]?secret)[\\w.-]*\\s*(?:=|:|,|\\()\\s*)(\"|')([^\"'\\n]{4,})(\\2)");
    private static final Pattern PROPERTY_LINE = Pattern.compile(
            "(?im)^([+\\- ]?\\s*[\\w.-]*(?:password|secret|token|api[_-]?key|private[_-]?key|credential)[\\w.-]*\\s*[=:]\\s*)(\\S.*)$");
    private static final Pattern PEM = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = PEM.matcher(text).replaceAll("-----BEGIN PRIVATE KEY----- [redacted by the Control Center] -----END PRIVATE KEY-----");
        Matcher m = ASSIGNED_LITERAL.matcher(out);
        out = m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + r.group(2) + "••••(redacted)" + r.group(4)));
        Matcher p = PROPERTY_LINE.matcher(out);
        out = p.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + "••••(redacted)"));
        return out;
    }
}
