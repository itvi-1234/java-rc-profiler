package w;

import example.conformance.objectalternatives.Bindings;
import example.conformance.objectalternatives.Configuration;
import example.conformance.objectalternatives.Process;

final class W {
    static final Object BY_COMMAND = Bindings.deployment(new Process(), new Configuration("run", null));
    static final Object BY_IMAGE = Bindings.deployment(new Process(), new Configuration(null, "app:1"));
}
