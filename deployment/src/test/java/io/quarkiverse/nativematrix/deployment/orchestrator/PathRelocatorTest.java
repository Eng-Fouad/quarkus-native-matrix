package io.quarkiverse.nativematrix.deployment.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.deployment.runners.LocalRunnerAccess;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;

class PathRelocatorTest {

    @TempDir
    Path tmp;

    @Test
    void shipsLocalFilesAndRewritesTheArguments() throws Exception {
        Path sources = TestSupport.nativeSources(tmp);
        Path agent = Files.createDirectories(tmp.resolve("agent-config"));
        Files.writeString(agent.resolve("reflect-config.json"), "[]");
        Path trustStore = Files.writeString(tmp.resolve("cacerts.p12"), "store");
        String missing = tmp.resolve("does-not-exist").toAbsolutePath().toString();
        List<String> args = List.of(
                "-H:ConfigurationFileDirectories=" + agent.toAbsolutePath() + "," + missing,
                "-J-Djavax.net.ssl.trustStore=" + trustStore.toAbsolutePath(),
                "-H:ConfigurationFileDirectories=" + agent.toAbsolutePath(), // same path twice: copied once
                "-H:ResourceConfigurationFiles=" + sources.resolve("lib/main/dep.jar").toAbsolutePath(),
                "--exclude-config", "\\Qio.netty\\E", "^/META-INF/native-image/.*",
                "-jar", "app-runner.jar");

        PathRelocator.Result r = PathRelocator.relocate(args, sources);

        assertThat(r.args()).containsExactly(
                "-H:ConfigurationFileDirectories=native-matrix-extra/1/agent-config," + missing,
                "-J-Djavax.net.ssl.trustStore=native-matrix-extra/2/cacerts.p12",
                "-H:ConfigurationFileDirectories=native-matrix-extra/1/agent-config",
                "-H:ResourceConfigurationFiles=lib/main/dep.jar", // inside the native sources: just relative
                "--exclude-config", "\\Qio.netty\\E", "^/META-INF/native-image/.*",
                "-jar", "app-runner.jar");
        assertThat(sources.resolve("native-matrix-extra/1/agent-config/reflect-config.json")).hasContent("[]");
        assertThat(sources.resolve("native-matrix-extra/2/cacerts.p12")).hasContent("store");
        assertThat(r.relocated()).hasSize(2);
        assertThat(r.unresolved()).containsExactly(args.get(0));

        // relocating again starts from a clean directory
        PathRelocator.relocate(List.of("-jar", "app-runner.jar"), sources);
        assertThat(sources.resolve("native-matrix-extra")).doesNotExist();
    }

    @Test
    void runnersReceivePortableArguments() throws Exception {
        Path sources = TestSupport.nativeSources(tmp);
        Path agent = Files.createDirectories(tmp.resolve("agent"));
        Files.writeString(sources.resolve("native-image.args"),
                "-H:ConfigurationFileDirectories=" + agent.toAbsolutePath().toString().replace('\\', '/')
                        + " -o app-runner -jar app-runner.jar");
        List<List<String>> seen = new ArrayList<>();
        NativeBuildRunner recording = new NativeBuildRunner() {
            @Override
            public String id() {
                return "rec";
            }

            @Override
            public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
                seen.add(request.nativeImageArgs());
                Path exe = request.outputDirectory().resolve(request.executableName());
                Files.writeString(exe, "x");
                return NativeBuildResult.of(exe);
            }
        };
        new NativeMatrixOrchestrator(new RunnerRegistry(List.of(recording)),
                new NativeMatrixOrchestrator.Settings(tmp.resolve("out"), 1, false, false, false))
                .run(NativeSources.read(sources), List.of(new MatrixTarget("linux-arm64", Platform.parse("linux-arm64"),
                        "rec", RunnerOptions.empty(), Duration.ofSeconds(10))));
        assertThat(seen.get(0)).contains("-H:ConfigurationFileDirectories=native-matrix-extra/1/agent");

        // the local runner, which runs outside of the native sources, makes them absolute again
        assertThat(LocalRunnerAccess.absolutize(List.of("-H:ConfigurationFileDirectories=native-matrix-extra/1/agent,x"),
                sources)).singleElement().asString()
                .startsWith("-H:ConfigurationFileDirectories=" + sources.toAbsolutePath().toString().replace('\\', '/'))
                .endsWith("/native-matrix-extra/1/agent,x");
    }
}
