package io.runtimeconditions.profiler;

import static io.runtimeconditions.profiler.TestSupport.expected;
import static io.runtimeconditions.profiler.TestSupport.generate;
import static io.runtimeconditions.profiler.TestSupport.packages;
import static io.runtimeconditions.profiler.TestSupport.workload;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.runtimeconditions.profiler.TestSupport.Result;
import java.io.IOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Each workload declares against the binding packages of one conformance case: a small, made-up extension
 * that exercises one shape of the binding model. The profiler has no knowledge of any of them, so the same
 * code that reads these reads every extension.
 */
class ConformanceTest {
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "owned|an owned kind and interface, and a condition field|owned-kind-interface",
            "additive|a field added by a second extension|additive-field,additive-field-dependency",
            "transitive|dependencies through an extension that declares only an interface type|transitive-closure,transitive-closure-middle,transitive-closure-leaf",
            "alternatives|object alternatives|object-alternatives",
            "union|a union of an object and a string|heterogeneous-union",
            "collections|lists, maps, and lists of objects|collections-and-maps",
            "recursive|a recursive type|recursive-reference",
            "scoped|values scoped to an interface type|scoped-domains-collisions",
            "names|Unicode names and names that are Java keywords|source-name-preservation",
            "dependencyonly|an extension that is only a dependency|dependency-schema-only,dependency-schema-only-dependency",
            "static-enum|enum constants imported statically|scoped-domains-collisions",
    })
    void theWorkloadBecomesTheReviewedProfile(String workload, String shape, String packages) throws IOException {
        Result result = generate(workload(workload), packages(packages.split(",")), workload);
        assertEquals("", result.err(), shape);
        assertEquals(0, result.code(), shape);
        assertEquals(expected(workload), result.out(), shape);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "owned|owned-kind-interface",
            "recursive|recursive-reference",
            "union|heterogeneous-union",
    })
    void aProfileIsIdenticalOnEveryRun(String workload, String packages) {
        assertEquals(
                generate(workload(workload), packages(packages.split(",")), workload).out(),
                generate(workload(workload), packages(packages.split(",")), workload).out());
    }
}
