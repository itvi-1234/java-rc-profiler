package io.runtimeconditions.profiler.bindings;

import io.runtimeconditions.profiler.core.Json;
import io.runtimeconditions.profiler.core.ProfilerException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One classpath entry, a directory or a jar, from which package resources are read without loading any class. */
public final class ResourceRoot {
    private final Path path;
    private final boolean jar;

    public ResourceRoot(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.jar = Files.isRegularFile(this.path);
    }

    public Path path() {
        return path;
    }

    public boolean isJar() {
        return jar;
    }

    /** Returns the bytes of the resource, or null when the entry has no such resource. */
    public byte[] read(String resource) {
        try {
            if (jar) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    ZipEntry entry = zip.getEntry(resource);
                    if (entry == null || entry.isDirectory()) {
                        return null;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        return Json.readLimited(in, path + "!" + resource);
                    }
                }
            }
            Path file = path.resolve(resource).normalize();
            if (!file.startsWith(path) || !Files.isRegularFile(file)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file)) {
                return Json.readLimited(in, file.toString());
            }
        } catch (IOException e) {
            throw new ProfilerException("bindings", "cannot read " + resource + ": " + e.getMessage(), path.toString());
        }
    }

    public boolean has(String resource) {
        return read(resource) != null;
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
