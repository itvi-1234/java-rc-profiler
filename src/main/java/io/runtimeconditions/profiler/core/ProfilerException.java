package io.runtimeconditions.profiler.core;

/** A failure the profiler reports to the user: the stage it happened in, a message, and where. */
public final class ProfilerException extends RuntimeException {
    private final String stage;
    private final String location;

    public ProfilerException(String stage, String message, String location) {
        super(message);
        this.stage = stage;
        this.location = location;
    }

    public ProfilerException(String stage, String message) {
        this(stage, message, null);
    }

    public String stage() {
        return stage;
    }

    public String location() {
        return location;
    }

    @Override
    public String toString() {
        return "error: " + stage + ": " + (location == null ? "" : location + ": ") + getMessage();
    }
}
