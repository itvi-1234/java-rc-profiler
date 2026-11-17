package app;

import io.runtimeconditions.bindings.commonintegrations.Bindings;
import io.runtimeconditions.bindings.commonintegrations.KeyValue;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfiguration;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnv;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnvItem;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnvItemProperty;
import java.util.List;

public final class App {
    static void declare() {
        final KeyValueConfigurationEnvItem sessionUrl =
                new KeyValueConfigurationEnvItem("REDIS_URL", KeyValueConfigurationEnvItemProperty.SCHEME, null, true);
        Bindings.cache(
                new KeyValue(Config.ENGINE),
                new KeyValueConfiguration(
                        null,
                        new KeyValueConfigurationEnv(
                                List.of(sessionUrl, new KeyValueConfigurationEnvItem("REDIS_PORT", KeyValueConfigurationEnvItemProperty.PORT, null, null)))));
    }
}
