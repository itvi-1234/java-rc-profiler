package app;

import io.runtimeconditions.bindings.commonintegrations.Bindings;

public final class App {
    static final Object SESSIONS = Bindings.cache(new java.util.ArrayList<>());
}
