package app;

import io.runtimeconditions.bindings.commonintegrations.Bindings;
import io.runtimeconditions.bindings.commonintegrations.KeyValue;
import io.runtimeconditions.bindings.commonintegrations.KeyValueEngine;

public final class App {
    static KeyValueEngine engine() {
        return KeyValueEngine.valueOf(System.getenv("ENGINE"));
    }

    static final Object SESSIONS = Bindings.cache(new KeyValue(engine()));
}
