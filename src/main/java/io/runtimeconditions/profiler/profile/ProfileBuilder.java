package io.runtimeconditions.profiler.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import io.runtimeconditions.profiler.bindings.Binding;
import io.runtimeconditions.profiler.bindings.Catalog;
import io.runtimeconditions.profiler.core.Json;
import io.runtimeconditions.profiler.core.ProfilerException;
import io.runtimeconditions.profiler.core.Schemas;
import io.runtimeconditions.profiler.extract.Extractor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Assembles a profile from extracted conditions and validates it before it is written. */
public final class ProfileBuilder {
    private ProfileBuilder() {
    }

    public record Options(String name, String workloadUri, String workloadVersion) {
    }

    public static ObjectNode build(Extractor.Extraction extraction, Catalog catalog, Options options) {
        if (extraction.conditions().isEmpty()) {
            throw new ProfilerException("extract", "no declaration from a binding package on the classpath was found in the project");
        }
        ObjectNode workload = Json.object();
        workload.put("uri", options.workloadUri());
        if (options.workloadVersion() != null && !options.workloadVersion().isEmpty()) {
            workload.put("version", options.workloadVersion());
        }
        ObjectNode profile = Json.object();
        profile.put("apiVersion", "runtimeconditions.io/v1alpha1");
        profile.put("kind", "RuntimeConditionsProfile");
        profile.putObject("metadata").put("name", options.name());
        profile.set("workload", workload);
        ArrayNode extensions = profile.putArray("extensions");
        extensionReferences(extraction.used()).forEach(extensions::add);
        ArrayNode conditions = profile.putArray("conditions");
        extraction.conditions().forEach(conditions::add);
        validate(profile, catalog.closure(extraction.used()));
        return profile;
    }

    /** Writes an extension as short-url:version when its id ends with /version/runtimeconditions.extension.yaml. */
    public static String extensionReference(String id, String version) {
        if (version == null || version.isEmpty()) {
            return id;
        }
        String tail = "/" + version + "/runtimeconditions.extension.yaml";
        return id.startsWith("https://") && id.endsWith(tail) ? id.substring(0, id.length() - tail.length()) + ":" + version : id + ":" + version;
    }

    private static List<String> extensionReferences(List<Binding> used) {
        TreeSet<String> references = new TreeSet<>();
        for (Binding binding : used) {
            references.add(extensionReference(binding.extensionId(), binding.extensionVersion()));
        }
        return new ArrayList<>(references);
    }

    public static void validate(ObjectNode profile, List<Binding> closure) {
        JsonSchema core = Schemas.compile(Schemas.load("runtimeconditions.profile.schema.yaml"));
        List<String> problems = Schemas.problems(core, profile);
        if (!problems.isEmpty()) {
            throw new ProfilerException("schema", "the profile does not match the core profile schema: " + String.join("; ", problems));
        }
        Map<String, JsonNode> schemas = new LinkedHashMap<>();
        for (Binding binding : closure) {
            for (JsonNode item : binding.model().path("schemas")) {
                schemas.put(item.path("coordinate").asText(), item);
            }
        }
        Map<String, JsonSchema> compiled = new HashMap<>();
        JsonNode conditions = profile.path("conditions");
        for (int index = 0; index < conditions.size(); index++) {
            JsonNode condition = conditions.get(index);
            String type = condition.path("interface").path("type").asText();
            for (JsonNode item : schemas.values()) {
                if (!item.path("kind").asText("").equals(condition.path("kind").asText())
                        || item.has("interfaceType") && !item.path("interfaceType").asText().equals(type)) {
                    continue;
                }
                JsonSchema schema = compiled.computeIfAbsent(item.path("coordinate").asText(), key -> Schemas.compile(item.path("exact")));
                List<String> failures = Schemas.problems(schema, condition);
                if (!failures.isEmpty()) {
                    throw new ProfilerException("schema", "condition " + (index + 1) + " (" + condition.path("kind").asText()
                            + ") does not satisfy " + item.path("coordinate").asText() + ": " + String.join("; ", failures));
                }
            }
        }
    }
}
