package io.runtimeconditions.profiler.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The schemas bundled with the profiler, and JSON Schema validation against them. */
public final class Schemas {
    public static final String CORE_ID =
            "https://runtimeconditions.io/schemas/profile/0.4.0/runtimeconditions.profile.schema.yaml";
    public static final String CORE_VERSION = "0.4.0";
    public static final String CORE_SOURCE_SHA256 = "96d430c7936fcf7334aa9613f63bb306592e07cf56fe4154af57933d2c1480e6";
    public static final String CORE_SEMANTIC_SHA256 = "ed447dccefd7507d905b0b6177b1386d8ee96a1d053b731939a9a7ae973d5af1";

    /** The 0.2.0 core schema the first published bindings were normalized against. Its structure is kept by 0.4.0. */
    public static final String CORE_0_2_0_ID =
            "https://runtimeconditions.io/schemas/profile/0.2.0/runtimeconditions.profile.schema.yaml";
    public static final String CORE_0_2_0_VERSION = "0.2.0";
    public static final String CORE_0_2_0_SEMANTIC_SHA256 =
            "a090a8016d045f9c3fa872a67f8df293b77ca2809a1bea5ae9fa31a27a06109a";

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private Schemas() {
    }

    /** Reads a bundled schema; the core profile schema must match its pinned digests. */
    public static JsonNode load(String name) {
        try (InputStream in = Schemas.class.getResourceAsStream("/schemas/" + name)) {
            if (in == null) {
                throw new ProfilerException("schema", "the bundled schema " + name + " is missing");
            }
            byte[] bytes = Json.readLimited(in, name);
            JsonNode schema = Json.yaml(bytes, "schema", name);
            if (name.equals("runtimeconditions.profile.schema.yaml")
                    && (!CORE_ID.equals(schema.path("$id").asText())
                            || !CORE_SOURCE_SHA256.equals(Json.sha256(bytes))
                            || !CORE_SEMANTIC_SHA256.equals(Json.sha256(Json.canonical(schema).getBytes(java.nio.charset.StandardCharsets.UTF_8))))) {
                throw new ProfilerException("schema", "the bundled core profile schema does not match its pinned identity and digests");
            }
            return schema;
        } catch (IOException e) {
            throw new ProfilerException("schema", "cannot read the bundled schema " + name + ": " + e.getMessage());
        }
    }

    public static JsonSchema compile(JsonNode schema) {
        try {
            return FACTORY.getSchema(schema);
        } catch (RuntimeException e) {
            throw new ProfilerException("schema", "a schema cannot be compiled: " + e.getMessage());
        }
    }

    /** Returns the sorted validation problems; empty means the document is valid. */
    public static List<String> problems(JsonSchema schema, JsonNode document) {
        Set<ValidationMessage> found = schema.validate(document);
        TreeSet<String> sorted = new TreeSet<>();
        for (ValidationMessage message : found) {
            sorted.add(message.getMessage());
        }
        return new ArrayList<>(sorted);
    }
}
