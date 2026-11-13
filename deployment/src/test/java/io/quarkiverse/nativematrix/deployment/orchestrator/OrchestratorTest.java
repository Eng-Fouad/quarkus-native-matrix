package io.quarkiverse.nativematrix.deployment.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;

class OrchestratorTest {

    @TempDir
    Path tmp;

    /** Writes the executable after an optional delay, or fails / hangs depending on its options. */
    static class ScriptedRunner implements NativeBuildRunner {
        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public boolean supports(Platform platform) {
            return platform.arch() == Platform.Arch.ARM64 || platform.os() == Platform.Os.WINDOWS
                    || platform.os() == Platform.Os.LINUX;
        }

        @Override
        public void validate(NativeBuildRequest request) {
            if (request.options().getBoolean("invalid", false)) {
                throw new IllegalArgumentException("bad option");
            }
        }

        @Override
        public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
            assertThat(Files.isDirectory(context.workDirectory())).isTrue();
            String behaviour = request.options().get("behaviour", "ok");
            switch (behaviour) {
                case "fail" -> throw new NativeBuildException("boom");
                case "hang" -> Thread.sleep(60_000);
                case "slow" -> Thread.sleep(300);
                default -> {
                }
            }
            Path exe = request.outputDirectory().resolve(request.executableName());
            Files.writeString(exe, String.join("\n", request.nativeImageArgs()));
            return NativeBuildResult.of(exe).withMetadata("where", "here");
        }
    }

    private NativeMatrixOrchestrator orchestrator(boolean failFast) {
        return new NativeMatrixOrchestrator(new RunnerRegistry(List.of(new ScriptedRunner())),
                new NativeMatrixOrchestrator.Settings(tmp.resolve("out"), 4, failFast, false, false));
    }

    private static MatrixTarget target(String name, String behaviour) {
        return new MatrixTarget(name, Platform.parse(name.replace("-slow", "")), "scripted",
                RunnerOptions.of(Map.of("behaviour", behaviour)), Duration.ofSeconds(5));
    }

    @Test
    void buildsAllTargetsAndWritesAManifest() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        var result = orchestrator(false).run(sources,
                List.of(target("linux-arm64", "ok"), target("windows-amd64", "slow")));

        assertThat(result.succeeded()).isTrue();
        Path windowsExe = tmp.resolve("out/windows-amd64/app-runner.exe");
        assertThat(windowsExe).exists();
        // heapdump is not supported on Windows
        assertThat(Files.readString(windowsExe)).contains("--enable-monitoring=threaddump")
                .doesNotContain("heapdump").contains("-o\noutput/app-runner");
        assertThat(Files.readString(tmp.resolve("out/linux-arm64/app-runner")))
                .contains("--enable-monitoring=heapdump,threaddump");
        String manifest = Files.readString(result.manifest());
        assertThat(manifest).contains("\"platform\" : \"windows-amd64\"", "\"sha256\"", "\"where\" : \"here\"",
                "\"executable\" : \"linux-arm64/app-runner\"");
        assertThat(tmp.resolve("out/.work/linux-arm64")).doesNotExist();
    }

    @Test
    void reportsFailuresWithoutStoppingOtherTargets() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        var result = orchestrator(false).run(sources,
                List.of(target("linux-arm64", "fail"), target("windows-amd64", "slow")));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.targets()).extracting(TargetResult::status)
                .containsExactly(TargetResult.Status.FAILED, TargetResult.Status.SUCCESS);
        assertThat(result.targets().get(0).error()).contains("boom");
    }

    @Test
    void failFastCancelsTheOtherTargets() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        long start = System.nanoTime();
        var result = orchestrator(true).run(sources, List.of(target("linux-arm64", "fail"),
                target("windows-amd64", "hang")));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        assertThat(result.targets()).extracting(TargetResult::status)
                .containsExactly(TargetResult.Status.FAILED, TargetResult.Status.CANCELLED);
    }

    @Test
    void enforcesTimeouts() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        MatrixTarget hanging = new MatrixTarget("linux-arm64", Platform.parse("linux-arm64"), "scripted",
                RunnerOptions.of(Map.of("behaviour", "hang")), Duration.ofMillis(300));
        var result = orchestrator(false).run(sources, List.of(hanging));
        assertThat(result.targets().get(0).status()).isEqualTo(TargetResult.Status.TIMED_OUT);
    }

    @Test
    void validatesEverythingBeforeBuilding() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        assertThatThrownBy(() -> orchestrator(false).run(sources, List.of(
                new MatrixTarget("a", Platform.parse("linux-arm64"), "nope", null, Duration.ofSeconds(1)),
                new MatrixTarget("b", Platform.parse("macos-amd64"), "scripted", null, Duration.ofSeconds(1)),
                new MatrixTarget("c", Platform.parse("linux-arm64"), "scripted",
                        RunnerOptions.of(Map.of("invalid", "true")), Duration.ofSeconds(1)))))
                .hasMessageContaining("a: unknown runner 'nope', available runners: scripted")
                .hasMessageContaining("b: the 'scripted' runner cannot build macos-amd64")
                .hasMessageContaining("c (scripted): bad option");
        assertThat(tmp.resolve("out/a")).doesNotExist();
    }

    @Test
    void readsNativeSources() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        assertThat(sources.outputName()).isEqualTo("app-runner");
        assertThat(sources.runnerJar()).isEqualTo("app-runner.jar");
        assertThat(sources.graalVmVersion()).contains("25.0");
        assertThat(sources.builderImage()).isEmpty();
        assertThat(sources.args()).contains("\\Qio.netty\\E");
        assertThatThrownBy(() -> NativeSources.read(tmp.resolve("missing")))
                .hasMessageContaining("quarkus.native.sources-only=true");
    }

    @Test
    void adjustsArgumentsPerPlatform() {
        List<String> args = List.of("--static", "--libc=musl", "--enable-monitoring=heapdump", "-o", "x");
        List<String> warnings = new java.util.ArrayList<>();
        assertThat(ArgsAdjuster.adjust(args, Platform.parse("macos-arm64"), tmp, warnings::add))
                .containsExactly("--enable-monitoring=heapdump", "-o", "x");
        assertThat(warnings).hasSize(2);
        assertThat(ArgsAdjuster.adjust(args, Platform.parse("windows-amd64"), tmp, w -> {
        })).containsExactly("-o", "x");
        assertThat(ArgsAdjuster.adjust(args, Platform.parse("linux-amd64"), tmp, w -> {
        })).isEqualTo(args);
    }

    @Test
    void sanitizesNames() {
        assertThat(NativeMatrixOrchestrator.sanitize("My Pi/ARM")).isEqualTo("my-pi-arm");
        assertThat(NativeMatrixOrchestrator.newBuildId()).matches("\\d{8}-\\d{6}-[0-9a-f]{6}");
    }
}
