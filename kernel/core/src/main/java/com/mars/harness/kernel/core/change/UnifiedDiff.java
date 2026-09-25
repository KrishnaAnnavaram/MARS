package com.mars.harness.kernel.core.change;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified diff with hunk headers and context, from a longest-common-subsequence.
 *
 * <p>The algorithm and output are identical to Bootshift's {@code FileMutationGateway.unifiedDiff},
 * which lives in Bootshift's adapter layer where capability packs may not reach. A parity test
 * asserts byte-identical output for the same inputs. The gateway keeps using Bootshift's copy for
 * the patches it writes, so this one only renders proposals for review.
 */
public final class UnifiedDiff {

    private UnifiedDiff() {
    }

    public static String of(String pathBefore, String pathAfter, String before, String after, int context) {
        List<String> beforeLines = before == null ? List.of() : List.of(before.split("\n", -1));
        List<String> afterLines = after == null ? List.of() : List.of(after.split("\n", -1));
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(pathBefore == null ? "/dev/null" : "a/" + pathBefore).append('\n');
        sb.append("+++ ").append(pathAfter == null ? "/dev/null" : "b/" + pathAfter).append('\n');
        List<int[]> ops = diffOps(beforeLines, afterLines);
        if (ops.stream().noneMatch(op -> op[0] != 0)) {
            return sb.toString();
        }
        int index = 0;
        while (index < ops.size()) {
            if (ops.get(index)[0] == 0) {
                index++;
                continue;
            }
            int changeStart = index;
            int changeEnd = index;
            for (int scan = index; scan < ops.size(); scan++) {
                if (ops.get(scan)[0] != 0) {
                    changeEnd = scan;
                } else if (scan - changeEnd > 2 * context) {
                    break;
                }
            }
            int hunkStart = Math.max(0, changeStart - context);
            int hunkEnd = Math.min(ops.size() - 1, changeEnd + context);
            int oldStart = -1;
            int newStart = -1;
            int oldCount = 0;
            int newCount = 0;
            StringBuilder body = new StringBuilder();
            for (int i = hunkStart; i <= hunkEnd; i++) {
                int[] op = ops.get(i);
                if (op[1] >= 0 && oldStart < 0) {
                    oldStart = op[1];
                }
                if (op[2] >= 0 && newStart < 0) {
                    newStart = op[2];
                }
                switch (op[0]) {
                    case 0 -> {
                        body.append(' ').append(beforeLines.get(op[1])).append('\n');
                        oldCount++;
                        newCount++;
                    }
                    case -1 -> {
                        body.append('-').append(beforeLines.get(op[1])).append('\n');
                        oldCount++;
                    }
                    default -> {
                        body.append('+').append(afterLines.get(op[2])).append('\n');
                        newCount++;
                    }
                }
            }
            sb.append("@@ -").append(oldCount == 0 ? 0 : Math.max(0, oldStart) + 1).append(',').append(oldCount).append(" +")
                    .append(newCount == 0 ? 0 : Math.max(0, newStart) + 1).append(',').append(newCount).append(" @@\n");
            sb.append(body);
            index = hunkEnd + 1;
        }
        return sb.toString();
    }

    private static List<int[]> diffOps(List<String> before, List<String> after) {
        int n = before.size();
        int m = after.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = before.get(i).equals(after.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<int[]> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (before.get(i).equals(after.get(j))) {
                ops.add(new int[]{0, i, j});
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new int[]{-1, i, -1});
                i++;
            } else {
                ops.add(new int[]{1, -1, j});
                j++;
            }
        }
        while (i < n) {
            ops.add(new int[]{-1, i++, -1});
        }
        while (j < m) {
            ops.add(new int[]{1, -1, j++});
        }
        return ops;
    }
}
