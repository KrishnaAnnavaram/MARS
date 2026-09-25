package com.mars.harness.kernel.core.identity;

/**
 * Where an entity was observed. A location is an attribute, never an identity: line numbers move
 * with every edit above them.
 */
public record Location(String path, int lineStart, int colStart, int lineEnd, int colEnd) {

    public static Location of(String path, int lineStart, int lineEnd) {
        return new Location(path, lineStart, 0, lineEnd, 0);
    }

    public boolean containsLine(int line) {
        return line >= lineStart && line <= lineEnd;
    }

    public boolean overlaps(int fromLine, int toLine) {
        return fromLine <= lineEnd && toLine >= lineStart;
    }

    @Override
    public String toString() {
        return path + ":" + lineStart + (lineEnd != lineStart ? "-" + lineEnd : "");
    }
}
