package io.runtimeconditions.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The launcher picks the JDK the way Java tools do. Each JDK here is a script that says which one ran. */
class LauncherTest {
    private static final Path LAUNCHER = Path.of("distribution/rc-profiler-java").toAbsolutePath();

    @TempDir
    Path temp;

    private record Run(int code, String out, String err) {
    }

    @BeforeEach
    void onlyWhereThereIsAShell() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "the launcher is a POSIX shell script");
    }

    private Path jdk(String name) throws IOException {
        Path java = temp.resolve(name).resolve("bin/java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\necho \"ran:" + name + "\"\nfor a in \"$@\"; do echo \"arg:$a\"; done\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        return java.getParent().getParent();
    }

    /** A directory laid out like an installed package: the jar, and optionally a bundled runtime. */
    private Path app(boolean bundled) throws IOException {
        Path app = temp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("runtimeconditions-java-profiler.jar"), "not a real jar");
        if (bundled) {
            Path java = jdk("app/runtime").resolve("bin/java");
            assertTrue(Files.isExecutable(java));
        }
        return app;
    }

    private Run run(Path script, Map<String, String> environment, String... args) throws Exception {
        // PATH holds only the tools the script needs, so the machine's own java cannot be picked up by accident.
        Path tools = temp.resolve("tools");
        Files.createDirectories(tools);
        for (String tool : new String[] {"dirname", "readlink"}) {
            Path link = tools.resolve(tool);
            if (!Files.exists(link)) {
                Path real = Path.of("/usr/bin", tool);
                Files.createSymbolicLink(link, Files.exists(real) ? real : Path.of("/bin", tool));
            }
        }
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("/bin/sh", script.toString()));
        command.addAll(java.util.List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", environment.getOrDefault("PATH", tools.toString()));
        environment.forEach((key, value) -> {
            if (!key.equals("PATH")) {
                env.put(key, value);
            }
        });
        Path out = temp.resolve("out.txt");
        Path err = temp.resolve("err.txt");
        Process process = builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        int code = process.waitFor();
        return new Run(code, Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    private Map<String, String> with(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void rcJavaHomeWinsOverJavaHome() throws Exception {
        Path app = app(false);
        Run run = run(LAUNCHER, with("RC_JAVA_HOME", jdk("rc").toString(), "JAVA_HOME", jdk("standard").toString(),
                "RC_JAVA_PROFILER_HOME", app.toString()));
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().startsWith("ran:rc\n"), run.out());
    }

    @Test
    void javaHomeIsUsedWhenThereIsNoOverride() throws Exception {
        Path app = app(true);
        Run run = run(LAUNCHER, with("JAVA_HOME", jdk("standard").toString(), "RC_JAVA_PROFILER_HOME", app.toString()));
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().startsWith("ran:standard\n"), run.out());
    }

    @Test
    void theBundledRuntimeIsUsedWhenNothingIsSet() throws Exception {
        Path app = app(true);
        Run run = run(LAUNCHER, with("RC_JAVA_PROFILER_HOME", app.toString()));
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().startsWith("ran:app/runtime\n"), run.out());
    }

    @Test
    void javaOnPathIsTheLastResort() throws Exception {
        Path app = app(false);
        Path onPath = jdk("pathjdk");
        Run run = run(LAUNCHER, with("RC_JAVA_PROFILER_HOME", app.toString(), "PATH", onPath.resolve("bin") + ":" + temp.resolve("tools")));
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().startsWith("ran:pathjdk\n"), run.out());
    }

    @Test
    void aVariableThatNamesNoJdkIsAnErrorAndNeverFallsBack() throws Exception {
        Path app = app(true);
        Run override = run(LAUNCHER, with("RC_JAVA_HOME", temp.resolve("missing").toString(), "JAVA_HOME", jdk("standard").toString(),
                "RC_JAVA_PROFILER_HOME", app.toString()));
        assertEquals(1, override.code());
        assertEquals("", override.out());
        assertTrue(override.err().contains("RC_JAVA_HOME is " + temp.resolve("missing")), override.err());

        Run standard = run(LAUNCHER, with("JAVA_HOME", temp.resolve("missing").toString(), "RC_JAVA_PROFILER_HOME", app.toString()));
        assertEquals(1, standard.code());
        assertEquals("", standard.out());
        assertTrue(standard.err().contains("JAVA_HOME is " + temp.resolve("missing")), standard.err());
    }

    @Test
    void aJdkOlderThan17IsRefusedByName() throws Exception {
        Path app = app(false);
        for (String[] item : new String[][] {{"11.0.21", "11"}, {"1.8.0_392", "8"}, {"16", "16"}}) {
            Path old = jdk("old" + item[1]);
            Files.writeString(old.resolve("release"), "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"" + item[0] + "\"\n");
            Run run = run(LAUNCHER, with("JAVA_HOME", old.toString(), "RC_JAVA_PROFILER_HOME", app.toString()));
            assertEquals(1, run.code(), item[0]);
            assertEquals("", run.out());
            assertTrue(run.err().contains("which is JDK " + item[1] + "; the profiler needs JDK 17 or newer"), run.err());
        }
    }

    @Test
    void aJdkThatIs17OrNewerOrRecordsNoVersionIsAccepted() throws Exception {
        Path app = app(false);
        Path current = jdk("current");
        Files.writeString(current.resolve("release"), "JAVA_VERSION=\"21.0.13\"\n");
        assertEquals(0, run(LAUNCHER, with("JAVA_HOME", current.toString(), "RC_JAVA_PROFILER_HOME", app.toString())).code());
        Path seventeen = jdk("seventeen");
        Files.writeString(seventeen.resolve("release"), "JAVA_VERSION=\"17\"\n");
        assertEquals(0, run(LAUNCHER, with("JAVA_HOME", seventeen.toString(), "RC_JAVA_PROFILER_HOME", app.toString())).code());
        // No release file: it cannot be checked, so it is trusted.
        assertEquals(0, run(LAUNCHER, with("JAVA_HOME", jdk("unrecorded").toString(), "RC_JAVA_PROFILER_HOME", app.toString())).code());
    }

    @Test
    void withNoJdkAnywhereItSaysSo() throws Exception {
        Run run = run(LAUNCHER, with("RC_JAVA_PROFILER_HOME", app(false).toString()));
        assertEquals(1, run.code());
        assertTrue(run.err().contains("no JDK found: set RC_JAVA_HOME or JAVA_HOME, or put java on PATH"), run.err());
    }

    @Test
    void theArgumentsReachTheProfilerUnchangedAfterTheJar() throws Exception {
        Path app = app(true);
        Run run = run(LAUNCHER, with("RC_JAVA_PROFILER_HOME", app.toString()), "profile", "generate", "--name", "two words", "--out", "");
        assertEquals(0, run.code(), run.err());
        assertEquals("ran:app/runtime\narg:-jar\narg:" + app.toRealPath() + "/runtimeconditions-java-profiler.jar\narg:profile\narg:generate\narg:--name\narg:two words\narg:--out\narg:\n", run.out());
    }

    @Test
    void anExplicitJarWins() throws Exception {
        Path jar = Files.writeString(temp.resolve("other.jar"), "x");
        Run run = run(LAUNCHER, with("RC_JAVA_PROFILER_JAR", jar.toString(), "JAVA_HOME", jdk("standard").toString()));
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().contains("arg:" + jar + "\n"), run.out());
    }

    @Test
    void aMissingJarIsReported() throws Exception {
        Run run = run(LAUNCHER, with("JAVA_HOME", jdk("standard").toString()));
        assertEquals(1, run.code());
        assertTrue(run.err().contains("cannot find runtimeconditions-java-profiler.jar"), run.err());
        Run explicit = run(LAUNCHER, with("RC_JAVA_PROFILER_JAR", temp.resolve("nope.jar").toString(), "JAVA_HOME", jdk("standard").toString()));
        assertEquals(1, explicit.code());
        assertTrue(explicit.err().contains("does not exist"), explicit.err());
    }

    @Test
    void installedAsALinkItFindsTheJarOfThePackage() throws Exception {
        // <prefix>/bin/rc-profiler-java -> a link; <prefix>/lib/rc-profiler-java holds the jar and the bundled runtime.
        Path prefix = temp.resolve("prefix");
        Path library = prefix.resolve("lib/rc-profiler-java");
        Files.createDirectories(library);
        Files.createDirectories(prefix.resolve("bin"));
        Files.copy(LAUNCHER, library.resolve("rc-profiler-java"));
        Files.setPosixFilePermissions(library.resolve("rc-profiler-java"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(library.resolve("runtimeconditions-java-profiler.jar"), "x");
        Path java = library.resolve("runtime/bin/java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\necho \"ran:bundled\"\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path link = prefix.resolve("bin/rc-profiler-java");
        Files.createSymbolicLink(link, Path.of("../lib/rc-profiler-java/rc-profiler-java"));
        Run run = run(link, new HashMap<>());
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().startsWith("ran:bundled\n"), run.out());
    }
}
