package io.quarkiverse.nativematrix.deployment.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildMode;
import io.quarkiverse.nativematrix.spi.FullBuild;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;

/**
 * Platform dependency check, GraalVM version check, build modes, reuse of unchanged targets and packaging.
 */
class MatrixFeaturesTest {

    static final Platform WINDOWS = Platform.parse("windows-amd64");
    static final Platform LINUX = Platform.parse("linux-amd64");
    static final Platform MAC_ARM = Platform.parse("macos-arm64");

    @TempDir
    Path tmp;

    /** Reports a configurable GraalVM version, counts builds, supports both modes. */
    static class CountingRunner implements NativeBuildRunner {
        final AtomicInteger builds = new AtomicInteger();
        volatile String version = TestSupport.MANDREL_VERSION_OUTPUT;
        volatile boolean full = true;

        @Override
        public String id() {
            return "counting";
        }

        @Override
        public boolean supports(BuildMode mode) {
            return mode == BuildMode.SOURCES || full;
        }

        @Override
        public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
            builds.incrementAndGet();
            context.reportNativeImageVersion(version);
            Path exe = request.outputDirectory().resolve(request.executableName());
            Files.writeString(exe, request.mode() + " " + request.fullBuild().map(FullBuild::posixCommand).orElse(""));
            Path lib = request.outputDirectory().resolve("libawt.so");
            Files.writeString(lib, "lib");
            return new NativeBuildResult(exe, List.of(lib), Map.of());
        }
    }

    private final CountingRunner runner = new CountingRunner();

    private NativeMatrixOrchestrator orchestrator(CheckPolicy platformCheck, CheckPolicy versionCheck, boolean reuse,
            List<String> ignored) {
        return new NativeMatrixOrchestrator(new RunnerRegistry(List.of(runner)),
                new NativeMatrixOrchestrator.Settings(tmp.resolve("out"), 4, false, false, false, WINDOWS,
                        platformCheck, ignored, versionCheck, reuse));
    }

    private static MatrixTarget target(Platform platform, BuildMode mode) {
        return new MatrixTarget(platform.id(), platform, "counting", RunnerOptions.empty(), Duration.ofSeconds(30), mode);
    }

    private NativeSources sourcesWithJavaFxForWindows() throws Exception {
        Path dir = TestSupport.nativeSources(tmp);
        Files.writeString(dir.resolve("lib/main/org.openjfx.javafx-graphics-25-win.jar"), "fx");
        Files.writeString(dir.resolve("lib/main/org.openjfx.javafx-base-25-win.jar"), "fx");
        // bundled on purpose for several platforms: never a problem
        Files.writeString(dir.resolve("lib/main/org.lwjgl.lwjgl-3.3.3-natives-windows.jar"), "x");
        Files.writeString(dir.resolve("lib/main/org.lwjgl.lwjgl-3.3.3-natives-linux.jar"), "x");
        return NativeSources.read(dir);
    }

    @Test
    void detectsPlatformSpecificJars() {
        assertThat(PlatformDependencies.parse("org.openjfx.javafx-graphics-21.0.2-mac-aarch64.jar")).hasValueSatisfying(j -> {
            assertThat(j.os()).isEqualTo(Platform.Os.MACOS);
            assertThat(j.arch()).contains(Platform.Arch.ARM64);
            assertThat(j.group()).isEqualTo("org.openjfx.javafx-graphics-21.0.2");
        });
        assertThat(PlatformDependencies.parse("io.netty.netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar"))
                .hasValueSatisfying(j -> assertThat(j.arch()).contains(Platform.Arch.AMD64));
        assertThat(PlatformDependencies.parse("io.netty.netty-transport-native-kqueue-4.1.100.Final-osx-aarch_64.jar"))
                .hasValueSatisfying(j -> assertThat(j.os()).isEqualTo(Platform.Os.MACOS));
        assertThat(PlatformDependencies.parse("org.lwjgl.lwjgl-3.3.3-natives-windows.jar"))
                .hasValueSatisfying(j -> assertThat(j.arch()).isEmpty());
        assertThat(PlatformDependencies.parse("com.example.linux-utils-1.0.jar")).isEmpty();
        assertThat(PlatformDependencies.parse("io.quarkus.quarkus-core-3.40.1.jar")).isEmpty();
    }

    @Test
    void failsSourcesTargetsThatWouldGetJarsOfTheBuildMachine() throws Exception {
        NativeSources sources = sourcesWithJavaFxForWindows();
        assertThatThrownBy(() -> orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.SOURCES), target(WINDOWS, BuildMode.SOURCES))))
                .hasMessageContaining("linux-amd64: the native sources were produced on windows-amd64")
                .hasMessageContaining("org.openjfx.javafx-graphics-25-win.jar")
                .hasMessageContaining("mode=full")
                .hasMessageNotContaining("windows-amd64: the native")
                .hasMessageNotContaining("lwjgl");
        assertThat(runner.builds).hasValue(0);
    }

    @Test
    void platformDependencyCheckCanBeRelaxed() throws Exception {
        NativeSources sources = sourcesWithJavaFxForWindows();
        var warned = orchestrator(CheckPolicy.WARN, CheckPolicy.WARN, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.SOURCES)));
        assertThat(warned.succeeded()).isTrue();
        var ignored = orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of("*javafx*"))
                .run(sources, List.of(target(LINUX, BuildMode.SOURCES)));
        assertThat(ignored.succeeded()).isTrue();
    }

    @Test
    void fullModeTargetsSkipThePlatformDependencyCheck() throws Exception {
        NativeSources sources = sourcesWithJavaFxForWindows();
        FullBuild fullBuild = new FullBuild(tmp, "sh ./mvnw package", "call mvnw.cmd package", "target", Set.of());
        var result = orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.FULL)), fullBuild);
        assertThat(result.succeeded()).isTrue();
        assertThat(tmp.resolve("out/linux-amd64/app-runner")).hasContent("FULL sh ./mvnw package");
        assertThat(Files.readString(result.manifest())).contains("\"mode\" : \"full\"");
    }

    @Test
    void rejectsModesARunnerDoesNotSupport() throws Exception {
        runner.full = false;
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        FullBuild fullBuild = new FullBuild(tmp, "a", "b", "target", Set.of());
        assertThatThrownBy(() -> orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.FULL)), fullBuild))
                .hasMessageContaining("the 'counting' runner does not support mode=full");
        runner.full = true;
        assertThatThrownBy(() -> orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.FULL)), null))
                .hasMessageContaining("full-build.project-directory");
    }

    @Test
    void checksTheGraalVmVersionOfBuilders() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp)); // graalvm.version = 25.0
        var ok = orchestrator(CheckPolicy.FAIL, CheckPolicy.FAIL, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.SOURCES)));
        assertThat(ok.succeeded()).isTrue();
        assertThat(ok.targets().get(0).result().orElseThrow().metadata()).containsEntry("graalvm", "25.0");

        runner.version = TestSupport.MANDREL_VERSION_OUTPUT.replace("25.0.1", "24.2.1");
        var failed = orchestrator(CheckPolicy.FAIL, CheckPolicy.FAIL, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.SOURCES)));
        assertThat(failed.targets().get(0).error()).hasValueSatisfying(e -> assertThat(e)
                .contains("generated for GraalVM 25.0 but the builder runs GraalVM 24.2"));
        var warned = orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.SOURCES)));
        assertThat(warned.succeeded()).isTrue();
        assertThat(GraalVmVersions.majorMinor("25.0.4")).contains("25.0");
        assertThat(GraalVmVersions.parse("garbage")).isEmpty();
    }

    @Test
    void reusesUnchangedTargets() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        List<MatrixTarget> targets = List.of(target(LINUX, BuildMode.SOURCES), target(MAC_ARM, BuildMode.SOURCES));
        assertThat(orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, true, List.of()).run(sources, targets).succeeded())
                .isTrue();
        assertThat(runner.builds).hasValue(2);

        // same inputs: nothing is rebuilt
        var second = orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, true, List.of()).run(sources, targets);
        assertThat(runner.builds).hasValue(2);
        assertThat(second.targets()).extracting(TargetResult::status).containsOnly(TargetResult.Status.REUSED);
        assertThat(second.succeeded()).isTrue();

        // a tampered executable or changed native sources trigger a rebuild
        Files.writeString(tmp.resolve("out/linux-amd64/app-runner"), "tampered");
        orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, true, List.of()).run(sources, targets);
        assertThat(runner.builds).hasValue(3);
        Files.writeString(sources.directory().resolve("lib/main/dep.jar"), "dep v2");
        orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, true, List.of()).run(sources, targets);
        assertThat(runner.builds).hasValue(5);
        // disabled: always rebuilt
        orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of()).run(sources, targets);
        assertThat(runner.builds).hasValue(7);
    }

    @Test
    void packagesArchivesAndAJReleaserConfiguration() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        var result = orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of())
                .run(sources, List.of(target(LINUX, BuildMode.SOURCES), target(WINDOWS, BuildMode.SOURCES)));
        Path matrix = tmp.resolve("out");
        var packages = Packager.archive(result, matrix, "app");
        assertThat(packages.packages()).extracting(p -> p.archive().getFileName().toString())
                .containsExactly("app-linux-x86_64.tar.gz", "app-windows-x86_64.zip");
        assertThat(Packager.jreleaserPlatform(MAC_ARM)).isEqualTo("osx-aarch_64");

        // two targets of the same platform: no overwrite, only the first one is distributed
        var twice = orchestrator(CheckPolicy.FAIL, CheckPolicy.WARN, false, List.of()).run(sources, List.of(
                target(LINUX, BuildMode.SOURCES),
                new MatrixTarget("linux-again", LINUX, "counting", RunnerOptions.empty(), Duration.ofSeconds(30))));
        var both = Packager.archive(twice, matrix, "app");
        assertThat(both.packages()).extracting(p -> p.archive().getFileName().toString() + "/" + p.distributed())
                .containsExactlyInAnyOrder("app-linux-x86_64.tar.gz/true", "app-linux-x86_64-linux-again.tar.gz/false");
        assertThat(Packager.writeJReleaserConfig(both, matrix, "app")).content()
                .doesNotContain("linux-again");
        packages = Packager.archive(result, matrix, "app");

        Path jreleaser = Packager.writeJReleaserConfig(packages, matrix, "app");
        assertThat(jreleaser).content().contains("type: BINARY", "platform: linux-x86_64",
                "out/dist/app-windows-x86_64.zip");

        // the tar keeps the executable bit, readable by the system tar when present
        Optional<Path> tar = io.quarkiverse.nativematrix.spi.support.Processes.findExecutable("tar", List.of());
        if (tar.isPresent()) {
            var listing = io.quarkiverse.nativematrix.spi.support.Processes.captureOrFail(
                    // relative path: GNU tar reads "C:" as a remote host
                    List.of(tar.get().toString(), "-tvzf", "app-linux-x86_64.tar.gz"), matrix.resolve("dist"));
            assertThat(listing).contains("app-linux-x86_64/app").contains("rwxr-xr-x").contains("libawt.so");
        }
    }
}
