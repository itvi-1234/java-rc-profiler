package forms;

import static forms.Paths.HEALTH;
import static io.runtimeconditions.bindings.commonintegrations.Method.GET;

import io.runtimeconditions.bindings.commonintegrations.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class Forms {
    static final String PREFIX = "/v" + 1;

    private static final SimpleSchema TEXT = new SimpleSchema.Variant3(SimpleSchemaVariant3.STRING);

    private final String instance = Paths.API + "/instance";

    void declare() {
        final String local = PREFIX + "/local";
        io.runtimeconditions.bindings.commonintegrations.Bindings.api(
                new Http(
                        new Operations(
                                Arrays.asList(
                                        new OperationsItem(GET, HEALTH, null, null),
                                        new OperationsItem(Method.PUT, (String) local, null, null),
                                        new OperationsItem(Method.PATCH, (instance), null, null),
                                        new OperationsItem(
                                                Method.POST,
                                                "/items",
                                                new RequestBodySchema.Variant2(
                                                        new RequestBodySchemaVariant2(Map.ofEntries(Map.entry("id", TEXT), Map.entry("note", TEXT)))),
                                                new ResponseSchema.Variant3(ResponseSchemaVariant3.INTEGER)))),
                        new Spec(Format.OPENAPI, "https://example.com/openapi.json", null)));
    }
}
