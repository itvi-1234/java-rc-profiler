package io.runtimeconditions.profiler;

import static io.runtimeconditions.profiler.TestSupport.packages;
import static io.runtimeconditions.profiler.TestSupport.generate;
import static io.runtimeconditions.profiler.TestSupport.invoke;
import static io.runtimeconditions.profiler.TestSupport.workload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runtimeconditions.profiler.TestSupport.Result;
import io.runtimeconditions.profiler.profile.ProfileBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {
    private static final String OWNED = packages("owned-kind-interface");

    @Test
    void theCommandLineRejectsBadArgumentsAndReportsItsVersion() throws IOException {
        assertEquals(1, invoke("profile").code());
        assertEquals(1, invoke("frobnicate").code());
        assertEquals(1, invoke("generate", "--project", ".").code());
        assertTrue(invoke("generate", "--project", ".", "--name", "x").err().contains("--workload-uri is required"));
        assertTrue(invoke("generate", "--bogus").err().contains("unexpected argument --bogus"));
        assertTrue(invoke("verify-bindings", "--project").err().contains("--project needs a value"));
        Result version = invoke("--version");
        assertEquals(0, version.code());
        assertFalse(version.out().isBlank());
        JsonNode json = new ObjectMapper().readTree(invoke("--version", "--json").out());
        assertEquals("runtimeconditions-java-profiler", json.path("name").asText());
        assertEquals("0.4.0", json.path("coreSchemas").get(0).asText());
    }

    @Test
    void theProfilePrefixIsOptionalSoRcCanPassSubcommandsStraightThrough() {
        Result without = invoke("generate", "--project", workload("owned").toString(), "--classpath", OWNED, "--name", "owned",
                "--workload-uri", "https://services.example.com/owned");
        assertEquals(0, without.code(), without.err());
        assertEquals(generate(workload("owned"), OWNED, "owned").out(), without.out());
    }

    @Test
    void helpAndHPrintTheUsageAndSucceed() {
        for (String flag : new String[] {"--help", "-h"}) {
            Result result = invoke(flag);
            assertEquals(0, result.code());
            assertTrue(result.out().startsWith("usage: runtimeconditions-java-profiler"), result.out());
        }
        // A -h that is the value of a flag is not a request for help.
        Result value = invoke("profile", "generate", "--project", workload("owned").toString(), "--classpath", OWNED, "--name", "-h",
                "--workload-uri", "https://services.example.com/owned");
        assertEquals(0, value.code(), value.err());
    }

    @Test
    void jsonTurnsAFailureIntoOneStructuredErrorAndStillWritesNothing() throws IOException {
        Result result = generate(workload("dynamic"), OWNED, "dynamic", "--json");
        assertEquals(1, result.code());
        assertEquals("", result.out());
        JsonNode error = new ObjectMapper().readTree(result.err()).path("error");
        assertEquals("extract", error.path("stage").asText());
        assertEquals("this value cannot be determined statically", error.path("message").asText());
        assertEquals("src/main/java/w/W.java:12:61", error.path("location").asText());
    }

    @Test
    void verifyBindingsCanPrintJson() throws IOException {
        Result result = invoke("profile", "verify-bindings", "--project", workload("additive").toString(), "--classpath",
                packages("additive-field", "additive-field-dependency"), "--json");
        assertEquals(0, result.code(), result.err());
        JsonNode rows = new ObjectMapper().readTree(result.out()).path("bindings");
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).path("direct").asBoolean());
        assertEquals("example.conformance:additive-field", rows.get(0).path("package").asText());
    }

    @Test
    void aMissingProjectDirectoryIsAUsageError() {
        Result result = invoke("generate", "--project", "/does/not/exist", "--name", "x", "--workload-uri", "https://x.example");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("usage") && result.err().contains("does not exist"), result.err());
    }

    @Test
    void theExtensionReferenceIsWrittenAsShortUrlAndVersion() {
        assertEquals("https://runtimeconditions.io/extensions/common-integrations:v1alpha1", ProfileBuilder.extensionReference(
                "https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml", "v1alpha1"));
        assertEquals("https://example.com/ext:1.0", ProfileBuilder.extensionReference("https://example.com/ext", "1.0"));
        // An id that already ends with its version is written whole, not with the version twice.
        assertEquals("https://example.com/conformance/ext:1.0.0", ProfileBuilder.extensionReference("https://example.com/conformance/ext:1.0.0", "1.0.0"));
        assertEquals("urn:x:ext", ProfileBuilder.extensionReference("urn:x:ext", null));
        assertEquals("https://example.com/other/1/runtimeconditions.extension.yaml:2",
                ProfileBuilder.extensionReference("https://example.com/other/1/runtimeconditions.extension.yaml", "2"));
    }

    @Test
    void testSourcesAndBuildOutputDoNotContributeConditions(@TempDir Path temp) throws IOException {
        Path project = temp.resolve("project");
        TestSupport.copy(workload("owned"), project);
        for (String ignored : new String[] {"src/test/java/app", "target/classes", "build/gen", ".hidden"}) {
            Files.createDirectories(project.resolve(ignored));
            Files.writeString(project.resolve(ignored).resolve("Broken.java"), "this is not java");
        }
        Result result = generate(project, OWNED, "owned");
        assertEquals(0, result.code(), result.err());
        assertEquals(TestSupport.generate(workload("owned"), OWNED, "owned").out(), result.out());
    }

    @Test
    void aSyntaxErrorInTheWorkloadIsReportedWithItsLocation(@TempDir Path temp) throws IOException {
        Path project = temp.resolve("project");
        TestSupport.copy(workload("owned"), project);
        Files.writeString(project.resolve("src/main/java/w/Broken.java"), "package app;\nclass Broken { void f( }\n");
        Result result = generate(project, OWNED, "owned");
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("syntax error") && result.err().contains("src/main/java/w/Broken.java:2:"), result.err());
    }

    @Test
    void symbolicLinksAreNeverFollowedWhileScanning(@TempDir Path temp) throws IOException {
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("Outside.java"), "package out;\nclass Outside { Object x = io.runtimeconditions.bindings.commonintegrations.Bindings.cache(); }\n");
        Path project = temp.resolve("project");
        TestSupport.copy(workload("owned"), project);
        try {
            Files.createSymbolicLink(project.resolve("src/main/java/linked"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        Result result = generate(project, OWNED, "owned");
        assertEquals(0, result.code(), result.err());
        assertEquals(TestSupport.generate(workload("owned"), OWNED, "owned").out(), result.out());
    }
}
