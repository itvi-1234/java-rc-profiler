package app;

import io.runtimeconditions.bindings.commonintegrations.Bindings;
import io.runtimeconditions.bindings.commonintegrations.KeyValue;
import io.runtimeconditions.bindings.commonintegrations.KeyValueEngine;

public final class App {
    static final Object RECORDS = Bindings.datastore(new KeyValue(KeyValueEngine.REDIS));
}
