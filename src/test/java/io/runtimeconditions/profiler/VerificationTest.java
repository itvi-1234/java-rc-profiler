package io.runtimeconditions.profiler;

import static io.runtimeconditions.profiler.TestSupport.classpath;
import static io.runtimeconditions.profiler.TestSupport.copy;
import static io.runtimeconditions.profiler.TestSupport.generate;
import static io.runtimeconditions.profiler.TestSupport.pkg;
import static io.runtimeconditions.profiler.TestSupport.workload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.runtimeconditions.profiler.TestSupport.Result;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the profiler checks about a binding package before it reads any source. */
class VerificationTest {
    private static final String OWNED = "owned-kind-interface";
    private static final String ADDON = "additive-field";
    private static final String BASE = "additive-field-dependency";

    @TempDir
    Path temp;

    private Path copyOf(String name) throws IOException {
        Path target = temp.resolve(name);
        copy(pkg(name), target);
        return target;
    }

    /** Rewrites one resource and, unless told not to, the file manifest entry that guards it. */
    private static void rewrite(Path pkg, String resource, UnaryOperator<String> change, boolean updateManifest) throws IOException {
        Path directory = pkg.resolve("runtimeconditions");
        Path file = directory.resolve(resource);
        String before = Files.readString(file);
        String text = change.apply(before);
        assertTrue(!text.equals(before), "the change did not alter " + resource);
        Files.writeString(file, text);
        if (updateManifest) {
            Path listing = directory.resolve("runtimeconditions.file-manifest.yaml");
            StringBuilder out = new StringBuilder();
            for (String line : Files.readString(listing).split("\n")) {
                out.append(line.trim().startsWith(resource + ":")
                        ? "    " + resource + ": " + sha256(text.getBytes(StandardCharsets.UTF_8)) : line).append('\n');
            }
            Files.writeString(listing, out.toString());
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String valueOf(Path pkg, String resource, String pattern) throws IOException {
        Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(Files.readString(pkg.resolve("runtimeconditions").resolve(resource)));
        assertTrue(matcher.find(), pattern);
        return matcher.group(1);
    }

    private static Result owned(String classpath) {
        return generate(workload("owned"), classpath, "owned");
    }

    private static Result additive(String classpath) {
        return generate(workload("additive"), classpath, "additive");
    }

    @Test
    void aChangedResourceNoLongerMatchesItsFileManifestDigest() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.extension.yaml", text -> text + "# changed\n", false);
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("runtimeconditions.extension.yaml differs from its file manifest digest"), result.err());
    }

