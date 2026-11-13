package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The content of {@code target/native-sources}, as written by Quarkus when {@code quarkus.native.sources-only=true}:
 * the runner jar, {@code lib/}, {@code native-image.args}, {@code graalvm.version} and, for container builds,
 * {@code native-builder.image}.
 */
public record NativeSources(Path directory, List<String> args, String outputName, String runnerJar,
        Optional<String> graalVmVersion, Optional<String> builderImage) {

    public static final String ARGS_FILE = "native-image.args";

    public static NativeSources read(Path directory) throws IOException {
        Path argsFile = directory.resolve(ARGS_FILE);
        if (!Files.isRegularFile(argsFile)) {
            throw new IOException("No " + ARGS_FILE + " in " + directory
                    + ": the native sources were not generated. Build with quarkus.native.enabled=true and"
                    + " quarkus.native.sources-only=true");
        }
        List<String> args = tokenize(Files.readString(argsFile, StandardCharsets.UTF_8));
        String outputName = valueAfter(args, "-o")
                .orElseThrow(() -> new IOException("No -o option in " + argsFile));
        String jar = valueAfter(args, "-jar")
                .orElseThrow(() -> new IOException("No -jar option in " + argsFile));
        return new NativeSources(directory, List.copyOf(args), outputName, jar,
                readOptional(directory.resolve("graalvm.version")),
                readOptional(directory.resolve("native-builder.image")));
    }

    /**
     * Quarkus joins the arguments with single spaces (see {@code NativeImageBuildStep#nativeSourcesResult}), without any
     * quoting, so splitting on whitespace is the exact inverse operation.
     */
    static List<String> tokenize(String content) {
        List<String> args = new ArrayList<>();
        for (String token : content.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                args.add(token);
            }
        }
        return args;
    }

    private static Optional<String> valueAfter(List<String> args, String option) {
        int i = args.lastIndexOf(option);
        return i >= 0 && i + 1 < args.size() ? Optional.of(args.get(i + 1)) : Optional.empty();
    }

    private static Optional<String> readOptional(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String value = Files.readString(file, StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
