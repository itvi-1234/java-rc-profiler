# Runtime Conditions Java profiler

The profiler turns declarations in a Java codebase into a validated
[Runtime Conditions](https://runtimeconditions.github.io/) Profile. Your code calls the
declaration functions of **generated binding packages on its classpath**. The profiler finds
those packages, verifies their resources, and reads your source **without compiling or running
it**.

It does for Java what the
[TypeScript profiler](https://github.com/runtimeconditions/ts-rc-profiler) and the
[Python profiler](https://github.com/runtimeconditions/python-rc-profiler) do for their languages.
The same declarations produce the same profile in every language.

Runtime Conditions is currently seeking adoption by an established parent project. The
repositories in this organization are split for hands-on usability, review, demos, and
implementation feedback. They are not intended to present Runtime Conditions as a standalone
foundation or competing project.

## Requirements

- A **JDK**, 17 or newer, to run the profiler. A JRE is not enough, because the profiler uses the JDK's parser
  (the jdk.compiler module) to read your source, and says so when it is missing. A runtime made with jlink that
  includes jdk.compiler also works: java.base, java.desktop, java.sql and jdk.compiler are all it needs.
- Generated Java binding packages for the extensions your code declares against, on the classpath.
  They are built by the
  [Java binding emitter](https://github.com/runtimeconditions/extensions/tree/main/tooling/extension-bindings/emitters/java)
  in `runtimeconditions/extensions`.

## Declare conditions in your workload

Use the API of the binding packages. Every argument of a declaration is a generated type, built with
`new`:

```java
import io.runtimeconditions.bindings.commonintegrations.Bindings;
import io.runtimeconditions.bindings.commonintegrations.KeyValue;
import io.runtimeconditions.bindings.commonintegrations.KeyValueEngine;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfiguration;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnv;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnvItem;
import io.runtimeconditions.bindings.envconfiguration.KeyValueConfigurationEnvItemProperty;
import java.util.List;

final class Sessions {
    static final Object CACHE = Bindings.cache(
            new KeyValue(KeyValueEngine.REDIS),
            new KeyValueConfiguration(
                    null,
                    new KeyValueConfigurationEnv(List.of(
                            new KeyValueConfigurationEnvItem("REDIS_URL", KeyValueConfigurationEnvItemProperty.URL, null, true)))));
}
```

The generated types are records, enums, and sealed interfaces. A record's constructor takes its
fields in order, and `null` leaves an optional field out.

The profiler must be able to determine each value statically. It follows:

- string, number, boolean, and `null` literals, and `+` between them;
- `final` fields, `final` locals, and interface constants, in the same class or another one, by simple
  name, qualified name, or static import;
- enum constants such as `Method.GET`;
- `List.of`, `Arrays.asList`, `Collections.emptyList`, `Map.of`, and `Map.ofEntries`;
- union variants such as `new RequestBodySchema.Variant2(...)`.

A value it cannot determine, such as a method call, a parameter, or a field that is not `final`, fails
with the file, line, and column. It never produces a partial condition. Sources under `src/test`,
build output (`target`, `build`, `out`), hidden directories, and symbolic links are not read.

## Generate a profile

```sh
java -jar runtimeconditions-java-profiler.jar profile generate \
  --project path/to/service \
  --classpath path/to/common-integrations.jar:path/to/env-configuration.jar \
  --name sessions \
  --workload-uri https://services.example.com/sessions \
  --workload-version 1.0.0 \
  --out runtimeconditions.profile.yaml
```

Without `--out` the profile is printed. A failed run writes nothing and leaves an existing output file
alone. `--json` turns a failure into one JSON object on standard error.

`--classpath` takes jars or directories separated by `:` (`;` on Windows). Instead, or as well,
`--resolve-build-classpath` asks the project's own build tool for its runtime classpath: Maven
(`./mvnw` or `mvn`) or Gradle (`./gradlew` or `gradle`). **That runs your build**, so it is only done
when you ask for it. The profiler itself never runs your code or the binding packages.

`profile verify-bindings` verifies the packages and lists the ones your project imports, with the
extensions they depend on marked as dependencies:

```sh
java -jar runtimeconditions-java-profiler.jar profile verify-bindings --project path/to/service --classpath ...
```

## What is verified

Before it reads any source, the profiler checks every binding package on the classpath:

- the package carries `runtimeconditions/runtimeconditions.bindings.yaml`,
  `runtimeconditions.binding-model.yaml`, `runtimeconditions.extension.yaml`, and a
  `runtimeconditions.file-manifest.yaml` that lists the SHA-256 of each; the digests must match;
- the manifest and the model match their schemas, the model was generated against the core profile
  schema this profiler bundles, and the manifest, model, and extension definition agree on the model
  digest, the root extension, and its identity;
- the package is for Java, and when it is a jar its Maven coordinates match the manifest;
- every extension a package was generated against has a binding package on the classpath, with the
  same digest.

The finished profile is then validated against the core profile schema and every extension schema in
the dependency closure. Invalid output is never written.

## Build and test

```sh
mvn verify
```

`target/runtimeconditions-java-profiler-<version>.jar` is a runnable jar with its dependencies.

The profiler knows no extension by name: it reads the declaration functions, types, and schemas from
the packages' manifests and models. Its tests are general in the same way. They use the **conformance
cases** of `runtimeconditions/extensions`, small made-up extensions that each exercise one shape of the
binding model: an owned kind and interface, an additive field, transitive dependencies, a recursive type, a
union, collections and maps, values scoped to an interface type, Unicode names, and an extension that is
only a dependency. `scripts/build-test-packages.py` builds the packages in `src/test/resources/packages`
from them with the normalizer and the Java binding emitter. They have the layout that
[`ci/generate-java.py`](https://github.com/runtimeconditions/extensions/blob/main/tooling/extension-bindings/ci/generate-java.py)
generates: the three resources and a file manifest in the `runtimeconditions` directory.

Each workload under `src/test/resources/workloads` declares against one case, and the profile it produces
is compared with the reviewed one in `src/test/resources/expected`.
