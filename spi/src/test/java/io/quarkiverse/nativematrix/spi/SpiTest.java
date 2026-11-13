package io.quarkiverse.nativematrix.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.nativematrix.spi.support.ArgFile;

class SpiTest {

    @Test
    void parsesPlatforms() {
        assertThat(Platform.parse("linux-amd64")).isEqualTo(new Platform(Platform.Os.LINUX, Platform.Arch.AMD64));
        assertThat(Platform.parse("darwin/aarch64")).isEqualTo(new Platform(Platform.Os.MACOS, Platform.Arch.ARM64));
        assertThat(Platform.parse("windows_x86_64")).isEqualTo(new Platform(Platform.Os.WINDOWS, Platform.Arch.AMD64));
        assertThat(Platform.parse("macos-arm64").id()).isEqualTo("macos-arm64");
        assertThat(Platform.parse("linux-arm64").containerPlatform()).isEqualTo("linux/arm64");
        assertThat(Platform.parse("windows-arm64").executableName("app")).isEqualTo("app.exe");
        assertThat(Platform.tryParse("my-raspberry-pi")).isEmpty();
        assertThatThrownBy(() -> Platform.parse("solaris-sparc")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Platform.host()).isNotNull();
    }

    @Test
    void readsTypedOptions() {
        RunnerOptions o = RunnerOptions.of(Map.of("a", "1", "b", "yes", "d", "5m", "l", "x, y,,z", "blank", " "))
                .with(Map.of("a", "2"));
        assertThat(o.getInt("a", 0)).isEqualTo(2);
        assertThat(o.getBoolean("b", false)).isTrue();
        assertThat(o.getDuration("d", null)).isEqualTo(Duration.ofMinutes(5));
        assertThat(o.getDuration("missing", Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(1));
        assertThat(RunnerOptions.parseDuration("k", "PT2H")).isEqualTo(Duration.ofHours(2));
        assertThat(RunnerOptions.parseDuration("k", "250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(RunnerOptions.parseDuration("k", "30")).isEqualTo(Duration.ofSeconds(30));
        assertThat(o.getList("l")).containsExactly("x", "y", "z");
        assertThat(o.get("blank")).isEmpty();
        assertThatThrownBy(() -> o.require("nope")).hasMessageContaining("nope");
        assertThatThrownBy(() -> o.getDuration("b", null)).hasMessageContaining("duration");
        assertThat(o.toString()).doesNotContain("yes");
    }

    @Test
    void replacesTheOutputPath() {
        NativeBuildRequest request = NativeBuildRequest.builder()
                .buildId("id").targetName("t").platform(Platform.parse("windows-amd64"))
                .nativeSourcesDirectory(Path.of("src")).outputDirectory(Path.of("out"))
                .outputName("app-runner")
                .nativeImageArgs(List.of("-J-Dx=y", "-o", "app-runner", "-jar", "app-runner.jar"))
                .build();
        assertThat(request.nativeImageArgs())
                .containsExactly("-J-Dx=y", "-o", "output/app-runner", "-jar", "app-runner.jar");
        assertThat(request.executableName()).isEqualTo("app-runner.exe");

        NativeBuildRequest noOutput = request.toBuilder().nativeImageArgs(List.of("-jar", "a.jar")).build();
        assertThat(noOutput.nativeImageArgs("x/y")).containsExactly("-o", "x/y", "-jar", "a.jar");
    }

    @Test
    void quotesArgumentFiles() {
        String content = ArgFile.render(List.of("--exclude-config", "\\Qio.netty\\E", "^/META-INF/x.*",
                "-J-Dmsg=hello world", "it's", "#notacomment", "", "C:\\path with space\\a.jar"));
        assertThat(content.split("\n")).containsExactly(
                "--exclude-config",
                "\\Qio.netty\\E",
                "^/META-INF/x.*",
                "\"-J-Dmsg=hello world\"",
                "\"it's\"",
                "\"#notacomment\"",
                "\"\"",
                "\"C:\\\\path with space\\\\a.jar\"");
    }
}
