package w;

import example.conformance.transitiveclosure.Command;
import example.conformance.transitiveclosureleaf.Bindings;

final class W {
    static final Object WORKER = Bindings.worker(new Command("run"));
}
