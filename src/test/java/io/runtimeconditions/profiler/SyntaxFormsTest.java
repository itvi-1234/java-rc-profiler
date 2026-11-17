package io.runtimeconditions.profiler;

import static io.runtimeconditions.profiler.TestSupport.expected;
import static io.runtimeconditions.profiler.TestSupport.generate;
import static io.runtimeconditions.profiler.TestSupport.packages;
import static io.runtimeconditions.profiler.TestSupport.workload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.runtimeconditions.profiler.TestSupport.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The ways a value can be written in source, and the ones the profiler refuses. */
class SyntaxFormsTest {
    private static final String[] PACKAGES = {"owned-kind-interface", "collections-and-maps", "heterogeneous-union"};

    @Test
    void importsConstantsLocalsAndFactoriesAreFollowedWhicheverWayTheyAreWritten() throws IOException {
        Result result = generate(workload("syntax-forms"), packages(PACKAGES), "forms");
        assertEquals("", result.err());
        assertEquals(0, result.code());
        assertEquals(expected("syntax-forms"), result.out());
    }

    private static final String HEAD = """
            package t;

            import example.conformance.collectionsandmaps.*;
            import example.conformance.heterogeneousunion.TargetValue;
            import example.conformance.ownedkindinterface.*;
            import java.util.List;
            import java.util.Map;

            final class T {
                static String dynamic = "/x";
                static final String FROM_CALL = String.valueOf(1);
                final String instance = "/ok";

                void declare(String parameter, boolean flag) {
                    String notFinal = "/y";
                    BODY
                }
            }
            """;

    private static Result snippet(Path temp, String body) throws IOException {
        Path project = temp.resolve("p" + Math.abs(body.hashCode()));
        Files.createDirectories(project.resolve("src/main/java/t"));
        Files.writeString(project.resolve("src/main/java/t/T.java"), HEAD.replace("BODY", body));
        return generate(project, packages(PACKAGES), "t");
    }

    private static String service(String endpoint) {
        return "example.conformance.ownedkindinterface.Bindings.service(new Http(" + endpoint + "), new Region(\"eu\"));";
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "a non-final static field|dynamic|this value cannot be determined statically",
            "a method call|String.valueOf(1)|this value cannot be determined statically",
            "a parameter|parameter|this value cannot be determined statically",
            "a local that is not final|notFinal|this value cannot be determined statically",
            "a constant initialised by a call|FROM_CALL|this value cannot be determined statically",
            "a conditional|flag ? \"/a\" : \"/b\"|this value cannot be determined statically",
            "joining a null|\"/a\" + null|only strings, numbers, and booleans can be joined statically",
            "joining a list|\"/a\" + List.of(\"b\")|only strings, numbers, and booleans can be joined statically",
    })
    void anEndpointThatIsNotStaticIsRejected(String name, String endpoint, String message, @TempDir Path temp) throws IOException {
        Result result = snippet(temp, service(endpoint));
        assertEquals(1, result.code(), name);
        assertEquals("", result.out());
        assertTrue(result.err().contains(message), result.err());
    }

    @Test
    void aFieldWithTheWrongTypeOfValueIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, service("42"));
        assertEquals(1, result.code());
        assertTrue(result.err().contains("expected a String value"), result.err());
    }

    @Test
    void aStringWhereAGeneratedTypeIsExpectedIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.collection(new Items(), new Tags(new TagsValue(List.of(1))));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("expected a String value"), result.err());
    }

    @Test
    void theWrongNumberOfConstructorArgumentsIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "example.conformance.ownedkindinterface.Bindings.service(new Http(), new Region(\"eu\"));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("Http takes 1 argument(s) but 0 were given"), result.err());
    }

    @Test
    void aUnionConstructedWithoutAVariantIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "example.conformance.heterogeneousunion.Bindings.selector(new example.conformance.heterogeneousunion.InterfaceTarget(), "
                + "new example.conformance.heterogeneousunion.TargetTarget(new TargetValue()));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("is a union; construct one of its variants"), result.err());
    }

    @Test
    void aVariantThatTheUnionDoesNotHaveIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "example.conformance.heterogeneousunion.Bindings.selector(new example.conformance.heterogeneousunion.InterfaceTarget(), "
                + "new example.conformance.heterogeneousunion.TargetTarget(new TargetValue.Variant9(\"x\")));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("has no variant Variant9"), result.err());
    }

    @Test
    void anAnonymousSubclassOfAGeneratedTypeIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "example.conformance.ownedkindinterface.Bindings.service(new Http(\"x\") { });");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("anonymous subclass"), result.err());
    }

    @Test
    void aListThatIsNotWrittenWithAKnownFactoryIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.collection(new Items(), new Tags(new TagsValue(new java.util.ArrayList<>())));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("a list must be written with List.of"), result.err());
    }

    @Test
    void aMapThatIsNotWrittenWithAKnownFactoryIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.collection(new Items(), new Labels(new LabelsValue(new java.util.HashMap<>())));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("a map must be written with Map.of"), result.err());
    }

    @Test
    void aMapKeyThatIsNotAStringIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.collection(new Items(), new Labels(new LabelsValue(Map.of(1, \"v\"))));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("a map key must be a string"), result.err());
    }

    @Test
    void aSecondInterfaceInOneDeclarationIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "example.conformance.ownedkindinterface.Bindings.service(new Http(\"a\"), new Http(\"b\"));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("more than one interface"), result.err());
    }

    @Test
    void codeInTheSamePackageAsABindingNeedsNoImports(@TempDir Path temp) throws IOException {
        Path project = temp.resolve("p");
        Files.createDirectories(project.resolve("src/main/java/example/conformance/ownedkindinterface"));
        Files.writeString(project.resolve("src/main/java/example/conformance/ownedkindinterface/Same.java"),
                "package example.conformance.ownedkindinterface;\nfinal class Same { static final Object S = Bindings.service(new Http(\"https://example.com\"), new Region(\"eu\")); }\n");
        Result result = generate(project, packages("owned-kind-interface"), "same");
        assertEquals("", result.err());
        assertEquals(0, result.code());
        assertTrue(result.out().contains("endpoint: https://example.com"), result.out());
    }

    @Test
    void aClassWithTheSameNameAsAGeneratedOneIsNotMistakenForIt(@TempDir Path temp) throws IOException {
        Path project = temp.resolve("p");
        Files.createDirectories(project.resolve("src/main/java/t"));
        Files.writeString(project.resolve("src/main/java/t/Bindings.java"), "package t;\nfinal class Bindings { static void service(Object... x) {} }\n");
        Files.writeString(project.resolve("src/main/java/t/T.java"), "package t;\nfinal class T { void f() { Bindings.service(new Object()); } }\n");
        Result result = generate(project, packages(PACKAGES), "t");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("no declaration from a binding package"), result.err());
    }
}
