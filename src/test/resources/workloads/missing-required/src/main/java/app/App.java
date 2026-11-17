package app;

import io.runtimeconditions.bindings.commonintegrations.Bindings;
import io.runtimeconditions.bindings.commonintegrations.KeyValue;
import io.runtimeconditions.bindings.commonintegrations.KeyValueEngine;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfiguration;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnv;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnvItem;
import java.util.List;

public final class App {
    static final Object SESSIONS = Bindings.cache(
            new KeyValue(KeyValueEngine.REDIS),
            new KeyValueConfiguration(null, new KeyValueConfigurationEnv(List.of(new KeyValueConfigurationEnvItem("REDIS_URL", null, null, null)))));
}
