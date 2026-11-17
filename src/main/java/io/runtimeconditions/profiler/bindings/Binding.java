package io.runtimeconditions.profiler.bindings;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** One verified generated binding package. */
public record Binding(
        ResourceRoot root,
        String javaPackage,
        String coordinate,
        String version,
        String extensionId,
        String extensionVersion,
        String extensionSha256,
        List<Declaration> declarations,
        List<JsonNode> rootBindings,
        Map<String, JsonNode> types,
        JsonNode model) {

    /** A generated declaration function such as Bindings.api. */
    public record Declaration(String sourceName, String function, String functionClass, String owner) {
    }

    public String describe() {
        return coordinate + "@" + version;
    }
}
