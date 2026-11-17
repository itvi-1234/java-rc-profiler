package io.runtimeconditions.profiler;

import static io.runtimeconditions.profiler.TestSupport.generate;
import static io.runtimeconditions.profiler.TestSupport.pkg;
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

class SyntaxFormsTest {
    @Test
    void importsConstantsLocalsAndUnionsAreFollowedWhicheverWayTheyAreWritten() {
        Result result = generate(workload("syntax-forms"), pkg("common-integrations").toString(), "forms");
        assertEquals("", result.err());
        assertEquals(0, result.code());
        assertEquals("""
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsProfile
                metadata:
                  name: forms
                workload:
                  uri: https://services.example.com/forms
                extensions:
                  - https://runtimeconditions.io/extensions/common-integrations:v1alpha1
                conditions:
                  - kind: api
                    interface:
                      type: http
                      operations:
                        - method: GET
                          path: /health
                        - method: PUT
                          path: /v1/local
                        - method: PATCH
                          path: /api/instance
                        - method: POST
                          path: /items
                          requestBodySchema:
                            id: string
                            note: string
                          responseSchema: integer
                      spec:
                        format: openapi
                        uri: https://example.com/openapi.json
                """, result.out());
    }

    private static final String HEAD = """
            package t;

            import io.runtimeconditions.bindings.commonintegrations.*;
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
        return generate(project, pkg("common-integrations").toString(), "t");
    }

    private static String api(String path) {
        return "Bindings.api(new Http(new Operations(List.of(new OperationsItem(Method.GET, " + path + ", null, null))), null));";
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
    void aPathThatIsNotStaticIsRejected(String name, String path, String message, @TempDir Path temp) throws IOException {
        Result result = snippet(temp, api(path));
        assertEquals(1, result.code(), name);
        assertEquals("", result.out());
        assertTrue(result.err().contains(message), result.err());
    }

    @Test
    void aMethodWithTheWrongTypeOfValueIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(new Operations(List.of(new OperationsItem(Method.GET, 42, null, null))), null));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("expected a String value"), result.err());
    }

    @Test
    void anEnumValueThatIsNotAConstantOfTheEnumIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(new Operations(List.of(new OperationsItem(Method.FETCH, \"/a\", null, null))), null));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("Method has no constant FETCH"), result.err());
    }

    @Test
    void aStringWhereAnEnumIsExpectedIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(new Operations(List.of(new OperationsItem(\"GET\", \"/a\", null, null))), null));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("expected a constant of Method"), result.err());
    }

    @Test
    void aRequiredFieldThatIsNullIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(new Operations(List.of(new OperationsItem(Method.GET, null, null, null))), null));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("OperationsItem is missing the required field path"), result.err());
    }

    @Test
    void theWrongNumberOfConstructorArgumentsIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(new Operations(List.of(new OperationsItem(Method.GET, \"/a\"))), null));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("OperationsItem takes 4 argument(s) but 2 were given"), result.err());
    }

    @Test
    void aUnionConstructedWithoutAVariantIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(new Operations(List.of(new OperationsItem(Method.GET, \"/a\", new RequestBodySchema(), null))), null));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("is a union; construct one of its variants"), result.err());
    }

    @Test
    void anAnonymousSubclassOfAGeneratedTypeIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(null, null) { });");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("anonymous subclass"), result.err());
    }

    @Test
    void aListThatIsNotWrittenWithAKnownFactoryIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(new Operations(new java.util.ArrayList<>()), null));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("a list must be written with List.of"), result.err());
    }

    @Test
    void aFieldDeclaredTwiceIsRejected(@TempDir Path temp) throws IOException {
        Result result = snippet(temp, "Bindings.api(new Http(null, null), new Http(null, null));");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("more than one interface"), result.err());
    }

    @Test
    void aDeclarationFromAnotherClassWithTheSameNameIsNotMistakenForAGeneratedOne(@TempDir Path temp) throws IOException {
        Path project = temp.resolve("p");
        Files.createDirectories(project.resolve("src/main/java/t"));
        Files.writeString(project.resolve("src/main/java/t/Bindings.java"), "package t;\nfinal class Bindings { static void api(Object... x) {} }\n");
        Files.writeString(project.resolve("src/main/java/t/T.java"), "package t;\nfinal class T { void f() { Bindings.api(new Object()); } }\n");
        Result result = generate(project, pkg("common-integrations").toString(), "t");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("no declaration from a binding package"), result.err());
    }
}
