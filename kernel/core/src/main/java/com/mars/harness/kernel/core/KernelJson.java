package com.mars.harness.kernel.core;

import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON facade for unified kernel artifacts.
 *
 * <p>Kernel artifacts use snake_case, like Bootshift's published artifacts. Hashing always goes
 * through Bootshift's canonical writer ({@link Json#canonical}), so a kernel hash and a Bootshift
 * hash over the same tree are the same value.
 */
public final class KernelJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private KernelJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode tree(Object value) {
        return value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot convert JSON to " + type.getSimpleName(), e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Canonical (key-sorted, compact) serialization, shared with Bootshift's ledger hashing. */
    public static String canonical(Object value) {
        return Json.canonical(tree(value));
    }

    public static String hash(Object value) {
        return Hashing.sha256(canonical(value));
    }

    public static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public static <T> T read(Path file, Class<T> type) {
        return convert(read(file), type);
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse JSON", e);
        }
    }
}
