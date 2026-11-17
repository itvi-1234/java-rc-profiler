package app;

import io.runtimeconditions.bindings.commonintegrations.Bindings;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfiguration;

public final class App {
    static final Object SESSIONS = Bindings.cache(new KeyValueConfiguration(null, null));
}
