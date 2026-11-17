package app;

import static io.runtimeconditions.bindings.commonintegrations.Bindings.api;

import io.runtimeconditions.bindings.commonintegrations.Http;
import io.runtimeconditions.bindings.commonintegrations.Method;
import io.runtimeconditions.bindings.commonintegrations.Operations;
import io.runtimeconditions.bindings.commonintegrations.OperationsItem;
import io.runtimeconditions.bindings.commonintegrations.RequestBodySchema;
import io.runtimeconditions.bindings.commonintegrations.RequestBodySchemaVariant2;
import io.runtimeconditions.bindings.commonintegrations.SimpleSchema;
import io.runtimeconditions.bindings.commonintegrations.SimpleSchemaVariant1;
import io.runtimeconditions.bindings.commonintegrations.SimpleSchemaVariant3;
import java.util.List;
import java.util.Map;

public final class App {
    private static final String BASE = "/orders";

    private static final SimpleSchema TEXT = new SimpleSchema.Variant3(SimpleSchemaVariant3.STRING);

    static final Object ORDERS = api(
            new Http(
                    new Operations(
                            List.of(
                                    new OperationsItem(Method.GET, BASE, null, null),
                                    new OperationsItem(
                                            Method.POST,
                                            BASE + "/new",
                                            new RequestBodySchema.Variant2(
                                                    new RequestBodySchemaVariant2(
                                                            Map.of(
                                                                    "name", TEXT,
                                                                    "tags", new SimpleSchema.Variant1(new SimpleSchemaVariant1(List.of(TEXT)))))),
                                            null))),
                    null));
}
