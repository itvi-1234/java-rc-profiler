package w;

import example.conformance.transitiveclosure.Command;
import example.conformance.transitiveclosureleaf.Bindings;
import example.conformance.transitiveclosuremiddle.Process;

final class W {
    // The leaf declares the worker kind, the middle declares its interface, and the root adds a field.
    static final Object WORKER = Bindings.worker(new Process(), new Command("run"));
}
