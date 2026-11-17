package io.runtimeconditions.profiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class TestSupport {
    static final Path RESOURCES = Path.of("src/test/resources").toAbsolutePath();

    record Result(int code, String out, String err) {
    }

    private TestSupport() {
    }

    static Result invoke(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static Path pkg(String name) {
        return RESOURCES.resolve("packages").resolve(name);
    }

    static String classpath(Path... entries) {
        List<String> parts = new ArrayList<>();
        for (Path entry : entries) {
            parts.add(entry.toString());
        }
        return String.join(java.io.File.pathSeparator, parts);
    }

    static String both() {
        return classpath(pkg("common-integrations"), pkg("env-configuration"));
    }

    static Path workload(String name) {
        return RESOURCES.resolve("workloads").resolve(name);
    }

    static String expected(String name) throws IOException {
        return Files.readString(RESOURCES.resolve("expected").resolve(name + ".profile.yaml"));
    }

    static Result generate(Path project, String classpath, String name, String... extra) {
        List<String> args = new ArrayList<>(List.of("profile", "generate", "--project", project.toString(), "--classpath", classpath,
                "--name", name, "--workload-uri", "https://services.example.com/" + name));
        args.addAll(List.of(extra));
        return invoke(args.toArray(String[]::new));
    }

    /** Copies a directory tree, so a test can change it. */
    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path source : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }
}
