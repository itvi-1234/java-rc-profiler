package io.runtimeconditions.profiler.bindings;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import io.runtimeconditions.profiler.core.Json;
import io.runtimeconditions.profiler.core.ProfilerException;
import io.runtimeconditions.profiler.core.Schemas;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and verifies the resources of one generated Java binding package. A package carries them in the
 * runtimeconditions directory of its jar or classes directory, with a file manifest listing their digests.
 */
public final class BindingLoader {
    public static final String DIRECTORY = "runtimeconditions/";
    static final String BINDINGS = DIRECTORY + "runtimeconditions.bindings.yaml";
    static final String MODEL = DIRECTORY + "runtimeconditions.binding-model.yaml";
    static final String EXTENSION = DIRECTORY + "runtimeconditions.extension.yaml";
    static final String FILE_MANIFEST = DIRECTORY + "runtimeconditions.file-manifest.yaml";
    private static final List<String> REQUIRED = List.of(BINDINGS, MODEL, EXTENSION);
    private static final String STAGE = "bindings";

    private static JsonSchema manifestSchema;
    private static JsonSchema modelSchema;

    private BindingLoader() {
    }

    public static boolean isBinding(ResourceRoot root) {
        return root.has(BINDINGS);
    }

    public static Binding load(ResourceRoot root) {
        String where = root.toString();
        byte[] listingBytes = root.read(FILE_MANIFEST);
        if (listingBytes == null) {
            throw new ProfilerException(STAGE, FILE_MANIFEST + " is missing", where);
        }
        JsonNode listing = Json.yaml(listingBytes, STAGE, where + ": " + FILE_MANIFEST);
        JsonNode files = listing.path("files");
        if (!files.isObject()) {
            throw new ProfilerException(STAGE, "the file manifest lists no files", where);
        }
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (String required : REQUIRED) {
            String key = required.substring(DIRECTORY.length());
            if (!files.has(key)) {
                throw new ProfilerException(STAGE, key + " is not listed in the file manifest", where);
            }
            byte[] data = root.read(required);
            if (data == null) {
                throw new ProfilerException(STAGE, required + " is missing", where);
            }
            contents.put(required, data);
        }
        files.fields().forEachRemaining(entry -> {
            byte[] data = contents.containsKey(DIRECTORY + entry.getKey()) ? contents.get(DIRECTORY + entry.getKey()) : root.read(DIRECTORY + entry.getKey());
            if (data != null && !Json.sha256(data).equals(entry.getValue().asText())) {
                throw new ProfilerException(STAGE, entry.getKey() + " differs from its file manifest digest", where);
            }
        });

        JsonNode manifest = Json.yaml(contents.get(BINDINGS), STAGE, where + ": " + BINDINGS);
        JsonNode model = Json.yaml(contents.get(MODEL), STAGE, where + ": " + MODEL);
        JsonNode extension = Json.yaml(contents.get(EXTENSION), STAGE, where + ": " + EXTENSION);
        check("binding manifest", manifestSchema(), manifest, where);
        check("binding model", modelSchema(), model, where);

        JsonNode core = model.path("coreProfileSchema");
        boolean current = Schemas.CORE_ID.equals(core.path("id").asText())
                && Schemas.CORE_VERSION.equals(core.path("version").asText())
                && Schemas.CORE_SEMANTIC_SHA256.equals(core.path("semanticSha256").asText());
        boolean approvedOlder = Schemas.CORE_0_2_0_ID.equals(core.path("id").asText())
                && Schemas.CORE_0_2_0_VERSION.equals(core.path("version").asText())
                && Schemas.CORE_0_2_0_SEMANTIC_SHA256.equals(core.path("semanticSha256").asText());
        if (!current && !approvedOlder) {
            throw new ProfilerException(STAGE, "the package was generated against core profile schema " + core.path("version").asText()
                    + " (" + core.path("semanticSha256").asText() + "); this profiler bundles " + Schemas.CORE_VERSION
                    + " (" + Schemas.CORE_SEMANTIC_SHA256 + ")", where);
        }

        JsonNode pkg = manifest.path("package");
        if (!"java".equals(pkg.path("language").asText())) {
            throw new ProfilerException(STAGE, "the package language is " + pkg.path("language").asText() + ", not java", where);
        }
        verifyJarIdentity(root, pkg, where);
        if (!manifest.path("model").path("semanticSha256").asText().equals(model.path("metadata").path("semanticSha256").asText())) {
            throw new ProfilerException(STAGE, "the manifest and the binding model disagree on the model digest", where);
        }
        JsonNode rootExtension = model.path("rootExtension");
        if (!manifest.path("extension").path("id").asText().equals(rootExtension.path("id").asText())
                || !manifest.path("extension").path("semanticSha256").asText().equals(rootExtension.path("semanticSha256").asText())) {
            throw new ProfilerException(STAGE, "the manifest and the binding model disagree on the root extension", where);
        }
        if (!extensionIdMatches(extension.path("metadata").path("id").asText(), manifest.path("extension").path("id").asText())) {
            throw new ProfilerException(STAGE, "the extension definition id differs from the manifest extension id", where);
        }

        List<Binding.Declaration> declarations = new ArrayList<>();
        for (JsonNode item : manifest.path("declarations")) {
            declarations.add(new Binding.Declaration(item.path("sourceName").asText(), item.path("function").asText(),
                    item.path("functionClass").asText(), item.path("owner").asText()));
        }
        List<JsonNode> roots = new ArrayList<>();
        manifest.path("rootBindings").forEach(roots::add);
        Map<String, JsonNode> types = new LinkedHashMap<>();
        for (JsonNode item : manifest.path("types")) {
            types.put(item.path("nativeName").asText(), item);
        }
        return new Binding(root, pkg.path("name").asText(), pkg.path("coordinate").asText(), pkg.path("version").asText(),
                manifest.path("extension").path("id").asText(),
                rootExtension.has("version") ? rootExtension.path("version").asText() : null,
                manifest.path("extension").path("semanticSha256").asText(), declarations, roots, types, model);
    }

