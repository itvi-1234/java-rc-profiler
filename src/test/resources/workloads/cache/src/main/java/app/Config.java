package app;

import io.runtimeconditions.bindings.commonintegrations.KeyValueEngine;

final class Config {
    private Config() {
    }

    static final KeyValueEngine ENGINE = KeyValueEngine.REDIS;
}
