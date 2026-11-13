package io.quarkiverse.nativematrix.deployment.runners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;

class LocalRunnerTest {

    @TempDir
    Path tmp;

    private NativeBuildRequest request(List<String> extraArgs) throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        List<String> args = new ArrayList<>(extraArgs);
        args.addAll(sources.args());
        return NativeBuildRequest.builder()
                .buildId("b1").targetName("host").platform(Platform.host())
                .nativeSourcesDirectory(sources.directory()).outputName(sources.outputName())
                .nativeImageArgs(args)
                .options(RunnerOptions.of(Map.of("command", String.join(",", TestSupport.fakeNativeImageCommand()))))
                .outputDirectory(Files.createDirectories(tmp.resolve("out")))
                .build();
    }

    @Test
    void runsNativeImageThroughAnArgumentFile() throws Exception {
        NativeBuildRequest request = request(List.of("-J-Dmessage=with space"));
        Path work = Files.createDirectories(tmp.resolve("work"));
        var result = new LocalRunner().build(request, TestSupport.context(work));

        assertThat(result.executable()).isEqualTo(tmp.resolve("out").resolve(request.executableName()));
        assertThat(result.executable()).hasContent("fake executable");
        // the java launcher parsed our argument file: every argument survived unchanged
        List<String> seen = Files.readAllLines(Path.of(result.executable().toString()
                .replaceAll("\\.exe$", "") + ".args"));
        assertThat(seen).contains("-J-Dmessage=with space", "\\Qio.netty\\E", "^/META-INF/native-image/.*");
        assertThat(seen.get(seen.indexOf("-jar") + 1)).endsWith("native-sources/app-runner.jar");
        // nothing written into the shared native sources
        assertThat(tmp.resolve("native-sources/output")).doesNotExist();
    }

    @Test
    void reportsNativeImageFailures() throws Exception {
        NativeBuildRequest request = request(List.of("--fail"));
        assertThatThrownBy(() -> new LocalRunner().build(request,
                TestSupport.context(Files.createDirectories(tmp.resolve("work")))))
                .hasMessageContaining("exit code 1");
    }

    @Test
    void onlySupportsTheHostPlatform() {
        LocalRunner runner = new LocalRunner();
        assertThat(runner.supports(Platform.host())).isTrue();
        Platform other = new Platform(Platform.host().os() == Platform.Os.LINUX ? Platform.Os.WINDOWS : Platform.Os.LINUX,
                Platform.host().arch());
        assertThat(runner.supports(other)).isFalse();
    }
}
