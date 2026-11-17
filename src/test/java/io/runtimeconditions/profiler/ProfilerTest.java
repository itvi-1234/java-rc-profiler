package io.runtimeconditions.profiler;

import static io.runtimeconditions.profiler.TestSupport.both;
import static io.runtimeconditions.profiler.TestSupport.expected;
import static io.runtimeconditions.profiler.TestSupport.generate;
import static io.runtimeconditions.profiler.TestSupport.pkg;
import static io.runtimeconditions.profiler.TestSupport.workload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.runtimeconditions.profiler.TestSupport.Result;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfilerTest {
    @Test
    void theCacheWorkloadBecomesTheReviewedProfile() throws IOException {
        Result result = generate(workload("cache"), both(), "cache", "--workload-version", "1.0.0");
        assertEquals("", result.err());
        assertEquals(0, result.code());
        assertEquals(expected("cache"), result.out());
    }

    @Test
    void theApiWorkloadWithAConstantPathAMapAndAUnionBecomesTheReviewedProfile() throws IOException {
        Result result = generate(workload("api"), both(), "api");
        assertEquals("", result.err());
        assertEquals(0, result.code());
        assertEquals(expected("api"), result.out());
    }

    @Test
    void aProfileIsIdenticalOnEveryRun() {
        assertEquals(generate(workload("api"), both(), "api").out(), generate(workload("api"), both(), "api").out());
    }

    @Test
    void theWorkloadIsAnalyzedNeverExecuted() {
        Result result = generate(workload("boundary"), both(), "boundary");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("engine: memcached"), result.out());
    }

    @Test
    void verifyBindingsListsTheClosureAndMarksWhatTheWorkloadImports() {
        Result direct = TestSupport.invoke("profile", "verify-bindings", "--project", workload("cache").toString(), "--classpath", both());
        assertEquals(0, direct.code(), direct.err());
        assertTrue(direct.out().contains("runtimeconditions-common-integrations@0.1.0 https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml\n"), direct.out());
        assertTrue(direct.out().contains("runtimeconditions-env-configuration@0.1.0 https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml\n"), direct.out());
        assertFalse(direct.out().contains("(dependency)"), direct.out());

        Result viaDependency = TestSupport.invoke("profile", "verify-bindings", "--project", workload("config-only").toString(), "--classpath", both());
        assertEquals(0, viaDependency.code(), viaDependency.err());
        assertTrue(viaDependency.out().contains("runtimeconditions-common-integrations@0.1.0 https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml (dependency)\n"), viaDependency.out());
        assertTrue(viaDependency.out().contains("runtimeconditions-env-configuration@0.1.0 https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml\n"), viaDependency.out());
    }

    @Test
    void outWritesTheProfileAndAFailedRunLeavesAnExistingFileAlone(@TempDir java.nio.file.Path temp) throws IOException {
        java.nio.file.Path out = temp.resolve("profile.yaml");
        assertEquals(0, generate(workload("api"), both(), "api", "--out", out.toString()).code());
        assertEquals(expected("api"), java.nio.file.Files.readString(out));
        Result failed = generate(workload("dynamic"), both(), "dynamic", "--out", out.toString());
        assertEquals(1, failed.code());
        assertEquals(expected("api"), java.nio.file.Files.readString(out));
        assertEquals("", failed.out());
    }

    @Test
    void aWorkloadWithNoBindingOnTheClasspathFindsNoDeclaration() {
        Result result = generate(workload("api"), pkg("env-configuration").toString(), "api");
        assertEquals(1, result.code());
        assertEquals("", result.out());
    }
}