    /** A definition id may be written with or without the trailing definition file name; both name one release. */
    static boolean extensionIdMatches(String definitionId, String manifestId) {
        return definitionId.equals(manifestId) || manifestId.startsWith(definitionId) && manifestId.endsWith("/runtimeconditions.extension.yaml");
    }

    private static void verifyJarIdentity(ResourceRoot root, JsonNode pkg, String where) {
        if (!root.isJar()) {
            return;
        }
        String coordinate = pkg.path("coordinate").asText();
        int colon = coordinate.indexOf(':');
        if (colon < 0) {
            throw new ProfilerException(STAGE, "the package coordinate " + coordinate + " is not groupId:artifactId", where);
        }
        byte[] properties = root.read("META-INF/maven/" + coordinate.substring(0, colon) + "/" + coordinate.substring(colon + 1) + "/pom.properties");
        if (properties == null) {
            return;
        }
        java.util.Properties parsed = new java.util.Properties();
        try {
            parsed.load(new java.io.StringReader(new String(properties, StandardCharsets.ISO_8859_1)));
        } catch (java.io.IOException e) {
            throw new ProfilerException(STAGE, "cannot read the jar's pom.properties: " + e.getMessage(), where);
        }
        if (!coordinate.equals(parsed.getProperty("groupId") + ":" + parsed.getProperty("artifactId"))
                || !pkg.path("version").asText().equals(parsed.getProperty("version"))) {
            throw new ProfilerException(STAGE, "the manifest package identity differs from the jar's Maven coordinates", where);
        }
    }

    private static void check(String name, JsonSchema schema, JsonNode document, String where) {
        List<String> problems = Schemas.problems(schema, document);
        if (!problems.isEmpty()) {
            throw new ProfilerException(STAGE, "the " + name + " does not match its schema: " + String.join("; ", problems), where);
        }
    }

    private static synchronized JsonSchema manifestSchema() {
        if (manifestSchema == null) {
            manifestSchema = Schemas.compile(Schemas.load("runtimeconditions.binding-manifest.schema.yaml"));
        }
        return manifestSchema;
    }

    private static synchronized JsonSchema modelSchema() {
        if (modelSchema == null) {
            modelSchema = Schemas.compile(Schemas.load("runtimeconditions.binding-model.schema.yaml"));
        }
        return modelSchema;
    }
}
