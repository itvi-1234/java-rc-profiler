package w;

import example.conformance.dependencyschemaonly.Bindings;
import example.conformance.dependencyschemaonly.Command;
import example.conformance.dependencyschemaonly.Process;

final class W {
    static final Object JOB = Bindings.job(new Process(), new Command("run"));
}
