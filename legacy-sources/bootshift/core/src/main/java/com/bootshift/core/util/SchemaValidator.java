package com.bootshift.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned JSON Schema validation for the artifact plane (spec section 43).
 *
 * <p>Every stage validates its artifacts before publishing. A validation error prevents
 * {@code latest.json} from advancing, which is how a malformed artifact cannot become the input to
 * the next stage.
 */
public final class SchemaValidator {

    private final Path schemaRoot;
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> cache = new LinkedHashMap<>();
    private final boolean available;

    public SchemaValidator(Path schemaRoot) {
        this.schemaRoot = schemaRoot;
        this.available = schemaRoot != null && Files.isDirectory(schemaRoot);
    }

    public boolean available() {
        return available;
    }

    public Path schemaRoot() {
        return schemaRoot;
    }

    /**
     * Validates a payload against {@code <schemaRoot>/<relativeSchemaPath>}.
     *
     * @return the list of validation messages; empty means valid. A missing schema file is reported
     *         as a single explicit message rather than silently passing.
     */
    public List<String> validate(String relativeSchemaPath, JsonNode payload) {
        if (!available) {
            return List.of("Schema root not available: " + schemaRoot);
        }
        Path schemaFile = schemaRoot.resolve(relativeSchemaPath);
        if (!Files.isRegularFile(schemaFile)) {
            return List.of("Schema not found: " + relativeSchemaPath);
        }
        JsonSchema schema = cache.computeIfAbsent(relativeSchemaPath, key -> {
            try {
                return factory.getSchema(Files.readString(schemaFile));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load schema " + schemaFile, e);
            }
        });
        Set<ValidationMessage> messages = schema.validate(payload);
        List<String> result = new ArrayList<>();
        messages.forEach(m -> result.add(m.getMessage()));
        result.sort(java.util.Comparator.naturalOrder());
        return result;
    }

    /** Lists every schema file under the root, used by the schema conformance test suite. */
    public List<Path> allSchemas() {
        if (!available) {
            return List.of();
        }
        try (var stream = Files.walk(schemaRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".schema.json"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot enumerate schemas under " + schemaRoot, e);
        }
    }
}
