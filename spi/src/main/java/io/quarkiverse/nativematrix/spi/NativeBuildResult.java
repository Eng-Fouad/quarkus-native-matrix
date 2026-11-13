package io.quarkiverse.nativematrix.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link NativeBuildRunner} produced for one target.
 *
 * @param executable the native executable, inside {@link NativeBuildRequest#outputDirectory()}
 * @param additionalFiles companion files that must be shipped with the executable (shared libraries...), may be empty
 * @param metadata free form information shown in the build summary and written to the manifest, e.g. a CI run URL
 */
public record NativeBuildResult(Path executable, List<Path> additionalFiles, Map<String, String> metadata) {

    public NativeBuildResult {
        Objects.requireNonNull(executable, "executable");
        additionalFiles = additionalFiles == null ? List.of() : List.copyOf(additionalFiles);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static NativeBuildResult of(Path executable) {
        return new NativeBuildResult(executable, List.of(), Map.of());
    }

    public NativeBuildResult withMetadata(String key, String value) {
        var copy = new java.util.LinkedHashMap<>(metadata);
        copy.put(key, value);
        return new NativeBuildResult(executable, additionalFiles, copy);
    }
}
