package io.runtimeconditions.profiler.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/** YAML and JSON helpers. Documents are read as plain data and are never turned into objects. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** The largest document the profiler reads. */
    public static final int MAX_BYTES = 64 * 1024 * 1024;

    private Json() {
    }

    public static byte[] readLimited(InputStream in, String what) throws IOException {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) {
            throw new ProfilerException("bindings", "file exceeds the " + MAX_BYTES + " byte size limit", what);
        }
        return data;
    }

    /** Reads one YAML 1.2 document, under the core schema, into a JSON tree. Anchors and aliases are limited. */
    public static JsonNode yaml(byte[] data, String stage, String what) {
        LoadSettings settings = LoadSettings.builder()
                .setSchema(new CoreSchema())
                .setMaxAliasesForCollections(100)
                .setAllowDuplicateKeys(false)
                .setCodePointLimit(MAX_BYTES)
                .build();
        try {
            Object value = new Load(settings).loadFromString(new String(data, StandardCharsets.UTF_8));
            return MAPPER.valueToTree(value);
        } catch (YamlEngineException | IllegalArgumentException | StackOverflowError e) {
            throw new ProfilerException(stage, "invalid YAML: " + e.getMessage(), what);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sorted keys, no whitespace, strings escaped as JSON.stringify does. */
    public static String canonical(JsonNode node) {
        StringBuilder out = new StringBuilder();
        canonical(node, out);
        return out.toString();
    }

    private static void canonical(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            Collections.sort(names);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                quote(names.get(i), out);
                out.append(':');
                canonical(node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                canonical(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            quote(node.textValue(), out);
        } else if (node.isIntegralNumber() || node.isBoolean() || node.isNull()) {
            out.append(node.asText());
        } else {
            throw new ProfilerException("schema", "a non-integer number cannot be canonicalized");
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    public static ObjectNode object() {
        return NODES.objectNode();
    }

    public static ArrayNode array() {
        return NODES.arrayNode();
    }

    /** Converts a JSON tree back to plain maps and lists, keeping key order. */
    public static Object plain(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), plain(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(plain(item)));
            return list;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return node.textValue();
    }
}
