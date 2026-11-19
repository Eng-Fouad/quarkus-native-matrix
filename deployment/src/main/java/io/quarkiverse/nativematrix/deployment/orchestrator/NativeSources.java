package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.quarkus.deployment.pkg.builditem.NativeImageSourcesBuildItem;

/**
 * The native sources written by Quarkus to {@code target/native-sources} when {@code quarkus.native.sources-only=true}:
 * the directory holding the runner jar and {@code lib/}, the {@code native-image} arguments, the name of the executable,
 * the GraalVM version the arguments were generated for and, for container builds, the builder image.
 */
public record NativeSources(Path directory, List<String> args, String outputName, String runnerJar,
        Optional<String> graalVmVersion, Optional<String> builderImage) {

    public NativeSources withArgs(List<String> newArgs) {
        return new NativeSources(directory, List.copyOf(newArgs), outputName, runnerJar, graalVmVersion, builderImage);
    }

    public static NativeSources of(NativeImageSourcesBuildItem item) {
        return new NativeSources(item.getDirectory(), item.getNativeImageArgs(), item.getNativeImageName(),
                item.getRunnerJarName(), Optional.of(item.getGraalVMVersion()).filter(v -> !v.isBlank()),
                item.getBuilderImage());
    }
}
