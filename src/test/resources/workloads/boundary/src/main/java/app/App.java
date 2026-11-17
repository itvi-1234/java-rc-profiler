package app;

import io.runtimeconditions.bindings.commonintegrations.Bindings;
import io.runtimeconditions.bindings.commonintegrations.KeyValue;
import io.runtimeconditions.bindings.commonintegrations.KeyValueEngine;

public final class App {
    static {
        if (true) {
            throw new IllegalStateException("the workload must never be executed");
        }
    }

    static final Object SESSIONS = Bindings.cache(new KeyValue(KeyValueEngine.MEMCACHED));
}
