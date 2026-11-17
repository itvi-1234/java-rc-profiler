package io.runtimeconditions.profiler;

import static io.runtimeconditions.profiler.TestSupport.classpath;
import static io.runtimeconditions.profiler.TestSupport.generate;
import static io.runtimeconditions.profiler.TestSupport.pkg;
import static io.runtimeconditions.profiler.TestSupport.workload;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.runtimeconditions.profiler.TestSupport.Result;
import org.junit.jupiter.api.Test;

/** Packages emitted from the current catalog (extensions v1alpha2, core profile schema 0.4.0). */
class RealPackagesTest {
    @Test
    void anOrdersServiceDeclaresAnApiADatabaseAndACache() {
        Result result = generate(workload("orders-service"),
                classpath(pkg("common-integrations-v1alpha2"), pkg("env-configuration-v1alpha2")), "orders", "--workload-version", "2.4.0");
        assertEquals("", result.err());
        assertEquals(0, result.code());
        assertEquals("""
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsProfile
                metadata:
                  name: orders
                workload:
                  uri: https://services.example.com/orders
                  version: 2.4.0
                extensions:
                  - https://runtimeconditions.io/extensions/rc/common-integrations:v1alpha2
                  - https://runtimeconditions.io/extensions/rc/env-configuration:v1alpha2
                conditions:
                  - kind: api
                    interface:
                      type: http
                      operations:
                        - method: GET
                          path: /orders
                        - method: POST
                          path: /orders
                        - method: DELETE
                          path: /orders/{id}
                  - kind: datastore
                    interface:
                      type: relational
                      engine: postgres
                  - kind: cache
                    interface:
                      type: key_value
                      engine: redis
                    configuration:
                      env:
                        - name: REDIS_URL
                          property: url
                          required: true
                          sensitive: true
                """, result.out());
    }
}
