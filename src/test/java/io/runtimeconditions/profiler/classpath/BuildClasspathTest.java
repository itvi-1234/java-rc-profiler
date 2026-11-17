package io.runtimeconditions.profiler.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.runtimeconditions.profiler.command.CommandResult;
import io.runtimeconditions.profiler.command.CommandRunner;
import io.runtimeconditions.profiler.core.ProfilerException;
import io.runtimeconditions.profiler.project.BuildTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildClasspathTest {
    private static final class Fake implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        final int exit;
        final String classpath;

        Fake(int exit, String classpath) {
            this.exit = exit;
            this.classpath = classpath;
        }

        @Override
        public CommandResult run(List<String> command, Path workingDirectory) throws IOException {
            commands.add(command);
            for (String part : command) {
                if (part.startsWith("-Dmdep.outputFile=")) {
                    Files.writeString(Path.of(part.substring("-Dmdep.outputFile=".length())), classpath);
                }
            }
            return new CommandResult(exit, command.contains("--init-script") ? "noise\nRCP_CLASSPATH=" + classpath + "\n" : "", exit == 0 ? "" : "boom");
        }
    }

    @Test
    void theBuildToolIsDetectedFromTheProjectFiles(@TempDir Path temp) throws IOException {
        assertEquals(BuildTool.SOURCE_ONLY, BuildClasspath.detect(temp));
        Files.writeString(temp.resolve("build.gradle.kts"), "");
        assertEquals(BuildTool.GRADLE, BuildClasspath.detect(temp));
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        assertEquals(BuildTool.MAVEN, BuildClasspath.detect(temp));
    }

    @Test
    void mavenIsAskedForTheRuntimeClasspath(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path jar = Files.createFile(temp.resolve("a.jar"));
        Fake fake = new Fake(0, jar.toString());
        List<Path> entries = BuildClasspath.resolve(temp, fake);
        assertTrue(entries.contains(jar), entries.toString());
        assertTrue(fake.commands.get(0).contains("dependency:build-classpath"), fake.commands.toString());
        assertTrue(fake.commands.get(0).contains("-DincludeScope=runtime"), fake.commands.toString());
    }

    @Test
    void gradleIsAskedThroughAnInitScript(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("build.gradle"), "");
        Path jar = Files.createFile(temp.resolve("a.jar"));
        Fake fake = new Fake(0, jar.toString());
        assertTrue(BuildClasspath.resolve(temp, fake).contains(jar));
        assertTrue(fake.commands.get(0).contains("--init-script"), fake.commands.toString());
    }

    @Test
    void aFailingBuildToolIsReportedWithItsOutput(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        ProfilerException error = assertThrows(ProfilerException.class, () -> BuildClasspath.resolve(temp, new Fake(1, "")));
        assertEquals("classpath", error.stage());
        assertTrue(error.getMessage().contains("exit code 1") && error.getMessage().contains("boom"), error.getMessage());
    }

    @Test
    void aProjectWithoutABuildFileCannotResolveABuildClasspath(@TempDir Path temp) {
        ProfilerException error = assertThrows(ProfilerException.class, () -> BuildClasspath.resolve(temp, new Fake(0, "")));
        assertTrue(error.getMessage().contains("needs a pom.xml or a Gradle build"), error.getMessage());
    }
}
