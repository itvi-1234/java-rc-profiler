package io.runtimeconditions.profiler.profile;

import com.fasterxml.jackson.databind.JsonNode;
import io.runtimeconditions.profiler.core.Json;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/** Writes a profile as block-style YAML with a stable layout. */
public final class ProfileYaml {
    private ProfileYaml() {
    }

    public static String write(JsonNode profile) {
        DumpSettings settings = DumpSettings.builder()
                .setSchema(new CoreSchema())
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setIndent(2)
                .setIndicatorIndent(2)
                .setIndentWithIndicator(true)
                .setWidth(Integer.MAX_VALUE)
                .setSplitLines(false)
                .build();
        return new Dump(settings).dumpToString(Json.plain(profile));
    }
}
