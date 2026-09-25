package com.mars.harness.tests.parity;

import com.bootshift.adapters.mutation.FileMutationGateway;
import com.mars.harness.kernel.core.change.UnifiedDiff;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.4: the kernel's {@link UnifiedDiff#of} renders byte-identical output to Bootshift's
 * {@code FileMutationGateway.unifiedDiff} (which it copies so capability packs can render
 * proposals without reaching the adapter layer) for edits, insertions at the start and end,
 * deletions, CRLF content, a missing trailing newline, file creation and deletion, rename, no
 * change, far-apart and near-together hunks, and context widths 0, 1, 3 and 10.
 */
class UnifiedDiffParityTest {

    private static final String BASE = lines(1, 30);

    static Stream<Arguments> pairs() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("single edit", "A.java", "A.java", BASE, BASE.replace("line 15\n", "line fifteen\n")));
        cases.add(Arguments.of("insert at start", "A.java", "A.java", BASE, "package x;\n\n" + BASE));
        cases.add(Arguments.of("insert at end", "A.java", "A.java", BASE, BASE + "line 31\nline 32\n"));
        cases.add(Arguments.of("delete block", "A.java", "A.java", BASE, BASE.replace("line 10\nline 11\nline 12\n", "")));
        cases.add(Arguments.of("far-apart hunks", "A.java", "A.java", BASE,
                BASE.replace("line 2\n", "line two\n").replace("line 28\n", "line twenty-eight\n")));
        cases.add(Arguments.of("near-together hunks", "A.java", "A.java", BASE,
                BASE.replace("line 10\n", "line ten\n").replace("line 14\n", "line fourteen\n")));
        cases.add(Arguments.of("replace everything", "A.java", "A.java", "a\nb\nc\n", "x\ny\n"));
        cases.add(Arguments.of("crlf edit", "W.java", "W.java", BASE.replace("\n", "\r\n"),
                BASE.replace("\n", "\r\n").replace("line 5\r\n", "line five\r\n")));
        cases.add(Arguments.of("crlf to lf", "W.java", "W.java", "a\r\nb\r\n", "a\nb\n"));
        cases.add(Arguments.of("no trailing newline before", "N.java", "N.java", "a\nb\nc", "a\nb\nc\n"));
        cases.add(Arguments.of("no trailing newline after", "N.java", "N.java", "a\nb\nc\n", "a\nb\nC"));
        cases.add(Arguments.of("create", null, "New.java", null, "class New {\n}\n"));
        cases.add(Arguments.of("delete file", "Old.java", null, "class Old {\n}\n", null));
        cases.add(Arguments.of("rename with edit", "old/A.java", "new/A.java", BASE, BASE.replace("line 1\n", "line one\n")));
        cases.add(Arguments.of("unchanged", "A.java", "A.java", BASE, BASE));
        cases.add(Arguments.of("empty to content", "E.txt", "E.txt", "", "x\n"));
        cases.add(Arguments.of("repeated lines", "R.txt", "R.txt", "x\nx\ny\nx\nx\n", "x\ny\nx\ny\nx\n"));
        List<Arguments> withContext = new ArrayList<>();
        for (Arguments c : cases) {
            for (int context : new int[]{0, 1, 3, 10}) {
                Object[] a = c.get();
                withContext.add(Arguments.of(a[0], a[1], a[2], a[3], a[4], context));
            }
        }
        return withContext.stream();
    }

    @ParameterizedTest(name = "{0}, context {5}")
    @MethodSource("pairs")
    void kernelDiffIsByteIdenticalToBootshift(String name, String pathBefore, String pathAfter, String before, String after,
                                              int context) {
        String bootshift = FileMutationGateway.unifiedDiff(pathBefore, pathAfter, before, after, context);
        assertThat(UnifiedDiff.of(pathBefore, pathAfter, before, after, context)).isEqualTo(bootshift);
    }

    private static String lines(int from, int to) {
        StringBuilder sb = new StringBuilder();
        IntStream.rangeClosed(from, to).forEach(i -> sb.append("line ").append(i).append('\n'));
        return sb.toString();
    }
}
