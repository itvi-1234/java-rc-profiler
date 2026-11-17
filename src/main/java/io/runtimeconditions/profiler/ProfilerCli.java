package io.runtimeconditions.profiler;

/** Entry point of the runtimeconditions-java-profiler executable jar. */
public final class ProfilerCli {
    private ProfilerCli() {
    }

    public static void main(String[] args) {
        System.exit(Cli.run(args, System.out, System.err));
    }
}
