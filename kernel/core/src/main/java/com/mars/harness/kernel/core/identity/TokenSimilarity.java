package com.mars.harness.kernel.core.identity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic similarity measures used by reattachment. Every measure is symmetric and in [0, 1].
 */
public final class TokenSimilarity {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*|\\d+(?:\\.\\d+)?|\"(?:[^\"\\\\]|\\\\.)*\"|\\S");

    private TokenSimilarity() {
    }

    public static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /**
     * Sequence similarity {@code 2 * LCS(a, b) / (|a| + |b|)} over tokens. It is order-aware, so
     * {@code save(sanitize(e))} stays close to {@code save(e)} while {@code b = a} and
     * {@code a = b} do not look identical.
     */
    public static double sequence(String left, String right) {
        List<String> a = tokens(left);
        List<String> b = tokens(right);
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        return 2.0 * lcs(a, b) / (a.size() + b.size());
    }

    public static <T> int lcs(List<T> a, List<T> b) {
        int[][] table = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                table[i][j] = a.get(i).equals(b.get(j)) ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        return table[0][0];
    }

    /** LCS alignment as index pairs (oldIndex, newIndex), in order. */
    public static <T> List<int[]> align(List<T> a, List<T> b) {
        int[][] table = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                table[i][j] = a.get(i).equals(b.get(j)) ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        List<int[]> pairs = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                pairs.add(new int[]{i, j});
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return pairs;
    }

    /** Multiset Dice coefficient. Two empty multisets are 0: no evidence is not a match. */
    public static double dice(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> counts = new HashMap<>();
        left.forEach(t -> counts.merge(t, 1, Integer::sum));
        int common = 0;
        for (String t : right) {
            Integer c = counts.get(t);
            if (c != null && c > 0) {
                common++;
                counts.put(t, c - 1);
            }
        }
        return 2.0 * common / (left.size() + right.size());
    }

    /** Fraction of {@code part}'s tokens contained in {@code whole} (multiset). */
    public static double containment(String part, String whole) {
        List<String> p = tokens(part);
        List<String> w = tokens(whole);
        if (p.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> counts = new HashMap<>();
        w.forEach(t -> counts.merge(t, 1, Integer::sum));
        int contained = 0;
        for (String t : p) {
            Integer c = counts.get(t);
            if (c != null && c > 0) {
                contained++;
                counts.put(t, c - 1);
            }
        }
        return (double) contained / p.size();
    }
}
