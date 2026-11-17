package io.runtimeconditions.profiler.bindings;

import com.fasterxml.jackson.databind.JsonNode;
import io.runtimeconditions.profiler.core.ProfilerException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every verified binding package on the classpath, and the checks that tie them together. */
public final class Catalog {
    private final Map<String, Binding> byPath = new LinkedHashMap<>();
    private final Map<String, Binding> byJavaPackage = new LinkedHashMap<>();

    /** Loads each classpath entry that carries binding resources. */
    public static Catalog of(List<Path> classpath) {
        Catalog catalog = new Catalog();
        for (Path entry : classpath) {
            if (!java.nio.file.Files.exists(entry)) {
                continue;
            }
            ResourceRoot root = new ResourceRoot(entry);
            if (BindingLoader.isBinding(root)) {
                catalog.add(BindingLoader.load(root));
            }
        }
        catalog.verifyClosure();
        return catalog;
    }

    private void add(Binding binding) {
        Binding previous = byJavaPackage.get(binding.javaPackage());
        if (previous != null) {
            throw new ProfilerException("bindings", "two classpath entries provide the Java package " + binding.javaPackage()
                    + ": " + previous.root() + " and " + binding.root());
        }
        byPath.put(binding.root().toString(), binding);
        byJavaPackage.put(binding.javaPackage(), binding);
    }

    public List<Binding> all() {
        List<Binding> list = new ArrayList<>(byPath.values());
        list.sort(Comparator.comparing(Binding::coordinate));
        return list;
    }

    public Binding byJavaPackage(String name) {
        return byJavaPackage.get(name);
    }

    public Binding byExtension(String id) {
        for (Binding binding : byPath.values()) {
            if (binding.extensionId().equals(id)) {
                return binding;
            }
        }
        return null;
    }

    private void verifyClosure() {
        Map<String, Binding> extensions = new LinkedHashMap<>();
        for (Binding binding : byPath.values()) {
            Binding previous = extensions.put(binding.extensionId(), binding);
            if (previous != null) {
                throw new ProfilerException("bindings", "two binding packages provide the extension " + binding.extensionId()
                        + ": " + previous.describe() + " and " + binding.describe());
            }
        }
        for (Binding binding : byPath.values()) {
            for (JsonNode entry : binding.model().path("extensions")) {
                String id = entry.path("id").asText();
                if (id.equals(binding.extensionId())) {
                    continue;
                }
                Binding provider = extensions.get(id);
                if (provider == null) {
                    throw new ProfilerException("bindings", "the dependency " + id + " has no binding package on the classpath", binding.describe());
                }
                if (!provider.extensionSha256().equals(entry.path("semanticSha256").asText())) {
                    throw new ProfilerException("bindings", "the classpath's " + provider.describe() + " differs from the extension " + id
                            + " that " + binding.describe() + " was generated against");
                }
            }
        }
    }

    /** The bindings the given ones depend on, transitively, including themselves. */
    public List<Binding> closure(List<Binding> roots) {
        Map<String, Binding> seen = new LinkedHashMap<>();
        List<Binding> pending = new ArrayList<>(roots);
        while (!pending.isEmpty()) {
            Binding binding = pending.remove(0);
            if (seen.putIfAbsent(binding.root().toString(), binding) != null) {
                continue;
            }
            for (JsonNode entry : binding.model().path("extensions")) {
                Binding provider = byExtension(entry.path("id").asText());
                if (provider != null) {
                    pending.add(provider);
                }
            }
        }
        List<Binding> list = new ArrayList<>(seen.values());
        list.sort(Comparator.comparing(Binding::coordinate));
        return list;
    }
}
