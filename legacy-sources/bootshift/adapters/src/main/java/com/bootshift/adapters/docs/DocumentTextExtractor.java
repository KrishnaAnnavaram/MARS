package com.bootshift.adapters.docs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts readable prose from a retrieved document.
 *
 * <p>The raw snapshot remains the authority (spec section 18): this produces a <em>rendition</em>
 * used for pattern extraction, and both are content-addressed so a reviewer can check the extraction
 * against the original.
 *
 * <p>Deliberately dependency-free. Pulling in an HTML parser would add a component to the strict-OSS
 * bill of materials for a job that is bounded and easy to verify.
 */
public final class DocumentTextExtractor {

    /**
     * Opening tags of the content containers the documentation sources use.
     *
     * <p>Matched with a depth-counting scan rather than a lazy regex: these containers nest, and a
     * lazy match stops at the first inner close, which silently truncates a one-megabyte page down
     * to a few hundred characters of navigation chrome.
     */
    private static final Pattern MARKDOWN_BODY_OPEN = Pattern.compile(
            "<div[^>]*class=\"[^\"]*markdown-body[^\"]*\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIKI_BODY_OPEN = Pattern.compile(
            "<div[^>]*id=\"wiki-body\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTICLE_OPEN = Pattern.compile(
            "<article[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIV_TAG = Pattern.compile("</?div\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTICLE_TAG =
            Pattern.compile("</?article\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "<(script|style|svg|noscript)[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");
    private static final Pattern SPACE_RUN = Pattern.compile("[ \\t]{2,}");

    private static final Map<String, String> ENTITIES = new LinkedHashMap<>();

    static {
        ENTITIES.put("&nbsp;", " ");
        ENTITIES.put("&amp;", "&");
        ENTITIES.put("&lt;", "<");
        ENTITIES.put("&gt;", ">");
        ENTITIES.put("&quot;", "\"");
        ENTITIES.put("&#39;", "'");
        ENTITIES.put("&apos;", "'");
        ENTITIES.put("&mdash;", "-");
        ENTITIES.put("&ndash;", "-");
        ENTITIES.put("&hellip;", "...");
        ENTITIES.put("&rarr;", "->");
    }

    private DocumentTextExtractor() {
    }

    public static boolean looksLikeHtml(String body) {
        if (body == null || body.length() < 40) {
            return false;
        }
        String head = body.substring(0, Math.min(2000, body.length())).toLowerCase(java.util.Locale.ROOT);
        return head.contains("<!doctype html") || head.contains("<html");
    }

    /**
     * Returns readable text. Non-HTML input is returned unchanged; HTML is reduced to the content
     * container when one is recognised, and to the whole body otherwise.
     */
    public static String extract(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        if (!looksLikeHtml(body)) {
            return body;
        }
        String content = extractContainer(body, MARKDOWN_BODY_OPEN, DIV_TAG);
        if (content == null) {
            content = extractContainer(body, WIKI_BODY_OPEN, DIV_TAG);
        }
        if (content == null) {
            content = extractContainer(body, ARTICLE_OPEN, ARTICLE_TAG);
        }
        if (content == null) {
            content = body;
        }
        String cleaned = SCRIPT_OR_STYLE.matcher(content).replaceAll(" ");
        // Preserve block structure so sentence-level patterns do not run across headings.
        cleaned = cleaned.replaceAll("(?i)</(p|div|li|h[1-6]|tr|pre|blockquote)>", "\n");
        cleaned = cleaned.replaceAll("(?i)<br\\s*/?>", "\n");
        cleaned = cleaned.replaceAll("(?i)<li[^>]*>", "\n- ");
        cleaned = TAG.matcher(cleaned).replaceAll("");
        cleaned = decodeEntities(cleaned);
        cleaned = SPACE_RUN.matcher(cleaned).replaceAll(" ");
        cleaned = BLANK_RUN.matcher(cleaned).replaceAll("\n\n");
        return cleaned.strip();
    }

    /**
     * Extracts the inner content of a container by counting opening and closing tags.
     *
     * @param openPattern matches the container's opening tag
     * @param tagPattern  matches every opening and closing tag of that element type
     * @return the inner HTML, or null when the container is absent or unbalanced
     */
    static String extractContainer(String body, Pattern openPattern, Pattern tagPattern) {
        Matcher open = openPattern.matcher(body);
        if (!open.find()) {
            return null;
        }
        int contentStart = open.end();
        Matcher tags = tagPattern.matcher(body);
        if (!tags.find(contentStart)) {
            return null;
        }
        int depth = 1;
        do {
            String tag = tags.group();
            if (tag.startsWith("</")) {
                depth--;
                if (depth == 0) {
                    return body.substring(contentStart, tags.start());
                }
            } else if (!tag.endsWith("/>")) {
                depth++;
            }
        } while (tags.find());
        // Unbalanced markup: take everything after the opening tag rather than returning nothing.
        return body.substring(contentStart);
    }

    static String decodeEntities(String text) {
        String result = text;
        for (Map.Entry<String, String> entity : ENTITIES.entrySet()) {
            result = result.replace(entity.getKey(), entity.getValue());
        }
        Matcher numeric = Pattern.compile("&#(\\d{2,5});").matcher(result);
        StringBuilder sb = new StringBuilder();
        while (numeric.find()) {
            int codePoint = Integer.parseInt(numeric.group(1));
            numeric.appendReplacement(sb, Matcher.quoteReplacement(
                    codePoint < 0x110000 ? new String(Character.toChars(codePoint)) : ""));
        }
        numeric.appendTail(sb);
        return sb.toString();
    }
}
