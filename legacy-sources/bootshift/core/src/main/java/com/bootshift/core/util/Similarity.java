package com.bootshift.core.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content similarity used only as the last resort in identity reattachment (spec 11.2 step 4).
 *
 * <p>Uses token-shingle Jaccard similarity: cheap, language-agnostic, and stable under reformatting
 * because shingles are built from normalized whitespace-separated tokens rather than raw lines.
 */
public final class Similarity {

    private static final int SHINGLE = 3;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Similarity() {
    }

    public static Set<String> shingles(String content) {
        String[] tokens = WHITESPACE.matcher(content).replaceAll(" ").trim().split(" ");
        Set<String> result = new HashSet<>();
        if (tokens.length < SHINGLE) {
            if (tokens.length > 0 && !tokens[0].isEmpty()) {
                result.add(String.join(" ", tokens));
            }
            return result;
        }
        for (int i = 0; i + SHINGLE <= tokens.length; i++) {
            result.add(tokens[i] + " " + tokens[i + 1] + " " + tokens[i + 2]);
        }
        return result;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int intersection = 0;
        for (String token : smaller) {
            if (larger.contains(token)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    public static double compare(String left, String right) {
        return jaccard(shingles(left), shingles(right));
    }

    /**
     * Containment of {@code part} within {@code whole}. Used for split detection: a fragment that is
     * almost entirely contained in the original file is a descendant even when Jaccard is low
     * because the sizes differ.
     */
    public static double containment(String part, String whole) {
        Set<String> partShingles = shingles(part);
        Set<String> wholeShingles = shingles(whole);
        if (partShingles.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String token : partShingles) {
            if (wholeShingles.contains(token)) {
                intersection++;
            }
        }
        return (double) intersection / partShingles.size();
    }

    /** Cheap pre-filter so similarity is not computed against every registry entry. */
    public static Map<String, Integer> sizeBuckets(Map<String, Long> sizes, int bucketBytes) {
        Map<String, Integer> buckets = new HashMap<>();
        sizes.forEach((key, size) -> buckets.put(key, (int) (size / bucketBytes)));
        return buckets;
    }
}