    @Test
    void aResourceThatTheFileManifestDoesNotListIsRejected() throws IOException {
        Path owned = copyOf(OWNED);
        Path listing = owned.resolve("runtimeconditions/runtimeconditions.file-manifest.yaml");
        Files.writeString(listing, Files.readString(listing).replaceAll("(?m)^ {4}runtimeconditions.extension.yaml:.*\n", ""));
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("runtimeconditions.extension.yaml is not listed in the file manifest"), result.err());
    }

    @Test
    void aPackageWithoutTheFileManifestIsRejected() throws IOException {
        Path owned = copyOf(OWNED);
        Files.delete(owned.resolve("runtimeconditions/runtimeconditions.file-manifest.yaml"));
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("runtimeconditions.file-manifest.yaml is missing"), result.err());
    }

    @Test
    void aMissingResourceIsRejected() throws IOException {
        Path owned = copyOf(OWNED);
        Files.delete(owned.resolve("runtimeconditions/runtimeconditions.binding-model.yaml"));
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("runtimeconditions.binding-model.yaml is missing"), result.err());
    }

    // ---- the closure ---------------------------------------------------------------------------------------

    @Test
    void aDependencyWithoutABindingPackageFailsTheClosureCheck() {
        Result result = additive(pkg(ADDON).toString());
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("has no binding package on the classpath"), result.err());
    }

    @Test
    void aDependencyGeneratedFromAnotherDigestOfTheExtensionIsRejected() throws IOException {
        Path addon = copyOf(ADDON);
        String digest = valueOf(pkg(BASE), "runtimeconditions.bindings.yaml", "^extension:\\n  id: .*\\n  semanticSha256: ([0-9a-f]{64})");
        rewrite(addon, "runtimeconditions.binding-model.yaml", text -> text.replace(digest, "ab".repeat(32)), true);
        Result result = additive(classpath(addon, pkg(BASE)));
        assertEquals(1, result.code());
        assertTrue(result.err().contains("differs from the extension"), result.err());
    }

    @Test
    void twoEntriesThatProvideTheSameJavaPackageAreRejected() throws IOException {
        Result result = owned(classpath(pkg(OWNED), copyOf(OWNED)));
        assertEquals(1, result.code());
        assertTrue(result.err().contains("provide the Java package"), result.err());
    }

    @Test
    void aClasspathEntryThatDoesNotExistIsIgnored() {
        Result result = owned(classpath(pkg(OWNED), temp.resolve("missing.jar")));
        assertEquals(0, result.code(), result.err());
    }

    // ---- identity ------------------------------------------------------------------------------------------

    private static final String CORE_0_4_0 = """
            coreProfileSchema:
                id: https://runtimeconditions.io/schemas/profile/0.4.0/runtimeconditions.profile.schema.yaml
                version: 0.4.0
                semanticSha256: ed447dccefd7507d905b0b6177b1386d8ee96a1d053b731939a9a7ae973d5af1
            """;

    private static UnaryOperator<String> core(String version, String digest) {
        return text -> {
            assertTrue(text.contains(CORE_0_4_0), "the fixture model no longer names core 0.4.0 in the expected layout");
            return text.replace(CORE_0_4_0, "coreProfileSchema:\n    id: https://runtimeconditions.io/schemas/profile/" + version
                    + "/runtimeconditions.profile.schema.yaml\n    version: " + version + "\n    semanticSha256: " + digest + "\n");
        };
    }

    @Test
    void aPackageGeneratedAgainstAnotherCoreSchemaIsRejected() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.binding-model.yaml", core("0.3.0", "ab".repeat(32)), true);
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("generated against core profile schema 0.3.0"), result.err());
    }

    @Test
    void bindingsGeneratedAgainstTheApprovedCore020InputAreAccepted() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.binding-model.yaml", core("0.2.0", "a090a8016d045f9c3fa872a67f8df293b77ca2809a1bea5ae9fa31a27a06109a"), true);
        Result result = owned(owned.toString());
        assertEquals("", result.err());
        assertEquals(0, result.code());
    }

    @Test
    void core020WithAnyOtherDigestIsRejected() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.binding-model.yaml", core("0.2.0", "ab".repeat(32)), true);
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("generated against core profile schema 0.2.0"), result.err());
    }

    @Test
    void aManifestThatNamesAnotherLanguageIsRejected() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.bindings.yaml", text -> text.replace("language: java", "language: python"), true);
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("does not match its schema"), result.err());
    }

    @Test
    void aManifestAndAModelThatDisagreeOnTheModelDigestAreRejected() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.bindings.yaml",
                text -> text.replaceFirst("(?m)^(model:\\n  apiVersion: .*\\n  semanticSha256: )[0-9a-f]{64}", "$1" + "ab".repeat(32)), true);
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("disagree on the model digest"), result.err());
    }

    @Test
    void aManifestAndAModelThatDisagreeOnTheRootExtensionAreRejected() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.bindings.yaml",
                text -> text.replaceFirst("(?m)^(extension:\\n  id: .*\\n  semanticSha256: )[0-9a-f]{64}", "$1" + "cd".repeat(32)), true);
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("disagree on the root extension"), result.err());
    }

    @Test
    void anExtensionDefinitionWithAnotherIdIsRejected() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.extension.yaml", text -> text.replace("owned-kind-interface:1.0.0", "owned-kind-interface:9.9.9"), true);
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("extension definition id differs"), result.err());
    }

    @Test
    void aMalformedManifestIsRejectedWithItsLocation() throws IOException {
        Path owned = copyOf(OWNED);
        rewrite(owned, "runtimeconditions.bindings.yaml", text -> text + "\n: : : [\n", true);
        Result result = owned(owned.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("invalid YAML"), result.err());
    }

    // ---- jars ----------------------------------------------------------------------------------------------

    private Path jar(String name, String artifactId, String version) throws IOException {
        Path jar = temp.resolve(name + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            try (Stream<Path> files = Files.walk(pkg(name))) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    zip.putNextEntry(new ZipEntry(pkg(name).relativize(file).toString().replace('\\', '/')));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
            if (artifactId != null) {
                zip.putNextEntry(new ZipEntry("META-INF/maven/example.conformance/" + artifactId + "/pom.properties"));
                zip.write(("groupId=example.conformance\nartifactId=" + artifactId + "\nversion=" + version + "\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void packagesReadFromJarsGiveTheSameProfileAsDirectories() throws IOException {
        Result fromJars = additive(classpath(jar(ADDON, ADDON, "1.0.0"), jar(BASE, BASE, "1.0.0")));
        Result fromDirectories = additive(classpath(pkg(ADDON), pkg(BASE)));
        assertEquals("", fromJars.err());
        assertEquals(0, fromJars.code());
        assertEquals(fromDirectories.out(), fromJars.out());
    }

    @Test
    void aJarWhoseMavenCoordinatesDifferFromTheManifestIsRejected() throws IOException {
        Result result = owned(jar(OWNED, OWNED, "9.9.9").toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("differs from the jar's Maven coordinates"), result.err());
    }

    @Test
    void aJarWithoutMavenMetadataIsAccepted() throws IOException {
        Result result = owned(jar(OWNED, null, null).toString());
        assertEquals("", result.err());
        assertEquals(0, result.code());
    }
}
