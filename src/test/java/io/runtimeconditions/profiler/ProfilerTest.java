package io.runtimeconditions.profiler;

import static io.runtimeconditions.profiler.TestSupport.expected;
import static io.runtimeconditions.profiler.TestSupport.generate;
import static io.runtimeconditions.profiler.TestSupport.packages;
import static io.runtimeconditions.profiler.TestSupport.workload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.runtimeconditions.profiler.TestSupport.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfilerTest {
    private static final String OWNED = "owned-kind-interface";

    @Test
    void theWorkloadIsAnalyzedNeverExecuted() {
        Result result = generate(workload("boundary"), packages(OWNED), "boundary");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("region: eu"), result.out());
    }

    @Test
    void verifyBindingsListsTheClosureAndMarksWhatTheWorkloadImports() {
        Result direct = TestSupport.invoke("profile", "verify-bindings", "--project", workload("additive").toString(), "--classpath",
                packages("additive-field", "additive-field-dependency"));
        assertEquals(0, direct.code(), direct.err());
        assertEquals("""
                example.conformance:additive-field@1.0.0 https://runtimeconditions.io/conformance/additive-field:1.0.0
                example.conformance:additive-field-dependency@1.0.0 https://runtimeconditions.io/conformance/additive-field-base:1.0.0
                """, direct.out());

        Result viaDependency = TestSupport.invoke("profile", "verify-bindings", "--project", workload("config-only").toString(), "--classpath",
                packages("additive-field", "additive-field-dependency"));
        assertEquals(0, viaDependency.code(), viaDependency.err());
        assertEquals("""
                example.conformance:additive-field@1.0.0 https://runtimeconditions.io/conformance/additive-field:1.0.0
                example.conformance:additive-field-dependency@1.0.0 https://runtimeconditions.io/conformance/additive-field-base:1.0.0 (dependency)
                """, viaDependency.out());
    }

    @Test
    void theClosureFollowsDependenciesThroughAnExtensionThatDeclaresNothing(@TempDir Path temp) throws IOException {
        // root -> middle -> leaf; the workload imports only the root's type.
        Path project = temp.resolve("p");
        Files.createDirectories(project.resolve("src/main/java/t"));
        Files.writeString(project.resolve("src/main/java/t/T.java"),
                "package t;\nimport example.conformance.transitiveclosure.Command;\nfinal class T { static final Object C = new Command(\"run\"); }\n");
        Result result = TestSupport.invoke("profile", "verify-bindings", "--project", project.toString(), "--classpath",
                packages("transitive-closure", "transitive-closure-middle", "transitive-closure-leaf"));
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("transitive-closure@1.0.0 https://runtimeconditions.io/conformance/transitive-root:1.0.0\n"), result.out());
        assertTrue(result.out().contains("transitive-closure-middle@1.0.0 https://runtimeconditions.io/conformance/transitive-middle:1.0.0 (dependency)\n"), result.out());
        assertTrue(result.out().contains("transitive-closure-leaf@1.0.0 https://runtimeconditions.io/conformance/transitive-leaf:1.0.0 (dependency)\n"), result.out());
    }

    @Test
    void anInterfaceTypeWithNoSchemaHasNoGeneratedTypeSoTheDeclarationHasNoInterface() {
        // The middle extension of the transitive case declares the interface type process without a schema. The Java
        // emitter generates a type only for what a schema describes, so there is nothing to construct as its interface.
        Result result = generate(workload("transitive"), packages("transitive-closure", "transitive-closure-middle", "transitive-closure-leaf"), "transitive");
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("the worker declaration has no interface"), result.err());
    }

    @Test
    void outWritesTheProfileAndAFailedRunLeavesAnExistingFileAlone(@TempDir Path temp) throws IOException {
        Path out = temp.resolve("profile.yaml");
        assertEquals(0, generate(workload("owned"), packages(OWNED), "owned", "--out", out.toString()).code());
        assertEquals(expected("owned"), Files.readString(out));
        Result failed = generate(workload("dynamic"), packages(OWNED), "dynamic", "--out", out.toString());
        assertEquals(1, failed.code());
        assertEquals(expected("owned"), Files.readString(out));
        assertEquals("", failed.out());
    }

    @Test
    void aWorkloadWithNoBindingOnTheClasspathFindsNoDeclaration() {
        Result result = generate(workload("owned"), packages("recursive-reference"), "owned");
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertFalse(result.err().isBlank());
    }
}
