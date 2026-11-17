package io.runtimeconditions.profiler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.runtimeconditions.profiler.bindings.Binding;
import io.runtimeconditions.profiler.bindings.Catalog;
import io.runtimeconditions.profiler.classpath.BuildClasspath;
import io.runtimeconditions.profiler.core.Json;
import io.runtimeconditions.profiler.core.ProfilerException;
import io.runtimeconditions.profiler.core.Schemas;
import io.runtimeconditions.profiler.extract.Extractor;
import io.runtimeconditions.profiler.profile.ProfileBuilder;
import io.runtimeconditions.profiler.profile.ProfileYaml;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The command line: profile generate, profile verify-bindings, and --version. */
public final class Cli {
    static final String USAGE = """
            usage: runtimeconditions-java-profiler [profile] verify-bindings --project DIR [--classpath PATH] [--resolve-build-classpath] [--json]
                   runtimeconditions-java-profiler [profile] generate --project DIR --name NAME --workload-uri URI [--workload-version VERSION] [--classpath PATH] [--resolve-build-classpath] [--out FILE] [--json]
                   runtimeconditions-java-profiler --version [--json]""";

    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "verify-bindings", Set.of("--project", "--classpath", "--resolve-build-classpath", "--json"),
            "generate", Set.of("--project", "--name", "--workload-uri", "--workload-version", "--classpath", "--resolve-build-classpath", "--out", "--json"));
    private static final Set<String> VALUE_FLAGS = Set.of("--project", "--name", "--workload-uri", "--workload-version", "--classpath", "--out");

    private Cli() {
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        boolean json = Arrays.asList(args).contains("--json");
        try {
            if (args.length > 0 && args[0].equals("--version")) {
                String version = version();
                out.print(json ? Json.MAPPER.writeValueAsString(Json.object().put("name", "runtimeconditions-java-profiler")
                        .put("version", version).set("coreSchemas", Json.array().add(Schemas.CORE_VERSION))) + "\n" : version + "\n");
                return 0;
            }
            if (wantsHelp(args)) {
                out.println(USAGE);
                return 0;
            }
            List<String> rest = new ArrayList<>(Arrays.asList(args));
            if (!rest.isEmpty() && rest.get(0).equals("profile")) {
                rest.remove(0);
            }
            if (rest.isEmpty() || !OPTIONS.containsKey(rest.get(0))) {
                throw new ProfilerException("usage", USAGE);
            }
            String command = rest.remove(0);
            Map<String, String> values = parse(rest, OPTIONS.get(command));
            Path project = Path.of(required(values, "--project")).toAbsolutePath().normalize();
            if (command.equals("generate")) {
                required(values, "--name");
                required(values, "--workload-uri");
            }
            if (!Files.isDirectory(project)) {
                throw new ProfilerException("usage", "the project directory " + project + " does not exist");
            }
            List<Path> classpath = classpath(values.get("--classpath"));
            if (values.containsKey("--resolve-build-classpath")) {
                classpath.addAll(BuildClasspath.resolve(project));
            }
            Catalog catalog = Catalog.of(classpath);
            Extractor extractor = new Extractor(project, catalog);
            List<Path> files = Extractor.projectFiles(project);
            if (command.equals("verify-bindings")) {
                return verify(extractor, catalog, files, project, values.containsKey("--json"), out);
            }
            ObjectNode profile = ProfileBuilder.build(extractor.run(files), catalog,
                    new ProfileBuilder.Options(values.get("--name"), values.get("--workload-uri"), values.get("--workload-version")));
            String yaml = ProfileYaml.write(profile);
            String target = values.get("--out");
            if (target != null) {
                write(Path.of(target).toAbsolutePath().normalize(), yaml);
            } else {
                out.print(yaml);
            }
            return 0;
        } catch (ProfilerException e) {
            report(err, json, e.stage(), e.getMessage(), e.location(), e.toString());
            return 1;
        } catch (IOException | RuntimeException e) {
            report(err, json, "internal", String.valueOf(e.getMessage()), null, "error: " + e.getMessage());
            return 1;
        }
    }

    private static int verify(Extractor extractor, Catalog catalog, List<Path> files, Path project, boolean json, PrintStream out)
            throws IOException {
        List<Binding> direct = extractor.discover(files);
        List<Binding> closure = catalog.closure(direct);
        if (json) {
            ObjectNode root = Json.object();
            root.put("project", project.toString());
            ArrayNode rows = root.putArray("bindings");
            for (Binding binding : closure) {
                rows.addObject().put("package", binding.coordinate()).put("version", binding.version())
                        .put("extension", binding.extensionId()).put("direct", direct.contains(binding));
            }
            out.print(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        } else {
            for (Binding binding : closure) {
                out.print(binding.coordinate() + "@" + binding.version() + " " + binding.extensionId()
                        + (direct.contains(binding) ? "" : " (dependency)") + "\n");
            }
        }
        return 0;
    }

    private static void report(PrintStream err, boolean json, String stage, String message, String location, String text) {
        if (!json) {
            err.println(text);
            return;
        }
        ObjectNode root = Json.object();
        ObjectNode error = root.putObject("error");
        error.put("stage", stage).put("message", message);
        if (location != null) {
            error.put("location", location);
        }
        try {
            err.print(Json.MAPPER.writeValueAsString(root) + "\n");
        } catch (IOException e) {
            err.println(text);
        }
    }

    private static List<Path> classpath(String value) {
        List<Path> entries = new ArrayList<>();
        if (value != null) {
            for (String part : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!part.isBlank()) {
                    entries.add(Path.of(part.trim()).toAbsolutePath().normalize());
                }
            }
        }
        return entries;
    }

    private static boolean wantsHelp(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ((args[i].equals("--help") || args[i].equals("-h")) && !(i > 0 && VALUE_FLAGS.contains(args[i - 1]))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> parse(List<String> args, Set<String> allowed) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String flag = args.get(i);
            if (!allowed.contains(flag) || values.containsKey(flag)) {
                throw new ProfilerException("usage", "unexpected argument " + flag + "\n" + USAGE);
            }
            if (flag.equals("--json") || flag.equals("--resolve-build-classpath")) {
                values.put(flag, "true");
            } else {
                if (++i >= args.size()) {
                    throw new ProfilerException("usage", flag + " needs a value\n" + USAGE);
                }
                values.put(flag, args.get(i));
            }
        }
        return values;
    }

    private static String required(Map<String, String> values, String flag) {
        String value = values.get(flag);
        if (value == null || value.isEmpty()) {
            throw new ProfilerException("usage", flag + " is required\n" + USAGE);
        }
        return value;
    }

    /** Writes the profile through a temporary file, so a failure never leaves a partial output. */
    private static void write(Path target, String yaml) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        try {
            Files.write(temporary, yaml.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String version() {
        String version = Cli.class.getPackage().getImplementationVersion();
        return version == null ? "0.0.0-dev" : version;
    }
}
