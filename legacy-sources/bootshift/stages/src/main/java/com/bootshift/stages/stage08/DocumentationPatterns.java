package com.bootshift.stages.stage08;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prose patterns for the documentation channel (Agent 08).
 *
 * <p>Written against how Spring release notes and migration guides actually read, rather than
 * against an idealised grammar. Real notes say "YamlJsonParser has been removed", "a replacement,
 * server.max-http-request-header-size, has been introduced" and "should be replaced with a
 * text-based banner.txt file" - three different shapes for the same class of fact.
 *
 * <p>Everything produced here is a CANDIDATE. Documentation states intent; only the artifact channel
 * can confirm that the published bytes actually changed (R10).
 */
public final class DocumentationPatterns {

    /** One candidate fact located in prose, with the sentence that produced it. */
    public record Extraction(MigrationFact.Type type, String subject, String replacement,
                             String sentence, String rule) {
    }

    /** A Java type name, a dotted FQN, an annotation, or a dotted configuration key. */
    private static final String SUBJECT =
            "(@?[A-Za-z][\\w.$]*(?:\\.[A-Za-z][\\w.$]*)*)";

    private static final Pattern REMOVED = Pattern.compile(
            SUBJECT + "\\s+(?:has been|have been|has|is|was|are|were)\\s+removed", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPRECATED = Pattern.compile(
            SUBJECT + "\\s+(?:has been|have been|has|is|was|are|were)\\s+deprecated", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAMED_TO = Pattern.compile(
            SUBJECT + "\\s+(?:has been|have been|has|is|was)\\s+renamed\\s+to\\s+" + SUBJECT,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACED_BY = Pattern.compile(
            SUBJECT + "[^.]{0,60}?\\s+(?:replaced|superseded)\\s+(?:by|with)\\s+" + SUBJECT,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACEMENT_INTRODUCED = Pattern.compile(
            SUBJECT + "\\s+has been deprecated[^.]{0,40}?replacement,\\s*" + SUBJECT,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_LONGER = Pattern.compile(
            SUBJECT + "\\s+is no longer\\s+(?:supported|available|needed|used)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_CHANGED = Pattern.compile(
            SUBJECT + "[^.]{0,60}?default(?:s| value)?\\s+(?:has been |is now |changed |set )",
            Pattern.CASE_INSENSITIVE);

    /** Sentence-leading words that produce noise rather than subjects. */
    private static final Set<String> STOPWORDS = new LinkedHashSet<>(List.of(
            "the", "this", "that", "these", "those", "it", "they", "support", "supports",
            "and", "but", "which", "when", "if", "as", "all", "any", "some", "several", "many",
            "a", "an", "in", "on", "for", "with", "by", "from", "to", "of", "spring", "boot",
            "classes", "methods", "properties", "class", "method", "property", "please", "note",
            "additionally", "however", "therefore", "finally", "also", "now", "previously"));

    private DocumentationPatterns() {
    }

    /** Extracts candidate facts from a document rendition. */
    public static List<Extraction> extract(String text) {
        List<Extraction> extractions = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return extractions;
        }
        Set<String> seen = new LinkedHashSet<>();

        for (String sentence : sentences(text)) {
            // Order matters: a sentence that names a replacement is a rename, not a bare removal.
            if (add(extractions, seen, sentence, REPLACEMENT_INTRODUCED,
                    MigrationFact.Type.PROPERTY_RENAMED, true, "DOC_REPLACEMENT_INTRODUCED")) {
                continue;
            }
            if (add(extractions, seen, sentence, RENAMED_TO,
                    MigrationFact.Type.API_RENAMED, true, "DOC_RENAMED_TO")) {
                continue;
            }
            if (add(extractions, seen, sentence, REPLACED_BY,
                    MigrationFact.Type.API_RENAMED, true, "DOC_REPLACED_BY")) {
                continue;
            }
            if (add(extractions, seen, sentence, REMOVED,
                    MigrationFact.Type.API_REMOVED, false, "DOC_REMOVED")) {
                continue;
            }
            if (add(extractions, seen, sentence, NO_LONGER,
                    MigrationFact.Type.API_REMOVED, false, "DOC_NO_LONGER_SUPPORTED")) {
                continue;
            }
            if (add(extractions, seen, sentence, DEPRECATED,
                    MigrationFact.Type.API_REMOVED, false, "DOC_DEPRECATED")) {
                continue;
            }
            add(extractions, seen, sentence, DEFAULT_CHANGED,
                    MigrationFact.Type.DEFAULT_CHANGED, false, "DOC_DEFAULT_CHANGED");
        }
        return extractions;
    }

    private static boolean add(List<Extraction> extractions, Set<String> seen, String sentence,
                               Pattern pattern, MigrationFact.Type defaultType, boolean hasReplacement,
                               String rule) {
        Matcher matcher = pattern.matcher(sentence);
        boolean matched = false;
        int guard = 0;
        while (matcher.find() && guard++ < 20) {
            String subject = clean(matcher.group(1));
            if (!isUsefulSubject(subject)) {
                continue;
            }
            String replacement = hasReplacement && matcher.groupCount() >= 2
                    ? clean(matcher.group(2)) : null;
            if (replacement != null && !isUsefulSubject(replacement)) {
                replacement = null;
            }
            MigrationFact.Type type = refineType(subject, defaultType, replacement);
            if (!seen.add(type + "|" + subject)) {
                matched = true;
                continue;
            }
            extractions.add(new Extraction(type, subject, replacement, trim(sentence), rule));
            matched = true;
        }
        return matched;
    }

    /**
     * A dotted lowercase token is a configuration key; anything else is an API symbol. Getting this
     * wrong sends the impact analyzer looking in the wrong place entirely.
     */
    static MigrationFact.Type refineType(String subject, MigrationFact.Type defaultType,
                                         String replacement) {
        boolean configurationKey = subject.contains(".") && !subject.startsWith("@")
                && subject.equals(subject.toLowerCase(Locale.ROOT));
        if (configurationKey) {
            return replacement != null
                    ? MigrationFact.Type.PROPERTY_RENAMED : MigrationFact.Type.PROPERTY_REMOVED;
        }
        if (defaultType == MigrationFact.Type.PROPERTY_RENAMED) {
            return MigrationFact.Type.API_RENAMED;
        }
        return defaultType;
    }

    /**
     * Filters out sentence noise. A subject is useful when it is an annotation, a dotted name, or a
     * CamelCase identifier - the shapes a reader would recognise as a thing rather than a word.
     */
    static boolean isUsefulSubject(String subject) {
        if (subject == null || subject.length() < 3 || subject.length() > 120) {
            return false;
        }
        if (STOPWORDS.contains(subject.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (subject.startsWith("@")) {
            return subject.length() > 3;
        }
        if (subject.contains(".")) {
            // Reject sentence fragments that merely ended up adjacent to a full stop.
            return subject.chars().filter(c -> c == '.').count() >= 1
                    && !subject.endsWith(".")
                    && subject.matches("[A-Za-z][\\w.$]*");
        }
        // Bare identifier: require CamelCase, which is how type names read in prose.
        return subject.matches("[A-Z][a-z0-9]*(?:[A-Z][a-z0-9]*)+");
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(",") || cleaned.endsWith(")")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static String trim(String sentence) {
        String collapsed = sentence.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 300 ? collapsed : collapsed.substring(0, 300) + "...";
    }

    /** Splits on sentence and line boundaries so a heading is treated as its own statement. */
    static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String block : text.split("\\n")) {
            String line = block.strip();
            if (line.isEmpty()) {
                continue;
            }
            for (String sentence : line.split("(?<=[.!?])\\s+")) {
                String candidate = sentence.strip();
                if (candidate.length() >= 12) {
                    sentences.add(candidate);
                }
            }
        }
        return sentences;
    }
}
