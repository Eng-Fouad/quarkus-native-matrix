package io.quarkiverse.nativematrix.deployment.runners;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.Processes;

/**
 * Exercises the Docker runner against a real engine with a tiny fake builder image (Alpine + a shell script standing in
 * for native-image). Opt-in: {@code mvn verify -Dnative-matrix.it.docker=true}.
 */
@EnabledIfSystemProperty(named = "native-matrix.it.docker", matches = "true")
class DockerRunnerIT {

    static final String IMAGE = "quarkus-native-matrix-fake-builder:test";

    @TempDir
    Path tmp;

    @Test
    void buildsInAContainer() throws Exception {
        Path context = Files.createDirectories(tmp.resolve("image"));
        Files.writeString(context.resolve("native-image"), """
                #!/bin/sh
                set -e
                args="${1#@}"
                out=$(awk 'p { print; exit } $0 == "-o" { p = 1 }' "$args")
                mkdir -p "$(dirname "$out")"
                echo "linux executable built by $(id -un) on $(uname -m)" > "$out"
                echo "fake native-image wrote $out"
                """);
        Files.writeString(context.resolve("Dockerfile"), """
                FROM alpine:3.20
                COPY native-image /usr/local/bin/native-image
                RUN chmod +x /usr/local/bin/native-image && mkdir -p /project
                WORKDIR /project
                """);
        Processes.Result built = Processes.run(List.of("docker", "build", "-t", IMAGE, context.toString()), null, Map.of(),
                BuildLog.console("docker-build"));
        assertThat(built.exitCode()).isZero();

        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        NativeBuildRequest request = NativeBuildRequest.builder()
                .buildId("it").targetName("linux-amd64").platform(Platform.parse("linux-amd64"))
                .nativeSourcesDirectory(sources.directory()).outputName(sources.outputName())
                .nativeImageArgs(sources.args())
                .options(RunnerOptions.of(Map.of("image", IMAGE, "pull", "never")))
                .outputDirectory(Files.createDirectories(tmp.resolve("out")))
                .build();
        var result = new DockerRunner().build(request, TestSupport.context(Files.createDirectories(tmp.resolve("work"))));

        assertThat(result.executable()).content().startsWith("linux executable built by");
        assertThat(Processes.captureOrFail(List.of("docker", "ps", "-a", "--filter", "name=native-matrix-it-",
                "--format", "{{.Names}}"), null)).isEmpty();
        assertThat(Processes.captureOrFail(List.of("docker", "volume", "ls", "--filter", "name=native-matrix-it-",
                "--format", "{{.Name}}"), null)).isEmpty();
    }
}
