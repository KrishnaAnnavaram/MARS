package com.bootshift.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * JSON facade for the artifact plane.
 *
 * <p>Two writers exist on purpose: a pretty writer for human-inspectable artifacts, and a
 * canonical writer (recursively key-sorted, no insignificant whitespace) used wherever a hash is
 * computed over JSON - ledger links, evidence manifests, normalization policy hashes.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    public static String pretty(Object value) {
        try {
            return PRETTY.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String canonical(Object value) {
        JsonNode tree = value instanceof JsonNode node ? node : CANONICAL_MAPPER.valueToTree(value);
        StringBuilder sb = new StringBuilder();
        writeCanonical(tree, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            Collections.sort(names);
            sb.append("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(quote(names.get(i))).append(":");
                writeCanonical(node.get(names.get(i)), sb);
            }
            sb.append("}");
            return;
        }
        if (node.isArray()) {
            sb.append("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                writeCanonical(node.get(i), sb);
            }
            sb.append("]");
            return;
        }
        if (node.isTextual()) {
            sb.append(quote(node.textValue()));
            return;
        }
        sb.append(node.asText());
    }

    private static String quote(String raw) {
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String canonicalHash(Object value) {
        return Hashing.sha256(canonical(value));
    }

    public static void write(Path target, Object value) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, pretty(value) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write JSON artifact " + target, e);
        }
    }

    /**
     * Writes JSON by creating a sibling temporary file and moving it into place.
     *
     * <p>A plain write leaves a window in which the target is truncated. For an artifact that is
     * merely one of many that is a lost file; for {@code latest.json} it is worse, because a reader
     * that finds a truncated pointer cannot fall back to the previous good one - the pointer IS the
     * fallback. The move is requested atomic and degrades to a replacing move on filesystems that
     * cannot promise it, which is still strictly better than writing in place.
     */
    public static void writeAtomic(Path target, Object value) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, pretty(value) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write JSON artifact " + target, e);
        }
    }

    public static void appendLine(Path target, Object value) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, canonical(value) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append JSONL record to " + target, e);
        }
    }

    public static JsonNode read(Path source) {
        try {
            return MAPPER.readTree(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JSON artifact " + source, e);
        }
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse JSON", e);
        }
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        return MAPPER.convertValue(node, type);
    }

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }
}
