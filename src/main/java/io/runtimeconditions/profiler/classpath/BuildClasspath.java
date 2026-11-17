package io.runtimeconditions.profiler.classpath;

import io.runtimeconditions.profiler.command.CommandRunner;
import io.runtimeconditions.profiler.command.DefaultCommandRunner;
import io.runtimeconditions.profiler.core.ProfilerException;
import io.runtimeconditions.profiler.project.BuildTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks the project's own build tool for its runtime classpath. This runs Maven or Gradle, and so runs the
 * project's build, which is why it is only done when the user asks for it with --resolve-build-classpath.
 */
public final class BuildClasspath {
    private BuildClasspath() {
    }

    public static BuildTool detect(Path project) {
        if (Files.isRegularFile(project.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        for (String name : new String[] {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}) {
            if (Files.isRegularFile(project.resolve(name))) {
                return BuildTool.GRADLE;
            }
        }
        return BuildTool.SOURCE_ONLY;
    }

    public static List<Path> resolve(Path project) {
        return resolve(project, new DefaultCommandRunner());
    }

    static List<Path> resolve(Path project, CommandRunner runner) {
        BuildTool tool = detect(project);
        if (tool == BuildTool.SOURCE_ONLY) {
            throw new ProfilerException("classpath", "--resolve-build-classpath needs a pom.xml or a Gradle build in the project", project.toString());
        }
        try {
            return new ArrayList<>(new BuildToolClasspathResolver(runner).resolve(project, tool, List.of()));
        } catch (IOException e) {
            throw new ProfilerException("classpath", e.getMessage(), project.toString());
        }
    }
}
