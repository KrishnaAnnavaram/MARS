package com.bootshift.adapters.transform;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A structural view of a YAML document that maps each leaf's dotted key to its physical lines.
 *
 * <p>The transformer this replaces computed nesting depth as {@code indent / 2}. That is an
 * assumption, not a rule: YAML permits any consistent indentation, and a four-space file - which is
 * common - produced dotted keys with the wrong depth, so a rule for
 * {@code spring.datasource.url} silently matched nothing while the run reported the property as
 * migrated. Nested sequences and quoted keys made it worse.
 *
 * <p>The structure is resolved by a real YAML parser, which also reports the exact line each key
 * occupies. Edits are then applied to those lines, so comments, blank lines, ordering and
 * indentation everywhere else survive untouched. Parsing to rewrite the whole file would be simpler
 * and would destroy every comment in it.
 *
 * <p>Multi-document files are handled as multiple documents, because Spring profile documents live
 * in exactly that shape and treating the file as one document merges keys across profiles.
 */
public final class YamlPropertyModel {

    /** One leaf property: its dotted key and where it physically lives. */
    public record Leaf(String dottedKey, int documentIndex, int keyLine, int valueEndLine,
                       int indent, String rawValue, boolean block, boolean sequence) {
    }

    /** A parse that failed, so the caller can degrade honestly instead of guessing. */
    public record ParseFailure(String detail) {
    }

    private final List<Leaf> leaves;
    private final ParseFailure failure;
    private final int documentCount;

    private YamlPropertyModel(List<Leaf> leaves, ParseFailure failure, int documentCount) {
        this.leaves = leaves;
        this.failure = failure;
        this.documentCount = documentCount;
    }

    public List<Leaf> leaves() {
        return leaves;
    }

    public boolean parsed() {
        return failure == null;
    }

    public ParseFailure failure() {
        return failure;
    }

    public int documentCount() {
        return documentCount;
    }

    /** Every dotted key in the file, in document order. */
    public Map<String, Leaf> byDottedKey() {
        Map<String, Leaf> map = new LinkedHashMap<>();
        // Later documents win for lookup purposes, but every occurrence is still in leaves(), and
        // callers that rewrite iterate leaves() so a key repeated across profiles is rewritten in
        // each profile rather than only once.
        leaves.forEach(leaf -> map.put(leaf.dottedKey(), leaf));
        return map;
    }

    /**
     * Parses the content.
     *
     * <p>A file that does not parse yields a model that says so. The alternative - falling back to
     * line scanning - is how a malformed file gets edited by a rule that was never really matched.
     */
    public static YamlPropertyModel parse(String content) {
        if (content == null || content.isBlank()) {
            return new YamlPropertyModel(List.of(), null, 0);
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        // Anchors and aliases are permitted but bounded: an unbounded alias expansion is a denial of
        // service on untrusted input, and the repository under analysis is untrusted input.
        options.setMaxAliasesForCollections(200);
        options.setAllowRecursiveKeys(false);

        List<Leaf> leaves = new ArrayList<>();
        int documents = 0;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (Node root : yaml.composeAll(new java.io.StringReader(content))) {
                collect(root, new ArrayList<>(), documents, leaves);
                documents++;
            }
        } catch (RuntimeException e) {
            return new YamlPropertyModel(List.of(),
                    new ParseFailure(e.getClass().getSimpleName() + ": " + e.getMessage()), 0);
        }
        return new YamlPropertyModel(leaves, null, documents);
    }

    private static void collect(Node node, List<String> path, int documentIndex, List<Leaf> leaves) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                    continue;
                }
                List<String> child = new ArrayList<>(path);
                child.add(key.getValue());
                Node value = tuple.getValueNode();
                if (value instanceof MappingNode) {
                    collect(value, child, documentIndex, leaves);
                } else if (value instanceof SequenceNode sequence) {
                    // A sequence is a leaf for property purposes: Spring binds it as an indexed
                    // list, and the dotted key is the sequence's own key.
                    leaves.add(new Leaf(String.join(".", child), documentIndex,
                            key.getStartMark().getLine(), sequence.getEndMark().getLine(),
                            key.getStartMark().getColumn(), null, true, true));
                } else if (value instanceof ScalarNode scalar) {
                    boolean block = scalar.getScalarStyle() == org.yaml.snakeyaml.DumperOptions
                            .ScalarStyle.LITERAL
                            || scalar.getScalarStyle() == org.yaml.snakeyaml.DumperOptions
                            .ScalarStyle.FOLDED;
                    leaves.add(new Leaf(String.join(".", child), documentIndex,
                            key.getStartMark().getLine(), scalar.getEndMark().getLine(),
                            key.getStartMark().getColumn(), scalar.getValue(), block, false));
                }
            }
        }
    }

    /**
     * Rewrites the key of one leaf in place, preserving the value and everything around it.
     *
     * <p>Only the key token on the key's own line is replaced. The value - including a quoted value,
     * a trailing comment, or a block scalar continuing over later lines - is left exactly as it was.
     */
    public static String rewriteKeyLine(String line, String oldKeySegment, String newDottedKey) {
        int colon = indexOfKeyColon(line);
        if (colon < 0) {
            return line;
        }
        String leading = line.substring(0, line.length() - line.stripLeading().length());
        String remainder = line.substring(colon);
        return leading + newDottedKey + remainder;
    }

    /**
     * The index of the colon that separates key from value.
     *
     * <p>Quoted keys may contain a colon, and a value certainly may - {@code url: jdbc:mysql://...}
     * is the everyday case. Taking the first colon rewrites the wrong token.
     */
    public static int indexOfKeyColon(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == ':' && !inSingle && !inDouble) {
                // A colon only separates when followed by whitespace or end of line.
                if (i + 1 >= line.length() || Character.isWhitespace(line.charAt(i + 1))) {
                    return i;
                }
            }
        }
        return -1;
    }
}
