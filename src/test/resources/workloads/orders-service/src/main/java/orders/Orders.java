package orders;

import io.runtimeconditions.bindings.commonintegrations.Bindings;
import io.runtimeconditions.bindings.commonintegrations.Http;
import io.runtimeconditions.bindings.commonintegrations.HttpOperations;
import io.runtimeconditions.bindings.commonintegrations.HttpOperationsItem;
import io.runtimeconditions.bindings.commonintegrations.KeyValue;
import io.runtimeconditions.bindings.commonintegrations.KeyValueEngine;
import io.runtimeconditions.bindings.commonintegrations.Method;
import io.runtimeconditions.bindings.commonintegrations.Relational;
import io.runtimeconditions.bindings.commonintegrations.RelationalEngine;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfiguration;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnv;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnvItem;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnvItemProperty;
import java.util.List;

/** An orders service: an HTTP API, a relational database, and a Redis session cache configured from the environment. */
public final class Orders {
    private static final String ORDERS = "/orders";

    static void declare() {
        Bindings.api(
                new Http(
                        new HttpOperations(
                                List.of(
                                        new HttpOperationsItem(Method.GET, ORDERS, null, null),
                                        new HttpOperationsItem(Method.POST, ORDERS, null, null),
                                        new HttpOperationsItem(Method.DELETE, ORDERS + "/{id}", null, null))),
                        null));
        Bindings.datastore(new Relational(RelationalEngine.POSTGRES));
        Bindings.cache(
                new KeyValue(KeyValueEngine.REDIS),
                new KeyValueConfiguration(
                        null,
                        new KeyValueConfigurationEnv(
                                List.of(new KeyValueConfigurationEnvItem("REDIS_URL", KeyValueConfigurationEnvItemProperty.URL, true, true)))));
    }
}
